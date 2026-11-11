package com.maodouchat.domain.contacts

import com.maodouchat.core.realtime.RealtimeDomainEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow

/** 实时同步协调领域事件 */
sealed class ContactSyncEvent {
    object FriendRequestsNeedsRefresh : ContactSyncEvent()
    object GroupInvitesNeedsRefresh : ContactSyncEvent()
    data class FriendAccepted(val friendId: String, val friendName: String?) : ContactSyncEvent()
    data class FriendRemoved(val friendId: String) : ContactSyncEvent()
}

/**
 * 通讯录与关系实时同步协调器契约
 */
interface ContactsRealtimeSyncCoordinator {
    val syncEvents: Flow<ContactSyncEvent>

    suspend fun handleEvent(event: RealtimeDomainEvent, expectedOwnerUserId: String, currentToken: String)

    fun startObserving(
        scope: CoroutineScope,
        eventsFlow: Flow<RealtimeDomainEvent>,
        sessionProvider: () -> Pair<String?, String?>
    ): Job
}
