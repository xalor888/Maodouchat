package com.maodouchat.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.maodouchat.navigation.AppLinkAuthGate
import com.maodouchat.navigation.AppLinkDestination

class AppLinkAuthGateTest {

    @Test
    fun proceedsWhenLoggedIn() {
        val dest = AppLinkDestination.ChatDetail("c1")
        val decision = AppLinkAuthGate.evaluate(dest, isLoggedIn = true)
        assertIs<AppLinkAuthGate.Decision.Proceed>(decision)
        assertEquals(dest, decision.destination)
    }

    @Test
    fun requiresLoginWhenLoggedOutAndAuthRequired() {
        val dest = AppLinkDestination.GroupInvite("A".repeat(40))
        val decision = AppLinkAuthGate.evaluate(dest, isLoggedIn = false)
        assertIs<AppLinkAuthGate.Decision.RequireLogin>(decision)
        assertEquals(dest, decision.pending)
    }

    @Test
    fun allAuthRequiredDestinationsRequireLoginWhenLoggedOut() {
        val samples = listOf(
            AppLinkDestination.ChatDetail("c1"),
            AppLinkDestination.PublicProfile("alice"),
            AppLinkDestination.PostDetail("p1"),
            AppLinkDestination.AiTasksChat("c1"),
            AppLinkDestination.GroupInvite("tok"),
            AppLinkDestination.NotificationCenter,
            AppLinkDestination.CallHistory,
            AppLinkDestination.ContactsTab,
            AppLinkDestination.MissedCallsTab,
            AppLinkDestination.GroupInvitesTab,
        )
        samples.forEach { dest ->
            assertEquals(true, dest.requiresAuth)
            assertIs<AppLinkAuthGate.Decision.RequireLogin>(
                AppLinkAuthGate.evaluate(dest, isLoggedIn = false)
            )
        }
    }

    @Test
    fun externalUrlProceedsWithoutLogin() {
        val dest = AppLinkDestination.ExternalUrl("https://example.com/path")
        assertEquals(false, dest.requiresAuth)
        val decision = AppLinkAuthGate.evaluate(dest, isLoggedIn = false)
        assertIs<AppLinkAuthGate.Decision.Proceed>(decision)
        assertEquals(dest, decision.destination)
    }
}
