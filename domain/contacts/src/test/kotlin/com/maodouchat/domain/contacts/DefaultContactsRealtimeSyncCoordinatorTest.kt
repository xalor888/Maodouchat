package com.maodouchat.domain.contacts

import com.maodouchat.core.realtime.RealtimeDomainEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultContactsRealtimeSyncCoordinatorTest {

    private data class VisibilityCall(
        val userId: String,
        val isOnline: Boolean,
        val onlineRevoked: Boolean,
        val statusRevoked: Boolean,
        val updatedAt: Long,
    )

    private class Harness(
        var gate: Boolean = true,
        var now: Long = 12345L,
    ) {
        val visibilityCalls = mutableListOf<VisibilityCall>()
        val cachedFriends = mutableListOf<Pair<String, String>>()
        val notifications = mutableListOf<Tuple4<String, String, String, String?>>()
        val emitted = mutableListOf<ContactSyncEvent>()

        val coordinator = DefaultContactsRealtimeSyncCoordinator(
            applyRealtimeVisibility = { userId, isOnline, onlineRevoked, statusRevoked, updatedAt ->
                visibilityCalls += VisibilityCall(userId, isOnline, onlineRevoked, statusRevoked, updatedAt)
            },
            addFriendToCache = { friendId, ownerUserId -> cachedFriends += friendId to ownerUserId },
            sessionMayContinue = { _, _, _ -> gate },
            nowMs = { now },
            onNotificationCenterItem = { id, title, subtitle, message ->
                notifications += Tuple4(id, title, subtitle, message)
            },
        )

        fun collectEvents(scope: CoroutineScope) = scope.launch { coordinator.syncEvents.collect { emitted += it } }
    }

    private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    @Test
    fun presence_writesVisibilityWithInjectedClock() = runTest {
        val harness = Harness()
        harness.coordinator.handleEvent(
            RealtimeDomainEvent.Presence(
                userId = "bob",
                isOnline = true,
                onlineRevoked = false,
                statusRevoked = false,
                lastSeen = 12345678L
            ),
            "alice",
            "token-alice"
        )
        assertEquals(
            listOf(VisibilityCall("bob", true, false, false, 12345L)),
            harness.visibilityCalls
        )
    }

    @Test
    fun friendAccepted_emitsRefreshAndAccepted_andCaches() = runTest {
        val harness = Harness()
        harness.collectEvents(backgroundScope)
        // 先让收集器真正订阅：replay=0 的 SharedFlow 会丢掉订阅前发出的事件。
        runCurrent()
        harness.coordinator.handleEvent(
            RealtimeDomainEvent.SocialUpdate(
                kind = "friend_request",
                targetId = "req-1",
                action = "ACCEPTED",
                fromUserId = "bob",
                fromUserName = "Bob",
                toUserId = "alice"
            ),
            "alice",
            "token-alice"
        )
        runCurrent()
        assertTrue(harness.emitted.any { it is ContactSyncEvent.FriendRequestsNeedsRefresh })
        assertTrue(harness.emitted.any { it is ContactSyncEvent.FriendAccepted && it.friendId == "bob" })
        assertEquals(listOf("bob" to "alice"), harness.cachedFriends)
    }

    @Test
    fun friendAccepted_fromOwner_resolvesFriendIdFromTarget() = runTest {
        val harness = Harness()
        harness.collectEvents(backgroundScope)
        // 先让收集器真正订阅：replay=0 的 SharedFlow 会丢掉订阅前发出的事件。
        runCurrent()
        harness.coordinator.handleEvent(
            RealtimeDomainEvent.SocialUpdate(
                kind = "friend_request",
                targetId = "req-2",
                action = "ACCEPTED",
                fromUserId = "alice",
                fromUserName = "Alice",
                toUserId = "bob"
            ),
            "alice",
            "token-alice"
        )
        runCurrent()
        assertTrue(harness.emitted.any { it is ContactSyncEvent.FriendAccepted && it.friendId == "bob" })
        assertEquals(listOf("bob" to "alice"), harness.cachedFriends)
    }

    @Test
    fun friendRequestCreated_incoming_triggersNotification() = runTest {
        val harness = Harness()
        harness.coordinator.handleEvent(
            RealtimeDomainEvent.SocialUpdate(
                kind = "friend_request",
                targetId = "req-99",
                action = "CREATED",
                fromUserId = "charlie",
                fromUserName = "Charlie",
                toUserId = "alice",
                message = "Add me"
            ),
            "alice",
            "token-alice"
        )
        assertEquals(
            listOf(Tuple4("friend_req-99", "好友申请", "Charlie", "Add me")),
            harness.notifications
        )
    }

    @Test
    fun emptyOwnerOrToken_dropsEvent() = runTest {
        val harness = Harness()
        harness.collectEvents(backgroundScope)
        val presence = RealtimeDomainEvent.Presence(userId = "bob", isOnline = true)
        harness.coordinator.handleEvent(presence, "", "token-alice")
        harness.coordinator.handleEvent(presence, "alice", "")
        runCurrent()
        assertTrue(harness.visibilityCalls.isEmpty())
        assertTrue(harness.emitted.isEmpty())
    }

    @Test
    fun sessionGateRejected_dropsEvent() = runTest {
        val harness = Harness(gate = false)
        harness.collectEvents(backgroundScope)
        harness.coordinator.handleEvent(
            RealtimeDomainEvent.Presence(userId = "bob", isOnline = true),
            "alice",
            "token-alice"
        )
        harness.coordinator.handleEvent(
            RealtimeDomainEvent.SocialUpdate(
                kind = "friend_request",
                targetId = "req-3",
                action = "ACCEPTED",
                fromUserId = "bob",
                toUserId = "alice"
            ),
            "alice",
            "token-alice"
        )
        runCurrent()
        assertTrue(harness.visibilityCalls.isEmpty())
        assertTrue(harness.emitted.isEmpty())
        assertTrue(harness.cachedFriends.isEmpty())
    }

    @Test
    fun groupInviteCreated_incoming_triggersNotificationAndRefresh() = runTest {
        val harness = Harness()
        harness.collectEvents(backgroundScope)
        // 先让收集器真正订阅：replay=0 的 SharedFlow 会丢掉订阅前发出的事件。
        runCurrent()
        harness.coordinator.handleEvent(
            RealtimeDomainEvent.GroupInvite(
                action = "CREATED",
                inviteId = "inv-7",
                chatId = "chat-1",
                inviterId = "dave",
                inviteeId = "alice",
                inviterName = "Dave",
                chatName = "Weekend"
            ),
            "alice",
            "token-alice"
        )
        runCurrent()
        assertTrue(harness.emitted.any { it is ContactSyncEvent.GroupInvitesNeedsRefresh })
        assertEquals(
            listOf(Tuple4("group_invite_inv-7", "群邀请", "Dave", "Weekend")),
            harness.notifications
        )
    }

    @Test
    fun socialUpdate_otherKind_ignored() = runTest {
        val harness = Harness()
        harness.collectEvents(backgroundScope)
        harness.coordinator.handleEvent(
            RealtimeDomainEvent.SocialUpdate(
                kind = "post_liked",
                targetId = "post-1",
                action = "CREATED",
                fromUserId = "bob",
                toUserId = "alice"
            ),
            "alice",
            "token-alice"
        )
        runCurrent()
        assertTrue(harness.emitted.isEmpty())
        assertTrue(harness.cachedFriends.isEmpty())
        assertTrue(harness.notifications.isEmpty())
    }

    @Test
    fun startObserving_routesEventsOnlyWithLiveSession() = runBlocking {
        val harness = Harness()
        var session: Pair<String?, String?> = Pair(null, null)
        val events = MutableSharedFlow<RealtimeDomainEvent>(extraBufferCapacity = 16)
        val job = harness.coordinator.startObserving(this, events) { session }
        try {
            events.emit(RealtimeDomainEvent.Presence(userId = "bob", isOnline = true))
            Thread.sleep(300)
            assertTrue(harness.visibilityCalls.isEmpty())

            session = Pair("alice", "token-alice")
            events.emit(RealtimeDomainEvent.Presence(userId = "bob", isOnline = true))
            val deadline = System.currentTimeMillis() + 2000
            while (harness.visibilityCalls.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(1, harness.visibilityCalls.size)
            assertEquals("bob", harness.visibilityCalls.single().userId)
        } finally {
            job.cancel()
        }
    }
}
