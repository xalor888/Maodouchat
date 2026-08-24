package com.maodouchat.crypto

import com.maodouchat.MaodouchatApp

/**
 * 1:1 SessionCipher 按 (peer, device) 寻址，不能只按 chatId 门闩。
 * SECRET 与同人 DIRECT 共用同一 ratchet；未打开群的 SK_DIST 1:1 包装也会
 * decryptContentEnvelope(senderId)。ChatDetail 占用该 peer 时，列表/Backlog/ingest
 * 禁止再走 SessionCipher。
 */
object SessionCipherOccupancy {

    @Volatile
    var openPeerUserId: String? = null
        private set

    fun occupy(chatId: String, peerUserId: String? = null, updatePeer: Boolean = false) {
        val id = chatId.trim()
        if (id.isBlank()) return
        MaodouchatApp.openChatDetailId = id
        MaodouchatApp.activeChatId = id
        if (updatePeer) {
            openPeerUserId = peerUserId?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    fun occupyPeer(peerUserId: String?) {
        openPeerUserId = peerUserId?.trim()?.takeIf { it.isNotBlank() }
    }

    fun release(chatId: String) {
        val id = chatId.trim()
        if (id.isBlank()) return
        if (MaodouchatApp.activeChatId == id) {
            MaodouchatApp.activeChatId = null
            MaodouchatApp.activeChatOpenedAtMs = 0L
        }
        if (MaodouchatApp.openChatDetailId == id) {
            MaodouchatApp.openChatDetailId = null
        }
        // Peer occupancy follows the open chat; another VM may re-occupy immediately.
        if (MaodouchatApp.openChatDetailId == null) {
            openPeerUserId = null
        }
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
