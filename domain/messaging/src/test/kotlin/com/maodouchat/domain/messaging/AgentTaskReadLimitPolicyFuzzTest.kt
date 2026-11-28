package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// Fuzz / random-oracle tests for AgentTaskReadLimitPolicy (checklist line
// 931, 61st block). Test-only, zero production changes. The clamp oracle
// is hand-written (never coerceIn); the draftText oracle is a hand-written
// take(160) over UTF-16 units. Random ints cluster on the bounds and the
// extremes; random draft strings cluster on length 160.
class AgentTaskReadLimitPolicyFuzzTest {

// ---------- C2 AgentTaskReadLimitPolicy oracle ----------

// Hand-written clamp: below the range takes the low end, above takes
// the high end, inside passes through. Never calls coerceIn.
private fun clampOracle(v: Int, lo: Int, hi: Int): Int =
    if (v < lo) lo else if (v > hi) hi else v

private fun missedCallOracle(limit: Int): Int = clampOracle(limit, 1, 30)

private fun taskQueryOracle(limit: Int): Int = clampOracle(limit, 1, 80)

private fun taskResultOracle(limit: Int): Int = clampOracle(limit, 1, 40)

private fun takeOracle(s: String, n: Int): String =
    if (s.length <= n) s else s.substring(0, n)

private fun draftTextOracle(text: String): String = takeOracle(text, 160)

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Code-point ranges the random strings draw from. Covers ASCII, Latin-1,
// Latin/Greek/Cyrillic/Armenian/Georgian scripts, Greek Extended (with the
// multi-code-unit uppercase expansions), ligatures, fullwidth forms,
// Deseret / Adlam / Medefaidrin / Vithkuqi astral letters, CJK, combining
// marks, digits of several scripts, and general punctuation. Osage small
// letters are excluded: JDK 17 leaves them unchanged on uppercase while
// newer Unicode data maps them (pinned separately).
private val CP_RANGES = arrayOf(
    0x20 to 0x7E, 0x09 to 0x0D, 0x1C to 0x1F, 0x0085 to 0x0085,
    0x00A0 to 0x00FF, 0x0100 to 0x024F, 0x0300 to 0x036F,
    0x0370 to 0x03FF, 0x0400 to 0x052F, 0x0530 to 0x058F,
    0x0661 to 0x0661, 0x06F0 to 0x06F0, 0x0966 to 0x0966,
    0x10D0 to 0x10FF, 0x1680 to 0x1680, 0x1E00 to 0x1EFF,
    0x1F00 to 0x1FFF, 0x2000 to 0x206F, 0x2C00 to 0x2CFF,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xA640 to 0xA69F,
    0xA7C0 to 0xA7FF, 0xAB53 to 0xAB53, 0xAB70 to 0xAB70,
    0xFB00 to 0xFB17, 0xFEFF to 0xFEFF, 0xFF10 to 0xFF10,
    0xFF21 to 0xFF5A, 0x10400 to 0x1044F, 0x104A0 to 0x104A0,
    0x10570 to 0x105BC, 0x16E61 to 0x16E7F, 0x1E922 to 0x1E943,
    0x1F610 to 0x1F610
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = CP_RANGES[rnd.nextInt(CP_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0 -> { sb.append(' '); n++ }
            1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2 -> {
                // Lone surrogate (high or low): survives take/uppercase
                // untouched, encodes as 0x3F in UTF-8.
                sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++
            }
            else -> {
                val cp = randomCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

private fun randomInt(rnd: Random): Int = when (rnd.nextInt(8)) {
    0 -> 0; 1 -> 1; 2 -> -1; 3 -> 42; 4 -> -42
    5 -> Int.MIN_VALUE; 6 -> Int.MAX_VALUE
    else -> rnd.nextInt()
}

private fun randomLong(rnd: Random): Long = when (rnd.nextInt(8)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L; 3 -> 42L; 4 -> -42L
    5 -> Long.MIN_VALUE; 6 -> Long.MAX_VALUE
    else -> rnd.nextLong()
}

// ---------- block-61 generators ----------

// String of exactly len UTF-16 units (an astral char landing on the last
// unit may overshoot by one; the input is still used verbatim).
private fun randomStringExact(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0 -> { sb.append(' '); n++ }
            1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2 -> { sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++ }
            else -> {
                val cp = randomCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

// Draft text: lengths cluster on the 160 boundary (and 80, half of it).
private fun randomDraftString(rnd: Random): String {
    val len = when (rnd.nextInt(10)) {
        0 -> 0; 1 -> 1; 2 -> 159; 3 -> 160; 4 -> 161; 5 -> 320
        6 -> 79; 7 -> 80; 8 -> 81
        else -> rnd.nextInt(0, 401)
    }
    return randomStringExact(rnd, len)
}

// Entity-id suffix: mostly random strings, plus empty / UUID-shaped /
// template-injection shapes ($, ${...}) that must survive verbatim.
private fun randomIdSuffix(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> ""
    1 -> "11111111-2222-3333-4444-555555555555"
    2 -> "\$x"
    3 -> "\${x}"
    4 -> "\$"
    5 -> "a\$b\${c}d"
    6 -> "\n\t"
    7 -> randomString(rnd, 8)
    8 -> randomString(rnd, 40)
    else -> randomString(rnd)
}

// All 52 registered tool names (values of AgentToolNamePolicy.kt consts).
private val TOOL_NAMES = arrayOf(
    "accept_friend_request", "add_group_members", "block_user",
    "cancel_friend_request", "comment_on_post", "complete_local_task",
    "create_direct_chat", "create_group", "create_local_task",
    "create_text_post", "delete_chat", "delete_local_message",
    "delete_local_task", "delete_post", "get_chat", "get_chat_history",
    "get_contacts", "get_draft", "get_me", "get_post", "like_post",
    "list_blocked_users", "list_chats", "list_drafts",
    "list_friend_requests", "list_friends", "list_local_tasks",
    "list_missed_calls", "list_notifications", "list_pinned_messages",
    "list_post_comments", "list_posts", "list_starred_messages",
    "mark_notification_read", "mute_group_member", "pin_message",
    "react_to_message", "reject_friend_request", "remove_friend",
    "remove_group_member", "rename_group", "revoke_message",
    "search_messages", "search_users", "send_friend_request",
    "send_text_message", "set_contact_nickname", "set_draft",
    "star_message", "unblock_user", "update_chat",
    "update_group_announcement"
)

private fun nearMissToolName(rnd: Random): String {
    val base = TOOL_NAMES[rnd.nextInt(TOOL_NAMES.size)]
    return when (rnd.nextInt(8)) {
        0 -> base.uppercase()
        1 -> base.replace("_", "")
        2 -> " $base"
        3 -> "$base "
        4 -> base.dropLast(1)
        5 -> base + "s"
        6 -> base.replaceFirstChar { it.uppercaseChar() }
        else -> base.drop(1)
    }
}

private fun randomToolName(rnd: Random): String = when (rnd.nextInt(8)) {
    0, 1, 2, 3 -> TOOL_NAMES[rnd.nextInt(TOOL_NAMES.size)]
    4 -> nearMissToolName(rnd)
    5 -> randomString(rnd, 24)
    6 -> TOOL_NAMES[rnd.nextInt(TOOL_NAMES.size)] + TOOL_NAMES[rnd.nextInt(TOOL_NAMES.size)]
    else -> randomString(rnd)
}

    @Test
    fun missedCallRandom() {
        val rnd = Random(42)
        repeat(300) {
            val v = randomInt(rnd)
            assertEquals(missedCallOracle(v), AgentTaskReadLimitPolicy.missedCallLimit(v))
        }
    }

    @Test
    fun taskQueryRandom() {
        val rnd = Random(42)
        repeat(300) {
            val v = randomInt(rnd)
            assertEquals(taskQueryOracle(v), AgentTaskReadLimitPolicy.taskQueryLimit(v))
        }
    }

    @Test
    fun taskResultRandom() {
        val rnd = Random(42)
        repeat(300) {
            val v = randomInt(rnd)
            assertEquals(taskResultOracle(v), AgentTaskReadLimitPolicy.taskResultLimit(v))
        }
    }

    @Test
    fun draftTextRandom() {
        val rnd = Random(42)
        repeat(300) {
            val s = randomDraftString(rnd)
            assertEquals(draftTextOracle(s), AgentTaskReadLimitPolicy.draftText(s))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // Draft constants are part of the contract.
        assertEquals(40, AgentTaskReadLimitPolicy.MAX_DRAFT_LIST_ITEMS)
        assertEquals(160, AgentTaskReadLimitPolicy.MAX_DRAFT_TEXT_CHARS)
        // missedCallLimit: clamps to 1..30.
        assertEquals(1, AgentTaskReadLimitPolicy.missedCallLimit(Int.MIN_VALUE))
        assertEquals(1, AgentTaskReadLimitPolicy.missedCallLimit(-42))
        assertEquals(1, AgentTaskReadLimitPolicy.missedCallLimit(-1))
        assertEquals(1, AgentTaskReadLimitPolicy.missedCallLimit(0))
        assertEquals(1, AgentTaskReadLimitPolicy.missedCallLimit(1))
        assertEquals(1, AgentTaskReadLimitPolicy.missedCallLimit(0))
        assertEquals(1, AgentTaskReadLimitPolicy.missedCallLimit(1))
        assertEquals(2, AgentTaskReadLimitPolicy.missedCallLimit(2))
        assertEquals(29, AgentTaskReadLimitPolicy.missedCallLimit(29))
        assertEquals(30, AgentTaskReadLimitPolicy.missedCallLimit(30))
        assertEquals(30, AgentTaskReadLimitPolicy.missedCallLimit(31))
        assertEquals(30, AgentTaskReadLimitPolicy.missedCallLimit(42))
        assertEquals(30, AgentTaskReadLimitPolicy.missedCallLimit(Int.MAX_VALUE))
        // taskQueryLimit: clamps to 1..80.
        assertEquals(1, AgentTaskReadLimitPolicy.taskQueryLimit(Int.MIN_VALUE))
        assertEquals(1, AgentTaskReadLimitPolicy.taskQueryLimit(-42))
        assertEquals(1, AgentTaskReadLimitPolicy.taskQueryLimit(-1))
        assertEquals(1, AgentTaskReadLimitPolicy.taskQueryLimit(0))
        assertEquals(1, AgentTaskReadLimitPolicy.taskQueryLimit(1))
        assertEquals(1, AgentTaskReadLimitPolicy.taskQueryLimit(0))
        assertEquals(1, AgentTaskReadLimitPolicy.taskQueryLimit(1))
        assertEquals(2, AgentTaskReadLimitPolicy.taskQueryLimit(2))
        assertEquals(79, AgentTaskReadLimitPolicy.taskQueryLimit(79))
        assertEquals(80, AgentTaskReadLimitPolicy.taskQueryLimit(80))
        assertEquals(80, AgentTaskReadLimitPolicy.taskQueryLimit(81))
        assertEquals(42, AgentTaskReadLimitPolicy.taskQueryLimit(42))
        assertEquals(80, AgentTaskReadLimitPolicy.taskQueryLimit(Int.MAX_VALUE))
        // taskResultLimit: clamps to 1..40.
        assertEquals(1, AgentTaskReadLimitPolicy.taskResultLimit(Int.MIN_VALUE))
        assertEquals(1, AgentTaskReadLimitPolicy.taskResultLimit(-42))
        assertEquals(1, AgentTaskReadLimitPolicy.taskResultLimit(-1))
        assertEquals(1, AgentTaskReadLimitPolicy.taskResultLimit(0))
        assertEquals(1, AgentTaskReadLimitPolicy.taskResultLimit(1))
        assertEquals(1, AgentTaskReadLimitPolicy.taskResultLimit(0))
        assertEquals(1, AgentTaskReadLimitPolicy.taskResultLimit(1))
        assertEquals(2, AgentTaskReadLimitPolicy.taskResultLimit(2))
        assertEquals(39, AgentTaskReadLimitPolicy.taskResultLimit(39))
        assertEquals(40, AgentTaskReadLimitPolicy.taskResultLimit(40))
        assertEquals(40, AgentTaskReadLimitPolicy.taskResultLimit(41))
        assertEquals(40, AgentTaskReadLimitPolicy.taskResultLimit(42))
        assertEquals(40, AgentTaskReadLimitPolicy.taskResultLimit(Int.MAX_VALUE))
        // draftText: limit 160 UTF-16 units, no trimming.
        assertEquals("", AgentTaskReadLimitPolicy.draftText(""))
        assertEquals("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", AgentTaskReadLimitPolicy.draftText("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
        assertEquals("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", AgentTaskReadLimitPolicy.draftText("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
        assertEquals("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", AgentTaskReadLimitPolicy.draftText("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
        assertEquals("\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10", AgentTaskReadLimitPolicy.draftText("\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10"))
        assertEquals("\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10", AgentTaskReadLimitPolicy.draftText("\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10"))
        assertEquals("\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10", AgentTaskReadLimitPolicy.draftText("\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10"))
        assertEquals("\ud800\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d", AgentTaskReadLimitPolicy.draftText("\ud800\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10"))
        assertEquals("a\ud800\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10", AgentTaskReadLimitPolicy.draftText("a\ud800\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10\ud83d\ude10"))
        assertEquals("hello", AgentTaskReadLimitPolicy.draftText("hello"))
        assertEquals("  hi", AgentTaskReadLimitPolicy.draftText("  hi"))
        assertEquals("  xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", AgentTaskReadLimitPolicy.draftText("  xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
        assertEquals("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", AgentTaskReadLimitPolicy.draftText("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))    }
}
