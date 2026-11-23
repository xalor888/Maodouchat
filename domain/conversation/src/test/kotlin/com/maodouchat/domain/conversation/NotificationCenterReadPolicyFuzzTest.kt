// Fuzz / random-oracle tests for NotificationCenterReadPolicy (checklist line 931,
// 106th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. NotificationCenterReadPolicy: notification-center row matching.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationCenterReadPolicyFuzzTest {

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

// ---------- block-106 common oracle helpers ----------

// Hand-written List.distinct: first occurrence wins, order kept.
// Never distinct().
private fun <T> distinctOracle(list: List<T>): List<T> {
    val out = ArrayList<T>()
    for (e in list) if (e !in out) out.add(e)
    return out
}

// Hand-written List.take: first n elements, never take().
private fun <T> takeListOracle(list: List<T>, n: Int): List<T> {
    if (n <= 0) return emptyList()
    if (n >= list.size) return list.toList()
    val out = ArrayList<T>(n)
    for (i in 0 until n) out.add(list[i])
    return out
}

// Hand-written String?.isNullOrBlank: null counts as blank.
private fun isNullOrBlankOracle(s: String?): Boolean =
    s == null || isBlankOracle(s)

// Nullable-int codec for the differential wire format.
private fun encNullableInt(v: Int?): String = v?.toString() ?: "N"

// Hand-written String.lastIndexOf(needle) over UTF-16 units.
// Never lastIndexOf().
private fun lastIndexOfOracle(s: String, needle: String): Int {
    if (needle.isEmpty() || needle.length > s.length) return -1
    var i = s.length - needle.length
    while (i >= 0) {
        var ok = true
        for (j in needle.indices) {
            if (s[i + j] != needle[j]) { ok = false; break }
        }
        if (ok) return i
        i--
    }
    return -1
}

// Hand-written String.substring(startIndex): units from start to end.
// Never substring().
private fun substringFromOracle(s: String, start: Int): String {
    val sb = StringBuilder()
    var i = start
    while (i < s.length) { sb.append(s[i]); i++ }
    return sb.toString()
}

// Hand-written per-unit Char.isDigit filter + take: keeps only units
// where Character.isDigit holds (astral mathematical digits are two
// non-digit surrogate halves; superscripts are not digits), then
// takes the first n units. Never filter { it.isDigit() } / take().
private fun filterDigitsTakeOracle(s: String, n: Int): String {
    val sb = StringBuilder()
    var kept = 0
    for (c in s) {
        if (kept >= n) break
        if (isDigitOracle(c)) { sb.append(c); kept++ }
    }
    return sb.toString()
}

// Hand-written per-unit all-digits check. Never all { it.isDigit() }.
private fun allDigitsOracle(s: String): Boolean {
    for (c in s) if (!isDigitOracle(c)) return false
    return true
}

// Hand-written equals(other, ignoreCase = true): per-unit fold,
// exactly as regionMatches(ignoreCase = true) does it.
private fun equalsIgnoreCaseOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        if (!charsEqualIgnoreCaseOracle(a[i], b[i])) return false
    }
    return true
}


// ---------- NotificationCenterReadPolicy oracle ----------

// Hand-written reimplementation of isChatMessageItem /
// belongsToChat / referencesMessage. The message-row match is
// msg_<chatId> merge keys, maodouchat:chat:<chatId> deeplinks, or
// an extra chatId with a msg_ mergeKey prefix -- the prefix
// requirement keeps ai_tasks_<chatId> out of the message rows.
// referencesMessage uses the LAST "_m_" anchor in a msg_ item id
// (chatIds may contain underscores, so the first '_' split is
// wrong -- the 8.46 fix); idx > 0 is required.
private fun isChatMessageItemOracle(
    chatId: String,
    mergeKey: String,
    deeplink: String?,
    extraChatId: String?
): Boolean {
    if (isBlankOracle(chatId)) return false
    if (mergeKey == "msg_$chatId") return true
    if (deeplink == "maodouchat:chat:$chatId") return true
    if (extraChatId == chatId && startsWithOracle(mergeKey, "msg_")) return true
    return false
}

private fun belongsToChatOracle(
    chatId: String,
    mergeKey: String,
    deeplink: String?,
    extraChatId: String?
): Boolean {
    if (isBlankOracle(chatId)) return false
    if (isChatMessageItemOracle(chatId, mergeKey, deeplink, extraChatId)) return true
    if (mergeKey == "ai_tasks_$chatId") return true
    if (deeplink == "maodouchat:ai_tasks:$chatId") return true
    if (extraChatId == chatId) return true
    return false
}

private fun referencesMessageOracle(
    messageId: String,
    itemId: String,
    extraMessageId: String?
): Boolean {
    if (isBlankOracle(messageId)) return false
    if (extraMessageId == messageId) return true
    if (startsWithOracle(itemId, "msg_")) {
        val idx = lastIndexOfOracle(itemId, "_m_")
        if (idx > 0) {
            val embedded = substringFromOracle(itemId, idx + 1)
            if (embedded == messageId) return true
        }
    }
    return false
}

private val NCR_CHAT_POOL106 = arrayOf(
    "", " ", "c1", "chat-1", "c_uuid", "a_b", "m_uuid", "msg_c1",
    "ai_tasks_c1", "chat_1_2", "x",
)

private val NCR_MERGE_POOL106 = arrayOf(
    "", " ", "msg_c1", "msg_", "msg_c1_extra", "msg_c1_m_m1",
    "ai_tasks_c1", "ai_tasks_", "other", "msg_chat-1", "msg_a_b",
    "msg_c_uuid", "MSG_C1",
)

private val NCR_DEEPLINK_POOL106: Array<String?> = arrayOf(
    null, "", "maodouchat:chat:c1", "maodouchat:ai_tasks:c1",
    "maodouchat:chat:", "maodouchat:chat:chat-1", "other",
    "maodouchat:chat:c_uuid", "MAODOUCHAT:CHAT:C1",
)

private val NCR_EXTRA_CHAT_POOL106: Array<String?> = arrayOf(
    null, "", "c1", "other", "chat-1", "c_uuid", "a_b", "m_uuid",
)

private val NCR_MSG_POOL106 = arrayOf(
    "", " ", "m1", "m_uuid", "m_1", "x", "m1_extra", "m_c1_m1",
)

private val NCR_ITEM_POOL106 = arrayOf(
    "", " ", "msg_c1_m1", "msg_c1_m_m1", "msg_a_b_m_x", "msg_",
    "msg_c1", "other_m_1", "msg_c1_m1_m2", "msg_m_x", "msg_c1_m_",
    "xmsg_c1_m1", "msg_c1_m1 ", "msg__m_m1",
)

private val NCR_EXTRA_MSG_POOL106: Array<String?> = arrayOf(
    null, "", "m1", "other", "m_uuid", "m_1", "x",
)

private fun genNcrChat(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> NCR_CHAT_POOL106[rnd.nextInt(NCR_CHAT_POOL106.size)]
        6 -> randomToken(rnd)
        else -> randomString(rnd, 12)
    }

private fun genNcrMerge(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> NCR_MERGE_POOL106[rnd.nextInt(NCR_MERGE_POOL106.size)]
        6 -> randomToken(rnd)
        else -> randomString(rnd, 16)
    }

private fun genNcrDeeplink(rnd: Random): String? =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> NCR_DEEPLINK_POOL106[rnd.nextInt(NCR_DEEPLINK_POOL106.size)]
        6 -> randomToken(rnd)
        else -> randomString(rnd, 16)
    }

private fun genNcrExtraChat(rnd: Random): String? =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> NCR_EXTRA_CHAT_POOL106[rnd.nextInt(NCR_EXTRA_CHAT_POOL106.size)]
        6 -> randomToken(rnd)
        else -> randomString(rnd, 12)
    }

private fun genNcrMsg(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> NCR_MSG_POOL106[rnd.nextInt(NCR_MSG_POOL106.size)]
        6 -> randomToken(rnd)
        else -> randomString(rnd, 12)
    }

private fun genNcrItem(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> NCR_ITEM_POOL106[rnd.nextInt(NCR_ITEM_POOL106.size)]
        6 -> randomToken(rnd)
        else -> randomString(rnd, 16)
    }

private fun genNcrExtraMsg(rnd: Random): String? =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> NCR_EXTRA_MSG_POOL106[rnd.nextInt(NCR_EXTRA_MSG_POOL106.size)]
        6 -> randomToken(rnd)
        else -> randomString(rnd, 12)
    }

    @Test
    fun notificationCenterReadPolicyRandom() {
        val rnd = Random(42)
        repeat(300) {
            val chatId = genNcrChat(rnd)
            val mergeKey = genNcrMerge(rnd)
            val deeplink = genNcrDeeplink(rnd)
            val extraChat = genNcrExtraChat(rnd)
            val messageId = genNcrMsg(rnd)
            val itemId = genNcrItem(rnd)
            val extraMsg = genNcrExtraMsg(rnd)
            assertEquals(isChatMessageItemOracle(chatId, mergeKey, deeplink, extraChat), NotificationCenterReadPolicy.isChatMessageItem(chatId, mergeKey, deeplink, extraChat))
            assertEquals(belongsToChatOracle(chatId, mergeKey, deeplink, extraChat), NotificationCenterReadPolicy.belongsToChat(chatId, mergeKey, deeplink, extraChat))
            assertEquals(referencesMessageOracle(messageId, itemId, extraMsg), NotificationCenterReadPolicy.referencesMessage(messageId, itemId, extraMsg))
        }
    }

    @Test
    fun notificationCenterReadPolicyEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // The edge leg cycles the pools: msg_/ai_tasks_ merge
            // keys and deeplinks, underscore-heavy ids, and the
            // "_m_" anchor items against every message id.
            val chatId = NCR_CHAT_POOL106[rnd.nextInt(NCR_CHAT_POOL106.size)]
            val mergeKey = NCR_MERGE_POOL106[rnd.nextInt(NCR_MERGE_POOL106.size)]
            val deeplink = NCR_DEEPLINK_POOL106[rnd.nextInt(NCR_DEEPLINK_POOL106.size)]
            val extraChat = NCR_EXTRA_CHAT_POOL106[rnd.nextInt(NCR_EXTRA_CHAT_POOL106.size)]
            val messageId = NCR_MSG_POOL106[rnd.nextInt(NCR_MSG_POOL106.size)]
            val itemId = NCR_ITEM_POOL106[rnd.nextInt(NCR_ITEM_POOL106.size)]
            val extraMsg = NCR_EXTRA_MSG_POOL106[rnd.nextInt(NCR_EXTRA_MSG_POOL106.size)]
            assertEquals(isChatMessageItemOracle(chatId, mergeKey, deeplink, extraChat), NotificationCenterReadPolicy.isChatMessageItem(chatId, mergeKey, deeplink, extraChat))
            assertEquals(belongsToChatOracle(chatId, mergeKey, deeplink, extraChat), NotificationCenterReadPolicy.belongsToChat(chatId, mergeKey, deeplink, extraChat))
            assertEquals(referencesMessageOracle(messageId, itemId, extraMsg), NotificationCenterReadPolicy.referencesMessage(messageId, itemId, extraMsg))
        }
    }

    @Test
    fun notificationCenterReadPolicyPinned() {
        assertEquals(true, NotificationCenterReadPolicy.isChatMessageItem("c1", "msg_c1", null, null))
        assertEquals(true, NotificationCenterReadPolicy.isChatMessageItem("c1", "other", "maodouchat:chat:c1", null))
        assertEquals(true, NotificationCenterReadPolicy.isChatMessageItem("c1", "msg_x", null, "c1"))
        assertEquals(false, NotificationCenterReadPolicy.isChatMessageItem("c1", "ai_tasks_c1", null, "c1"))
        assertEquals(false, NotificationCenterReadPolicy.isChatMessageItem("c1", "other", null, "c1"))
        assertEquals(false, NotificationCenterReadPolicy.isChatMessageItem("", "msg_", null, null))
        assertEquals(false, NotificationCenterReadPolicy.isChatMessageItem(" ", "msg_ ", null, null))
        assertEquals(false, NotificationCenterReadPolicy.isChatMessageItem("c1", "msg_c2", null, null))
        assertEquals(true, NotificationCenterReadPolicy.belongsToChat("c1", "msg_c1", null, null))
        assertEquals(true, NotificationCenterReadPolicy.belongsToChat("c1", "ai_tasks_c1", null, null))
        assertEquals(true, NotificationCenterReadPolicy.belongsToChat("c1", "other", "maodouchat:ai_tasks:c1", null))
        assertEquals(true, NotificationCenterReadPolicy.belongsToChat("c1", "other", null, "c1"))
        assertEquals(false, NotificationCenterReadPolicy.belongsToChat("c1", "other", null, null))
        assertEquals(false, NotificationCenterReadPolicy.belongsToChat("", "msg_", null, null))
        // The "_m_" anchor: "msg_c1_m1" has no "_m_" marker, so it
        // does NOT reference m1; the embedded id always starts with
        // "m_" (the marker's own m + _), so only messageIds with
        // the "m_" prefix can match through the anchor -- "m_m1"
        // matches "msg_c1_m_m1", plain "m1" does not.
        assertEquals(false, NotificationCenterReadPolicy.referencesMessage("m1", "msg_c1_m1", null))
        assertEquals(true, NotificationCenterReadPolicy.referencesMessage("m_m1", "msg_c1_m_m1", null))
        assertEquals(true, NotificationCenterReadPolicy.referencesMessage("m1", "other", "m1"))
        assertEquals(true, NotificationCenterReadPolicy.referencesMessage("m_m1", "msg_a_b_m_m1", null))
        // The LAST "_m_" wins: "msg_c1_m_m1_m_m2" embeds m_m2, not m_m1.
        assertEquals(false, NotificationCenterReadPolicy.referencesMessage("m_m1", "msg_c1_m_m1_m_m2", null))
        assertEquals(true, NotificationCenterReadPolicy.referencesMessage("m_m2", "msg_c1_m_m1_m_m2", null))
        assertEquals(false, NotificationCenterReadPolicy.referencesMessage("", "msg_c1_m_m1", null))
        assertEquals(false, NotificationCenterReadPolicy.referencesMessage("m1", "xmsg_c1_m1", null))
        assertEquals(false, NotificationCenterReadPolicy.referencesMessage("m1", "msg_c1_m1", "m2"))
    }
}
