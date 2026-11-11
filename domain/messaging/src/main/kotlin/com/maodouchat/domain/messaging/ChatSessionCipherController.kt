package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Lease

// Signal session cipher 占用一族：app 侧纯搬移；占用标记仍在 app 侧
// SessionCipherOccupancy（写 MaodouchatApp 全局单例），这里只持租约、经端口调用。
class ChatSessionCipherController(
    initialChatId: String,
    private val getChatById: suspend (String) -> Chat?,
    private val acquireOccupancy: (String) -> Lease,
    private val occupyOccupancy: (Lease, String, String?, Boolean) -> Unit,
    private val releaseOccupancy: (Lease) -> Unit,
    private val currentUserId: () -> String,
) {
    private val sessionOccupancyLock = Any()
    @Volatile
    private var sessionOccupancyLease = if (initialChatId.isBlank()) {
        null
    } else {
        acquireOccupancy(initialChatId)
    }

    fun occupySessionCipher(
        targetChatId: String,
        peerUserId: String? = null,
        updatePeer: Boolean = false,
    ) {
        if (targetChatId.isBlank()) return
        synchronized(sessionOccupancyLock) {
            val lease = sessionOccupancyLease
                ?: acquireOccupancy(targetChatId).also {
                    sessionOccupancyLease = it
                }
            occupyOccupancy(lease, targetChatId, peerUserId, updatePeer)
        }
    }

    fun releaseSessionCipher() {
        val lease = synchronized(sessionOccupancyLock) {
            sessionOccupancyLease.also { sessionOccupancyLease = null }
        } ?: return
        releaseOccupancy(lease)
    }

    /** Room already knows participants; pin 1:1 peer before getChats returns. */
    suspend fun pinSessionCipherPeerFromCache(targetChatId: String) {
        val cached = getChatById(targetChatId) ?: return
        if (cached.isGroup) {
            occupySessionCipher(cached.id, peerUserId = null, updatePeer = true)
            return
        }
        val peer = cached.participants.firstOrNull { it.id.isNotBlank() && it.id != currentUserId() }?.id
        if (peer != null) {
            occupySessionCipher(cached.id, peer, updatePeer = true)
        }
    }
}
