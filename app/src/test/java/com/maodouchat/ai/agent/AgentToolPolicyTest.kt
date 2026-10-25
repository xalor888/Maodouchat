package com.maodouchat.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolPolicyTest {

    @Test
    fun `send and write tools need user approval`() {
        AgentToolPolicy.tools
            .filter { it.risk == Risk.WRITE || it.risk == Risk.SEND }
            .forEach { spec ->
                assertEquals(
                    spec.name,
                    Approval.NEED_USER,
                    AgentToolPolicy.approvalFor(spec.name, emptyMap())
                )
            }
    }

    @Test
    fun `read tools are allowed without approval`() {
        assertEquals(Approval.ALLOW, AgentToolPolicy.approvalFor("list_chats", emptyMap()))
        assertEquals(Approval.ALLOW, AgentToolPolicy.approvalFor("get_chat_history", mapOf("chatId" to "c1")))
        assertEquals(Approval.ALLOW, AgentToolPolicy.approvalFor("search_messages", mapOf("query" to "hi")))
        assertEquals(Approval.ALLOW, AgentToolPolicy.approvalFor("get_contacts", emptyMap()))
        assertEquals(Approval.ALLOW, AgentToolPolicy.approvalFor("rewrite_text", mapOf("text" to "x")))
        assertEquals(Approval.ALLOW, AgentToolPolicy.approvalFor("get_me", emptyMap()))
        assertEquals(Approval.ALLOW, AgentToolPolicy.approvalFor("list_drafts", emptyMap()))
        assertEquals(Approval.ALLOW, AgentToolPolicy.approvalFor("list_notifications", emptyMap()))
    }

    @Test
    fun `unknown tool is denied`() {
        assertEquals(Approval.DENY, AgentToolPolicy.approvalFor("dump_host_sql", emptyMap()))
    }

    @Test
    fun `openai tools json names match builtins`() {
        val names = AgentToolPolicy.openaiToolsJson().map {
            @Suppress("UNCHECKED_CAST")
            ((it["function"] as Map<String, Any?>)["name"] as String)
        }
        assertTrue(names.contains("send_text_message"))
        assertTrue(names.contains("list_chats"))
        assertTrue(names.contains("update_chat"))
        assertTrue(names.contains("set_draft"))
        assertTrue(names.contains("star_message"))
        assertTrue(names.contains("revoke_message"))
        assertTrue(names.contains("create_group"))
        assertTrue(names.contains("list_posts"))
        assertFalse(names.contains("execute_sql"))
        assertFalse(names.contains("dump_ui"))
        assertFalse(names.contains("edit_message"))
    }

    @Test
    fun `system prompt forbids maodou server plaintext`() {
        val prompt = agentSystemPrompt("2026-01-01 00:00:00", null)
        assertTrue(prompt.contains("端到端加密"))
        assertTrue(prompt.contains("send_text_message"))
        assertTrue(prompt.contains("密聊"))
        assertTrue(prompt.contains("草稿"))
        assertTrue(prompt.contains("不能点屏幕") || prompt.contains("禁止声称能点屏幕"))
    }

    @Test
    fun `write previews stay human readable`() {
        val send = AgentToolHost.preview(
            "send_text_message",
            """{"chatId":"c1","text":"hello"}"""
        )
        assertTrue(send.contains("c1"))
        assertTrue(send.contains("hello"))
        val mute = AgentToolHost.preview(
            "update_chat",
            """{"chatId":"c1","muted":"true"}"""
        )
        assertTrue(mute.contains("muted=true"))
    }

    @Test
    fun `parseArgs maps json values to strings`() {
        val args = AgentToolHost.parseArgs("""{"chatId":"c1","starred":true,"limit":20}""")
        assertEquals("c1", args["chatId"])
        assertEquals("true", args["starred"])
        assertEquals("20", args["limit"])
    }

    @Test
    fun `toolByName resolves and misses`() {
        assertEquals("list_chats", AgentToolPolicy.toolByName("list_chats")?.name)
        assertEquals(null, AgentToolPolicy.toolByName("dump_host_sql"))
    }

    @Test
    fun `policy limits are frozen`() {
        assertEquals(12, AgentToolPolicy.MAX_TOOL_ROUNDS)
        assertEquals(24, AgentToolPolicy.MAX_HISTORY_MESSAGES)
        assertEquals(40, AgentToolPolicy.MAX_CHAT_HISTORY)
        assertEquals(20, AgentToolPolicy.MAX_SEARCH_HITS)
        assertEquals(6_000, AgentToolPolicy.MAX_TOOL_RESULT_CHARS)
        assertEquals(4_000, AgentToolPolicy.MAX_TEXT_SEND_CHARS)
        assertEquals(80, AgentToolPolicy.MAX_LIST_CHATS)
        assertEquals(4_000, AgentToolPolicy.MAX_DRAFT_CHARS)
        assertEquals(40, AgentToolPolicy.MAX_NICKNAME_CHARS)
    }

    @Test
    fun `preview falls back to raw name and json`() {
        val preview = AgentToolHost.preview("no_such_tool", """{"a":"b"}""")
        assertTrue(preview.startsWith("no_such_tool"))
        assertTrue(preview.contains("""{"a":"b"}"""))
    }

    @Test
    fun `parseArgs blank and malformed return empty`() {
        assertTrue(AgentToolHost.parseArgs("").isEmpty())
        assertTrue(AgentToolHost.parseArgs("{oops").isEmpty())
    }

    @Test
    fun `parseBool accepts variants`() {
        listOf("true", "TRUE", "1", "yes", " Yes ").forEach {
            assertEquals(true, AgentToolHost.parseBool(it))
        }
        listOf("false", "FALSE", "0", "no", " No ").forEach {
            assertEquals(false, AgentToolHost.parseBool(it))
        }
        assertEquals(null, AgentToolHost.parseBool("maybe"))
        assertEquals(null, AgentToolHost.parseBool(""))
        assertEquals(null, AgentToolHost.parseBool(null))
    }


    @Test
    fun `approval ignores arguments for known and unknown tools`() {
        assertEquals(Approval.ALLOW, AgentToolPolicy.approvalFor("list_chats", mapOf("chatId" to "")))
        assertEquals(
            Approval.NEED_USER,
            AgentToolPolicy.approvalFor("send_text_message", mapOf("chatId" to "c1", "text" to "hi"))
        )
        assertEquals(Approval.DENY, AgentToolPolicy.approvalFor("dump_host_sql", mapOf("x" to "y")))
    }

    @Test
    fun `toolByName is exact and case sensitive`() {
        assertEquals(null, AgentToolPolicy.toolByName("List_Chats"))
        assertEquals(null, AgentToolPolicy.toolByName(""))
        assertEquals(null, AgentToolPolicy.toolByName("list_chats "))
        assertEquals("list_chats", AgentToolPolicy.toolByName("list_chats")?.name)
    }

    @Test
    fun `openai tools json mirrors spec structure and enums`() {
        val json = AgentToolPolicy.openaiToolsJson()
        assertEquals(AgentToolPolicy.tools.size, json.size)
        @Suppress("UNCHECKED_CAST")
        fun fn(entry: Map<String, Any?>) = entry["function"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        fun params(entry: Map<String, Any?>) = fn(entry)["parameters"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        fun props(entry: Map<String, Any?>) = params(entry)["properties"] as Map<String, Map<String, Any?>>
        assertTrue(json.all { it["type"] == "function" })
        val post = json.single { fn(it)["name"] == "create_text_post" }
        assertEquals("object", params(post)["type"])
        assertEquals(setOf("text", "visibility"), props(post).keys)
        assertEquals(listOf("PUBLIC", "CONTACTS", "PRIVATE"), props(post)["visibility"]?.get("enum"))
        assertEquals(listOf("text"), params(post)["required"])
        val chats = json.single { fn(it)["name"] == "list_chats" }
        assertEquals(setOf("query"), props(chats).keys)
        assertEquals(emptyList<String>(), params(chats)["required"])
        assertFalse(props(chats)["query"]?.containsKey("enum") ?: false)
    }

    @Test
    fun `parseArgs keeps null array nested and float values as strings`() {
        assertEquals("null", AgentToolHost.parseArgs("""{"a":null}""")["a"])
        assertEquals("[1,2]", AgentToolHost.parseArgs("""{"arr":[1,2]}""")["arr"])
        assertEquals("{\"b\":1}", AgentToolHost.parseArgs("""{"obj":{"b":1}}""")["obj"])
        assertEquals("3.5", AgentToolHost.parseArgs("""{"f":3.5}""")["f"])
    }

    @Test
    fun `parseBool rejects on off single letters and other digits`() {
        listOf("ON", "off", "y", "n", "t", "f", "2").forEach {
            assertEquals(it, null, AgentToolHost.parseBool(it))
        }
        assertEquals(true, AgentToolHost.parseBool("YES"))
        assertEquals(false, AgentToolHost.parseBool("NO"))
    }

}
