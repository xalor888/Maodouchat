package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPollPolicyTest {
    @Test
    fun pkShortcutNormalizesChoiceAndFallsBackForInvalidChoice() {
        assertEquals("~pk:pk_1|left", GroupPollPolicy.buildPkShortcut("pk_1", "left"))
        assertEquals("~pk:pk_1|right", GroupPollPolicy.buildPkShortcut("pk_1", "RIGHT"))
        assertEquals("~pk:pk_1|left", GroupPollPolicy.buildPkShortcut("pk_1", "middle"))
    }

    @Test
    fun pkShortcutParsesOnlyKnownChoices() {
        assertEquals("pk_1" to "left", GroupPollPolicy.parsePkShortcut("~pk:pk_1|left"))
        assertEquals("pk_1" to "right", GroupPollPolicy.parsePkShortcut("~pk:pk_1|right"))
        assertNull(GroupPollPolicy.parsePkShortcut("~pk:pk_1|middle"))
    }

    @Test
    fun voteShortcutRoundTrips() {
        val built = GroupPollPolicy.buildVoteShortcut("p1", listOf(0, 2))
        assertEquals("p1" to listOf(0, 2), GroupPollPolicy.parseVoteShortcut(built))
    }

    @Test
    fun voteShortcutRejectsMalformed() {
        assertNull(GroupPollPolicy.parseVoteShortcut("hello"))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:|0"))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:p1|"))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:p1|abc"))
        assertFalse(GroupPollPolicy.isVoteShortcut("~vote:p1|"))
    }

    @Test
    fun checkinShortcutWithAndWithoutChatId() {
        assertEquals("g1", GroupPollPolicy.parseCheckinShortcut(GroupPollPolicy.buildCheckinShortcut("g1")))
        assertTrue(GroupPollPolicy.isCheckinShortcut(GroupPollPolicy.buildCheckinShortcut()))
        assertNull(GroupPollPolicy.parseCheckinShortcut("~checkin"))
    }

    @Test
    fun chainShortcutEscapesSeparators() {
        val built = GroupPollPolicy.buildChainShortcut("c1", "a|b^c")
        assertEquals("c1" to "a|b^c", GroupPollPolicy.parseChainShortcut(built))
    }

    @Test
    fun pollValidationBounds() {
        assertTrue(GroupPollPolicy.isValidPollQuestion("x".repeat(200)))
        assertFalse(GroupPollPolicy.isValidPollQuestion("x".repeat(201)))
        assertTrue(GroupPollPolicy.isValidPollOptions(listOf("a", "b")))
        assertFalse(GroupPollPolicy.isValidPollOptions(listOf("a")))
        assertFalse(GroupPollPolicy.isValidPollOptions(listOf("a", "A")))
    }

    @Test
    fun voteShortcutParseNormalizesIndexes() {
        assertEquals("p1" to listOf(2, 0), GroupPollPolicy.parseVoteShortcut("~vote:p1|2, 0, 2, -1"))
        assertEquals("p1" to listOf(1), GroupPollPolicy.parseVoteShortcut("  ~vote:p1|1  "))
        assertEquals("p1" to listOf(0, 1), GroupPollPolicy.parseVoteShortcut("~vote:p1|0,abc,1"))
        assertTrue(GroupPollPolicy.isVoteShortcut("~vote:p1|0"))
        assertFalse(GroupPollPolicy.isVoteShortcut("~vote:p1|-1"))
    }

    @Test
    fun sanitizePollOptionsTrimsFiltersAndTruncatesWithoutDedup() {
        assertEquals(
            listOf("a", "b".repeat(80)),
            GroupPollPolicy.sanitizePollOptions(listOf("  a  ", "", "   ", "b".repeat(100))),
        )
        assertEquals(listOf("a", "a"), GroupPollPolicy.sanitizePollOptions(listOf("a", "a")))
    }

    @Test
    fun pollOptionsBounds() {
        assertTrue(GroupPollPolicy.isValidPollOptions((1..12).map { "o$it" }))
        assertFalse(GroupPollPolicy.isValidPollOptions((1..13).map { "o$it" }))
        assertTrue(GroupPollPolicy.isValidPollOptions(listOf("x".repeat(80), "y")))
        assertFalse(GroupPollPolicy.isValidPollOptions(listOf("x".repeat(81), "y")))
        assertTrue(GroupPollPolicy.isValidPollOptions(listOf("a", " ", "b")))
    }

    @Test
    fun chainAndPkValidationBounds() {
        assertTrue(GroupPollPolicy.isValidChainTitle("x".repeat(200)))
        assertFalse(GroupPollPolicy.isValidChainTitle("x".repeat(201)))
        assertFalse(GroupPollPolicy.isValidChainTitle(""))
        assertFalse(GroupPollPolicy.isValidChainTitle("   "))
        assertTrue(GroupPollPolicy.isValidChainTopic("x".repeat(500)))
        assertFalse(GroupPollPolicy.isValidChainTopic("x".repeat(501)))
        assertTrue(GroupPollPolicy.isValidChainTopic(""))
        assertTrue(GroupPollPolicy.isValidChainTopic("   "))
        assertFalse(GroupPollPolicy.isValidChainContent(""))
        assertFalse(GroupPollPolicy.isValidChainContent("   "))
        assertTrue(GroupPollPolicy.isValidChainContent("x".repeat(500)))
        assertFalse(GroupPollPolicy.isValidChainContent("x".repeat(501)))
        assertTrue(GroupPollPolicy.isValidPkTitle("x".repeat(120)))
        assertFalse(GroupPollPolicy.isValidPkTitle("x".repeat(121)))
        assertFalse(GroupPollPolicy.isValidPkTitle(""))
    }

    @Test
    fun pkShortcutParseEdges() {
        assertEquals("k1" to "left", GroupPollPolicy.parsePkShortcut("~pk:k1|LEFT"))
        assertEquals("k1" to "right", GroupPollPolicy.parsePkShortcut("~pk:k1| RIGHT "))
        assertEquals("~pk:k1|left", GroupPollPolicy.buildPkShortcut("k1", "LEFT"))
        assertEquals("~pk:k1|left", GroupPollPolicy.buildPkShortcut("k1", ""))
        assertNull(GroupPollPolicy.parsePkShortcut("~pk:|left"))
        assertNull(GroupPollPolicy.parsePkShortcut("~pk:k1|"))
        assertFalse(GroupPollPolicy.isPkShortcut("~pk:k1|middle"))
    }

    @Test
    fun checkinShortcutBlankEdgesAndStreakZero() {
        assertEquals("~checkin", GroupPollPolicy.buildCheckinShortcut("  "))
        assertNull(GroupPollPolicy.parseCheckinShortcut("~checkin:"))
        assertEquals("g1", GroupPollPolicy.parseCheckinShortcut("~checkin: g1 "))
        assertFalse(GroupPollPolicy.isCheckinShortcut("~checkin:"))
        assertTrue(GroupPollPolicy.isCheckinShortcut("~checkin"))
        assertEquals("~checkin 小明 已签到 · 连续 0 天", GroupPollPolicy.formatCheckinShare("小明", 0))
        assertEquals("~checkin 小明 已签到 · 连续 1 天", GroupPollPolicy.formatCheckinShare("小明", 1))
    }

    @Test
    fun chainShortcutCaretEscapeAndEmptyContent() {
        val built = GroupPollPolicy.buildChainShortcut("c1", "a^b|c")
        assertEquals("c1" to "a^b|c", GroupPollPolicy.parseChainShortcut(built))
        assertNull(GroupPollPolicy.parseChainShortcut("~chain:c1|"))
        assertFalse(GroupPollPolicy.isChainShortcut("~chain:c1|"))
        assertTrue(GroupPollPolicy.isChainShortcut("~chain:c1|x"))
        assertEquals(
            "🔗 群接龙 · T\na|b\n第 1 条：~chain:c1|a\u0001b",
            GroupPollPolicy.formatChainShare("c1", "T", "a|b", 1),
        )
    }

    @Test
    fun voteShortcutEmptyAndDedupOrder() {
        assertEquals("~vote:p1|", GroupPollPolicy.buildVoteShortcut("p1", emptyList()))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:p1|"))
        assertFalse(GroupPollPolicy.isVoteShortcut("~vote:p1|"))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:p1|abc"))
        assertEquals("p1" to listOf(2, 1), GroupPollPolicy.parseVoteShortcut("~vote:p1|2,2,1"))
        assertEquals("p1" to listOf(3, 1), GroupPollPolicy.parseVoteShortcut("~vote:p1|3, 1, 3"))
    }

    @Test
    fun pollQuestionLengthCapAppliesToValidationAndShare() {
        assertFalse(GroupPollPolicy.isValidPollQuestion(""))
        assertFalse(GroupPollPolicy.isValidPollQuestion("   "))
        assertTrue(GroupPollPolicy.isValidPollQuestion(" " + "x".repeat(200) + " "))
        assertFalse(GroupPollPolicy.isValidPollQuestion(" " + "x".repeat(201) + " "))
        assertFalse(GroupPollPolicy.isValidPollOptions(listOf("a ", " A")))
        assertEquals(emptyList<String>(), GroupPollPolicy.sanitizePollOptions(emptyList()))
        assertEquals(listOf("x"), GroupPollPolicy.sanitizePollOptions(listOf("  x  ")))
        assertEquals(
            "📊 群投票 · " + "q".repeat(200) + "\n~vote:p1|0",
            GroupPollPolicy.formatPollShare("p1", "q".repeat(250), emptyList(), false),
        )
    }

    @Test
    fun shareFormatsEmbedShortcuts() {
        assertEquals(
            "📊 群投票 · Q?\n1. A\n2. B\n~vote:p1|0",
            GroupPollPolicy.formatPollShare("p1", "Q?", listOf("A", "B"), false),
        )
        assertEquals(
            "📊 群投票 · Q?\n1. A\n2. B\n（可多选）\n~vote:p1|0",
            GroupPollPolicy.formatPollShare("p1", "Q?", listOf("A", "B"), true),
        )
        val longShare = GroupPollPolicy.formatPollShare("p1", "q".repeat(250), emptyList(), false)
        assertTrue(longShare.startsWith("📊 群投票 · " + "q".repeat(200)))
        assertTrue(longShare.endsWith("~vote:p1|0"))
        assertEquals("~checkin 小明 已签到 · 连续 3 天", GroupPollPolicy.formatCheckinShare("小明", 3))
        assertEquals(
            "🔗 群接龙 · T\ntopic\n第 2 条：~chain:c1|topic",
            GroupPollPolicy.formatChainShare("c1", "T", "topic", 2),
        )
        assertEquals(
            "⚔️ 群 PK\n~pk:k1|left · ~pk:k1|right\n左 vs 右",
            GroupPollPolicy.formatPkShare("k1", "左", "右"),
        )
        assertTrue(GroupPollPolicy.isPkShortcut("~pk:k1|LEFT"))
        assertFalse(GroupPollPolicy.isChainShortcut("~chain:c1|"))
    }

    @Test
    fun voteShortcutNormalizesPlusAndLeadingZeroIndexes() {
        assertEquals("p1" to listOf(1, 2), GroupPollPolicy.parseVoteShortcut("~vote:p1|+1,01,2"))
        assertEquals("p1" to listOf(7), GroupPollPolicy.parseVoteShortcut("~vote:p1|007"))
        assertEquals("p1" to listOf(0), GroupPollPolicy.parseVoteShortcut("~vote:p1|00"))
    }

    @Test
    fun voteShortcutSecondPipeBreaksIndexSection() {
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:a|b|0"))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:p1|0|1"))
        assertFalse(GroupPollPolicy.isVoteShortcut("~vote:p1|0|1"))
    }

    @Test
    fun pkShortcutBuildDoesNotTrimChoice() {
        assertEquals("~pk:k1|left", GroupPollPolicy.buildPkShortcut("k1", "LEFT "))
        assertEquals("~pk:k1|left", GroupPollPolicy.buildPkShortcut("k1", " Right"))
        assertNull(GroupPollPolicy.parsePkShortcut("~pk:k1|left extra"))
        assertEquals("k1" to "left", GroupPollPolicy.parsePkShortcut("~pk:k1|left "))
    }

    @Test
    fun voteBuildParseDropsNegativeIndexes() {
        val built = GroupPollPolicy.buildVoteShortcut("p1", listOf(-1, 5))
        assertEquals("~vote:p1|-1,5", built)
        assertEquals("p1" to listOf(5), GroupPollPolicy.parseVoteShortcut(built))
        assertEquals(
            "p1" to listOf(3),
            GroupPollPolicy.parseVoteShortcut(GroupPollPolicy.buildVoteShortcut("p1", listOf(3, -2, 3))),
        )
    }

    @Test
    fun pollShareNumbersOptionsAndKeepsTitlesVerbatim() {
        assertEquals(
            "📊 群投票 · Q?\n1. A\n2. B\n3. C\n~vote:p1|0",
            GroupPollPolicy.formatPollShare("p1", "Q?", listOf("A", "B", "C"), false),
        )
        assertEquals(
            "⚔️ 群 PK\n~pk:k1|left · ~pk:k1|right\n左 vs 右 vs 右",
            GroupPollPolicy.formatPkShare("k1", "左 vs 右", "右"),
        )
        assertEquals(
            "🔗 群接龙 · T\ntopic\n第 0 条：~chain:c1|topic",
            GroupPollPolicy.formatChainShare("c1", "T", "topic", 0),
        )
    }

}
