package moe.meowrealms.ysmsync.modernysm

import io.netty.buffer.Unpooled
import moe.meowrealms.ysmsync.YSMSyncMain
import moe.meowrealms.ysmsync.data.PlayerDataStorage
import moe.meowrealms.ysmsync.data.PlayerDataStorage.getYSMSyncData
import moe.meowrealms.ysmsync.model.ModelManager
import moe.meowrealms.ysmsync.network.PaperYsmPayloadBridge
import moe.meowrealms.ysmsync.utils.Scheduling
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ModernYSM / OpenYSM 同步连接的集中管理。
 *
 * 负责：
 * - 玩家进服后延迟发送 S2C 51 版本检查（等待客户端注册通道）
 * - 按包 ID 分派 C2S 消息到对应会话
 * - 每 tick 推进模型分块传输并施加带宽限制
 */
object ModernYsmManager {

    /** 每个 tick 允许发送的最大字节数（约 5 MB/s，可按需调整）。 */
    private var maxBytesPerTick = 32_000

    /** 玩家 UUID → 会话。 */
    private val sessions = ConcurrentHashMap<UUID, ModernYsmSession>()

    /** 每服务器 tick 的传输任务句柄。 */
    private var tickTask: Any? = null

    fun init() {
        Logs.info("ModernYSM protocol support enabled (channel: {}).", ModernYsmProtocol.CHANNEL)
        startTicking()
    }

    fun getSession(playerId: UUID): ModernYsmSession? = sessions[playerId]

    /** 玩家进入服务器：建立会话并投递版本检查。 */
    fun onPlayerJoin(player: Player) {
        val session = ModernYsmSession(player.uniqueId, ModelManagerModelSource)
        sessions[player.uniqueId] = session

        // 延迟 20 tick：确保客户端已完成通道注册。
        // 顺序与官方一致：先发版本检查，再主动发起公钥交换（nativeSyncModels 的行为）。
        Scheduling.entitySpecificScheduler(player).runDelayed(Runnable {
            if (!player.isOnline) return@Runnable
            try {
                sendPayload(player, session.buildVersionCheckPayload())
                Logs.info("Sent ModernYSM version check to {}.", player.name)

                val packet01 = session.beginSync()
                if (packet01 != null) {
                    sendPayload(player, packet01)
                }
            } catch (t: Throwable) {
                Logs.error("Failed to start ModernYSM sync for ${player.name}", t)
            }
        }, null, 20L)

        // 再延迟一段：等客户端把模型加载/缓存就绪，再把「自己的模型」广播出去，
        // 并把「其他在线玩家的模型」推给自己。
        // 缺了这一步，玩家每次进服都会停在 default —— 客户端不知道服务端记的模型是什么。
        Scheduling.entitySpecificScheduler(player).runDelayed(Runnable {
            if (!player.isOnline) return@Runnable
            try {
                restoreModelSelection(player)
            } catch (t: Throwable) {
                Logs.error("Failed to restore model selection for ${player.name}", t)
            }
        }, null, RESTORE_SELECTION_DELAY_TICKS)
    }

    /** 玩家离开：清理会话。 */
    fun onPlayerQuit(player: Player) {
        sessions.remove(player.uniqueId)?.cleanup()
    }

    /**
     * 处理客户端发来的通道消息。
     *
     * @return true 表示本模块已消费该消息，调用方不应再走旧协议
     */
    fun onClientMessage(player: Player, raw: ByteArray): Boolean {
        if (raw.isEmpty()) return false

        val session = sessions[player.uniqueId] ?: return false
        // 解掉客户端的 byteArray 包装（官方 YSM 会包一层，ModernYSM 走裸数据）
        val data = unwrapPayload(raw)
        if (data.isEmpty()) return false
        val discriminator = data[0].toInt() and 0xFF

        try {
            when (discriminator) {
                ModernYsmProtocol.Packets.C2S_VERSION_CHECK -> {
                    // 只记录版本：真正的公钥交换由服务端在进服时主动发起
                    val version = readUtf(Unpooled.wrappedBuffer(data, 1, data.size - 1))
                    session.onClientVersion(version)
                    return true
                }

                ModernYsmProtocol.Packets.C2S_MODEL_SYNC_PAYLOAD -> {
                    val body = data.copyOfRange(1, data.size)
                    for (packet in session.handleClientPayload(body)) {
                        sendPayload(player, packet)
                    }
                    return true
                }

                ModernYsmProtocol.Packets.C2S_REQUEST_SWITCH_MODEL -> {
                    handleSwitchModel(player, session, data)
                    return true
                }

                ModernYsmProtocol.Packets.C2S_PLAY_ANIMATION -> {
                    handlePlayAnimation(player, data)
                    return true
                }

                ModernYsmProtocol.Packets.C2S_SET_STAR_MODEL,
                ModernYsmProtocol.Packets.C2S_SYNC_ANIMATION_EXPRESSION,
                ModernYsmProtocol.Packets.C2S_SWING_ARM,
                ModernYsmProtocol.Packets.C2S_COMPLETE_FEEDBACK,
                ModernYsmProtocol.Packets.C2S_REQUEST_EXECUTE_MOLANG,
                ModernYsmProtocol.Packets.C2S_MODEL_UPLOAD_START,
                ModernYsmProtocol.Packets.C2S_MODEL_UPLOAD_CHUNK,
                ModernYsmProtocol.Packets.C2S_MODEL_UPLOAD_FINISH -> {
                    // 这些包由后续控制层处理；当前先消费，避免误投递给旧协议解析器
                    return true
                }
            }
        } catch (t: Throwable) {
            Logs.error(
                "Error handling ModernYSM packet ${discriminator} from ${player.name}", t
            )
        }
        return false
    }

    /** C2S 5：请求切换模型（modelId + textureId，UTF 编码）。 */
    private fun handleSwitchModel(player: Player, session: ModernYsmSession, data: ByteArray) {
        val buf = Unpooled.wrappedBuffer(data, 1, data.size - 1)
        try {
            val modelId = readUtf(buf)
            val textureId = readUtf(buf)

            Logs.info(
                "Player {} requested model switch: {} / {}", player.name, modelId, textureId
            )

            // 记住玩家当前模型：按 Z 的动画要按模型换算动画名，否则 index 会指向错误模型
            persistSelectedModel(player, modelId, textureId)

            // 广播给其他玩家：对应 S2C 4 (SetModelAndTexture)
            broadcastModelSelection(player, modelId, textureId)
        } finally {
            buf.release()
        }
    }

    /** S2C 21 中表示「本次同步携带模型/动画切换」的标志位。 */
    private val FLAG_MODEL_SWITCH: Int = 2048

    /**
     * C2S 7：播放额外动画（客户端按 Z / 动画轮盘触发）。
     *
     * 线上格式：`varInt animationIndex`, `utf category`, `varInt entityId`
     * - `entityId != -1`：官方实现里只用于女仆实体，普通玩家动画一律走 -1，这里直接忽略
     * - `animationIndex == -1`：停止当前额外动画
     * - 其余：按「分类 → 动画表」取第 index 个动画名，再经 S2C 21 广播
     */
    private fun handlePlayAnimation(player: Player, data: ByteArray) {
        val buf = Unpooled.wrappedBuffer(data, 1, data.size - 1)
        try {
            val animationIndex = readVarInt(buf)
            val category = readUtf(buf)
            val entityId = if (buf.isReadable) readVarInt(buf) else -1

            if (entityId != -1) {
                // 女仆/其他实体的动画轮盘：当前不接管
                return
            }

            val animationName = if (animationIndex == -1) {
                "" // 空串 = 清除额外动画
            } else {
                val modelId = currentModelIdOf(player)
                if (modelId == null) {
                    Logs.warn(
                        "Player {} pressed animation key but has no model selected; ignoring.",
                        player.name
                    )
                    return
                }
                val resolved = ModelManager.lookupAnimationFromPacket(modelId, animationIndex, category)
                if (resolved == null) {
                    Logs.warn(
                        "Player {} requested animation index {} (category '{}') which is out of range for model {}.",
                        player.name, animationIndex, category, modelId
                    )
                    return
                }
                resolved
            }

            Logs.info(
                "Player {} requested animation '{}' (index {}, category '{}').",
                player.name, animationName.ifEmpty { "<stop>" }, animationIndex, category
            )

            broadcastAnimation(player, animationName)
        } catch (t: Throwable) {
            Logs.error("Failed to handle play animation from ${player.name}", t)
        } finally {
            buf.release()
        }
    }

    /** 玩家当前选用的模型 ID（优先内存数据，回退默认模型）。 */
    private fun currentModelIdOf(player: Player): String? =
        runCatching { player.getYSMSyncData()?.selectedModelId }.getOrNull()
            ?: ModelManager.getDefaultModelConfig(
                YSMSyncMain.instance.config.getString("model-defaults.model-id", "default") ?: "default",
                YSMSyncMain.instance.config.getString("model-defaults.texture", "default") ?: "default"
            ).left

    /** 记录玩家当前模型与贴图到持久化数据。 */
    private fun persistSelectedModel(player: Player, modelId: String, textureId: String) {
        runCatching {
            val data = player.getYSMSyncData() ?: return@runCatching
            if (data.selectedModelId == modelId && data.selectedModelTexture == textureId) return@runCatching
            data.selectedModelId = modelId
            data.selectedModelTexture = textureId
            PlayerDataStorage.savePlayer(player.uniqueId, data)
        }.onFailure { Logs.error("Failed to persist model selection for ${player.name}", it) }
    }

    /**
     * 广播玩家额外动画状态。
     *
     * 对应官方 S2C 21（SyncPlayerState）：`varInt entityId`, `short flags`,
     * 当 `flags & 2048` 时再跟 `utf modelSwitchId`。空串表示停止动画。
     */
    private fun broadcastAnimation(source: Player, animationName: String) {
        val payload = buildPlayerStateAnimation(source.entityId, animationName) ?: return

        for (viewer in Bukkit.getOnlinePlayers()) {
            if (viewer.uniqueId != source.uniqueId && !viewer.canSee(source)) continue
            if (sessions[viewer.uniqueId]?.isHandshakeConfirmed != true) continue
            sendPayload(viewer, payload)
        }
    }

    private fun buildPlayerStateAnimation(entityId: Int, animationName: String): ByteArray? {
        val buf = Unpooled.buffer()
        try {
            buf.writeByte(ModernYsmProtocol.Packets.S2C_SYNC_PLAYER_STATE)
            writeVarInt(buf, entityId)
            buf.writeShort(FLAG_MODEL_SWITCH and 0xFFFF)
            writeUtf(buf, animationName)

            val out = ByteArray(buf.readableBytes())
            buf.readBytes(out)
            return out
        } catch (t: Throwable) {
            Logs.error("Failed to build player state animation payload", t)
            return null
        } finally {
            buf.release()
        }
    }

    /** 进服后延迟多少 tick 再恢复模型选择（等客户端加载完模型）。 */
    private const val RESTORE_SELECTION_DELAY_TICKS = 40L

    /**
     * 恢复并同步玩家的模型选择。
     *
     * 做两件事：
     * 1. 把该玩家保存的模型广播给所有已握手玩家（含自己）——
     *    否则客户端进服后一直停在 default，因为服务端从没告诉过它用哪个模型
     * 2. 把所有其他在线玩家的模型也推给该玩家，让他一进服就能看到别人正确的样子
     */
    private fun restoreModelSelection(player: Player) {
        val own = selectionOf(player)
        if (own != null) {
            Logs.info(
                "Restoring model selection for {}: {} / {}",
                player.name, own.first, own.second
            )
            broadcastModelSelection(player, own.first, own.second)
        } else {
            Logs.warn("No saved model selection for {}; leaving client default.", player.name)
        }

        // 把其他在线玩家的模型推给刚进服的人
        for (other in Bukkit.getOnlinePlayers()) {
            if (other.uniqueId == player.uniqueId) continue
            if (!player.canSee(other)) continue
            if (sessions[other.uniqueId]?.isHandshakeConfirmed != true) continue

            val otherSelection = selectionOf(other) ?: continue
            val payload = buildSetModelAndTexture(other.entityId, otherSelection.first, otherSelection.second)
                ?: continue
            sendPayload(player, payload)
        }
    }

    /** 读取玩家当前模型与贴图；模型不可用时返回 null。 */
    private fun selectionOf(player: Player): Pair<String, String>? {
        val data = runCatching { player.getYSMSyncData() }.getOrNull() ?: return null
        val modelId = data.selectedModelId.takeIf { it.isNotBlank() } ?: return null
        val texture = data.selectedModelTexture.takeIf { it.isNotBlank() }
            ?: ModelManager.defaultTextureOf(modelId)
            ?: return null
        return modelId to texture
    }

    /**
     * 广播实体模型选择。
     *
     * S2C 4 的线上格式：
     * ```
     * varInt  entityId
     * utf     modelId
     * utf     textureId
     * bool    disabled
     * <S2CSyncPlayerStatePacket: varInt entityId, short flags(0)>
     * ```
     */
    private fun broadcastModelSelection(source: Player, modelId: String, textureId: String) {
        val payload = buildSetModelAndTexture(source.entityId, modelId, textureId)
        if (payload == null) return

        for (viewer in Bukkit.getOnlinePlayers()) {
            // 必须包含自己：客户端靠这个包更新本地玩家模型（官方实现同样回发给自己）
            if (viewer.uniqueId != source.uniqueId && !viewer.canSee(source)) continue
            if (sessions[viewer.uniqueId]?.isHandshakeConfirmed != true) continue
            sendPayload(viewer, payload)
        }
    }

    private fun buildSetModelAndTexture(entityId: Int, modelId: String, textureId: String): ByteArray? {
        val buf = Unpooled.buffer()
        try {
            buf.writeByte(ModernYsmProtocol.Packets.S2C_SET_MODEL_AND_TEXTURE)
            writeVarInt(buf, entityId)
            writeUtf(buf, modelId)
            writeUtf(buf, textureId)
            buf.writeBoolean(false) // disabled
            // 内嵌 S2CSyncPlayerStatePacket：entityId + flags(0)
            writeVarInt(buf, entityId)
            buf.writeShort(0)

            val out = ByteArray(buf.readableBytes())
            buf.readBytes(out)
            return out
        } catch (t: Throwable) {
            Logs.error("Failed to build SetModelAndTexture payload", t)
            return null
        } finally {
            buf.release()
        }
    }

    /**
     * 模型目录发生变化（上传 / 删除 / 重载）时调用。
     *
     * 让已握手的客户端重新走一次目录交换，把新增或消失的模型反映到界面上。
     * 注意：只有模型真的变化时才会被调用，正常游戏过程中不会被触发。
     */
    fun broadcastCatalogChanged() {
        if (sessions.isEmpty()) return
        for ((playerId, session) in sessions) {
            val player = Bukkit.getPlayer(playerId) ?: continue
            if (!session.isHandshakeConfirmed) continue
            Scheduling.entitySpecificScheduler(player).run(Runnable {
                try {
                    val packet = session.onCatalogChanged()
                    if (packet != null) {
                        sendPayload(player, packet)
                    }
                    Logs.info("Model catalog changed; re-syncing {}.", player.name)
                } catch (t: Throwable) {
                    Logs.error("Failed to re-sync ${player.name} after catalog change", t)
                }
            }, null)
        }
    }

    /** 启动每 tick 传输推进。 */
    private fun startTicking() {
        val plugin = YSMSyncMain.instance
        val handle = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
        tickTask = handle
    }

    private fun tick() {
        if (sessions.isEmpty()) return

        for ((playerId, session) in sessions) {
            if (!session.hasPendingTransfers()) continue

            val player = Bukkit.getPlayer(playerId) ?: continue
            if (!player.isOnline) continue

            try {
                var budget = maxBytesPerTick
                while (budget > 0) {
                    val chunk = session.pumpOneChunk(budget) ?: break
                    sendPayload(player, chunk)
                    budget -= chunk.size
                }
            } catch (t: Throwable) {
                Logs.error("Transfer tick failed for ${player.name}", t)
            }
        }
    }

    /** 关闭时释放资源。 */
    fun shutdown() {
        (tickTask as? org.bukkit.scheduler.BukkitTask)?.cancel()
        tickTask = null
        for (session in sessions.values) session.cleanup()
        sessions.clear()
    }

    /**
     * 发送通道负载。优先走原生 CustomPayload 桥（与旧协议保持一致），
     * 通道尚未建立时回退到 Bukkit 插件消息。
     */
    /**
     * 客户端注册的 payload codec 是 `readByteArray()`（见官方 YSM 的 `YsmRawPayload`），
     * 而服务端 `DiscardedPayload` 的编码器是裸 `writeBytes`，两者差一层 `VarInt 长度`。
     * 因此这里补上包装，否则客户端会把包 ID 当成数组长度直接断开。
     */
    var payloadWrapped: Boolean = true

    private fun wrapPayload(payload: ByteArray): ByteArray {
        val buf = Unpooled.buffer(payload.size + 5)
        try {
            writeVarInt(buf, payload.size)
            buf.writeBytes(payload)
            val out = ByteArray(buf.readableBytes())
            buf.readBytes(out)
            return out
        } finally {
            buf.release()
        }
    }

    /**
     * 剥掉客户端的 byteArray 包装。
     *
     * 判据：若首字节解出的 VarInt 恰好等于「剩余字节数」，说明它是长度前缀；
     * 否则视为裸数据（ModernYSM / OpenYSM 的 raw 风格）。
     */
    internal fun unwrapPayload(raw: ByteArray): ByteArray {
        if (raw.size < 2) return raw
        var value = 0
        var pos = 0
        while (pos < raw.size && pos < 5) {
            val b = raw[pos].toInt() and 0xFF
            value = value or ((b and 0x7F) shl (7 * pos))
            pos++
            if (b and 0x80 == 0) break
        }
        if (pos in 1..5 && value == raw.size - pos) {
            return raw.copyOfRange(pos, raw.size)
        }
        return raw
    }

    private fun sendPayload(player: Player, payload: ByteArray) {
        val wire = if (payloadWrapped) wrapPayload(payload) else payload
        if (!PaperYsmPayloadBridge.sendClientboundPayload(player, wire)) {
            player.sendPluginMessage(YSMSyncMain.instance, ModernYsmProtocol.CHANNEL, wire)
        }
    }

    // ---- FriendlyByteBuf 对齐的读写 ----

    private fun readUtf(buf: io.netty.buffer.ByteBuf): String {
        val len = readVarInt(buf)
        val bytes = ByteArray(len)
        buf.readBytes(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readVarInt(buf: io.netty.buffer.ByteBuf): Int {
        var value = 0
        var position = 0
        while (true) {
            val current = buf.readByte()
            value = value or ((current.toInt() and 127) shl position)
            if ((current.toInt() and 128) == 0) break
            position += 7
            if (position >= 32) throw RuntimeException("VarInt too big")
        }
        return value
    }

    private fun writeVarInt(buf: io.netty.buffer.ByteBuf, value: Int) {
        var v = value
        while ((v and -128) != 0) {
            buf.writeByte(v and 127 or 128)
            v = v ushr 7
        }
        buf.writeByte(v)
    }

    private fun writeUtf(buf: io.netty.buffer.ByteBuf, str: String) {
        val bytes = str.toByteArray(Charsets.UTF_8)
        writeVarInt(buf, bytes.size)
        buf.writeBytes(bytes)
    }
}
