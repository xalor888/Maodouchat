package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [LoginInputPolicy] (checklist line 931,
 * 55th block). Test-only, zero production changes.
 *
 * The oracle state is a verbatim-field mirror of LoginUiState; every
 * apply* oracle re-implements the copy() transition by hand. Digit
 * filtering uses Char.isDigit (Nd per UTF-16 code unit: astral digits
 * filter to nothing, U+00B2 is not a digit). Random inputs use Random(42),
 * fresh per @Test.
 */
class LoginInputPolicyFuzzTest {

// ---------- C2 LoginInputPolicy oracle ----------

// Verbatim-field mirror of LoginUiState, so the oracle never touches the
// production type; the test file converts between the two for comparison.
private data class OracleLoginState(
    val email: String = "",
    val password: String = "",
    val passwordConfirm: String = "",
    val name: String = "",
    val code: String = "",
    val totpCode: String = "",
    val requiresTotp: Boolean = false,
    val selectedTab: Int = 0,
    val passwordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isCodeSending: Boolean = false,
    val codeSent: Boolean = false,
    val codeCountdown: Int = 0,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val isLoggedIn: Boolean = false
)

// Hex codec for state fields, local to this fragment: Parse55.kt is
// scratch-only and is not compiled with the repo tests, so the oracle
// state codec cannot call encStr/decStr from there.
private fun encHexStr(s: String): String {
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

private fun decHexStr(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4)
    for (i in chars.indices) {
        chars[i] = h.substring(i * 4, i * 4 + 4).toInt(16).toChar()
    }
    return String(chars)
}

private fun encOracleState(s: OracleLoginState): String = listOf(
    "S" + encHexStr(s.email), "S" + encHexStr(s.password),
    "S" + encHexStr(s.passwordConfirm), "S" + encHexStr(s.name),
    "S" + encHexStr(s.code), "S" + encHexStr(s.totpCode),
    if (s.requiresTotp) "1" else "0", s.selectedTab.toString(),
    if (s.passwordVisible) "1" else "0", if (s.isLoading) "1" else "0",
    if (s.isCodeSending) "1" else "0", if (s.codeSent) "1" else "0",
    s.codeCountdown.toString(),
    if (s.errorMessage == null) "N" else "S" + encHexStr(s.errorMessage),
    if (s.infoMessage == null) "N" else "S" + encHexStr(s.infoMessage),
    if (s.isLoggedIn) "1" else "0"
).joinToString(",")

private fun decOracleState(s: String): OracleLoginState {
    val p = s.split(",")
    return OracleLoginState(
        email = decHexStr(p[0].substring(1)),
        password = decHexStr(p[1].substring(1)),
        passwordConfirm = decHexStr(p[2].substring(1)),
        name = decHexStr(p[3].substring(1)),
        code = decHexStr(p[4].substring(1)),
        totpCode = decHexStr(p[5].substring(1)),
        requiresTotp = p[6] == "1",
        selectedTab = p[7].toInt(),
        passwordVisible = p[8] == "1",
        isLoading = p[9] == "1",
        isCodeSending = p[10] == "1",
        codeSent = p[11] == "1",
        codeCountdown = p[12].toInt(),
        errorMessage = if (p[13] == "N") null else decHexStr(p[13].substring(1)),
        infoMessage = if (p[14] == "N") null else decHexStr(p[14].substring(1)),
        isLoggedIn = p[15] == "1"
    )
}

private fun randomOracleState(rnd: Random): OracleLoginState = OracleLoginState(
    email = randomString(rnd),
    password = randomString(rnd),
    passwordConfirm = randomString(rnd),
    name = randomString(rnd),
    code = randomString(rnd),
    totpCode = randomString(rnd),
    requiresTotp = rnd.nextBoolean(),
    selectedTab = randomTab(rnd),
    passwordVisible = rnd.nextBoolean(),
    isLoading = rnd.nextBoolean(),
    isCodeSending = rnd.nextBoolean(),
    codeSent = rnd.nextBoolean(),
    codeCountdown = when (rnd.nextInt(5)) {
        0 -> 0; 1 -> 1; 2 -> -1; 3 -> 60
        else -> rnd.nextInt()
    },
    errorMessage = if (rnd.nextBoolean()) null else randomString(rnd),
    infoMessage = if (rnd.nextBoolean()) null else randomString(rnd),
    isLoggedIn = rnd.nextBoolean()
)

private fun normalizeEmailOracle(email: String): String = email.trim().take(254)

private fun applyEmailChangeOracle(state: OracleLoginState, email: String): OracleLoginState =
    state.copy(
        email = normalizeEmailOracle(email),
        errorMessage = null,
        infoMessage = null,
        codeSent = false,
        codeCountdown = 0
    )

private fun applyPasswordChangeOracle(state: OracleLoginState, password: String): OracleLoginState =
    state.copy(
        password = password.take(128),
        passwordConfirm = "",
        errorMessage = null,
        infoMessage = null
    )

private fun applyPasswordConfirmChangeOracle(state: OracleLoginState, passwordConfirm: String): OracleLoginState =
    state.copy(
        passwordConfirm = passwordConfirm.take(128),
        errorMessage = null,
        infoMessage = null
    )

private fun applyNameChangeOracle(state: OracleLoginState, name: String): OracleLoginState =
    state.copy(name = name.take(50), errorMessage = null, infoMessage = null)

private fun applyTotpCodeChangeOracle(state: OracleLoginState, value: String): OracleLoginState =
    state.copy(totpCode = value.filter { it.isDigit() }.take(8), errorMessage = null)

private fun applyCodeChangeOracle(state: OracleLoginState, code: String): OracleLoginState =
    state.copy(
        code = code.filter { it.isDigit() }.take(8),
        errorMessage = null,
        infoMessage = null
    )

private fun applyTabSelectedOracle(state: OracleLoginState, tab: Int): OracleLoginState =
    state.copy(
        selectedTab = tab.coerceIn(0, 2),
        errorMessage = null,
        infoMessage = null,
        codeSent = false,
        // Raw tab (not the coerced value) decides what is cleared.
        code = if (tab == 0) "" else state.code,
        passwordConfirm = if (tab == 1) state.passwordConfirm else "",
        totpCode = if (tab == 0) "" else state.totpCode,
        requiresTotp = false
    )

private fun togglePasswordVisibilityOracle(state: OracleLoginState): OracleLoginState =
    state.copy(passwordVisible = !state.passwordVisible)

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Digits Char.isDigit keeps (Nd), plus U+00B2 which is NOT a digit in
// Kotlin (unlike Python str.isdigit) — a deliberate discriminator.
private val DIGIT_CHARS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '\u0660', '\u06F0', '\u0966', '\uFF10', '\u00B2'
)

private val PLAIN_CHARS = charArrayOf(
    'a', 'Z', '0', '_', '-', '.', '@', 'é', '中', '\u200B', '\uFEFF',
    '\u0085', '\uD800', '|', ':', 'C', 'A', 'P', 'T', 'U', 'R', 'E', '/',
    '?', '%', ' '
)

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0, 1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2, 3 -> { sb.append(DIGIT_CHARS[rnd.nextInt(DIGIT_CHARS.size)]); n++ }
            4 -> {
                // Astral char (emoji or astral digit U+104A0): 2 code units.
                // Its surrogates are not digits and not whitespace.
                val cp = if (rnd.nextBoolean()) 0x1F600 else 0x104A0
                sb.append(Character.toChars(cp)); n += 2
            }
            else -> { sb.append(PLAIN_CHARS[rnd.nextInt(PLAIN_CHARS.size)]); n++ }
        }
    }
    return sb.toString()
}

private fun randomNullableString(rnd: Random): String? =
    if (rnd.nextInt(4) == 0) null else randomString(rnd)

private fun randomLong(rnd: Random): Long = when (rnd.nextInt(8)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L; 3 -> 350L; 4 -> -350L
    5 -> Long.MIN_VALUE; 6 -> Long.MAX_VALUE
    else -> rnd.nextLong()
}

private fun randomTab(rnd: Random): Int = when (rnd.nextInt(10)) {
    0 -> -5; 1 -> -1; 2 -> 0; 3 -> 1; 4 -> 2; 5 -> 3; 6 -> 5; 7 -> 100
    8 -> Int.MAX_VALUE
    else -> rnd.nextInt()
}

private fun randomAlertContent(rnd: Random): String = when (rnd.nextInt(6)) {
    0 -> "CAPTURE_ALERT:" + randomString(rnd, 12) + "|" + randomString(rnd, 20)
    1 -> "CAPTURE_ALERT:" + randomString(rnd, 12)
    2 -> "CAPTURE_ALERT:" + randomString(rnd, 6) + "|" + randomString(rnd, 6) + "|" + randomString(rnd, 6)
    3 -> "CAPTURE_ALERT:"
    4 -> randomString(rnd)
    else -> randomString(rnd, 8) + "CAPTURE_ALERT:" + randomString(rnd, 8)
}

    // Test-only converter between the oracle state and the production type.
    private fun OracleLoginState.toProduction(): LoginUiState = LoginUiState(
        email, password, passwordConfirm, name, code, totpCode, requiresTotp,
        selectedTab, passwordVisible, isLoading, isCodeSending, codeSent,
        codeCountdown, errorMessage, infoMessage, isLoggedIn
    )

    @Test
    fun normalizeEmailRandom() {
        val rnd = Random(42)
        repeat(300) {
            val email = randomString(rnd, 300)
            assertEquals(normalizeEmailOracle(email), LoginInputPolicy.normalizeEmail(email))
        }
    }

    @Test
    fun applyEmailChangeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val state = randomOracleState(rnd)
            val email = randomString(rnd, 300)
            assertEquals(
                applyEmailChangeOracle(state, email).toProduction(),
                LoginInputPolicy.applyEmailChange(state.toProduction(), email)
            )
        }
    }

    @Test
    fun applyPasswordChangeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val state = randomOracleState(rnd)
            val password = randomString(rnd, 300)
            assertEquals(
                applyPasswordChangeOracle(state, password).toProduction(),
                LoginInputPolicy.applyPasswordChange(state.toProduction(), password)
            )
        }
    }

    @Test
    fun applyPasswordConfirmChangeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val state = randomOracleState(rnd)
            val passwordConfirm = randomString(rnd, 300)
            assertEquals(
                applyPasswordConfirmChangeOracle(state, passwordConfirm).toProduction(),
                LoginInputPolicy.applyPasswordConfirmChange(state.toProduction(), passwordConfirm)
            )
        }
    }

    @Test
    fun applyNameChangeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val state = randomOracleState(rnd)
            val name = randomString(rnd, 80)
            assertEquals(
                applyNameChangeOracle(state, name).toProduction(),
                LoginInputPolicy.applyNameChange(state.toProduction(), name)
            )
        }
    }

    @Test
    fun applyTotpCodeChangeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val state = randomOracleState(rnd)
            val value = randomString(rnd, 40)
            assertEquals(
                applyTotpCodeChangeOracle(state, value).toProduction(),
                LoginInputPolicy.applyTotpCodeChange(state.toProduction(), value)
            )
        }
    }

    @Test
    fun applyCodeChangeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val state = randomOracleState(rnd)
            val code = randomString(rnd, 40)
            assertEquals(
                applyCodeChangeOracle(state, code).toProduction(),
                LoginInputPolicy.applyCodeChange(state.toProduction(), code)
            )
        }
    }

    @Test
    fun applyTabSelectedRandom() {
        val rnd = Random(42)
        repeat(300) {
            val state = randomOracleState(rnd)
            val tab = randomTab(rnd)
            assertEquals(
                applyTabSelectedOracle(state, tab).toProduction(),
                LoginInputPolicy.applyTabSelected(state.toProduction(), tab)
            )
        }
    }

    @Test
    fun togglePasswordVisibilityRandom() {
        val rnd = Random(42)
        repeat(300) {
            val state = randomOracleState(rnd)
            assertEquals(
                togglePasswordVisibilityOracle(state).toProduction(),
                LoginInputPolicy.togglePasswordVisibility(state.toProduction())
            )
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // normalizeEmail: trim (NBSP trims, NEL does not) then take(254).
        assertEquals("a@b.com", LoginInputPolicy.normalizeEmail("  a@b.com  "))
        assertEquals("a@b.com", LoginInputPolicy.normalizeEmail("\u00A0a@b.com\u00A0"))
        assertEquals("", LoginInputPolicy.normalizeEmail("   "))
        assertEquals("\u0085a\u0085", LoginInputPolicy.normalizeEmail("\u0085a\u0085"))
        assertEquals(254, LoginInputPolicy.normalizeEmail("x".repeat(300)).length)
        assertEquals("x".repeat(254), LoginInputPolicy.normalizeEmail("x".repeat(300)))
        assertEquals(254, LoginInputPolicy.normalizeEmail("   " + "x".repeat(300)).length)
        // applyEmailChange: normalizes, clears messages, resets code state.
        val base = LoginUiState(
            email = "old@x.com", password = "pw", passwordConfirm = "pw",
            name = "n", code = "123", totpCode = "456", requiresTotp = true,
            selectedTab = 1, passwordVisible = true, isLoading = true,
            isCodeSending = true, codeSent = true, codeCountdown = 42,
            errorMessage = "e", infoMessage = "i", isLoggedIn = true
        )
        val emailed = LoginInputPolicy.applyEmailChange(base, "  new@x.com  ")
        assertEquals("new@x.com", emailed.email)
        assertEquals(null, emailed.errorMessage)
        assertEquals(null, emailed.infoMessage)
        assertEquals(false, emailed.codeSent)
        assertEquals(0, emailed.codeCountdown)
        assertEquals("pw", emailed.password)
        assertEquals("123", emailed.code)
        assertEquals(true, emailed.requiresTotp)
        assertEquals(true, emailed.isLoggedIn)
        // applyPasswordChange: take(128), clears confirm + messages.
        val pwChanged = LoginInputPolicy.applyPasswordChange(base, "p".repeat(200))
        assertEquals(128, pwChanged.password.length)
        assertEquals("", pwChanged.passwordConfirm)
        assertEquals(null, pwChanged.errorMessage)
        assertEquals(null, pwChanged.infoMessage)
        assertEquals("old@x.com", pwChanged.email)
        // applyPasswordConfirmChange: take(128), messages cleared.
        val pcChanged = LoginInputPolicy.applyPasswordConfirmChange(base, "q".repeat(200))
        assertEquals(128, pcChanged.passwordConfirm.length)
        assertEquals("pw", pcChanged.password)
        // applyNameChange: take(50).
        val named = LoginInputPolicy.applyNameChange(base, "n".repeat(60))
        assertEquals(50, named.name.length)
        assertEquals(null, named.errorMessage)
        // applyTotpCodeChange: digits only, take(8); infoMessage untouched.
        assertEquals("123456", LoginInputPolicy.applyTotpCodeChange(base, "a1b2c3d4e5f6").totpCode)
        assertEquals("12345678", LoginInputPolicy.applyTotpCodeChange(base, "123456789").totpCode)
        assertEquals("１２３４５６７８", LoginInputPolicy.applyTotpCodeChange(base, "１２３４５６７８９").totpCode)
        assertEquals("12", LoginInputPolicy.applyTotpCodeChange(base, "²12").totpCode)
        assertEquals("", LoginInputPolicy.applyTotpCodeChange(base, "\uD801\uDCA0").totpCode)
        assertEquals("i", LoginInputPolicy.applyTotpCodeChange(base, "123").infoMessage)
        // applyCodeChange: same digit rule, but infoMessage IS cleared.
        assertEquals("123456", LoginInputPolicy.applyCodeChange(base, "a1b2c3d4e5f6").code)
        assertEquals(null, LoginInputPolicy.applyCodeChange(base, "123").infoMessage)
        assertEquals(null, LoginInputPolicy.applyCodeChange(base, "123").errorMessage)
        // applyTabSelected: the RAW tab decides what is cleared, not the
        // coerced selectedTab. tab=-5 selects 0 but keeps code/totpCode.
        val tabNeg = LoginInputPolicy.applyTabSelected(base, -5)
        assertEquals(0, tabNeg.selectedTab)
        assertEquals("123", tabNeg.code)
        assertEquals("456", tabNeg.totpCode)
        assertEquals("", tabNeg.passwordConfirm)
        assertEquals(false, tabNeg.requiresTotp)
        assertEquals(false, tabNeg.codeSent)
        // tab=0 clears code + totpCode; tab=1 keeps passwordConfirm;
        // tab=2 clears passwordConfirm; tab=5 selects 2, keeps code.
        assertEquals("", LoginInputPolicy.applyTabSelected(base, 0).code)
        assertEquals("", LoginInputPolicy.applyTabSelected(base, 0).totpCode)
        assertEquals("pw", LoginInputPolicy.applyTabSelected(base, 1).passwordConfirm)
        assertEquals("123", LoginInputPolicy.applyTabSelected(base, 1).code)
        assertEquals("", LoginInputPolicy.applyTabSelected(base, 2).passwordConfirm)
        assertEquals("123", LoginInputPolicy.applyTabSelected(base, 2).code)
        assertEquals(2, LoginInputPolicy.applyTabSelected(base, 5).selectedTab)
        assertEquals("123", LoginInputPolicy.applyTabSelected(base, 5).code)
        assertEquals("", LoginInputPolicy.applyTabSelected(base, 5).passwordConfirm)
        // togglePasswordVisibility flips and flips back.
        assertEquals(false, LoginInputPolicy.togglePasswordVisibility(base.copy(passwordVisible = true)).passwordVisible)
        assertEquals(true, LoginInputPolicy.togglePasswordVisibility(base.copy(passwordVisible = false)).passwordVisible)
    }
}
