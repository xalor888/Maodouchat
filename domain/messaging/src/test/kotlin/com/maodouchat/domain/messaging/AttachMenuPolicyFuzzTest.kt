package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// items restated by hand: channels get the fixed media-first list
// (AI only when enabled, SILENT last); 1:1 chats add VIEW_ONCE and
// LIVE_LOCATION, groups never show either; NUDGE never lands on the
// sheet at all; the group strip at the end removes nothing new.
internal fun menuOracle171(
    isGroup: Boolean,
    isChannel: Boolean,
    viewOnceEnabled: Boolean,
    contactCardEnabled: Boolean,
    nudgeEnabled: Boolean,
    aiEnabled: Boolean
): List<AttachMenuKind> {
    if (isChannel) {
        return buildList {
            addAll(
                listOf(
                    AttachMenuKind.IMAGE,
                    AttachMenuKind.PASTE,
                    AttachMenuKind.VIDEO,
                    AttachMenuKind.FILE,
                    AttachMenuKind.SCHEDULE
                )
            )
            if (aiEnabled) add(AttachMenuKind.AI)
            add(AttachMenuKind.SILENT)
        }
    }
    val items = mutableListOf(AttachMenuKind.IMAGE)
    if (!isGroup && viewOnceEnabled) items += AttachMenuKind.VIEW_ONCE
    items += listOf(
        AttachMenuKind.SPOILER,
        AttachMenuKind.PASTE,
        AttachMenuKind.VIDEO,
        AttachMenuKind.FILE,
        AttachMenuKind.VOICE,
        AttachMenuKind.LOCATION
    )
    if (!isGroup) items += AttachMenuKind.LIVE_LOCATION
    items += listOf(
        AttachMenuKind.SCHEDULE,
        AttachMenuKind.QUICK_PHRASES
    )
    if (contactCardEnabled) items += AttachMenuKind.CONTACT_CARD
    if (aiEnabled) items += AttachMenuKind.AI
    items += AttachMenuKind.SILENT
    if (isGroup) {
        items.removeAll {
            it == AttachMenuKind.VIEW_ONCE ||
                it == AttachMenuKind.LIVE_LOCATION ||
                it == AttachMenuKind.NUDGE
        }
    }
    return items
}

// JSON booleans need no escaping; the wire builder only joins them.
internal fun escJsonMenu171(b: Boolean): String = if (b) "true" else "false"

// Production items returns the attach-sheet kinds in order.
internal fun menuProduction171(
    isGroup: Boolean,
    isChannel: Boolean,
    viewOnceEnabled: Boolean,
    contactCardEnabled: Boolean,
    nudgeEnabled: Boolean,
    aiEnabled: Boolean
): List<AttachMenuKind> =
    AttachMenuPolicy.items(
        isGroup = isGroup,
        isChannel = isChannel,
        viewOnceEnabled = viewOnceEnabled,
        contactCardEnabled = contactCardEnabled,
        nudgeEnabled = nudgeEnabled,
        aiEnabled = aiEnabled
    )

// ---------- generators ----------

internal data class MenuCase171(
    val isGroup: Boolean,
    val isChannel: Boolean,
    val viewOnceEnabled: Boolean,
    val contactCardEnabled: Boolean,
    val nudgeEnabled: Boolean,
    val aiEnabled: Boolean
)

internal fun MenuCase171.flags171(): List<Boolean> = listOf(
    isGroup, isChannel, viewOnceEnabled, contactCardEnabled, nudgeEnabled, aiEnabled
)

internal fun randomMenuCase171(rnd: Random): MenuCase171 =
    MenuCase171(
        isGroup = rnd.nextBoolean(),
        isChannel = rnd.nextBoolean(),
        viewOnceEnabled = rnd.nextBoolean(),
        contactCardEnabled = rnd.nextBoolean(),
        nudgeEnabled = rnd.nextBoolean(),
        aiEnabled = rnd.nextBoolean()
    )

internal fun menuWireJson171(c: MenuCase171): String {
    val flags = c.flags171().joinToString(",") { escJsonMenu171(it) }
    return "{\"flags\":[$flags]}"
}

class AttachMenuPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // (case, expected kinds) -- hand-written from the policy text.
        val plain = listOf(
            AttachMenuKind.IMAGE, AttachMenuKind.SPOILER, AttachMenuKind.PASTE,
            AttachMenuKind.VIDEO, AttachMenuKind.FILE, AttachMenuKind.VOICE,
            AttachMenuKind.LOCATION, AttachMenuKind.LIVE_LOCATION,
            AttachMenuKind.SCHEDULE, AttachMenuKind.QUICK_PHRASES,
            AttachMenuKind.SILENT
        )
        val cases = listOf(
            MenuCase171(false, false, false, false, false, false) to plain,
            MenuCase171(false, false, true, false, false, false) to listOf(
                AttachMenuKind.IMAGE, AttachMenuKind.VIEW_ONCE, AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE, AttachMenuKind.VIDEO, AttachMenuKind.FILE,
                AttachMenuKind.VOICE, AttachMenuKind.LOCATION,
                AttachMenuKind.LIVE_LOCATION, AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES, AttachMenuKind.SILENT
            ),
            MenuCase171(true, false, false, false, false, false) to listOf(
                AttachMenuKind.IMAGE, AttachMenuKind.SPOILER, AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO, AttachMenuKind.FILE, AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION, AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES, AttachMenuKind.SILENT
            ),
            MenuCase171(false, true, false, false, false, false) to listOf(
                AttachMenuKind.IMAGE, AttachMenuKind.PASTE, AttachMenuKind.VIDEO,
                AttachMenuKind.FILE, AttachMenuKind.SCHEDULE, AttachMenuKind.SILENT
            ),
            MenuCase171(false, true, false, false, false, true) to listOf(
                AttachMenuKind.IMAGE, AttachMenuKind.PASTE, AttachMenuKind.VIDEO,
                AttachMenuKind.FILE, AttachMenuKind.SCHEDULE, AttachMenuKind.AI,
                AttachMenuKind.SILENT
            ),
            // Channel wins over group even when both flags are set.
            MenuCase171(true, true, true, true, true, true) to listOf(
                AttachMenuKind.IMAGE, AttachMenuKind.PASTE, AttachMenuKind.VIDEO,
                AttachMenuKind.FILE, AttachMenuKind.SCHEDULE, AttachMenuKind.AI,
                AttachMenuKind.SILENT
            ),
            MenuCase171(false, false, false, true, false, false) to listOf(
                AttachMenuKind.IMAGE, AttachMenuKind.SPOILER, AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO, AttachMenuKind.FILE, AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION, AttachMenuKind.LIVE_LOCATION,
                AttachMenuKind.SCHEDULE, AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.CONTACT_CARD, AttachMenuKind.SILENT
            ),
            // Nudge changes nothing: the tile stays overflow-only.
            MenuCase171(false, false, false, false, true, false) to plain,
            MenuCase171(false, false, false, false, false, true) to listOf(
                AttachMenuKind.IMAGE, AttachMenuKind.SPOILER, AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO, AttachMenuKind.FILE, AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION, AttachMenuKind.LIVE_LOCATION,
                AttachMenuKind.SCHEDULE, AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.AI, AttachMenuKind.SILENT
            ),
            // Group with everything on: 1:1-only tiles still suppressed.
            MenuCase171(true, false, true, true, true, true) to listOf(
                AttachMenuKind.IMAGE, AttachMenuKind.SPOILER, AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO, AttachMenuKind.FILE, AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION, AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES, AttachMenuKind.CONTACT_CARD,
                AttachMenuKind.AI, AttachMenuKind.SILENT
            )
        )
        for ((c, expected) in cases) {
            assertEquals(expected, menuItems171(c), "items($c)")
            assertEquals(expected, menuOracleItems171(c), "oracle($c)")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(171002)
        repeat(4000) {
            val c = randomMenuCase171(rnd)
            assertEquals(
                menuOracleItems171(c),
                menuItems171(c),
                "items(random $c)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // All 64 flag combinations: oracle and production agree, and
        // the two structural invariants hold on every combination.
        for (bits in 0 until 64) {
            val c = MenuCase171(
                isGroup = bits and 1 != 0,
                isChannel = bits and 2 != 0,
                viewOnceEnabled = bits and 4 != 0,
                contactCardEnabled = bits and 8 != 0,
                nudgeEnabled = bits and 16 != 0,
                aiEnabled = bits and 32 != 0
            )
            val prod = menuItems171(c)
            assertEquals(menuOracleItems171(c), prod, "sweep(bits=$bits)")
            assertEquals(false, AttachMenuKind.NUDGE in prod, "no-nudge(bits=$bits)")
            if (c.isChannel) {
                assertEquals(false, AttachMenuKind.VIEW_ONCE in prod, "channel-no-view-once(bits=$bits)")
                assertEquals(false, AttachMenuKind.LIVE_LOCATION in prod, "channel-no-live(bits=$bits)")
            }
            if (c.isGroup && !c.isChannel) {
                assertEquals(false, AttachMenuKind.VIEW_ONCE in prod, "group-no-view-once(bits=$bits)")
                assertEquals(false, AttachMenuKind.LIVE_LOCATION in prod, "group-no-live(bits=$bits)")
            }
        }
    }
}

// Test-only helpers (outside the extracted oracle/generator spans,
// so the differential oracle never sees the production call).
internal fun menuItems171(c: MenuCase171): List<AttachMenuKind> =
    menuProduction171(
        c.isGroup, c.isChannel, c.viewOnceEnabled,
        c.contactCardEnabled, c.nudgeEnabled, c.aiEnabled
    )

internal fun menuOracleItems171(c: MenuCase171): List<AttachMenuKind> =
    menuOracle171(
        c.isGroup, c.isChannel, c.viewOnceEnabled,
        c.contactCardEnabled, c.nudgeEnabled, c.aiEnabled
    )
