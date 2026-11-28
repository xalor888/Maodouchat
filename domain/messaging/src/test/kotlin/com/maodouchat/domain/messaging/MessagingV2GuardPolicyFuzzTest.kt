// Fuzz / random-oracle tests for MessagingV2GuardPolicy (checklist line 931,
// 78th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. shouldDeadLetter on 3 permanent codes or >= 8 attempts; isGroupControl on {SENDER_KEY, KEY_REQUEST}; isStale needs a group-control kind, liveRevision > 0 and queued != live; ordering kinds {DATA, EVENT} causal vs {SENDER_KEY, KEY_REQUEST, RECEIPT} bypass; service envelopes need device 0 + SERVICE_PLAINTEXT + bot_ prefix or system; OutboxRetryGuard null -> ENQUEUE, conversation mismatch, kind != DATA -> KIND_MISMATCH, else RETRY; isSameOutbox is 3 equalities; SenderKeyRequestValidation reports the first problem.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagingV2GuardPolicyFuzzTest {

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


// ---------- MessagingV2 guard policies oracle ----------

private fun shouldDeadLetterOracle(errorCode: String, attemptsAfterFailure: Int): Boolean =
    equalsOracle(errorCode, "messaging_v2_wrong_device") ||
        equalsOracle(errorCode, "messaging_v2_unsupported_ciphertext") ||
        equalsOracle(errorCode, "messaging_v2_sender_key_install_failed") ||
        attemptsAfterFailure >= 8

private fun isGroupControlOracle(kind: String): Boolean =
    equalsOracle(kind, "SENDER_KEY") || equalsOracle(kind, "KEY_REQUEST")

private fun isStaleOracle(kind: String, queuedRevision: Long?, liveRevision: Long): Boolean =
    isGroupControlOracle(kind) && liveRevision > 0L && queuedRevision != liveRevision

private fun requiresConversationOrderOracle(kind: String): Boolean =
    equalsOracle(kind, "DATA") || equalsOracle(kind, "EVENT")

private fun mayBypassConversationOrderOracle(kind: String): Boolean =
    equalsOracle(kind, "SENDER_KEY") || equalsOracle(kind, "KEY_REQUEST") ||
        equalsOracle(kind, "RECEIPT")

private fun acceptsServiceEnvelopeOracle(
    senderUserId: String,
    senderDeviceId: Int,
    ciphertextType: String,
): Boolean =
    senderDeviceId == 0 &&
        equalsOracle(ciphertextType, "SERVICE_PLAINTEXT") &&
        (startsWithOracle(senderUserId, "bot_") || equalsOracle(senderUserId, "system"))

private fun decideRetryGuardOracle(
    existingConversationId: String?,
    existingKind: String?,
    conversationId: String,
): String = when {
    existingConversationId == null -> "ENQUEUE"
    !equalsOracle(existingConversationId, conversationId) -> "CONVERSATION_MISMATCH"
    existingKind == null || !equalsOracle(existingKind, "DATA") -> "KIND_MISMATCH"
    else -> "RETRY"
}

private fun isSameOutboxOracle(
    existingConversationId: String,
    existingKind: String,
    existingPayload: String,
    conversationId: String,
    kind: String,
    payload: String,
): Boolean =
    equalsOracle(existingConversationId, conversationId) &&
        equalsOracle(existingKind, kind) &&
        equalsOracle(existingPayload, payload)

private fun validateSenderKeyOracle(
    requestedSenderUserId: String,
    groupRevision: Long,
    failedMessageId: String,
): String? = when {
    isBlankOracle(requestedSenderUserId) -> "SENDER_MISSING"
    groupRevision <= 0L -> "REVISION_MISSING"
    isBlankOracle(failedMessageId) -> "MESSAGE_MISSING"
    else -> null
}

private val GUARD_KIND_POOL = listOf(
    "DATA", "EVENT", "SENDER_KEY", "KEY_REQUEST", "RECEIPT", "",
    " ", "data", "Data", "SENDER_KEY ", " SENDER_KEY", "KEY_REQUESTS",
    "CONTROL", "MESSAGE", "sender_key", "receipt"
)

private val GUARD_ERROR_POOL = listOf(
    "", " ", "messaging_v2_wrong_device",
    "messaging_v2_unsupported_ciphertext",
    "messaging_v2_sender_key_install_failed",
    "messaging_v2_wrong_device ", " messaging_v2_wrong_device",
    "MESSAGING_V2_WRONG_DEVICE", "messaging_v2_other",
    "timeout", "network_error"
)

private val GUARD_CIPHER_POOL = listOf(
    "SERVICE_PLAINTEXT", "service_plaintext", "Service_Plaintext",
    "SERVICE_PLAINTEXT ", " SERVICE_PLAINTEXT", "", " ", "SIGNAL",
    "SERVICE_CIPHERTEXT"
)

private val GUARD_SENDER_POOL = listOf(
    "bot_1", "bot_", "bot", "system", "System", "SYSTEM", "bot_x",
    "user_1", "", " ", "bot_system", "system ", " system", "bots_1"
)

private fun genGuardKind(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> GUARD_KIND_POOL[rnd.nextInt(GUARD_KIND_POOL.size)]
        6 -> randomToken(rnd)
        7 -> randomWord(rnd)
        else -> randomString(rnd, 12)
    }

private fun genGuardError(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> GUARD_ERROR_POOL[rnd.nextInt(GUARD_ERROR_POOL.size)]
        6 -> randomToken(rnd)
        7 -> randomWord(rnd)
        else -> randomString(rnd, 16)
    }

private fun genGuardAttempts(rnd: Random): Int =
    when (rnd.nextInt(10)) {
        0 -> 0
        1 -> 7
        2 -> 8
        3 -> 9
        4 -> -1
        5 -> rnd.nextInt(-5, 20)
        6 -> Int.MAX_VALUE
        7 -> Int.MIN_VALUE
        else -> rnd.nextInt()
    }

private fun genGuardRevision(rnd: Random): Long =
    when (rnd.nextInt(10)) {
        0 -> 0L
        1 -> 1L
        2 -> -1L
        3 -> 2L
        4 -> Long.MAX_VALUE
        5 -> Long.MIN_VALUE
        6 -> rnd.nextLong(-10L, 10L)
        else -> rnd.nextLong()
    }

private fun genGuardNullableRevision(rnd: Random): Long? =
    when (rnd.nextInt(10)) {
        0, 1 -> null
        else -> genGuardRevision(rnd)
    }

private fun genGuardDeviceId(rnd: Random): Int =
    when (rnd.nextInt(10)) {
        0 -> 0
        1 -> 1
        2 -> -1
        3 -> Int.MAX_VALUE
        4 -> Int.MIN_VALUE
        else -> rnd.nextInt(-5, 5)
    }

private fun genGuardNullableStr(rnd: Random): String? =
    when (rnd.nextInt(10)) {
        0, 1 -> null
        2 -> ""
        3 -> " "
        4, 5 -> randomWord(rnd)
        6 -> randomToken(rnd)
        else -> randomString(rnd, 10)
    }

    @Test
    fun guardRandom() {
        val rnd = Random(42)
        repeat(300) {
            val kind = genGuardKind(rnd)
            val error = genGuardError(rnd)
            val attempts = genGuardAttempts(rnd)
            val queued = genGuardNullableRevision(rnd)
            val live = genGuardRevision(rnd)
            val sender = when (rnd.nextInt(10)) {
                0, 1, 2, 3, 4, 5 -> GUARD_SENDER_POOL[rnd.nextInt(GUARD_SENDER_POOL.size)]
                6 -> randomToken(rnd)
                7 -> randomWord(rnd)
                else -> randomString(rnd, 12)
            }
            val deviceId = genGuardDeviceId(rnd)
            val cipher = when (rnd.nextInt(10)) {
                0, 1, 2, 3, 4, 5 -> GUARD_CIPHER_POOL[rnd.nextInt(GUARD_CIPHER_POOL.size)]
                6 -> randomToken(rnd)
                else -> randomString(rnd, 12)
            }
            val existingConv = genGuardNullableStr(rnd)
            val existingKind = genGuardNullableStr(rnd)
            val conv = genGuardNullableStr(rnd) ?: "conv-1"
            val payload = genGuardNullableStr(rnd) ?: ""
            val revision = genGuardRevision(rnd)
            val msgId = genGuardNullableStr(rnd) ?: ""
            assertEquals(
                shouldDeadLetterOracle(error, attempts),
                MessagingV2InboxFailurePolicy.shouldDeadLetter(error, attempts)
            )
            assertEquals(isGroupControlOracle(kind), MessagingV2GroupControlPolicy.isGroupControl(kind))
            assertEquals(
                isStaleOracle(kind, queued, live),
                MessagingV2GroupControlPolicy.isStale(kind, queued, live)
            )
            assertEquals(
                requiresConversationOrderOracle(kind),
                MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind)
            )
            assertEquals(
                mayBypassConversationOrderOracle(kind),
                MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind)
            )
            assertEquals(
                acceptsServiceEnvelopeOracle(sender, deviceId, cipher),
                MessagingV2ServiceEnvelopePolicy.accepts(sender, deviceId, cipher)
            )
            assertEquals(
                decideRetryGuardOracle(existingConv, existingKind, conv),
                OutboxRetryGuard.decide(existingConv, existingKind, conv).name
            )
            assertEquals(
                isSameOutboxOracle(existingConv ?: "", existingKind ?: "", payload, conv, kind, payload),
                OutboxIdempotencyPolicy.isSameOutbox(existingConv ?: "", existingKind ?: "", payload, conv, kind, payload)
            )
            assertEquals(
                validateSenderKeyOracle(sender, revision, msgId),
                SenderKeyRequestValidation.validate(sender, revision, msgId)?.name
            )
        }
    }

    @Test
    fun guardKindRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Kind-heavy: set-membership boundaries decide every branch.
            val kind = genGuardKind(rnd)
            val queued = genGuardNullableRevision(rnd)
            val live = genGuardRevision(rnd)
            assertEquals(isGroupControlOracle(kind), MessagingV2GroupControlPolicy.isGroupControl(kind))
            assertEquals(
                isStaleOracle(kind, queued, live),
                MessagingV2GroupControlPolicy.isStale(kind, queued, live)
            )
            assertEquals(
                requiresConversationOrderOracle(kind),
                MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind)
            )
            assertEquals(
                mayBypassConversationOrderOracle(kind),
                MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind)
            )
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // Dead-letter: 3 permanent codes or >= 8 attempts.
        assertEquals(true, MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_wrong_device", 0))
        assertEquals(true, MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_unsupported_ciphertext", 0))
        assertEquals(true, MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_sender_key_install_failed", 0))
        assertEquals(true, MessagingV2InboxFailurePolicy.shouldDeadLetter("other", 8))
        assertEquals(true, MessagingV2InboxFailurePolicy.shouldDeadLetter("other", 100))
        assertEquals(false, MessagingV2InboxFailurePolicy.shouldDeadLetter("other", 7))
        assertEquals(false, MessagingV2InboxFailurePolicy.shouldDeadLetter("", 0))
        assertEquals(false, MessagingV2InboxFailurePolicy.shouldDeadLetter("messaging_v2_wrong_device ", 0))
        assertEquals(8, MessagingV2InboxFailurePolicy.MAX_RECOVERABLE_ATTEMPTS)
        // Group control kinds.
        assertEquals(true, MessagingV2GroupControlPolicy.isGroupControl("SENDER_KEY"))
        assertEquals(true, MessagingV2GroupControlPolicy.isGroupControl("KEY_REQUEST"))
        assertEquals(false, MessagingV2GroupControlPolicy.isGroupControl("DATA"))
        assertEquals(false, MessagingV2GroupControlPolicy.isGroupControl(""))
        assertEquals(true, MessagingV2GroupControlPolicy.isStale("SENDER_KEY", 1L, 2L))
        assertEquals(true, MessagingV2GroupControlPolicy.isStale("SENDER_KEY", null, 2L))
        assertEquals(false, MessagingV2GroupControlPolicy.isStale("SENDER_KEY", 2L, 2L))
        assertEquals(false, MessagingV2GroupControlPolicy.isStale("SENDER_KEY", 1L, 0L))
        assertEquals(false, MessagingV2GroupControlPolicy.isStale("DATA", 1L, 2L))
        assertEquals(false, MessagingV2GroupControlPolicy.isStale("SENDER_KEY", 1L, -1L))
        // Ordering kinds.
        assertEquals(true, MessagingV2OutboxOrderingPolicy.requiresConversationOrder("DATA"))
        assertEquals(true, MessagingV2OutboxOrderingPolicy.requiresConversationOrder("EVENT"))
        assertEquals(false, MessagingV2OutboxOrderingPolicy.requiresConversationOrder("SENDER_KEY"))
        assertEquals(true, MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder("RECEIPT"))
        assertEquals(true, MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder("SENDER_KEY"))
        assertEquals(false, MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder("DATA"))
        // Service envelopes: device 0 + SERVICE_PLAINTEXT + bot_/system.
        assertEquals(true, MessagingV2ServiceEnvelopePolicy.accepts("bot_1", 0, "SERVICE_PLAINTEXT"))
        assertEquals(true, MessagingV2ServiceEnvelopePolicy.accepts("system", 0, "SERVICE_PLAINTEXT"))
        assertEquals(true, MessagingV2ServiceEnvelopePolicy.accepts("bot_", 0, "SERVICE_PLAINTEXT"))
        assertEquals(false, MessagingV2ServiceEnvelopePolicy.accepts("bot", 0, "SERVICE_PLAINTEXT"))
        assertEquals(false, MessagingV2ServiceEnvelopePolicy.accepts("System", 0, "SERVICE_PLAINTEXT"))
        assertEquals(false, MessagingV2ServiceEnvelopePolicy.accepts("bot_1", 1, "SERVICE_PLAINTEXT"))
        assertEquals(false, MessagingV2ServiceEnvelopePolicy.accepts("bot_1", 0, "SIGNAL"))
        // Retry guard decision order.
        assertEquals(OutboxRetryGuard.Decision.ENQUEUE, OutboxRetryGuard.decide(null, null, "c1"))
        assertEquals(OutboxRetryGuard.Decision.ENQUEUE, OutboxRetryGuard.decide(null, "DATA", "c1"))
        assertEquals(
            OutboxRetryGuard.Decision.CONVERSATION_MISMATCH,
            OutboxRetryGuard.decide("c2", "DATA", "c1")
        )
        assertEquals(
            OutboxRetryGuard.Decision.KIND_MISMATCH,
            OutboxRetryGuard.decide("c1", "EVENT", "c1")
        )
        assertEquals(
            OutboxRetryGuard.Decision.KIND_MISMATCH,
            OutboxRetryGuard.decide("c1", null, "c1")
        )
        assertEquals(OutboxRetryGuard.Decision.RETRY, OutboxRetryGuard.decide("c1", "DATA", "c1"))
        // Idempotency: all three fields must match.
        assertEquals(true, OutboxIdempotencyPolicy.isSameOutbox("c1", "DATA", "p", "c1", "DATA", "p"))
        assertEquals(false, OutboxIdempotencyPolicy.isSameOutbox("c2", "DATA", "p", "c1", "DATA", "p"))
        assertEquals(false, OutboxIdempotencyPolicy.isSameOutbox("c1", "EVENT", "p", "c1", "DATA", "p"))
        assertEquals(false, OutboxIdempotencyPolicy.isSameOutbox("c1", "DATA", "q", "c1", "DATA", "p"))
        // Sender-key validation: first problem wins.
        assertEquals(
            SenderKeyRequestValidation.Problem.SENDER_MISSING,
            SenderKeyRequestValidation.validate("", 1L, "m1")
        )
        assertEquals(
            SenderKeyRequestValidation.Problem.SENDER_MISSING,
            SenderKeyRequestValidation.validate("   ", 0L, "")
        )
        assertEquals(
            SenderKeyRequestValidation.Problem.REVISION_MISSING,
            SenderKeyRequestValidation.validate("u1", 0L, "m1")
        )
        assertEquals(
            SenderKeyRequestValidation.Problem.REVISION_MISSING,
            SenderKeyRequestValidation.validate("u1", -5L, "")
        )
        assertEquals(
            SenderKeyRequestValidation.Problem.MESSAGE_MISSING,
            SenderKeyRequestValidation.validate("u1", 3L, "  ")
        )
        assertEquals(null, SenderKeyRequestValidation.validate("u1", 3L, "m1"))
    }
}
