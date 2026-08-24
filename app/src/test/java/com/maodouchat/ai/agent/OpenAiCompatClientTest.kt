package com.maodouchat.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatClientTest {

    @Test
    fun `parseNonStream reads assistant text`() {
        val payload = """
            {"choices":[{"message":{"role":"assistant","content":"hello"}}]}
        """.trimIndent()
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("hello", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream reads tool calls`() {
        val payload = """
            {"choices":[{"message":{"content":"","tool_calls":[
              {"id":"call_1","function":{"name":"list_chats","arguments":"{}"}}
            ]}}]}
        """.trimIndent()
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        val tools = result as OpenAiCompatClient.Completion.Tools
        assertEquals("list_chats", tools.calls.single().name)
        assertEquals("call_1", tools.calls.single().id)
    }

    @Test
    fun `parseNonStream rejects empty payload`() {
        val result = OpenAiCompatClient.parseNonStream("not-json")
        assertTrue(result is OpenAiCompatClient.Completion.Error)
    }
}
