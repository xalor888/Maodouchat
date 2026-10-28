package com.maodouchat.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.maodouchat.navigation.NotificationTarget
import com.maodouchat.navigation.NotificationTargetReplayDecision
import com.maodouchat.navigation.NotificationTargetReplayPolicy

class NotificationTargetReplayPolicyTest {

    private val loginRoute = "login"

    private fun chat(
        owner: String = "u1",
        gen: Long = 1L,
    ) = NotificationTarget.Chat("c1", gen, owner)

    @Test
    fun publicProfileAndGroupInviteBypassOwnerCheck() {
        assertTrue(
            NotificationTargetReplayPolicy.bypassesOwnerCheck(
                NotificationTarget.PublicProfile("alice", 1L, "")
            )
        )
        assertTrue(
            NotificationTargetReplayPolicy.bypassesOwnerCheck(
                NotificationTarget.GroupInvite("tok", 1L, "")
            )
        )
        assertFalse(NotificationTargetReplayPolicy.bypassesOwnerCheck(chat()))
    }

    @Test
    fun dropsWhenSessionGenerationMismatchWhileWaiting() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = chat(gen = 1L),
            isLoggedIn = false,
            currentRoute = null,
            loginRoute = loginRoute,
            liveSessionGeneration = 2L,
            liveUserId = null,
        )
        assertIs<NotificationTargetReplayDecision.Drop>(decision)
    }

    @Test
    fun waitsWhenLoggedOut() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = chat(),
            isLoggedIn = false,
            currentRoute = null,
            loginRoute = loginRoute,
            liveSessionGeneration = 1L,
            liveUserId = null,
        )
        assertIs<NotificationTargetReplayDecision.ContinueWaiting>(decision)
    }

    @Test
    fun waitsOnLoginRouteEvenWhenLoggedIn() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = chat(),
            isLoggedIn = true,
            currentRoute = loginRoute,
            loginRoute = loginRoute,
            liveSessionGeneration = 1L,
            liveUserId = "u1",
        )
        assertIs<NotificationTargetReplayDecision.ContinueWaiting>(decision)
    }

    @Test
    fun dropsWhileWaitingWhenLiveOwnerMismatches() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = chat(owner = "u1"),
            isLoggedIn = false,
            currentRoute = null,
            loginRoute = loginRoute,
            liveSessionGeneration = 1L,
            liveUserId = "u2",
        )
        assertIs<NotificationTargetReplayDecision.Drop>(decision)
    }

    @Test
    fun doesNotDropWhileWaitingWhenLiveOwnerUnknown() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = chat(owner = "u1"),
            isLoggedIn = false,
            currentRoute = null,
            loginRoute = loginRoute,
            liveSessionGeneration = 1L,
            liveUserId = null,
        )
        assertIs<NotificationTargetReplayDecision.ContinueWaiting>(decision)
    }

    @Test
    fun groupInviteWaitsDespiteEmptyOwnerWhenLoggedOut() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = NotificationTarget.GroupInvite("tok", 1L, ""),
            isLoggedIn = false,
            currentRoute = null,
            loginRoute = loginRoute,
            liveSessionGeneration = 1L,
            liveUserId = "u2",
        )
        assertIs<NotificationTargetReplayDecision.ContinueWaiting>(decision)
    }

    @Test
    fun navigatesWhenReadyAndOwnerMatches() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = chat(owner = "u1"),
            isLoggedIn = true,
            currentRoute = "main",
            loginRoute = loginRoute,
            liveSessionGeneration = 1L,
            liveUserId = "u1",
        )
        assertIs<NotificationTargetReplayDecision.Navigate>(decision)
    }

    @Test
    fun dropsWhenReadyButOwnerMismatches() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = chat(owner = "u1"),
            isLoggedIn = true,
            currentRoute = "main",
            loginRoute = loginRoute,
            liveSessionGeneration = 1L,
            liveUserId = "u2",
        )
        assertIs<NotificationTargetReplayDecision.Drop>(decision)
    }

    @Test
    fun publicProfileNavigatesWithoutOwnerMatch() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = NotificationTarget.PublicProfile("alice", 1L, ""),
            isLoggedIn = true,
            currentRoute = "main",
            loginRoute = loginRoute,
            liveSessionGeneration = 1L,
            liveUserId = "u9",
        )
        assertEquals(
            NotificationTargetReplayDecision.Navigate,
            decision,
        )
    }

    @Test
    fun generationMismatchDropsEvenWhenReadyAndOwnerMatches() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = chat(gen = 1L),
            isLoggedIn = true,
            currentRoute = "main",
            loginRoute = loginRoute,
            liveSessionGeneration = 2L,
            liveUserId = "u1",
        )
        assertIs<NotificationTargetReplayDecision.Drop>(decision)
    }

    @Test
    fun groupInviteNavigatesWhenReadyDespiteOwnerMismatch() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = NotificationTarget.GroupInvite("tok", 1L, ""),
            isLoggedIn = true,
            currentRoute = "main",
            loginRoute = loginRoute,
            liveSessionGeneration = 1L,
            liveUserId = "u2",
        )
        assertIs<NotificationTargetReplayDecision.Navigate>(decision)
    }

    @Test
    fun blankLiveUserIdWhileWaitingDoesNotDrop() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = chat(owner = "u1"),
            isLoggedIn = false,
            currentRoute = null,
            loginRoute = loginRoute,
            liveSessionGeneration = 1L,
            liveUserId = "   ",
        )
        assertIs<NotificationTargetReplayDecision.ContinueWaiting>(decision)
    }

    @Test
    fun loggedInButNullRouteStillWaits() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = chat(owner = "u1"),
            isLoggedIn = true,
            currentRoute = null,
            loginRoute = loginRoute,
            liveSessionGeneration = 1L,
            liveUserId = "u1",
        )
        assertIs<NotificationTargetReplayDecision.ContinueWaiting>(decision)
    }

    @Test
    fun readyChatWithNullLiveUserDropsOnOwnerMismatch() {
        val decision = NotificationTargetReplayPolicy.evaluate(
            target = chat(owner = "u1"),
            isLoggedIn = true,
            currentRoute = "main",
            loginRoute = loginRoute,
            liveSessionGeneration = 1L,
            liveUserId = null,
        )
        assertIs<NotificationTargetReplayDecision.Drop>(decision)
    }

}
