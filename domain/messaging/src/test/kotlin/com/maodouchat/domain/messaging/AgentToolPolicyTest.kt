package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Approval
import com.maodouchat.core.model.Risk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentToolPolicyTest {

    @Test
    fun `send and write tools need user approval`() {
        AgentToolPolicy.tools
            .filter { it.risk == Risk.WRITE || it.risk == Risk.SEND }
            .forEach { spec ->
                assertEquals(Approval.NEED_USER, AgentToolPolicy.approvalFor(spec.name, emptyMap()), spec.name)
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
    fun `approval follows risk for every catalog tool`() {
        AgentToolPolicy.tools.forEach { spec ->
            val expected = when (spec.risk) {
                Risk.READ -> Approval.ALLOW
                Risk.WRITE, Risk.SEND -> Approval.NEED_USER
            }
            assertEquals(expected, AgentToolPolicy.approvalFor(spec.name, emptyMap()), spec.name)
        }
    }

    @Test
    fun `empty and padded tool names are denied`() {
        assertEquals(Approval.DENY, AgentToolPolicy.approvalFor("", emptyMap()))
        assertEquals(Approval.DENY, AgentToolPolicy.approvalFor(" list_chats", emptyMap()))
        assertEquals(Approval.DENY, AgentToolPolicy.approvalFor("list_chats ", emptyMap()))
    }

    @Test
    fun `openai tools json mirrors spec descriptions`() {
        val json = AgentToolPolicy.openaiToolsJson()
        assertEquals(AgentToolPolicy.tools.size, json.size)
        AgentToolPolicy.tools.forEachIndexed { index, spec ->
            @Suppress("UNCHECKED_CAST")
            val fn = json[index]["function"] as Map<String, Any?>
            assertEquals(spec.name, fn["name"])
            assertEquals(spec.description, fn["description"])
            assertEquals("object", (fn["parameters"] as Map<String, Any?>)["type"])
        }
    }

    @Test
    fun `openai tools json required lists mirror specs`() {
        val json = AgentToolPolicy.openaiToolsJson()
        AgentToolPolicy.tools.forEachIndexed { index, spec ->
            @Suppress("UNCHECKED_CAST")
            val fn = json[index]["function"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val params = fn["parameters"] as Map<String, Any?>
            assertEquals(spec.name, fn["name"])
            assertEquals(spec.required, params["required"])
        }
    }

    @Test
    fun `openai tools json enums appear only for enum parameters`() {
        AgentToolPolicy.tools.forEachIndexed { index, spec ->
            @Suppress("UNCHECKED_CAST")
            val fn = AgentToolPolicy.openaiToolsJson()[index]["function"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val params = fn["parameters"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val props = params["properties"] as Map<String, Map<String, Any?>>
            spec.parameters.forEach { (name, param) ->
                val prop = props[name]
                assertTrue(prop != null, spec.name + ":" + name)
                assertEquals(param.enumValues != null, prop!!.containsKey("enum"), spec.name + ":" + name)
                if (param.enumValues != null) {
                    assertEquals(param.enumValues, prop["enum"])
                }
            }
        }
    }

    @Test
    fun `catalog tool names are unique`() {
        val names = AgentToolPolicy.tools.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `catalog tool names are non-blank`() {
        assertTrue(AgentToolPolicy.tools.all { it.name.isNotBlank() })
    }

    @Test
    fun `system prompt includes style hint only when non-blank`() {
        val withHint = agentSystemPrompt("2026-01-01 00:00:00", "简洁有力")
        assertTrue(withHint.contains("写作偏好"))
        assertTrue(withHint.contains("简洁有力"))
        val blankHint = agentSystemPrompt("2026-01-01 00:00:00", "   ")
        assertFalse(blankHint.contains("写作偏好"))
    }

    @Test
    fun `openai tools json parameter types mirror specs`() {
        AgentToolPolicy.tools.forEachIndexed { index, spec ->
            @Suppress("UNCHECKED_CAST")
            val fn = AgentToolPolicy.openaiToolsJson()[index]["function"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val params = fn["parameters"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val props = params["properties"] as Map<String, Map<String, Any?>>
            spec.parameters.forEach { (name, param) ->
                assertEquals(param.type, props[name]?.get("type"), spec.name + ":" + name)
            }
        }
    }

    @Test
    fun `policy limits are positive and ordered`() {
        listOf(
            AgentToolPolicy.MAX_TOOL_ROUNDS,
            AgentToolPolicy.MAX_HISTORY_MESSAGES,
            AgentToolPolicy.MAX_CHAT_HISTORY,
            AgentToolPolicy.MAX_SEARCH_HITS,
            AgentToolPolicy.MAX_TOOL_RESULT_CHARS,
            AgentToolPolicy.MAX_TEXT_SEND_CHARS,
            AgentToolPolicy.MAX_LIST_CHATS,
            AgentToolPolicy.MAX_DRAFT_CHARS,
            AgentToolPolicy.MAX_NICKNAME_CHARS
        ).forEach { assertTrue(it > 0) }
        assertTrue(AgentToolPolicy.MAX_TOOL_RESULT_CHARS > AgentToolPolicy.MAX_TEXT_SEND_CHARS)
        assertTrue(AgentToolPolicy.MAX_LIST_CHATS > AgentToolPolicy.MAX_CHAT_HISTORY)
        assertTrue(AgentToolPolicy.MAX_CHAT_HISTORY > AgentToolPolicy.MAX_SEARCH_HITS)
        assertTrue(AgentToolPolicy.MAX_HISTORY_MESSAGES > AgentToolPolicy.MAX_TOOL_ROUNDS)
    }

    @Test
    fun `approval is case sensitive for tool names`() {
        assertEquals(Approval.DENY, AgentToolPolicy.approvalFor("LIST_CHATS", emptyMap()))
        assertEquals(Approval.DENY, AgentToolPolicy.approvalFor("Send_Text_Message", emptyMap()))
    }

    @Test
    fun `openai tools json entry and function key sets are exact`() {
        AgentToolPolicy.openaiToolsJson().forEach { entry ->
            assertEquals(setOf("type", "function"), entry.keys)
            @Suppress("UNCHECKED_CAST")
            val fn = entry["function"] as Map<String, Any?>
            assertEquals(setOf("name", "description", "parameters"), fn.keys)
            @Suppress("UNCHECKED_CAST")
            val params = fn["parameters"] as Map<String, Any?>
            assertEquals(setOf("type", "properties", "required"), params.keys)
        }
    }

    @Test
    fun `toolByName exposes risk for send and read tools`() {
        assertEquals(Risk.SEND, AgentToolPolicy.toolByName("send_text_message")?.risk)
        assertEquals(Risk.READ, AgentToolPolicy.toolByName("list_chats")?.risk)
        assertEquals(Risk.READ, AgentToolPolicy.toolByName("get_chat_history")?.risk)
    }

    @Test
    fun `system prompt embeds the given time label`() {
        val prompt = agentSystemPrompt("2026-01-01 00:00:00", null)
        assertTrue(prompt.contains("2026-01-01 00:00:00"))
        assertTrue(prompt.contains("会话锚点"))
    }

    @Test
    fun `list and search limits scale off chat history limit`() {
        assertEquals(AgentToolPolicy.MAX_CHAT_HISTORY * 2, AgentToolPolicy.MAX_LIST_CHATS)
        assertEquals(AgentToolPolicy.MAX_CHAT_HISTORY / 2, AgentToolPolicy.MAX_SEARCH_HITS)
    }

    @Test
    fun `openai tools json parameter descriptions mirror specs`() {
        AgentToolPolicy.tools.forEachIndexed { index, spec ->
            @Suppress("UNCHECKED_CAST")
            val fn = AgentToolPolicy.openaiToolsJson()[index]["function"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val params = fn["parameters"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val props = params["properties"] as Map<String, Map<String, Any?>>
            spec.parameters.forEach { (name, param) ->
                assertEquals(param.description, props[name]?.get("description"), spec.name + ":" + name)
            }
        }
    }

    @Test
    fun `openai tools json properties key sets mirror spec parameter names`() {
        AgentToolPolicy.tools.forEachIndexed { index, spec ->
            @Suppress("UNCHECKED_CAST")
            val fn = AgentToolPolicy.openaiToolsJson()[index]["function"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val params = fn["parameters"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val props = params["properties"] as Map<String, Map<String, Any?>>
            assertEquals(spec.parameters.keys, props.keys, spec.name)
        }
    }

    @Test
    fun `openai tools json builds fresh maps on every call`() {
        val json1 = AgentToolPolicy.openaiToolsJson()
        val json2 = AgentToolPolicy.openaiToolsJson()
        assertTrue(json1 !== json2)
        assertTrue(json1[0] !== json2[0])
        @Suppress("UNCHECKED_CAST")
        val fn1 = json1[0]["function"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val fn2 = json2[0]["function"] as Map<String, Any?>
        assertTrue(fn1 !== fn2)
    }

    @Test
    fun `openai tools json type fields are function and object for every tool`() {
        AgentToolPolicy.openaiToolsJson().forEach { entry ->
            assertEquals("function", entry["type"])
            @Suppress("UNCHECKED_CAST")
            val fn = entry["function"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val params = fn["parameters"] as Map<String, Any?>
            assertEquals("object", params["type"])
        }
    }

    @Test
    fun `every tool required list is a subset of its parameter names`() {
        AgentToolPolicy.tools.forEach { spec ->
            assertTrue(spec.required.all { it in spec.parameters.keys })
        }
    }

    @Test
    fun `send text message is the only send risk tool`() {
        assertEquals(
            listOf("send_text_message"),
            AgentToolPolicy.tools.filter { it.risk == Risk.SEND }.map { it.name }
        )
        assertTrue(AgentToolPolicy.tools.any { it.risk == Risk.READ })
        assertTrue(AgentToolPolicy.tools.any { it.risk == Risk.WRITE })
    }

    @Test
    fun `every tool and parameter description is non-blank`() {
        AgentToolPolicy.tools.forEach { spec ->
            assertTrue(spec.description.isNotBlank())
            spec.parameters.values.forEach { assertTrue(it.description.isNotBlank()) }
        }
    }

    @Test
    fun `every tool name is lowercase snake case`() {
        AgentToolPolicy.tools.forEach { spec ->
            assertTrue(spec.name.none { it.isUpperCase() })
            assertFalse(spec.name.contains(" "))
        }
    }

    @Test
    fun `read tools are allowed without approval across the whole catalog`() {
        AgentToolPolicy.tools
            .filter { it.risk == Risk.READ }
            .forEach { spec ->
                assertEquals(Approval.ALLOW, AgentToolPolicy.approvalFor(spec.name, emptyMap()), spec.name)
            }
    }

    @Test
    fun `toolByName exposes write risk for group tools`() {
        assertEquals(Risk.WRITE, AgentToolPolicy.toolByName("create_group")?.risk)
        assertEquals(Risk.WRITE, AgentToolPolicy.toolByName("rename_group")?.risk)
        assertEquals(listOf("name", "memberIds"), AgentToolPolicy.toolByName("create_group")?.required)
    }

    @Test
    fun `toolByName exposes send tool required arguments`() {
        assertEquals(listOf("chatId", "text"), AgentToolPolicy.toolByName("send_text_message")?.required)
        assertEquals(Risk.SEND, AgentToolPolicy.toolByName("send_text_message")?.risk)
    }

    @Test
    fun `openai tools json preserves catalog order`() {
        val names = AgentToolPolicy.openaiToolsJson().map {
            @Suppress("UNCHECKED_CAST")
            ((it["function"] as Map<String, Any?>)["name"] as String)
        }
        assertEquals(AgentToolPolicy.tools.map { it.name }, names)
    }

    @Test
    fun `openai tools json names are unique`() {
        val names = AgentToolPolicy.openaiToolsJson().map {
            @Suppress("UNCHECKED_CAST")
            ((it["function"] as Map<String, Any?>)["name"] as String)
        }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `write tool approval ignores blank arguments`() {
        assertEquals(Approval.NEED_USER, AgentToolPolicy.approvalFor("create_group", emptyMap()))
        assertEquals(
            Approval.NEED_USER,
            AgentToolPolicy.approvalFor("create_group", mapOf("name" to "", "memberIds" to ""))
        )
    }

    @Test
    fun `search and contacts tools carry read risk`() {
        assertEquals(Risk.READ, AgentToolPolicy.toolByName("search_messages")?.risk)
        assertEquals(Risk.READ, AgentToolPolicy.toolByName("get_contacts")?.risk)
        assertEquals(Approval.ALLOW, AgentToolPolicy.approvalFor("search_messages", emptyMap()))
    }

    @Test
    fun `toolByName matches names exactly`() {
        assertTrue(AgentToolPolicy.toolByName("LIST_CHATS") == null)
        assertTrue(AgentToolPolicy.toolByName(" list_chats ") == null)
        assertTrue(AgentToolPolicy.toolByName("") == null)
        assertTrue(AgentToolPolicy.toolByName("list_chats") != null)
    }

    @Test
    fun `catalog spans all three risk kinds`() {
        assertTrue(AgentToolPolicy.tools.any { it.risk == Risk.READ })
        assertTrue(AgentToolPolicy.tools.any { it.risk == Risk.WRITE })
        assertTrue(AgentToolPolicy.tools.any { it.risk == Risk.SEND })
    }

    @Test
    fun `draft limit equals text send limit`() {
        assertEquals(AgentToolPolicy.MAX_TEXT_SEND_CHARS, AgentToolPolicy.MAX_DRAFT_CHARS)
    }

    @Test
    fun `write approval ignores non-blank arguments`() {
        assertEquals(
            Approval.NEED_USER,
            AgentToolPolicy.approvalFor("create_group", mapOf("name" to "g", "memberIds" to "[\"u1\"]"))
        )
    }

    @Test
    fun `openai property maps carry only type description and enum keys`() {
        val json = AgentToolPolicy.openaiToolsJson()
        AgentToolPolicy.tools.forEachIndexed { index, spec ->
            @Suppress("UNCHECKED_CAST")
            val fn = json[index]["function"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val params = fn["parameters"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val props = params["properties"] as Map<String, Map<String, Any?>>
            spec.parameters.forEach { (name, _) ->
                val prop = props[name]
                assertTrue(prop != null, spec.name + ":" + name)
                assertTrue(prop!!.keys.all { it == "type" || it == "description" || it == "enum" })
                assertTrue(prop.containsKey("type"))
                assertTrue(prop.containsKey("description"))
            }
        }
    }

    @Test
    fun `zero parameter tool serializes to empty properties and required`() {
        val json = AgentToolPolicy.openaiToolsJson()
        val entry = json.single { item ->
            @Suppress("UNCHECKED_CAST")
            (item["function"] as Map<String, Any?>)["name"] == "get_me"
        }
        @Suppress("UNCHECKED_CAST")
        val fn = entry["function"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val params = fn["parameters"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val props = params["properties"] as Map<String, Any?>
        assertTrue(props.isEmpty())
        assertEquals(emptyList<String>(), params["required"])
    }

    @Test
    fun `catalog starts with list chats and ends with delete chat`() {
        assertEquals("list_chats", AgentToolPolicy.tools.first().name)
        assertEquals("delete_chat", AgentToolPolicy.tools.last().name)
    }
}
