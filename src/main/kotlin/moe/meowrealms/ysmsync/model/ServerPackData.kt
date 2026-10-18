package moe.meowrealms.ysmsync.model

/**
 * YSM 内置模型包（资源包）的元数据。
 *
 * 原实现为 `ServerModelManager.ServerPackData`，但 `ServerModelManager` 整个类
 * 依赖 Minecraft 服务端内部 API，无法在新版本服务端加载。这里按原字段逐一复刻，
 * 因为模型同步协议直接按这些字段序列化给客户端。
 */
class ServerPackData {
    @JvmField var folderPath: String? = null
    @JvmField var iconData: ByteArray? = null
    @JvmField var iconWidth: Int = 0
    @JvmField var iconHeight: Int = 0
    @JvmField var iconFormat: Int = 0
    @JvmField var name: String? = null
    @JvmField var description: String? = null
    @JvmField var lang: MutableMap<String, MutableMap<String, String>>? = null
}
