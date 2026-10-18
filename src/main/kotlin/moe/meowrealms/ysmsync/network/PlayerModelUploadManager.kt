package moe.meowrealms.ysmsync.network

import io.netty.buffer.Unpooled
import io.netty.util.ReferenceCountUtil
import moe.meowrealms.ysmsync.YSMSyncMain
import moe.meowrealms.ysmsync.data.PlayerDataStorage.getYSMSyncData
import moe.meowrealms.ysmsync.model.ModelManager
import moe.meowrealms.ysmsync.utils.SimpleFriendlyByteBuf
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import moe.meowrealms.ysmsync.utils.Scheduling

/**
 * Receives a selected local .ysm file from the optional YSM Model Share client
 * addon.  The archive is loaded by YSMSync itself afterwards, so every YSM 2.6
 * client obtains it through the normal encrypted YSMSync sync rather than a
 * second, incompatible YSM protocol.
 */
object PlayerModelUploadManager : PluginMessageListener {
    const val CHANNEL = "ysmshare:main"
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_CHUNK_BYTES = 24 * 1024
    private val uploads = ConcurrentHashMap<UUID, Upload>()

    fun init() {
        Bukkit.getMessenger().registerIncomingPluginChannel(YSMSyncMain.instance, CHANNEL, this)
        Bukkit.getMessenger().registerOutgoingPluginChannel(YSMSyncMain.instance, CHANNEL)
        YSMSyncMain.instance.slF4JLogger.info("Initialized player YSM model upload bridge.")
    }

    fun shutdown() {
        uploads.clear()
        Bukkit.getMessenger().unregisterIncomingPluginChannel(YSMSyncMain.instance, CHANNEL)
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(YSMSyncMain.instance, CHANNEL)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != CHANNEL || message.isEmpty()) return
        val input = SimpleFriendlyByteBuf(Unpooled.wrappedBuffer(message))
        try {
            when (input.readUnsignedByte().toInt()) {
                0 -> return // companion transport heartbeat
                1 -> begin(player, input)
                2 -> append(player, input)
                3 -> finish(player)
                else -> reject(player, "unknown packet type")
            }
            if (input.readableBytes() != 0) reject(player, "trailing packet data")
        } catch (error: Throwable) {
            uploads.remove(player.uniqueId)
            reject(player, error.message ?: error.javaClass.simpleName)
        } finally {
            ReferenceCountUtil.safeRelease(input)
        }
    }

    private fun begin(player: Player, input: SimpleFriendlyByteBuf) {
        val selectedId = cleanId(input.readUtf(128))
        val texture = input.readUtf(128).take(128)
        val size = input.readInt()
        val digest = input.readUtf(64).lowercase()
        require(size in 1..MAX_BYTES) { "invalid model size" }
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "invalid checksum" }
        uploads[player.uniqueId] = Upload(selectedId, texture, digest, ByteArray(size))
        YSMSyncMain.instance.slF4JLogger.info("Receiving selected YSM model '$selectedId' (${size} bytes) from ${player.name}")
    }

    private fun append(player: Player, input: SimpleFriendlyByteBuf) {
        val upload = uploads[player.uniqueId] ?: throw IllegalArgumentException("upload was not started")
        val offset = input.readInt()
        val size = input.readVarInt()
        require(size in 0..MAX_CHUNK_BYTES && offset == upload.received && size <= upload.bytes.size - offset) { "invalid upload chunk" }
        require(input.readableBytes() == size) { "incorrect upload chunk length" }
        input.readBytes(upload.bytes, offset, size)
        upload.received += size
    }

    private fun finish(player: Player) {
        val upload = uploads.remove(player.uniqueId) ?: throw IllegalArgumentException("upload was not started")
        require(upload.received == upload.bytes.size) { "incomplete model upload" }
        require(sha256(upload.bytes) == upload.sha256) { "model checksum mismatch" }

        val fileName = "${safeFilePart(upload.originalId)}.ysm"
        val relativeId = "shared/${player.uniqueId}/$fileName"
        val target = YSMSyncMain.instance.dataFolder.toPath().resolve("models/custom").resolve(relativeId).normalize()
        val root = YSMSyncMain.instance.dataFolder.toPath().resolve("models/custom").toAbsolutePath().normalize()
        require(target.toAbsolutePath().normalize().startsWith(root)) { "unsafe upload destination" }

        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".upload-", ".ysm")
        try {
            Files.write(temporary, upload.bytes, StandardOpenOption.TRUNCATE_EXISTING)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Throwable) {
            Files.deleteIfExists(temporary)
            throw error
        }

        YSMSyncMain.instance.slF4JLogger.info("Stored uploaded YSM model for ${player.name} as $relativeId; reloading YSMSync models.")
        Scheduling.asyncScheduler().run(Runnable {
            try {
                ModelManager.loadModels()
                // ModelManager has actually decrypted, deserialized and cached the
                // archive by this point. A missing descriptor means the input was
                // invalid, so remove it before it can ever be advertised.
                if (ModelManager.getModelData(relativeId) == null) {
                    Files.deleteIfExists(target)
                    ModelManager.loadModels()
                    throw IllegalArgumentException("uploaded .ysm failed YSMSync validation")
                }
                val texture = ModelManager.resolveTextureId(relativeId, upload.texture)
                    ?: ModelManager.defaultTextureOf(relativeId)
                    ?: run {
                        Files.deleteIfExists(target)
                        ModelManager.loadModels()
                        throw IllegalArgumentException("uploaded .ysm has no usable texture")
                    }
                Bukkit.getScheduler().runTask(YSMSyncMain.instance, Runnable {
                    if (!player.isOnline) return@Runnable
                    val data = player.getYSMSyncData() ?: return@Runnable
                    data.selectedModelId = relativeId
                    data.selectedModelTexture = texture
                    data.disabled = false
                    data.markDirty()
                    ClientConnectionManager.restartModelSynchronizationForConnectedPlayers()
                    YSMSyncMain.instance.slF4JLogger.info("Activated uploaded YSM model $relativeId for ${player.name}.")
                    status(player, true, "模型已校验并发布给在线 YSM 玩家")
                })
            } catch (error: Throwable) {
                YSMSyncMain.instance.slF4JLogger.error("Could not activate uploaded YSM model from ${player.name}", error)
                Bukkit.getScheduler().runTask(YSMSyncMain.instance, Runnable {
                    if (player.isOnline) {
                        status(player, false, "模型校验失败，未发布：${error.message ?: "无效文件"}")
                    }
                })
            }
        })
    }

    private fun cleanId(value: String): String {
        require(value.isNotBlank() && value.length <= 120 && value.none { it == '/' || it == '\\' || it.isISOControl() }) { "unsafe model id" }
        return value
    }

    private fun safeFilePart(value: String): String = value.map {
        when {
            it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' || it == '.' || it == '(' || it == ')' -> it
            else -> '_'
        }
    }.joinToString("").take(120).ifBlank { "model" }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun reject(player: Player, reason: String) {
        YSMSyncMain.instance.slF4JLogger.warn("Rejected YSM model upload from ${player.name}: $reason")
        status(player, false, "上传被拒绝：$reason")
    }

    /** Sends a concise result back to the optional Alt+Y upload companion. */
    private fun status(player: Player, accepted: Boolean, text: String) {
        if (!player.isOnline) return
        val output = SimpleFriendlyByteBuf(Unpooled.buffer())
        try {
            output.writeByte(20)
            output.writeBoolean(accepted)
            output.writeUtf(text.take(240), 256)
            val bytes = ByteArray(output.readableBytes())
            output.readBytes(bytes)
            player.sendPluginMessage(YSMSyncMain.instance, CHANNEL, bytes)
        } finally {
            ReferenceCountUtil.safeRelease(output)
        }
    }

    private class Upload(
        val originalId: String,
        val texture: String,
        val sha256: String,
        val bytes: ByteArray,
        var received: Int = 0,
    )
}
