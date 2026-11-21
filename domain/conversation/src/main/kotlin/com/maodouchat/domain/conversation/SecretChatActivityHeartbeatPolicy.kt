package com.maodouchat.domain.conversation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// 密聊活动心跳纯逻辑：由 app 侧 SecretChatActivityHeartbeat 平移；
// Context/dao 装配仍在 app 侧的 start()，这里只留可注入依赖的循环本体。
object SecretChatActivityHeartbeatPolicy {

    const val HEARTBEAT_INTERVAL_MS = 60_000L

    suspend fun run(
        chatId: String,
        readLastActivityAt: suspend (String) -> Long?,
        isExpired: (String, Long) -> Boolean,
        destroy: (String) -> Unit,
        touch: suspend (String) -> Unit,
        intervalMs: Long = HEARTBEAT_INTERVAL_MS,
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ) {
        // 进入时先校验一次：已过期立即销毁本地解密缓存，再以本次进入为新的活动起点。
        // 不用 runCatching 包这段：它会吞掉 CancellationException，取消后仍走一次 touch，
        // 而 touch 会延长密聊 TTL，等于给已离开的会话续命。
        currentCoroutineContext().ensureActive()
        try {
            val lastActivityAt = readLastActivityAt(chatId)
            currentCoroutineContext().ensureActive()
            if (lastActivityAt != null && isExpired(chatId, lastActivityAt)) {
                destroy(chatId)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // 普通读取失败（数据库锁住等）按原行为吞掉，不影响心跳。
        }
        while (true) {
            // 每次写活动前重新确认未取消：touch 是注入的，不保证协作取消。
            currentCoroutineContext().ensureActive()
            touch(chatId)
            sleep(intervalMs)
        }
    }
}
