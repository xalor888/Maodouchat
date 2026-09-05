package com.maodouchat.server.service

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 后台周期任务健康注册表（B14：readiness 覆盖数据库/迁移/存储之外，
 * 再覆盖后台任务状态；此前任务失败仅打日志，滚动发布无法感知）。
 *
 * 语义：
 * - 每个任务成功一次记 heartbeat，失败记 consecutiveFailures；
 * - 连续失败 >= [MAX_CONSECUTIVE_FAILURES] 判定 degraded；
 * - 从未运行过的任务为 unknown，不拉低 readiness（启动即跑首轮，unknown 窗口仅数秒）；
 * - CancellationException 原样抛出、不计失败（调用方取消语义不变）。
 */
class BackgroundTaskHealth(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class TaskStatus(
        val name: String,
        val lastSuccessAt: Long? = null,
        val lastErrorAt: Long? = null,
        val lastError: String? = null,
        val consecutiveFailures: Int = 0,
    ) {
        fun healthy(): Boolean = consecutiveFailures < MAX_CONSECUTIVE_FAILURES
        fun known(): Boolean = lastSuccessAt != null || lastErrorAt != null
    }

    private val tasks = ConcurrentHashMap<String, TaskStatus>()

    fun heartbeat(name: String, now: Long = clock()) {
        tasks.compute(name) { _, existing ->
            (existing ?: TaskStatus(name)).copy(
                lastSuccessAt = now,
                consecutiveFailures = 0,
            )
        }
    }

    fun recordFailure(name: String, error: Throwable, now: Long = clock()) {
        tasks.compute(name) { _, existing ->
            (existing ?: TaskStatus(name)).copy(
                lastErrorAt = now,
                lastError = "${error.javaClass.simpleName}: ${error.message}".take(500),
                consecutiveFailures = (existing?.consecutiveFailures ?: 0) + 1,
            )
        }
    }

    fun snapshot(): Map<String, TaskStatus> = tasks.toMap()

    /** 已知任务全部健康（未知任务不拉低结果）。 */
    fun allHealthy(): Boolean = tasks.values.all { !it.known() || it.healthy() }

    fun degradedTasks(): List<String> =
        tasks.values.filter { it.known() && !it.healthy() }.map { it.name }.sorted()

    companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
        val global = BackgroundTaskHealth()
    }
}

/**
 * 带健康追踪的周期任务执行器：成功记 heartbeat，失败记 failure 并按原消息打日志。
 * 与旧 `runCatching { }.onFailure { }` 行为一致（含 Cancellation 原样抛出）。
 */
suspend fun runTracked(name: String, logMessage: String, block: suspend () -> Unit) {
    val global = BackgroundTaskHealth.global
    runCatching { block() }
        .onSuccess { global.heartbeat(name) }
        .onFailure { error ->
            if (error is CancellationException) throw error
            global.recordFailure(name, error)
            LoggerFactory.getLogger(BackgroundTaskHealth::class.java).warn(logMessage, error)
        }
}
