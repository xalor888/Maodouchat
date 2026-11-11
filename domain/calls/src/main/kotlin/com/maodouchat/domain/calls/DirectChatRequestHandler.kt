package com.maodouchat.domain.calls

import com.maodouchat.core.model.DirectChatRequest
import com.maodouchat.core.session.Outcome

// 扫一扫后"和某人创建私聊"：从 app 侧 DirectChatRequestHandler 纯搬移；
// 会话快照/门禁/建聊网络/Log 全部走构造端口，方法体除端口替换外逐字平移。
class DirectChatRequestHandler(
    private val session: DirectChatRequestSession,
    private val gateway: DirectChatRequestGateway,
    private val logWarning: (String, Throwable?) -> Unit = { _, _ -> },
) {
    suspend fun handle(request: DirectChatRequest): Outcome {
        val token = session.token()
        val ownerUserId = session.ownerUserId()
        if (token.isBlank() || ownerUserId.isBlank() ||
            !session.mayContinue(ownerUserId)
        ) {
            return Outcome.SessionExpired
        }
        return gateway.createChat(token, listOf(request.userId)).fold(
            onSuccess = { chat ->
                if (!session.mayContinue(ownerUserId)) {
                    Outcome.Dropped
                } else {
                    Outcome.OpenChat(chat.id)
                }
            },
            onFailure = { error ->
                logWarning("createChat failed", error)
                Outcome.Failed(error.message)
            },
        )
    }
}
