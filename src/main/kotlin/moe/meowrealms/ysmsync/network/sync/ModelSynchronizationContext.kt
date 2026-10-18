package moe.meowrealms.ysmsync.network.sync

import com.elfmcys.yesstevemodel.model.format.ServerModelData
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.util.ReferenceCountUtil
import moe.meowrealms.ysmsync.YSMSyncConstants
import moe.meowrealms.ysmsync.YSMSyncMain
import moe.meowrealms.ysmsync.model.ModelManager
import moe.meowrealms.ysmsync.network.ClientConnectionManager.getYsmConnection
import moe.meowrealms.ysmsync.web.WebPortalManager
import moe.meowrealms.ysmsync.network.packet.s2c.S2CModelDataPayloadPacket
import org.bukkit.entity.Player
import rip.ysm.security.YSMByteBuf
import rip.ysm.security.YsmCrypt
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min
import moe.meowrealms.ysmsync.utils.Scheduling
import moe.meowrealms.ysmsync.utils.Scheduling.TaskHandle

class ModelSynchronizationContext(
    val player: Player
) {
    companion object {
        // lazy to ensure config.yml values are loaded before first access
        private val globalRateLimiter by lazy {
            ModelSyncRateLimiter(YSMSyncConstants.ModelSyncConstants.GLOBAL_RATE_LIMIT_MBPS)
        }
    }

    @Volatile
    private var state: Int = 0
    private lateinit var computedClientKey: ByteArray
    private lateinit var nextKeyInBytes: ByteArray
    private lateinit var subKeyInBytes: ByteArray
    private var allowedModels: Set<ServerModelData> = HashSet()

    private val scheduledTasksLock = ReentrantLock()
    private val scheduledTasks: MutableList<TaskHandle> = ArrayList()
    private val rateLimiter = ModelSyncRateLimiter(YSMSyncConstants.ModelSyncConstants.PER_PLAYER_RATE_LIMIT_MBPS)

    // reusable ByteBuf for chunk payload building, allocated once in sendMissing()
    private var reusableChunkBuf: ByteBuf? = null

    private fun computeClientKey() {
        this.computedClientKey = ByteArray(56)

        Random(this.player.uniqueId.mostSignificantBits + this.player.uniqueId.leastSignificantBits).nextBytes(this.computedClientKey)
    }

    private fun filterAllowedModels() {
        this.allowedModels = HashSet(ModelManager.getCachedModels().filter {
            WebPortalManager.isVisibleTo(this.player.uniqueId, it.modelId)
        })
    }

    fun restart() {
        this.state = 1

        this.cancelScheduledTasks()

        this.begin()
    }

    private fun cancelScheduledTasks() {
        this.scheduledTasksLock.withLock {
            for (scheduledTask in this.scheduledTasks) {
                scheduledTask.cancel()
            }

            this.scheduledTasks.clear()
        }
    }

    fun begin() {
        this.state = 1
        this.rateLimiter.reset()

        this.computeClientKey()
        this.filterAllowedModels()

        val garbageLen = 16 + ModelManager.secureRand.nextInt(48)
        val garbage = ByteArray(garbageLen)
        ModelManager.secureRand.nextBytes(garbage)

        YSMByteBuf(Unpooled.buffer()).use { outBuf ->
            outBuf.writeGarbageHeader(garbageLen, garbage)
            outBuf.writeByte(1.toByte())

            val result = YsmCrypt.encrypt(outBuf.toArray(), YsmCrypt.publicKey, true)
            this.subKeyInBytes = result.nextKey

            player.getYsmConnection().send(S2CModelDataPayloadPacket(result.data))
        }
    }

    fun cleanup() {
        this.state = 0
        this.cancelScheduledTasks()

        this.releaseReusableChunkBuf()

        ModelManager.onModelSynchronizationDone(this)
    }

    private fun releaseReusableChunkBuf() {
        this.reusableChunkBuf?.let {
            ReferenceCountUtil.safeRelease(it)
            this.reusableChunkBuf = null
        }
    }

    private fun sendMissing(requestedHashes: MutableList<LongArray>) {
        val queue = ArrayDeque<RequestedModelCache>(requestedHashes.size)

        for (hashes in requestedHashes) {
            if (hashes.size >= 2) {
                queue.add(RequestedModelCache(hashes[0], hashes[1]))
            }
        }

        // pre-allocate reusable ByteBuf for all chunk payloads in this sync
        this.releaseReusableChunkBuf()
        this.reusableChunkBuf = Unpooled.buffer(YSMSyncConstants.ModelSyncConstants.MAX_CHUNK_BYTES + 96)

        this.scheduleNextRequestedModel(queue)
    }

    private fun scheduleNextRequestedModel(queue: ArrayDeque<RequestedModelCache>) {
        if (this.state < 3) {
            return
        }

        this.trackScheduledTask(
            Scheduling.asyncScheduler().run(Runnable {
                this.prepareNextRequestedModel(queue)
            })
        )
    }

    private fun prepareNextRequestedModel(queue: ArrayDeque<RequestedModelCache>) {
        if (this.state < 3) {
            return
        }

        val requested = queue.poll() ?: return
        val fileName = String.format("%016x%016x", requested.hash1, requested.hash2)
        val file = ModelManager.getCacheFile(fileName)

        try {
            if (!Files.exists(file)) {
                this.scheduleNextRequestedModel(queue)
                return
            }

            val totalSize = Files.size(file)
            if (totalSize > Int.MAX_VALUE) {
                YSMSyncMain.instance.slF4JLogger.warn("Model cache file is too large to sync: $file")
                this.scheduleNextRequestedModel(queue)
                return
            }

            val channel = Files.newByteChannel(file, StandardOpenOption.READ)

            this.scheduleNextModelChunk(
                ModelCacheTransfer(file, requested.hash1, requested.hash2, totalSize.toInt(), queue, channel)
            )
        } catch (e: Exception) {
            YSMSyncMain.instance.slF4JLogger.error("Failed to prepare model cache file: $file", e)
            this.scheduleNextRequestedModel(queue)
        }
    }

    private fun scheduleNextModelChunk(transfer: ModelCacheTransfer) {
        if (this.state < 3) {
            return
        }

        this.trackScheduledTask(
            Scheduling.asyncScheduler().run(Runnable {
                this.processNextModelChunk(transfer)
            })
        )
    }

    private fun processNextModelChunk(transfer: ModelCacheTransfer) {
        if (this.state < 3) {
            this.closeTransferChannel(transfer)
            return
        }

        if (transfer.offset >= transfer.totalSize) {
            this.closeTransferChannel(transfer)
            this.scheduleNextRequestedModel(transfer.remainingRequests)
            return
        }

        try {
            val length = this.readModelChunk(transfer)
            if (length <= 0) {
                this.closeTransferChannel(transfer)
                this.scheduleNextRequestedModel(transfer.remainingRequests)
                return
            }

            val offset = transfer.offset
            val payload = this.buildModelChunkPayload(
                transfer.hash1,
                transfer.hash2,
                transfer.totalSize,
                offset,
                transfer.buffer,
                length
            )
            transfer.offset += length

            this.scheduleRateLimitedModelPayload(payload, 3) {
                this.scheduleNextModelChunk(transfer)
            }
        } catch (e: Exception) {
            YSMSyncMain.instance.slF4JLogger.error("Failed to send model chunks to ${this.player.name}", e)
            this.closeTransferChannel(transfer)
            this.scheduleNextRequestedModel(transfer.remainingRequests)
        }
    }

    private fun closeTransferChannel(transfer: ModelCacheTransfer) {
        try {
            transfer.channel.close()
        } catch (_: Exception) {
        }
    }

    private fun readModelChunk(transfer: ModelCacheTransfer): Int {
        val expectedLength = min(transfer.buffer.size, transfer.totalSize - transfer.offset)
        val byteBuffer = ByteBuffer.wrap(transfer.buffer, 0, expectedLength)

        transfer.channel.position(transfer.offset.toLong())
        return transfer.channel.read(byteBuffer)
    }

    private fun buildModelChunkPayload(
        hash1: Long,
        hash2: Long,
        totalSize: Int,
        offset: Int,
        buffer: ByteArray,
        length: Int
    ): ByteArray {
        val garbageLen = 16 + ModelManager.secureRand.nextInt(48)
        val garbage = ByteArray(garbageLen)
        ModelManager.secureRand.nextBytes(garbage)

        val chunkBuf = this.reusableChunkBuf
        if (chunkBuf != null) {
            // reuse pre-allocated buffer: clear and write fresh data
            chunkBuf.clear()
            val outBuf = YSMByteBuf(chunkBuf)
            outBuf.writeGarbageHeader(garbageLen, garbage)
            outBuf.writeVarInt(5)
            outBuf.writeVarLong(hash1)
            outBuf.writeVarLong(hash2)
            outBuf.writeVarInt(totalSize)
            outBuf.writeVarInt(offset)
            outBuf.writeVarInt(length)
            outBuf.rawBuf.writeBytes(buffer, 0, length)

            return YsmCrypt.encrypt(outBuf.toArray(), this.subKeyInBytes, false).data
        } else {
            // fallback: allocate fresh buffer (shouldn't happen in normal flow)
            YSMByteBuf(Unpooled.buffer(length + 96)).use { outBuf ->
                outBuf.writeGarbageHeader(garbageLen, garbage)
                outBuf.writeVarInt(5)
                outBuf.writeVarLong(hash1)
                outBuf.writeVarLong(hash2)
                outBuf.writeVarInt(totalSize)
                outBuf.writeVarInt(offset)
                outBuf.writeVarInt(length)
                outBuf.rawBuf.writeBytes(buffer, 0, length)

                return YsmCrypt.encrypt(outBuf.toArray(), this.subKeyInBytes, false).data
            }
        }
    }


    private fun sendCacheList() {
        val garbageLen = 16 + ModelManager.secureRand.nextInt(48)
        val garbage = ByteArray(garbageLen)
        ModelManager.secureRand.nextBytes(garbage)

        try {
            YSMByteBuf(Unpooled.buffer()).use { outBuf ->
                outBuf.writeGarbageHeader(garbageLen, garbage)
                // sub packet id
                outBuf.writeVarInt(3)
                // cache folder name
                outBuf.writeVarLong(ModelManager.cacheDeterminer)
                // cache key
                outBuf.rawBuf.writeBytes(ModelManager.cacheKey())
                // client key
                outBuf.rawBuf.writeBytes(this.computedClientKey)
                outBuf.writeVarInt(this.allowedModels.size)

                for (model in this.allowedModels) {
                    val sha256 = model.loadedModelData.modelHash
                    val hashes = YsmCrypt.calculateModelHashes(sha256, ModelManager.cacheKey())
                    outBuf.writeVarLong(hashes[0])
                    outBuf.writeVarLong(hashes[1])
                    outBuf.writeString(model.modelId)
                    outBuf.writeVarInt(if (model.isAuth) 1 else 0)
                    outBuf.writeVarInt(if (model.isCustomSkinModel) 1 else 0)
                    outBuf.writeVarInt(32)
                }

                val builtinModels = ArrayList(ModelManager.getBuiltinModels())

                outBuf.writeVarInt(builtinModels.size)

                for (pack in builtinModels) {
                    outBuf.writeString(pack.folderPath)
                    if (pack.iconData != null) {
                        outBuf.writeVarInt(1)
                        outBuf.writeByteArray(pack.iconData)
                        outBuf.writeVarInt(pack.iconWidth)
                        outBuf.writeVarInt(pack.iconHeight)
                        outBuf.writeVarInt(pack.iconFormat)
                        outBuf.writeVarInt(1)
                    } else {
                        outBuf.writeVarInt(0)
                    }

                    if (pack.name == null && pack.description == null) {
                        outBuf.writeVarInt(0)
                    } else {
                        outBuf.writeVarInt(1)
                        outBuf.writeString(pack.name ?: "")
                        outBuf.writeString(pack.description ?: "")
                    }

                    val packLang = pack.lang
                    if (packLang != null && !packLang.isEmpty()) {
                        outBuf.writeVarInt(packLang.size)

                        for (langEntry in packLang.entries) {
                            outBuf.writeString(langEntry.key)
                            outBuf.writeVarInt((langEntry.value as MutableMap<*, *>).size)

                            for (kv in (langEntry.value as MutableMap<*, *>).entries) {
                                outBuf.writeString(kv.key as String?)
                                outBuf.writeString(kv.value as String?)
                            }
                        }
                    } else {
                        outBuf.writeVarInt(0)
                    }
                }

                outBuf.writeVarInt(0)
                val result = YsmCrypt.encrypt(outBuf.toArray(), this.nextKeyInBytes, false)

                this.scheduleRateLimitedModelPayload(result.data(), 2)
            }
        } catch (e: java.lang.Exception) {
            throw RuntimeException(e)
        }
    }

    private fun scheduleRateLimitedModelPayload(
        payload: ByteArray,
        minimumState: Int,
        onSent: (() -> Unit)? = null
    ) {
        val perPlayerDelay = this.rateLimiter.reserveDelayTicks(payload.size)
        val globalDelay = globalRateLimiter.reserveDelayTicks(payload.size)
        val delayTicks = max(perPlayerDelay, globalDelay)

        val task = if (delayTicks <= 0L) {
            Scheduling.entitySpecificScheduler(this.player).run(Runnable {
                if (this.sendModelPayloadIfAlive(payload, minimumState)) {
                    onSent?.invoke()
                }
            }, null)
        } else {
            Scheduling.entitySpecificScheduler(this.player).runDelayed(Runnable {
                if (this.sendModelPayloadIfAlive(payload, minimumState)) {
                    onSent?.invoke()
                }
            }, null, delayTicks)
        }

        this.trackScheduledTask(task)
    }

    private fun trackScheduledTask(task: TaskHandle?) {
        this.scheduledTasksLock.withLock {
            task?.let {
                this.scheduledTasks.add(it)
            }
        }
    }

    private fun sendModelPayloadIfAlive(payload: ByteArray, minimumState: Int): Boolean {
        if (this.state < minimumState) {
            return false
        }

        this.player.getYsmConnection().send(S2CModelDataPayloadPacket(payload))
        return true
    }

    fun handleClientReply(data: ByteArray) {
        if (data.isEmpty()){
            this.cleanup()
            return
        }

        try {
            val decrypted = YsmCrypt.decrypt(data, this.subKeyInBytes)

            if (this.state == 1) {
                if (decrypted != null && decrypted.size >= 56) {
                    this.nextKeyInBytes = Arrays.copyOfRange(decrypted, decrypted.size - 56, decrypted.size)
                    val payload = Arrays.copyOfRange(decrypted, 0, decrypted.size - 56)

                    YSMByteBuf(Unpooled.wrappedBuffer(payload)).use { buf ->
                        buf.skipGarbageHeader()
                        if (buf.rawBuf.readByte().toInt() != 2) {
                            return
                        }
                    }

                    // promote state
                    this.state = 2

                    this.sendCacheList()
                    return
                }

                return
            } else if (this.state == 2) {
                YSMByteBuf(Unpooled.wrappedBuffer(decrypted)).use { buf ->
                    buf.skipGarbageHeader()

                    val ordinal = buf.rawBuf.readByte().toInt()

                    if (ordinal == 4) {
                        val numRequests = buf.readVarInt()
                        val requestedHashes: MutableList<LongArray> = ArrayList()

                        for (i in 0..< numRequests) {
                            requestedHashes.add(longArrayOf(buf.readVarLong(), buf.readVarLong()))
                        }

                        this.state = 3

                        YSMSyncMain.instance.slF4JLogger.info("Sending requested hashes: ${requestedHashes.toTypedArray().contentToString()}")
                        this.sendMissing(requestedHashes)
                        return
                    }

                    YSMSyncMain.instance.slF4JLogger.warn("Synchronization err, client reported: $ordinal")
                }
                return
            }
        } catch (e: Exception) {
            YSMSyncMain.instance.slF4JLogger.error("Model synchronization fatal error: ${this.player.name}", e)

            // we'll handle it upstream
            throw e
        }
    }
}

private data class RequestedModelCache(
    val hash1: Long,
    val hash2: Long
)

private class ModelCacheTransfer(
    val file: Path,
    val hash1: Long,
    val hash2: Long,
    val totalSize: Int,
    val remainingRequests: ArrayDeque<RequestedModelCache>,
    val channel: SeekableByteChannel
) {
    val buffer: ByteArray = ByteArray(YSMSyncConstants.ModelSyncConstants.MAX_CHUNK_BYTES)
    var offset: Int = 0
}
