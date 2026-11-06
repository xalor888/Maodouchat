package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateModelsTest {

    @Test
    fun `settings ui state defaults to visible online and public posts`() {
        val state = SettingsUiState()
        assertEquals("", state.userName)
        assertTrue(state.showOnline)
        assertEquals("everyone", state.onlineVisibility)
        assertEquals("PUBLIC", state.defaultPostVisibility)
        assertTrue(state.blockedUsers.isEmpty())
        assertTrue(state.devices.isEmpty())
        assertFalse(state.isLoggedOut)
    }

    @Test
    fun `loaded privacy round-trips through settings ui state`() {
        val privacy = LoadedPrivacy(
            ownerUserId = "u1",
            showOnline = false,
            showStatus = true,
            searchable = false,
            defaultPostVisibility = "FRIENDS",
            onlineVisibility = "friends"
        )
        assertEquals("u1", privacy.ownerUserId)
        assertFalse(privacy.showOnline)
        assertEquals(PrivacyField.SHOW_ONLINE, PrivacyField.valueOf("SHOW_ONLINE"))
    }

    @Test
    fun `author profile ui state defaults to loading more`() {
        val state = AuthorProfileUiState()
        assertEquals("", state.currentUserId)
        assertTrue(state.posts.isEmpty())
        assertFalse(state.isLoading)
        assertTrue(state.hasMore)
        assertFalse(state.isBlocked)
    }
}
