package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentSecretPeerPolicyTest {

    @Test
    fun `isSecretChat matches only the SECRET chat type`() {
        assertTrue(AgentSecretPeerPolicy.isSecretChat("SECRET"))
        assertFalse(AgentSecretPeerPolicy.isSecretChat("GROUP"))
        assertFalse(AgentSecretPeerPolicy.isSecretChat(""))
    }

    @Test
    fun `secretPeerIds splits trims drops blanks and dedupes`() {
        assertEquals(
            setOf("a", "b", "c"),
            AgentSecretPeerPolicy.secretPeerIds(listOf("a, b,, c ", "b", ""))
        )
    }

    @Test
    fun `secretPeerIds returns empty set for empty input`() {
        assertTrue(AgentSecretPeerPolicy.secretPeerIds(emptyList()).isEmpty())
        assertTrue(AgentSecretPeerPolicy.secretPeerIds(listOf("", " , ")).isEmpty())
    }

    @Test
    fun `secretPeerIds keeps ids that only differ by case`() {
        assertEquals(
            setOf("Alice", "alice"),
            AgentSecretPeerPolicy.secretPeerIds(listOf("Alice,alice"))
        )
    }
}
