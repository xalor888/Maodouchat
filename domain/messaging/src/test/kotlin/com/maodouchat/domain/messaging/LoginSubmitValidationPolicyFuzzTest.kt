// Fuzz / random-oracle tests for LoginSubmitValidationPolicy (checklist line 931,
// 77th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. validateEmail short-circuits blank, then the injected predicate; validate chains email, password blank, password < 6, then tab 1 register checks (codeSent, name, confirm, code) / tab 2 reset checks (codeSent, code).
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LoginSubmitValidationPolicyFuzzTest {

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

// trimStart('-', '*', '•'): strip leading chars in the given set.
private fun trimStartCharsOracle(s: String): String {
    var i = 0
    while (i < s.length && (s[i] == '-' || s[i] == '*' || s[i] == '•')) i++
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

// ---------- LoginSubmitValidationPolicy oracle ----------

// The injected predicate, by id (the same three lambdas the probe
// and the test use): 0 = contains "@"; 1 = contains "@" and ".";
// 2 = length >= 5 and contains "@".
private fun emailPredOracle(email: String, pred: Int): Boolean = when (pred) {
    0 -> containsOracle(email, "@")
    1 -> containsOracle(email, "@") && containsOracle(email, ".")
    else -> email.length >= 5 && containsOracle(email, "@")
}

private fun validateEmailOracle(email: String, pred: Int): String? {
    if (isBlankOracle(email)) return "EmailBlank"
    if (!emailPredOracle(email, pred)) return "EmailInvalid"
    return null
}

private fun validateOracle(
    email: String,
    password: String,
    passwordConfirm: String,
    name: String,
    code: String,
    codeSent: Boolean,
    selectedTab: Int,
    pred: Int,
): String? {
    validateEmailOracle(email, pred)?.let { return it }
    if (isBlankOracle(password)) return "PasswordBlank"
    if (password.length < 6) return "PasswordTooShort"
    when (selectedTab) {
        1 -> {
            if (!codeSent) return "CodeNotSent"
            if (isBlankOracle(name)) return "NameBlank"
            if (isBlankOracle(passwordConfirm) || !equalsOracle(passwordConfirm, password)) {
                return "PasswordConfirmMismatch"
            }
            if (isBlankOracle(code)) return "CodeBlank"
        }
        2 -> {
            if (!codeSent) return "CodeNotSent"
            if (isBlankOracle(code)) return "CodeBlank"
        }
    }
    return null
}

private val LOGIN_EMAIL_POOL = listOf(
    "", " ", "   ", "a@b.com", "a@b", "a.b", "plain", "a@",
    "@b.com", "  a@b.com  ", "a@b@c.com", "x".repeat(300), "a@b.",
    "ü@b.com", "a b@c.com"
)

private fun genLoginEmail(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> LOGIN_EMAIL_POOL[rnd.nextInt(LOGIN_EMAIL_POOL.size)]
        6 -> randomToken(rnd)
        7 -> randomWord(rnd)
        else -> randomString(rnd, 24)
    }

private fun genLoginPassword(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0 -> ""
        1 -> " "
        2 -> "   "
        3 -> "12345"
        4 -> "123456"
        5 -> "password"
        6 -> "a".repeat(rnd.nextInt(0, 12))
        7 -> randomToken(rnd)
        8 -> randomWord(rnd)
        else -> randomString(rnd, 16)
    }

private fun genLoginShort(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0 -> ""
        1 -> " "
        2 -> "   "
        3 -> "x"
        4 -> randomWord(rnd)
        5 -> randomToken(rnd)
        else -> randomString(rnd, 12)
    }

private fun genLoginTab(rnd: Random): Int =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3 -> 0
        4, 5, 6 -> 1
        7, 8 -> 2
        else -> rnd.nextInt(-2, 5)
    }

    @Test
    fun loginRandom() {
        val rnd = Random(42)
        repeat(300) {
            val email = genLoginEmail(rnd)
            val password = genLoginPassword(rnd)
            val confirm = genLoginShort(rnd)
            val name = genLoginShort(rnd)
            val code = genLoginShort(rnd)
            val codeSent = rnd.nextBoolean()
            val tab = genLoginTab(rnd)
            val pred = rnd.nextInt(3)
            val isValidEmail: (String) -> Boolean = when (pred) {
                0 -> { s: String -> s.contains("@") }
                1 -> { s: String -> s.contains("@") && s.contains(".") }
                else -> { s: String -> s.length >= 5 && s.contains("@") }
            }
            assertEquals(
                validateEmailOracle(email, pred),
                LoginSubmitValidationPolicy.validateEmail(email, isValidEmail)?.name
            )
            val state = LoginUiState(
                email = email, password = password, passwordConfirm = confirm,
                name = name, code = code, codeSent = codeSent, selectedTab = tab
            )
            assertEquals(
                validateOracle(email, password, confirm, name, code, codeSent, tab, pred),
                LoginSubmitValidationPolicy.validate(state, isValidEmail)?.name
            )
        }
    }

    @Test
    fun loginGrid() {
        // Exhaustive: tab x codeSent x blank/non-blank fields, with
        // the email predicate fixed to "contains @".
        val isValidEmail: (String) -> Boolean = { s: String -> s.contains("@") }
        for (tab in listOf(-1, 0, 1, 2, 3)) {
            for (codeSent in listOf(false, true)) {
                for (password in listOf("", " ", "12345", "123456", "password")) {
                    for (confirm in listOf("", "123456", "password", "other")) {
                        for (name in listOf("", " ", "阿明")) {
                            for (code in listOf("", " ", "123456")) {
                                val state = LoginUiState(
                                    email = "a@b.com", password = password,
                                    passwordConfirm = confirm, name = name,
                                    code = code, codeSent = codeSent,
                                    selectedTab = tab
                                )
                                assertEquals(
                                    validateOracle(
                                        "a@b.com", password, confirm, name, code,
                                        codeSent, tab, 0
                                    ),
                                    LoginSubmitValidationPolicy.validate(state, isValidEmail)?.name
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    @Test
    fun pinnedEdgeCases() {
        val atOnly: (String) -> Boolean = { s: String -> s.contains("@") }
        // Email: blank short-circuits before the predicate.
        assertEquals(
            LoginValidationFailure.EmailBlank,
            LoginSubmitValidationPolicy.validateEmail("   ", atOnly)
        )
        assertEquals(
            LoginValidationFailure.EmailInvalid,
            LoginSubmitValidationPolicy.validateEmail("plain", atOnly)
        )
        assertEquals(
            null,
            LoginSubmitValidationPolicy.validateEmail("a@b.com", atOnly)
        )
        assertEquals(
            null,
            LoginSubmitValidationPolicy.validateEmail("  a@b.com  ", atOnly)
        )
        // The chain stops at the first failure.
        assertEquals(
            LoginValidationFailure.EmailBlank,
            LoginSubmitValidationPolicy.validate(
                LoginUiState(email = "", password = ""), atOnly
            )
        )
        assertEquals(
            LoginValidationFailure.PasswordBlank,
            LoginSubmitValidationPolicy.validate(
                LoginUiState(email = "a@b.com", password = "   "), atOnly
            )
        )
        assertEquals(
            LoginValidationFailure.PasswordTooShort,
            LoginSubmitValidationPolicy.validate(
                LoginUiState(email = "a@b.com", password = "12345"), atOnly
            )
        )
        assertEquals(
            null,
            LoginSubmitValidationPolicy.validate(
                LoginUiState(email = "a@b.com", password = "123456"), atOnly
            )
        )
        // Tab 1 (register): codeSent -> name -> confirm -> code.
        assertEquals(
            LoginValidationFailure.CodeNotSent,
            LoginSubmitValidationPolicy.validate(
                LoginUiState(email = "a@b.com", password = "123456", selectedTab = 1),
                atOnly
            )
        )
        assertEquals(
            LoginValidationFailure.NameBlank,
            LoginSubmitValidationPolicy.validate(
                LoginUiState(
                    email = "a@b.com", password = "123456",
                    selectedTab = 1, codeSent = true, name = "  "
                ),
                atOnly
            )
        )
        assertEquals(
            LoginValidationFailure.PasswordConfirmMismatch,
            LoginSubmitValidationPolicy.validate(
                LoginUiState(
                    email = "a@b.com", password = "123456",
                    selectedTab = 1, codeSent = true, name = "阿明",
                    passwordConfirm = "12345"
                ),
                atOnly
            )
        )
        assertEquals(
            LoginValidationFailure.PasswordConfirmMismatch,
            LoginSubmitValidationPolicy.validate(
                LoginUiState(
                    email = "a@b.com", password = "123456",
                    selectedTab = 1, codeSent = true, name = "阿明",
                    passwordConfirm = ""
                ),
                atOnly
            )
        )
        assertEquals(
            LoginValidationFailure.CodeBlank,
            LoginSubmitValidationPolicy.validate(
                LoginUiState(
                    email = "a@b.com", password = "123456",
                    selectedTab = 1, codeSent = true, name = "阿明",
                    passwordConfirm = "123456", code = " "
                ),
                atOnly
            )
        )
        assertEquals(
            null,
            LoginSubmitValidationPolicy.validate(
                LoginUiState(
                    email = "a@b.com", password = "123456",
                    selectedTab = 1, codeSent = true, name = "阿明",
                    passwordConfirm = "123456", code = "654321"
                ),
                atOnly
            )
        )
        // Tab 2 (reset): codeSent -> code only; name/confirm ignored.
        assertEquals(
            LoginValidationFailure.CodeNotSent,
            LoginSubmitValidationPolicy.validate(
                LoginUiState(email = "a@b.com", password = "123456", selectedTab = 2),
                atOnly
            )
        )
        assertEquals(
            null,
            LoginSubmitValidationPolicy.validate(
                LoginUiState(
                    email = "a@b.com", password = "123456",
                    selectedTab = 2, codeSent = true, code = "654321",
                    name = "", passwordConfirm = "mismatch"
                ),
                atOnly
            )
        )
        // Tab 0 and out-of-range tabs skip the tab checks.
        assertEquals(
            null,
            LoginSubmitValidationPolicy.validate(
                LoginUiState(email = "a@b.com", password = "123456", selectedTab = 3),
                atOnly
            )
        )
    }
}
