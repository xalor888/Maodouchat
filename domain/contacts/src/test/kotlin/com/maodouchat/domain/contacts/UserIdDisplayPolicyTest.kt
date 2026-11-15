package com.maodouchat.domain.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

class UserIdDisplayPolicyTest {

    @Test
    fun `short id is shown as-is`() {
        assertEquals("abc", UserIdDisplayPolicy.abbreviate("abc"))
        assertEquals("", UserIdDisplayPolicy.abbreviate(""))
    }

    @Test
    fun `exactly sixteen chars is shown as-is`() {
        val id = "u_" + "a".repeat(14)
        assertEquals(16, id.length)
        assertEquals(id, UserIdDisplayPolicy.abbreviate(id))
    }

    @Test
    fun `seventeen chars abbreviates to head plus tail`() {
        assertEquals("abcdefgh…nopq", UserIdDisplayPolicy.abbreviate("abcdefghijklmnopq"))
    }

    @Test
    fun `long uuid-like id abbreviates to head plus tail`() {
        val id = "u_12345678-90ab-cdef-1234-567890abcdef"
        assertEquals("u_123456…cdef", UserIdDisplayPolicy.abbreviate(id))
    }

    @Test
    fun `abbreviation keeps exactly eight head and four tail chars`() {
        val id = "0123456789ABCDEFGHIJ"
        assertEquals("01234567…GHIJ", UserIdDisplayPolicy.abbreviate(id))
    }
}
