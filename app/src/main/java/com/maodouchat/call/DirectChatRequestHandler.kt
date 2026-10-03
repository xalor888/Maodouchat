package com.maodouchat.call

import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.session.CurrentSession

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
