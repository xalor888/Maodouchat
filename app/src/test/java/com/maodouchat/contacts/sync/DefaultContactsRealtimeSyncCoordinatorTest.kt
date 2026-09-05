package com.maodouchat.contacts.sync

import android.content.Context
import android.content.SharedPreferences
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.data.local.dao.UserDao
import com.maodouchat.data.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class DefaultContactsRealtimeSyncCoordinatorTest {

    private val context = mockk<Context>(relaxed = true)
    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val userDao = mockk<UserDao>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)

    @Before
    fun setUp() {
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putStringSet(any(), any()) } returns editor
        every { prefs.getStringSet(any(), any()) } returns emptySet()
    }

    @Test
    fun handlePresenceEvent_writesDirectlyToRoom() = runBlocking {
        val coordinator = DefaultContactsRealtimeSyncCoordinator(
            context = context,
            userDao = userDao,
            userRepository = userRepository
        )

        val presenceEvent = RealtimeDomainEvent.Presence(
            userId = "bob",
            isOnline = true,
            onlineRevoked = false,
            statusRevoked = false,
            lastSeen = 12345678L
        )

        coordinator.handleEvent(presenceEvent, "alice", "token-alice")

        coVerify(exactly = 1) {
            userDao.applyRealtimeVisibility(
                userId = "bob",
                isOnline = true,
                onlineRevoked = false,
                statusRevoked = false,
                updatedAt = any()
            )
        }
    }

    @Test
    fun handleSocialUpdate_friendAccepted_emitsSyncEventAndUpdatesCache() = runBlocking {
        val coordinator = DefaultContactsRealtimeSyncCoordinator(
            context = context,
            userDao = userDao,
            userRepository = userRepository
        )

        val socialEvent = RealtimeDomainEvent.SocialUpdate(
            kind = "friend_request",
            targetId = "req-1",
            action = "ACCEPTED",
            fromUserId = "bob",
            fromUserName = "Bob",
            toUserId = "alice"
        )

        val emittedEvents = mutableListOf<ContactSyncEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            coordinator.syncEvents.collect { emittedEvents.add(it) }
        }

        coordinator.handleEvent(socialEvent, "alice", "token-alice")

        assertTrue("Should emit FriendRequestsNeedsRefresh", emittedEvents.any { it is ContactSyncEvent.FriendRequestsNeedsRefresh })
        assertTrue("Should emit FriendAccepted", emittedEvents.any { it is ContactSyncEvent.FriendAccepted && it.friendId == "bob" })

        job.cancel()
    }

    @Test
    fun handleSocialUpdate_createdIncoming_triggersNotificationCenter() = runBlocking {
        val notificationTriggered = AtomicBoolean(false)
        val coordinator = DefaultContactsRealtimeSyncCoordinator(
            context = context,
            userDao = userDao,
            userRepository = userRepository,
            onNotificationCenterItem = { id, title, subtitle, msg ->
                if (id == "friend_req-99" && subtitle == "Charlie") {
                    notificationTriggered.set(true)
                }
            }
        )

        val socialEvent = RealtimeDomainEvent.SocialUpdate(
            kind = "friend_request",
            targetId = "req-99",
            action = "CREATED",
            fromUserId = "charlie",
            fromUserName = "Charlie",
            toUserId = "alice",
            message = "Add me"
        )

        coordinator.handleEvent(socialEvent, "alice", "token-alice")
        assertTrue(notificationTriggered.get())
    }

    @Test
    fun handleEvent_emptyTokenOrOwner_dropsEvent() = runBlocking {
        val coordinator = DefaultContactsRealtimeSyncCoordinator(
            context = context,
            userDao = userDao,
            userRepository = userRepository
        )

        val presenceEvent = RealtimeDomainEvent.Presence(
            userId = "bob",
            isOnline = true
        )

        coordinator.handleEvent(presenceEvent, "", "token-alice")
        coordinator.handleEvent(presenceEvent, "alice", "")

        coVerify(exactly = 0) {
            userDao.applyRealtimeVisibility(any(), any(), any(), any(), any())
        }
    }
}
