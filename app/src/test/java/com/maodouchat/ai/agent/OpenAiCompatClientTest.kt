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

    @Test
    fun `parseNonStream rejects empty choices array`() {
        val result = OpenAiCompatClient.parseNonStream("""{"choices":[]}""")
        assertTrue(result is OpenAiCompatClient.Completion.Error)
        assertEquals("模型没有 choices", (result as OpenAiCompatClient.Completion.Error).message)
    }

    @Test
    fun `parseNonStream reads empty text for choice without message`() {
        val result = OpenAiCompatClient.parseNonStream("""{"choices":[{}]}""")
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream treats null content as empty text`() {
        val result = OpenAiCompatClient.parseNonStream("""{"choices":[{"message":{"content":null}}]}""")
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream ignores empty tool_calls array`() {
        val payload = """{"choices":[{"message":{"content":"hi","tool_calls":[]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("hi", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream keeps multiple tool calls in order`() {
        val payload = """{"choices":[{"message":{"content":"","tool_calls":[
          {"id":"c1","function":{"name":"list_chats","arguments":"{}"}},
          {"id":"c2","function":{"name":"search_messages","arguments":"{}"}}
        ]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        val calls = (result as OpenAiCompatClient.Completion.Tools).calls
        assertEquals(listOf("list_chats", "search_messages"), calls.map { it.name })
        assertEquals(listOf("c1", "c2"), calls.map { it.id })
    }

    @Test
    fun `parseNonStream reports exact message for non-JSON payload`() {
        val result = OpenAiCompatClient.parseNonStream("not-json")
        assertTrue(result is OpenAiCompatClient.Completion.Error)
        assertEquals("模型返回不是 JSON", (result as OpenAiCompatClient.Completion.Error).message)
    }

    @Test
    fun `parseNonStream prefers content over reasoning_content`() {
        val payload = """{"choices":[{"message":{"content":"answer","reasoning_content":"thinking"}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("answer", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream reads reasoning_content without content key`() {
        val payload = """{"choices":[{"message":{"reasoning_content":"only reasoning"}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("only reasoning", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream keeps tool call with blank function name`() {
        val payload = """{"choices":[{"message":{"content":"","tool_calls":[{"id":"c1","function":{}}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        val call = (result as OpenAiCompatClient.Completion.Tools).calls.single()
        assertEquals("c1", call.id)
        assertEquals("", call.name)
    }

    @Test
    fun `parseNonStream reads only the first choice`() {
        val payload = """{"choices":[
          {"message":{"content":"first"}},
          {"message":{"content":"second"}}
        ]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("first", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream rejects JSON array payload`() {
        val result = OpenAiCompatClient.parseNonStream("""[{"choices":[]}]""")
        assertTrue(result is OpenAiCompatClient.Completion.Error)
        assertEquals("模型返回不是 JSON", (result as OpenAiCompatClient.Completion.Error).message)
    }

    @Test
    fun `parseNonStream rejects non-array choices`() {
        val result = OpenAiCompatClient.parseNonStream("""{"choices":{"message":{"content":"hi"}}}""")
        assertTrue(result is OpenAiCompatClient.Completion.Error)
        assertEquals("模型没有 choices", (result as OpenAiCompatClient.Completion.Error).message)
    }

    @Test
    fun `parseNonStream rejects non-object first choice`() {
        val result = OpenAiCompatClient.parseNonStream("""{"choices":["hello"]}""")
        assertTrue(result is OpenAiCompatClient.Completion.Error)
        assertEquals("模型没有 choices", (result as OpenAiCompatClient.Completion.Error).message)
    }

    @Test
    fun `parseNonStream reads empty text for null message`() {
        val result = OpenAiCompatClient.parseNonStream("""{"choices":[{"message":null}]}""")
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream keeps empty arguments for tool call without arguments`() {
        val payload = """{"choices":[{"message":{"content":"","tool_calls":[{"id":"c9","function":{"name":"list_chats"}}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        val call = (result as OpenAiCompatClient.Completion.Tools).calls.single()
        assertEquals("c9", call.id)
        assertEquals("list_chats", call.name)
        assertEquals("", call.argumentsJson)
    }

    @Test
    fun `parseNonStream skips non-object tool call entries`() {
        val payload = """{"choices":[{"message":{"content":"","tool_calls":["junk",{"id":"c1","function":{"name":"list_chats","arguments":"{}"}}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        val call = (result as OpenAiCompatClient.Completion.Tools).calls.single()
        assertEquals("c1", call.id)
        assertEquals("list_chats", call.name)
    }

    @Test
    fun `parseNonStream falls back to text when every tool entry lacks a function`() {
        val payload = """{"choices":[{"message":{"content":"done","tool_calls":[{},{"id":"c1"}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("done", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream keeps tool arguments verbatim`() {
        val payload = """{"choices":[{"message":{"content":"","tool_calls":[{"id":"c1","function":{"name":"list_chats","arguments":"{\"query\":\"a b\",\"n\":3}"}}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        assertEquals("""{"query":"a b","n":3}""", (result as OpenAiCompatClient.Completion.Tools).calls.single().argumentsJson)
    }

    @Test
    fun `parseNonStream reads first choice even when it has no message`() {
        val payload = """{"choices":[{},{"message":{"content":"second"}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream ignores non-string reasoning when content is present`() {
        val payload = """{"choices":[{"message":{"content":"hi","reasoning_content":123}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("hi", (result as OpenAiCompatClient.Completion.Text).content)
    }


    @Test
    fun `parseNonStream generates id for tool call with blank id`() {
        val payload = """{"choices":[{"message":{"content":"","tool_calls":[{"id":"","function":{"name":"list_chats","arguments":"{}"}}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        val call = (result as OpenAiCompatClient.Completion.Tools).calls.single()
        assertEquals("list_chats", call.name)
        assertTrue(call.id.startsWith("call_"))
    }

    @Test
    fun `parseNonStream tools content falls back to reasoning_content`() {
        val payload = """{"choices":[{"message":{"content":"","reasoning_content":"thinking","tool_calls":[{"id":"c1","function":{"name":"list_chats","arguments":"{}"}}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        assertEquals("thinking", (result as OpenAiCompatClient.Completion.Tools).content)
    }

    @Test
    fun `parseNonStream first choice tools win over second choice text`() {
        val payload = """{"choices":[
          {"message":{"content":"","tool_calls":[{"id":"c1","function":{"name":"list_chats","arguments":"{}"}}]}},
          {"message":{"content":"second"}}
        ]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        assertEquals("list_chats", (result as OpenAiCompatClient.Completion.Tools).calls.single().name)
    }

    @Test
    fun `parseNonStream non-string reasoning without content yields empty text`() {
        val result = OpenAiCompatClient.parseNonStream("""{"choices":[{"message":{"reasoning_content":123}}]}""")
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream non-array tool calls fall back to text`() {
        val payload = """{"choices":[{"message":{"content":"hi","tool_calls":"x"}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("hi", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream null content falls back to reasoning`() {
        val payload = """{"choices":[{"message":{"content":null,"reasoning_content":"thinking"}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("thinking", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream tools keep non-empty content`() {
        val payload = """{"choices":[{"message":{"content":"hello","tool_calls":[{"id":"c1","function":{"name":"list_chats","arguments":"{}"}}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        val tools = result as OpenAiCompatClient.Completion.Tools
        assertEquals("hello", tools.content)
        assertEquals("list_chats", tools.calls.single().name)
    }

    @Test
    fun `parseNonStream empty message object yields empty text`() {
        val result = OpenAiCompatClient.parseNonStream("""{"choices":[{"message":{}}]}""")
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream empty string content alone yields empty text`() {
        val result = OpenAiCompatClient.parseNonStream("""{"choices":[{"message":{"content":""}}]}""")
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream tools content prefers content over reasoning`() {
        val payload = """{"choices":[{"message":{"content":"hi","reasoning_content":"thinking","tool_calls":[{"id":"c1","function":{"name":"list_chats","arguments":"{}"}}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        assertEquals("hi", (result as OpenAiCompatClient.Completion.Tools).content)
    }

    @Test
    fun `parseNonStream reads whitespace-only content as text`() {
        val result = OpenAiCompatClient.parseNonStream("""{"choices":[{"message":{"content":" "}}]}""")
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals(" ", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream skips tool call with null function`() {
        val payload = """{"choices":[{"message":{"content":"hi","tool_calls":[{"id":"c1","function":null}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("hi", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream skips null tool call entries`() {
        val payload = """{"choices":[{"message":{"content":"","tool_calls":[null,{"id":"c1","function":{"name":"list_chats","arguments":"{}"}}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        val call = (result as OpenAiCompatClient.Completion.Tools).calls.single()
        assertEquals("c1", call.id)
        assertEquals("list_chats", call.name)
    }

    @Test
    fun `parseNonStream ignores second choice tools when first choice has text`() {
        val payload = """{"choices":[
          {"message":{"content":"first"}},
          {"message":{"content":"","tool_calls":[{"id":"c1","function":{"name":"list_chats","arguments":"{}"}}]}}
        ]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Text)
        assertEquals("first", (result as OpenAiCompatClient.Completion.Text).content)
    }

    @Test
    fun `parseNonStream generates id for whitespace-only tool call id`() {
        val payload = """{"choices":[{"message":{"content":"","tool_calls":[{"id":" ","function":{"name":"list_chats","arguments":"{}"}}]}}]}"""
        val result = OpenAiCompatClient.parseNonStream(payload)
        assertTrue(result is OpenAiCompatClient.Completion.Tools)
        val call = (result as OpenAiCompatClient.Completion.Tools).calls.single()
        assertEquals("list_chats", call.name)
        assertTrue(call.id.startsWith("call_"))
    }

}
