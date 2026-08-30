package com.maodouchat.server.plugins

import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException

/**
 * B08：单进程实时总线实现。向 [userId] 的全部活跃 session 发送文本帧，失败 session
 * 按死连接清理（锁 + 限时发送见 [sendSafe]）。
 */
object LocalRealtimeBus : RealtimeBus {
    override suspend fun publish(userId: String, message: String) {
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
        // 用 compute 原子地检查并移除空列表，避免 check-then-remove 竞态。
        ConnectionRegistry.onlineUsers.compute(userId) { _, existing ->
            if (existing === sessions && sessions.isEmpty()) null else existing
        }
    }
}
