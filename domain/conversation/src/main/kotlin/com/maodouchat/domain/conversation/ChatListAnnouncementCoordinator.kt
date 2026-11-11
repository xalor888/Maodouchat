package com.maodouchat.domain.conversation

import com.maodouchat.core.model.AnnouncementData
import com.maodouchat.core.model.AnnouncementPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 会话列表公告 + 推送验签密钥（ChatList 瘦身：ApiService 旁路切片）。
 *
 * 承接活跃公告拉取/ack 与 push verify key 刷新；ViewModel 只委托入口，
 * 不直接拼 JSON 或调用 ApiService。
 */
class ChatListAnnouncementCoordinator(
    private val scope: CoroutineScope,
    private val hasSession: () -> Boolean,
    private val ownerUserId: () -> String,
    private val sessionMayContinue: (String) -> Boolean,
    private val getActiveAnnouncements: () -> List<AnnouncementData>,
    private val updateActiveAnnouncements: ((List<AnnouncementData>) -> List<AnnouncementData>) -> Unit,
    /** 端口不再接令牌：凭据由仓库自己取（见 `data/repository/SessionTokens.kt`）。 */
    private val fetchActiveAnnouncements: suspend () -> Result<String>,
    private val ackAnnouncementRemote: suspend (announcementId: String) -> Result<*>,
    private val fetchPushVerifyKeyRaw: suspend () -> Result<String>,
    private val applyPushVerifyKey: (raw: String) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val announcementAckInFlight = mutableSetOf<String>()

    fun refreshAnnouncements() {
        val owner = ownerUserId()
        if (!hasSession()) return
        scope.launch {
            if (!sessionMayContinue(owner)) {
                return@launch
            }
            val raw = fetchActiveAnnouncements().getOrNull().orEmpty()
            if (raw.isBlank()) return@launch
            if (!sessionMayContinue(owner)) {
                return@launch
            }
            val list = AnnouncementPolicy.parseAndFilterActivePayload(raw, nowMs())
            if (ownerUserId() != owner) return@launch
            updateActiveAnnouncements { list }
        }
    }

    fun ackAnnouncement(announcementId: String) {
        val owner = ownerUserId()
        if (!hasSession()) return
        if (!announcementAckInFlight.add(announcementId)) return
        scope.launch {
            try {
                ackAnnouncementRemote(announcementId)
            } finally {
                announcementAckInFlight.remove(announcementId)
            }
            if (ownerUserId() != owner) return@launch
            updateActiveAnnouncements { current ->
                current.filterNot { a -> a.id == announcementId }
            }
        }
    }

    fun fetchPushVerifyKey() {
        val owner = ownerUserId()
        if (!hasSession()) return
        scope.launch {
            if (!sessionMayContinue(owner)) {
                return@launch
            }
            val raw = fetchPushVerifyKeyRaw().getOrNull().orEmpty()
            if (raw.isBlank()) return@launch
            applyPushVerifyKey(raw)
        }
    }
}
