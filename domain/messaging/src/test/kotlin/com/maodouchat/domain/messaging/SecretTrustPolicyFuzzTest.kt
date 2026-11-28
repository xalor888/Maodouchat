package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// Fuzz / random-oracle tests for SecretTrustPolicy (checklist line 931,
// 67th block). Test-only, zero production changes. The oracle is
// hand-written: trim over the probed 28-unit Kotlin whitespace set,
// blank check over the same set, unit-by-unit equality, explicit set
// membership loops. The three gates share one shape but differ at the
// third step: device/fingerprint check plain membership, forward also
// needs a non-empty whitelist -- and with the gate disabled any
// non-blank key passes, even forward with an empty whitelist. The gate
// checks use the raw key: a padded key misses a set holding the
// trimmed form.
class SecretTrustPolicyFuzzTest {

// ---------- shared oracle (all four clusters) ----------

// The probed Kotlin blank set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as blank, U+0085 NEL does not. Hand-written, never calls
// Char.isWhitespace / String.isBlank.
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

// Explicit set membership (unit-by-unit equality per member). Never
// `in` on an id set: the gate checks compare the raw key, untrimmed.
private fun containsOracle(set: Set<String>, key: String): Boolean {
    for (m in set) {
        if (equalsOracle(m, key)) return true
    }
    return false
}

private fun coerceInLongOracle(v: Long, lo: Long, hi: Long): Long =
    if (v < lo) lo else if (v > hi) hi else v

// ---------- SecretTrustPolicy oracle ----------

private fun normalizeIdsOracle(raw: Collection<String>): Set<String> {
    val out = mutableSetOf<String>()
    for (piece in raw) {
        val t = trimOracle(piece)
        if (!isBlankOracle(t)) out.add(t)
    }
    return out
}

private fun normalizeIdOracle(id: String): String = trimOracle(id)

private fun isDeviceTrustedOracle(enabled: Boolean, deviceId: String, knownDevices: Set<String>): Boolean {
    if (isBlankOracle(deviceId)) return false
    if (!enabled) return true
    return containsOracle(knownDevices, deviceId)
}

private fun isFingerprintVerifiedOracle(enabled: Boolean, fingerprint: String, verified: Set<String>): Boolean {
    if (isBlankOracle(fingerprint)) return false
    if (!enabled) return true
    return containsOracle(verified, fingerprint)
}

private fun isForwardAllowedOracle(enabled: Boolean, targetId: String, whitelist: Set<String>): Boolean {
    if (isBlankOracle(targetId)) return false
    if (!enabled) return true
    if (whitelist.isEmpty()) return false
    return containsOracle(whitelist, targetId)
}

// ---------- SecretSimChangePolicy oracle ----------

// Stored id is NOT trimmed: " x " passes through verbatim; only
// blank/null becomes null.
private fun normalizeStoredSimIdOracle(raw: String?): String? {
    if (raw == null) return null
    if (isBlankOracle(raw)) return null
    return raw
}

private fun normalizeSimIdOracle(simId: String): String = trimOracle(simId)

// Only the new id is normalized: a padded previous id still counts
// as changed against the trimmed new id.
private fun isSimChangedOracle(previousSimId: String?, newSimId: String): Boolean {
    if (previousSimId == null) return false
    return !equalsOracle(previousSimId, trimOracle(newSimId))
}

// ---------- SecretTtlPolicy oracle ----------

private fun clampTtlSecondsOracle(seconds: Long): Long =
    coerceInLongOracle(seconds, 300L, 2_592_000L)

// ---------- SecretGateTimeoutPolicy oracle ----------

private fun clampTimeoutMsOracle(timeoutMs: Long): Long =
    coerceInLongOracle(timeoutMs, 10_000L, 86_400_000L)

// Plain Long subtraction, so the JVM wraparound is identical on both
// sides; the timeout argument is NOT clamped here.
private fun isGateOpenOracle(lastVerifiedAt: Long, nowMs: Long, timeoutMs: Long): Boolean =
    lastVerifiedAt > 0L && nowMs - lastVerifiedAt < timeoutMs

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
// Latin/Greek/Cyrillic/Armenian/Georgian scripts, Greek Extended,
// ligatures, fullwidth forms, astral letters (Deseret / Adlam /
// Medefaidrin / Vithkuqi), CJK, combining marks, digits of several
// scripts, and general punctuation.
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
                // Lone surrogate (high or low): survives split/trim
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

// ---------- case-safe alphabet (lowercase/uppercase paths) ----------

// Every code point here maps 1:1 under BOTH the per-code-point simple
// mapping (Character.toLowerCase/toUpperCase, what the oracle uses) and
// the full string mapping (String.lowercase()/uppercase(), what
// production uses). Excluded on purpose: Greek (word-final sigma),
// Armenian, ligatures U+FB00-FB17, U+0130 (İ -> i + combining dot),
// U+00DF (ß -> SS when uppercased), U+0149, U+01F0, Cherokee,
// Georgian -- all special-casing code points.
private val CASE_SAFE_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00C0 to 0x00DE, 0x00E0 to 0x00FE,
    0x0100 to 0x012F, 0x0131 to 0x0148, 0x014A to 0x01EF,
    0x0300 to 0x036F,
    0x1E00 to 0x1EFF,
    0x2000 to 0x206F,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF,
    0xFF21 to 0xFF3A, 0xFF41 to 0xFF5A,
    0x10400 to 0x1044F
)

private fun randomCaseSafeCodePoint(rnd: Random): Int {
    val (lo, hi) = CASE_SAFE_RANGES[rnd.nextInt(CASE_SAFE_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomCaseSafeString(rnd: Random, maxLen: Int = 40): String {
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
            else -> {
                val cp = randomCaseSafeCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

private fun randomNullableString(rnd: Random, maxLen: Int = 40, caseSafe: Boolean = false): String? =
    if (rnd.nextInt(6) == 0) null
    else if (caseSafe) randomCaseSafeString(rnd, maxLen)
    else randomString(rnd, maxLen)

// ASCII-only random strings (letters/digits/punctuation/space): used
// for isIncoming, where String.equals(ignoreCase = true) folds
// non-ASCII letters (U+0130 İ, U+0131 ı) onto ASCII ones and the Python
// mini-model only folds ASCII. The Kotlin oracle itself
// (equalsIgnoreCaseOracle) is exact on every input; the restriction
// keeps the third differential leg exact too. Pinned tests cover the
// non-ASCII folding with JDK-probe-verified expectations.
private fun randomAsciiString(rnd: Random, maxLen: Int = 10): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) {
        sb.append((rnd.nextInt(0x20, 0x7F)).toChar())
    }
    return sb.toString()
}

private fun randomLong(rnd: Random): Long = when (rnd.nextInt(8)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L
    3 -> Long.MIN_VALUE; 4 -> Long.MAX_VALUE
    5 -> rnd.nextLong(-1000L, 1001L)
    6 -> rnd.nextLong()
    else -> rnd.nextLong(-2147483648L, 2147483648L)
}

private fun randomInt(rnd: Random): Int = when (rnd.nextInt(8)) {
    0 -> 0; 1 -> 1; 2 -> -1
    3 -> Int.MIN_VALUE; 4 -> Int.MAX_VALUE
    5 -> rnd.nextInt(-1000, 1001)
    6 -> rnd.nextInt()
    else -> rnd.nextInt(-100000, 100001)
}

// Length-biased random text: short most of the time, but regularly
// landing on the take() boundaries of this block (16/40/80/120/300/
// 800/1200/2000/4000) so truncation is exercised, not just trimming.
private fun randomBoundedLen(rnd: Random): Int = when (rnd.nextInt(12)) {
    0 -> 0; 1 -> 1; 2 -> 2; 3 -> 8; 4 -> 16; 5 -> 32
    6 -> rnd.nextInt(0, 41)
    7 -> rnd.nextInt(0, 121)
    8 -> rnd.nextInt(0, 301)
    9 -> rnd.nextInt(0, 801)
    10 -> rnd.nextInt(3990, 4011)
    else -> rnd.nextInt(0, 4101)
}

private fun randomLongText(rnd: Random): String {
    val len = randomBoundedLen(rnd)
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

private fun randomLongCaseSafeText(rnd: Random): String {
    val len = randomBoundedLen(rnd)
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0 -> { sb.append(' '); n++ }
            1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2 -> { sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++ }
            else -> {
                val cp = randomCaseSafeCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

// ---------- block-67 generators ----------

// Id-like tokens: real-looking ids plus strings that stress trim vs
// raw comparison (padded forms, case-near ids, NBSP vs NEL, lone
// surrogates, astral emoji, tab/CR inside the token).
private val ID_POOL = arrayOf(
    "", "a", "b", "d1", "d2", "user1", "user2", "Alice", "alice", "ALICE",
    "SECRET", "peer-1", "peer_2", "0", "-1", "x", "y", "id",
    "\$x", "\${x}", "\ud800", "\udc00", "\ud83d\ude10",
    "\u00a0", "\u0085", "\u200b", "\ufeff", "\u2007", "\u202f", "\u3000",
    "  padded  ", "\u0009tab\u0009", "d1 d2", "a,b", "a b",
    "a\tb", "a\rb", "D1", "d1 ", " d1", " d1 "
)

private fun randomIdToken(rnd: Random): String =
    if (rnd.nextInt(4) == 0) randomString(rnd, 8)
    else ID_POOL[rnd.nextInt(ID_POOL.size)]

private fun randomRawIds(rnd: Random): List<String> {
    val n = when (rnd.nextInt(7)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 4; 5 -> 5
        else -> rnd.nextInt(0, 7)
    }
    val out = mutableListOf<String>()
    repeat(n) { out.add(randomIdToken(rnd)) }
    return out
}

private fun randomIdSet(rnd: Random): Set<String> {
    val n = rnd.nextInt(0, 5)
    val out = mutableSetOf<String>()
    repeat(n) { out.add(randomIdToken(rnd)) }
    if (rnd.nextInt(3) == 0) out.add(randomString(rnd, 6))
    return out
}

// One time in three the key itself is planted in the set, so hit and
// miss are both exercised instead of almost-always-miss.
private fun randomGateSet(rnd: Random, key: String): Set<String> {
    val s = randomIdSet(rnd).toMutableSet()
    if (rnd.nextInt(3) == 0) s.add(key)
    return s
}

// SIM ids: padded forms are the point -- the stored form keeps its
// padding while the new form is trimmed before comparison.
private val SIM_POOL = arrayOf(
    "", " ", "  ", "sim-1", "sim-2", "x", " x ", "x ", " x",
    "sim-1 ", " sim-1", "\u00a0", "\u00a0sim-1\u00a0", "\u0085",
    "\u0085sim-1\u0085", "\ud800", "SIM-1", "sim_1", "89014103211118510720"
)

private fun randomSimPrev(rnd: Random): String? = when (rnd.nextInt(6)) {
    0 -> null
    1 -> SIM_POOL[rnd.nextInt(SIM_POOL.size)]
    else -> randomString(rnd, 8)
}

private fun randomSimNew(rnd: Random): String = when (rnd.nextInt(4)) {
    0 -> SIM_POOL[rnd.nextInt(SIM_POOL.size)]
    else -> randomString(rnd, 8)
}

// Boundary-biased longs: both clamp windows' edges (300/2_592_000 and
// 10_000/86_400_000) plus Long extremes via randomLong.
private fun randomClampLong(rnd: Random): Long = when (rnd.nextInt(14)) {
    0 -> 299L; 1 -> 300L; 2 -> 301L
    3 -> 2_592_000L - 1; 4 -> 2_592_000L; 5 -> 2_592_000L + 1
    6 -> 9_999L; 7 -> 10_000L; 8 -> 10_001L
    9 -> 86_400_000L - 1; 10 -> 86_400_000L; 11 -> 86_400_000L + 1
    else -> randomLong(rnd)
}

// Gate-open longs: extremes on both sides so nowMs - lastVerifiedAt
// wraps (last=MAX, now=MIN wraps to 1; last=1, now=MIN wraps to MAX).
private fun randomGateLong(rnd: Random): Long = when (rnd.nextInt(10)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L
    3 -> Long.MIN_VALUE; 4 -> Long.MAX_VALUE
    5 -> Long.MIN_VALUE + 1; 6 -> Long.MAX_VALUE - 1
    7 -> rnd.nextLong(-1000L, 1001L)
    8 -> rnd.nextLong(0L, 86_400_001L)
    else -> rnd.nextLong()
}

    @Test
    fun normalizeRandom() {
        val rnd = Random(42)
        repeat(500) {
            val raw = randomRawIds(rnd)
            assertEquals(normalizeIdsOracle(raw), SecretTrustPolicy.normalizeIds(raw))
            val id = randomIdToken(rnd)
            assertEquals(normalizeIdOracle(id), SecretTrustPolicy.normalizeId(id))
        }
    }

    @Test
    fun deviceAndFingerprintGatesRandom() {
        val rnd = Random(42)
        repeat(500) {
            val enabled = rnd.nextBoolean()
            val dev = randomIdToken(rnd)
            val known = randomGateSet(rnd, dev)
            assertEquals(isDeviceTrustedOracle(enabled, dev, known), SecretTrustPolicy.isDeviceTrusted(enabled, dev, known))
            val fp = randomIdToken(rnd)
            val ver = randomGateSet(rnd, fp)
            assertEquals(isFingerprintVerifiedOracle(enabled, fp, ver), SecretTrustPolicy.isFingerprintVerified(enabled, fp, ver))
        }
    }

    @Test
    fun forwardGateRandom() {
        val rnd = Random(42)
        repeat(500) {
            val enabled = rnd.nextBoolean()
            val target = randomIdToken(rnd)
            val wl = randomGateSet(rnd, target)
            assertEquals(isForwardAllowedOracle(enabled, target, wl), SecretTrustPolicy.isForwardAllowed(enabled, target, wl))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        assertEquals(emptySet<String>(), SecretTrustPolicy.normalizeIds(emptyList<String>()))
        assertEquals(emptySet<String>(), SecretTrustPolicy.normalizeIds(listOf("")))
        assertEquals(emptySet<String>(), SecretTrustPolicy.normalizeIds(listOf(" ")))
        assertEquals(setOf("d1"), SecretTrustPolicy.normalizeIds(listOf("d1")))
        assertEquals(setOf("d1"), SecretTrustPolicy.normalizeIds(listOf(" d1 ", "d1")))
        assertEquals(setOf("D1", "d1"), SecretTrustPolicy.normalizeIds(listOf("d1", "D1", "d1")))
        assertEquals(setOf(""), SecretTrustPolicy.normalizeIds(listOf(" ", "")))
        assertEquals(setOf("A", "a"), SecretTrustPolicy.normalizeIds(listOf("a", "a", " a ", "A")))
        assertEquals(setOf("\ud800"), SecretTrustPolicy.normalizeIds(listOf("\ud800", "\ud800")))
        assertEquals(setOf("padded"), SecretTrustPolicy.normalizeIds(listOf("  padded  ", "padded")))
        assertEquals("", SecretTrustPolicy.normalizeId(""))
        assertEquals("", SecretTrustPolicy.normalizeId(" "))
        assertEquals("a", SecretTrustPolicy.normalizeId(" a "))
        assertEquals("a", SecretTrustPolicy.normalizeId("a"))
        assertEquals("abc", SecretTrustPolicy.normalizeId("  abc  "))
        assertEquals("\ud800", SecretTrustPolicy.normalizeId("\ud800"))
        assertEquals(false, SecretTrustPolicy.isDeviceTrusted(true, " ", setOf("d1")))
        assertEquals(false, SecretTrustPolicy.isDeviceTrusted(false, "", setOf("d1")))
        assertEquals(true, SecretTrustPolicy.isDeviceTrusted(false, "unknown", emptySet<String>()))
        assertEquals(true, SecretTrustPolicy.isDeviceTrusted(true, "d1", setOf("d1")))
        assertEquals(false, SecretTrustPolicy.isDeviceTrusted(true, "d1", setOf("d2")))
        assertEquals(false, SecretTrustPolicy.isDeviceTrusted(true, " d1 ", setOf("d1")))
        assertEquals(false, SecretTrustPolicy.isDeviceTrusted(true, "d1", setOf(" d1 ")))
        assertEquals(false, SecretTrustPolicy.isDeviceTrusted(true, " ", setOf("x")))
        assertEquals(true, SecretTrustPolicy.isDeviceTrusted(true, "", setOf("")))
        assertEquals(true, SecretTrustPolicy.isDeviceTrusted(false, "d1", setOf("other")))
        assertEquals(false, SecretTrustPolicy.isFingerprintVerified(true, " ", setOf("d1")))
        assertEquals(false, SecretTrustPolicy.isFingerprintVerified(false, "", setOf("d1")))
        assertEquals(true, SecretTrustPolicy.isFingerprintVerified(false, "unknown", emptySet<String>()))
        assertEquals(true, SecretTrustPolicy.isFingerprintVerified(true, "d1", setOf("d1")))
        assertEquals(false, SecretTrustPolicy.isFingerprintVerified(true, "d1", setOf("d2")))
        assertEquals(false, SecretTrustPolicy.isFingerprintVerified(true, " d1 ", setOf("d1")))
        assertEquals(false, SecretTrustPolicy.isFingerprintVerified(true, "d1", setOf(" d1 ")))
        assertEquals(false, SecretTrustPolicy.isFingerprintVerified(true, " ", setOf("x")))
        assertEquals(true, SecretTrustPolicy.isFingerprintVerified(true, "", setOf("")))
        assertEquals(true, SecretTrustPolicy.isFingerprintVerified(false, "d1", setOf("other")))
        assertEquals(false, SecretTrustPolicy.isForwardAllowed(true, " ", setOf("c1")))
        assertEquals(false, SecretTrustPolicy.isForwardAllowed(false, "", setOf("c1")))
        assertEquals(true, SecretTrustPolicy.isForwardAllowed(false, "c9", emptySet<String>()))
        assertEquals(false, SecretTrustPolicy.isForwardAllowed(true, "c1", emptySet<String>()))
        assertEquals(true, SecretTrustPolicy.isForwardAllowed(true, "c1", setOf("c1", "c2")))
        assertEquals(false, SecretTrustPolicy.isForwardAllowed(true, "c3", setOf("c1", "c2")))
        assertEquals(false, SecretTrustPolicy.isForwardAllowed(true, " c1 ", setOf("c1")))
        assertEquals(false, SecretTrustPolicy.isForwardAllowed(true, " ", setOf("c1")))
        assertEquals(true, SecretTrustPolicy.isForwardAllowed(true, "", setOf("")))
        assertEquals(true, SecretTrustPolicy.isForwardAllowed(false, "c1", setOf("other")))
    }
}
