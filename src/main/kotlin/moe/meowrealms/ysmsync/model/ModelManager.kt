package moe.meowrealms.ysmsync.model

import com.elfmcys.yesstevemodel.model.format.ServerAnimationInfo
import com.elfmcys.yesstevemodel.model.format.ServerModelData
import com.elfmcys.yesstevemodel.resource.YSMBinaryDeserializer
import com.elfmcys.yesstevemodel.resource.YSMBinarySerializer
import com.elfmcys.yesstevemodel.resource.YSMFolderDeserializer
import com.elfmcys.yesstevemodel.resource.models.ModelProperties
import com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel
import com.elfmcys.yesstevemodel.util.data.OrderedStringMap
import com.google.gson.JsonParser
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSet
import it.unimi.dsi.fastutil.objects.Object2ObjectMap
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import moe.meowrealms.ysmsync.YSMSyncMain
import moe.meowrealms.ysmsync.network.sync.ModelSynchronizationContext
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.tuple.Pair
import org.bukkit.entity.Player
import rip.ysm.security.YsmCrypt
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool
import moe.meowrealms.ysmsync.utils.Scheduling

// TODO 等ysm开源了重写()
object ModelManager {
    private lateinit var workingDir: Path
    private lateinit var keyFilePath: Path
    private lateinit var cacheFolderPath: Path
    private lateinit var cacheDeterminerFilePath: Path

    private lateinit var modelsDir: Path
    private lateinit var builtinModelsFolderPath: Path
    private lateinit var customModelsFolderPath: Path
    private lateinit var authModelsFolderPath: Path

    var cacheDeterminer: Long = 0
    private lateinit var cacheKey: ByteArray

    val secureRand = SecureRandom()

    private val loadedPacks: MutableMap<String, ServerPackData> = ConcurrentHashMap()

    @Volatile
    private var availableCaches: IntSet = IntOpenHashSet()
    @Volatile
    private var name2ModelData: Object2ObjectMap<String, ServerModelData> = Object2ObjectOpenHashMap()
    @Volatile
    private var authRequiredModels: Set<String> = HashSet()

    private var modelSynchronizationContexts: MutableMap<UUID, ModelSynchronizationContext> = ConcurrentHashMap()

    fun getAuthRequiredModels(): Set<String> {
        return this.authRequiredModels
    }

    fun lookupAnimationFromPacket(modelId: String, index: Int, category: String): String? {
        val modelData = this.name2ModelData[modelId] ?: return null

        val modelProperties: ModelProperties = modelData.loadedModelData.modelProperties
        val extraAnimationClassify = modelProperties.extraAnimationClassify
        var extraAnimations: OrderedStringMap<String, String>

        if (StringUtils.isNotBlank(category)) {
            val tryFetch = extraAnimationClassify[category]

            if (tryFetch != null) {
                extraAnimations = tryFetch
            } else {
                extraAnimations = modelProperties.extraAnimation
            }

        } else {
            extraAnimations = modelProperties.extraAnimation
        }

        if (extraAnimations.size > index) {
           return extraAnimations.getKeyAt(index)
        }

        return null
    }

    fun defaultTextureOf(model: String): String? {
        val modelData = this.name2ModelData[model] ?: return null

        return modelData.modelInfo.textures.firstOrNull()
    }

    fun validateSelectedModel(modelId: String, texture: String): Pair<Boolean, Boolean> {
        val modelData = this.name2ModelData[modelId] ?: return Pair.of(false, false)

        return Pair.of(true, modelData.modelInfo.textures.contains(texture))
    }

    fun getDefaultModelConfig(defaultModelId: String, defaultModelTexture: String): Pair<String, String> {
        var defaultTexture = defaultModelTexture

        if (defaultTexture.lowercase(Locale.getDefault()).endsWith(".png") && defaultTexture.length > 4) {
            defaultTexture = defaultTexture.substring(0, defaultTexture.length - 4)
        }

        if (!this.loadedPacks.isEmpty()) { // TODO: 这里其实借助了builtin模型自带的pack判断的)
            return Pair.of<String, String>(defaultModelId, defaultTexture)
        } else {
            val modelData = this.name2ModelData[defaultModelId]
            if (modelData == null) {
                return Pair.of<String, String>("default", "default")
            } else {
                if (!modelData.modelInfo.textures.contains(defaultTexture)) {
                    if (modelData.modelInfo.textures
                            .contains(modelData.loadedModelData.modelProperties.defaultTexture)
                    ) {
                        defaultTexture = modelData.loadedModelData.modelProperties.defaultTexture
                    } else {
                        defaultTexture = modelData.modelInfo.textures[0] as String
                    }
                }

                return Pair.of<String, String>(defaultModelId, defaultTexture)
            }
        }
    }

    fun getLoadedModelCount(): Int {
        return this.availableCaches.size + this.loadedPacks.size
    }

    fun onModelSynchronizationDone(context: ModelSynchronizationContext) {
        this.modelSynchronizationContexts.remove(context.player.uniqueId)
    }

    fun createNewModelSynchronizationContext(player: Player): ModelSynchronizationContext {
        val created = ModelSynchronizationContext(player)

        if (this.modelSynchronizationContexts.putIfAbsent(player.uniqueId, created) != null) {
            throw IllegalStateException("Already created sync context for player ${player.name}!")
        }

        return created
    }

    fun getCacheFile(formattedName: String): Path {
        return this.cacheFolderPath.resolve(formattedName)
    }

    fun getCachedModels(): Collection<ServerModelData> {
        return this.name2ModelData.values
    }

    fun getModelData(modelId: String): ServerModelData? {
        return this.name2ModelData[modelId]
    }

    fun getModelIds(): Set<String> {
        return this.name2ModelData.keys
    }

    /**
     * Resolve texture ID for a model. If textureId is null, returns the model's default texture.
     * Returns null if the model doesn't exist or the texture is invalid.
     */
    fun resolveTextureId(modelId: String, textureId: String?): String? {
        val modelData = this.name2ModelData[modelId] ?: return null

        if (textureId != null) {
            return if (modelData.modelInfo.textures.contains(textureId)) textureId else null
        }

        // no texture specified, use model default
        return this.defaultTextureOf(modelId)
            ?: modelData.modelInfo.textures.firstOrNull()
    }

    /**
     * Get all available texture IDs for a model. Returns empty list if model not found.
     */
    fun getTexturesOf(modelId: String): List<String> {
        val modelData = this.name2ModelData[modelId] ?: return emptyList()

        return modelData.modelInfo.textures.map { it as String }
    }

    fun getBuiltinModels(): Collection<ServerPackData> {
        return this.loadedPacks.values
    }

    fun cacheKey(): ByteArray {
        return this.cacheKey
    }

    fun setWorkingDir(workingDir: Path) {
        this.workingDir = workingDir
    }

    fun initEnv() {
        if (!Files.exists(this.workingDir)) {
            Files.createDirectories(this.workingDir)
        }

        this.cacheFolderPath = this.workingDir.resolve("caches")
        this.modelsDir = this.workingDir.resolve("models")

        this.builtinModelsFolderPath = this.modelsDir.resolve("builtin")
        this.customModelsFolderPath = this.modelsDir.resolve("custom")
        this.authModelsFolderPath =  this.modelsDir.resolve("auth")

        this.keyFilePath = this.workingDir.resolve("password.bin")
        this.cacheDeterminerFilePath = this.workingDir.resolve("cache_key.bin")

        if (!Files.exists(this.cacheFolderPath))
            Files.createDirectories(this.cacheFolderPath)

        if (!Files.exists(this.customModelsFolderPath))
            Files.createDirectories(this.customModelsFolderPath)

        if (!Files.exists(this.authModelsFolderPath))
            Files.createDirectories(this.authModelsFolderPath)

        if (!Files.exists(this.builtinModelsFolderPath))
            Files.createDirectories(this.builtinModelsFolderPath)

        this.extractBuiltinAssets()

        this.loadKeyFile()
        this.loadCacheDeterminer()
    }

    fun loadCacheDeterminer() {
        if (!Files.exists(this.cacheDeterminerFilePath)) {
            YSMSyncMain.instance.slF4JLogger.info("Cache key dose not exists! Creating.")
            this.cacheDeterminer = this.secureRand.nextLong()

            Files.writeString(this.cacheDeterminerFilePath, this.cacheDeterminer.toString())
            return
        }

        this.cacheDeterminer = Files.readString(this.cacheDeterminerFilePath).toLong()
        YSMSyncMain.instance.slF4JLogger.info("Loaded cache key file.")
    }

    fun extractBuiltinAssets() {
        try {
            val jarUri: URI = YSMSyncMain::class.java
                .protectionDomain
                .codeSource
                .location
                .toURI()
            val jarFsUri = URI.create("jar:${jarUri}")

            val fs: FileSystem = try {
                FileSystems.getFileSystem(jarFsUri)
            } catch (_: FileSystemNotFoundException) {
                FileSystems.newFileSystem(jarFsUri, emptyMap<String, Any>())
            }

            val builtinRoot = fs.getPath("builtin_models")
            if (Files.notExists(builtinRoot) || !Files.isDirectory(builtinRoot)) {
                return
            }

            Files.walkFileTree(builtinRoot, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = builtinRoot.relativize(dir).toString()
                    val dest = builtinModelsFolderPath.resolve(relative)

                    Files.createDirectories(dest)

                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = builtinRoot.relativize(file).toString()
                    val dest = builtinModelsFolderPath.resolve(relative)

                    Files.createDirectories(dest.parent)
                    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING)

                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: Exception) {
            YSMSyncMain.instance.slF4JLogger.error("Failed to extract builtin assets from JAR!", e)

            throw RuntimeException(e)
        }
    }


    fun loadKeyFile() {
        if (!Files.exists(this.keyFilePath)) {
            this.cacheKey = ByteArray(56)
            SecureRandom().nextBytes(this.cacheKey)

            Files.write(this.keyFilePath, this.cacheKey, StandardOpenOption.CREATE)

            YSMSyncMain.instance.slF4JLogger.info("Created new key file for model synchronization.")
            return
        }

        if (Files.size(this.keyFilePath) != 56L) {
            throw RuntimeException("Invalid key file detected!")
        }

        this.cacheKey = Files.readAllBytes(this.keyFilePath)
        YSMSyncMain.instance.slF4JLogger.info("Loaded new key file for model synchronization.")
    }

    fun loadModels(): Boolean {
        synchronized(this) {
            try {
                val loadedModels: MutableMap<String, ServerModelData> = ConcurrentHashMap()
                val authIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
                val validCacheFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()

                this.loadedPacks.clear()

                this.scanAndCapturePacks(this.builtinModelsFolderPath)
                this.scanAndCapturePacks(this.customModelsFolderPath)
                this.scanAndCapturePacks(this.authModelsFolderPath)

                this.scanAndLoadModels(
                    this.builtinModelsFolderPath,
                    this.cacheFolderPath,
                    loadedModels,
                    authIds,
                    validCacheFiles,
                    false
                )
                this.scanAndLoadModels(
                    this.customModelsFolderPath,
                    this.cacheFolderPath,
                    loadedModels,
                    authIds,
                    validCacheFiles,
                    false
                )
                this.scanAndLoadModels(
                    this.authModelsFolderPath,
                    this.cacheFolderPath,
                    loadedModels,
                    authIds,
                    validCacheFiles,
                    true
                )

                try {
                    Files.list(this.cacheFolderPath).use { stream ->
                        stream.forEach { file: Path ->
                            if (!validCacheFiles.contains(file.fileName.toString())) {
                                try {
                                    Files.deleteIfExists(file)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                } catch (_: Exception) { }

                this.onModelLoadComplete(loadedModels, authIds)
                return true
            } catch (e: java.lang.Exception) {
                YSMSyncMain.instance.slF4JLogger.error("Model loading failed!", e)

                throw RuntimeException(e)
            }
        }
    }

    private fun onModelLoadComplete(modelDefinitions: Map<String, ServerModelData>, authRequiredModels: Set<String>) {
        val availableCaches = IntOpenHashSet(modelDefinitions.size)

        for (data in modelDefinitions.values) {
            availableCaches.add(data.loadedModelData.hashId)
        }

        this.availableCaches = availableCaches
        this.name2ModelData = Object2ObjectOpenHashMap(modelDefinitions)
        this.authRequiredModels = authRequiredModels
    }

    private fun scanAndLoadModels(
        modelsDir: Path,
        cacheDir: Path,
        loaded: MutableMap<String, ServerModelData>,
        authIds: MutableSet<String>,
        validCaches: MutableSet<String>,
        isAuth: Boolean
    ) {
        if (!Files.isDirectory(modelsDir)) return

        try {
            Files.walk(modelsDir).use { stream ->
                CompletableFuture.allOf(*stream.map { path ->
                    CompletableFuture.runAsync({
                        val fileName = path.fileName.toString()
                        try {
                            // 文件夹
                            if (fileName == "ysm.json") {
                                val modelDir = path.parent
                                val modelId = modelsDir.relativize(modelDir).toString().replace('\\', '/')

                                YSMFolderDeserializer(modelDir).use { deserializer ->
                                    val rawModel = deserializer.deserialize()
                                    val data = generateCacheFile(modelId, rawModel, cacheDir, isAuth, validCaches)
                                    if (data != null) {
                                        loaded[modelId] = data
                                        if (isAuth) authIds.add(modelId)
                                    }
                                }
                            }

                            // ysm文件
                            if (fileName.endsWith(".ysm")) {
                                val relativePath = modelsDir.relativize(path).toString().replace('\\', '/')
                                val modelId = relativePath // 原始代码就是 relativePath
                                val raw = Files.readAllBytes(path)
                                val ysmCryptoVersion = YsmLegacyReader.getYsmCryptoVersion(raw);
                                if (ysmCryptoVersion == -1) throw IllegalStateException ("Unknown YSM crypto version for file: $path");

                                var rawModel: RawYsmModel?
                                if (ysmCryptoVersion == 1 || ysmCryptoVersion == 2) { // 旧版加密模型
                                    val input = YsmLegacyReader.input(raw)
                                    YSMFolderDeserializer(input).use {
                                        rawModel = it.deserialize();
                                    }
                                } else {
                                    val decrypted = YsmCrypt.decryptYsmFile(raw)
                                    YSMBinaryDeserializer(decrypted).use { deserializer ->
                                        rawModel = deserializer.deserializeKeepOpen()
                                        deserializer.parseYSMFooter(rawModel) // 仅用于 GUI 展示
                                    }
                                }

                                val data = generateCacheFile(modelId, rawModel!!, cacheDir, isAuth, validCaches);
                                if (data != null) {
                                    loaded[modelId] = data
                                    if (isAuth) authIds.add(modelId)
                                }
                            }
                        } catch (e: Exception) {
                            YSMSyncMain.instance.slF4JLogger.error("Failed to load model at: $path", e)
                        }
                    }, {
                        if (!YSMSyncMain.inited) {
                            ForkJoinPool.commonPool().execute(it)
                            return@runAsync
                        }

                        Scheduling.asyncScheduler().execute(it)
                    })
                }.toList().toTypedArray()).join()
            }
        } catch (e: Exception) {
            YSMSyncMain.instance.slF4JLogger.error("Failed to walk directory: $modelsDir", e)
        }
    }

    private fun scanAndCapturePacks(baseDir: Path) {
        if (!Files.isDirectory(baseDir)) return

        try {
            Files.list(baseDir).use { stream ->
                CompletableFuture.allOf(*stream.filter {
                    Files.isDirectory(it) && it != baseDir
                }.map { modelDir ->
                    CompletableFuture.runAsync(
                        {
                            val packJson = modelDir.resolve("ysm-pack.json")
                            if (Files.exists(packJson)) {
                                try {
                                    val packData = ServerPackData()
                                    packData.folderPath = baseDir.toUri().relativize(modelDir.toUri()).path

                                    val jsonStr = Files.readString(packJson, StandardCharsets.UTF_8)
                                    val json = JsonParser.parseString(jsonStr).asJsonObject
                                    if (json.has("name")) packData.name = json.get("name").asString
                                    if (json.has("description")) packData.description = json.get("description").asString

                                    if (json.has("lang") && json.get("lang").isJsonObject) {
                                        val langMap = HashMap<String, MutableMap<String, String>>()
                                        packData.lang = langMap
                                        val langObj = json.getAsJsonObject("lang")
                                        for ((langKey, value) in langObj.entrySet()) {
                                            if (value.isJsonObject) {
                                                val translations = HashMap<String, String>()
                                                for ((transKey, transValue) in value.asJsonObject.entrySet()) {
                                                    translations[transKey] = transValue.asString
                                                }
                                                langMap[langKey] = translations
                                            }
                                        }
                                    }

                                    val packPng = modelDir.resolve("ysm-pack.png")
                                    if (Files.exists(packPng)) {
                                        val data = Files.readAllBytes(packPng)
                                        val (w, h) = dimensionsOfPng(data)
                                        packData.iconData = data
                                        packData.iconWidth = w
                                        packData.iconHeight = h
                                        packData.iconFormat = 2 // 2 = PNG
                                    }

                                    this.loadedPacks[packData.folderPath ?: ""] = packData
                                } catch (e: Exception) {
                                    YSMSyncMain.instance.slF4JLogger.error("Failed to load pack metadata: $packJson", e)
                                }
                            }
                        }, {
                            if (!YSMSyncMain.inited) {
                                ForkJoinPool.commonPool().execute(it)
                                return@runAsync
                            }

                            Scheduling.asyncScheduler().execute(it)
                        }
                    )
                }.toList().toTypedArray()).join()
            }
        } catch (e: Exception) {
            YSMSyncMain.instance.slF4JLogger.error("Failed to walk directory for packs: $baseDir", e)
        }
    }

    private fun dimensionsOfPng(data: ByteArray?): IntArray {
        if (data == null || data.size < 24) return intArrayOf(0, 0)

        if ((data[0].toInt() and 0xFF) != 0x89 || data[1] != 0x50.toByte() || data[2] != 0x4E.toByte() || data[3] != 0x47.toByte())
            return intArrayOf(0, 0)
        val width = ((data[16].toInt() and 0xFF) shl 24) or ((data[17].toInt() and 0xFF) shl 16) or
                ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val height = ((data[20].toInt() and 0xFF) shl 24) or ((data[21].toInt() and 0xFF) shl 16) or
                ((data[22].toInt() and 0xFF) shl 8) or (data[23].toInt() and 0xFF)

        return intArrayOf(width, height)
    }

    private fun generateCacheFile(
        modelId: String,
        model: RawYsmModel,
        serverCacheDir: Path,
        isAuth: Boolean,
        validCacheFiles: MutableSet<String>
    ): ServerModelData? {
        val sha256 = model.properties.sha256 ?: return null
        if (sha256.isEmpty()) return null

        return try {
            val hashes = YsmCrypt.calculateModelHashes(sha256, this.cacheKey)
            val cacheFileName = String.format("%016x%016x", hashes[0], hashes[1])
            val cacheFile = serverCacheDir.resolve(cacheFileName)

            if (!Files.isDirectory(serverCacheDir)) {
                Files.createDirectories(serverCacheDir)
            }

            var needsUpdate = true
            if (Files.exists(cacheFile)) {
                val existingData = Files.readAllBytes(cacheFile)
                if (YsmCrypt.verifyServerCache(existingData, hashes[0], hashes[1])) {
                    needsUpdate = false
                }
            }

            if (needsUpdate) {
                YSMBinarySerializer.serialize(model, 32, true).use { serialized ->
                    val rawBuf = serialized.rawBuf
                    val rawBytes = ByteArray(rawBuf.readableBytes())
                    rawBuf.readBytes(rawBytes)

                    val encryptedCache = YsmCrypt.encryptServerCache(rawBytes, this.cacheKey, hashes[0], hashes[1])
                    Files.write(cacheFile, encryptedCache)
                }
            }

            validCacheFiles.add(cacheFileName)

            val isCustomSkinModel = modelId == "misc/2_steve" || modelId == "misc/1_alex"

            createNewModelDesc(modelId, model, isAuth, isCustomSkinModel)
        } catch (e: Exception) {
            YSMSyncMain.instance.slF4JLogger.error("Failed to process and cache model: $modelId", e)
            null
        }
    }

    private fun createNewModelDesc(
        modelId: String,
        raw: RawYsmModel,
        isAuth: Boolean,
        isCustomSkinModel: Boolean
    ): ServerModelData {
        val serverModelInfo = YsmModelInfoBuilder.buildModelInfo(raw)

        val animMap = raw.mainEntity.animationFiles.mapValues { it.value.animations.keys.toTypedArray() }
        val texArr = raw.mainEntity.textures.keys.toTypedArray()
        val animInfo = ServerAnimationInfo(animMap.toMap(HashMap()), texArr)

        val projectiles = raw.projectiles.values.map { it.matchIds ?: arrayOf(it.identifier) }.toTypedArray()
        val vehicles = raw.vehicles.values.map { it.matchIds ?: arrayOf(it.identifier) }.toTypedArray()

        return ServerModelData(modelId, animInfo, projectiles, vehicles, serverModelInfo, isCustomSkinModel, isAuth)
    }
}