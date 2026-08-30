package com.maodouchat.server.plugins

import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 实时发布 / 断连（自 Sockets.kt 拆分，checklist B08）。
// 在线状态广播、动态删除广播、可见性撤回、定向/全量推送与按会话断开集中于此，
// 复用 Sockets.kt 的连接注册表（ConnectionRegistry.onlineUsers / session* 锁 / 限流器）。

internal suspend fun broadcastUserStatus(userId: String, isOnline: Boolean, json: Json, userRepo: UserRepository) {
    // 隐私保护：nobody / showOnline=false 不广播在线状态
    if (!userRepo.shouldBroadcastOnline(userId)) return
    // 频控：防重连风暴放大（单用户 connect/disconnect 反复触发 O(N) 全量广播）。
    // 丢弃超额广播不影响最终状态——客户端以定期轮询在线状态兜底收敛。
    if (!presenceBroadcastRateLimiter.acquire(userId, maxPerMinute = 20)) return
    // 防广播风暴：在线用户过多时跳过全量 fanout（O(N) 每条事件 → O(N²) 放大攻击面），
    // 由客户端定期轮询在线状态兜底。
    if (ConnectionRegistry.onlineUsers.size > PRESENCE_FANOUT_CAP) return
    val status = UserStatusPayload(userId, isOnline, System.currentTimeMillis())
    val msg = json.encodeToString(WsMessage("USER_STATUS", json.encodeToString(status)))
    // 广播给所有在线用户（除自己）；双向拉黑不泄露在线状态
    // 8.48 修复 H7：一次批量查 viewer 与全体在线用户的双向拉黑（此前逐在线用户
    // isBlockedEitherWay → 每人 2 次 BlockedUsers 查询，PRESENCE_FANOUT_CAP=500 → 1000 次）
    val onlineIds = ConnectionRegistry.onlineUsers.keys.filter { it != userId }
    val blockedIds = try { userRepo.blockedEitherWayIdsInTx(userId, onlineIds) } catch (_: Exception) { emptySet() }
    onlineIds.forEach { uid ->
        if (uid !in blockedIds && userRepo.shouldShowOnlineTo(userId, uid)) {
            sendToUser(uid, msg)
        }
    }
}


/** 动态被作者/版主删除后向所有在线客户端广播，前端即时移除，避免残留。 */
internal suspend fun broadcastPostDeleted(postId: String, actorId: String? = null) {
    if (postId.isBlank()) return
    // 9.136：与 presence 广播同构的防护——频控（普通用户路径）+ 在线规模上限，
    // 防反复建/删动态把单事件放大为 O(N) 全量 fanout
    if (actorId != null && !postDeleteBroadcastLimiter.acquire(actorId, maxPerMinute = 30)) return
    if (ConnectionRegistry.onlineUsers.size > PRESENCE_FANOUT_CAP) return
    val json = Json { ignoreUnknownKeys = true }
    val message = json.encodeToString(
        WsMessage.serializer(),
        WsMessage("POST_DELETED", json.encodeToString(PostDeletedPayload.serializer(), PostDeletedPayload(postId)))
    )
    ConnectionRegistry.onlineUserIds().forEach { sendToUser(it, message) }
}

internal suspend fun broadcastUserVisibilityRevoked(
    userId: String,
    onlineRevoked: Boolean,
    statusRevoked: Boolean,
    json: Json,
    userRepo: UserRepository
) {
    if (!onlineRevoked && !statusRevoked) return
    val payload = UserStatusPayload(
        userId = userId,
        isOnline = false,
        lastSeen = 0L,
        onlineRevoked = onlineRevoked,
        statusRevoked = statusRevoked
    )
    val message = json.encodeToString(WsMessage("USER_STATUS", json.encodeToString(payload)))
    // 本人其他设备也需清缓存；双向拉黑关系不得借撤回事件感知对方活动。
    // 8.48 修复 H7（同构）：批量查双向拉黑（此前逐在线用户 isBlockedEitherWay → 2 次查询/人）
    val onlineIds = ConnectionRegistry.onlineUsers.keys.filter { it != userId }
    val blockedIds = try { userRepo.blockedEitherWayIdsInTx(userId, onlineIds) } catch (_: Exception) { emptySet() }
    ConnectionRegistry.onlineUsers.keys
        .filter { viewerId -> viewerId == userId || viewerId !in blockedIds }
        .forEach { viewerId -> sendToUser(viewerId, message) }
}

/**
 * 并发安全地向单个 session 发送文本帧。Ktor 的 [WebSocketSession.send] 不保证并发安全，
 * 用 [ConnectionRegistry.sessionSendLocks] 中按 session 的互斥锁串行化，避免同收件人的多路 fanout 帧交错。
 */
internal suspend fun runWithWsSendTimeout(
    timeoutMs: Long = WS_SEND_TIMEOUT_MS,
    send: suspend () -> Unit,
) {
    try {
        kotlinx.coroutines.withTimeout(timeoutMs) {
            send()
        }
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
        // A timeout owned by this helper leaves the parent active; cancellation from the
        // WebSocket/application parent must retain CancellationException semantics so its Job
        // is not accidentally converted into a dead-session IOException.
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw error
        throw java.io.IOException("ws send timeout after ${timeoutMs}ms", error)
    }
}

internal suspend fun sendSafe(session: WebSocketSession, text: String) {
    val lock = ConnectionRegistry.sessionSendLocks.compute(session) { _, existing ->
        val current = existing ?: ConnectionRegistry.SessionSendLock()
        current.users++
        current
    }!!
    try {
        lock.mutex.withLock {
            // 9.226：发送限时——卡住的会话（TCP 写缓冲满/弱网）不得拖住整条群扇出链；
            // 超时异常转换为 IOException，让调用方按死连接清理（它不是 CancellationException
            // 子类，本可直穿 catch，但显式转换表明超时语义，且避免将来实现变更时误判）。
            runWithWsSendTimeout {
                session.send(Frame.Text(text))
            }
        }
    } finally {
        ConnectionRegistry.sessionSendLocks.computeIfPresent(session) { _, current ->
            if (current === lock) {
                if (current.users > 1) {
                    current.users--
                    current
                } else {
                    null
                }
            } else {
                current
            }
        }
    }
}

internal suspend fun sendToUser(userId: String, message: String) {
    val sessions = ConnectionRegistry.onlineUsers[userId] ?: return
    val failedSessions = mutableListOf<WebSocketSession>()
    sessions.forEach { session ->
        try {
            sendSafe(session, message)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failedSessions.add(session)
        }
    }
    failedSessions.forEach { session ->
        try {
            session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "send failed"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        ConnectionRegistry.sessionAccessJtis.remove(session)
        ConnectionRegistry.sessionAuthSessionIds.remove(session)
        ConnectionRegistry.removeSessionSendLock(session)
        sessions.remove(session)
    }
    // 用 compute 原子地检查并移除空列表，避免 check-then-remove 竞态：
    // 旧实现先 isEmpty() 再 remove()，两步之间新 session 可能被加入列表却被误删。
    ConnectionRegistry.onlineUsers.compute(userId) { _, existing ->
        if (existing === sessions && sessions.isEmpty()) null else existing
    }
}

/**
 * Force-close all live WebSocket sessions for [userId].
 * Call after password change / logout-all / account deactivation so revoked tokens
 * cannot keep accepting signaling until the TCP socket drops.
 */
internal suspend fun disconnectUserSessions(userId: String, reason: String = "会话已失效") {
    if (userId.isBlank()) return
    val sessions = ConnectionRegistry.onlineUsers.remove(userId) ?: return
    sessions.forEach { session ->
        ConnectionRegistry.sessionAccessJtis.remove(session)
        ConnectionRegistry.sessionAuthSessionIds.remove(session)
        ConnectionRegistry.removeSessionSendLock(session)
        try {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason.take(120)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    markOfflineAndBroadcastIfNoSessions(userId)
}

internal suspend fun disconnectUserSessionsByAuthSessionIds(
    userId: String,
    authSessionIds: Set<String>,
    reason: String = "会话已失效"
) {
    if (userId.isBlank() || authSessionIds.isEmpty()) return
    val sessions = ConnectionRegistry.onlineUsers[userId] ?: return
    val toClose = sessions.filter { session -> ConnectionRegistry.sessionAuthSessionIds[session] in authSessionIds }
    toClose.forEach { session ->
        ConnectionRegistry.sessionAccessJtis.remove(session)
        ConnectionRegistry.sessionAuthSessionIds.remove(session)
        ConnectionRegistry.removeSessionSendLock(session)
        try {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason.take(120)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        sessions.remove(session)
    }
    ConnectionRegistry.onlineUsers.compute(userId) { _, existing ->
        if (existing === sessions && sessions.isEmpty()) null else existing
    }
    markOfflineAndBroadcastIfNoSessions(userId)
}

/**
 * 单设备 logout：只关闭 access jti 匹配的 WS，避免误踢其他仍有效的设备。
 * 若无 jti（body-only logout 未带 Authorization），则只吊销 refresh token，不踢任何 WS
 * （无法区分会话时宁可保留连接，避免误踢其他仍有效的设备；access token 自然过期后 WS 会被 authWatchdog 踢掉）。
 */
internal suspend fun disconnectUserSessionsByAccessJti(
    userId: String,
    accessJti: String?,
    reason: String = "已退出登录"
) {
    if (userId.isBlank()) return
    // 无 jti（过期 access / body-only logout）：只吊销 refresh，不误踢其他设备 WS
    if (accessJti.isNullOrBlank()) {
        return
    }
    val sessions = ConnectionRegistry.onlineUsers[userId] ?: return
    val toClose = sessions.filter { session -> ConnectionRegistry.sessionAccessJtis[session] == accessJti }
    toClose.forEach { session ->
        ConnectionRegistry.sessionAccessJtis.remove(session)
        ConnectionRegistry.sessionAuthSessionIds.remove(session)
        ConnectionRegistry.removeSessionSendLock(session)
        try {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason.take(120)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        sessions.remove(session)
    }
    ConnectionRegistry.onlineUsers.compute(userId) { _, existing ->
        if (existing === sessions && sessions.isEmpty()) null else existing
    }
    markOfflineAndBroadcastIfNoSessions(userId)
}

/**
 * 强制断连后若该用户已无任何在线会话，落库离线并向其他在线用户广播。
 * 状态锁 + 锁外二次确认避免新连接注册后被旧清理路径误标离线。
 */
internal suspend fun markOfflineAndBroadcastIfNoSessions(userId: String) {
    PresenceService.markOffline(userId, Json { ignoreUnknownKeys = true }, UserRepository())
}
