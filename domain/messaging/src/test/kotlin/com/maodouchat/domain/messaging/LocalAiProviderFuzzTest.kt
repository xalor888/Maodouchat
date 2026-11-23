// Fuzz / random-oracle tests for LocalAiProvider (checklist line 931,
// 102nd block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. LocalAiProvider: four int clamps, vision-capability model-name match, and resolved endpoint URLs.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalAiProviderFuzzTest {

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

// ---------- block-102 common oracle helpers ----------

// endsWith / trimEnd('/') helpers. NOTE on trim: Kotlin
// String.trim() strips Character.isWhitespace units -- the same
// 28-unit set trimOracle (ORACLE_SHARED) strips -- NOT the <= 0x20
// set Java's String.trim() strips. (Probe-verified this block:
// production trim() drops leading U+1680 and trailing U+202F.) So
// the provider oracle uses trimOracle, never a <= 0x20 trim.
private fun endsWithOracle(s: String, suffix: String): Boolean {
    if (suffix.length > s.length) return false
    for (i in suffix.indices) {
        if (s[s.length - suffix.length + i] != suffix[i]) return false
    }
    return true
}

// Kotlin String.trimEnd('/'): strip trailing '/' units only.
private fun trimEndSlashOracle(s: String): String {
    var hi = s.length
    while (hi > 0 && s[hi - 1] == '/') hi--
    return s.substring(0, hi)
}

// ---------- LocalAiProvider oracle ----------

// Hand-written reimplementation of LocalAiProvider (data-class
// members, LocalAiProviderModels.kt). The clamps are plain
// coerceIn; vision is an explicit flag OR a case-folded model-name
// match (trim is Kotlin trim -- the 28-unit isWhitespace set --
// then full lowercase, spelled out per code point by
// lowercaseOracle); the
// resolved URLs trim, strip trailing slashes, and append their
// suffix only when absent.
private fun clampedHistoryLimitOracle(v: Int): Int =
    if (v < 4) 4 else if (v > 200) 200 else v

private fun clampedMaxTokensOracle(v: Int): Int =
    if (v < 16) 16 else if (v > 128000) 128000 else v

private fun clampedContextWindowOracle(v: Int): Int =
    if (v < 1024) 1024 else if (v > 2000000) 2000000 else v

private fun clampedTimeoutSecondsOracle(v: Int): Int =
    if (v < 15) 15 else if (v > 300) 300 else v

private fun hasVisionCapabilityOracle(model: String, supportsVision: Boolean): Boolean {
    if (supportsVision) return true
    val m = lowercaseOracle(trimOracle(model))
    return containsOracle(m, "vision") ||
        containsOracle(m, "4o") ||
        containsOracle(m, "claude-3") ||
        containsOracle(m, "gemini") ||
        containsOracle(m, "qwen-vl") ||
        containsOracle(m, "vl-") ||
        endsWithOracle(m, "-vl") ||
        containsOracle(m, "pixtral") ||
        containsOracle(m, "llava") ||
        containsOracle(m, "multimodal")
}

private fun resolvedChatCompletionsUrlOracle(baseUrl: String): String {
    val base = trimEndSlashOracle(trimOracle(baseUrl))
    return if (endsWithOracle(base, "/chat/completions")) base else base + "/chat/completions"
}

private fun resolvedResponsesUrlOracle(baseUrl: String): String {
    val base = trimEndSlashOracle(trimOracle(baseUrl))
    return if (endsWithOracle(base, "/responses")) base else base + "/responses"
}

private fun resolvedAnthropicUrlOracle(baseUrl: String): String {
    val base = trimEndSlashOracle(trimOracle(baseUrl))
    return if (endsWithOracle(base, "/messages")) base else base + "/v1/messages"
}

private val PROVIDER_INT_POOL102 = arrayOf(
    -2147483648, -100, -1, 0, 1, 3, 4, 5, 15, 16, 17, 199, 200, 201,
    299, 300, 301, 1023, 1024, 1025, 127999, 128000, 128001,
    1999999, 2000000, 2000001, 2147483647,
)

private val PROVIDER_MODEL_POOL102 = arrayOf(
    "", " ", "gpt-4o", "GPT-4O", "gpt-4o-mini", "claude-3-opus", "CLAUDE-3",
    "gemini-pro", "qwen-vl-chat", "QWEN-VL", "model-vl", "model-VL",
    "pixtral-12b", "llava-13b", "multimodal-x", "gpt-3.5-turbo", "llama-3.2",
    "vision", "VISION", "  vision  ", "\tvision\n", "4o", "a4o", "vl-",
    "-vl", "x-vl", "vl", "v", "claude-3.5-sonnet", "gemini-1.5-flash",
    "qwen2-vl-7b", "my-vision-model", "novisionhere", "4", "o", "  ",
    "   vision", "vision   ", "ViSiOn", "GEMINI", "Pixtral", "LLaVA",
    "multiModal", "claude-3-", "3", "v-l", "-v-l", "x4oy",
)

private val PROVIDER_BASE_POOL102 = arrayOf(
    "", " ", "https://api.openai.com/v1", "https://api.openai.com/v1/",
    "https://api.openai.com/v1//", "https://api.openai.com/v1/chat/completions",
    "https://api.openai.com/v1/chat/completions/",
    "https://api.openai.com/v1/responses",
    "https://api.anthropic.com/v1/messages",
    "https://api.anthropic.com/v1/messages/",
    "https://x/v1/messages", "https://x/messages",
    "https://x/chat/completions", "  https://x/v1  ", "https://x/v1///",
    "/", "//", "http://localhost:8080", "https://x/v1/chat",
    "https://x/v1/response", "https://x/v1/message",
    "https://x/chat/completions ", "https://x/CHAT/COMPLETIONS",
)

private fun genProviderInt(rnd: Random): Int =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> PROVIDER_INT_POOL102[rnd.nextInt(PROVIDER_INT_POOL102.size)]
        6 -> rnd.nextInt(400) - 20
        else -> rnd.nextInt()
    }

private fun genProviderModel(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> PROVIDER_MODEL_POOL102[rnd.nextInt(PROVIDER_MODEL_POOL102.size)]
        6 -> randomToken(rnd)
        else -> randomString(rnd, 20)
    }

private fun genProviderBase(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> PROVIDER_BASE_POOL102[rnd.nextInt(PROVIDER_BASE_POOL102.size)]
        6 -> randomToken(rnd)
        else -> randomString(rnd, 30)
    }

private fun providerOf(
    history: Int, maxTokens: Int, ctx: Int, timeout: Int,
    model: String, supportsVision: Boolean, baseUrl: String,
): LocalAiProvider = LocalAiProvider(
    id = "p", name = "t", baseUrl = baseUrl, apiKey = "k", model = model,
    supportsVision = supportsVision, maxTokens = maxTokens,
    contextWindowTokens = ctx, historyMessageLimit = history,
    timeoutSeconds = timeout,
)

    @Test
    fun localAiProviderRandom() {
        val rnd = Random(42)
        repeat(300) {
            val history = genProviderInt(rnd)
            val maxTokens = genProviderInt(rnd)
            val ctx = genProviderInt(rnd)
            val timeout = genProviderInt(rnd)
            val model = genProviderModel(rnd)
            val supportsVision = rnd.nextBoolean()
            val baseUrl = genProviderBase(rnd)
            val p = providerOf(history, maxTokens, ctx, timeout, model, supportsVision, baseUrl)
            assertEquals(clampedHistoryLimitOracle(history), p.clampedHistoryLimit())
            assertEquals(clampedMaxTokensOracle(maxTokens), p.clampedMaxTokens())
            assertEquals(clampedContextWindowOracle(ctx), p.clampedContextWindow())
            assertEquals(clampedTimeoutSecondsOracle(timeout), p.clampedTimeoutSeconds())
            assertEquals(hasVisionCapabilityOracle(model, supportsVision), p.hasVisionCapability())
            assertEquals(resolvedChatCompletionsUrlOracle(baseUrl), p.resolvedChatCompletionsUrl())
            assertEquals(resolvedResponsesUrlOracle(baseUrl), p.resolvedResponsesUrl())
            assertEquals(resolvedAnthropicUrlOracle(baseUrl), p.resolvedAnthropicUrl())
        }
    }

    @Test
    fun localAiProviderEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // The edge leg cycles the int pool (Int bounds, every
            // clamp edge), the model pool (every needle, padded and
            // wrong-case near-misses) and the base-URL pool
            // (trailing slashes, already-suffixed, blank).
            val history = PROVIDER_INT_POOL102[rnd.nextInt(PROVIDER_INT_POOL102.size)]
            val maxTokens = PROVIDER_INT_POOL102[rnd.nextInt(PROVIDER_INT_POOL102.size)]
            val ctx = PROVIDER_INT_POOL102[rnd.nextInt(PROVIDER_INT_POOL102.size)]
            val timeout = PROVIDER_INT_POOL102[rnd.nextInt(PROVIDER_INT_POOL102.size)]
            val model = PROVIDER_MODEL_POOL102[rnd.nextInt(PROVIDER_MODEL_POOL102.size)]
            val supportsVision = rnd.nextBoolean()
            val baseUrl = PROVIDER_BASE_POOL102[rnd.nextInt(PROVIDER_BASE_POOL102.size)]
            val p = providerOf(history, maxTokens, ctx, timeout, model, supportsVision, baseUrl)
            assertEquals(clampedHistoryLimitOracle(history), p.clampedHistoryLimit())
            assertEquals(clampedMaxTokensOracle(maxTokens), p.clampedMaxTokens())
            assertEquals(clampedContextWindowOracle(ctx), p.clampedContextWindow())
            assertEquals(clampedTimeoutSecondsOracle(timeout), p.clampedTimeoutSeconds())
            assertEquals(hasVisionCapabilityOracle(model, supportsVision), p.hasVisionCapability())
            assertEquals(resolvedChatCompletionsUrlOracle(baseUrl), p.resolvedChatCompletionsUrl())
            assertEquals(resolvedResponsesUrlOracle(baseUrl), p.resolvedResponsesUrl())
            assertEquals(resolvedAnthropicUrlOracle(baseUrl), p.resolvedAnthropicUrl())
        }
    }

    @Test
    fun localAiProviderPinned() {
        val p = providerOf(1, 1, 10, 1, "gpt-3.5-turbo", false, "https://api.openai.com/v1/")
        assertEquals(4, p.clampedHistoryLimit())
        assertEquals(16, p.clampedMaxTokens())
        assertEquals(1024, p.clampedContextWindow())
        assertEquals(15, p.clampedTimeoutSeconds())
        assertEquals(false, p.hasVisionCapability())
        assertEquals("https://api.openai.com/v1/chat/completions", p.resolvedChatCompletionsUrl())
        assertEquals("https://api.openai.com/v1/responses", p.resolvedResponsesUrl())
        assertEquals("https://api.openai.com/v1/v1/messages", p.resolvedAnthropicUrl())
        val hi = providerOf(999, 999999, 9999999, 999, "x", false, "https://x/v1")
        assertEquals(200, hi.clampedHistoryLimit())
        assertEquals(128000, hi.clampedMaxTokens())
        assertEquals(2000000, hi.clampedContextWindow())
        assertEquals(300, hi.clampedTimeoutSeconds())
        // Vision needles: exact, case-folded, padded, endsWith.
        assertEquals(true, providerOf(24, 4096, 128000, 120, "gpt-4o-mini", false, "https://x").hasVisionCapability())
        assertEquals(true, providerOf(24, 4096, 128000, 120, "GPT-4O", false, "https://x").hasVisionCapability())
        assertEquals(true, providerOf(24, 4096, 128000, 120, "  VISION  ", false, "https://x").hasVisionCapability())
        assertEquals(true, providerOf(24, 4096, 128000, 120, "model-VL", false, "https://x").hasVisionCapability())
        assertEquals(true, providerOf(24, 4096, 128000, 120, "qwen2-vl-7b", false, "https://x").hasVisionCapability())
        assertEquals(true, providerOf(24, 4096, 128000, 120, "novisionhere", false, "https://x").hasVisionCapability())
        assertEquals(true, providerOf(24, 4096, 128000, 120, "gpt-3.5-turbo", true, "https://x").hasVisionCapability())
        assertEquals(false, providerOf(24, 4096, 128000, 120, "gpt-3.5-turbo", false, "https://x").hasVisionCapability())
        assertEquals(false, providerOf(24, 4096, 128000, 120, "", false, "https://x").hasVisionCapability())
        assertEquals(false, providerOf(24, 4096, 128000, 120, "v-l", false, "https://x").hasVisionCapability())
        // URLs: already-suffixed stays put, blank base gets the suffix.
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            providerOf(24, 4096, 128000, 120, "x", false, "https://api.anthropic.com/v1/messages").resolvedAnthropicUrl()
        )
        assertEquals(
            "/chat/completions",
            providerOf(24, 4096, 128000, 120, "x", false, "").resolvedChatCompletionsUrl()
        )
        assertEquals(
            "/responses",
            providerOf(24, 4096, 128000, 120, "x", false, "/").resolvedResponsesUrl()
        )
        assertEquals(
            "/v1/messages",
            providerOf(24, 4096, 128000, 120, "x", false, "///").resolvedAnthropicUrl()
        )
    }
}
