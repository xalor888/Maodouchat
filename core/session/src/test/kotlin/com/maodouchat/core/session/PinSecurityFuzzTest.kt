// Fuzz / random-oracle tests for PinSecurity (checklist line 931,
// 99th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. PinSecurityPolicy: PIN format, constant-time compare, SHA-256 legacy hash, PBKDF2 hash, and verifyPin outcomes (upgrade flag only).
package com.maodouchat.core.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class PinSecurityFuzzTest {

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

// ---------- PinSecurity oracle ----------

// Hand-written reimplementation of PinSecurityPolicy. The JDK
// crypto primitives (MessageDigest SHA-256, SecretKeyFactory
// PBKDF2WithHmacSHA256) are the platform, not the function under
// test; the branch logic, hex codec, salt decoding and outcome
// mapping are reimplemented by hand. verifyPin returns only the
// outcome code -- C (Corrupted), M (Mismatch), S0 (Success, no
// upgrade), S1 (Success, upgrade needed) -- the random upgraded
// hash is never compared on either side.
private fun isValidPinFormatOracle(pin: String): Boolean =
    pin.length in 4..12 && pin.all { isDigitOracle(it) }

private fun constantTimeEqualsOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var result = 0
    for (i in a.indices) {
        result = result or (a[i].code xor b[i].code)
    }
    return result == 0
}

private fun hexEncodeOracle(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v shr 4])
        sb.append("0123456789abcdef"[v and 0xF])
    }
    return sb.toString()
}

private fun sha256Oracle(data: ByteArray): ByteArray =
    java.security.MessageDigest.getInstance("SHA-256").digest(data)

private fun hashSha256LegacyOracle(pin: String, salt: String): String =
    hexEncodeOracle(sha256Oracle((pin + salt).toByteArray(Charsets.UTF_8)))

// Production chunks the hex salt two chars at a time; a trailing
// lone char is its own value (not shifted). Invalid hex is never
// generated (production would throw).
private fun hexDecodeChunkedOracle(saltHex: String): ByteArray {
    val out = ByteArray((saltHex.length + 1) / 2)
    var i = 0
    var j = 0
    while (i < saltHex.length) {
        val hi = saltHex[i].digitToIntOrNull(16) ?: error("bad hex salt")
        val lo = if (i + 1 < saltHex.length) {
            saltHex[i + 1].digitToIntOrNull(16) ?: error("bad hex salt")
        } else {
            0
        }
        out[j++] = (if (i + 1 < saltHex.length) (hi shl 4) or lo else hi).toByte()
        i += 2
    }
    return out
}

private fun hashPbkdf2Oracle(pin: String, saltHex: String, iterations: Int): String {
    val salt = hexDecodeChunkedOracle(saltHex)
    val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
    val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    return hexEncodeOracle(factory.generateSecret(spec).encoded)
}

private fun toIntOrNullOracle(s: String): Int? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (s.length == 1) return null
    }
    var v = 0L
    while (i < s.length) {
        val d = s[i].code - '0'.code
        if (d < 0 || d > 9) return null
        v = v * 10 + d
        if (v > 2147483648L) return null
        i++
    }
    if (neg) v = -v
    if (v < -2147483648L || v > 2147483647L) return null
    return v.toInt()
}

private fun verifyPinOracle(pin: String, storedHash: String, storedSalt: String, minDesiredIterations: Int): String {
    if (isBlankOracle(storedHash)) return "C"
    if (startsWithOracle(storedHash, "pbkdf2$")) {
        val parts = storedHash.split("$")
        if (parts.size != 4) return "C"
        val iter = toIntOrNullOracle(parts[1]) ?: return "C"
        val salt = parts[2]
        val expectedHash = parts[3]
        val computedHash = hashPbkdf2Oracle(pin, salt, iter)
        if (!equalsOracle(expectedHash, computedHash)) return "M"
        return if (iter < minDesiredIterations) "S1" else "S0"
    }
    val computedHash = hashSha256LegacyOracle(pin, storedSalt)
    if (!equalsOracle(storedHash, computedHash)) return "M"
    return "S1"
}

private fun verifyOutcomeCodeOracle(outcome: VerificationOutcome): String =
    when (outcome) {
        is VerificationOutcome.Success ->
            if (outcome.needsUpgrade) "S1" else "S0"
        VerificationOutcome.Mismatch -> "M"
        VerificationOutcome.Corrupted -> "C"
    }

private val PIN_POOL99 = arrayOf(
    "", "1", "12", "123", "1234", "0000", "9999", "123456789012",
    "1234567890123", "12a4", "123a", "a123", " 1234", "1234 ",
    "12 34", "１２３４", "123\n4", "000000000000",
)

private val PIN_SALT_POOL99 = arrayOf(
    "00", "ff", "00ff10", "abcdef", "0123456789abcdef", "f", "abc",
    "FF", "AbC",
)

private val PIN_ITER_POOL99 = arrayOf(1, 2, 10, 100, 1000)

private fun genPin(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> PIN_POOL99[rnd.nextInt(PIN_POOL99.size)]
        6 -> randomToken(rnd)
        else -> randomString(rnd, 6)
    }

private fun genPinEdge(rnd: Random): String =
    PIN_POOL99[rnd.nextInt(PIN_POOL99.size)]

private fun genPinPair(rnd: Random): Pair<String, String> {
    val a = genPin(rnd)
    return when (rnd.nextInt(4)) {
        0 -> a to a
        1 -> a to genPin(rnd)
        2 -> a to (a + "x")
        else -> genPin(rnd) to genPin(rnd)
    }
}

private fun genPinPairEdge(rnd: Random): Pair<String, String> {
    val a = PIN_POOL99[rnd.nextInt(PIN_POOL99.size)]
    return when (rnd.nextInt(3)) {
        0 -> a to a
        1 -> a to PIN_POOL99[rnd.nextInt(PIN_POOL99.size)]
        else -> a to (a + "5")
    }
}

private fun genPbkdf2Input(rnd: Random): Triple<String, String, Int> =
    Triple(
        genPin(rnd),
        PIN_SALT_POOL99[rnd.nextInt(PIN_SALT_POOL99.size)],
        PIN_ITER_POOL99[rnd.nextInt(PIN_ITER_POOL99.size)],
    )

private fun genPbkdf2InputEdge(rnd: Random): Triple<String, String, Int> =
    Triple(
        PIN_POOL99[rnd.nextInt(PIN_POOL99.size)],
        PIN_SALT_POOL99[rnd.nextInt(PIN_SALT_POOL99.size)],
        PIN_ITER_POOL99[rnd.nextInt(PIN_ITER_POOL99.size)],
    )

// A stored hash string: pbkdf2$<iter>$<salt>$<hash>, a legacy
// SHA-256 hex hash, or a malformed string. Built with the oracle
// hash functions so the stored value is verifiable by construction.
private fun genStoredHash(rnd: Random, pin: String): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4 -> {
            val salt = PIN_SALT_POOL99[rnd.nextInt(PIN_SALT_POOL99.size)]
            val iter = PIN_ITER_POOL99[rnd.nextInt(PIN_ITER_POOL99.size)]
            "pbkdf2$" + iter.toString() + "$" + salt + "$" +
                hashPbkdf2Oracle(pin, salt, iter)
        }
        5, 6 -> hashSha256LegacyOracle(
            pin, PIN_SALT_POOL99[rnd.nextInt(PIN_SALT_POOL99.size)])
        7 -> ""
        8 -> "pbkdf2\$notanint\$salt\$hash"
        else -> "pbkdf2$10$00ff"
    }

private fun genStoredHashEdge(rnd: Random, pin: String): String =
    when (rnd.nextInt(5)) {
        0, 1 -> {
            val salt = PIN_SALT_POOL99[rnd.nextInt(PIN_SALT_POOL99.size)]
            val iter = PIN_ITER_POOL99[rnd.nextInt(PIN_ITER_POOL99.size)]
            "pbkdf2$" + iter.toString() + "$" + salt + "$" +
                hashPbkdf2Oracle(pin, salt, iter)
        }
        2 -> hashSha256LegacyOracle(pin, "00ff10")
        3 -> ""
        else -> "pbkdf2$10$00ff"
    }

private fun genVerifyInput(rnd: Random): Triple<String, String, String> {
    val pin = genPin(rnd)
    val stored = genStoredHash(rnd, if (rnd.nextBoolean()) pin else genPin(rnd))
    val salt = PIN_SALT_POOL99[rnd.nextInt(PIN_SALT_POOL99.size)]
    return Triple(pin, stored, salt)
}

private fun genVerifyInputEdge(rnd: Random): Triple<String, String, String> {
    val pin = PIN_POOL99[rnd.nextInt(PIN_POOL99.size)]
    val stored = genStoredHashEdge(rnd, if (rnd.nextBoolean()) pin else PIN_POOL99[rnd.nextInt(PIN_POOL99.size)])
    return Triple(pin, stored, "00ff10")
}

    @Test
    fun pinRandom() {
        val rnd = Random(42)
        repeat(300) {
            val pin = genPin(rnd)
            val pair = genPinPair(rnd)
            val pbk = genPbkdf2Input(rnd)
            val ver = genVerifyInput(rnd)
            assertEquals(isValidPinFormatOracle(pin), PinSecurityPolicy.isValidPinFormat(pin))
            assertEquals(
                constantTimeEqualsOracle(pair.first, pair.second),
                PinSecurityPolicy.constantTimeEquals(pair.first, pair.second)
            )
            assertEquals(
                hashSha256LegacyOracle(pin, "00ff10"),
                PinSecurityPolicy.hashSha256Legacy(pin, "00ff10")
            )
            assertEquals(
                hashPbkdf2Oracle(pbk.first, pbk.second, pbk.third),
                PinSecurityPolicy.hashPbkdf2(pbk.first, pbk.second, pbk.third)
            )
            assertEquals(
                verifyPinOracle(ver.first, ver.second, ver.third, 100),
                verifyOutcomeCodeOracle(PinSecurityPolicy.verifyPin(ver.first, ver.second, ver.third, 100))
            )
        }
    }

    @Test
    fun pinEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // The edge leg cycles the pools: short/long pins, digit
            // vs letter, odd-length hex salts, tiny iteration counts,
            // and stored hashes built from the oracle itself.
            val pin = genPinEdge(rnd)
            val pair = genPinPairEdge(rnd)
            val pbk = genPbkdf2InputEdge(rnd)
            val ver = genVerifyInputEdge(rnd)
            assertEquals(isValidPinFormatOracle(pin), PinSecurityPolicy.isValidPinFormat(pin))
            assertEquals(
                constantTimeEqualsOracle(pair.first, pair.second),
                PinSecurityPolicy.constantTimeEquals(pair.first, pair.second)
            )
            assertEquals(
                hashSha256LegacyOracle(pin, "00ff10"),
                PinSecurityPolicy.hashSha256Legacy(pin, "00ff10")
            )
            assertEquals(
                hashPbkdf2Oracle(pbk.first, pbk.second, pbk.third),
                PinSecurityPolicy.hashPbkdf2(pbk.first, pbk.second, pbk.third)
            )
            assertEquals(
                verifyPinOracle(ver.first, ver.second, ver.third, 100),
                verifyOutcomeCodeOracle(PinSecurityPolicy.verifyPin(ver.first, ver.second, ver.third, 100))
            )
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // Format: 4..12 digits, nothing else.
        assertEquals(true, PinSecurityPolicy.isValidPinFormat("1234"))
        assertEquals(true, PinSecurityPolicy.isValidPinFormat("000000000000"))
        assertEquals(false, PinSecurityPolicy.isValidPinFormat("123"))
        assertEquals(false, PinSecurityPolicy.isValidPinFormat("1234567890123"))
        assertEquals(false, PinSecurityPolicy.isValidPinFormat("12a4"))
        assertEquals(false, PinSecurityPolicy.isValidPinFormat(""))
        // Constant-time compare: length first, then xor.
        assertEquals(true, PinSecurityPolicy.constantTimeEquals("1234", "1234"))
        assertEquals(false, PinSecurityPolicy.constantTimeEquals("1234", "1235"))
        assertEquals(false, PinSecurityPolicy.constantTimeEquals("1234", "12345"))
        assertEquals(true, PinSecurityPolicy.constantTimeEquals("", ""))
        // SHA-256 legacy hash is deterministic (known vector).
        assertEquals(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            PinSecurityPolicy.hashSha256Legacy("test", "")
        )
        assertEquals(
            hashSha256LegacyOracle("1234", "00ff10"),
            PinSecurityPolicy.hashSha256Legacy("1234", "00ff10")
        )
        // PBKDF2 with one iteration is deterministic.
        assertEquals(
            hashPbkdf2Oracle("1234", "00ff10", 1),
            PinSecurityPolicy.hashPbkdf2("1234", "00ff10", 1)
        )
        // Verify: blank is Corrupted, wrong pin is Mismatch, the
        // right pin at the desired iteration succeeds without upgrade.
        assertEquals(
            VerificationOutcome.Corrupted,
            PinSecurityPolicy.verifyPin("1234", "", "00ff10", 100)
        )
        val stored = "pbkdf2$100$00ff10$" + PinSecurityPolicy.hashPbkdf2("1234", "00ff10", 100)
        assertEquals(
            VerificationOutcome.Success(needsUpgrade = false, upgradedHash = null),
            PinSecurityPolicy.verifyPin("1234", stored, "00ff10", 100)
        )
        assertEquals(
            VerificationOutcome.Mismatch,
            PinSecurityPolicy.verifyPin("4321", stored, "00ff10", 100)
        )
        assertEquals(
            VerificationOutcome.Corrupted,
            PinSecurityPolicy.verifyPin("1234", "pbkdf2$10$00ff", "00ff10", 100)
        )
        // Legacy SHA-256 hash verifies but always wants an upgrade.
        val legacy = PinSecurityPolicy.hashSha256Legacy("1234", "00ff10")
        val outcome = PinSecurityPolicy.verifyPin("1234", legacy, "00ff10", 100)
        assertEquals(true, outcome is VerificationOutcome.Success)
        assertEquals(true, (outcome as VerificationOutcome.Success).needsUpgrade)
        assertEquals(
            VerificationOutcome.Mismatch,
            PinSecurityPolicy.verifyPin("4321", legacy, "00ff10", 100)
        )
    }
}
