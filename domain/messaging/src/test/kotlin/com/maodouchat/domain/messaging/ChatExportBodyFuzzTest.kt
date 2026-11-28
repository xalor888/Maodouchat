// Fuzz / random-oracle tests for ChatExportBody (checklist line 931,
// 115th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. ChatExportBody: export line rendering, full text, and file-name sanitizing.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta

class ChatExportBodyFuzzTest {

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

// ---------- block-115 common oracle helpers ----------

// Case-insensitive string equality, per-char fold exactly as
// String.equals(other, ignoreCase = true) does it (see
// charsEqualIgnoreCaseOracle in ORACLE_SHARED).
private fun equalsIgnoreCaseOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        if (!charsEqualIgnoreCaseOracle(a[i], b[i])) return false
    }
    return true
}

// Java regex \s (what BotInboxPolicy's SLASH / AT_BOT use): space,
// tab, LF, VT, FF, CR -- NOT the 28-unit Kotlin isWhitespace set.
private fun isRegexWhitespaceOracle(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

// substringBeforeLast with the missing-delimiter value (Kotlin's
// substringBeforeLast(delimiter, missingDelimiterValue)).
private fun substringBeforeLastOracle(s: String, delim: Char): String {
    val idx = s.lastIndexOf(delim)
    return if (idx < 0) s else s.substring(0, idx)
}

private fun substringAfterLastOracle(s: String, delim: Char, missing: String): String {
    val idx = s.lastIndexOf(delim)
    return if (idx < 0) missing else s.substring(idx + 1)
}

// Left-pad with '0' to n units (for the yyyy-MM-dd HH:mm stamp).
private fun padOracle(s: String, n: Int): String {
    if (s.length >= n) return s
    val sb = StringBuilder()
    repeat(n - s.length) { sb.append('0') }
    sb.append(s)
    return sb.toString()
}

// Local hex encoder (same codec as Parse115.kt's encStr); the spec
// builders below and the test files live in named packages and
// cannot see the root-package Parse115.kt, so the codec lives here.
private fun encSpecStr(s: String): String {
    val sb = StringBuilder(s.length * 4)
    for (c in s) {
        val v = c.code
        sb.append("0123456789abcdef"[v shr 12])
        sb.append("0123456789abcdef"[(v shr 8) and 0xF])
        sb.append("0123456789abcdef"[(v shr 4) and 0xF])
        sb.append("0123456789abcdef"[v and 0xF])
    }
    return sb.toString()
}

// ---------- wire value parts (oracle-side, never production) ----------

private data class SlashCommandParts(
    val command: String,
    val targetUsername: String?,
    val arguments: String
)

private data class ChatUserParts(val avatar: String?, val isOnline: Boolean)

private data class ChatParts(
    val isSecretParam: Boolean,
    val isGroup: Boolean,
    val chatType: String,
    val groupAvatar: String?,
    val users: List<ChatUserParts>
)

private data class ClassificationParts(val category: String, val confidence: Double)

private data class CategoryCountParts(val category: String, val count: Int, val confidence: Double)

private data class LocalFileMetadataParts(val fileName: String, val mimeType: String, val sizeBytes: Long)

private data class MessageParts(
    val type: MessageType,
    val senderId: String,
    val content: String,
    val timestamp: Long,
    val fileName: String?
)

// ---------- spec encoders (GEN only; decoders live in EVAL_HELPERS) ----------

private fun chatSpecOf(p: ChatParts): String {
    val users = p.users.joinToString(";") { u ->
        (u.avatar?.let { encSpecStr(it) } ?: "N") + "|" + (if (u.isOnline) "1" else "0")
    }
    return "G|" + (if (p.isSecretParam) "1" else "0") + "|" +
        (if (p.isGroup) "1" else "0") + "|" + encSpecStr(p.chatType) + "|" +
        (p.groupAvatar?.let { encSpecStr(it) } ?: "N") + "|" + users
}

private fun tallySpecOf(entries: List<ClassificationParts>): String =
    entries.joinToString(";") { e -> e.category + "|" + e.confidence.toBits().toString() }

private fun messageSpecOf(m: MessageParts): String =
    "M|" + m.type.name + "|" + encSpecStr(m.senderId) + "|" +
        encSpecStr(m.content) + "|" + m.timestamp.toString() + "|" +
        (m.fileName?.let { encSpecStr(it) } ?: "N")

// ---------- output encoders (eval only) ----------

private fun encSlashParts(p: SlashCommandParts?): String =
    if (p == null) "N"
    else encSpecStr(p.command) + "|" + encSpecStr(p.targetUsername ?: "") + "|" + encSpecStr(p.arguments)

private fun encTallyParts(parts: List<CategoryCountParts>): String =
    parts.joinToString(";") { p ->
        encSpecStr(p.category) + "|" + p.count.toString() + "|" + p.confidence.toBits().toString()
    }

private fun encMetaParts(p: LocalFileMetadataParts): String =
    encSpecStr(p.fileName) + "|" + encSpecStr(p.mimeType) + "|" + p.sizeBytes.toString()

private fun genTextOf(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    while (sb.length < len) sb.append(randomString(rnd, 64))
    return if (sb.length > len) sb.toString().substring(0, len) else sb.toString()
}

// ---------- ChatExportBody oracle ----------

// The resolve-name lambda the tests and the differential share:
// blank for one sentinel id (exercises the ifBlank fallback), else
// a "User-<id>" display name.
private val RESOLVE_NAME115: (String) -> String = { id ->
    if (id == "blank-user") "" else "User-$id"
}

// Hand-written reimplementation of ChatTextDisplayPolicy
// .unescapeHtmlEntities: numeric decimal, numeric hex, then the
// seven literal replaces in production order. Never Regex /
// .replace() with a lambda here -- the scan is explicit so the
// Python side mirrors it exactly.
private fun replaceLiteralOracle(s: String, old: String, new: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        if (s.startsWith(old, i)) { sb.append(new); i += old.length }
        else { sb.append(s[i]); i++ }
    }
    return sb.toString()
}

private fun replaceNumericDecimalOracle(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        var matched = false
        if (i + 2 < s.length && s[i] == '&' && s[i + 1] == '#') {
            var j = i + 2
            var digits = 0
            while (j < s.length && digits < 7 && s[j] in '0'..'9') { j++; digits++ }
            if (digits in 1..7 && j < s.length && s[j] == ';') {
                var code = 0
                for (k in i + 2 until j) code = code * 10 + (s[k].code - '0'.code)
                if (code in 1..0x10FFFF) sb.append(String(intArrayOf(code), 0, 1))
                else sb.append(s.substring(i, j + 1))
                i = j + 1
                matched = true
            }
        }
        if (!matched) { sb.append(s[i]); i++ }
    }
    return sb.toString()
}

private fun isHexDigitOracle(c: Char): Boolean =
    c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private fun hexValueOracle(c: Char): Int =
    when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        else -> c.code - 'A'.code + 10
    }

private fun replaceNumericHexOracle(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        var matched = false
        if (i + 3 < s.length && s[i] == '&' && s[i + 1] == '#' &&
            (s[i + 2] == 'x' || s[i + 2] == 'X')
        ) {
            var j = i + 3
            var digits = 0
            while (j < s.length && digits < 6 && isHexDigitOracle(s[j])) { j++; digits++ }
            if (digits in 1..6 && j < s.length && s[j] == ';') {
                var code = 0
                for (k in i + 3 until j) code = code * 16 + hexValueOracle(s[k])
                if (code in 1..0x10FFFF) sb.append(String(intArrayOf(code), 0, 1))
                else sb.append(s.substring(i, j + 1))
                i = j + 1
                matched = true
            }
        }
        if (!matched) { sb.append(s[i]); i++ }
    }
    return sb.toString()
}

private fun unescapeHtmlEntitiesOracle(raw: String): String {
    if (raw.isEmpty() || indexOfCharOracle(raw, '&') < 0) return raw
    var out = replaceNumericDecimalOracle(raw)
    out = replaceNumericHexOracle(out)
    out = replaceLiteralOracle(out, "&nbsp;", " ")
    out = replaceLiteralOracle(out, "&quot;", "\"")
    out = replaceLiteralOracle(out, "&#39;", "'")
    out = replaceLiteralOracle(out, "&apos;", "'")
    out = replaceLiteralOracle(out, "&lt;", "<")
    out = replaceLiteralOracle(out, "&gt;", ">")
    out = replaceLiteralOracle(out, "&amp;", "&")
    return out
}

private fun parsedContentOracle(content: String): String {
    val idx = content.lastIndexOf("<meta>")
    val body = if (idx < 0) content else content.substring(0, idx)
    return unescapeHtmlEntitiesOracle(body)
}

// formatAiTaskDue re-derived from java.util.Calendar (same default
// timezone as the production SimpleDateFormat, no .format() call).
private fun formatAiTaskDueOracle(timestamp: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = timestamp
    val year = cal.get(java.util.Calendar.YEAR)
    val month = cal.get(java.util.Calendar.MONTH) + 1
    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = cal.get(java.util.Calendar.MINUTE)
    return padOracle(year.toString(), 4) + "-" + padOracle(month.toString(), 2) + "-" +
        padOracle(day.toString(), 2) + " " + padOracle(hour.toString(), 2) + ":" +
        padOracle(minute.toString(), 2)
}

private fun renderBodyOracle(m: MessageParts): String =
    when (m.type) {
        MessageType.TEXT, MessageType.MARKDOWN, MessageType.GIF -> parsedContentOracle(m.content)
        MessageType.IMAGE -> "[图片]"
        MessageType.STICKER -> "[表情]"
        MessageType.LOCATION -> "[位置]"
        MessageType.VOICE -> "[语音]"
        MessageType.VIDEO -> "[视频]"
        MessageType.FILE -> if (m.fileName != null) "[文件] " + m.fileName else "[文件]"
        MessageType.NUDGE -> "[戳一戳]"
        MessageType.REVOKED -> "[消息已撤回]"
        MessageType.SYSTEM, MessageType.SK_DIST -> ""
    }

private fun formatLineOracle(m: MessageParts, ownerId: String): String? {
    if (m.type == MessageType.SYSTEM || m.type == MessageType.SK_DIST) return null
    val body = renderBodyOracle(m)
    if (body.isEmpty()) return null
    val sender = if (m.senderId == ownerId) "我" else RESOLVE_NAME115(m.senderId)
    val shown = if (isBlankOracle(sender)) m.senderId else sender
    return "[" + formatAiTaskDueOracle(m.timestamp) + "] " + shown + ": " + body
}

private fun buildTextOracle(
    chatName: String,
    ownerId: String,
    messages: List<MessageParts>,
    exportedAtText: String
): String {
    val sb = StringBuilder()
    sb.append("=== ").append(if (isBlankOracle(chatName)) "-" else chatName).append(" ===\n")
    sb.append("format_version=").append(1).append("\n")
    sb.append("exported_at=").append(exportedAtText).append("\n")
    sb.append("count=").append(messages.size).append("\n\n")
    for (m in messages) {
        val line = formatLineOracle(m, ownerId)
        if (line != null) sb.append(line).append("\n")
    }
    return sb.toString()
}

private fun sanitizeExportFileNameOracle(fileName: String): String {
    val sb = StringBuilder(fileName.length)
    for (c in fileName) {
        sb.append(
            if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' ||
                c == '"' || c == '<' || c == '>' || c == '|'
            ) '_' else c
        )
    }
    return takeOracle(sb.toString(), 80)
}

private val CEB_CONTENT_POOL115 = arrayOf(
    "", "hello", "a &amp; b", "&#65; BC", "&#x41; BC", "&#X4a; BC",
    "&#0;", "&#1114112;", "&#x110000;", "&#12345678;", "&nbsp;sp",
    "&lt;tag&gt;", "&quot;q&quot;", "&apos;a&apos;", "&#39;s",
    "text<meta>{\"a\":1}</meta>", "a<meta>b</meta>c<meta>d</meta>",
    "no meta <meta", "<meta>", "x&#65", "&#;", "&#x;", "&amp;amp;",
    "&amp;lt;", "&#65;&#66;", "emoji \uD83D\uDE00", "a\nb", "  spaces  ",
    "&#10;nl", "&#x0A;nl", "&#x1F600;", "&#128512;", "a & b", "&unknown;",
    "&#65", "&#x41", "&#0000065; seven digits", "&#00000065; eight digits"
)

private val CEB_SENDER_POOL115 = arrayOf(
    "u1", "u2", "owner", "blank-user", "", "  ", "üser"
)

private val CEB_TS_POOL115 = arrayOf(
    0L, 1L, -86400000L, 1700000000000L, 1750000000000L, 4102444800000L,
    123456789000L, 1700000000123L
)

private val CEB_FILENAME_POOL115 = arrayOf<String?>(
    null, "", "doc.pdf", "a/b", "üñï.docx", "report:final?.txt"
)

private fun genMessageParts115(rnd: Random, poolOnly: Boolean): MessageParts {
    val type = MessageType.entries[rnd.nextInt(MessageType.entries.size)]
    val sender = if (poolOnly) CEB_SENDER_POOL115[rnd.nextInt(CEB_SENDER_POOL115.size)]
        else if (rnd.nextBoolean()) randomToken(rnd) else genTextOf(rnd, rnd.nextInt(0, 10))
    val content = if (poolOnly) CEB_CONTENT_POOL115[rnd.nextInt(CEB_CONTENT_POOL115.size)]
        else if (rnd.nextInt(4) == 0) randomString(rnd, 60)
        else genTextOf(rnd, rnd.nextInt(0, 60))
    val ts = if (poolOnly) CEB_TS_POOL115[rnd.nextInt(CEB_TS_POOL115.size)]
        else rnd.nextLong(1700000000000L, 1800000000000L)
    val fileName = if (type == MessageType.FILE) {
        if (poolOnly) CEB_FILENAME_POOL115[rnd.nextInt(CEB_FILENAME_POOL115.size)]
        else if (rnd.nextBoolean()) null else randomToken(rnd) + ".pdf"
    } else null
    return MessageParts(type, sender, content, ts, fileName)
}

private fun genBuildMessages115(rnd: Random, poolOnly: Boolean): List<MessageParts> {
    val n = if (poolOnly) rnd.nextInt(0, 3) else rnd.nextInt(0, 6)
    return (0 until n).map { genMessageParts115(rnd, poolOnly) }
}

    @Test
    fun chatExportBodyRandom() {
        val rnd = Random(42)
        repeat(300) {
            val m = genMessageParts115(rnd, poolOnly = false)
            val owner = if (rnd.nextBoolean()) "owner" else "u1"
            val msg = Message(
                id = "m", chatId = "c", senderId = m.senderId, content = m.content,
                type = m.type, timestamp = m.timestamp,
                meta = MessageMeta(fileName = m.fileName)
            )
            assertEquals(renderBodyOracle(m), ChatExportBody.renderBody(msg))
            assertEquals(
                formatLineOracle(m, owner),
                ChatExportBody.formatLine(msg, owner, RESOLVE_NAME115)
            )
            val chatName = if (rnd.nextBoolean()) "Group" else randomToken(rnd)
            val exportedAt = "2026-11-24 06:00"
            val messages = genBuildMessages115(rnd, poolOnly = false)
            val prodMessages = messages.map {
                Message(
                    id = "m", chatId = "c", senderId = it.senderId, content = it.content,
                    type = it.type, timestamp = it.timestamp,
                    meta = MessageMeta(fileName = it.fileName)
                )
            }
            assertEquals(
                buildTextOracle(chatName, owner, messages, exportedAt),
                ChatExportBody.buildText(chatName, owner, RESOLVE_NAME115, prodMessages, exportedAt)
            )
            val name = genTextOf(rnd, rnd.nextInt(0, 90))
            assertEquals(
                sanitizeExportFileNameOracle(name),
                ChatExportBody.sanitizeExportFileName(name)
            )
        }
    }

    @Test
    fun chatExportBodyEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Pool-only legs: entities, meta tags, timestamp edges dominate.
            val m = genMessageParts115(rnd, poolOnly = true)
            val owner = if (rnd.nextBoolean()) "owner" else "u1"
            val msg = Message(
                id = "m", chatId = "c", senderId = m.senderId, content = m.content,
                type = m.type, timestamp = m.timestamp,
                meta = MessageMeta(fileName = m.fileName)
            )
            assertEquals(renderBodyOracle(m), ChatExportBody.renderBody(msg))
            assertEquals(
                formatLineOracle(m, owner),
                ChatExportBody.formatLine(msg, owner, RESOLVE_NAME115)
            )
            val chatName = CEB_CONTENT_POOL115[rnd.nextInt(CEB_CONTENT_POOL115.size)]
            val exportedAt = "2026-11-24 06:00"
            val messages = genBuildMessages115(rnd, poolOnly = true)
            val prodMessages = messages.map {
                Message(
                    id = "m", chatId = "c", senderId = it.senderId, content = it.content,
                    type = it.type, timestamp = it.timestamp,
                    meta = MessageMeta(fileName = it.fileName)
                )
            }
            assertEquals(
                buildTextOracle(chatName, owner, messages, exportedAt),
                ChatExportBody.buildText(chatName, owner, RESOLVE_NAME115, prodMessages, exportedAt)
            )
            val name = CEB_CONTENT_POOL115[rnd.nextInt(CEB_CONTENT_POOL115.size)]
            assertEquals(
                sanitizeExportFileNameOracle(name),
                ChatExportBody.sanitizeExportFileName(name)
            )
        }
    }

    @Test
    fun chatExportBodyPinned() {
        val text = Message(
            id = "m", chatId = "c", senderId = "u1", content = "hi",
            type = MessageType.TEXT, timestamp = 1700000000000L
        )
        assertEquals("hi", ChatExportBody.renderBody(text))
        assertEquals("[文件] doc.pdf", ChatExportBody.renderBody(text.copy(type = MessageType.FILE, meta = MessageMeta(fileName = "doc.pdf"))))
        assertEquals("[文件]", ChatExportBody.renderBody(text.copy(type = MessageType.FILE)))
        assertEquals("[图片]", ChatExportBody.renderBody(text.copy(type = MessageType.IMAGE)))
        assertEquals("", ChatExportBody.renderBody(text.copy(type = MessageType.SYSTEM)))
        assertEquals("A", ChatExportBody.renderBody(text.copy(content = "&#65;")))
        assertEquals("A", ChatExportBody.renderBody(text.copy(content = "&#x41;")))
        assertEquals("J", ChatExportBody.renderBody(text.copy(content = "&#X4a;")))
        assertEquals("&#0;", ChatExportBody.renderBody(text.copy(content = "&#0;")))
        assertEquals("&#1114112;", ChatExportBody.renderBody(text.copy(content = "&#1114112;")))
        assertEquals("&#x110000;", ChatExportBody.renderBody(text.copy(content = "&#x110000;")))
        assertEquals("&#12345678;", ChatExportBody.renderBody(text.copy(content = "&#12345678;")))
        assertEquals("a <b", ChatExportBody.renderBody(text.copy(content = "a &lt;b")))
        assertEquals("&lt;", ChatExportBody.renderBody(text.copy(content = "&amp;lt;")))
        assertEquals("&amp;", ChatExportBody.renderBody(text.copy(content = "&amp;amp;")))
        assertEquals("a", ChatExportBody.renderBody(text.copy(content = "a<meta>{\"a\":1}</meta>")))
        assertEquals("a<meta>b</meta>c", ChatExportBody.renderBody(text.copy(content = "a<meta>b</meta>c<meta>d</meta>")))
        assertEquals("[2023-11-15 06:13] 我: hi", ChatExportBody.formatLine(text.copy(senderId = "owner"), "owner", RESOLVE_NAME115))
        assertEquals("[2023-11-15 06:13] User-u1: hi", ChatExportBody.formatLine(text, "owner", RESOLVE_NAME115))
        assertEquals("[2023-11-15 06:13] blank-user: hi", ChatExportBody.formatLine(text.copy(senderId = "blank-user"), "owner", RESOLVE_NAME115))
        assertEquals(null, ChatExportBody.formatLine(text.copy(type = MessageType.SYSTEM), "owner", RESOLVE_NAME115))
        assertEquals(null, ChatExportBody.formatLine(text.copy(content = ""), "owner", RESOLVE_NAME115))
        assertEquals(
            "=== Group ===\nformat_version=1\nexported_at=2026-11-24 06:00\ncount=2\n\n[2023-11-15 06:13] User-u1: hi\n",
            ChatExportBody.buildText(
                "Group", "owner", RESOLVE_NAME115,
                listOf(text, text.copy(type = MessageType.SYSTEM)), "2026-11-24 06:00"
            )
        )
        assertEquals("=== - ===\nformat_version=1\nexported_at=t\ncount=0\n\n", ChatExportBody.buildText("  ", "owner", RESOLVE_NAME115, emptyList(), "t"))
        assertEquals("a_b_c", ChatExportBody.sanitizeExportFileName("a/b:c"))
        assertEquals("x".repeat(80), ChatExportBody.sanitizeExportFileName("x".repeat(90)))
        // Oracle agrees with production on the entity/date edges.
        assertEquals("&#0;", unescapeHtmlEntitiesOracle("&#0;"))
        assertEquals("&lt;", unescapeHtmlEntitiesOracle("&amp;lt;"))
        assertEquals("2023-11-15 06:13", formatAiTaskDueOracle(1700000000000L))
        assertEquals("A", parsedContentOracle("&#65;<meta>x</meta>"))
    }
}
