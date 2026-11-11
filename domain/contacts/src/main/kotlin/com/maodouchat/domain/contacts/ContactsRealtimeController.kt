package com.maodouchat.domain.contacts

import com.maodouchat.core.realtime.RealtimeDomainEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// 实时事件一族：实时分发器订阅、同步协调器启动与事件路由。从 ContactsViewModel 纯搬移；
// VM 只留启动调用，刷新回调经 lambda 注入。事件流与当前会话走端口（app 侧 AppRuntime /
// CurrentSession），同步协调器本身仍是 app 侧实现（Context/Room）。
class ContactsRealtimeController(
    private val scope: CoroutineScope,
    private val eventsFlow: Flow<RealtimeDomainEvent>?,
    private val currentSession: () -> Pair<String?, String?>,
    private val realtimeSyncCoordinator: ContactsRealtimeSyncCoordinator,
    private val contactsController: ContactsController,
    private val onFriendRequestsNeedsRefresh: () -> Unit,
    private val onGroupInvitesNeedsRefresh: () -> Unit,
) {
    fun start() {
        if (eventsFlow != null) {
            realtimeSyncCoordinator.startObserving(
                scope,
                eventsFlow
            ) {
                currentSession()
            }
        }

        scope.launch {
            realtimeSyncCoordinator.syncEvents.collect { event ->
                when (event) {
                    is ContactSyncEvent.FriendRequestsNeedsRefresh -> {
                        onFriendRequestsNeedsRefresh()
                    }
                    is ContactSyncEvent.GroupInvitesNeedsRefresh -> {
                        onGroupInvitesNeedsRefresh()
                    }
                    is ContactSyncEvent.FriendAccepted -> {
                        contactsController.currentSession()?.let { session ->
                            contactsController.loadFriends(session)
                        }
                    }
                    is ContactSyncEvent.FriendRemoved -> {
                        // Room Flow 自动响应
                    }
                }
            }
        }
    }
}
