package com.maodouchat.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolPolicyTest {

    @Test
    fun `send and write tools need user approval`() {
        AgentToolPolicy.tools
            .filter { it.risk == AgentToolPolicy.Risk.WRITE || it.risk == AgentToolPolicy.Risk.SEND }
            .forEach { spec ->
                assertEquals(
                    spec.name,
                    AgentToolPolicy.Approval.NEED_USER,
                    AgentToolPolicy.approvalFor(spec.name, emptyMap())
                )
            }
    }

    @Test
    fun `read tools are allowed without approval`() {
        assertEquals(AgentToolPolicy.Approval.ALLOW, AgentToolPolicy.approvalFor("list_chats", emptyMap()))
        assertEquals(AgentToolPolicy.Approval.ALLOW, AgentToolPolicy.approvalFor("get_chat_history", mapOf("chatId" to "c1")))
        assertEquals(AgentToolPolicy.Approval.ALLOW, AgentToolPolicy.approvalFor("search_messages", mapOf("query" to "hi")))
        assertEquals(AgentToolPolicy.Approval.ALLOW, AgentToolPolicy.approvalFor("get_contacts", emptyMap()))
        assertEquals(AgentToolPolicy.Approval.ALLOW, AgentToolPolicy.approvalFor("rewrite_text", mapOf("text" to "x")))
        assertEquals(AgentToolPolicy.Approval.ALLOW, AgentToolPolicy.approvalFor("get_me", emptyMap()))
        assertEquals(AgentToolPolicy.Approval.ALLOW, AgentToolPolicy.approvalFor("list_drafts", emptyMap()))
        assertEquals(AgentToolPolicy.Approval.ALLOW, AgentToolPolicy.approvalFor("list_notifications", emptyMap()))
    }

    @Test
    fun `unknown tool is denied`() {
        assertEquals(AgentToolPolicy.Approval.DENY, AgentToolPolicy.approvalFor("dump_host_sql", emptyMap()))
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
        val prompt = AgentToolPolicy.systemPrompt("2026-01-01 00:00:00", null)
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
}
