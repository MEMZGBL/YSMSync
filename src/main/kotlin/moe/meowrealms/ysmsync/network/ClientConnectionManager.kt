package moe.meowrealms.ysmsync.network

import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import io.netty.util.ReferenceCountUtil
import moe.meowrealms.ysmsync.YSMSyncMain
import moe.meowrealms.ysmsync.network.packet.Packet
import moe.meowrealms.ysmsync.utils.SimpleFriendlyByteBuf
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.messaging.PluginMessageListener
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import moe.meowrealms.ysmsync.utils.Scheduling

object ClientConnectionManager : Listener, PluginMessageListener {
    const val VERSION = "2.6.0"
    private val LISTENING_CHANNEL = NamespacedKey("yes_steve_model", VERSION.replace(".", "_"))
    private val LISTENING_CHANNEL_STR = LISTENING_CHANNEL.toString()

    private val connectedPlayers : MutableMap<UUID, ClientConnection> = ConcurrentHashMap()

    fun init() {
        Bukkit.getPluginManager().registerEvents(this, YSMSyncMain.instance)

        Bukkit.getMessenger().registerIncomingPluginChannel(YSMSyncMain.instance, LISTENING_CHANNEL_STR, this)
        Bukkit.getMessenger().registerOutgoingPluginChannel(YSMSyncMain.instance, LISTENING_CHANNEL_STR)

        // Paper 26.x 下 NeoForge 客户端发送的是标准 CustomPacketPayload。
        // 原生桥在 Netty 管线内直接接管 YSM 通道；Bukkit 监听作为兜底保留。

        YSMSyncMain.instance.slF4JLogger.info("Initialized network manager.")
    }

    // note: packets are coming from netty threads
    override fun onPluginMessageReceived(
        channel: String,
        player: Player,
        message: ByteArray
    ) {
        if (channel != LISTENING_CHANNEL_STR) {
            return
        }

        // 原生桥已消费该负载时，Bukkit 不应重复派发
        if (PaperYsmPayloadBridge.isBridged(player)) {
            return
        }

        // push back to main thread
        // so that we could manage the states of a single connection safely and easily
        Scheduling.entitySpecificScheduler(player).run(Runnable {
            val messageAsBuffer = SimpleFriendlyByteBuf(Unpooled.wrappedBuffer(message))

            try {
                this.onClientMessage(player, messageAsBuffer)
            }finally {
                ReferenceCountUtil.safeRelease(messageAsBuffer)
            }
        }, null)
    }

    @EventHandler
    fun onPlayerJoinEvent(event: PlayerJoinEvent) {
        YSMSyncMain.instance.slF4JLogger.info("Player {} has connected with UUID {}", event.player.name, event.player.uniqueId)

        PaperYsmPayloadBridge.inject(event.player)

        val newConnection = ClientConnection(event.player)

        this.connectedPlayers[event.player.uniqueId] = newConnection

        // we are already on the target thread, no need to schedule back

        // catch and pass through exceptions
        try {
            newConnection.onConnected()
        }catch (throwable : Throwable){
            this.connectionExceptionCaught(event.player, throwable)
        }
    }

    @EventHandler
    fun onPlayerQuitEvent(event: PlayerQuitEvent) {
        PaperYsmPayloadBridge.remove(event.player)
        val disconnected = this.connectedPlayers.remove(event.player.uniqueId)

        if (disconnected != null) {
            // catch and pass through exceptions
            try {
                disconnected.onDisconnected()
            }catch (throwable : Throwable){
                this.connectionExceptionCaught(event.player, throwable)
            }

            YSMSyncMain.instance.slF4JLogger.info("Disconnected connection for player {}", event.player.name)
        }
    }

    fun connectionExceptionCaught(player: Player, throwable: Throwable) {
        YSMSyncMain.instance.slF4JLogger.error("Error has caught during packet processing! Packet sender is ${player.name}.", throwable)
    }

    fun restartModelSynchronizationForConnectedPlayers() {
        for ((playerUuid, connection) in this.connectedPlayers) {
            val player = Bukkit.getPlayer(playerUuid) ?: continue

            Scheduling.entitySpecificScheduler(player).run(Runnable {
                try {
                    connection.restartModelSynchronizationAfterReload()
                } catch (throwable: Throwable) {
                    this.connectionExceptionCaught(player, throwable)
                }
            }, null)
        }
    }

    fun sendMessage(receiver: Player, packet: Packet) {
        try {
            val packetId = YSMSyncMain.packetRegistry.lookupForId(packet)

            if (packetId == -1) {
                YSMSyncMain.instance.slF4JLogger.warn("Sending unknown packet of id: {}", packetId)
                return
            }

            val tempBuffer = SimpleFriendlyByteBuf(Unpooled.buffer())
            var encodedPacket: ByteArray?

            try {
                tempBuffer.writeByte(packetId)

                packet.write(tempBuffer)
            }finally {
                try {
                    encodedPacket = ByteArray(tempBuffer.readableBytes())
                    tempBuffer.readBytes(encodedPacket)
                }finally {
                    ReferenceCountUtil.safeRelease(tempBuffer)
                }
            }

            // 优先走原生 CustomPayload；通道尚未建立时回退到 Bukkit 插件消息
            if (!PaperYsmPayloadBridge.sendClientboundPayload(receiver, encodedPacket)) {
                receiver.sendPluginMessage(YSMSyncMain.instance, LISTENING_CHANNEL_STR, encodedPacket)
            }
        }catch (throwable : Throwable){
            this.connectionExceptionCaught(receiver, throwable)
        }
    }

    fun onClientMessage(sender: Player, packetData: SimpleFriendlyByteBuf) {
        val connection = this.connectedPlayers[sender.uniqueId]

        if (connection == null) {
            YSMSyncMain.instance.slF4JLogger.warn("Receiving packets for uninitialized connection for player {}!", sender.uniqueId)
            return
        }

        try {
            val packetInt = packetData.readByte().toInt()

            val createdPacket = YSMSyncMain.packetRegistry.lookupAndConstruct(packetInt)
                ?: throw DecoderException("Packet with id $packetInt not found!")

            val direction = createdPacket.direction()
            val receivingDirection = connection.receivingDirection()

            if (direction != receivingDirection) {
                throw DecoderException("Handling packets with mismatched direction $direction! Expected direction is $receivingDirection!")
            }

            createdPacket.read(packetData)

            if (packetData.readableBytes() != 0) {
                throw DecoderException("Packet $packetInt has ${packetData.readableBytes()} unread bytes")
            }

            createdPacket.handle(connection)
        } catch (throwable: Throwable) {
            this.connectionExceptionCaught(sender, throwable)
        }
    }

    /**
     * Purpur 1.19.4 does not consistently forward Forge custom-payload C2S
     * messages to Bukkit's PluginMessageListener. The Netty bridge calls this
     * method after extracting a YSM payload from the wire.
     */
    @JvmStatic
    fun onNativeClientMessage(sender: Player, message: ByteArray) {
        Bukkit.getScheduler().runTask(YSMSyncMain.instance, Runnable {
            if (!sender.isOnline) return@Runnable

            val messageAsBuffer = SimpleFriendlyByteBuf(Unpooled.wrappedBuffer(message))
            try {
                onClientMessage(sender, messageAsBuffer)
            } finally {
                ReferenceCountUtil.safeRelease(messageAsBuffer)
            }
        })
    }

    fun Player.getYsmConnectionOrNull(): ClientConnection? {
        return connectedPlayers[this.uniqueId]
    }

    /** A connection exists for every joining player; only confirmed ones run YSM. */
    fun Player.getConfirmedYsmConnectionOrNull(): ClientConnection? {
        return connectedPlayers[this.uniqueId]?.takeIf { it.isHandshakeConfirmed() }
    }

    fun Player.getYsmConnection(): ClientConnection {
        return connectedPlayers[this.uniqueId]!!
    }
}
