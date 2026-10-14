package com.maodouchat.group

import com.maodouchat.contacts.QrScanFeedbackPolicy
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ChatDto
import com.maodouchat.navigation.AppLinkRouter
import com.maodouchat.navigation.sanitizeChatInviteToken
import com.maodouchat.navigation.sanitizeInviteCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JoinGroupInviteUseCase(
    private val tokenProvider: () -> String,
    private val userIdProvider: () -> String,
    private val sessionGate: () -> Boolean,
    private val join: suspend (authToken: String, inviteToken: String) -> Result<ChatDto>,
) {
    suspend fun join(inviteCode: String): JoinGroupInviteResult = withContext(Dispatchers.IO) {
        val code = AppLinkRouter.sanitizeChatInviteToken(inviteCode)
            ?: AppLinkRouter.sanitizeInviteCode(inviteCode)
            ?: return@withContext JoinGroupInviteResult.InvalidCode
        val auth = tokenProvider().trim()
        val owner = userIdProvider().trim()
        if (auth.isBlank() || owner.isBlank() || !sessionGate()) {
            return@withContext JoinGroupInviteResult.Failed(QrScanFeedbackPolicy.forSessionExpired())
        }
        val liveAuth = tokenProvider().trim().ifBlank { auth }
        val outcome = runCatching { join(liveAuth, code) }.getOrElse { error ->
            Result.failure(error)
        }
        outcome.fold(
            onSuccess = { chat ->
                if (!sessionGate()) JoinGroupInviteResult.Aborted
                else JoinGroupInviteResult.Joined(chat)
            },
            onFailure = { error ->
                val api = error as? ApiException
                JoinGroupInviteResult.Failed(
                    QrScanFeedbackPolicy.forJoinInvite(
                        httpStatus = api?.statusCode,
                        serverCode = api?.serverCode,
                        serverMessage = api?.serverMessage ?: error.message,
                        isNetwork = api?.kind == ApiFailureKind.NETWORK,
                        isTimeout = api?.kind == ApiFailureKind.TIMEOUT,
                    )
                )
            },
        )
    }
}
