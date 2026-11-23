// Fuzz / random-oracle tests for WidgetRowPolicy (checklist line 931,
// 112th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. WidgetRowPolicy: widget chat titles, previews, and row building under locks.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.User

class WidgetRowPolicyFuzzTest {

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

// ---------- extra hand-written helpers ----------

private fun isWhitespaceOracle(c: Char): Boolean = c.code in BLANK_UNITS_ORACLE

private fun isLetterOrDigitOracle(c: Char): Boolean =
    Character.isLetterOrDigit(c.code)

private fun startsWithOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (s[i] != prefix[i]) return false
    }
    return true
}

private fun startsWithIgnoreCaseOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (!charsEqualIgnoreCaseOracle(s[i], prefix[i])) return false
    }
    return true
}

private fun dropOracle(s: String, n: Int): String {
    if (n <= 0) return s
    if (n >= s.length) return ""
    return s.substring(n)
}

private fun removePrefixOracle(s: String, prefix: String): String =
    if (startsWithOracle(s, prefix)) dropOracle(s, prefix.length) else s

// trimStart() with no args: strip leading Kotlin-whitespace units.
private fun trimStartBlankOracle(s: String): String {
    var i = 0
    while (i < s.length && s[i].code in BLANK_UNITS_ORACLE) i++
    return s.substring(i)
}

// lineSequence(): split on "\r\n" | "\n" | "\r", terminators dropped,
// a trailing terminator leaves a final empty line.
private fun splitLinesOracle(s: String): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\n') {
            out.add(s.substring(start, i))
            i++
            start = i
        } else if (c == '\r') {
            out.add(s.substring(start, i))
            i++
            if (i < s.length && s[i] == '\n') i++
            start = i
        } else {
            i++
        }
    }
    out.add(s.substring(start, s.length))
    return out
}

private fun indexOfCharOracle(s: String, ch: Char): Int {
    for (i in s.indices) {
        if (s[i] == ch) return i
    }
    return -1
}

// Java String.hashCode(): h = 31*h + unit, Int arithmetic wraps.
private fun hashCodeOracle(s: String): Int {
    var h = 0
    for (c in s) h = 31 * h + c.code
    return h
}

// java.lang.Long.parseLong(s, radix), reimplemented with the same
// JDK primitive (Character.digit) and the same negative-accumulator
// overflow algorithm, so the exact Long.MIN_VALUE edge parses.
private fun parseLongRadixOracle(s: String, radix: Int): Long? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (i >= s.length) return null
    }
    var acc = 0L
    val limit = if (neg) Long.MIN_VALUE else -Long.MAX_VALUE
    val multmin = limit / radix
    while (i < s.length) {
        val d = Character.digit(s[i].code, radix)
        if (d < 0) return null
        if (acc < multmin) return null
        acc *= radix
        if (acc < limit + d) return null
        acc -= d
        i++
    }
    return if (neg) acc else -acc
}

// java.lang.Integer.parseInt(s), same algorithm on Int.
private fun parseIntOracle(s: String): Int? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (i >= s.length) return null
    }
    var acc = 0
    val limit = if (neg) Int.MIN_VALUE else -Int.MAX_VALUE
    val multmin = limit / 10
    while (i < s.length) {
        val d = Character.digit(s[i].code, 10)
        if (d < 0) return null
        if (acc < multmin) return null
        acc *= 10
        if (acc < limit + d) return null
        acc -= d
        i++
    }
    return if (neg) acc else -acc
}

private fun isDigitOracle(c: Char): Boolean = Character.isDigit(c.code)


// ---------- extra helpers, block 80 ----------

// Per-code-point simple case mapping, mirroring lowercaseOracle.
// Agrees with String.uppercase() (full mapping) on the case-safe
// alphabet; never calls uppercase().
private fun uppercaseOracle(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        sb.append(Character.toChars(Character.toUpperCase(cp)))
        i += Character.charCount(cp)
    }
    return sb.toString()
}

// String.substringBefore(delimiter): up to the first occurrence,
// or the whole string when absent.
private fun substringBeforeOracle(s: String, delim: Char): String {
    val idx = indexOfCharOracle(s, delim)
    return if (idx < 0) s else s.substring(0, idx)
}

// ---------- block-112 common oracle helpers ----------

// (No shared production-type helpers: each cluster's oracle below
// is self-contained. The wire codecs live in Parse112.kt.)

private fun trimStartOracle(s: String): String {
    var lo = 0
    while (lo < s.length && s[lo].code in BLANK_UNITS_ORACLE) lo++
    return s.substring(lo)
}

private fun indexOfStrOracle(s: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var i = 0
    while (i + needle.length <= s.length) {
        var ok = true
        for (j in needle.indices) if (s[i + j] != needle[j]) { ok = false; break }
        if (ok) return i
        i++
    }
    return -1
}

private fun lastIndexOfStrOracle(s: String, needle: String): Int {
    if (needle.isEmpty()) return s.length
    var i = s.length - needle.length
    while (i >= 0) {
        var ok = true
        for (j in needle.indices) if (s[i + j] != needle[j]) { ok = false; break }
        if (ok) return i
        i--
    }
    return -1
}

private fun substringBeforeStrOracle(s: String, needle: String): String {
    val idx = indexOfStrOracle(s, needle)
    return if (idx < 0) s else s.substring(0, idx)
}

private fun genTextOf(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    while (sb.length < len) sb.append(randomString(rnd, 64))
    return if (sb.length > len) sb.toString().substring(0, len) else sb.toString()
}

// ---------- WidgetRowPolicy oracle ----------

// Hand-written reimplementation: group chats use the group name,
// 1:1 chats use the first non-owner participant's display name
// (blank display name falls back to the raw name); previews strip
// a "<meta>" tail, trim, and cap at 40 units with an ellipsis;
// rows skip blank ids and disallowed chats, cap at maxRows, and
// force the app name / generic text / zero badge under a lock.
private fun widgetChatTitleOracle(chat: Chat, ownerUserId: String): String? {
    if (chat.isGroup) return chat.groupName?.takeIf { !isBlankOracle(it) }
    return chat.participants
        .firstOrNull { it.id != ownerUserId }
        ?.let { val d = it.displayName; if (isBlankOracle(d)) it.name else d }
        ?: chat.groupName?.takeIf { !isBlankOracle(it) }
}

private fun widgetPreviewOfOracle(raw: String, type: MessageType, attachmentLabel: String): String {
    if (type != MessageType.TEXT && type != MessageType.MARKDOWN) return attachmentLabel
    val text = trimOracle(substringBeforeStrOracle(raw, "<meta>"))
    return if (text.length <= 40) text else takeOracle(text, 40) + "…"
}

private fun buildWidgetRowsOracle(
    inputs: List<WidgetChatInput>,
    ownerUserId: String,
    showUnreadBadge: Boolean,
    appLockOn: Boolean,
    labels: WidgetRowLabels,
    maxRows: Int,
    timeLabel: (Long) -> String
): WidgetRowsResult {
    val rows = ArrayList<WidgetRow>(coerceAtLeastOracle(maxRows, 0))
    var totalUnread = 0
    for (input in inputs) {
        if (rows.size >= maxRows) break
        val chat = input.chat
        if (isBlankOracle(chat.id)) continue
        if (!input.previewAllowed) continue
        val pinLocked = input.isLocked
        val title = if (appLockOn || pinLocked) {
            labels.appName
        } else {
            widgetChatTitleOracle(chat, ownerUserId) ?: takeOracle(chat.id, 12)
        }
        val subtitle = when {
            pinLocked -> labels.lockedPreview
            appLockOn -> labels.genericPreview
            else -> widgetPreviewOfOracle(chat.lastMessage, chat.lastMessageType, labels.attachmentLabel)
        }
        val unread = if (appLockOn || !showUnreadBadge) 0 else chat.unreadCount
        totalUnread += unread
        rows += WidgetRow(
            chatId = chat.id,
            title = title,
            subtitle = subtitle,
            timeLabel = timeLabel(chat.lastMessageTime),
            unread = unread
        )
    }
    return WidgetRowsResult(rows, totalUnread)
}

private val WRP_ID_POOL112 = arrayOf(
    "", "c1", "chat-1", "a", "x".repeat(20), "  ", "chat:1",
    "üñï", "emoji \uD83D\uDE00"
)

private val WRP_NAME_POOL112 = arrayOf<String?>(
    null, "", " ", "Group", "  Group  ", "A", "x".repeat(30),
    "üñï", "emoji \uD83D\uDE00", "\u00A0"
)

private val WRP_MSG_POOL112 = arrayOf(
    "", "hello", "x".repeat(39), "x".repeat(40), "x".repeat(41),
    "x".repeat(100), "hello <meta>{\"a\":1}", "<meta>tail",
    "text<meta>more", "üñï".repeat(20), "emoji \uD83D\uDE00".repeat(30),
    "  padded  ", "line1\nline2"
)

private val WRP_LABEL_POOL112 = arrayOf(
    "App", "Locked", "Generic", "Attachment", "", "x".repeat(20)
)

private val WRP_INT_POOL112 = intArrayOf(
    Int.MIN_VALUE, -100, -1, 0, 1, 2, 3, 4, 5, 100, Int.MAX_VALUE
)

private val WRP_MAXROWS_POOL112 = intArrayOf(
    -5, -1, 0, 1, 2, 3, 5, 10, 100
)

private val WRP_LONG_POOL112 = longArrayOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -2L, -1L, 0L, 1L, 2L,
    1000L, Long.MAX_VALUE - 1, Long.MAX_VALUE
)

private fun genWrpUser(rnd: Random): User {
    val id = when (rnd.nextInt(4)) {
        0 -> WRP_ID_POOL112[rnd.nextInt(WRP_ID_POOL112.size)]
        else -> randomString(rnd, 12)
    }
    val name = when (rnd.nextInt(4)) {
        0 -> WRP_NAME_POOL112[rnd.nextInt(WRP_NAME_POOL112.size)] ?: ""
        else -> randomString(rnd, 12)
    }
    val nickname = when (rnd.nextInt(4)) {
        0 -> null
        1 -> WRP_NAME_POOL112[rnd.nextInt(WRP_NAME_POOL112.size)]
        else -> randomString(rnd, 12)
    }
    return User(id = id, name = name, nickname = nickname)
}

private fun genWrpChat(rnd: Random): Chat {
    val id = when (rnd.nextInt(4)) {
        0 -> WRP_ID_POOL112[rnd.nextInt(WRP_ID_POOL112.size)]
        else -> randomString(rnd, 12)
    }
    val isGroup = rnd.nextBoolean()
    val groupName = when (rnd.nextInt(4)) {
        0 -> WRP_NAME_POOL112[rnd.nextInt(WRP_NAME_POOL112.size)]
        else -> randomString(rnd, 12)
    }
    val nParts = rnd.nextInt(0, 4)
    val parts = ArrayList<User>(nParts)
    repeat(nParts) { parts += genWrpUser(rnd) }
    val lastMessage = when (rnd.nextInt(4)) {
        0 -> WRP_MSG_POOL112[rnd.nextInt(WRP_MSG_POOL112.size)]
        else -> randomString(rnd, 60)
    }
    val type = MessageType.entries[rnd.nextInt(MessageType.entries.size)]
    return Chat(
        id = id, participants = parts, lastMessage = lastMessage,
        lastMessageType = type, lastMessageTime = genWrpLong(rnd),
        unreadCount = genWrpInt(rnd), isGroup = isGroup,
        groupName = groupName
    )
}

private fun genWrpChatEdge(rnd: Random): Chat = Chat(
    id = WRP_ID_POOL112[rnd.nextInt(WRP_ID_POOL112.size)],
    participants = listOf(genWrpUser(rnd)),
    lastMessage = WRP_MSG_POOL112[rnd.nextInt(WRP_MSG_POOL112.size)],
    lastMessageType = MessageType.entries[rnd.nextInt(MessageType.entries.size)],
    lastMessageTime = WRP_LONG_POOL112[rnd.nextInt(WRP_LONG_POOL112.size)],
    unreadCount = WRP_INT_POOL112[rnd.nextInt(WRP_INT_POOL112.size)],
    isGroup = rnd.nextBoolean(),
    groupName = WRP_NAME_POOL112[rnd.nextInt(WRP_NAME_POOL112.size)]
)

private fun genWrpLong(rnd: Random): Long =
    if (rnd.nextInt(3) == 0) WRP_LONG_POOL112[rnd.nextInt(WRP_LONG_POOL112.size)]
    else rnd.nextLong()

private fun genWrpInt(rnd: Random): Int =
    if (rnd.nextInt(3) == 0) WRP_INT_POOL112[rnd.nextInt(WRP_INT_POOL112.size)]
    else rnd.nextInt()

private fun genWrpLabels(rnd: Random): WidgetRowLabels =
    WidgetRowLabels(
        appName = WRP_LABEL_POOL112[rnd.nextInt(WRP_LABEL_POOL112.size)],
        genericPreview = WRP_LABEL_POOL112[rnd.nextInt(WRP_LABEL_POOL112.size)],
        lockedPreview = WRP_LABEL_POOL112[rnd.nextInt(WRP_LABEL_POOL112.size)],
        attachmentLabel = WRP_LABEL_POOL112[rnd.nextInt(WRP_LABEL_POOL112.size)]
    )

private fun genWrpInputs(rnd: Random): List<WidgetChatInput> {
    val n = rnd.nextInt(0, 6)
    val inputs = ArrayList<WidgetChatInput>(n)
    repeat(n) {
        inputs += WidgetChatInput(
            chat = genWrpChat(rnd),
            previewAllowed = rnd.nextBoolean(),
            isLocked = rnd.nextBoolean()
        )
    }
    return inputs
}

private fun genWrpInputsEdge(rnd: Random): List<WidgetChatInput> {
    val n = rnd.nextInt(0, 6)
    val inputs = ArrayList<WidgetChatInput>(n)
    repeat(n) {
        inputs += WidgetChatInput(
            chat = genWrpChatEdge(rnd),
            previewAllowed = rnd.nextBoolean(),
            isLocked = rnd.nextBoolean()
        )
    }
    return inputs
}

    @Test
    fun widgetRowPolicyRandom() {
        val rnd = Random(42)
        repeat(300) {
            val chat = genWrpChat(rnd)
            val owner = when (rnd.nextInt(4)) {
                0 -> WRP_ID_POOL112[rnd.nextInt(WRP_ID_POOL112.size)]
                else -> randomString(rnd, 12)
            }
            assertEquals(
                widgetChatTitleOracle(chat, owner),
                widgetChatTitle(chat, owner)
            )
            val raw = when (rnd.nextInt(4)) {
                0 -> WRP_MSG_POOL112[rnd.nextInt(WRP_MSG_POOL112.size)]
                else -> randomString(rnd, 60)
            }
            val type = MessageType.entries[rnd.nextInt(MessageType.entries.size)]
            val label = WRP_LABEL_POOL112[rnd.nextInt(WRP_LABEL_POOL112.size)]
            assertEquals(
                widgetPreviewOfOracle(raw, type, label),
                widgetPreviewOf(raw, type, label)
            )
            val inputs = genWrpInputs(rnd)
            val showBadge = rnd.nextBoolean()
            val appLock = rnd.nextBoolean()
            val labels = genWrpLabels(rnd)
            val maxRows = WRP_MAXROWS_POOL112[rnd.nextInt(WRP_MAXROWS_POOL112.size)]
            val timeLabel: (Long) -> String = { "t$it" }
            assertEquals(
                buildWidgetRowsOracle(inputs, owner, showBadge, appLock, labels, maxRows, timeLabel),
                buildWidgetRows(inputs, owner, showBadge, appLock, labels, maxRows, timeLabel)
            )
        }
    }

    @Test
    fun widgetRowPolicyEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Pool-only legs: blank ids, group names and long previews dominate.
            val chat = genWrpChatEdge(rnd)
            val owner = WRP_ID_POOL112[rnd.nextInt(WRP_ID_POOL112.size)]
            assertEquals(
                widgetChatTitleOracle(chat, owner),
                widgetChatTitle(chat, owner)
            )
            val raw = WRP_MSG_POOL112[rnd.nextInt(WRP_MSG_POOL112.size)]
            val type = MessageType.entries[rnd.nextInt(MessageType.entries.size)]
            val label = WRP_LABEL_POOL112[rnd.nextInt(WRP_LABEL_POOL112.size)]
            assertEquals(
                widgetPreviewOfOracle(raw, type, label),
                widgetPreviewOf(raw, type, label)
            )
            val inputs = genWrpInputsEdge(rnd)
            val showBadge = rnd.nextBoolean()
            val appLock = rnd.nextBoolean()
            val labels = genWrpLabels(rnd)
            val maxRows = WRP_MAXROWS_POOL112[rnd.nextInt(WRP_MAXROWS_POOL112.size)]
            val timeLabel: (Long) -> String = { "t$it" }
            assertEquals(
                buildWidgetRowsOracle(inputs, owner, showBadge, appLock, labels, maxRows, timeLabel),
                buildWidgetRows(inputs, owner, showBadge, appLock, labels, maxRows, timeLabel)
            )
        }
    }

    @Test
    fun widgetRowPolicyPinned() {
        assertEquals(
            "G",
            widgetChatTitle(Chat(id = "c1", isGroup = true, groupName = "G"), "u1")
        )
        assertEquals(
            null,
            widgetChatTitle(Chat(id = "c1", isGroup = true, groupName = null), "u1")
        )
        assertEquals(
            null,
            widgetChatTitle(Chat(id = "c1", isGroup = true, groupName = "  "), "u1")
        )
        assertEquals(
            "Bob",
            widgetChatTitle(
                Chat(id = "c1", participants = listOf(User(id = "u2", name = "Bob"))),
                "u1"
            )
        )
        assertEquals(
            "u2",
            widgetChatTitle(
                Chat(id = "c1", participants = listOf(User(id = "u2", name = ""))),
                "u1"
            )
        )
        assertEquals(
            "G",
            widgetChatTitle(
                Chat(id = "c1", participants = listOf(User(id = "u1", name = "Me")), groupName = "G"),
                "u1"
            )
        )
        assertEquals("hello", widgetPreviewOf("hello", MessageType.TEXT, "[附件]"))
        assertEquals("x".repeat(40) + "…", widgetPreviewOf("x".repeat(50), MessageType.TEXT, "[附件]"))
        assertEquals("x".repeat(40), widgetPreviewOf("x".repeat(40), MessageType.TEXT, "[附件]"))
        assertEquals("[附件]", widgetPreviewOf("hello", MessageType.IMAGE, "[附件]"))
        assertEquals("hi", widgetPreviewOf("hi<meta>junk", MessageType.TEXT, "[附件]"))
        assertEquals("", widgetPreviewOf("", MessageType.TEXT, "[附件]"))
        val labels = WidgetRowLabels(
            appName = "App", genericPreview = "Generic",
            lockedPreview = "Locked", attachmentLabel = "[附件]"
        )
        val timeLabel: (Long) -> String = { "t$it" }
        val two = listOf(
            WidgetChatInput(
                chat = Chat(id = "c1", lastMessage = "hello", lastMessageTime = 0L, unreadCount = 3),
                previewAllowed = true, isLocked = false
            ),
            WidgetChatInput(
                chat = Chat(id = "c2", lastMessage = "world", lastMessageTime = 0L, unreadCount = 5),
                previewAllowed = true, isLocked = false
            )
        )
        assertEquals(
            WidgetRowsResult(
                listOf(
                    WidgetRow("c1", "c1", "hello", "t0", 3),
                    WidgetRow("c2", "c2", "world", "t0", 5)
                ),
                8
            ),
            buildWidgetRows(two, "u1", true, false, labels, 10, timeLabel)
        )
        assertEquals(
            WidgetRowsResult(
                listOf(WidgetRow("c1", "c1", "hello", "t0", 3)),
                3
            ),
            buildWidgetRows(two, "u1", true, false, labels, 1, timeLabel)
        )
        assertEquals(
            WidgetRowsResult(emptyList(), 0),
            buildWidgetRows(
                listOf(WidgetChatInput(Chat(id = "c1"), previewAllowed = false, isLocked = false)),
                "u1", true, false, labels, 10, timeLabel
            )
        )
        assertEquals(
            WidgetRowsResult(
                listOf(
                    WidgetRow("c1", "App", "Generic", "t0", 0),
                    WidgetRow("c2", "App", "Generic", "t0", 0)
                ),
                0
            ),
            buildWidgetRows(two, "u1", true, true, labels, 10, timeLabel)
        )
        assertEquals(
            WidgetRowsResult(
                listOf(WidgetRow("c1", "App", "Locked", "t0", 3)),
                3
            ),
            buildWidgetRows(
                listOf(WidgetChatInput(
                    chat = Chat(id = "c1", lastMessage = "hello", lastMessageTime = 0L, unreadCount = 3),
                    previewAllowed = true, isLocked = true
                )),
                "u1", true, false, labels, 10, timeLabel
            )
        )
        assertEquals(
            WidgetRowsResult(
                listOf(
                    WidgetRow("c1", "c1", "hello", "t0", 0),
                    WidgetRow("c2", "c2", "world", "t0", 0)
                ),
                0
            ),
            buildWidgetRows(two, "u1", false, false, labels, 10, timeLabel)
        )
    }
}
