package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoginSubmitValidationPolicyTest {

    private val isValidEmail: (String) -> Boolean = { email ->
        val at = email.indexOf('@')
        if (at <= 0 || at != email.lastIndexOf('@')) {
            false
        } else {
            val domain = email.substring(at + 1)
            domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
        }
    }

    private fun base(tab: Int = 0) = LoginUiState(
        email = "u@x.com",
        password = "password123",
        passwordConfirm = "password123",
        name = "user",
        code = "123456",
        codeSent = true,
        selectedTab = tab
    )

    @Test
    fun `blank email fails first`() {
        assertEquals(
            LoginValidationFailure.EmailBlank,
            LoginSubmitValidationPolicy.validate(base().copy(email = ""), isValidEmail)
        )
    }

    @Test
    fun `invalid email fails`() {
        assertEquals(
            LoginValidationFailure.EmailInvalid,
            LoginSubmitValidationPolicy.validate(base().copy(email = "a@"), isValidEmail)
        )
    }

    @Test
    fun `blank password fails`() {
        assertEquals(
            LoginValidationFailure.PasswordBlank,
            LoginSubmitValidationPolicy.validate(base().copy(password = ""), isValidEmail)
        )
    }

    @Test
    fun `short password fails`() {
        assertEquals(
            LoginValidationFailure.PasswordTooShort,
            LoginSubmitValidationPolicy.validate(base().copy(password = "123"), isValidEmail)
        )
    }

    @Test
    fun `valid login tab passes`() {
        assertNull(LoginSubmitValidationPolicy.validate(base(0), isValidEmail))
    }

    @Test
    fun `register without codeSent fails`() {
        assertEquals(
            LoginValidationFailure.CodeNotSent,
            LoginSubmitValidationPolicy.validate(base(1).copy(codeSent = false), isValidEmail)
        )
    }

    @Test
    fun `register blank name fails`() {
        assertEquals(
            LoginValidationFailure.NameBlank,
            LoginSubmitValidationPolicy.validate(base(1).copy(name = ""), isValidEmail)
        )
    }

    @Test
    fun `register mismatched passwordConfirm fails`() {
        assertEquals(
            LoginValidationFailure.PasswordConfirmMismatch,
            LoginSubmitValidationPolicy.validate(base(1).copy(passwordConfirm = "other"), isValidEmail)
        )
    }

    @Test
    fun `register blank passwordConfirm fails`() {
        assertEquals(
            LoginValidationFailure.PasswordConfirmMismatch,
            LoginSubmitValidationPolicy.validate(base(1).copy(passwordConfirm = ""), isValidEmail)
        )
    }

    @Test
    fun `register blank code fails`() {
        assertEquals(
            LoginValidationFailure.CodeBlank,
            LoginSubmitValidationPolicy.validate(base(1).copy(code = ""), isValidEmail)
        )
    }

    @Test
    fun `valid register tab passes`() {
        assertNull(LoginSubmitValidationPolicy.validate(base(1), isValidEmail))
    }

    @Test
    fun `reset without codeSent fails`() {
        assertEquals(
            LoginValidationFailure.CodeNotSent,
            LoginSubmitValidationPolicy.validate(base(2).copy(codeSent = false), isValidEmail)
        )
    }

    @Test
    fun `reset blank code fails`() {
        assertEquals(
            LoginValidationFailure.CodeBlank,
            LoginSubmitValidationPolicy.validate(base(2).copy(code = ""), isValidEmail)
        )
    }

    @Test
    fun `valid reset tab passes`() {
        assertNull(LoginSubmitValidationPolicy.validate(base(2), isValidEmail))
    }

    @Test
    fun `validateEmail blank`() {
        assertEquals(
            LoginValidationFailure.EmailBlank,
            LoginSubmitValidationPolicy.validateEmail("", isValidEmail)
        )
    }

    @Test
    fun `validateEmail invalid`() {
        assertEquals(
            LoginValidationFailure.EmailInvalid,
            LoginSubmitValidationPolicy.validateEmail("a@", isValidEmail)
        )
    }

    @Test
    fun `validateEmail valid`() {
        assertNull(LoginSubmitValidationPolicy.validateEmail("u@x.com", isValidEmail))
    }

    @Test
    fun `first failure wins in order`() {
        assertEquals(
            LoginValidationFailure.EmailBlank,
            LoginSubmitValidationPolicy.validate(
                base().copy(email = "", password = "1"), isValidEmail
            )
        )
    }
}
