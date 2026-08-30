package com.maodouchat.server.plugins

import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * B08：WS 连接注册表。在线 session 映射、access jti / auth session id 关联与
 * 按 session 的发送互斥锁统一收敛于此，替代原先散落在 Sockets.kt 顶部的全局状态。
 */
internal object ConnectionRegistry {
    /** userId -> 活跃 WebSocketSession 列表。 */
    val onlineUsers = ConcurrentHashMap<String, CopyOnWriteArrayList<WebSocketSession>>()

    /** session -> access token jti；单设备 logout 只踢匹配 jti 的连接。 */
    val sessionAccessJtis = ConcurrentHashMap<WebSocketSession, String>()

    /** session -> auth session id。 */
    val sessionAuthSessionIds = ConcurrentHashMap<WebSocketSession, String>()

    /**
     * 单 session 发送互斥锁。Ktor WebSocketSession.send 不保证并发安全，按 session
     * 串行化发送避免多路 fanout 帧交错。
     */
    class SessionSendLock(val mutex: Mutex = Mutex(), var users: Int = 0)

    val sessionSendLocks = ConcurrentHashMap<WebSocketSession, SessionSendLock>()

    /** Snapshot of user ids with at least one live WS session (admin ops / metrics). */
    fun onlineUserIds(): List<String> = onlineUsers.keys.toList()

    /** 仅在无发送者使用时删除锁，避免等待中的发送者与新建锁并发写同一 session。 */
    fun removeSessionSendLock(session: WebSocketSession) {
        sessionSendLocks.computeIfPresent(session) { _, current ->
            if (current.users == 0) null else current
        }
    }
}
