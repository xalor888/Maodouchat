package com.maodouchat.server

import com.maodouchat.server.service.OpenAiCompatibleCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiCompatibleCodecTest {

    @Test
    fun `chat completions json yields message content`() {
        val body = """
            {"id":"cmpl-1","choices":[{"index":0,"message":{"role":"assistant","content":"改写后的草稿"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":12,"completion_tokens":4,"total_tokens":16}}
        """.trimIndent()
        assertEquals("改写后的草稿", OpenAiCompatibleCodec.extractOutputText(body))
        val usage = OpenAiCompatibleCodec.extractUsage(body)
        assertEquals(12L, usage?.first)
        assertEquals(4L, usage?.second)
    }

    @Test
    fun `responses json is fallback only`() {
        val body = """{"output_text":"from-responses","usage":{"input_tokens":3,"output_tokens":2}}"""
        assertEquals("from-responses", OpenAiCompatibleCodec.extractOutputText(body))
        val usage = OpenAiCompatibleCodec.extractUsage(body)
        assertEquals(3L, usage?.first)
        assertEquals(2L, usage?.second)
    }

    @Test
    fun `developer role maps to system`() {
        assertEquals("system", OpenAiCompatibleCodec.chatRole("developer"))
        assertEquals("system", OpenAiCompatibleCodec.chatRole("system"))
        assertEquals("user", OpenAiCompatibleCodec.chatRole("user"))
    }

    @Test
    fun `stream delta prefers choices delta content`() {
        val json = Json.parseToJsonElement(
            """{"choices":[{"delta":{"content":"你好"},"index":0}]}"""
        ) as JsonObject
        assertEquals("你好", OpenAiCompatibleCodec.streamDelta(json))
        assertNull(OpenAiCompatibleCodec.streamError(json))
    }

    @Test
    fun `empty completions body is not a secret leak`() {
        val body = """{"choices":[{"message":{"content":""}}]}"""
        assertNull(OpenAiCompatibleCodec.extractOutputText(body))
        assertTrue(!body.contains("sk-"))
    }
}
