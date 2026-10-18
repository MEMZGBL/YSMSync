package moe.meowrealms.ysmsync.utils

import io.papermc.paper.threadedregions.scheduler.ScheduledTask as PaperScheduledTask
import moe.meowrealms.ysmsync.YSMSyncMain
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * 轻量调度适配层。
 *
 * 原项目依赖 MorePaperLib 来同时兼容 Bukkit 与 Folia。Paper 26.x 已经把
 * 区域化调度器 (`io.papermc.paper.threadedregions.scheduler`) 并入官方 API，
 * 因此这里直接使用官方实现：既去掉了第三方依赖，也仍然是 Folia 兼容的。
 */
object Scheduling {

    /** 可取消的任务句柄，屏蔽不同调度器实现的差异。 */
    interface TaskHandle {
        fun cancel()
    }

    /** 围绕实体的调度器：等价于「在该玩家所属的线程上执行」。 */
    interface EntityScheduler {
        fun run(task: Runnable, retired: Runnable?): TaskHandle?
        fun runDelayed(task: Runnable, retired: Runnable?, delayTicks: Long): TaskHandle?
        fun runAtFixedRate(task: Runnable, retired: Runnable?, initialDelayTicks: Long, periodTicks: Long): TaskHandle?
    }

    /** 异步调度器。实现 [java.util.concurrent.Executor]，可直接交给 CompletableFuture 使用。 */
    interface AsyncScheduler : java.util.concurrent.Executor {
        fun run(task: Runnable): TaskHandle?
    }

    @JvmStatic
    fun entitySpecificScheduler(player: Player): EntityScheduler = PaperEntityScheduler(player)

    @JvmStatic
    fun asyncScheduler(): AsyncScheduler = PaperAsyncScheduler

    private class PaperEntityScheduler(private val player: Player) : EntityScheduler {

        override fun run(task: Runnable, retired: Runnable?): TaskHandle? =
            player.scheduler.run(YSMSyncMain.instance, { task.run() }, retired).asHandle()

        override fun runDelayed(task: Runnable, retired: Runnable?, delayTicks: Long): TaskHandle? =
            player.scheduler.runDelayed(
                YSMSyncMain.instance,
                { task.run() },
                retired,
                delayTicks.coerceAtLeast(1L)
            ).asHandle()

        override fun runAtFixedRate(
            task: Runnable,
            retired: Runnable?,
            initialDelayTicks: Long,
            periodTicks: Long
        ): TaskHandle? =
            player.scheduler.runAtFixedRate(
                YSMSyncMain.instance,
                { task.run() },
                retired,
                initialDelayTicks.coerceAtLeast(1L),
                periodTicks.coerceAtLeast(1L)
            ).asHandle()
    }

    private object PaperAsyncScheduler : AsyncScheduler {
        override fun run(task: Runnable): TaskHandle? =
            Bukkit.getAsyncScheduler().runNow(YSMSyncMain.instance) { task.run() }.asHandle()

        override fun execute(task: Runnable) {
            Bukkit.getAsyncScheduler().runNow(YSMSyncMain.instance) { task.run() }
        }
    }

    private fun PaperScheduledTask?.asHandle(): TaskHandle? {
        if (this == null) return null
        val task = this
        return object : TaskHandle {
            override fun cancel() {
                task.cancel()
            }
        }
    }
}
