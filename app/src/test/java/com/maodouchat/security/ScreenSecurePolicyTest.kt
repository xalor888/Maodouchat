package com.maodouchat.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSecurePolicyTest {
    @Test
    fun `app lock always secures`() {
        assertTrue(
            ScreenSecurePolicy.shouldSecureWindow(
                appLockShowing = true,
                globalEnabled = false,
                onChatSurface = false
            )
        )
    }

    @Test
    fun `global on only on chat surface`() {
        assertTrue(
            ScreenSecurePolicy.shouldSecureWindow(
                appLockShowing = false,
                globalEnabled = true,
                onChatSurface = true
            )
        )
        assertFalse(
            ScreenSecurePolicy.shouldSecureWindow(
                appLockShowing = false,
                globalEnabled = true,
                onChatSurface = false
            )
        )
        assertFalse(
            ScreenSecurePolicy.shouldSecureWindow(
                appLockShowing = false,
                globalEnabled = false,
                onChatSurface = true
            )
        )
    }

    @Test
    fun `secret chat surface forces secure even when global off`() {
        assertTrue(
            ScreenSecurePolicy.shouldSecureWindow(
                appLockShowing = false,
                globalEnabled = false,
                onChatSurface = true,
                secretChatSurfaceActive = true
            )
        )
        assertTrue(
            ScreenSecurePolicy.shouldSecureWindow(
                appLockShowing = false,
                globalEnabled = false,
                onChatSurface = false,
                secretChatSurfaceActive = true
            )
        )
    }

    @Test
    fun `chat surface route detection`() {
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("chat_detail/abc"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("chat_detail/abc?messageId=m1"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("media_center/x"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("group_detail/g1"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("starred_messages/c1"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("ai_tasks/c1"))
        assertFalse(ScreenSecurePolicy.isChatSurfaceRoute("main"))
        assertFalse(ScreenSecurePolicy.isChatSurfaceRoute("settings/account_security"))
        assertFalse(ScreenSecurePolicy.isChatSurfaceRoute(null))
    }

    @Test
    fun `extract chat id from surface routes`() {
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/abc"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/abc?messageId=m1"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("media_center/x%20y"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("group_detail/g1"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("main"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute(null))
    }
}
