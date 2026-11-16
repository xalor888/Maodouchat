package com.maodouchat.domain.messaging

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentToolArgsTest {

    @Test
    fun `parseBool accepts true words`() {
        assertEquals(true, AgentToolArgs.parseBool("true"))
        assertEquals(true, AgentToolArgs.parseBool("1"))
        assertEquals(true, AgentToolArgs.parseBool("YES"))
        assertEquals(true, AgentToolArgs.parseBool("  yes  "))
    }

    @Test
    fun `parseBool accepts false words`() {
        assertEquals(false, AgentToolArgs.parseBool("false"))
        assertEquals(false, AgentToolArgs.parseBool("0"))
        assertEquals(false, AgentToolArgs.parseBool("No"))
    }

    @Test
    fun `parseBool returns null for blank and unknown`() {
        assertNull(AgentToolArgs.parseBool(null))
        assertNull(AgentToolArgs.parseBool("  "))
        assertNull(AgentToolArgs.parseBool("maybe"))
        assertNull(AgentToolArgs.parseBool("2"))
    }

    @Test
    fun `parseArgs flattens json values to strings`() {
        assertEquals(mapOf("chatId" to "c1", "limit" to "20"),
            AgentToolArgs.parseArgs("{\"chatId\":\"c1\",\"limit\":20}"))
    }

    @Test
    fun `parseArgs returns empty map for blank and invalid json`() {
        assertEquals(emptyMap<String, String>(), AgentToolArgs.parseArgs(""))
        assertEquals(emptyMap<String, String>(), AgentToolArgs.parseArgs("   "))
        assertEquals(emptyMap<String, String>(), AgentToolArgs.parseArgs("{not json"))
        assertEquals(emptyMap<String, String>(), AgentToolArgs.parseArgs("{}"))
    }

    @Test
    fun `fail prefixes error message`() {
        assertEquals("Error: boom", AgentToolArgs.fail(RuntimeException("boom")))
    }

    @Test
    fun `fail falls back to class name when message is null`() {
        assertEquals("Error: CancellationException",
            AgentToolArgs.fail(CancellationException()))
    }

    @Test
    fun `parseArgs keeps keys with null values as null string`() {
        assertTrue(AgentToolArgs.parseArgs("{\"a\":null}")["a"] == "null")
    }
}
