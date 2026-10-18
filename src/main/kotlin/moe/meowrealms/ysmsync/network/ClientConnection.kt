package moe.meowrealms.ysmsync.network

import moe.meowrealms.ysmsync.YSMSyncConstants
import moe.meowrealms.ysmsync.YSMSyncMain
import moe.meowrealms.ysmsync.data.PlayerDataStorage.getYSMSyncData
import moe.meowrealms.ysmsync.model.ModelManager
import moe.meowrealms.ysmsync.network.ClientConnectionManager.getYsmConnection
import moe.meowrealms.ysmsync.network.ClientConnectionManager.getConfirmedYsmConnectionOrNull
import moe.meowrealms.ysmsync.network.packet.Packet
import moe.meowrealms.ysmsync.network.packet.PacketHandler
import moe.meowrealms.ysmsync.network.packet.c2s.C2SAnimationRequestPacket
import moe.meowrealms.ysmsync.network.packet.c2s.C2SHandshakeConfirmedPacket
import moe.meowrealms.ysmsync.network.packet.c2s.C2SModelDataPayload
import moe.meowrealms.ysmsync.network.packet.c2s.C2SModelSwitchRequestPacket
import moe.meowrealms.ysmsync.network.packet.c2s.C2SMolangExecuteRequestPacket
import moe.meowrealms.ysmsync.network.packet.c2s.C2SMolangExpressionValueSyncPacket
import moe.meowrealms.ysmsync.network.packet.c2s.C2SStarModelPacket
import moe.meowrealms.ysmsync.network.packet.s2c.S2CAuthModelListPacket
import moe.meowrealms.ysmsync.network.packet.s2c.S2CEntityModelAnimationDataPacket
import moe.meowrealms.ysmsync.network.packet.s2c.S2CEntityModelSelectionDataPacket
import moe.meowrealms.ysmsync.network.packet.s2c.S2CHandshakeRequestPacket
import moe.meowrealms.ysmsync.network.packet.s2c.S2CMolangExecutePacket
import moe.meowrealms.ysmsync.network.packet.s2c.S2CMolangExpressionValueSyncPacket
import moe.meowrealms.ysmsync.network.packet.s2c.S2CStarModelListPacket
import moe.meowrealms.ysmsync.network.sync.ModelSynchronizationContext
import moe.meowrealms.ysmsync.tracker.EntityTracker
import moe.meowrealms.ysmsync.web.WebPortalManager
import org.bukkit.entity.Player
import moe.meowrealms.ysmsync.utils.Scheduling
import moe.meowrealms.ysmsync.utils.Scheduling.TaskHandle

class ClientConnection (
    private val player: Player,
): PacketHandler {
    private lateinit var synchronizationContext: ModelSynchronizationContext
    private var tickTask: TaskHandle? = null
    private var handshakeConfirmed: Boolean = false

    fun isHandshakeConfirmed(): Boolean = this.handshakeConfirmed

    fun onConnected() {
        this.synchronizationContext = ModelManager.createNewModelSynchronizationContext(this.player)

        YSMSyncMain.instance.slF4JLogger.info("Sending handshake to player ${this.player.uniqueId}")
        this.send(S2CHandshakeRequestPacket(ClientConnectionManager.VERSION))

        // kick-start ticking
        Scheduling.entitySpecificScheduler(this.player).runAtFixedRate(Runnable{
            this.tickConnection()
        }, null, 1L, 1L)?.let {
            this.tickTask = it
        }
    }

    fun onDisconnected() {
        this.synchronizationContext.cleanup()

        this.tickTask?.cancel()
    }

    fun restartModelSynchronizationAfterReload() {
        if (!this.handshakeConfirmed) {
            return
        }

        this.synchronizationContext.restart()

        val playerData = this.player.getYSMSyncData()

        playerData?.validateAndCorrectModelSelection(
            YSMSyncConstants.ModelDefaults.DEFAULT_MODEL_ID,
            YSMSyncConstants.ModelDefaults.DEFAULT_MODEL_TEXTURE
        )

        this.syncModelSubscribes()
        this.syncModelSelectionData()
        this.reassertVisibleEntityRenderStates()
    }

    private fun reassertVisibleEntityRenderStates() {
        // ClientModelManager rebuilds its model cache asynchronously during a
        // synchronization. That rebuild can reset forceDisabled on already
        // tracked vanilla players after their first selection packet arrived.
        // Reapply all visible entity states now and after both the immediate
        // and deferred client-side cache work have had time to finish.
        EntityTracker.resynchronizeViewer(this.player)

        for (delayTicks in longArrayOf(5L, 40L)) {
            Scheduling.entitySpecificScheduler(this.player).runDelayed(Runnable {
                if (this.handshakeConfirmed && this.player.isOnline) {
                    EntityTracker.resynchronizeViewer(this.player)
                }
            }, null, delayTicks)
        }
    }

    private fun syncEntityStatesToAnimationData() {
        val playerData = this.player.getYSMSyncData() ?: return
        val animationData = playerData.animationData

        animationData.health(this.player.health.toInt())
        animationData.expLevel(this.player.exp.toInt())
        animationData.foodLevel(this.player.foodLevel)
        animationData.flying(this.player.isFlying)
    }

    private fun checkAndSyncAnimationState() {
        val playerData = this.player.getYSMSyncData() ?: return

        if (playerData.animationData.tickPacketSyncRequired) {
            playerData.animationData.tickPacketSyncRequired = false
            this.syncModelAnimationData()
        }
    }

    fun tickConnection() {
        this.syncEntityStatesToAnimationData()

        this.checkAndSyncAnimationState()
    }

    fun syncModelSubscribes() {
        val playerData = this.player.getYSMSyncData() ?: return

        this.send(S2CAuthModelListPacket(ModelManager.getAuthRequiredModels()))
        this.send(S2CStarModelListPacket(playerData.staredModels))
    }

    fun syncModelSelectionDataTo(player: Player) {
        val playerData = this.player.getYSMSyncData() ?: return
        // Keep a private model private to unauthorised viewers: they receive
        // the normal fallback instead of a reference to a model they were not
        // sent during the cache-list sync.
        val allowed = WebPortalManager.isVisibleTo(player.uniqueId, playerData.selectedModelId)
        val fallback = ModelManager.getDefaultModelConfig(
            YSMSyncConstants.ModelDefaults.DEFAULT_MODEL_ID,
            YSMSyncConstants.ModelDefaults.DEFAULT_MODEL_TEXTURE
        )

        player.getConfirmedYsmConnectionOrNull()?.send(S2CEntityModelSelectionDataPacket(
            this.player.entityId,
            if (allowed) playerData.selectedModelId else fallback.left,
            if (allowed) playerData.selectedModelTexture else fallback.right,
            playerData.disabled,
            playerData.animationData
        ))
    }

    fun syncModelAnimationDataTo(player: Player) {
        val playerData = this.player.getYSMSyncData() ?: return

        player.getConfirmedYsmConnectionOrNull()?.send(S2CEntityModelAnimationDataPacket(
            playerData.animationData
        ))
    }

    fun syncModelFullDataTo(player: Player) {
        this.syncModelSelectionDataTo(player)
        this.syncModelAnimationDataTo(player)
    }

    fun syncModelFullData() {
        this.syncModelFullDataTo(this.player)

        for (visible in EntityTracker.getVisible(this.player)) {
            this.syncModelFullDataTo(visible)
        }
    }

    fun syncModelSelectionData() {
        this.syncModelSelectionDataTo(this.player)

        for (visible in EntityTracker.getVisible(this.player)) {
            this.syncModelSelectionDataTo(visible)
        }
    }

    fun syncModelAnimationData() {
        this.syncModelAnimationDataTo(this.player)

        for (visible in EntityTracker.getVisible(this.player)) {
            this.syncModelAnimationDataTo(visible)
        }
    }

    fun handleHandshakeCallback() {
        this.synchronizationContext.begin()

        // sync to ourselves
        this.syncModelSelectionDataTo(this.player)

        // model subscribes
        this.syncModelSubscribes()

        // The player may already be tracking vanilla and YSM players before
        // their handshake completes. Send their current states now, including
        // an explicit vanilla fallback for clients without YSM.
        this.reassertVisibleEntityRenderStates()
        EntityTracker.resynchronizeWatchers(this.player)
    }

    fun broadcastMolangExecute(expression: String) {
        val toSend = S2CMolangExecutePacket(intArrayOf(this.player.entityId), expression)

        // ourself
        this.send(toSend)

        // others
        for (target in EntityTracker.getVisible(this.player)) {
            target.getConfirmedYsmConnectionOrNull()?.send(toSend)
        }
    }

    override fun receivingDirection(): EnumDirection {
        return EnumDirection.C_T_S
    }

    override fun sendingDirection(): EnumDirection {
        return EnumDirection.S_T_C
    }

    override fun send(packet: Packet) {
        ClientConnectionManager.sendMessage(this.player, packet)
    }

    override fun handleClientModelSyncPayload(packet: C2SModelDataPayload) {
        this.synchronizationContext.handleClientReply(packet.payload)
    }

    override fun handleHandshakeConfirmed(packet: C2SHandshakeConfirmedPacket) {
        // Both the native Purpur bridge and ProtocolLib can observe the same
        // Forge custom payload. Handshake processing must be idempotent or a
        // duplicate callback restarts synchronization and overwrites entity
        // render state that was just sent to the client.
        if (this.handshakeConfirmed) {
            return
        }
        this.handshakeConfirmed = true

        YSMSyncMain.instance.slF4JLogger.info("Received handshake confirmed packet from player ${this.player.name}. Begin model synchronization")

        this.handleHandshakeCallback()
    }

    override fun handleModelSwitchRequest(packet: C2SModelSwitchRequestPacket) {
        if (!WebPortalManager.canUse(this.player.uniqueId, packet.modelId)) {
            YSMSyncMain.instance.slF4JLogger.warn("Player ${this.player.name} attempted to use a private model without access: ${packet.modelId}")
            return
        }
        val validate = ModelManager.validateSelectedModel(packet.modelId, packet.textureId)

        if (!validate.left || !validate.right) {
            YSMSyncMain.instance.slF4JLogger.warn("Player ${this.player.name} is trying switching to an unknown model ${packet.modelId} with texture ${packet.textureId}!")
            return
        }

        val playerData = this.player.getYSMSyncData() ?: return

        playerData.selectedModelId = packet.modelId
        playerData.selectedModelTexture = packet.textureId

        playerData.markDirty()

        this.syncModelSelectionData()
    }

    override fun handleAnimationRequest(packet: C2SAnimationRequestPacket) {
        val playerData = this.player.getYSMSyncData() ?: return

        if (packet.entityId != -1) {
            // TODO - This is for TLM, not ours to process
            return
        }

        if (packet.animationIndex == -1) {
            playerData.animationData.extraAnimation("")

            this.syncModelAnimationData()
            return
        }

        val selectionValidated = playerData.validateModelSelection()
        if (!selectionValidated) {
            YSMSyncMain.instance.slF4JLogger.warn("Player ${this.player.name} is trying playing animations with an invalid model selection ${playerData.selectedModelId} - ${playerData.selectedModelTexture}!")
            return
        }

        val animationLookup = ModelManager.lookupAnimationFromPacket(playerData.selectedModelId, packet.animationIndex, packet.category)
        if (animationLookup == null) {
            YSMSyncMain.instance.slF4JLogger.warn("Player ${this.player.name} is trying playing an invalid animation ${packet.category} - ${packet.animationIndex}!")
            return
        }

        playerData.animationData.extraAnimation(animationLookup)
        this.syncModelAnimationData()
    }

    override fun handleStarModel(packet: C2SStarModelPacket) {
        val playerData = this.player.getYSMSyncData() ?: return

        if (packet.add) {
            playerData.staredModels.add(packet.modelId)
        } else {
            playerData.staredModels.remove(packet.modelId)
        }

        playerData.markDirty()
    }

    override fun handleMolangExecuteRequest(packet: C2SMolangExecuteRequestPacket) {
        // uhm 好吧我也不知道ysm在数据包里留个这个是干啥的,按理来说sender都能获取到应该也用不着这个
        // 可能是给TLM留的 ?()
        if (packet.onEntityId != this.player.entityId) {
            return
        }

        this.broadcastMolangExecute(packet.expression)
    }

    override fun handleMolangExpressionValueSyncPacket(packet: C2SMolangExpressionValueSyncPacket) {
        val toSend = S2CMolangExpressionValueSyncPacket(this.player.entityId, packet.expressionValues)

        this.send(toSend)
        for (watching in EntityTracker.getVisible(this.player)) {
            watching.getConfirmedYsmConnectionOrNull()?.send(toSend)
        }
    }
}
