package com.maodouchat.security

import android.content.Context
import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * B2 密聊活动心跳。
 *
 * 从 `ui/screen/chatdetail/ChatDetailRoute.kt` 里那段自包含的 `LaunchedEffect` 抽出（M6/G9）。
 * 抽它的**主要收益不是少几行**，而是这段逻辑原先埋在 Composable 里、**完全不可测**：
 * 「过期就销毁解密缓存」「未过期不能误销毁」「按节奏写 activity」这三条只能靠肉眼读。
 *
 * 拆成两层：
 * - [run] 是纯逻辑，依赖全部以函数注入（读 lastActivityAt / 判过期 / 销毁 / 写活动 / 睡眠），
 *   因此可以在 JVM 单测里用 `runTest` 直接验；
 * - [start] 是 Android 侧装配，负责从 Application 取 SQLCipher dao 并接上 [SecretSessionTtl]。
 *
 * 行为与抽出前逐条保持一致：
 * 1. 进入时先读一次 `lastActivityAt`；**读失败按原来一样吞掉**（`runCatching`），不影响心跳；
 * 2. 仅当存在记录且判定过期时才销毁本地解密缓存；
 * 3. 之后进入 `写活动 → 睡 60s` 的无限循环，由 Composable 的 `LaunchedEffect` 取消来结束。
 */
object SecretChatActivityHeartbeat {

    const val HEARTBEAT_INTERVAL_MS = 60_000L

    /** 纯逻辑，依赖可注入。 */
    suspend fun run(
        chatId: String,
        readLastActivityAt: suspend (String) -> Long?,
        isExpired: (String, Long) -> Boolean,
        destroy: (String) -> Unit,
        touch: suspend (String) -> Unit,
        intervalMs: Long = HEARTBEAT_INTERVAL_MS,
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ) {
        // 进入前即时校验：会话已无活动过期时立即销毁本地解密缓存，
        // 再以本次进入为新的活动起点——不依赖 15 分钟周期清扫的滞后窗口。
        //
        // G10：不要用 `runCatching` 包这段。`runCatching` 会把 `CancellationException`
        // 一起吞掉，于是任务被取消后仍会往下走一次 `touch`；而 `touchActivity` 会**延长
        // 密聊 TTL**——等于让一次泄漏的心跳给已离开/已销毁的会话续命。
        // 项目自身的惯例是显式重抛取消，见 `SecretSessionTtl.destroySession`。
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
            // 普通读取失败（数据库锁住等）按抽出前的行为吞掉，不影响心跳。
        }
        while (true) {
            // 每次写活动前重新确认未取消：`touch` 是注入的，不保证协作取消，
            // 不能把「不会在取消后产生副作用」寄托在回调自己身上。
            currentCoroutineContext().ensureActive()
            touch(chatId)
            sleep(intervalMs)
        }
    }

    /** Android 侧装配：从 Application 取 dao 并接上 TTL 判定。 */
    suspend fun start(context: Context, chatId: String) {
        val app = context.applicationContext as? MaodouchatApp ?: return
        val dao = app.database.secretChatDao()
        run(
            chatId = chatId,
            readLastActivityAt = { id -> dao.get(id)?.lastActivityAt },
            isExpired = { id, lastActivityAt -> SecretSessionTtl.isExpired(context, id, lastActivityAt) },
            destroy = { id -> SecretSessionTtl.destroySession(context, id) },
            touch = { id -> dao.touchActivity(id) },
        )
    }
}
