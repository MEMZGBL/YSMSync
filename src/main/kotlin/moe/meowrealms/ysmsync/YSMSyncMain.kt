package moe.meowrealms.ysmsync

import it.unimi.dsi.fastutil.floats.FloatArrayList
import moe.meowrealms.ysmsync.command.YSMSyncCommand
import moe.meowrealms.ysmsync.data.PlayerDataStorage
import moe.meowrealms.ysmsync.i18n.I18NManager
import moe.meowrealms.ysmsync.model.ModelManager
import moe.meowrealms.ysmsync.network.ClientConnectionManager
import moe.meowrealms.ysmsync.web.WebPortalManager
import moe.meowrealms.ysmsync.web.WebPortalCommand
import moe.meowrealms.ysmsync.network.data.DispatchServerDrivenProperty
import moe.meowrealms.ysmsync.network.packet.PacketRegistry
import moe.meowrealms.ysmsync.network.packet.c2s.*
import moe.meowrealms.ysmsync.network.packet.s2c.*
import moe.meowrealms.ysmsync.tracker.EntityTracker
import org.bukkit.plugin.java.JavaPlugin


class YSMSyncMain : JavaPlugin() {

    companion object {
        lateinit var instance: YSMSyncMain
        lateinit var packetRegistry: PacketRegistry
        val languageManager: I18NManager = I18NManager()
        var inited = false
    }

    fun registerPackets() {
        packetRegistry.register(51, S2CHandshakeRequestPacket::class) { S2CHandshakeRequestPacket() }
        packetRegistry.register(52, C2SHandshakeConfirmedPacket::class) { C2SHandshakeConfirmedPacket() }

        packetRegistry.register(1, S2CModelDataPayloadPacket::class) { S2CModelDataPayloadPacket(ByteArray(0)) }
        packetRegistry.register(2, C2SModelDataPayload::class) { C2SModelDataPayload(ByteArray(0)) }

        packetRegistry.register(4, S2CEntityModelSelectionDataPacket::class) {
            S2CEntityModelSelectionDataPacket(
                0,
                "",
                "",
                false,
                DispatchServerDrivenProperty(0)
            )
        }
        packetRegistry.register(21, S2CEntityModelAnimationDataPacket::class) {
            S2CEntityModelAnimationDataPacket(
                DispatchServerDrivenProperty(0)
            )
        }

        packetRegistry.register(3, S2CMolangExecutePacket::class) { S2CMolangExecutePacket(IntArray(0), "") }
        packetRegistry.register(5, C2SModelSwitchRequestPacket::class) { C2SModelSwitchRequestPacket("", "") }
        packetRegistry.register(7, C2SAnimationRequestPacket::class) { C2SAnimationRequestPacket(0, "", 0) }
        packetRegistry.register(6, S2CAuthModelListPacket::class) { S2CAuthModelListPacket(HashSet()) }
        packetRegistry.register(8, S2CStarModelListPacket::class) { S2CStarModelListPacket(HashSet()) }
        packetRegistry.register(9, C2SStarModelPacket::class) { C2SStarModelPacket("", true) }
        packetRegistry.register(17, C2SMolangExecuteRequestPacket::class) { C2SMolangExecuteRequestPacket("", 0) }
        packetRegistry.register(19, S2CMolangExpressionValueSyncPacket::class) { S2CMolangExpressionValueSyncPacket(0, FloatArrayList()) }
        packetRegistry.register(18, C2SMolangExpressionValueSyncPacket::class) { C2SMolangExpressionValueSyncPacket(FloatArrayList()) }
    }

    fun initNetworking() {
        packetRegistry = PacketRegistry()

        this.registerPackets()

        ClientConnectionManager.init()
    }

    fun initAndLoadModels(){
        this.slF4JLogger.info("Begin loading models")

        ModelManager.setWorkingDir(this.dataFolder.toPath())

        ModelManager.initEnv()

        val loadBegin = System.nanoTime()

        ModelManager.loadModels()

        val timeElapsed = System.nanoTime() - loadBegin

        this.slF4JLogger.info("Model loading took ${timeElapsed / 1000_000L} ms")
        this.slF4JLogger.info("All models has been loaded! Currently has ${ModelManager.getLoadedModelCount()} models loaded.")
    }

    fun initDataStorage() {
        PlayerDataStorage.setWorkingDir(this.dataFolder.toPath())

        PlayerDataStorage.init()

        this.slF4JLogger.info("Data storage initialized.")
    }

    fun initEntityTracker() {
        EntityTracker.init()

        this.slF4JLogger.info("Entity tracker initialized.")
    }

    fun initI18n() {
        languageManager.loadLanguageFile("zh_CN")

        this.slF4JLogger.info("I18n initialized.")
    }

    fun initConfig() {
        this.saveDefaultConfig()

        val cfg = this.config
        YSMSyncConstants.ModelSyncConstants.PER_PLAYER_RATE_LIMIT_MBPS =
            cfg.getDouble("model-sync.per-player-rate-limit-mbps", 8.0)
        YSMSyncConstants.ModelSyncConstants.GLOBAL_RATE_LIMIT_MBPS =
            cfg.getDouble("model-sync.global-rate-limit-mbps", 40.0)
        YSMSyncConstants.ModelDefaults.DEFAULT_MODEL_ID =
            cfg.getString("model-defaults.model-id", "default")!!
        YSMSyncConstants.ModelDefaults.DEFAULT_MODEL_TEXTURE =
            cfg.getString("model-defaults.texture", "default")!!

        this.slF4JLogger.info("Config loaded: per-player=${YSMSyncConstants.ModelSyncConstants.PER_PLAYER_RATE_LIMIT_MBPS}Mbps, global=${YSMSyncConstants.ModelSyncConstants.GLOBAL_RATE_LIMIT_MBPS}Mbps, defaultModel=${YSMSyncConstants.ModelDefaults.DEFAULT_MODEL_ID}/${YSMSyncConstants.ModelDefaults.DEFAULT_MODEL_TEXTURE}")
    }

    fun initCommands() {
        this.server.commandMap.register("ysmsync", YSMSyncCommand())
        this.server.commandMap.register("ysmsyncweb", WebPortalCommand())

        this.slF4JLogger.info("Commands initialized.")
    }

    override fun onEnable() {
        instance = this

        this.initConfig()
        this.initI18n()
        this.initNetworking()
        this.initAndLoadModels()
        this.initDataStorage()
        this.initEntityTracker()
        this.initCommands()

        // Web 门户是可选附属功能。它内部已对端口占用做了降级处理，
        // 这里再兜一层：任何意外异常都不能让 onEnable 抛出，
        // 否则服务端会禁用整个插件，模型同步（主功能）也会一起失效。
        try {
            WebPortalManager.start(this)
        } catch (throwable: Throwable) {
            this.slF4JLogger.error("启动 YSM Web 门户时发生异常，已跳过；模型同步功能不受影响。", throwable)
        }

        inited = true
    }

    override fun onDisable() {
        WebPortalManager.stop()
        PlayerDataStorage.saveAll()
    }
}
