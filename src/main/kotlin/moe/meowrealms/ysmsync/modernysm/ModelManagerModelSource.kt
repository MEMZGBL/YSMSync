package moe.meowrealms.ysmsync.modernysm

import moe.meowrealms.ysmsync.model.ModelManager

/**
 * 把 YSMSync 现有的模型缓存暴露给 ModernYSM 协议层。
 *
 * 关键点：YSMSync 的模型缓存（`cache/<hash1><hash2>` 文件）本身就是
 * `YsmCrypt.encryptServerCache(YSMBinarySerializer.serialize(model, 32, true), cacheKey, hash1, hash2)`
 * 的产物，与 OpenYSM 服务端下发给客户端的数据格式完全一致。
 * 因此这里直接读原始字节返回，协议层不需要再做任何加解密。
 */
object ModelManagerModelSource : ModernYsmModelSource {

    /** 序列化格式版本，与 ModelManager 生成缓存时使用的 32 保持一致。 */
    private const val FORMAT_VERSION = 32

    /** 内置皮肤模型标识，客户端需要据此走原版皮肤渲染路径。 */
    private val CUSTOM_SKIN_MODELS = setOf("misc/1_alex", "misc/2_steve")

    override fun serverKey(): ByteArray = ModelManager.getServerKey()

    override fun clientKey(): ByteArray = ModernYsmSession.createClientKey()

    override fun catalog(): Collection<ModelCatalogEntry> {
        val hashes = ModelManager.getModelHashIndex()
        if (hashes.isEmpty()) return emptyList()

        val authRequired = ModelManager.getAuthRequiredModels()
        val result = ArrayList<ModelCatalogEntry>(hashes.size)

        for ((modelId, pair) in hashes) {
            if (pair.size < 2) continue
            result.add(
                ModelCatalogEntry(
                    modelId = modelId,
                    hash1 = pair[0],
                    hash2 = pair[1],
                    isAuth = authRequired.contains(modelId),
                    isCustomSkinModel = CUSTOM_SKIN_MODELS.contains(modelId),
                    version = FORMAT_VERSION,
                )
            )
        }
        return result
    }

    override fun modelCacheBytes(hash1: Long, hash2: Long): ByteArray? =
        ModelManager.getCacheBytesByHashes(hash1, hash2)
}
