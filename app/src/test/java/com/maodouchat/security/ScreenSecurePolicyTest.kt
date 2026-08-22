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
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("chat_detail_two_pane/abc"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("chat_detail_list_pane"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("media_center/x"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("group_detail/g1"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("starred_messages/c1"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("starred_messages?chatId=c1"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("ai_tasks/c1"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("incoming_call"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("call/u1/Alice/AUDIO"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("group_poll/g1"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("group_checkin/g1"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("group_chain/g1"))
        assertTrue(ScreenSecurePolicy.isChatSurfaceRoute("group_pk/g1"))
        assertFalse(ScreenSecurePolicy.isChatSurfaceRoute("main"))
        assertFalse(ScreenSecurePolicy.isChatSurfaceRoute("settings/account_security"))
        assertFalse(ScreenSecurePolicy.isChatSurfaceRoute("call_history"))
        assertFalse(ScreenSecurePolicy.isChatSurfaceRoute(null))
    }

    @Test
    fun `extract chat id from surface routes`() {
        assertEquals("abc", ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/abc"))
        assertEquals("abc", ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/abc?messageId=m1"))
        assertEquals("abc", ScreenSecurePolicy.extractChatIdFromRoute("chat_detail_two_pane/abc"))
        assertEquals("x y", ScreenSecurePolicy.extractChatIdFromRoute("media_center/x%20y"))
        assertEquals("g1", ScreenSecurePolicy.extractChatIdFromRoute("group_detail/g1"))
        assertEquals("c1", ScreenSecurePolicy.extractChatIdFromRoute("starred_messages/c1"))
        assertEquals("c1", ScreenSecurePolicy.extractChatIdFromRoute("starred_messages?chatId=c1"))
        assertEquals("c2", ScreenSecurePolicy.extractChatIdFromRoute("ai_tasks/c2"))
        assertEquals("g1", ScreenSecurePolicy.extractChatIdFromRoute("group_poll/g1"))
        assertEquals("g1", ScreenSecurePolicy.extractChatIdFromRoute("group_checkin/g1"))
        assertEquals("g1", ScreenSecurePolicy.extractChatIdFromRoute("group_chain/g1"))
        assertEquals("g1", ScreenSecurePolicy.extractChatIdFromRoute("group_pk/g1"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("starred_messages"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("incoming_call"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("call/u1/Alice/AUDIO"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("main"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute(null))
    }
}
