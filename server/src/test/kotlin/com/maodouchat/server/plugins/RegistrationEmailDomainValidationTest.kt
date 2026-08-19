package com.maodouchat.server.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistrationEmailDomainValidationTest {
    private val blocklist = setOf("blocked.example")

    @Test
    fun `blocked registration email domain is rejected after normalization`() {
        assertTrue(isRegistrationEmailDomainBlocked("  User@Blocked.Example  ", blocklist))
    }

    @Test
    fun `registration email outside blocklist is allowed`() {
        assertFalse(isRegistrationEmailDomainBlocked("user@allowed.example", blocklist))
    }
}
