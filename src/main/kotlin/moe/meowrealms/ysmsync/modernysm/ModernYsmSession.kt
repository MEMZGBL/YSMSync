package moe.meowrealms.ysmsync.modernysm

import io.netty.buffer.Unpooled
import moe.meowrealms.ysmsync.YSMSyncMain
import rip.ysm.security.YSMByteBuf
import rip.ysm.security.YsmCrypt
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID

/**
 * 模型目录条目，对应 ModernYSM Packet 03 中每个模型的字段。
 *
 * @param modelId 模型标识（如 `default`、`misc/2_steve`、`custom/xxx`）
 * @param hash1 服务端密钥参与计算的第一段哈希
 * @param hash2 服务端密钥参与计算的第二段哈希
 * @param isAuth 是否需要授权
 * @param isCustomSkinModel 是否为内置皮肤模型（misc/1_alex、misc/2_steve）
 * @param version 模型版本号；未加密的文件夹模型为 65535
 */
data class ModelCatalogEntry(
    val modelId: String,
    val hash1: Long,
    val hash2: Long,
    val isAuth: Boolean,
    val isCustomSkinModel: Boolean,
    val version: Int,
)

/**
 * 模型数据来源。由 ModelManager 实现，把 YSMSync 已有的模型缓存
 * （`encryptServerCache` 产物，文件名即 `%016x%016x`）直接喂给协议层。
 */
interface ModernYsmModelSource {
    /** 服务端密钥（56 字节），与生成缓存时使用的密钥完全一致。 */
    fun serverKey(): ByteArray

    /** 客户端缓存密钥（56 字节），OpenYSM 使用固定种子生成。 */
    fun clientKey(): ByteArray

    /** 当前可下发的模型目录。 */
    fun catalog(): Collection<ModelCatalogEntry>

    /**
     * 读取模型缓存文件内容。
     *
     * 返回值必须是 `YsmCrypt.encryptServerCache(...)` 的结果 —— 也就是
     * YSMSync 缓存目录里的原始文件字节。客户端会用 `serverKey` 做
     * `transcodeServerDataToClientCache` 转码，因此这里不能额外加解密。
     *
     * @return 缓存文件字节；模型不存在时返回 null
     */
    fun modelCacheBytes(hash1: Long, hash2: Long): ByteArray?
}

/**
 * 单个玩家的 ModernYSM 同步会话。
 *
 * 状态机：
 * ```
 * step 0  已发送 51 VersionCheck，等待 C2S 52
 * step 1  已发送加密 Packet 01（公钥），等待 C2S Pong(0x02)
 * step 2  已发送 Packet 03（目录），等待 C2S Packet 04（请求模型）
 * step 3  正在/已下发模型分块
 * ```
 *
 * 注意：所有方法都必须在玩家所属的区域线程上调用。
 */
class ModernYsmSession(
    private val playerId: UUID,
    private val source: ModernYsmModelSource,
) {

    companion object {
        private val RANDOM = SecureRandom()

        /** 生成与服务端一致的客户端缓存密钥（OpenYSM 用固定种子）。 */
        fun createClientKey(): ByteArray {
            val key = ByteArray(ModernYsmProtocol.KEY_LENGTH)
            java.util.Random(ModernYsmProtocol.CLIENT_KEY_SEED).nextBytes(key)
            return key
        }
    }

    /** 当前状态机阶段。 */
    var step: Int = 0
        private set

    /** 服务端参与本次会话的密钥，由 Packet 01 的 encrypt 产生。 */
    private var key1: ByteArray? = null

    /** 客户端回 Pong 后带回来的下一把密钥。 */
    private var clientNextKey: ByteArray? = null

    private val clientKey: ByteArray = createClientKey()

    /** 待发送的模型分块队列。 */
    private val transferQueue: ArrayDeque<ModelTransfer> = ArrayDeque()

    /** 当前正在传输的模型。 */
    private var currentTransfer: ModelTransfer? = null

    /** 客户端是否已完成握手（收到过 Pong）。 */
    val isHandshakeConfirmed: Boolean
        get() = step >= 2

    /**
     * 构造 S2C 51 VersionCheck 的负载。
     *
     * ModernYSM 客户端的解码逻辑：
     * ```
     * version = readUtf()
     * if (readableBytes() > 0) {
     *     brand = readUtf()
     *     // "open_ysm:v1" 或 "open_ysm:model_sync_fragments_v1"
     * }
     * ```
     * 这里只发版本号，不使用分片品牌 —— 保持与官方服务端默认行为一致。
     */
    fun buildVersionCheckPayload(): ByteArray {
        val buf = Unpooled.buffer()
        try {
            buf.writeByte(ModernYsmProtocol.Packets.S2C_VERSION_CHECK)
            writeUtf(buf, ModernYsmProtocol.VERSION)
            val out = ByteArray(buf.readableBytes())
            buf.readBytes(out)
            return out
        } finally {
            buf.release()
        }
    }

    /** 客户端上报的协议版本（收到 C2S 52 时记录）。 */
    var clientVersion: String? = null
        private set

    /** 是否已经发起过加密同步，避免重复下发 Packet 01 互相覆盖。 */
    private var syncStarted = false

    /** 仅记录客户端版本，不触发握手（对应官方 C2SVersionCheckPacket.handle 的行为）。 */
    fun onClientVersion(version: String) {
        clientVersion = version
        Logs.info("Player {} reported YSM version {}.", playerId, version)
    }

    /**
     * 主动发起加密同步，返回 Packet 01 的外层负载。
     *
     * 与官方 `ServerModelManager.nativeSyncModels` 一致：服务端在玩家进服后
     * 主动下发公钥交换，而不是等客户端的版本检查。
     *
     * @return 外层包字节（discriminator 1 + 密文）；已发起过则返回 null
     */
    fun beginSync(): ByteArray? {
        if (syncStarted) return null
        syncStarted = true
        Logs.info("Player {} starting ModernYSM crypto handshake.", playerId)

        val garbageLen = ModernYsmProtocol.GARBAGE_MIN + RANDOM.nextInt(ModernYsmProtocol.GARBAGE_RANDOM_RANGE)
        val garbage = ByteArray(garbageLen)
        RANDOM.nextBytes(garbage)

        val inner = Unpooled.buffer()
        try {
            YSMByteBuf(inner).use { out ->
                out.writeGarbageHeader(garbageLen, garbage)
                out.writeByte(ModernYsmProtocol.Inner.PUBLIC_KEY_EXCHANGE.toByte())
                val result = YsmCrypt.encrypt(out.toArray(), YsmCrypt.publicKey, true)
                key1 = result.nextKey()
                step = 1
                return wrapEncrypted(result.data())
            }
        } catch (e: Exception) {
            Logs.error("Failed to build Packet 01 for player $playerId", e)
            return null
        }
    }

    /**
     * 处理 C2S 2（加密容器）。根据当前 step 分派到 Pong 或模型请求。
     *
     * @return 需要回给客户端的若干外层负载（可能为空）
     */
    fun handleClientPayload(data: ByteArray): List<ByteArray> {
        val currentKey1 = key1 ?: return emptyList()

        return when (step) {
            1 -> handlePong(data, currentKey1)?.let { listOf(it) } ?: emptyList()
            else -> {
                handleInnerRequests(data, currentKey1)
                emptyList()
            }
        }
    }

    /** 处理 Pong（0x02），成功后推送目录。 */
    private fun handlePong(data: ByteArray, currentKey1: ByteArray): ByteArray? {
        val decrypted = try {
            YsmCrypt.decrypt(data, currentKey1)
        } catch (e: Exception) {
            null
        } ?: return null

        if (decrypted.size < ModernYsmProtocol.KEY_LENGTH) return null

        // 密文尾部 56 字节是客户端给出的下一把密钥
        clientNextKey = decrypted.copyOfRange(
            decrypted.size - ModernYsmProtocol.KEY_LENGTH,
            decrypted.size
        )
        val payload = decrypted.copyOfRange(0, decrypted.size - ModernYsmProtocol.KEY_LENGTH)

        // 校验内层确实是 Pong
        val ok = try {
            YSMByteBuf(Unpooled.wrappedBuffer(payload)).use { buf ->
                buf.skipGarbageHeader()
                buf.readByte().toInt() == ModernYsmProtocol.Inner.PUBLIC_KEY_PONG
            }
        } catch (e: Exception) {
            false
        }
        if (!ok) return null

        step = 2
        Logs.info("Player {} completed crypto handshake; sending catalog.", playerId)
        return buildCatalogPayload()
    }

    /** 构造 Packet 03 目录。 */
    fun buildCatalogPayload(): ByteArray? {
        val nextKey = clientNextKey ?: return null
        val entries = source.catalog()

        val garbageLen = ModernYsmProtocol.GARBAGE_MIN + RANDOM.nextInt(ModernYsmProtocol.GARBAGE_RANDOM_RANGE)
        val garbage = ByteArray(garbageLen)
        RANDOM.nextBytes(garbage)

        val inner = Unpooled.buffer()
        try {
            YSMByteBuf(inner).use { out ->
                out.writeGarbageHeader(garbageLen, garbage)
                out.writeVarInt(ModernYsmProtocol.Inner.CATALOG)
                // folderHash：服务端统一目录使用 0
                out.writeVarLong(0L)
                out.rawBuf.writeBytes(source.serverKey())
                out.rawBuf.writeBytes(clientKey)

                out.writeVarInt(entries.size)
                for (entry in entries) {
                    out.rawBuf.let {
                        writeVarLong(it, entry.hash1)
                        writeVarLong(it, entry.hash2)
                    }
                    out.writeString(entry.modelId)
                    out.writeVarInt(if (entry.isAuth) 1 else 0)
                    out.writeVarInt(if (entry.isCustomSkinModel) 1 else 0)
                    out.writeVarInt(entry.version)
                }

                // 服务端资源包（ModelPack）数量。
                // 客户端会依次读取每个包的 folderPath / 图标 / 名称描述 / 语言表。
                // 当前 YSMSync 不下发资源包元数据，发送 0 表示没有。
                out.writeVarInt(0)

                val result = YsmCrypt.encrypt(out.toArray(), nextKey, false)
                return wrapEncrypted(result.data())
            }
        } catch (e: Exception) {
            Logs.error("Failed to build catalog for player $playerId", e)
            return null
        }
    }

    /** 解析 C2S Packet 04，把请求的模型排入传输队列。 */
    private fun handleInnerRequests(data: ByteArray, currentKey1: ByteArray) {
        val decrypted = try {
            YsmCrypt.decrypt(data, currentKey1)
        } catch (e: Exception) {
            return
        } ?: return

        try {
            YSMByteBuf(Unpooled.wrappedBuffer(decrypted)).use { buf ->
                buf.skipGarbageHeader()
                val packetId = buf.readByte().toInt()
                if (packetId != ModernYsmProtocol.Inner.REQUEST_MODELS) return

                val count = buf.readVarInt()
                if (count <= 0 || count > 4096) return

                val byHash = source.catalog().associateBy { it.hash1 to it.hash2 }
                var queued = 0
                repeat(count) {
                    val h1 = buf.readVarLong()
                    val h2 = buf.readVarLong()
                    val entry = byHash[h1 to h2] ?: return@repeat
                    val bytes = source.modelCacheBytes(h1, h2) ?: return@repeat
                    transferQueue.add(ModelTransfer(entry, bytes, 0))
                    queued++
                }

                step = 3
                if (queued > 0) {
                    Logs.info(
                        "Player {} requested {} model(s); queued {} for transfer.",
                        playerId, count, queued
                    )
                }
            }
        } catch (e: Exception) {
            Logs.error("Failed to parse model request from $playerId", e)
        }
    }

    /** 是否还有未发送完毕的模型数据。 */
    fun hasPendingTransfers(): Boolean =
        currentTransfer != null || transferQueue.isNotEmpty()

    /**
     * 发送一个分块（受调用方限速控制）。
     *
     * @return 本次实际发送的字节数；0 表示没有可发送内容
     */
    fun pumpOneChunk(maxBytes: Int): ByteArray? {
        if (step < 3) return null
        val currentKey1 = key1 ?: return null

        if (currentTransfer == null) {
            currentTransfer = transferQueue.poll() ?: return null
        }
        val transfer = currentTransfer ?: return null

        val remaining = transfer.data.size - transfer.offset
        if (remaining <= 0) {
            currentTransfer = transferQueue.poll()
            return if (currentTransfer == null) null else pumpOneChunk(maxBytes)
        }

        val sendSize = minOf(ModernYsmProtocol.MAX_CHUNK_BYTES, maxBytes, remaining)
        if (sendSize <= 0) return null

        val garbageLen = ModernYsmProtocol.GARBAGE_MIN + RANDOM.nextInt(ModernYsmProtocol.GARBAGE_RANDOM_RANGE)
        val garbage = ByteArray(garbageLen)
        RANDOM.nextBytes(garbage)

        val inner = Unpooled.buffer()
        try {
            YSMByteBuf(inner).use { out ->
                out.writeGarbageHeader(garbageLen, garbage)
                out.writeVarInt(ModernYsmProtocol.Inner.MODEL_CHUNK)
                writeVarLong(out.rawBuf, transfer.entry.hash1)
                writeVarLong(out.rawBuf, transfer.entry.hash2)
                out.writeVarInt(transfer.data.size)
                out.writeVarInt(transfer.offset)
                out.writeVarInt(sendSize)
                out.rawBuf.writeBytes(transfer.data, transfer.offset, sendSize)

                val result = YsmCrypt.encrypt(out.toArray(), currentKey1, false)

                transfer.offset += sendSize
                if (transfer.offset >= transfer.data.size) {
                    currentTransfer = null
                }
                return wrapEncrypted(result.data())
            }
        } catch (e: Exception) {
            Logs.error("Failed to send model chunk to $playerId", e)
            currentTransfer = null
            return null
        }
    }

    /** 把密文包装成外层包（discriminator 1 + 密文）。 */
    private fun wrapEncrypted(encrypted: ByteArray): ByteArray {
        val out = ByteArray(encrypted.size + 1)
        out[0] = ModernYsmProtocol.Packets.S2C_MODEL_SYNC_PAYLOAD.toByte()
        System.arraycopy(encrypted, 0, out, 1, encrypted.size)
        return out
    }

    /** 清理会话持有的状态。 */
    fun cleanup() {
        syncStarted = false
        clientVersion = null
        transferQueue.clear()
        currentTransfer = null
        key1 = null
        clientNextKey = null
        step = 0
    }

    private data class ModelTransfer(
        val entry: ModelCatalogEntry,
        val data: ByteArray,
        var offset: Int,
    )

    // ---- 与 Minecraft FriendlyByteBuf 对齐的写入工具 ----

    private fun writeVarInt(buf: io.netty.buffer.ByteBuf, value: Int) {
        var v = value
        while ((v and -128) != 0) {
            buf.writeByte(v and 127 or 128)
            v = v ushr 7
        }
        buf.writeByte(v)
    }

    private fun writeVarLong(buf: io.netty.buffer.ByteBuf, value: Long) {
        var v = value
        while ((v and -128L) != 0L) {
            buf.writeByte((v and 127L).toInt() or 128)
            v = v ushr 7
        }
        buf.writeByte(v.toInt())
    }

    private fun writeUtf(buf: io.netty.buffer.ByteBuf, str: String) {
        val bytes = str.toByteArray(Charsets.UTF_8)
        writeVarInt(buf, bytes.size)
        buf.writeBytes(bytes)
    }
}
