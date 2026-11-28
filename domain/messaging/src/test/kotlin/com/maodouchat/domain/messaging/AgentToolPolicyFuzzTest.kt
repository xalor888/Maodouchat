// Fuzz / random-oracle tests for AgentToolPolicy (checklist line 931,
// 76th block). Test-only, zero production changes. The oracle is
// hand-written: plain string building with the exact templates
// read from the production file. toolByName is first-match by exact name over the 53-spec catalog; approvalFor is READ -> ALLOW, WRITE/SEND -> NEED_USER, unknown -> DENY; openaiToolsJson preserves catalog order with name / required / parameter keys per spec.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentToolPolicyFuzzTest {

// ---------- shared oracle (all three clusters) ----------

// The probed Kotlin blank set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as blank, U+0085 NEL / U+200B / U+FEFF do not.
// Hand-written, never calls Char.isWhitespace / String.isBlank.
private val BLANK_UNITS_ORACLE = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

private fun isBlankOracle(s: String): Boolean {
    for (c in s) {
        if (c.code !in BLANK_UNITS_ORACLE) return false
    }
    return true
}

// Hand-written trim: strip leading/trailing UTF-16 units in the
// probed 28-unit Kotlin whitespace set. Never trim().
private fun trimOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

// Unit-by-unit equality. Never == on strings.
private fun equalsOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        if (a[i] != b[i]) return false
    }
    return true
}

// Per-code-point simple case mapping. Agrees with String.lowercase()
// (full mapping) on the case-safe alphabet; never calls lowercase().
// Diverges on purpose outside the alphabet: U+0130 İ (full mapping
// expands to i + U+0307), word-final sigma, U+0587 -- those are
// pinned with probe-verified literals, never generated.
private fun lowercaseOracle(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        sb.append(Character.toChars(Character.toLowerCase(cp)))
        i += Character.charCount(cp)
    }
    return sb.toString()
}

// Hand-written substring search over UTF-16 units. Never contains().
private fun containsOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (s[i + j] != needle[j]) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

// Case-insensitive contains: per-char fold, exactly as
// regionMatches(ignoreCase = true) does it -- equal, else equal after
// toUpperCase, else equal after toLowerCase. Exact for every input;
// no alphabet restriction applies to this function.
private fun charsEqualIgnoreCaseOracle(a: Char, b: Char): Boolean {
    if (a == b) return true
    if (Character.toUpperCase(a) == Character.toUpperCase(b)) return true
    return Character.toLowerCase(a) == Character.toLowerCase(b)
}

private fun containsIgnoreCaseOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (!charsEqualIgnoreCaseOracle(s[i + j], needle[j])) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

private fun replaceCharOracle(s: String, from: Char, to: Char): String {
    val chars = s.toCharArray()
    for (i in chars.indices) {
        if (chars[i] == from) chars[i] = to
    }
    return String(chars)
}

// Lexicographic order over UTF-16 units, as String.compareTo.
private fun compareOracle(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val d = a[i].code - b[i].code
        if (d != 0) return d
    }
    return a.length - b.length
}

// Hand-written take: first n UTF-16 units. Never take().
private fun takeOracle(s: String, n: Int): String {
    if (n <= 0) return ""
    if (n >= s.length) return s
    return s.substring(0, n)
}

private fun coerceAtLeastOracle(v: Int, lo: Int): Int = if (v < lo) lo else v

// ---------- shared generators ----------

// Every code point here maps 1:1 under BOTH the per-code-point simple
// mapping (Character.toLowerCase, what the oracle uses) and the full
// string mapping (String.lowercase(), what production uses), and folds
// the same under contains(ignoreCase = true). Excluded on purpose:
// Greek (word-final sigma), Armenian, ligatures U+FB00-FB17, U+0130
// (İ), U+00DF (ß, conservative), U+0131 (ı: upper folds to I,
// simple lowercase keeps ı), U+0149, U+017F (ſ: ignoreCase folds
// it to s, simple lowercase does not), U+01F0, U+1E9B (ṡ: upper folds
// to Ṡ, simple lowercase does not), Cherokee, Georgian --
// all special-casing code points. Same ranges as block 66, minus
// ſ / ı / U+1E9B.
private val CASE_SAFE_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00C0 to 0x00DE, 0x00E0 to 0x00FE,
    0x0100 to 0x012F, 0x0132 to 0x0148, 0x014A to 0x017E, 0x0180 to 0x01EF,
    0x0300 to 0x036F,
    0x1E00 to 0x1E95, 0x1E9C to 0x1EFF,
    0x2000 to 0x206F,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF,
    0xFF21 to 0xFF3A, 0xFF41 to 0xFF5A,
    0x10400 to 0x1044F
)

// Astral emoji: no case at all, lowercase is identity on both sides.
private val EMOJI_RANGES = arrayOf(
    0x1F300 to 0x1F5FF, 0x1F600 to 0x1F64F, 0x1F680 to 0x1F6FF, 0x1F900 to 0x1F9FF
)

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private fun randomCaseSafeCodePoint(rnd: Random): Int {
    val (lo, hi) = CASE_SAFE_RANGES[rnd.nextInt(CASE_SAFE_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomEmojiCodePoint(rnd: Random): Int {
    val (lo, hi) = EMOJI_RANGES[rnd.nextInt(EMOJI_RANGES.size)]
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
            2 -> { sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++ }
            3 -> { sb.append(Character.toChars(randomEmojiCodePoint(rnd))); n += 2 }
            else -> {
                val cp = randomCaseSafeCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

// Short tokens for ids / pack ids / sticker names: pool words (so the
// interesting collisions actually happen) mixed with random strings.
private val TOKEN_POOL = arrayOf(
    "a", "b", "c", "A", "B", "x", "y", "z", "ab", "AB", "a_b", "a-b",
    "mood", "gesture", "party", "cat", "CAT", "Cat", "happy", "HAPPY",
    "ok", "fire", "pet", "love", "hi", "no", "win", "star", "", " ",
    "  ", "😀", "🎉", "🔥", "👍", "🥳", "a b", "funny_cat", "funny-cat"
)

private fun randomToken(rnd: Random): String =
    if (rnd.nextInt(4) == 0) randomString(rnd, 6) else TOKEN_POOL[rnd.nextInt(TOKEN_POOL.size)]

private fun randomWord(rnd: Random): String {
    return when (rnd.nextInt(5)) {
        0 -> "cat"
        1 -> "CAT"
        2 -> "funny cat"
        3 -> "happy"
        else -> randomString(rnd, 12)
    }
}

// ---------- Kotlin trim() oracle (28-unit whitespace set) ----------

private fun trimKotlinOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

// ---------- AgentToolPolicy oracle ----------

private data class ToolOracleEntry(
    val name: String,
    val risk: Risk,
    val required: List<String>,
    val params: List<String>,
)

private val TOOL_TABLE = listOf(
    ToolOracleEntry("list_chats", Risk.READ, listOf(), listOf("query")),
    ToolOracleEntry("get_chat_history", Risk.READ, listOf("chatId"), listOf("chatId", "limit")),
    ToolOracleEntry("search_messages", Risk.READ, listOf("query"), listOf("query", "limit")),
    ToolOracleEntry("get_contacts", Risk.READ, listOf(), listOf("query")),
    ToolOracleEntry("get_me", Risk.READ, listOf(), listOf()),
    ToolOracleEntry("get_chat", Risk.READ, listOf("chatId"), listOf("chatId")),
    ToolOracleEntry("list_starred_messages", Risk.READ, listOf(), listOf("limit")),
    ToolOracleEntry("list_drafts", Risk.READ, listOf(), listOf()),
    ToolOracleEntry("get_draft", Risk.READ, listOf("chatId"), listOf("chatId")),
    ToolOracleEntry("list_local_tasks", Risk.READ, listOf(), listOf("limit")),
    ToolOracleEntry("list_missed_calls", Risk.READ, listOf(), listOf("limit")),
    ToolOracleEntry("list_notifications", Risk.READ, listOf(), listOf("limit")),
    ToolOracleEntry("rewrite_text", Risk.READ, listOf("text"), listOf("text", "mode", "targetLanguage")),
    ToolOracleEntry("create_local_task", Risk.WRITE, listOf("chatId", "title"), listOf("chatId", "title", "dueText")),
    ToolOracleEntry("send_text_message", Risk.SEND, listOf("chatId", "text"), listOf("chatId", "text")),
    ToolOracleEntry("update_chat", Risk.WRITE, listOf("chatId"), listOf("chatId", "pinned", "muted", "archived", "markedUnread")),
    ToolOracleEntry("set_draft", Risk.WRITE, listOf("chatId"), listOf("chatId", "text")),
    ToolOracleEntry("star_message", Risk.WRITE, listOf("messageId", "starred"), listOf("messageId", "starred")),
    ToolOracleEntry("complete_local_task", Risk.WRITE, listOf("taskId", "completed"), listOf("taskId", "completed")),
    ToolOracleEntry("delete_local_task", Risk.WRITE, listOf("taskId"), listOf("taskId")),
    ToolOracleEntry("set_contact_nickname", Risk.WRITE, listOf("userId"), listOf("userId", "nickname")),
    ToolOracleEntry("delete_local_message", Risk.WRITE, listOf("messageId"), listOf("messageId")),
    ToolOracleEntry("mark_notification_read", Risk.WRITE, listOf("itemId"), listOf("itemId")),
    ToolOracleEntry("list_pinned_messages", Risk.READ, listOf("chatId"), listOf("chatId")),
    ToolOracleEntry("list_friend_requests", Risk.READ, listOf(), listOf("direction")),
    ToolOracleEntry("list_friends", Risk.READ, listOf(), listOf()),
    ToolOracleEntry("search_users", Risk.READ, listOf("query"), listOf("query", "limit")),
    ToolOracleEntry("list_posts", Risk.READ, listOf(), listOf("limit")),
    ToolOracleEntry("get_post", Risk.READ, listOf("postId"), listOf("postId")),
    ToolOracleEntry("list_post_comments", Risk.READ, listOf("postId"), listOf("postId", "limit")),
    ToolOracleEntry("list_blocked_users", Risk.READ, listOf(), listOf()),
    ToolOracleEntry("revoke_message", Risk.WRITE, listOf("messageId"), listOf("messageId")),
    ToolOracleEntry("react_to_message", Risk.WRITE, listOf("messageId"), listOf("messageId", "emoji")),
    ToolOracleEntry("pin_message", Risk.WRITE, listOf("chatId"), listOf("chatId", "messageId")),
    ToolOracleEntry("send_friend_request", Risk.WRITE, listOf("userId"), listOf("userId", "message")),
    ToolOracleEntry("accept_friend_request", Risk.WRITE, listOf("requestId"), listOf("requestId")),
    ToolOracleEntry("reject_friend_request", Risk.WRITE, listOf("requestId"), listOf("requestId")),
    ToolOracleEntry("cancel_friend_request", Risk.WRITE, listOf("requestId"), listOf("requestId")),
    ToolOracleEntry("remove_friend", Risk.WRITE, listOf("userId"), listOf("userId")),
    ToolOracleEntry("block_user", Risk.WRITE, listOf("userId"), listOf("userId")),
    ToolOracleEntry("unblock_user", Risk.WRITE, listOf("userId"), listOf("userId")),
    ToolOracleEntry("create_text_post", Risk.WRITE, listOf("text"), listOf("text", "visibility")),
    ToolOracleEntry("like_post", Risk.WRITE, listOf("postId", "liked"), listOf("postId", "liked")),
    ToolOracleEntry("comment_on_post", Risk.WRITE, listOf("postId", "text"), listOf("postId", "text")),
    ToolOracleEntry("delete_post", Risk.WRITE, listOf("postId"), listOf("postId")),
    ToolOracleEntry("create_direct_chat", Risk.WRITE, listOf("userId"), listOf("userId")),
    ToolOracleEntry("create_group", Risk.WRITE, listOf("name", "memberIds"), listOf("name", "memberIds")),
    ToolOracleEntry("rename_group", Risk.WRITE, listOf("chatId", "name"), listOf("chatId", "name")),
    ToolOracleEntry("update_group_announcement", Risk.WRITE, listOf("chatId"), listOf("chatId", "announcement")),
    ToolOracleEntry("add_group_members", Risk.WRITE, listOf("chatId", "memberIds"), listOf("chatId", "memberIds")),
    ToolOracleEntry("remove_group_member", Risk.WRITE, listOf("chatId", "memberId"), listOf("chatId", "memberId")),
    ToolOracleEntry("mute_group_member", Risk.WRITE, listOf("chatId", "memberId", "mutedUntil"), listOf("chatId", "memberId", "mutedUntil")),
    ToolOracleEntry("delete_chat", Risk.WRITE, listOf("chatId"), listOf("chatId"))
)

private fun toolByNameOracle(name: String): ToolOracleEntry? {
    for (entry in TOOL_TABLE) {
        if (equalsOracle(entry.name, name)) return entry
    }
    return null
}

private fun approvalOracle(name: String): Approval {
    val entry = toolByNameOracle(name) ?: return Approval.DENY
    return when (entry.risk) {
        Risk.READ -> Approval.ALLOW
        Risk.WRITE, Risk.SEND -> Approval.NEED_USER
    }
}

// openaiToolsJson structure, encoded as one "name|required|params"
// segment per tool, in catalog order. Descriptions are long
// literals and are not part of this oracle; names, order, required
// lists and parameter keys are.
private fun toolJsonOracle(): String {
    val sb = StringBuilder()
    for ((i, entry) in TOOL_TABLE.withIndex()) {
        if (i > 0) sb.append(";")
        sb.append(entry.name)
        sb.append("|")
        sb.append(entry.required.joinToString(","))
        sb.append("|")
        sb.append(entry.params.joinToString(","))
    }
    return sb.toString()
}

// Encodes the production JSON view the same way toolJsonOracle does,
// so the test can compare the two strings directly.
private fun toolJsonProd(): String {
    val sb = StringBuilder()
    val tools = AgentToolPolicy.openaiToolsJson()
    for ((i, entry) in tools.withIndex()) {
        if (i > 0) sb.append(";")
        @Suppress("UNCHECKED_CAST")
        val fn = entry["function"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val params = fn["parameters"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val props = params["properties"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val required = params["required"] as List<String>
        sb.append(fn["name"] as String)
        sb.append("|")
        sb.append(required.joinToString(","))
        sb.append("|")
        sb.append(props.keys.joinToString(","))
    }
    return sb.toString()
}

private val TOOL_ARG_KEYS = listOf(
    "chatId", "text", "pinned", "muted", "archived", "markedUnread",
    "messageId", "starred", "userId", "nickname", "itemId", "emoji",
    "name", "memberIds", "memberId", "postId", "liked", "title",
    "taskId", "completed", "requestId", "zzz", "other"
)

private fun genToolArgValue(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0 -> ""
        1 -> " "
        2 -> "   "
        3 -> "x"
        4 -> "c1"
        5 -> "true"
        6 -> randomWord(rnd)
        7 -> randomToken(rnd)
        8 -> "a".repeat(rnd.nextInt(0, 200))
        else -> randomString(rnd, 60)
    }

private fun genToolName(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> TOOL_TABLE[rnd.nextInt(TOOL_TABLE.size)].name
        6 -> TOOL_TABLE[rnd.nextInt(TOOL_TABLE.size)].name + "s"
        7 -> TOOL_TABLE[rnd.nextInt(TOOL_TABLE.size)].name.dropLast(1)
        8 -> randomToken(rnd)
        else -> randomString(rnd, 20)
    }

private fun genToolArgs(rnd: Random): Map<String, String> {
    val out = mutableMapOf<String, String>()
    val n = rnd.nextInt(0, 4)
    repeat(n) {
        val k = TOOL_ARG_KEYS[rnd.nextInt(TOOL_ARG_KEYS.size)]
        if (k !in out) out[k] = genToolArgValue(rnd)
    }
    return out
}

    @Test
    fun toolRandom() {
        val rnd = Random(42)
        repeat(300) {
            val name = genToolName(rnd)
            val args = genToolArgs(rnd)
            // toolByName: exact name match, first in catalog order.
            val expected = toolByNameOracle(name)
            val actual = AgentToolPolicy.toolByName(name)
            assertEquals(expected?.name, actual?.name)
            assertEquals(expected?.risk, actual?.risk)
            // approvalFor: READ -> ALLOW, WRITE/SEND -> NEED_USER,
            // unknown -> DENY; the arguments map is ignored.
            assertEquals(
                approvalOracle(name),
                AgentToolPolicy.approvalFor(name, args)
            )
        }
    }

    @Test
    fun toolJsonRandom() {
        val rnd = Random(7)
        repeat(300) {
            // The JSON view does not depend on inputs; the loop
            // re-derives it each time so a stateful drift would show.
            val tools = AgentToolPolicy.openaiToolsJson()
            assertEquals(TOOL_TABLE.size, tools.size)
            for ((i, entry) in tools.withIndex()) {
                val expected = TOOL_TABLE[i]
                assertEquals("function", entry["type"])
                @Suppress("UNCHECKED_CAST")
                val fn = entry["function"] as Map<String, Any?>
                assertEquals(expected.name, fn["name"])
                @Suppress("UNCHECKED_CAST")
                val params = fn["parameters"] as Map<String, Any?>
                assertEquals("object", params["type"])
                @Suppress("UNCHECKED_CAST")
                val props = params["properties"] as Map<String, Any?>
                assertEquals(expected.params.toSet(), props.keys)
                @Suppress("UNCHECKED_CAST")
                val required = params["required"] as List<String>
                assertEquals(expected.required, required)
            }
            assertEquals(toolJsonOracle(), toolJsonProd())
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // The nine caps pinned literally.
        assertEquals(12, AgentToolPolicy.MAX_TOOL_ROUNDS)
        assertEquals(24, AgentToolPolicy.MAX_HISTORY_MESSAGES)
        assertEquals(40, AgentToolPolicy.MAX_CHAT_HISTORY)
        assertEquals(20, AgentToolPolicy.MAX_SEARCH_HITS)
        assertEquals(6_000, AgentToolPolicy.MAX_TOOL_RESULT_CHARS)
        assertEquals(4_000, AgentToolPolicy.MAX_TEXT_SEND_CHARS)
        assertEquals(80, AgentToolPolicy.MAX_LIST_CHATS)
        assertEquals(4_000, AgentToolPolicy.MAX_DRAFT_CHARS)
        assertEquals(40, AgentToolPolicy.MAX_NICKNAME_CHARS)
        // The catalog holds 53 tools, in this exact order at the head.
        assertEquals(53, AgentToolPolicy.tools.size)
        assertEquals("list_chats", AgentToolPolicy.tools[0].name)
        assertEquals("delete_chat", AgentToolPolicy.tools[52].name)
        // toolByName: exact match only.
        assertEquals(Risk.READ, AgentToolPolicy.toolByName("list_chats")?.risk)
        assertEquals(Risk.SEND, AgentToolPolicy.toolByName("send_text_message")?.risk)
        assertEquals(Risk.WRITE, AgentToolPolicy.toolByName("delete_chat")?.risk)
        assertEquals(null, AgentToolPolicy.toolByName("list_chat"))
        assertEquals(null, AgentToolPolicy.toolByName("LIST_CHATS"))
        assertEquals(null, AgentToolPolicy.toolByName(""))
        // approvalFor: risk decides, arguments do not.
        assertEquals(Approval.ALLOW, AgentToolPolicy.approvalFor("list_chats", emptyMap()))
        assertEquals(Approval.ALLOW, AgentToolPolicy.approvalFor("rewrite_text", mapOf("text" to "x")))
        assertEquals(Approval.NEED_USER, AgentToolPolicy.approvalFor("send_text_message", emptyMap()))
        assertEquals(Approval.NEED_USER, AgentToolPolicy.approvalFor("delete_chat", mapOf("chatId" to "c1")))
        assertEquals(Approval.DENY, AgentToolPolicy.approvalFor("dump_host_sql", emptyMap()))
        assertEquals(Approval.DENY, AgentToolPolicy.approvalFor("", emptyMap()))
        // openaiToolsJson head/tail structure.
        val tools = AgentToolPolicy.openaiToolsJson()
        assertEquals(53, tools.size)
        @Suppress("UNCHECKED_CAST")
        val first = tools[0]["function"] as Map<String, Any?>
        assertEquals("list_chats", first["name"])
        @Suppress("UNCHECKED_CAST")
        val last = tools[52]["function"] as Map<String, Any?>
        assertEquals("delete_chat", last["name"])
    }
}
