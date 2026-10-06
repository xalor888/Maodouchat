package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.repository.NotificationCenterRepository
import com.maodouchat.notification.NotificationCenterType
import com.maodouchat.security.SecureSessionAccess
import com.maodouchat.security.SessionUiResetEvent
import com.maodouchat.util.TypingPresenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 角标编排：通知中心未读、Explore 互动未读、底部导航汇总未读，以及
 * 「正在输入」presence 投影。纯转发/订阅逻辑，不持有业务状态。
 */
class ChatListBadgeCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatListUiState>,
    private val notificationCenter: NotificationCenterRepository,
) {
    val notificationCenterUnread: StateFlow<Int> = notificationCenter.items
        .map { items -> items.count { !it.read } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), notificationCenter.unreadCount())

    // 1.112：Explore 标签「动态互动」未读角标（仅 POST_INTERACTION 未读）
    val exploreBadgeCount: StateFlow<Int> = notificationCenter.items
        .map { items -> items.count { !it.read && it.type == NotificationCenterType.POST_INTERACTION } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0)

    fun start() {
        // 1.55 事件化：登出/切号清理后 SecureSessionManager 经 uiResetEvents 发归零命令
        //（security 层不再直写本 ui 包的 UnreadBadgeStore 单例）；归零幂等，replay 保证
        // 清理时本 VM 尚未存活也能在重建后收到。
        scope.launch {
            SecureSessionAccess.manager.uiResetEvents.collect { event ->
                if (event == SessionUiResetEvent.UNREAD_BADGE) {
                    UnreadBadgeStore.totalUnread.value = 0
                }
            }
        }
        // 1.54：底部导航未读角标——汇总未读数推送到 UnreadBadgeStore
        scope.launch {
            // 8.49 修复：与 unreadChatCount/文件夹角标口径统一，排除已归档会话——
            // 否则归档会话来消息时 Tab 角标上涨，默认列表却看不到对应未读
            uiState.map { state -> state.chats.filter { !it.archived }.sumOf { it.unreadCount } }
                .distinctUntilChanged()
                .collect { UnreadBadgeStore.totalUnread.value = it }
        }
        // 1.112：Explore 标签「动态互动」未读角标
        scope.launch {
            notificationCenter.items
                .map { items -> items.count { !it.read && it.type == NotificationCenterType.POST_INTERACTION } }
                .distinctUntilChanged()
                .collect { ExploreBadgeStore.count.value = it }
        }
        // 1.103：会话列表「正在输入」presence（3s 过期由 store 维护）
        scope.launch {
            TypingPresenceStore.typingByChat.collect { typing ->
                uiState.update { it.copy(typingByChat = typing) }
            }
        }
    }
}
