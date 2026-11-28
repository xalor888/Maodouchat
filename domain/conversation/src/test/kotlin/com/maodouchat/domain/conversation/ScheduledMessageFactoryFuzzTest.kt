// Fuzz / random-oracle tests for ScheduledMessageFactory.build / applyUpdate (checklist line 931,
// 123rd block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. build / applyUpdate: scheduled-message construction (validation, clamping, id format).
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import com.maodouchat.core.model.ReadCountUi
import com.maodouchat.core.model.ScheduledMessage
import com.maodouchat.core.model.ScheduledMessageRequest

class ScheduledMessageFactoryFuzzTest {

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

// ---------- block-123 common codec helpers ----------

// Local hex encoder (same codec as Parse123.kt's encStr); the spec
// builders below and the test files live in named packages and
// cannot see the root-package Parse123.kt, so the codec lives here.
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

private fun encNullableSpecStr(s: String?): String =
    if (s == null) "N" else "S" + encSpecStr(s)

private fun decSpecStr(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4)
    for (i in chars.indices) {
        chars[i] = h.substring(i * 4, i * 4 + 4).toInt(16).toChar()
    }
    return String(chars)
}

private fun decNullableSpecStr(f: String): String? =
    if (f == "N") null else decSpecStr(f.substring(1))

private fun encNullableSpecLong(v: Long?): String =
    if (v == null) "N" else "L" + v.toString()

private fun decNullableSpecLong(f: String): Long? =
    if (f == "N") null else f.substring(1).toLong()

// Per-code-point simple case mapping. Agrees with String.uppercase()
// (full mapping) on the case-safe alphabet; never calls uppercase().
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

// Ordered list<String> spec: ";"-joined encSpecStr, "" = empty.
private fun listSpecOf(xs: List<String>): String =
    xs.joinToString(";") { encSpecStr(it) }

private fun listOfSpec(spec: String): List<String> =
    if (spec.isEmpty()) emptyList() else spec.split(";").map { decSpecStr(it) }

// Set<String> spec: ";"-joined encSpecStr elements, sorted by
// UTF-16 unit order so GEN output is deterministic; "" = empty set.
private fun setSpecOf(s: Set<String>): String =
    s.sorted().joinToString(";") { encSpecStr(it) }

private fun setOfSpec(spec: String): Set<String> =
    if (spec.isEmpty()) emptySet()
    else spec.split(";").map { decSpecStr(it) }.toSet()

// Map<String, String> spec: "key=value" entries (both hex), sorted
// by key (UTF-16 order), ";"-joined.
private fun strMapSpecOf(m: Map<String, String>): String =
    m.entries.sortedBy { it.key }
        .joinToString(";") { encSpecStr(it.key) + "=" + encSpecStr(it.value) }

private fun strMapOfSpec(spec: String): Map<String, String> =
    if (spec.isEmpty()) emptyMap()
    else spec.split(";").associate {
        val kv = it.split("=", limit = 2)
        decSpecStr(kv[0]) to decSpecStr(kv[1])
    }



// ---------- ScheduledMessageFactory oracle ----------

// Plain val, not const: this block is embedded inside the test
// class in the generated test files, where const is illegal.
private val SMF_MIN_DELAY_MS_ORACLE = 60_000L
private val SMF_MAX_DELAY_MS_ORACLE = 7L * 24 * 60 * 60 * 1000
private val SMF_MAX_TEXT_LENGTH_ORACLE = 4000
private val SMF_MAX_PENDING_ORACLE = 56

private fun smpNormalizeOracle(raw: String?): String =
    trimOracle(raw.orEmpty()).take(SMF_MAX_TEXT_LENGTH_ORACLE)

private fun smpValidTextOracle(raw: String?): Boolean {
    val t = smpNormalizeOracle(raw)
    return t.isNotEmpty() && t.length <= SMF_MAX_TEXT_LENGTH_ORACLE
}

private fun smpClampOracle(sendAtMillis: Long, nowMillis: Long): Long {
    val min = nowMillis + SMF_MIN_DELAY_MS_ORACLE
    val max = nowMillis + SMF_MAX_DELAY_MS_ORACLE
    return sendAtMillis.coerceIn(min, max)
}

private fun smpCanAddMoreOracle(pendingCount: Int): Boolean =
    pendingCount < SMF_MAX_PENDING_ORACLE

private fun smfBuildOracle(
    request: ScheduledMessageRequest,
    ownerUserId: String,
    pendingCount: Int,
    nowMs: Long,
    idSuffix: String,
    timeZoneId: String,
    idempotencyKey: String,
): ScheduledMessage? {
    val userId = trimOracle(ownerUserId)
    if (isBlankOracle(userId) || isBlankOracle(request.chatId)) return null
    val normalized = smpNormalizeOracle(request.text)
    if (!smpValidTextOracle(normalized)) return null
    if (!smpCanAddMoreOracle(pendingCount)) return null
    return ScheduledMessage(
        id = "sch_$idSuffix",
        chatId = request.chatId,
        peerUserId = request.peerUserId,
        text = normalized,
        sendAtMillis = smpClampOracle(request.sendAtMillis, nowMs),
        createdAtMillis = nowMs,
        isGroup = request.isGroup,
        ownerUserId = userId,
        repeatIntervalMs = request.repeatIntervalMs.coerceAtLeast(0L),
        repeatCount = request.repeatCount.coerceAtLeast(0),
        occurrencesSent = 0,
        weekdaysOnly = request.weekdaysOnly,
        status = "PENDING",
        attempt = 0,
        timeZoneId = timeZoneId,
        idempotencyKey = idempotencyKey,
    )
}

private fun smfUpdateOracle(
    existing: ScheduledMessage,
    text: String?,
    sendAtMillis: Long?,
    nowMs: Long,
    timeZoneId: String,
): ScheduledMessage? {
    if (isBlankOracle(existing.ownerUserId) || isBlankOracle(existing.id)) return null
    val nextText = text?.let { smpNormalizeOracle(it) } ?: existing.text
    if (!smpValidTextOracle(nextText)) return null
    val nextSendAt = sendAtMillis?.let { smpClampOracle(it, nowMs) } ?: existing.sendAtMillis
    val nextTimeZoneId = if (isBlankOracle(existing.timeZoneId)) timeZoneId else existing.timeZoneId
    return existing.copy(text = nextText, sendAtMillis = nextSendAt, timeZoneId = nextTimeZoneId)
}

private fun smfResultSpecOf(m: ScheduledMessage?): String =
    if (m == null) "N" else "S" + schedMsgSpecOf(m)

// ScheduledMessageRequest spec: 8 fields joined by "|".
private fun requestSpecOf(r: ScheduledMessageRequest): String =
    encSpecStr(r.chatId) + "|" + encSpecStr(r.peerUserId) + "|" +
        encSpecStr(r.text) + "|" + r.sendAtMillis.toString() + "|" +
        (if (r.isGroup) "B1" else "B0") + "|" + r.repeatIntervalMs.toString() +
        "|" + r.repeatCount.toString() + "|" +
        (if (r.weekdaysOnly) "B1" else "B0")

private fun requestOfSpec(spec: String): ScheduledMessageRequest {
    val p = spec.split("|")
    return ScheduledMessageRequest(
        chatId = decSpecStr(p[0]),
        peerUserId = decSpecStr(p[1]),
        text = decSpecStr(p[2]),
        sendAtMillis = p[3].toLong(),
        isGroup = p[4] == "B1",
        repeatIntervalMs = p[5].toLong(),
        repeatCount = p[6].toInt(),
        weekdaysOnly = p[7] == "B1",
    )
}

// ScheduledMessage spec: 16 fields joined by "|".
private fun schedMsgSpecOf(m: ScheduledMessage): String =
    encSpecStr(m.id) + "|" + encSpecStr(m.chatId) + "|" +
        encSpecStr(m.peerUserId) + "|" + encSpecStr(m.text) + "|" +
        m.sendAtMillis.toString() + "|" + m.createdAtMillis.toString() +
        "|" + (if (m.isGroup) "B1" else "B0") + "|" +
        encSpecStr(m.ownerUserId) + "|" + m.repeatIntervalMs.toString() +
        "|" + m.repeatCount.toString() + "|" +
        m.occurrencesSent.toString() + "|" +
        (if (m.weekdaysOnly) "B1" else "B0") + "|" +
        encSpecStr(m.status) + "|" + m.attempt.toString() + "|" +
        encSpecStr(m.timeZoneId) + "|" + encSpecStr(m.idempotencyKey)

private fun schedMsgOfSpec(spec: String): ScheduledMessage {
    val p = spec.split("|")
    return ScheduledMessage(
        id = decSpecStr(p[0]),
        chatId = decSpecStr(p[1]),
        peerUserId = decSpecStr(p[2]),
        text = decSpecStr(p[3]),
        sendAtMillis = p[4].toLong(),
        createdAtMillis = p[5].toLong(),
        isGroup = p[6] == "B1",
        ownerUserId = decSpecStr(p[7]),
        repeatIntervalMs = p[8].toLong(),
        repeatCount = p[9].toInt(),
        occurrencesSent = p[10].toInt(),
        weekdaysOnly = p[11] == "B1",
        status = decSpecStr(p[12]),
        attempt = p[13].toInt(),
        timeZoneId = decSpecStr(p[14]),
        idempotencyKey = decSpecStr(p[15]),
    )
}

private val RRP_ID_POOL123 = arrayOf(
    "", " ", "  ", "\u00A0", "u1", "u2", "alice", "bob", "me",
    "😀", "u123456789", "  u1  "
)

private fun genRrpId123(rnd: Random, poolOnly: Boolean): String =
    if (poolOnly) RRP_ID_POOL123[rnd.nextInt(RRP_ID_POOL123.size)]
    else when (rnd.nextInt(4)) {
        0 -> RRP_ID_POOL123[rnd.nextInt(RRP_ID_POOL123.size)]
        1 -> randomToken(rnd)
        else -> randomString(rnd, 12)
    }


// ---------- ScheduledMessageFactory GEN ----------

private val SMF_LONG_POOL123 = longArrayOf(
    0L, 1L, -1L, 1000L, 60000L, 604800000L, 1700000000000L,
    9223372036854775807L, Long.MIN_VALUE, Long.MAX_VALUE - 1000L,
    Long.MIN_VALUE + 1000L
)

private val SMF_INT_POOL123 = intArrayOf(
    0, 1, -1, 55, 56, 57, 100, Int.MAX_VALUE, Int.MIN_VALUE
)

private fun genSmfLong123(rnd: Random, poolOnly: Boolean): Long =
    if (poolOnly) SMF_LONG_POOL123[rnd.nextInt(SMF_LONG_POOL123.size)]
    else rnd.nextLong()

private fun genSmfInt123(rnd: Random, poolOnly: Boolean): Int =
    if (poolOnly) SMF_INT_POOL123[rnd.nextInt(SMF_INT_POOL123.size)]
    else rnd.nextInt()

private fun genSmfRequest123(rnd: Random, poolOnly: Boolean): ScheduledMessageRequest {
    val text = if (poolOnly) arrayOf("", " ", "hi", "  hello  ", "x".repeat(10))[rnd.nextInt(5)]
    else when (rnd.nextInt(4)) {
        0 -> arrayOf("", " ", "hi", "  hello  ")[rnd.nextInt(4)]
        1 -> randomToken(rnd)
        else -> randomString(rnd, 12)
    }
    return ScheduledMessageRequest(
        chatId = genRrpId123(rnd, poolOnly),
        peerUserId = genRrpId123(rnd, poolOnly),
        text = text,
        sendAtMillis = genSmfLong123(rnd, poolOnly),
        isGroup = rnd.nextBoolean(),
        repeatIntervalMs = genSmfLong123(rnd, poolOnly),
        repeatCount = genSmfInt123(rnd, poolOnly),
        weekdaysOnly = rnd.nextBoolean(),
    )
}

private fun genSmfExisting123(rnd: Random, poolOnly: Boolean): ScheduledMessage {
    return ScheduledMessage(
        id = genRrpId123(rnd, poolOnly),
        chatId = genRrpId123(rnd, poolOnly),
        peerUserId = genRrpId123(rnd, poolOnly),
        text = genRrpId123(rnd, poolOnly),
        sendAtMillis = genSmfLong123(rnd, poolOnly),
        createdAtMillis = genSmfLong123(rnd, poolOnly),
        isGroup = rnd.nextBoolean(),
        ownerUserId = genRrpId123(rnd, poolOnly),
        repeatIntervalMs = genSmfLong123(rnd, poolOnly),
        repeatCount = genSmfInt123(rnd, poolOnly),
        occurrencesSent = genSmfInt123(rnd, poolOnly),
        weekdaysOnly = rnd.nextBoolean(),
        status = genRrpId123(rnd, poolOnly),
        attempt = genSmfInt123(rnd, poolOnly),
        timeZoneId = genRrpId123(rnd, poolOnly),
        idempotencyKey = genRrpId123(rnd, poolOnly),
    )
}

private fun genSmfNullableText123(rnd: Random, poolOnly: Boolean): String? =
    if (rnd.nextInt(10) < 3) null
    else if (poolOnly) arrayOf("", " ", "new", "  updated  ")[rnd.nextInt(4)]
    else when (rnd.nextInt(4)) {
        0 -> arrayOf("", " ", "new")[rnd.nextInt(3)]
        1 -> randomToken(rnd)
        else -> randomString(rnd, 12)
    }

private fun genSmfNullableLong123(rnd: Random, poolOnly: Boolean): Long? =
    if (rnd.nextInt(10) < 3) null else genSmfLong123(rnd, poolOnly)

    @Test
    fun scheduledMessageFactoryRandom() {
        val rnd = Random(42)
        repeat(300) {
            val request = genSmfRequest123(rnd, poolOnly = false)
            val owner = genRrpId123(rnd, poolOnly = false)
            val pending = genSmfInt123(rnd, poolOnly = false)
            val nowMs = genSmfLong123(rnd, poolOnly = false)
            val suffix = genRrpId123(rnd, poolOnly = false)
            val tz = genRrpId123(rnd, poolOnly = false)
            val idem = genRrpId123(rnd, poolOnly = false)
            val existing = genSmfExisting123(rnd, poolOnly = false)
            val text = genSmfNullableText123(rnd, poolOnly = false)
            val sendAt = genSmfNullableLong123(rnd, poolOnly = false)
            assertEquals(
                smfBuildOracle(request, owner, pending, nowMs, suffix, tz, idem),
                ScheduledMessageFactory.build(request, owner, { pending }, nowMs, suffix, tz, idem)
            )
            assertEquals(
                smfUpdateOracle(existing, text, sendAt, nowMs, tz),
                ScheduledMessageFactory.applyUpdate(existing, text, sendAt, nowMs, tz)
            )
        }
    }

    @Test
    fun scheduledMessageFactoryEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            val request = genSmfRequest123(rnd, poolOnly = true)
            val owner = genRrpId123(rnd, poolOnly = true)
            val pending = genSmfInt123(rnd, poolOnly = true)
            val nowMs = genSmfLong123(rnd, poolOnly = true)
            val suffix = genRrpId123(rnd, poolOnly = true)
            val tz = genRrpId123(rnd, poolOnly = true)
            val idem = genRrpId123(rnd, poolOnly = true)
            val existing = genSmfExisting123(rnd, poolOnly = true)
            val text = genSmfNullableText123(rnd, poolOnly = true)
            val sendAt = genSmfNullableLong123(rnd, poolOnly = true)
            assertEquals(
                smfBuildOracle(request, owner, pending, nowMs, suffix, tz, idem),
                ScheduledMessageFactory.build(request, owner, { pending }, nowMs, suffix, tz, idem)
            )
            assertEquals(
                smfUpdateOracle(existing, text, sendAt, nowMs, tz),
                ScheduledMessageFactory.applyUpdate(existing, text, sendAt, nowMs, tz)
            )
        }
    }

    @Test
    fun scheduledMessageFactoryPinned() {
        val now = 1700000000000L
        val req = ScheduledMessageRequest(
            chatId = "c1", peerUserId = "u2", text = "  hello  ",
            sendAtMillis = now + 3600_000L, isGroup = false,
        )
        // Happy path: id gets the sch_ prefix, text is trimmed,
        // sendAt passes through inside the window.
        assertEquals(
            ScheduledMessage(
                id = "sch_abc", chatId = "c1", peerUserId = "u2",
                text = "hello", sendAtMillis = now + 3600_000L,
                createdAtMillis = now, isGroup = false,
                ownerUserId = "me", repeatIntervalMs = 0L,
                repeatCount = 0, occurrencesSent = 0,
                weekdaysOnly = false, status = "PENDING", attempt = 0,
                timeZoneId = "Asia/Shanghai", idempotencyKey = "k1",
            ),
            ScheduledMessageFactory.build(req, "me", { 0 }, now, "abc", "Asia/Shanghai", "k1")
        )
        // Owner is trimmed; blank owner or blank chat rejects.
        assertEquals(
            "me",
            ScheduledMessageFactory.build(req, "  me  ", { 0 }, now, "abc", "Asia/Shanghai", "k1")?.ownerUserId
        )
        assertEquals(null, ScheduledMessageFactory.build(req, "  ", { 0 }, now, "abc", "Asia/Shanghai", "k1"))
        assertEquals(
            null,
            ScheduledMessageFactory.build(
                req.copy(chatId = "  "), "me", { 0 }, now, "abc", "Asia/Shanghai", "k1"
            )
        )
        // Blank text rejects; the pending cap is 56.
        assertEquals(
            null,
            ScheduledMessageFactory.build(
                req.copy(text = "   "), "me", { 0 }, now, "abc", "Asia/Shanghai", "k1"
            )
        )
        assertEquals(
            null,
            ScheduledMessageFactory.build(req, "me", { 56 }, now, "abc", "Asia/Shanghai", "k1")
        )
        assertEquals(
            true,
            ScheduledMessageFactory.build(req, "me", { 55 }, now, "abc", "Asia/Shanghai", "k1") != null
        )
        // sendAt clamps into [now+60s, now+7d].
        assertEquals(
            now + 60_000L,
            ScheduledMessageFactory.build(
                req.copy(sendAtMillis = now), "me", { 0 }, now, "abc", "Asia/Shanghai", "k1"
            )?.sendAtMillis
        )
        assertEquals(
            now + 7L * 24 * 60 * 60 * 1000,
            ScheduledMessageFactory.build(
                req.copy(sendAtMillis = now + 30L * 24 * 60 * 60 * 1000),
                "me", { 0 }, now, "abc", "Asia/Shanghai", "k1"
            )?.sendAtMillis
        )
        // Negative repeats clamp to zero.
        val built = ScheduledMessageFactory.build(
            req.copy(repeatIntervalMs = -5L, repeatCount = -2),
            "me", { 0 }, now, "abc", "Asia/Shanghai", "k1"
        )
        assertEquals(0L, built?.repeatIntervalMs)
        assertEquals(0, built?.repeatCount)
        // applyUpdate: null text keeps the old text, null sendAt
        // keeps the old time; blank stored timeZone takes the new one.
        val existing = ScheduledMessage(
            id = "sch_abc", chatId = "c1", peerUserId = "u2",
            text = "old", sendAtMillis = now + 3600_000L,
            createdAtMillis = now, ownerUserId = "me",
            timeZoneId = "", idempotencyKey = "k1",
        )
        assertEquals(
            existing.copy(text = "new", timeZoneId = "UTC"),
            ScheduledMessageFactory.applyUpdate(existing, "  new  ", null, now, "UTC")
        )
        assertEquals(
            existing.copy(timeZoneId = "Asia/Shanghai", sendAtMillis = now + 7200_000L),
            ScheduledMessageFactory.applyUpdate(
                existing.copy(timeZoneId = "Asia/Shanghai"),
                null, now + 7200_000L, now, "UTC"
            )
        )
        assertEquals(
            null,
            ScheduledMessageFactory.applyUpdate(existing, "   ", null, now, "UTC")
        )
        assertEquals(
            null,
            ScheduledMessageFactory.applyUpdate(
                existing.copy(ownerUserId = " "), "new", null, now, "UTC"
            )
        )
        assertEquals(
            null,
            ScheduledMessageFactory.applyUpdate(
                existing.copy(id = ""), "new", null, now, "UTC"
            )
        )
        // Oracle cross-checks on the same pinned inputs.
        assertEquals(
            smfBuildOracle(req, "me", 0, now, "abc", "Asia/Shanghai", "k1"),
            ScheduledMessageFactory.build(req, "me", { 0 }, now, "abc", "Asia/Shanghai", "k1")
        )
        assertEquals(
            smfUpdateOracle(existing, "  new  ", null, now, "UTC"),
            ScheduledMessageFactory.applyUpdate(existing, "  new  ", null, now, "UTC")
        )
    }
}
