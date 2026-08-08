package com.maodouchat.perf

import android.os.SystemClock
import android.os.Trace
import android.util.Log

/**
 * 冷启动计时器（B7 性能预算：冷启动到首帧 < 1.2s / 到可交互 < 1.8s）。
 *
 * 职责：
 * 1. 以 [SystemClock.elapsedRealtime] 为基准记录各里程碑耗时，输出按耗时降序的 summary 日志；
 * 2. 里程碑同时写入 `android.os.Trace`（`StartupTracer.` 前缀），配合 systrace/perfetto 定位阻塞段；
 * 3. 线程安全、幂等，任何阶段都可以调用，不需要在 Application 里做特殊初始化。
 *
 * 建议接入点（不改 MaodouchatApp 时的最小埋点）：
 * - `attachBaseContext` 结束后：`StartupTracer.beginColdStart()`
 * - Application.onCreate 完成（DB/Signal/依赖就绪）：`StartupTracer.mark("applicationInit")`
 * - 首帧 compose 完成：`StartupTracer.mark("firstFrame")`
 * - 可交互：`StartupTracer.fullyDrawn()`（对应 MainActivity 的 reportFullyDrawn 时机）
 *
 * 所有方法在冷启动未 begin 时自动忽略，热启动/后台唤醒不会误报。
 */
object StartupTracer {

    const val TAG = "StartupTracer"

    private const val TRACE_PREFIX = "StartupTracer."

    /** 冷启动到首帧预算（ms），见 docs/ui-motion-performance-budget.md §2.2 */
    const val BUDGET_FIRST_FRAME_MS = 1_200L

    /** 冷启动到可交互预算（ms） */
    const val BUDGET_INTERACTIVE_MS = 1_800L

    @Volatile
    private var beginElapsedMs = 0L

    @Volatile
    private var started = false

    @Volatile
    private var fullyDrawnLogged = false

    /** 里程碑名 → 相对 begin 的耗时（ms），按到达顺序保存，重复里程碑只保留第一次。 */
    private val milestones = LinkedHashMap<String, Long>(8)

    /**
     * 开始记录一次冷启动。重复调用（如进程因 onTrimMemory 重启）会重置本轮计时。
     */
    fun beginColdStart(tag: String = "coldStart") {
        synchronized(milestones) {
            started = true
            fullyDrawnLogged = false
            beginElapsedMs = SystemClock.elapsedRealtime()
            milestones.clear()
            milestones["begin:$tag"] = 0L
        }
        traceBegin(tag)
    }

    /**
     * 记录一个里程碑。自动忽略「未 begin」或「已 fullyDrawn」后的调用。
     */
    fun mark(event: String) {
        if (!started) return
        val elapsed = SystemClock.elapsedRealtime() - beginElapsedMs
        synchronized(milestones) {
            if (!started || fullyDrawnLogged) return
            milestones.putIfAbsent(event, elapsed)
        }
        traceSection(event) { }
    }

    /**
     * 标记可交互完成，输出最终 summary。调用后 [mark] 不再记录。
     */
    fun fullyDrawn() {
        if (!started) return
        val elapsed = SystemClock.elapsedRealtime() - beginElapsedMs
        synchronized(milestones) {
            if (!started) return
            milestones.putIfAbsent("fullyDrawn", elapsed)
            fullyDrawnLogged = true
        }
        traceEnd()
        Log.d(TAG, summary())
    }

    /** 生成按耗时降序的里程碑汇总（相对 begin，ms）。 */
    fun summary(): String {
        synchronized(milestones) {
            if (!started) return "StartupTracer: cold start not recorded"
            val sorted = milestones.entries.sortedByDescending { it.value }
            val sb = StringBuilder("StartupTracer cold start: total=${lastElapsedMs()}ms")
            for ((name, elapsed) in sorted) {
                sb.append("\n  +${elapsed}ms  $name")
            }
            val firstFrame = milestones["firstFrame"]
            if (firstFrame != null) {
                sb.append("\n  firstFrame budget=$BUDGET_FIRST_FRAME_MS ${budgetStatus(firstFrame, BUDGET_FIRST_FRAME_MS)}")
            }
            val interactive = milestones["fullyDrawn"]
            if (interactive != null) {
                sb.append("\n  interactive budget=$BUDGET_INTERACTIVE_MS ${budgetStatus(interactive, BUDGET_INTERACTIVE_MS)}")
            }
            return sb.toString()
        }
    }

    /** 上一轮冷启动总耗时（ms），未记录返回 0。 */
    fun lastElapsedMs(): Long {
        synchronized(milestones) {
            val interactive = milestones["fullyDrawn"]
            return interactive ?: (if (started) SystemClock.elapsedRealtime() - beginElapsedMs else 0L)
        }
    }

    /** 是否命中预算。 */
    fun withinBudget(elapsedMs: Long, budgetMs: Long): Boolean = elapsedMs <= budgetMs

    private fun budgetStatus(elapsedMs: Long, budgetMs: Long): String =
        if (withinBudget(elapsedMs, budgetMs)) "PASS" else "OVERRUN(+${elapsedMs - budgetMs}ms)"

    private fun traceBegin(name: String) {
        if (Trace.isEnabled()) Trace.beginSection(TRACE_PREFIX + name)
    }

    private fun traceEnd() {
        if (Trace.isEnabled()) Trace.endSection()
    }

    private inline fun traceSection(name: String, block: () -> Unit) {
        val enabled = Trace.isEnabled()
        if (enabled) Trace.beginSection(TRACE_PREFIX + name)
        try {
            block()
        } finally {
            if (enabled) Trace.endSection()
        }
    }
}
