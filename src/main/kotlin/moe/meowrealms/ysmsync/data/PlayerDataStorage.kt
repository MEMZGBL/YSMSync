package moe.meowrealms.ysmsync.data

import moe.meowrealms.ysmsync.YSMSyncConstants
import moe.meowrealms.ysmsync.YSMSyncMain
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import moe.meowrealms.ysmsync.utils.Scheduling

// TODO 这块让ai写的()
object PlayerDataStorage : Listener {
    private val loaded: MutableMap<UUID, PlayerData> = ConcurrentHashMap()

    private val operationTails: ConcurrentHashMap<UUID, CompletableFuture<Void>> = ConcurrentHashMap()
    private val pendingSaves: MutableSet<CompletableFuture<Void>> = ConcurrentHashMap.newKeySet()

    private lateinit var dataDir: Path
    private lateinit var asyncExecutor: Executor

    fun setWorkingDir(dir: Path) {
        this.dataDir = dir.resolve("player_data").also {
            Files.createDirectories(it)
        }
    }

    fun init() {
        this.asyncExecutor = Scheduling.asyncScheduler()

        Bukkit.getPluginManager().registerEvents(this, YSMSyncMain.instance)
    }

    fun saveAll() {
        loaded.forEach { (uuid, data) ->
            if (data.isDirty()) {
                enqueueSave(uuid, data)
            }
        }

        val allFutures = pendingSaves.toTypedArray<CompletableFuture<Void>>()
        try {
            CompletableFuture.allOf(*allFutures).join()
        } catch (e: Exception) {
            YSMSyncMain.instance.slF4JLogger.error("Some saves failed during shutdown", e)
        }
    }

    private fun resolveDataFile(uuid: UUID): Path = dataDir.resolve("$uuid.json")

    private fun doSave(uuid: UUID, data: PlayerData) {
        val file = resolveDataFile(uuid)
        Files.writeString(
            file,
            PlayerData.GSON.toJson(data),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        )
    }

    private fun doLoad(uuid: UUID): PlayerData? {
        val file = resolveDataFile(uuid)
        if (!Files.exists(file)) return null
        val json = Files.readString(file)
        return PlayerData.GSON.fromJson(json, PlayerData::class.java)
    }

    private fun enqueueOp(uuid: UUID, task: Runnable): CompletableFuture<Void> {
        val newFuture = CompletableFuture<Void>()

        operationTails.compute(uuid) { _, oldTail ->
            val chain = if (oldTail == null || oldTail.isDone) {
                CompletableFuture.runAsync(task, asyncExecutor)
            } else {
                oldTail.thenRunAsync(task, asyncExecutor)
            }

            chain.whenComplete { _, ex ->
                if (ex != null) newFuture.completeExceptionally(ex)
                else newFuture.complete(null)
            }
            chain
        }

        return newFuture
    }

    private fun enqueueLoad(uuid: UUID): CompletableFuture<PlayerData?> {
        val resultFuture = CompletableFuture<PlayerData?>()
        val task = Runnable {
            try {
                val fromDisk = doLoad(uuid)
                val data = fromDisk ?: PlayerData().also {
                    YSMSyncMain.instance.slF4JLogger.info("No existing data for $uuid, creating new")
                }
                loaded[uuid] = data
                YSMSyncMain.instance.slF4JLogger.info("Loaded player data for $uuid")
                resultFuture.complete(data)
            } catch (e: Exception) {
                YSMSyncMain.instance.slF4JLogger.error("Failed to load player data for $uuid", e)
                resultFuture.completeExceptionally(e)
            }
        }

        enqueueOp(uuid, task)
        return resultFuture
    }

    private fun enqueueSave(uuid: UUID, data: PlayerData): CompletableFuture<Void> {
        val task = Runnable {
            try {
                doSave(uuid, data)
                YSMSyncMain.instance.slF4JLogger.info("Saved player data for $uuid")
            } catch (e: Exception) {
                YSMSyncMain.instance.slF4JLogger.error("Failed to save player data for $uuid", e)
                throw e
            }
        }

        val future = enqueueOp(uuid, task)
        pendingSaves.add(future)

        future.whenComplete { _, _ -> pendingSaves.remove(future) }
        return future
    }

    @EventHandler
    fun onPlayerPreJoin(event: AsyncPlayerPreLoginEvent) {
        val uuid = event.uniqueId

        val oldTail = operationTails[uuid]
        if (oldTail != null && !oldTail.isDone) {
            try {
                oldTail.join()
            } catch (e: Exception) {
                YSMSyncMain.instance.slF4JLogger.error("Previous operation failed for $uuid, continuing anyway", e)
            }
        }

        try {
            enqueueLoad(uuid).join()
        } catch (e: Exception) {
            YSMSyncMain.instance.slF4JLogger.error("Failed to load data for $uuid", e)
        }
    }

    @EventHandler
    fun onPlayerJoined(event: PlayerJoinEvent) {
        val player = event.player
        val data = loaded[player.uniqueId]
            ?: throw IllegalStateException("Player data missing at join for ${player.uniqueId}!")

        data.validateAndCorrectModelSelection(
            YSMSyncConstants.ModelDefaults.DEFAULT_MODEL_ID,
            YSMSyncConstants.ModelDefaults.DEFAULT_MODEL_TEXTURE
        )
        data.initSubComponents(player)
    }

    @EventHandler
    fun onPlayerQuited(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        val data = loaded.remove(uuid) ?: return

        if (data.isDirty()) {
            enqueueSave(uuid, data)
        }
    }

    fun Player.getYSMSyncData(): PlayerData? = loaded[uniqueId]
}