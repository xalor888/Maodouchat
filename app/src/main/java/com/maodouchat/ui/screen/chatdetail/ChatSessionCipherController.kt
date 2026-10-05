package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.repository.ChatRepository

// Signal session cipher 占用一族：从 ChatDetailViewModel 纯搬移；VM 只留同签名委托。
internal class ChatSessionCipherController(
    initialChatId: String,
    private val chatRepo: ChatRepository,
    private val currentUserId: () -> String,
) {
    private val sessionOccupancyLock = Any()
    @Volatile
    private var sessionOccupancyLease = if (initialChatId.isBlank()) {
        null
    } else {
        com.maodouchat.crypto.SessionCipherOccupancy.acquire(initialChatId)
    }

    internal fun occupySessionCipher(
        targetChatId: String,
        peerUserId: String? = null,
        updatePeer: Boolean = false,
    ) {
        if (targetChatId.isBlank()) return
        synchronized(sessionOccupancyLock) {
            val lease = sessionOccupancyLease
                ?: com.maodouchat.crypto.SessionCipherOccupancy.acquire(targetChatId).also {
                    sessionOccupancyLease = it
                }
            com.maodouchat.crypto.SessionCipherOccupancy.occupy(
                lease,
                targetChatId,
                peerUserId,
                updatePeer,
            )
        }
    }

    internal fun releaseSessionCipher() {
        val lease = synchronized(sessionOccupancyLock) {
            sessionOccupancyLease.also { sessionOccupancyLease = null }
        } ?: return
        com.maodouchat.crypto.SessionCipherOccupancy.release(lease)
    }

    /** Room already knows participants; pin 1:1 peer before getChats returns. */
    internal suspend fun pinSessionCipherPeerFromCache(targetChatId: String) {
        val cached = chatRepo.getChatById(targetChatId) ?: return
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
