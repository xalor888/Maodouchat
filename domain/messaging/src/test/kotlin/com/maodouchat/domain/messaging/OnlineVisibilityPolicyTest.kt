package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OnlineVisibilityPolicyTest {

    @Test
    fun `contacts and nobody are kept`() {
        assertEquals("contacts", normalizeOnlineVisibility("contacts"))
        assertEquals("nobody", normalizeOnlineVisibility("nobody"))
    }

    @Test
    fun `everyone is kept`() {
        assertEquals("everyone", normalizeOnlineVisibility("everyone"))
    }

    @Test
    fun `unknown and empty values fall back to everyone`() {
        assertEquals("everyone", normalizeOnlineVisibility("friends"))
        assertEquals("everyone", normalizeOnlineVisibility(""))
    }
}
