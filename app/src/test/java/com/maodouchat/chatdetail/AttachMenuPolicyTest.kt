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

    @Test
    fun channelKeepsMediaFirstOrder() {
        val items = AttachMenuPolicy.items(
            isGroup = true,
            isChannel = true,
            viewOnceEnabled = true,
            contactCardEnabled = true,
            nudgeEnabled = true,
        )
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.SILENT,
            ),
            items
        )
    }

    @Test
    fun channelAiSitsBetweenScheduleAndSilent() {
        val items = AttachMenuPolicy.items(
            isGroup = true,
            isChannel = true,
            viewOnceEnabled = true,
            contactCardEnabled = true,
            nudgeEnabled = true,
            aiEnabled = true,
        )
        assertEquals(AttachMenuKind.AI, items[items.size - 2])
        assertEquals(AttachMenuKind.SILENT, items.last())
    }

    @Test
    fun dmWithoutContactCardDropsOnlyThatTile() {
        val items = AttachMenuPolicy.items(
            isGroup = false,
            isChannel = false,
            viewOnceEnabled = true,
            contactCardEnabled = false,
            nudgeEnabled = true,
        )
        assertFalse(AttachMenuKind.CONTACT_CARD in items)
        assertTrue(AttachMenuKind.IMAGE in items)
        assertTrue(AttachMenuKind.VIEW_ONCE in items)
    }

    @Test
    fun dmWithoutViewOnceKeepsLiveLocation() {
        val items = AttachMenuPolicy.items(
            isGroup = false,
            isChannel = false,
            viewOnceEnabled = false,
            contactCardEnabled = true,
            nudgeEnabled = true,
        )
        assertFalse(AttachMenuKind.VIEW_ONCE in items)
        assertTrue(AttachMenuKind.LIVE_LOCATION in items)
        assertTrue(AttachMenuKind.IMAGE in items)
    }

    @Test
    fun noDuplicateTilesInAnyMode() {
        listOf(
            AttachMenuPolicy.items(false, false, true, true, true, aiEnabled = true),
            AttachMenuPolicy.items(true, false, true, true, true, aiEnabled = true),
            AttachMenuPolicy.items(true, true, true, true, true, aiEnabled = true),
        ).forEach { items ->
            assertEquals(items.distinct().size, items.size)
        }
    }

    @Test
    fun dmMenuOrderIsMediaFirstExact() {
        val items = AttachMenuPolicy.items(
            isGroup = false,
            isChannel = false,
            viewOnceEnabled = true,
            contactCardEnabled = true,
            nudgeEnabled = true,
        )
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.VIEW_ONCE,
                AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION,
                AttachMenuKind.LIVE_LOCATION,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.CONTACT_CARD,
                AttachMenuKind.SILENT,
            ),
            items
        )
    }

    @Test
    fun groupMenuOrderIsMediaFirstExact() {
        val items = AttachMenuPolicy.items(
            isGroup = true,
            isChannel = false,
            viewOnceEnabled = true,
            contactCardEnabled = true,
            nudgeEnabled = true,
        )
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.CONTACT_CARD,
                AttachMenuKind.SILENT,
            ),
            items
        )
    }

    @Test
    fun groupWithAiAiSitsBetweenContactCardAndSilent() {
        val items = AttachMenuPolicy.items(
            isGroup = true,
            isChannel = false,
            viewOnceEnabled = true,
            contactCardEnabled = true,
            nudgeEnabled = true,
            aiEnabled = true,
        )
        assertEquals(AttachMenuKind.CONTACT_CARD, items[items.size - 3])
        assertEquals(AttachMenuKind.AI, items[items.size - 2])
        assertEquals(AttachMenuKind.SILENT, items.last())
    }

    @Test
    fun channelWithoutAiHasSixTilesEndingSilent() {
        val items = AttachMenuPolicy.items(
            isGroup = true,
            isChannel = true,
            viewOnceEnabled = true,
            contactCardEnabled = true,
            nudgeEnabled = true,
        )
        assertEquals(6, items.size)
        assertFalse(AttachMenuKind.AI in items)
        assertEquals(AttachMenuKind.SILENT, items.last())
    }

    @Test
    fun silentIsLastAcrossAllModes() {
        listOf(
            AttachMenuPolicy.items(false, false, true, true, true, aiEnabled = true),
            AttachMenuPolicy.items(true, false, true, true, true),
            AttachMenuPolicy.items(true, true, true, true, true),
            AttachMenuPolicy.items(false, false, false, false, false),
        ).forEach { items ->
            assertEquals(AttachMenuKind.SILENT, items.last())
        }
    }

    @Test
    fun channelIgnoresGroupAndFeatureFlagsExceptAi() {
        val nonGroup = AttachMenuPolicy.items(false, true, true, true, true)
        val group = AttachMenuPolicy.items(true, true, true, true, true)
        assertEquals(group, nonGroup)
        val nonGroupAi = AttachMenuPolicy.items(false, true, true, true, true, aiEnabled = true)
        val groupAi = AttachMenuPolicy.items(true, true, true, true, true, aiEnabled = true)
        assertEquals(groupAi, nonGroupAi)
        assertTrue(AttachMenuKind.AI in groupAi)
    }

    @Test
    fun dmAllFlagsOffExactList() {
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION,
                AttachMenuKind.LIVE_LOCATION,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.SILENT,
            ),
            AttachMenuPolicy.items(false, false, false, false, false),
        )
    }

    @Test
    fun nudgeFlagNeverAddsNudgeTile() {
        listOf(
            AttachMenuPolicy.items(false, false, true, true, false) to
                AttachMenuPolicy.items(false, false, true, true, true),
            AttachMenuPolicy.items(true, false, true, true, false) to
                AttachMenuPolicy.items(true, false, true, true, true),
            AttachMenuPolicy.items(true, true, true, true, false) to
                AttachMenuPolicy.items(true, true, true, true, true),
        ).forEach { (off, on) ->
            assertEquals(off, on)
            assertFalse(AttachMenuKind.NUDGE in on)
        }
    }

    @Test
    fun channelWithAiExactListIgnoresContactAndViewOnce() {
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.AI,
                AttachMenuKind.SILENT,
            ),
            AttachMenuPolicy.items(true, true, true, true, true, aiEnabled = true),
        )
    }

    @Test
    fun dmAiWithoutContactCardSitsBeforeSilent() {
        val items = AttachMenuPolicy.items(
            isGroup = false,
            isChannel = false,
            viewOnceEnabled = true,
            contactCardEnabled = false,
            nudgeEnabled = true,
            aiEnabled = true,
        )
        assertFalse(AttachMenuKind.CONTACT_CARD in items)
        assertEquals(AttachMenuKind.AI, items[items.size - 2])
        assertEquals(AttachMenuKind.SILENT, items.last())
    }

    @Test
    fun groupWithoutViewOnceStillKeepsSpoilerAndVoice() {
        val items = AttachMenuPolicy.items(
            isGroup = true,
            isChannel = false,
            viewOnceEnabled = false,
            contactCardEnabled = true,
            nudgeEnabled = true,
        )
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.CONTACT_CARD,
                AttachMenuKind.SILENT,
            ),
            items
        )
    }

    @Test
    fun dmWithoutViewOnceAndContactCardExactList() {
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION,
                AttachMenuKind.LIVE_LOCATION,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.SILENT,
            ),
            AttachMenuPolicy.items(false, false, false, false, false),
        )
    }

    @Test
    fun dmAiSitsBetweenContactCardAndSilent() {
        val items = AttachMenuPolicy.items(
            isGroup = false,
            isChannel = false,
            viewOnceEnabled = true,
            contactCardEnabled = true,
            nudgeEnabled = true,
            aiEnabled = true,
        )
        assertEquals(14, items.size)
        assertEquals(AttachMenuKind.CONTACT_CARD, items[items.size - 3])
        assertEquals(AttachMenuKind.AI, items[items.size - 2])
        assertEquals(AttachMenuKind.SILENT, items.last())
    }

    @Test
    fun channelIgnoresViewOnceAndContactCardFlags() {
        val flagsOn = AttachMenuPolicy.items(true, true, true, true, true)
        val flagsOff = AttachMenuPolicy.items(true, true, false, false, false)
        assertEquals(flagsOn, flagsOff)
        assertEquals(6, flagsOff.size)
    }

    @Test
    fun viewOnceOnlyAppearsInDmWhenEnabled() {
        assertTrue(AttachMenuKind.VIEW_ONCE in AttachMenuPolicy.items(false, false, true, true, true))
        assertFalse(AttachMenuKind.VIEW_ONCE in AttachMenuPolicy.items(false, false, false, true, true))
        assertFalse(AttachMenuKind.VIEW_ONCE in AttachMenuPolicy.items(true, false, true, true, true))
        assertFalse(AttachMenuKind.VIEW_ONCE in AttachMenuPolicy.items(true, true, true, true, true))
    }

    @Test
    fun dmAiExactList() {
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.VIEW_ONCE,
                AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION,
                AttachMenuKind.LIVE_LOCATION,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.CONTACT_CARD,
                AttachMenuKind.AI,
                AttachMenuKind.SILENT,
            ),
            AttachMenuPolicy.items(false, false, true, true, true, aiEnabled = true),
        )
    }

    @Test
    fun groupWithoutContactCardExactList() {
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.SILENT,
            ),
            AttachMenuPolicy.items(true, false, true, false, true),
        )
    }

    @Test
    fun dmAiWithoutContactCardExactList() {
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.VIEW_ONCE,
                AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION,
                AttachMenuKind.LIVE_LOCATION,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.AI,
                AttachMenuKind.SILENT,
            ),
            AttachMenuPolicy.items(false, false, true, false, true, aiEnabled = true),
        )
    }

    @Test
    fun channelIgnoresNudgeFlag() {
        val off = AttachMenuPolicy.items(true, true, true, true, false)
        val on = AttachMenuPolicy.items(true, true, true, true, true)
        assertEquals(off, on)
        assertEquals(6, on.size)
        assertFalse(AttachMenuKind.NUDGE in on)
    }

    @Test
    fun baseTilesPresentInEveryMode() {
        val base = listOf(
            AttachMenuKind.IMAGE,
            AttachMenuKind.PASTE,
            AttachMenuKind.VIDEO,
            AttachMenuKind.FILE,
            AttachMenuKind.SCHEDULE,
            AttachMenuKind.SILENT,
        )
        listOf(
            AttachMenuPolicy.items(false, false, true, true, true),
            AttachMenuPolicy.items(true, false, true, true, true),
            AttachMenuPolicy.items(true, true, true, true, true),
        ).forEach { items ->
            base.forEach { assertTrue(it in items) }
        }
    }

    @Test
    fun groupWithAiExactList() {
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.CONTACT_CARD,
                AttachMenuKind.AI,
                AttachMenuKind.SILENT,
            ),
            AttachMenuPolicy.items(true, false, true, true, true, aiEnabled = true),
        )
    }

    @Test
    fun groupAllFlagsOffExactList() {
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.SILENT,
            ),
            AttachMenuPolicy.items(true, false, false, false, false),
        )
    }

    @Test
    fun dmViewOnceOffWithAiAndContactCardExactList() {
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION,
                AttachMenuKind.LIVE_LOCATION,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.CONTACT_CARD,
                AttachMenuKind.AI,
                AttachMenuKind.SILENT,
            ),
            AttachMenuPolicy.items(false, false, false, true, true, aiEnabled = true),
        )
    }

    @Test
    fun groupViewOnceOffAiWithoutContactCardExactList() {
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.AI,
                AttachMenuKind.SILENT,
            ),
            AttachMenuPolicy.items(true, false, false, false, true, aiEnabled = true),
        )
    }

    @Test
    fun channelLacksNonMediaTiles() {
        val items = AttachMenuPolicy.items(true, true, true, true, true)
        assertFalse(AttachMenuKind.SPOILER in items)
        assertFalse(AttachMenuKind.VOICE in items)
        assertFalse(AttachMenuKind.LOCATION in items)
        assertFalse(AttachMenuKind.QUICK_PHRASES in items)
        assertTrue(AttachMenuKind.PASTE in items)
        assertTrue(AttachMenuKind.SCHEDULE in items)
    }

}
