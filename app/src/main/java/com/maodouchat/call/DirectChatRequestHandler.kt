package com.maodouchat.call

import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.session.CurrentSession

object DirectChatRequestHandler {

    suspend fun handle(request: DirectChatRequest): Outcome {
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
