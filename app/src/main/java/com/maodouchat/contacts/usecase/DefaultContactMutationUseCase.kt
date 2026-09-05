package com.maodouchat.contacts.usecase

import com.maodouchat.network.ApiService
import com.maodouchat.security.BackgroundSessionGate
import kotlinx.coroutines.CancellationException

/**
 * 默认联系人变更用例实现。
 *
 * 封装：
 * 1. 本地备注修改
 * 2. 好友删除（远端同步与本地好友缓存剔除）
 * 3. 拉黑与解封（拦截拉黑自己、远端同步与好友关系解绑）
 * 4. 严格会话状态与账号隔离校验
 */
class DefaultContactMutationUseCase(
    private val sessionProvider: () -> Pair<String?, String?>,
    private val setNicknameLocal: suspend (userId: String, nickname: String) -> Unit,
    private val removeFriendApi: suspend (token: String, friendUserId: String) -> Result<Unit> = { token, id ->
        ApiService.removeFriend(token, id)
    },
    private val blockUserApi: suspend (token: String, targetUserId: String) -> Result<Unit> = { token, id ->
        ApiService.blockUser(token, id)
    },
    private val unblockUserApi: suspend (token: String, targetUserId: String) -> Result<Unit> = { token, id ->
        ApiService.unblockUser(token, id)
    },
    private val onFriendRemoved: suspend (ownerUserId: String, friendUserId: String) -> Unit = { _, _ -> },
    private val onUserBlocked: suspend (ownerUserId: String, targetUserId: String) -> Unit = { _, _ -> }
) : ContactMutationUseCase {

    override suspend fun setNickname(userId: String, nickname: String): Result<Unit> {
        val targetId = userId.trim()
        if (targetId.isBlank()) {
            return Result.failure(IllegalArgumentException("User ID cannot be blank"))
        }

        val (ownerUserId, _) = sessionProvider()
        if (ownerUserId.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Session expired or user not logged in"))
        }

        return try {
            val cleanNickname = nickname.trim()
            setNicknameLocal(targetId, cleanNickname)
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun removeFriend(userId: String): Result<Unit> {
        val targetId = userId.trim()
        if (targetId.isBlank()) {
            return Result.failure(IllegalArgumentException("User ID cannot be blank"))
        }

        val (ownerUserId, token) = sessionProvider()
        if (ownerUserId.isNullOrBlank() || token.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Session expired or user not logged in"))
        }

        if (!BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId, liveToken = token, liveUserId = ownerUserId)) {
            return Result.failure(IllegalStateException("Session validation failed"))
        }

        return try {
            val apiResult = removeFriendApi(token, targetId)

            val (currentUserId, currentToken) = sessionProvider()
            if (currentUserId != ownerUserId || currentToken.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Account switched during remove friend"))
            }

            apiResult.fold(
                onSuccess = {
                    onFriendRemoved(ownerUserId, targetId)
                    Result.success(Unit)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun blockUser(userId: String): Result<Unit> {
        val targetId = userId.trim()
        if (targetId.isBlank()) {
            return Result.failure(IllegalArgumentException("Target user ID cannot be blank"))
        }

        val (ownerUserId, token) = sessionProvider()
        if (ownerUserId.isNullOrBlank() || token.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Session expired or user not logged in"))
        }

        if (targetId == ownerUserId) {
            return Result.failure(IllegalArgumentException("Cannot block yourself"))
        }

        if (!BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId, liveToken = token, liveUserId = ownerUserId)) {
            return Result.failure(IllegalStateException("Session validation failed"))
        }

        return try {
            val apiResult = blockUserApi(token, targetId)

            val (currentUserId, currentToken) = sessionProvider()
            if (currentUserId != ownerUserId || currentToken.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Account switched during block user"))
            }

            apiResult.fold(
                onSuccess = {
                    onUserBlocked(ownerUserId, targetId)
                    Result.success(Unit)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun unblockUser(userId: String): Result<Unit> {
        val targetId = userId.trim()
        if (targetId.isBlank()) {
            return Result.failure(IllegalArgumentException("Target user ID cannot be blank"))
        }

        val (ownerUserId, token) = sessionProvider()
        if (ownerUserId.isNullOrBlank() || token.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Session expired or user not logged in"))
        }

        if (!BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId, liveToken = token, liveUserId = ownerUserId)) {
            return Result.failure(IllegalStateException("Session validation failed"))
        }

        return try {
            val apiResult = unblockUserApi(token, targetId)

            val (currentUserId, currentToken) = sessionProvider()
            if (currentUserId != ownerUserId || currentToken.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Account switched during unblock user"))
            }

            apiResult
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
