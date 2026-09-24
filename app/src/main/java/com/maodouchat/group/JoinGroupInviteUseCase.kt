package com.maodouchat.group

import com.maodouchat.contacts.QrScanFeedbackPolicy
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ChatDto
import com.maodouchat.navigation.AppLinkRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 群邀请加入用例（P08）：深链 / QR / 系统入口统一经此入口调用 join-by-invite。
 * UI 只观察 [JoinGroupInviteResult]，不直连 ApiService。
 */
sealed interface JoinGroupInviteResult {
    data class Joined(val chat: ChatDto) : JoinGroupInviteResult
    data object InvalidCode : JoinGroupInviteResult
    data class Failed(val feedback: QrScanFeedbackPolicy.Feedback) : JoinGroupInviteResult
    /** 加入成功后账号世代/会话已变，丢弃结果不导航。 */
    data object Aborted : JoinGroupInviteResult
}

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
