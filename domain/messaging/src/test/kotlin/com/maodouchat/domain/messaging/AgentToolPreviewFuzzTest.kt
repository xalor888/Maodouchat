// Fuzz / random-oracle tests for AgentToolPreview (checklist line 931,
// 76th block). Test-only, zero production changes. The oracle is
// hand-written: plain string building with the exact templates
// read from the production file. preview is a 29-branch plain-template when over the tool name; missing keys read as "", bare keys as "null"; unknown names return null.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentToolPreviewFuzzTest {

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

// ---------- AgentToolPreview oracle ----------

// Missing key reads as "" (orEmpty); a bare args[key] interpolates
// as "null" when absent. Hand-written map walk, never args[].
private fun argOrEmptyOracle(args: Map<String, String>, key: String): String {
    for ((k, v) in args) {
        if (equalsOracle(k, key)) return v
    }
    return ""
}

private fun argOrNullOracle(args: Map<String, String>, key: String): String? {
    for ((k, v) in args) {
        if (equalsOracle(k, key)) return v
    }
    return null
}

// ifBlank on the TAKEN value: "   ".take(80) stays blank, so the
// fallback fires; a non-blank value passes through verbatim.
private fun ifBlankOracle(s: String, fallback: String): String =
    if (isBlankOracle(s)) fallback else s

private fun previewOracle(name: String, args: Map<String, String>): String? =
    when (name) {
        "send_text_message" ->
            "发到 " + takeOracle(argOrEmptyOracle(args, "chatId"), 24) + "：" + takeOracle(argOrEmptyOracle(args, "text"), 160)
        "update_chat" ->
            "改会话 " + takeOracle(argOrEmptyOracle(args, "chatId"), 24) + " pinned=" + argOrNullOracle(args, "pinned") + " muted=" + argOrNullOracle(args, "muted") + " archived=" + argOrNullOracle(args, "archived") + " unread=" + argOrNullOracle(args, "markedUnread")
        "set_draft" ->
            "草稿 " + takeOracle(argOrEmptyOracle(args, "chatId"), 24) + "：" + ifBlankOracle(takeOracle(argOrEmptyOracle(args, "text"), 80), "(清空)")
        "star_message" ->
            "星标 " + takeOracle(argOrEmptyOracle(args, "messageId"), 24) + " → " + argOrNullOracle(args, "starred")
        "set_contact_nickname" ->
            "备注 " + takeOracle(argOrEmptyOracle(args, "userId"), 24) + " → " + ifBlankOracle(takeOracle(argOrEmptyOracle(args, "nickname"), 40), "(清除)")
        "delete_local_message" ->
            "本机删除消息 " + takeOracle(argOrEmptyOracle(args, "messageId"), 24)
        "mark_notification_read" ->
            "通知已读 " + takeOracle(argOrEmptyOracle(args, "itemId"), 24)
        "revoke_message" -> "撤回 " + takeOracle(argOrEmptyOracle(args, "messageId"), 24)
        "react_to_message" ->
            "反应 " + argOrEmptyOracle(args, "emoji") + " → " + takeOracle(argOrEmptyOracle(args, "messageId"), 24)
        "pin_message" -> "置顶消息 " + takeOracle(argOrEmptyOracle(args, "messageId"), 24)
        "create_direct_chat" -> "开聊 " + takeOracle(argOrEmptyOracle(args, "userId"), 24)
        "create_group" -> "建群 " + takeOracle(argOrEmptyOracle(args, "name"), 40)
        "rename_group" -> "改群名 " + takeOracle(argOrEmptyOracle(args, "name"), 40)
        "add_group_members" -> "拉人进群 " + takeOracle(argOrEmptyOracle(args, "memberIds"), 80)
        "remove_group_member" -> "移出群 " + takeOracle(argOrEmptyOracle(args, "memberId"), 24)
        "mute_group_member" -> "禁言 " + takeOracle(argOrEmptyOracle(args, "memberId"), 24)
        "delete_chat" -> "删除会话 " + takeOracle(argOrEmptyOracle(args, "chatId"), 24)
        "send_friend_request" -> "好友申请 " + takeOracle(argOrEmptyOracle(args, "userId"), 24)
        "accept_friend_request" -> "同意好友 " + takeOracle(argOrEmptyOracle(args, "requestId"), 24)
        "reject_friend_request" -> "拒绝好友 " + takeOracle(argOrEmptyOracle(args, "requestId"), 24)
        "create_text_post" -> "发动态：" + takeOracle(argOrEmptyOracle(args, "text"), 80)
        "like_post" ->
            "赞动态 " + takeOracle(argOrEmptyOracle(args, "postId"), 24) + " liked=" + argOrNullOracle(args, "liked")
        "comment_on_post" -> "评论动态 " + takeOracle(argOrEmptyOracle(args, "postId"), 24)
        "delete_post" -> "删除动态 " + takeOracle(argOrEmptyOracle(args, "postId"), 24)
        "block_user" -> "拉黑 " + takeOracle(argOrEmptyOracle(args, "userId"), 24)
        "unblock_user" -> "取消拉黑 " + takeOracle(argOrEmptyOracle(args, "userId"), 24)
        "create_local_task" ->
            "任务「" + takeOracle(argOrEmptyOracle(args, "title"), 80) + "」→ " + takeOracle(argOrEmptyOracle(args, "chatId"), 24)
        "complete_local_task" ->
            "待办 " + takeOracle(argOrEmptyOracle(args, "taskId"), 24) + " completed=" + argOrNullOracle(args, "completed")
        "delete_local_task" -> "删除待办 " + takeOracle(argOrEmptyOracle(args, "taskId"), 24)
        else -> null
    }

private val PREVIEW_KNOWN_NAMES = listOf(
    "send_text_message", "update_chat", "set_draft", "star_message",
    "set_contact_nickname", "delete_local_message",
    "mark_notification_read", "revoke_message", "react_to_message",
    "pin_message", "create_direct_chat", "create_group", "rename_group",
    "add_group_members", "remove_group_member", "mute_group_member",
    "delete_chat", "send_friend_request", "accept_friend_request",
    "reject_friend_request", "create_text_post", "like_post",
    "comment_on_post", "delete_post", "block_user", "unblock_user",
    "create_local_task", "complete_local_task", "delete_local_task"
)

private val PREVIEW_ARG_KEYS = listOf(
    "chatId", "text", "pinned", "muted", "archived", "markedUnread",
    "messageId", "starred", "userId", "nickname", "itemId", "emoji",
    "name", "memberIds", "memberId", "postId", "liked", "title",
    "taskId", "completed", "requestId", "zzz", "other"
)

private fun genPreviewName(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> PREVIEW_KNOWN_NAMES[rnd.nextInt(PREVIEW_KNOWN_NAMES.size)]
        6 -> PREVIEW_KNOWN_NAMES[rnd.nextInt(PREVIEW_KNOWN_NAMES.size)] + "s"
        7 -> PREVIEW_KNOWN_NAMES[rnd.nextInt(PREVIEW_KNOWN_NAMES.size)].dropLast(1)
        8 -> randomToken(rnd)
        else -> randomString(rnd, 20)
    }

private fun genPreviewArgValue(rnd: Random): String =
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

private fun genPreviewArgs(rnd: Random): Map<String, String> {
    val out = mutableMapOf<String, String>()
    val n = rnd.nextInt(0, 6)
    repeat(n) {
        val k = PREVIEW_ARG_KEYS[rnd.nextInt(PREVIEW_ARG_KEYS.size)]
        if (k !in out) out[k] = genPreviewArgValue(rnd)
    }
    return out
}

    @Test
    fun previewRandom() {
        val rnd = Random(42)
        repeat(300) {
            val name = genPreviewName(rnd)
            val args = genPreviewArgs(rnd)
            assertEquals(
                previewOracle(name, args),
                AgentToolPreview.preview(name, args)
            )
        }
    }

    @Test
    fun previewNamesRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Name-heavy: known names, near-misses and unknowns, each
            // with a full arg map so every branch template is hit.
            val name = genPreviewName(rnd)
            val args = mapOf(
                "chatId" to "c1", "text" to "hello world", "pinned" to "true",
                "muted" to "false", "archived" to "false", "markedUnread" to "true",
                "messageId" to "m1", "starred" to "true", "userId" to "u9",
                "nickname" to "阿明", "itemId" to "n7", "emoji" to "👍",
                "name" to "周末出游", "memberIds" to "u1,u2", "memberId" to "u3",
                "postId" to "p3", "liked" to "true", "title" to "买菜",
                "taskId" to "t5", "completed" to "true", "requestId" to "r2"
            )
            assertEquals(
                previewOracle(name, args),
                AgentToolPreview.preview(name, args)
            )
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // take() cuts at exactly N UTF-16 units.
        assertEquals(
            "发到 " + "a".repeat(24) + "：" + "b".repeat(160),
            AgentToolPreview.preview(
                "send_text_message",
                mapOf("chatId" to "a".repeat(30), "text" to "b".repeat(200))
            )
        )
        assertEquals(
            "发到 c1：hello",
            AgentToolPreview.preview(
                "send_text_message",
                mapOf("chatId" to "c1", "text" to "hello")
            )
        )
        // A missing key reads as ""; a missing bare key reads as "null".
        assertEquals(
            "发到 ：",
            AgentToolPreview.preview("send_text_message", emptyMap())
        )
        assertEquals(
            "改会话 c1 pinned=null muted=null archived=null unread=null",
            AgentToolPreview.preview("update_chat", mapOf("chatId" to "c1"))
        )
        // ifBlank fires on whitespace-only taken text, not on a
        // non-blank value, and the take happens before the blank check.
        assertEquals(
            "草稿 c1：(清空)",
            AgentToolPreview.preview("set_draft", mapOf("chatId" to "c1", "text" to "   "))
        )
        assertEquals(
            "草稿 c1：(清空)",
            AgentToolPreview.preview("set_draft", mapOf("chatId" to "c1", "text" to ""))
        )
        assertEquals(
            "备注 u1 → (清除)",
            AgentToolPreview.preview("set_contact_nickname", mapOf("userId" to "u1", "nickname" to ""))
        )
        assertEquals(
            "备注 u1 → 阿明",
            AgentToolPreview.preview("set_contact_nickname", mapOf("userId" to "u1", "nickname" to "阿明"))
        )
        // Unknown names return null, including near-misses and case.
        assertEquals(null, AgentToolPreview.preview("execute_sql", mapOf("chatId" to "c1")))
        assertEquals(null, AgentToolPreview.preview("send_text_messages", mapOf("chatId" to "c1")))
        assertEquals(null, AgentToolPreview.preview("SEND_TEXT_MESSAGE", mapOf("chatId" to "c1")))
        assertEquals(null, AgentToolPreview.preview("", emptyMap()))
        // Emoji passes through take() uncut at short lengths.
        assertEquals(
            "反应 👍 → m1",
            AgentToolPreview.preview("react_to_message", mapOf("emoji" to "👍", "messageId" to "m1"))
        )
        assertEquals(
            "任务「买菜」→ c1",
            AgentToolPreview.preview("create_local_task", mapOf("title" to "买菜", "chatId" to "c1"))
        )
        assertEquals(
            "赞动态 p3 liked=true",
            AgentToolPreview.preview("like_post", mapOf("postId" to "p3", "liked" to "true"))
        )
    }
}
