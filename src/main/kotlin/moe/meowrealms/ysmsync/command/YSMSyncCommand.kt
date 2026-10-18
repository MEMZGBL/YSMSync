package moe.meowrealms.ysmsync.command

import moe.meowrealms.ysmsync.YSMSyncConstants
import moe.meowrealms.ysmsync.YSMSyncMain
import moe.meowrealms.ysmsync.data.PlayerDataStorage.getYSMSyncData
import moe.meowrealms.ysmsync.model.ModelManager
import moe.meowrealms.ysmsync.network.ClientConnectionManager
import moe.meowrealms.ysmsync.network.ClientConnectionManager.getYsmConnection
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import moe.meowrealms.ysmsync.utils.Scheduling

class YSMSyncCommand : Command(
    "ysmsync",
    "YSMSync plugin main command",
    "/ysmsync <reload|setmodel>",
    listOf()
) {
    private val subcommands: Map<String, (CommandSender, Array<out String>) -> Unit> = mapOf(
        "reload" to ::executeReload,
        "setmodel" to ::executeSetModel
    )

    init {
        this.permission = YSMSyncConstants.PermissionConstants.YSMSYNC_COMMAND
    }

    override fun execute(
        sender: CommandSender,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (!sender.hasPermission(YSMSyncConstants.PermissionConstants.YSMSYNC_COMMAND)) {
            this.sendI18n(sender, YSMSyncConstants.LanguageConstants.NO_PERMISSION)
            return true
        }

        if (args.isEmpty()) {
            this.sendI18n(sender, YSMSyncConstants.LanguageConstants.COMMAND_HELP)
            return true
        }

        val handler = subcommands[args[0].lowercase()]
        if (handler != null) {
            handler(sender, args)
        } else {
            this.sendI18n(sender, YSMSyncConstants.LanguageConstants.COMMAND_UNKNOWN_SUBCOMMAND)
        }

        return true
    }

    // --- subcommands ---

    private fun executeReload(sender: CommandSender, @Suppress("UNUSED_PARAMETER") args: Array<out String>) {
        if (!sender.hasPermission(YSMSyncConstants.PermissionConstants.RELOAD_MODELS_COMMAND)) {
            this.sendI18n(sender, YSMSyncConstants.LanguageConstants.NO_PERMISSION)
            return
        }

        this.sendI18n(sender, YSMSyncConstants.LanguageConstants.RELOAD_MODELS_STARTED)

        Scheduling.asyncScheduler().run(Runnable {
            val loadBegin = System.nanoTime()

            try {
                ModelManager.loadModels()

                val timeElapsed = (System.nanoTime() - loadBegin) / 1_000_000L
                val loadedCount = ModelManager.getLoadedModelCount()

                ClientConnectionManager.restartModelSynchronizationForConnectedPlayers()

                this.sendI18n(
                    sender,
                    YSMSyncConstants.LanguageConstants.RELOAD_MODELS_SUCCESS,
                    listOf("count", "time_ms"),
                    listOf(loadedCount, timeElapsed)
                )

                YSMSyncMain.instance.slF4JLogger.info("Reloaded models by command. Currently has $loadedCount models loaded.")
            } catch (throwable: Throwable) {
                YSMSyncMain.instance.slF4JLogger.error("Failed to reload models by command!", throwable)

                this.sendI18n(
                    sender,
                    YSMSyncConstants.LanguageConstants.RELOAD_MODELS_FAILED,
                    listOf("reason"),
                    listOf(throwable.message ?: throwable.javaClass.simpleName)
                )
            }
        })
    }

    private fun executeSetModel(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission(YSMSyncConstants.PermissionConstants.SET_MODEL_COMMAND)) {
            this.sendI18n(sender, YSMSyncConstants.LanguageConstants.NO_PERMISSION)
            return
        }

        if (args.size < 3) {
            this.sendI18n(sender, YSMSyncConstants.LanguageConstants.SET_MODEL_USAGE)
            return
        }

        val targetPlayer = Bukkit.getPlayer(args[1])
        if (targetPlayer == null) {
            this.sendI18n(sender, YSMSyncConstants.LanguageConstants.SET_MODEL_PLAYER_NOT_FOUND,
                listOf("player"), listOf(args[1]))
            return
        }

        val modelId = args[2]
        if (ModelManager.getModelData(modelId) == null) {
            this.sendI18n(sender, YSMSyncConstants.LanguageConstants.SET_MODEL_NOT_FOUND,
                listOf("model"), listOf(modelId))
            return
        }

        val textureId = if (args.size >= 4) args[3] else null
        val resolvedTexture = ModelManager.resolveTextureId(modelId, textureId)
        if (resolvedTexture == null) {
            this.sendI18n(sender, YSMSyncConstants.LanguageConstants.SET_MODEL_TEXTURE_NOT_FOUND,
                listOf("texture", "model"), listOf(textureId ?: "null", modelId))
            return
        }

        val playerData = targetPlayer.getYSMSyncData()
        if (playerData == null) {
            this.sendI18n(sender, YSMSyncConstants.LanguageConstants.SET_MODEL_PLAYER_NO_DATA,
                listOf("player"), listOf(targetPlayer.name))
            return
        }

        playerData.selectedModelId = modelId
        playerData.selectedModelTexture = resolvedTexture
        playerData.markDirty()

        targetPlayer.getYsmConnection().syncModelSelectionData()

        this.sendI18n(sender, YSMSyncConstants.LanguageConstants.SET_MODEL_SUCCESS,
            listOf("player", "model", "texture"), listOf(targetPlayer.name, modelId, resolvedTexture))
    }

    // --- tab completion ---

    override fun tabComplete(
        sender: CommandSender,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        return when (args.size) {
            1 -> subcommands.keys.filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()

            2 -> if (args[0].equals("setmodel", ignoreCase = true)) {
                Bukkit.getOnlinePlayers().map { it.name }
                    .filter { it.startsWith(args[1], ignoreCase = true) }.toMutableList()
            } else mutableListOf()

            3 -> if (args[0].equals("setmodel", ignoreCase = true)) {
                ModelManager.getModelIds()
                    .filter { it.startsWith(args[2], ignoreCase = true) }.toMutableList()
            } else mutableListOf()

            4 -> if (args[0].equals("setmodel", ignoreCase = true)) {
                ModelManager.getTexturesOf(args[2])
                    .filter { it.startsWith(args[3], ignoreCase = true) }.toMutableList()
            } else mutableListOf()

            else -> mutableListOf()
        }
    }

    // --- utils ---

    private fun sendI18n(
        sender: CommandSender,
        key: String,
        subKeys: List<String> = emptyList(),
        args: List<Any> = emptyList()
    ) {
        sender.sendMessage(YSMSyncMain.languageManager.i18n(key, subKeys, args))
    }
}
