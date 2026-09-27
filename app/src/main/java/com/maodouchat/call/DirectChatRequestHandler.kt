package com.maodouchat.call

import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.session.CurrentSession

/**
 * 「扫一扫后和某人创建 1-on-1 私聊」请求的处理（U02 延伸：自 `NavGraph` 迁出的
 * app 单例访问收口 + 会话门禁留在非 ui 层）。
 *
 * 语义（逐字对齐原收集器）：
 * - 入口先查 token/owner 与实时会话门禁，任一不成立 → [Outcome.SessionExpired]
 *   （调用方弹「会话已失效」提示）；
 * - `createChat` 成功后**再查一次**门禁：中途登出/换号 → [Outcome.Dropped]（静默丢弃，
 *   原实现就是静默 return，不弹任何东西）；
 * - 失败 → [Outcome.Failed]（message 可为空，调用方回落到通用文案）。
 */
object DirectChatRequestHandler {

    sealed interface Outcome {
        /** 无有效会话（token/owner 缺失或门禁不过）。 */
        data object SessionExpired : Outcome

        /** 请求在途期间会话失效：静默丢弃。 */
        data object Dropped : Outcome

        /** 成功建/取到私聊，调用方导航到会话。 */
        data class OpenChat(val chatId: String) : Outcome

        /** HTTP/其他失败；message 可能为空。 */
        data class Failed(val message: String?) : Outcome
    }

    suspend fun handle(request: CallOrchestrator.DirectChatRequest): Outcome {
        val token = CurrentSession.snapshot().token.orEmpty()
        val ownerUserId = CurrentSession.ownerUserId()
        if (token.isBlank() || ownerUserId.isBlank() ||
            !BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId)
        ) {
            return Outcome.SessionExpired
        }
        return ChatNetworkRepository().createChat(token, listOf(request.userId)).fold(
            onSuccess = { chat ->
                if (!BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId)) {
                    Outcome.Dropped
                } else {
                    Outcome.OpenChat(chat.id)
                }
            },
            onFailure = { error ->
                android.util.Log.w("DirectChatRequestHandler", "createChat failed", error)
                Outcome.Failed(error.message)
            },
        )
    }
}
