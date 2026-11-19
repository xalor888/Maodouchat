package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginInputPolicyTest {

    @Test
    fun `email trims and truncates to 254`() {
        val state = LoginInputPolicy.applyEmailChange(LoginUiState(), "  " + "a".repeat(300) + "@x.com  ")
        assertEquals("a".repeat(254), state.email)
    }

    @Test
    fun `email change clears error info codeSent and countdown`() {
        val before = LoginUiState(
            email = "old@x.com", errorMessage = "e", infoMessage = "i",
            codeSent = true, codeCountdown = 30
        )
        val state = LoginInputPolicy.applyEmailChange(before, "new@x.com")
        assertEquals("new@x.com", state.email)
        assertNull(state.errorMessage)
        assertNull(state.infoMessage)
        assertFalse(state.codeSent)
        assertEquals(0, state.codeCountdown)
    }

    @Test
    fun `password truncates to 128 and always clears passwordConfirm`() {
        val before = LoginUiState(passwordConfirm = "keep-me")
        val state = LoginInputPolicy.applyPasswordChange(before, "p".repeat(200))
        assertEquals("p".repeat(128), state.password)
        assertEquals("", state.passwordConfirm)
    }

    @Test
    fun `password confirm truncates to 128`() {
        val state = LoginInputPolicy.applyPasswordConfirmChange(LoginUiState(), "c".repeat(200))
        assertEquals("c".repeat(128), state.passwordConfirm)
    }

    @Test
    fun `name truncates to 50`() {
        val state = LoginInputPolicy.applyNameChange(LoginUiState(), "n".repeat(60))
        assertEquals("n".repeat(50), state.name)
    }

    @Test
    fun `totp keeps digits only up to 8`() {
        val state = LoginInputPolicy.applyTotpCodeChange(LoginUiState(), "a1b2c3d4e5f6g7h8i9")
        assertEquals("12345678", state.totpCode)
    }

    @Test
    fun `totp clears errorMessage but keeps infoMessage`() {
        val before = LoginUiState(errorMessage = "e", infoMessage = "i")
        val state = LoginInputPolicy.applyTotpCodeChange(before, "123456")
        assertNull(state.errorMessage)
        assertEquals("i", state.infoMessage)
    }

    @Test
    fun `code keeps digits only up to 8`() {
        val state = LoginInputPolicy.applyCodeChange(LoginUiState(), "12-34 5678 90")
        assertEquals("12345678", state.code)
    }

    @Test
    fun `code change clears error and info`() {
        val before = LoginUiState(errorMessage = "e", infoMessage = "i")
        val state = LoginInputPolicy.applyCodeChange(before, "1234")
        assertNull(state.errorMessage)
        assertNull(state.infoMessage)
    }

    @Test
    fun `tab coerces into 0 to 2`() {
        assertEquals(0, LoginInputPolicy.applyTabSelected(LoginUiState(), -5).selectedTab)
        assertEquals(2, LoginInputPolicy.applyTabSelected(LoginUiState(), 9).selectedTab)
    }

    @Test
    fun `tab 0 clears code and totpCode`() {
        val before = LoginUiState(code = "1234", totpCode = "5678", passwordConfirm = "p")
        val state = LoginInputPolicy.applyTabSelected(before, 0)
        assertEquals("", state.code)
        assertEquals("", state.totpCode)
    }

    @Test
    fun `tab 1 keeps passwordConfirm`() {
        val before = LoginUiState(passwordConfirm = "p")
        val state = LoginInputPolicy.applyTabSelected(before, 1)
        assertEquals("p", state.passwordConfirm)
    }

    @Test
    fun `tab 2 clears passwordConfirm`() {
        val before = LoginUiState(passwordConfirm = "p")
        val state = LoginInputPolicy.applyTabSelected(before, 2)
        assertEquals("", state.passwordConfirm)
    }

    @Test
    fun `raw tab value decides cleanup not coerced value`() {
        val before = LoginUiState(code = "1234", totpCode = "5678", passwordConfirm = "p")
        val state = LoginInputPolicy.applyTabSelected(before, -1)
        assertEquals(0, state.selectedTab)
        assertEquals("1234", state.code)
        assertEquals("5678", state.totpCode)
        assertEquals("", state.passwordConfirm)
    }

    @Test
    fun `tab clears requiresTotp and codeSent`() {
        val before = LoginUiState(requiresTotp = true, codeSent = true)
        val state = LoginInputPolicy.applyTabSelected(before, 1)
        assertFalse(state.requiresTotp)
        assertFalse(state.codeSent)
    }

    @Test
    fun `toggle flips passwordVisible`() {
        val state = LoginInputPolicy.togglePasswordVisibility(LoginUiState(passwordVisible = false))
        assertTrue(state.passwordVisible)
        val back = LoginInputPolicy.togglePasswordVisibility(state)
        assertFalse(back.passwordVisible)
    }
}
