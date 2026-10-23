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
}
