package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.AttachMenuKind
import com.maodouchat.ui.screen.chatdetail.AttachMenuPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachMenuPolicyTest {

    @Test
    fun dmIncludesViewOnceLiveLocationAndNudge() {
        val items = AttachMenuPolicy.items(
            isGroup = false,
            isChannel = false,
            viewOnceEnabled = true,
            contactCardEnabled = true,
            nudgeEnabled = true,
        )
        assertTrue(AttachMenuKind.VIEW_ONCE in items)
        assertTrue(AttachMenuKind.LIVE_LOCATION in items)
        assertFalse(AttachMenuKind.NUDGE in items)
        assertTrue(AttachMenuKind.IMAGE in items)
        assertTrue(AttachMenuKind.CONTACT_CARD in items)
        assertFalse(AttachMenuKind.AI in items)
    }

    @Test
    fun aiTileOnlyWhenEnabled() {
        val hidden = AttachMenuPolicy.items(
            isGroup = false,
            isChannel = false,
            viewOnceEnabled = true,
            contactCardEnabled = true,
            nudgeEnabled = true,
            aiEnabled = false,
        )
        val shown = AttachMenuPolicy.items(
            isGroup = false,
            isChannel = false,
            viewOnceEnabled = true,
            contactCardEnabled = true,
            nudgeEnabled = true,
            aiEnabled = true,
        )
        assertFalse(AttachMenuKind.AI in hidden)
        assertTrue(AttachMenuKind.AI in shown)
    }

    @Test
    fun groupHidesOneToOneOnlyActions() {
        val items = AttachMenuPolicy.items(
            isGroup = true,
            isChannel = false,
            viewOnceEnabled = true,
            contactCardEnabled = true,
            nudgeEnabled = true,
        )
        assertFalse(AttachMenuKind.VIEW_ONCE in items)
        assertFalse(AttachMenuKind.LIVE_LOCATION in items)
        assertFalse(AttachMenuKind.NUDGE in items)
        assertTrue(AttachMenuKind.IMAGE in items)
        assertTrue(AttachMenuKind.FILE in items)
        assertTrue(AttachMenuKind.LOCATION in items)
        assertTrue(AttachMenuKind.IMAGE in items)
        assertEquals(1, items.count { it == AttachMenuKind.IMAGE })
        assertFalse(AttachMenuKind.AI in items)
    }

    @Test
    fun channelOwnerMenuIsMediaFirst() {
        val items = AttachMenuPolicy.items(
            isGroup = true,
            isChannel = true,
            viewOnceEnabled = true,
            contactCardEnabled = true,
            nudgeEnabled = true,
        )
        assertFalse(AttachMenuKind.VIEW_ONCE in items)
        assertFalse(AttachMenuKind.VOICE in items)
        assertFalse(AttachMenuKind.LIVE_LOCATION in items)
        assertFalse(AttachMenuKind.NUDGE in items)
        assertFalse(AttachMenuKind.CONTACT_CARD in items)
        assertTrue(AttachMenuKind.IMAGE in items)
        assertTrue(AttachMenuKind.VIDEO in items)
        assertTrue(AttachMenuKind.FILE in items)
        assertFalse(AttachMenuKind.AI in items)
    }
}
