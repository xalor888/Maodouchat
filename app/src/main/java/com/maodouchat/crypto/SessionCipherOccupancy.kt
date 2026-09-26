package com.maodouchat.crypto

import com.maodouchat.MaodouchatApp

/**
 * 1:1 SessionCipher 按 (peer, device) 寻址，不能只按 chatId 门闩。
 * SECRET 与同人 DIRECT 共用同一 ratchet；未打开群的 SK_DIST 1:1 包装也会
 * decryptContentEnvelope(senderId)。ChatDetail 占用该 peer 时，列表/Backlog/ingest
 * 禁止再走 SessionCipher。
 */
object SessionCipherOccupancy {

    class Lease internal constructor(internal val generation: Long)

    private var generation: Long = 0L

    @Volatile
    private var activeLeaseGeneration: Long = 0L

    @Volatile
    var openPeerUserId: String? = null
        private set

    /**
     * Claim ownership for one ChatDetail instance. A later claim supersedes the previous one,
     * so a delayed onCleared from the old ViewModel cannot release the new conversation's
     * SessionCipher guard.
     */
    @Synchronized
    fun acquire(chatId: String): Lease {
        val lease = Lease(++generation)
        activeLeaseGeneration = lease.generation
        occupyInternal(chatId, peerUserId = null, updatePeer = false)
        return lease
    }

    /** Update the marker only while [lease] still owns it. */
    @Synchronized
    fun occupy(
        lease: Lease,
        chatId: String,
        peerUserId: String? = null,
        updatePeer: Boolean = false,
    ): Boolean {
        if (lease.generation != activeLeaseGeneration) return false
        occupyInternal(chatId, peerUserId, updatePeer)
        return true
    }

    @Synchronized
    fun occupy(chatId: String, peerUserId: String? = null, updatePeer: Boolean = false) {
        occupyInternal(chatId, peerUserId, updatePeer)
    }

    private fun occupyInternal(chatId: String, peerUserId: String?, updatePeer: Boolean) {
        val id = chatId.trim()
        if (id.isBlank()) return
        MaodouchatApp.openChatDetailId = id
        MaodouchatApp.activeChatId = id
        if (updatePeer) {
            val trimmed = peerUserId?.trim()
            when {
                trimmed == null -> openPeerUserId = null
                trimmed.isBlank() -> { /* keep existing peer; blank is not a group clear */ }
                else -> openPeerUserId = trimmed
            }
        }
    }

    fun occupyPeer(peerUserId: String?) {
        openPeerUserId = peerUserId?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * 标记当前聊天界面变为活跃的时刻（幂等）：backlog 回填用该时间戳判断
     * 「早于界面打开」的消息不播接收提示音。只有尚未设置时才写入——
     * ON_RESUME 不得覆盖 ViewModel 在打开聊天时已写入的值。
     *
     * 此前该 `if (== 0L) 置值` 逻辑写在 `ChatDetailRoute` 的 ON_RESUME 观察器里，
     * 直接抓 `MaodouchatApp` 全局单例；挪到这里后 Route 不再直连持久层/全局单例
     * （`ClientArchitectureTest` 两份直连预算的 Route 条目随之归零）。
     */
    fun refreshActiveChatOpenedAtIfUnset() {
        if (MaodouchatApp.activeChatOpenedAtMs == 0L) {
            MaodouchatApp.activeChatOpenedAtMs = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun release(lease: Lease): Boolean {
        if (lease.generation != activeLeaseGeneration) return false
        activeLeaseGeneration = 0L
        MaodouchatApp.activeChatId = null
        MaodouchatApp.activeChatOpenedAtMs = 0L
        MaodouchatApp.openChatDetailId = null
        openPeerUserId = null
        return true
    }

    /** Legacy release path for non-ChatDetail callers. It is idempotent and never clears a
     * different chat's marker, but ChatDetail should use the lease overload above. */
    @Synchronized
    fun release(chatId: String): Boolean {
        val id = chatId.trim()
        if (id.isBlank() || MaodouchatApp.openChatDetailId != id && MaodouchatApp.activeChatId != id) {
            return false
        }
        activeLeaseGeneration = 0L
        if (MaodouchatApp.activeChatId == id) {
            MaodouchatApp.activeChatId = null
            MaodouchatApp.activeChatOpenedAtMs = 0L
        }
        if (MaodouchatApp.openChatDetailId == id) {
            MaodouchatApp.openChatDetailId = null
            openPeerUserId = null
        }
        return true
    }

    fun isChatOccupied(chatId: String): Boolean {
        if (chatId.isBlank()) return false
        return chatId == MaodouchatApp.openChatDetailId || chatId == MaodouchatApp.activeChatId
    }

    fun isPeerOccupied(peerUserId: String): Boolean {
        if (peerUserId.isBlank()) return false
        return peerUserId == openPeerUserId
    }

    /** 列表/Backlog/SK unwrap 对 1:1 SessionCipher 必须同时看 chatId 与 sender peer。 */
    fun shouldSkipSessionCipher(chatId: String, peerUserId: String): Boolean =
        isChatOccupied(chatId) || isPeerOccupied(peerUserId)
}
