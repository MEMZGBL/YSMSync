package moe.meowrealms.ysmsync.modernysm

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rip.ysm.security.YSMByteBuf
import rip.ysm.security.YsmCrypt
import java.security.SecureRandom
import java.util.UUID

/**
 * 端到端验证 [ModernYsmSession] 产出的字节流能被 ModernYSM / OpenYSM 客户端按
 * `ClientModelManager.processServerData` 的同一套逻辑解析。
 *
 * 客户端侧的解码步骤完全照抄 OpenYSM 源码，因此这个测试通过就意味着
 * 线上协议是对齐的（除了尚未覆盖的控制包）。
 */
class ModernYsmSessionTest {

    private val random = SecureRandom()

    /** 固定 56 字节服务端密钥，模拟 ModelManager.cacheKey。 */
    private val serverKey = ByteArray(56).also { random.nextBytes(it) }

    /** 一个假的模型内容（当作 encryptServerCache 的产物）。 */
    private val modelBytes = ByteArray(9_000).also { random.nextBytes(it) }

    private val entry = ModelCatalogEntry(
        modelId = "MT_Jake",
        hash1 = 0x1122334455667788L,
        hash2 = 0x1234567890ABCDEFL,
        isAuth = false,
        isCustomSkinModel = false,
        version = 32,
    )

    private val source = object : ModernYsmModelSource {
        override fun serverKey(): ByteArray = serverKey
        override fun clientKey(): ByteArray = ModernYsmSession.createClientKey()
        override fun catalog(): Collection<ModelCatalogEntry> = listOf(entry)
        override fun packs(): Collection<moe.meowrealms.ysmsync.model.ServerPackData> = emptyList()
        override fun modelCacheBytes(hash1: Long, hash2: Long): ByteArray? =
            if (hash1 == entry.hash1 && hash2 == entry.hash2) modelBytes else null
    }

    // ---- FriendlyByteBuf 读取工具（模拟客户端） ----

    private fun readVarInt(buf: io.netty.buffer.ByteBuf): Int {
        var value = 0
        var position = 0
        while (true) {
            val b = buf.readByte()
            value = value or ((b.toInt() and 127) shl position)
            if ((b.toInt() and 128) == 0) break
            position += 7
            if (position >= 32) throw RuntimeException("VarInt too big")
        }
        return value
    }

    private fun readVarLong(buf: io.netty.buffer.ByteBuf): Long {
        var value = 0L
        var position = 0
        while (true) {
            val b = buf.readByte()
            value = value or ((b.toLong() and 127L) shl position)
            if ((b.toLong() and 128L) == 0L) break
            position += 7
            if (position >= 64) throw RuntimeException("VarLong too big")
        }
        return value
    }

    private fun readString(buf: io.netty.buffer.ByteBuf): String {
        val len = readVarInt(buf)
        val bytes = ByteArray(len)
        buf.readBytes(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    @Test
    fun `完整握手与模型下发流程`() {
        val session = ModernYsmSession(UUID.randomUUID(), source)

        // ---------- 1. S2C 51 版本检查 ----------
        val versionCheck = session.buildVersionCheckPayload()
        assertEquals(ModernYsmProtocol.Packets.S2C_VERSION_CHECK.toByte(), versionCheck[0])

        val vcBuf = Unpooled.wrappedBuffer(versionCheck, 1, versionCheck.size - 1)
        val version = readString(vcBuf)
        assertEquals(ModernYsmProtocol.VERSION, version)
        // 未携带品牌串时客户端应正常处理（readableBytes == 0）
        assertEquals(0, vcBuf.readableBytes())

        // 客户端上报版本（官方实现里这一步只记录版本）
        session.onClientVersion(ModernYsmProtocol.VERSION)
        assertEquals(ModernYsmProtocol.VERSION, session.clientVersion)

        // ---------- 2. 服务端主动发起 Packet 01 ----------
        val packet01Outer = session.beginSync()
        assertNotNull(packet01Outer, "Packet 01 应生成成功")
        assertEquals(ModernYsmProtocol.Packets.S2C_MODEL_SYNC_PAYLOAD.toByte(), packet01Outer!![0])

        val encrypted01 = packet01Outer.copyOfRange(1, packet01Outer.size)
        val decrypted01 = YsmCrypt.decrypt(encrypted01, YsmCrypt.publicKey)
        assertNotNull(decrypted01, "客户端应能用 publicKey 解密 Packet 01")

        // 客户端固定取末尾 56 字节作为 key1
        val clientKey1 = decrypted01!!.copyOfRange(decrypted01.size - 56, decrypted01.size)
        try {
            YSMByteBuf(Unpooled.wrappedBuffer(decrypted01)).use { buf ->
                buf.skipGarbageHeader()
                assertEquals(ModernYsmProtocol.Inner.PUBLIC_KEY_EXCHANGE.toByte(), buf.readByte())
            }
        } catch (t: Throwable) {
            throw AssertionError("Packet 01 内层结构不正确", t)
        }

        // ---------- 3. 客户端回 Pong ----------
        val garbageLen = ModernYsmProtocol.GARBAGE_MIN + random.nextInt(ModernYsmProtocol.GARBAGE_RANDOM_RANGE)
        val garbage = ByteArray(garbageLen).also { random.nextBytes(it) }

        val innerPong = Unpooled.buffer()
        val pongEncrypted: ByteArray
        val clientLastKey: ByteArray
        YSMByteBuf(innerPong).use { out ->
            out.writeGarbageHeader(garbageLen, garbage)
            out.rawBuf.writeByte(0x02)
            out.rawBuf.writeByte(0x00)
            val result = YsmCrypt.encrypt(out.toArray(), clientKey1, true)
            pongEncrypted = result.data()
            clientLastKey = result.nextKey()
        }

        // ---------- 4. 服务端处理 Pong，产出 Packet 03 ----------
        val replies = session.handleClientPayload(pongEncrypted)
        assertEquals(1, replies.size, "处理 Pong 后应回一个目录包")
        assertTrue(session.isHandshakeConfirmed)

        val packet03Outer = replies[0]
        assertEquals(ModernYsmProtocol.Packets.S2C_MODEL_SYNC_PAYLOAD.toByte(), packet03Outer[0])

        val decrypted03 = YsmCrypt.decrypt(
            packet03Outer.copyOfRange(1, packet03Outer.size), clientLastKey
        )
        assertNotNull(decrypted03, "客户端应能用 lastKey 解密 Packet 03")

        // ---------- 5. 客户端解析目录 ----------
        try {
            YSMByteBuf(Unpooled.wrappedBuffer(decrypted03!!)).use { buf ->
                buf.skipGarbageHeader()
                assertEquals(ModernYsmProtocol.Inner.CATALOG, buf.readVarInt())
                assertEquals(0L, buf.readVarLong()) // folderHash

                val rxServerKey = ByteArray(56)
                buf.rawBuf.readBytes(rxServerKey)
                assertArrayEquals(serverKey, rxServerKey, "serverKey 必须与缓存密钥一致")

                val rxClientKey = ByteArray(56)
                buf.rawBuf.readBytes(rxClientKey)
                assertArrayEquals(ModernYsmSession.createClientKey(), rxClientKey)

                val count = buf.readVarInt()
                assertEquals(1, count)

                assertEquals(entry.hash1, readVarLong(buf.rawBuf))
                assertEquals(entry.hash2, readVarLong(buf.rawBuf))
                assertEquals(entry.modelId, readString(buf.rawBuf))
                assertEquals(0, buf.readVarInt()) // isAuth
                assertEquals(0, buf.readVarInt()) // isCustomSkinModel
                assertEquals(32, buf.readVarInt()) // version
                assertEquals(0, buf.readVarInt()) // 资源包数量
            }
        } catch (t: Throwable) {
            throw AssertionError("Packet 03 目录结构不正确", t)
        }

        // ---------- 6. 客户端请求模型（Packet 04） ----------
        val reqGarbageLen = ModernYsmProtocol.GARBAGE_MIN + random.nextInt(ModernYsmProtocol.GARBAGE_RANDOM_RANGE)
        val reqGarbage = ByteArray(reqGarbageLen).also { random.nextBytes(it) }
        val innerReq = Unpooled.buffer()
        val reqEncrypted: ByteArray
        YSMByteBuf(innerReq).use { out ->
            out.writeGarbageHeader(reqGarbageLen, reqGarbage)
            out.rawBuf.writeByte(0x04)
            out.writeVarInt(1)
                // 写入 hash1/hash2（VarLong）
                var v = entry.hash1
                while ((v and -128L) != 0L) {
                    out.rawBuf.writeByte((v and 127L).toInt() or 128); v = v ushr 7
                }
                out.rawBuf.writeByte(v.toInt())
                v = entry.hash2
                while ((v and -128L) != 0L) {
                    out.rawBuf.writeByte((v and 127L).toInt() or 128); v = v ushr 7
                }
                out.rawBuf.writeByte(v.toInt())

            val result = YsmCrypt.encrypt(out.toArray(), clientKey1, false)
            reqEncrypted = result.data()
        }

        session.handleClientPayload(reqEncrypted)
        assertTrue(session.hasPendingTransfers(), "请求后应有待发送的模型数据")

        // ---------- 7. 分块下发并校验重建内容 ----------
        val assembled = ByteArray(modelBytes.size)
        var received = 0
        var guard = 0
        while (session.hasPendingTransfers() && guard++ < 100) {
            val chunkOuter = session.pumpOneChunk(ModernYsmProtocol.MAX_CHUNK_BYTES) ?: break
            assertEquals(ModernYsmProtocol.Packets.S2C_MODEL_SYNC_PAYLOAD.toByte(), chunkOuter[0])

            val decrypted05 = YsmCrypt.decrypt(chunkOuter.copyOfRange(1, chunkOuter.size), clientKey1)
            assertNotNull(decrypted05, "客户端应能用 key1 解密模型分块")

            YSMByteBuf(Unpooled.wrappedBuffer(decrypted05!!)).use { buf ->
                buf.skipGarbageHeader()
                assertEquals(ModernYsmProtocol.Inner.MODEL_CHUNK, buf.readVarInt())
                assertEquals(entry.hash1, readVarLong(buf.rawBuf))
                assertEquals(entry.hash2, readVarLong(buf.rawBuf))
                assertEquals(modelBytes.size, buf.readVarInt()) // totalSize
                val offset = buf.readVarInt()
                val len = buf.readVarInt()
                val data = ByteArray(len)
                buf.rawBuf.readBytes(data)
                System.arraycopy(data, 0, assembled, offset, len)
                received += len
            }
        }

        assertEquals(modelBytes.size, received, "分块总长度必须等于模型大小")
        assertArrayEquals(modelBytes, assembled, "客户端重建出的模型内容必须与服务端一致")
    }
}
