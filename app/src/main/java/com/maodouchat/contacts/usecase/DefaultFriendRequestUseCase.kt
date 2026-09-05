package com.maodouchat.contacts.usecase

import com.maodouchat.data.model.User
import com.maodouchat.network.ApiService
import com.maodouchat.network.FriendRequestDto
import com.maodouchat.security.BackgroundSessionGate
import kotlinx.coroutines.CancellationException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 好友申请用例实现。
 *
 * 核心防御特性：
 * 1. 账号隔离：门禁校验防串号与会话泄露。
 * 2. 并发防重：同一目标用户的好友申请不允许并发重入。
 * 3. 接受/撤回竞态拦截：同一申请 ID 在接受或撤回期间锁定，终态记录拦截反向操作。
 * 4. 乱序到达校正：统一去重并按创建时间倒序排定单向因果顺序。
 */
class DefaultFriendRequestUseCase(
    private val sessionProvider: () -> Pair<String?, String?>,
    private val getIncomingRequestsApi: suspend (token: String) -> Result<List<FriendRequestDto>> = { token ->
        ApiService.getIncomingFriendRequests(token)
    },
    private val getOutgoingRequestsApi: suspend (token: String) -> Result<List<FriendRequestDto>> = { token ->
        ApiService.getOutgoingFriendRequests(token)
    },
    private val sendFriendRequestApi: suspend (token: String, targetUserId: String, note: String) -> Result<FriendRequestDto> = { token, target, note ->
        ApiService.sendFriendRequest(token, target, note)
    },
    private val acceptFriendRequestApi: suspend (token: String, requestId: String) -> Result<FriendRequestDto> = { token, id ->
        ApiService.acceptFriendRequest(token, id)
    },
    private val rejectFriendRequestApi: suspend (token: String, requestId: String) -> Result<FriendRequestDto> = { token, id ->
        ApiService.rejectFriendRequest(token, id)
    },
    private val cancelFriendRequestApi: suspend (token: String, requestId: String) -> Result<FriendRequestDto> = { token, id ->
        ApiService.cancelFriendRequest(token, id)
    },
    private val onFriendAccepted: suspend (User) -> Unit = {}
) : FriendRequestUseCase {

    private val inFlightSends = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val inFlightMutations = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val settledRequests = ConcurrentHashMap<String, String>()
    private val cachedRequests = ConcurrentHashMap<String, FriendRequestItem>()

    override suspend fun loadRequests(): Result<FriendRequestsSnapshot> {
        val (ownerUserId, token) = sessionProvider()
        if (ownerUserId.isNullOrBlank() || token.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Session expired or user not logged in"))
        }

        if (!BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId, liveToken = token, liveUserId = ownerUserId)) {
            return Result.failure(IllegalStateException("Session validation failed"))
        }

        return try {
            val incomingResult = getIncomingRequestsApi(token)
            val outgoingResult = getOutgoingRequestsApi(token)

            // 切号校验
            val (currentUserId, currentToken) = sessionProvider()
            if (currentUserId != ownerUserId || currentToken.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Account switched during requests load"))
            }

            if (incomingResult.isFailure && outgoingResult.isFailure) {
                return Result.failure(incomingResult.exceptionOrNull() ?: outgoingResult.exceptionOrNull() ?: RuntimeException("Failed to load friend requests"))
            }

            val incomingItems = (incomingResult.getOrNull() ?: emptyList())
                .map { dto ->
                    FriendRequestItem(
                        id = dto.id,
                        user = User(
                            id = dto.fromUser.id,
                            name = dto.fromUser.name,
                            avatar = dto.fromUser.avatar,
                            email = dto.fromUser.email,
                            isOnline = dto.fromUser.isOnline,
                            status = dto.fromUser.status,
                            lastSeen = dto.fromUser.lastSeen
                        ),
                        message = dto.message,
                        createdAt = dto.createdAt,
                        outgoing = false
                    )
                }
                .distinctBy { it.id }
                .sortedByDescending { it.createdAt }

            val outgoingItems = (outgoingResult.getOrNull() ?: emptyList())
                .map { dto ->
                    FriendRequestItem(
                        id = dto.id,
                        user = User(
                            id = dto.toUser.id,
                            name = dto.toUser.name,
                            avatar = dto.toUser.avatar,
                            email = dto.toUser.email,
                            isOnline = dto.toUser.isOnline,
                            status = dto.toUser.status,
                            lastSeen = dto.toUser.lastSeen
                        ),
                        message = dto.message,
                        createdAt = dto.createdAt,
                        outgoing = true
                    )
                }
                .distinctBy { it.id }
                .sortedByDescending { it.createdAt }

            incomingItems.forEach { cachedRequests[it.id] = it }
            outgoingItems.forEach { cachedRequests[it.id] = it }

            Result.success(FriendRequestsSnapshot(incoming = incomingItems, outgoing = outgoingItems))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun sendFriendRequest(targetUserId: String, note: String): Result<FriendRequestItem> {
        val target = targetUserId.trim()
        if (target.isBlank()) {
            return Result.failure(IllegalArgumentException("Target user ID cannot be blank"))
        }

        val (ownerUserId, token) = sessionProvider()
        if (ownerUserId.isNullOrBlank() || token.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Session expired or user not logged in"))
        }

        if (target == ownerUserId) {
            return Result.failure(IllegalArgumentException("Cannot send friend request to yourself"))
        }

        if (!inFlightSends.add(target)) {
            return Result.failure(IllegalStateException("A friend request to this user is already in progress"))
        }

        return try {
            if (!BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId, liveToken = token, liveUserId = ownerUserId)) {
                return Result.failure(IllegalStateException("Session validation failed"))
            }

            val apiResult = sendFriendRequestApi(token, target, note.trim())

            // 切号防护
            val (currentUserId, currentToken) = sessionProvider()
            if (currentUserId != ownerUserId || currentToken.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Account switched during send request"))
            }

            apiResult.fold(
                onSuccess = { dto ->
                    val item = FriendRequestItem(
                        id = dto.id,
                        user = User(
                            id = dto.toUser.id,
                            name = dto.toUser.name,
                            avatar = dto.toUser.avatar,
                            email = dto.toUser.email,
                            isOnline = dto.toUser.isOnline,
                            status = dto.toUser.status,
                            lastSeen = dto.toUser.lastSeen
                        ),
                        message = dto.message,
                        createdAt = dto.createdAt,
                        outgoing = true
                    )
                    cachedRequests[item.id] = item
                    Result.success(item)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            inFlightSends.remove(target)
        }
    }

    override suspend fun acceptFriendRequest(requestId: String): Result<FriendRequestItem?> {
        val id = requestId.trim()
        if (id.isBlank()) {
            return Result.failure(IllegalArgumentException("Request ID cannot be blank"))
        }

        val settled = settledRequests[id]
        if (settled != null) {
            return Result.failure(IllegalStateException("Request $id has already been settled as $settled"))
        }

        if (!inFlightMutations.add(id)) {
            return Result.failure(IllegalStateException("Operation already in flight for request $id"))
        }

        return try {
            val (ownerUserId, token) = sessionProvider()
            if (ownerUserId.isNullOrBlank() || token.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Session expired or user not logged in"))
            }

            if (!BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId, liveToken = token, liveUserId = ownerUserId)) {
                return Result.failure(IllegalStateException("Session validation failed"))
            }

            val apiResult = acceptFriendRequestApi(token, id)

            val (currentUserId, currentToken) = sessionProvider()
            if (currentUserId != ownerUserId || currentToken.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Account switched during accept request"))
            }

            apiResult.fold(
                onSuccess = { dto ->
                    settledRequests[id] = "ACCEPTED"
                    val cached = cachedRequests[id]
                    val item = cached ?: FriendRequestItem(
                        id = dto.id,
                        user = User(
                            id = dto.fromUser.id,
                            name = dto.fromUser.name,
                            avatar = dto.fromUser.avatar,
                            email = dto.fromUser.email,
                            isOnline = dto.fromUser.isOnline,
                            status = dto.fromUser.status,
                            lastSeen = dto.fromUser.lastSeen
                        ),
                        message = dto.message,
                        createdAt = dto.createdAt,
                        outgoing = false
                    )
                    onFriendAccepted(item.user)
                    Result.success(item)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            inFlightMutations.remove(id)
        }
    }

    override suspend fun rejectFriendRequest(requestId: String): Result<Unit> {
        val id = requestId.trim()
        if (id.isBlank()) {
            return Result.failure(IllegalArgumentException("Request ID cannot be blank"))
        }

        val settled = settledRequests[id]
        if (settled != null) {
            return Result.failure(IllegalStateException("Request $id has already been settled as $settled"))
        }

        if (!inFlightMutations.add(id)) {
            return Result.failure(IllegalStateException("Operation already in flight for request $id"))
        }

        return try {
            val (ownerUserId, token) = sessionProvider()
            if (ownerUserId.isNullOrBlank() || token.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Session expired or user not logged in"))
            }

            if (!BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId, liveToken = token, liveUserId = ownerUserId)) {
                return Result.failure(IllegalStateException("Session validation failed"))
            }

            val apiResult = rejectFriendRequestApi(token, id)

            val (currentUserId, currentToken) = sessionProvider()
            if (currentUserId != ownerUserId || currentToken.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Account switched during reject request"))
            }

            apiResult.fold(
                onSuccess = {
                    settledRequests[id] = "REJECTED"
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
        } finally {
            inFlightMutations.remove(id)
        }
    }

    override suspend fun cancelFriendRequest(requestId: String): Result<Unit> {
        val id = requestId.trim()
        if (id.isBlank()) {
            return Result.failure(IllegalArgumentException("Request ID cannot be blank"))
        }

        val settled = settledRequests[id]
        if (settled != null) {
            return Result.failure(IllegalStateException("Request $id has already been settled as $settled"))
        }

        if (!inFlightMutations.add(id)) {
            return Result.failure(IllegalStateException("Operation already in flight for request $id"))
        }

        return try {
            val (ownerUserId, token) = sessionProvider()
            if (ownerUserId.isNullOrBlank() || token.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Session expired or user not logged in"))
            }

            if (!BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId, liveToken = token, liveUserId = ownerUserId)) {
                return Result.failure(IllegalStateException("Session validation failed"))
            }

            val apiResult = cancelFriendRequestApi(token, id)

            val (currentUserId, currentToken) = sessionProvider()
            if (currentUserId != ownerUserId || currentToken.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Account switched during cancel request"))
            }

            apiResult.fold(
                onSuccess = {
                    settledRequests[id] = "CANCELLED"
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
        } finally {
            inFlightMutations.remove(id)
        }
    }

    override suspend fun batchAcceptFriendRequests(requestIds: List<String>): Map<String, Result<FriendRequestItem?>> {
        val results = mutableMapOf<String, Result<FriendRequestItem?>>()
        for (id in requestIds.distinct()) {
            results[id] = acceptFriendRequest(id)
        }
        return results
    }

    override suspend fun batchRejectFriendRequests(requestIds: List<String>): Map<String, Result<Unit>> {
        val results = mutableMapOf<String, Result<Unit>>()
        for (id in requestIds.distinct()) {
            results[id] = rejectFriendRequest(id)
        }
        return results
    }
}
