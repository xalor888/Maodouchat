// Fuzz / random-oracle tests for PushWakePayloadPolicy.evaluate / isSafe (checklist line 931,
// 123rd block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. evaluate / isSafe: offline wake-payload privacy gate (routing keys only, no chat plaintext).
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import com.maodouchat.core.model.ReadCountUi

class PushWakePayloadPolicyFuzzTest {

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



// ---------- PushWakePayloadPolicy oracle ----------

private val PWP_ALLOWED_KEYS_ORACLE: Set<String> = setOf(
    "type", "chatid", "messageid", "recipientid", "senderid",
    "callid", "calltype", "postid", "commentid", "actorid",
    "interaction", "inviteid", "fromuserid", "requestid", "action",
    "announcementid", "title", "level", "ts", "sig",
)

private val PWP_FORBIDDEN_KEYS_ORACLE: Set<String> = setOf(
    "body", "preview", "plaintext", "content", "message", "text",
    "ciphertext", "envelope", "lastmessage",
)

private fun pwpEvalOracle(data: Map<String, String>): PushWakeDecision {
    if (data.isEmpty()) return PushWakeDecision.Reject("empty")
    val keys = data.keys.map { lowercaseOracle(trimOracle(it)) }
        .filter { it.isNotEmpty() }
    val forbidden = keys.filter { it in PWP_FORBIDDEN_KEYS_ORACLE }
    if (forbidden.isNotEmpty()) {
        return PushWakeDecision.Reject(
            "sensitive_keys=" + forbidden.sorted().joinToString(",")
        )
    }
    val unknown = keys.filter { it !in PWP_ALLOWED_KEYS_ORACLE }
    if (unknown.isNotEmpty()) {
        return PushWakeDecision.Reject(
            "unknown_keys=" + unknown.sorted().joinToString(",")
        )
    }
    return PushWakeDecision.Accept
}

private fun pwpSafeOracle(data: Map<String, String>): Boolean =
    pwpEvalOracle(data) is PushWakeDecision.Accept

private fun pwpDecisionSpecOf(d: PushWakeDecision): String =
    when (d) {
        PushWakeDecision.Accept -> "A"
        is PushWakeDecision.Reject -> "R:" + d.reason
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


// ---------- PushWakePayloadPolicy GEN ----------

private val PWP_KEY_POOL123 = arrayOf(
    "", " ", "type", "Type", "TYPE", "chatId", "CHATID", "messageId",
    "body", "BODY", " Body ", "preview", "content", "text", "message",
    "ciphertext", "envelope", "lastmessage", "plaintext", "sig",
    "ts", "title", "level", "action", "unknown_key", "foo", "😀",
    "  ", "callid", "calltype", "postid", "actorid", "x"
)

private val PWP_VALUE_POOL123 = arrayOf(
    "", " ", "v1", "hello", "123", "😀", "  x  "
)

private fun genPwpData123(rnd: Random, poolOnly: Boolean): Map<String, String> {
    val n = if (poolOnly) rnd.nextInt(5) else rnd.nextInt(6)
    val out = LinkedHashMap<String, String>()
    repeat(n) {
        val k = if (poolOnly) PWP_KEY_POOL123[rnd.nextInt(PWP_KEY_POOL123.size)]
        else when (rnd.nextInt(4)) {
            0 -> PWP_KEY_POOL123[rnd.nextInt(PWP_KEY_POOL123.size)]
            1 -> randomToken(rnd)
            else -> randomString(rnd, 12)
        }
        val v = if (poolOnly) PWP_VALUE_POOL123[rnd.nextInt(PWP_VALUE_POOL123.size)]
        else genRrpId123(rnd, poolOnly = false)
        out[k] = v
    }
    return out
}

    @Test
    fun pushWakePayloadPolicyRandom() {
        val rnd = Random(42)
        repeat(300) {
            val data = genPwpData123(rnd, poolOnly = false)
            assertEquals(
                pwpDecisionSpecOf(pwpEvalOracle(data)),
                pwpDecisionSpecOf(PushWakePayloadPolicy.evaluate(data))
            )
            assertEquals(
                pwpSafeOracle(data),
                PushWakePayloadPolicy.isSafe(data)
            )
        }
    }

    @Test
    fun pushWakePayloadPolicyEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            val data = genPwpData123(rnd, poolOnly = true)
            assertEquals(
                pwpDecisionSpecOf(pwpEvalOracle(data)),
                pwpDecisionSpecOf(PushWakePayloadPolicy.evaluate(data))
            )
            assertEquals(
                pwpSafeOracle(data),
                PushWakePayloadPolicy.isSafe(data)
            )
        }
    }

    @Test
    fun pushWakePayloadPolicyPinned() {
        // Empty payload is rejected, not accepted.
        assertEquals(
            PushWakeDecision.Reject("empty"),
            PushWakePayloadPolicy.evaluate(emptyMap())
        )
        assertEquals(false, PushWakePayloadPolicy.isSafe(emptyMap()))
        // Routing-only keys pass; comparison is case-insensitive.
        assertEquals(
            PushWakeDecision.Accept,
            PushWakePayloadPolicy.evaluate(mapOf("type" to "wake", "chatId" to "c1"))
        )
        assertEquals(
            PushWakeDecision.Accept,
            PushWakePayloadPolicy.evaluate(mapOf("TYPE" to "wake", "SIG" to "abc"))
        )
        assertEquals(true, PushWakePayloadPolicy.isSafe(mapOf("type" to "wake")))
        // A single sensitive key rejects the whole packet, sorted.
        assertEquals(
            PushWakeDecision.Reject("sensitive_keys=body"),
            PushWakePayloadPolicy.evaluate(mapOf("type" to "wake", "body" to "hi"))
        )
        assertEquals(
            PushWakeDecision.Reject("sensitive_keys=body,preview"),
            PushWakePayloadPolicy.evaluate(
                mapOf("preview" to "p", "type" to "w", "BODY" to "b")
            )
        )
        assertEquals(false, PushWakePayloadPolicy.isSafe(mapOf("text" to "hi")))
        // Forbidden beats unknown: both present -> sensitive reason.
        assertEquals(
            PushWakeDecision.Reject("sensitive_keys=text"),
            PushWakePayloadPolicy.evaluate(mapOf("text" to "hi", "mystery" to "1"))
        )
        // Unknown keys are rejected with their sorted names.
        assertEquals(
            PushWakeDecision.Reject("unknown_keys=foo,mystery"),
            PushWakePayloadPolicy.evaluate(mapOf("mystery" to "1", "foo" to "2"))
        )
        // Blank/whitespace keys are dropped before the checks; a
        // packet whose keys all drop out has nothing forbidden or
        // unknown, so it is accepted (only an empty map is "empty").
        assertEquals(
            PushWakeDecision.Accept,
            PushWakePayloadPolicy.evaluate(mapOf(" " to "x", "type" to "w"))
        )
        assertEquals(
            PushWakeDecision.Accept,
            PushWakePayloadPolicy.evaluate(mapOf(" " to "x"))
        )
        // Oracle cross-checks on the same pinned inputs.
        assertEquals(
            pwpDecisionSpecOf(pwpEvalOracle(mapOf("type" to "w", "body" to "hi"))),
            pwpDecisionSpecOf(PushWakePayloadPolicy.evaluate(mapOf("type" to "w", "body" to "hi")))
        )
        assertEquals(
            pwpSafeOracle(mapOf("type" to "w")),
            PushWakePayloadPolicy.isSafe(mapOf("type" to "w"))
        )
    }
}
