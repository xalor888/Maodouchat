package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeSlotKindTest {

    @Test
    fun accentSlot() {
        assertEquals(ThemeSlotKind.ACCENT, themeSlotKind("accent"))
    }

    @Test
    fun chatBackgroundSlot() {
        assertEquals(ThemeSlotKind.CHAT_BACKGROUND, themeSlotKind("chat_background"))
    }

    @Test
    fun chatInBubbleSlot() {
        assertEquals(ThemeSlotKind.CHAT_IN_BUBBLE, themeSlotKind("chat_inBubble"))
    }

    @Test
    fun chatInTextSlot() {
        assertEquals(ThemeSlotKind.CHAT_IN_TEXT, themeSlotKind("chat_inText"))
    }

    @Test
    fun chatOutBubbleSlot() {
        assertEquals(ThemeSlotKind.CHAT_OUT_BUBBLE, themeSlotKind("chat_outBubble"))
    }

    @Test
    fun chatOutTextSlot() {
        assertEquals(ThemeSlotKind.CHAT_OUT_TEXT, themeSlotKind("chat_outText"))
    }

    @Test
    fun textPrimarySlot() {
        assertEquals(ThemeSlotKind.TEXT_PRIMARY, themeSlotKind("text_primary"))
    }

    @Test
    fun inputBackgroundSlot() {
        assertEquals(ThemeSlotKind.INPUT_BACKGROUND, themeSlotKind("input_background"))
    }

    @Test
    fun unreadBadgeSlot() {
        assertEquals(ThemeSlotKind.UNREAD_BADGE, themeSlotKind("unread_badge"))
    }

    @Test
    fun systemMessageSlot() {
        assertEquals(ThemeSlotKind.SYSTEM_MESSAGE, themeSlotKind("system_message"))
    }

    @Test
    fun windowBackgroundSlot() {
        assertEquals(ThemeSlotKind.WINDOW_BACKGROUND, themeSlotKind("window_background"))
    }

    @Test
    fun unknownSlotFallsBackToWindowBackground() {
        assertEquals(ThemeSlotKind.WINDOW_BACKGROUND, themeSlotKind("nope"))
        assertEquals(ThemeSlotKind.WINDOW_BACKGROUND, themeSlotKind(""))
    }
}
