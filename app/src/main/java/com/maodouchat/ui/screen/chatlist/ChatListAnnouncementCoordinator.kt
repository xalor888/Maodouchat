package com.maodouchat.ui.screen.chatlist

import com.maodouchat.notification.AnnouncementPolicy
import com.maodouchat.security.BackgroundSessionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 会话列表公告 + 推送验签密钥（ChatList 瘦身：ApiService 旁路切片）。
 *
 * 承接活跃公告拉取/ack 与 push verify key 刷新；ViewModel 只委托入口，
 * 不直接拼 JSON 或调用 ApiService。
 */
internal class ChatListAnnouncementCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatListUiState>,
    /** 端口不再接令牌：凭据由仓库自己取（见 `data/repository/SessionTokens.kt`）。 */
    private val fetchActiveAnnouncements: suspend () -> Result<String>,
    private val ackAnnouncementRemote: suspend (announcementId: String) -> Result<*>,
    private val fetchPushVerifyKeyRaw: suspend () -> Result<String>,
    private val applyPushVerifyKey: (raw: String) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val announcementAckInFlight = mutableSetOf<String>()

    fun refreshAnnouncements() {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession()) return
        scope.launch {
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) {
                return@launch
            }
            val raw = fetchActiveAnnouncements().getOrNull().orEmpty()
            if (raw.isBlank()) return@launch
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) {
                return@launch
            }
            val list = AnnouncementPolicy.parseAndFilterActivePayload(raw, nowMs())
            if (com.maodouchat.session.CurrentSession.ownerUserId() != ownerUserId) return@launch
            uiState.update { it.copy(activeAnnouncements = list) }
        }
    }

    fun ackAnnouncement(announcementId: String) {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession()) return
        if (!announcementAckInFlight.add(announcementId)) return
        scope.launch {
            try {
                ackAnnouncementRemote(announcementId)
            } finally {
                announcementAckInFlight.remove(announcementId)
            }
            if (com.maodouchat.session.CurrentSession.ownerUserId() != ownerUserId) return@launch
            uiState.update {
                it.copy(activeAnnouncements = it.activeAnnouncements.filterNot { a -> a.id == announcementId })
            }
        }
    }

    fun fetchPushVerifyKey() {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession()) return
        scope.launch {
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) {
                return@launch
            }
            val raw = fetchPushVerifyKeyRaw().getOrNull().orEmpty()
            if (raw.isBlank()) return@launch
            applyPushVerifyKey(raw)
        }
    }
}

/** 解析推送 HMAC 密钥 JSON：`{"key":...}`；key 缺失/空 → 清理本地缓存。 */
internal sealed interface PushVerifyKeyAction {
    data object Clear : PushVerifyKeyAction
    data class Set(val key: String) : PushVerifyKeyAction
    data object Ignore : PushVerifyKeyAction
}

internal fun parsePushVerifyKeyPayload(raw: String): PushVerifyKeyAction {
    if (raw.isBlank()) return PushVerifyKeyAction.Ignore
    return runCatching {
        val o = org.json.JSONObject(raw)
        if (o.isNull("key")) {
            PushVerifyKeyAction.Clear
        } else {
            val key = o.optString("key")
            if (key.isNotBlank() && key != "null") PushVerifyKeyAction.Set(key)
            else PushVerifyKeyAction.Ignore
        }
    }.getOrDefault(PushVerifyKeyAction.Ignore)
}
