package com.maodouchat.domain.conversation

import kotlin.test.assertEquals
import kotlin.test.Test

class ChatSettingsToggleTest {

    @Test
    fun `all four toggles exist in order`() {
        assertEquals(
            listOf(
                ChatSettingsToggle.PIN,
                ChatSettingsToggle.MUTE,
                ChatSettingsToggle.ARCHIVE,
                ChatSettingsToggle.MARKED_UNREAD,
            ),
            ChatSettingsToggle.entries.toList(),
        )
        assertEquals(ChatSettingsToggle.ARCHIVE, ChatSettingsToggle.valueOf("ARCHIVE"))
    }
}
