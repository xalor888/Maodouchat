package com.maodouchat.domain.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactCardMarkerPolicyTest {

    @Test
    fun `no marker returns null`() {
        assertNull(ContactCardMarkerPolicy.extractUserId("hello"))
        assertNull(ContactCardMarkerPolicy.extractUserId("👤 小明"))
    }

    @Test
    fun `extracts user id from standard marker`() {
        assertEquals("u9", ContactCardMarkerPolicy.extractUserId("👤 小明\n[contactUser:u9]"))
        assertEquals("user-1_x", ContactCardMarkerPolicy.extractUserId("[contactUser:user-1_x]"))
    }

    @Test
    fun `empty or blank id returns null`() {
        assertNull(ContactCardMarkerPolicy.extractUserId("[contactUser:]"))
        assertNull(ContactCardMarkerPolicy.extractUserId("[contactUser: ]"))
    }

    @Test
    fun `marker without closing bracket still extracts`() {
        assertEquals("abc", ContactCardMarkerPolicy.extractUserId("[contactUser:abc"))
    }

    @Test
    fun `first marker wins`() {
        assertEquals(
            "u1",
            ContactCardMarkerPolicy.extractUserId("[contactUser:u1] and [contactUser:u2]"),
        )
    }

    @Test
    fun `contains marker detects the prefix`() {
        assertTrue(ContactCardMarkerPolicy.containsMarker("👤 小明\n[contactUser:u9]"))
        assertTrue(ContactCardMarkerPolicy.containsMarker("[contactUser:"))
    }

    @Test
    fun `contains marker returns false without marker`() {
        assertFalse(ContactCardMarkerPolicy.containsMarker("hello"))
        assertFalse(ContactCardMarkerPolicy.containsMarker("[contact:u9]"))
    }
}
