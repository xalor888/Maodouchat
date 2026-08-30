package com.maodouchat.server.plugins

import com.maodouchat.server.repository.UserRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * B08：在线状态迁移服务。锁内只做 DB setOnline，广播移到锁外（挂起 I/O），
 * 锁外二次确认避免新连接注册后被旧清理路径误标离线。
 */
internal object PresenceService {
    private val userStatusLocks = Array(64) { Mutex() }
    internal fun userStatusLock(userId: String): Mutex =
        userStatusLocks[(userId.hashCode() and Int.MAX_VALUE) % userStatusLocks.size]

    suspend fun markOnline(userId: String, json: Json, userRepo: UserRepository) {
        userStatusLock(userId).withLock { userRepo.setOnline(userId, true) }
        broadcastUserStatus(userId, true, json, userRepo)
    }

    suspend fun markOffline(userId: String, json: Json, userRepo: UserRepository) {
        userStatusLock(userId).withLock {
            if (!ConnectionRegistry.onlineUsers.containsKey(userId)) {
                runCatching { userRepo.setOnline(userId, false) }
            }
        }
        if (!ConnectionRegistry.onlineUsers.containsKey(userId)) {
            broadcastUserStatus(userId, false, json, userRepo)
        }
    }

    suspend fun setForeground(userId: String, foreground: Boolean, json: Json, userRepo: UserRepository) {
        userStatusLock(userId).withLock { userRepo.setOnline(userId, foreground) }
        broadcastUserStatus(userId, foreground, json, userRepo)
    }
}
