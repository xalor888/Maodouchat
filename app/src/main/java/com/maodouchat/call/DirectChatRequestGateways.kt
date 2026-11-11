package com.maodouchat.call

import android.util.Log
import com.maodouchat.core.model.ChatDto
import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.domain.calls.DirectChatRequestGateway
import com.maodouchat.domain.calls.DirectChatRequestHandler
import com.maodouchat.domain.calls.DirectChatRequestSession
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.session.CurrentSession

/** 建单聊网络端口的 app 侧实现：直通 ChatNetworkRepository。 */
internal class ChatNetworkDirectChatRequestGateway : DirectChatRequestGateway {
    override suspend fun createChat(token: String, peerIds: List<String>): Result<ChatDto> =
        ChatNetworkRepository().createChat(token, peerIds)
}

/** 建单聊会话快照端口的 app 侧实现：直通 CurrentSession / BackgroundSessionGate。 */
internal object CurrentSessionDirectChatRequestSession : DirectChatRequestSession {
    override fun ownerUserId(): String = CurrentSession.ownerUserId()
    override fun token(): String = CurrentSession.snapshot().token.orEmpty()
    override fun mayContinue(expectedUserId: String): Boolean =
        BackgroundSessionGate.mayContinue(expectedUserId)
}

/** NavGraph 的扫码建私聊接线：网络 / 会话 / 日志全部由 app 侧提供。 */
internal fun createDirectChatRequestHandler(): DirectChatRequestHandler =
    DirectChatRequestHandler(
        session = CurrentSessionDirectChatRequestSession,
        gateway = ChatNetworkDirectChatRequestGateway(),
        logWarning = { message, error -> Log.w("DirectChatRequestHandler", message, error) },
    )
