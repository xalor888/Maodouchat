package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentProviderConfiguredPolicyTest {

    @Test
    fun `isConfigured passes when all three fields are set`() {
        assertTrue(AgentProviderConfiguredPolicy.isConfigured("https://api.example.com", "gpt-4", "sk-x"))
    }

    @Test
    fun `isConfigured fails when any field is blank`() {
        assertFalse(AgentProviderConfiguredPolicy.isConfigured("", "gpt-4", "sk-x"))
        assertFalse(AgentProviderConfiguredPolicy.isConfigured("https://api.example.com", "", "sk-x"))
        assertFalse(AgentProviderConfiguredPolicy.isConfigured("https://api.example.com", "gpt-4", ""))
        assertFalse(AgentProviderConfiguredPolicy.isConfigured("  ", "gpt-4", "sk-x"))
    }

    @Test
    fun `isConfigured fails when nothing is set`() {
        assertFalse(AgentProviderConfiguredPolicy.isConfigured("", "", ""))
    }
}
