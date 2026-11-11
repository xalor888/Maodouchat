package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ExploreBadgeStore
import com.maodouchat.core.model.NotificationCenterItem
import com.maodouchat.core.model.NotificationCenterType
import com.maodouchat.core.model.UnreadBadgeStore
import com.maodouchat.core.session.SessionUiResetEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 角标编排：通知中心未读、Explore 互动未读、底部导航汇总未读，以及
 * 「正在输入」presence 投影。纯转发/订阅逻辑，不持有业务状态。
 *
 * 端口化：通知仓库走 items 流 + 初始未读数；登出/切号归零事件走 uiResetEvents 流；
 * 会话列表走 chats 流；typing presence 走 typingByChat 流 + 写回端口。
 */
class ChatListBadgeCoordinator(
    private val scope: CoroutineScope,
    private val notificationItems: Flow<List<NotificationCenterItem>>,
    private val initialNotificationUnreadCount: () -> Int,
    private val uiResetEvents: Flow<SessionUiResetEvent>,
    private val chats: Flow<List<Chat>>,
    private val typingByChat: Flow<Map<String, String>>,
    private val updateTypingByChat: (Map<String, String>) -> Unit,
) {
    val notificationCenterUnread: StateFlow<Int> = notificationItems
        .map { items -> items.count { !it.read } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), initialNotificationUnreadCount())

    // 1.112：Explore 标签「动态互动」未读角标（仅 POST_INTERACTION 未读）
    val exploreBadgeCount: StateFlow<Int> = notificationItems
        .map { items -> items.count { !it.read && it.type == NotificationCenterType.POST_INTERACTION } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0)

    fun start() {
        // 1.55 事件化：登出/切号清理后 SecureSessionManager 经 uiResetEvents 发归零命令
        //（security 层不再直写本 ui 包的 UnreadBadgeStore 单例）；归零幂等，replay 保证
        // 清理时本 VM 尚未存活也能在重建后收到。
        scope.launch {
            uiResetEvents.collect { event ->
                if (event == SessionUiResetEvent.UNREAD_BADGE) {
                    UnreadBadgeStore.totalUnread.value = 0
                }
            }
        }
        // 1.54：底部导航未读角标——汇总未读数推送到 UnreadBadgeStore
        scope.launch {
            // 8.49 修复：与 unreadChatCount/文件夹角标口径统一，排除已归档会话——
            // 否则归档会话来消息时 Tab 角标上涨，默认列表却看不到对应未读
            chats.map { list -> list.filter { !it.archived }.sumOf { it.unreadCount } }
                .distinctUntilChanged()
                .collect { UnreadBadgeStore.totalUnread.value = it }
        }
        // 1.112：Explore 标签「动态互动」未读角标
        scope.launch {
            notificationItems
                .map { items -> items.count { !it.read && it.type == NotificationCenterType.POST_INTERACTION } }
                .distinctUntilChanged()
                .collect { ExploreBadgeStore.count.value = it }
        }
        // 1.103：会话列表「正在输入」presence（3s 过期由 store 维护）
        scope.launch {
            typingByChat.collect { typing ->
                updateTypingByChat(typing)
            }
        }
    }
}
