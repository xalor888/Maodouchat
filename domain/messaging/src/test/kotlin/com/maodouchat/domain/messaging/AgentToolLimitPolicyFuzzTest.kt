package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// Fuzz / random-oracle tests for AgentToolLimitPolicy (checklist line 931,
// 61st block). Test-only, zero production changes. The oracle dispatches
// by hand over literal tool names (no shared table with production);
// random names mix the 52 registered names, near-miss variants and
// random strings.
class AgentToolLimitPolicyFuzzTest {

// ---------- C3 AgentToolLimitPolicy oracle ----------

// Hand-written dispatch over literal names: only search_messages /
// list_missed_calls take 12, only list_post_comments takes 30, everything
// else takes 20 (the six explicitly-20 names in production are covered
// by the else branch here, which the differential drive verifies name
// by name). Case-sensitive, exact match only.
private fun toolLimitOracle(name: String): Int = when (name) {
    "search_messages", "list_missed_calls" -> 12
    "list_post_comments" -> 30
    else -> 20
}

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
    fun defaultLimitRandom() {
        val rnd = Random(42)
        repeat(300) {
            val n = randomToolName(rnd)
            assertEquals(toolLimitOracle(n), AgentToolLimitPolicy.defaultLimit(n))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // Every registered tool name, one assertion each.
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("accept_friend_request"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("add_group_members"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("block_user"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("cancel_friend_request"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("comment_on_post"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("complete_local_task"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("create_direct_chat"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("create_group"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("create_local_task"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("create_text_post"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("delete_chat"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("delete_local_message"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("delete_local_task"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("delete_post"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("get_chat"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("get_chat_history"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("get_contacts"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("get_draft"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("get_me"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("get_post"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("like_post"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_blocked_users"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_chats"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_drafts"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_friend_requests"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_friends"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_local_tasks"))
        assertEquals(12, AgentToolLimitPolicy.defaultLimit("list_missed_calls"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_notifications"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_pinned_messages"))
        assertEquals(30, AgentToolLimitPolicy.defaultLimit("list_post_comments"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_posts"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_starred_messages"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("mark_notification_read"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("mute_group_member"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("pin_message"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("react_to_message"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("reject_friend_request"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("remove_friend"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("remove_group_member"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("rename_group"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("revoke_message"))
        assertEquals(12, AgentToolLimitPolicy.defaultLimit("search_messages"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("search_users"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("send_friend_request"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("send_text_message"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("set_contact_nickname"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("set_draft"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("star_message"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("unblock_user"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("update_chat"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("update_group_announcement"))
        // Near misses fall back to 20: case, spacing, underscores,
        // truncation and splices never match.
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("SEARCH_MESSAGES"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("Search_Messages"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("search__messages"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list__missed_calls"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_missed_calls\u000a"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("search_messages\u0009"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit(" list_missed_calls"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_missed_calls "))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_missed_call"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_missed_callsx"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("listposts"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_posts_comments"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("get_chat_history2"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_post_comment"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("LIST_POST_COMMENTS"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("List_Post_Comments"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("searchmessages"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("listmissedcalls"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("get_chathistory"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit(""))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit(" "))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("list_chatslist_chats"))
        assertEquals(20, AgentToolLimitPolicy.defaultLimit("search_messages/list_missed_calls"))    }
}
