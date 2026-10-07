package com.maodouchat.ui.screen.contacts

import android.app.Application
import com.maodouchat.contacts.sync.ContactSyncEvent
import com.maodouchat.contacts.sync.ContactsRealtimeSyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 实时事件一族：实时分发器订阅、同步协调器启动与事件路由。从 ContactsViewModel 纯搬移；
// VM 只留启动调用，刷新回调经 lambda 注入。
internal class ContactsRealtimeController(
    private val scope: CoroutineScope,
    private val application: Application,
    private val realtimeSyncCoordinator: ContactsRealtimeSyncCoordinator,
    private val contactsController: ContactsController,
    private val onFriendRequestsNeedsRefresh: () -> Unit,
    private val onGroupInvitesNeedsRefresh: () -> Unit,
) {
    fun start() {
        val eventsFlow = com.maodouchat.session.AppRuntime
            .realtimeDispatcherOrNull(application)
            ?.allEvents
        if (eventsFlow != null) {
            realtimeSyncCoordinator.startObserving(
                scope,
                eventsFlow
            ) {
                val snap = com.maodouchat.session.CurrentSession.snapshot()
                Pair(snap.userId, snap.token)
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
