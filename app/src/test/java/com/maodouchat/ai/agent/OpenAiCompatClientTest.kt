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

    @Test
    fun `parseNonStream rejects choices-less payload`() {
        val result = OpenAiCompatClient.parseNonStream("{}")
        assertTrue(result is OpenAiCompatClient.Completion.Error)
        assertEquals("模型没有 choices", (result as OpenAiCompatClient.Completion.Error).message)
    }

    @Test
    fun `parseNonStream falls back to reasoning_content`() {
        val payload = """{"choices":[{"message":{"content":"","reasoning_content":"thinking out loud"}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("thinking out loud", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream generates id for tool call without id`() {
        val payload = """{"choices":[{"message":{"content":"","tool_calls":[{"function":{"name":"list_chats","arguments":"{}"}}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        val call = (result as OpenAiCompatClient.Completion.Tools).calls.single()
        assertEquals("list_chats", call.name)
        assertTrue(call.id.startsWith("call_"))
    }

    @Test
    fun `parseNonStream drops tool call without function`() {
        val payload = """{"choices":[{"message":{"content":"hi","tool_calls":[{}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("hi", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream carries content alongside tool calls`() {
        val payload = """{"choices":[{"message":{"content":"working on it","tool_calls":[{"id":"c1","function":{"name":"list_chats","arguments":"{}"}}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        assertEquals("working on it", (result as OpenAiCompatClient.Completion.Tools).content)
    }
}
