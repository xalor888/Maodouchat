package com.maodouchat.server.service

// 生命周期装配（start/shutdown/runtime）：原 BotWebhookService 成员按域拆出的扩展函数（同包调用，零行为改动）。

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Register an application lifecycle and return the token required to stop it. */
internal fun BotWebhookService.start(): Long = synchronized(this) {
    val lifecycleId = nextLifecycleId()
    activeLifecycleIds += lifecycleId
    lifecycleState = LifecycleState.RUNNING
    if (runtime?.scope?.coroutineContext?.get(Job)?.isActive != true) {
        runtime?.close()
        runtime = newRuntime()
    }
    replayStalePending()
    lifecycleId
}

/** Unregister one application lifecycle; the final shutdown cancels in-flight delivery. */
internal fun BotWebhookService.shutdown(lifecycleId: Long) {
    val runtimeToClose = synchronized(this) {
        if (!activeLifecycleIds.remove(lifecycleId) || activeLifecycleIds.isNotEmpty()) {
            return@synchronized null
        }
        lifecycleState = LifecycleState.STOPPED
        runtime.also { runtime = null }
    }
    runtimeToClose?.close()
}

internal fun BotWebhookService.nextLifecycleId(): Long {
    do {
        lastLifecycleId = if (lastLifecycleId == Long.MAX_VALUE) 1L else lastLifecycleId + 1L
    } while (lastLifecycleId in activeLifecycleIds)
    return lastLifecycleId
}

internal fun BotWebhookService.newRuntime(): Runtime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val queue = Channel<suspend () -> Unit>(capacity = DELIVERY_QUEUE_CAPACITY)
    repeat(DELIVERY_WORKERS) {
        scope.launch {
            for (delivery in queue) {
                try {
                    delivery()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    logger.warn("Bot webhook delivery failed", error)
                }
            }
        }
    }
    // B12：周期性重放——回收崩溃 worker 遗留的过期租约，兜底进程中途退出。
    scope.launch {
        while (isActive) {
            delay(WEBHOOK_REPLAY_INTERVAL_MS)
            try {
                replayStalePending()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                logger.warn("Bot webhook replay sweep failed", e)
            }
        }
    }
    return Runtime(scope, queue)
}

internal fun Runtime.close() {
    queue.close()
    scope.cancel()
}

internal fun BotWebhookService.activeQueue(): Channel<suspend () -> Unit>? {
    if (lifecycleState != LifecycleState.RUNNING) return null
    val current = runtime
    if (current?.scope?.coroutineContext?.get(Job)?.isActive == true) return current.queue
    return synchronized(this) {
        if (lifecycleState != LifecycleState.RUNNING) return@synchronized null
        runtime?.takeIf { it.scope.coroutineContext[Job]?.isActive == true }
            ?.queue
            ?: newRuntime().also { runtime = it }.queue
    }
}
