package moe.meowrealms.ysmsync.modernysm

import moe.meowrealms.ysmsync.YSMSyncMain
import org.slf4j.Logger

/**
 * 协议层的日志出口。
 *
 * 插件尚未完成初始化时（例如单元测试、早期加载阶段）`YSMSyncMain.instance`
 * 可能不可用，这里的访问器会安全地退化为 null，避免协议逻辑被日志拖垮。
 */
internal object Logs {
    val plugin: Logger?
        get() = runCatching { YSMSyncMain.instance.slF4JLogger }.getOrNull()

    fun info(message: String, vararg args: Any?) {
        plugin?.info(message, *args)
    }

    fun warn(message: String, vararg args: Any?) {
        plugin?.warn(message, *args)
    }

    fun error(message: String, t: Throwable? = null) {
        if (t != null) plugin?.error(message, t) else plugin?.error(message)
    }
}
