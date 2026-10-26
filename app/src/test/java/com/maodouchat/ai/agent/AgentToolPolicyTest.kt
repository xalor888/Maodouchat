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

    @Test
    fun `approval follows risk for every catalog tool`() {
        AgentToolPolicy.tools.forEach { spec ->
            val expected = when (spec.risk) {
                Risk.READ -> Approval.ALLOW
                Risk.WRITE, Risk.SEND -> Approval.NEED_USER
            }
            assertEquals(spec.name, expected, AgentToolPolicy.approvalFor(spec.name, emptyMap()))
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
                assertTrue(spec.name + ":" + name, prop != null)
                assertEquals(
                    spec.name + ":" + name,
                    param.enumValues != null,
                    prop!!.containsKey("enum")
                )
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
    fun `parseArgs keeps false and zero as strings`() {
        val args = AgentToolHost.parseArgs("""{"starred":false,"limit":0}""")
        assertEquals("false", args["starred"])
        assertEquals("0", args["limit"])
    }

    @Test
    fun `whitespace-only json input parses empty`() {
        assertTrue(AgentToolHost.parseArgs("   ").isEmpty())
        assertTrue(AgentToolHost.parseArgs("{}").isEmpty())
    }

    @Test
    fun `preview fallback truncates long json to 160 chars`() {
        val json = "{\"a\":\"" + "x".repeat(300) + "\"}"
        val preview = AgentToolHost.preview("no_such_tool", json)
        assertEquals("no_such_tool " + json.take(160), preview)
    }


    @Test
    fun `parseBool trims and lowercases mixed case`() {
        assertEquals(true, AgentToolHost.parseBool("  YeS  "))
        assertEquals(false, AgentToolHost.parseBool("  nO "))
        assertEquals(true, AgentToolHost.parseBool("  TrUe"))
    }

    @Test
    fun `send preview truncates chat id and text`() {
        val preview = AgentToolHost.preview(
            "send_text_message",
            """{"chatId":"""" + "c".repeat(30) + """","text":"""" + "t".repeat(200) + """"}"""
        )
        assertEquals("发到 " + "c".repeat(24) + "：" + "t".repeat(160), preview)
    }

    @Test
    fun `blank draft and nickname previews show clear markers`() {
        assertEquals(
            "草稿 c1：(清空)",
            AgentToolHost.preview("set_draft", """{"chatId":"c1","text":"   "}""")
        )
        assertEquals(
            "备注 u1 → (清除)",
            AgentToolHost.preview("set_contact_nickname", """{"userId":"u1","nickname":""}""")
        )
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
    fun `social previews stay human readable`() {
        assertEquals(
            "好友申请 u1",
            AgentToolHost.preview("send_friend_request", """{"userId":"u1"}""")
        )
        assertEquals(
            "发动态：hello",
            AgentToolHost.preview("create_text_post", """{"text":"hello","visibility":"PUBLIC"}""")
        )
        assertEquals(
            "拉黑 u9",
            AgentToolHost.preview("block_user", """{"userId":"u9"}""")
        )
        assertEquals(
            "赞动态 p1 liked=true",
            AgentToolHost.preview("like_post", """{"postId":"p1","liked":true}""")
        )
    }

    @Test
    fun `task previews stay human readable`() {
        assertEquals(
            "任务「买牛奶」→ c1",
            AgentToolHost.preview("create_local_task", """{"chatId":"c1","title":"买牛奶"}""")
        )
        assertEquals(
            "待办 t1 completed=true",
            AgentToolHost.preview("complete_local_task", """{"taskId":"t1","completed":true}""")
        )
        assertEquals(
            "删除待办 t1",
            AgentToolHost.preview("delete_local_task", """{"taskId":"t1"}""")
        )
    }

    @Test
    fun `group write previews stay human readable`() {
        assertEquals(
            "建群 " + "g".repeat(40),
            AgentToolHost.preview("create_group", """{"name":"""" + "g".repeat(50) + """","memberIds":"u1"}""")
        )
        assertEquals(
            "拉人进群 " + "u".repeat(80),
            AgentToolHost.preview("add_group_members", """{"chatId":"c1","memberIds":"""" + "u".repeat(90) + """"}""")
        )
        assertEquals(
            "移出群 u2",
            AgentToolHost.preview("remove_group_member", """{"chatId":"c1","memberId":"u2"}""")
        )
        assertEquals(
            "改群名 新群",
            AgentToolHost.preview("rename_group", """{"chatId":"c1","name":"新群"}""")
        )
        assertEquals(
            "删除会话 c1",
            AgentToolHost.preview("delete_chat", """{"chatId":"c1"}""")
        )
    }

    @Test
    fun `unpreviewed tools fall back to raw name and json`() {
        // 有执行分支但无预览分支的工具（读工具与 update_group_announcement）走统一回退。
        val chats = """{"query":"hi"}"""
        assertEquals("list_chats $chats", AgentToolHost.preview("list_chats", chats))
        val announcement = """{"chatId":"c1","announcement":"hello"}"""
        assertEquals(
            "update_group_announcement $announcement",
            AgentToolHost.preview("update_group_announcement", announcement)
        )
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
                assertEquals(spec.name + ":" + name, param.type, props[name]?.get("type"))
            }
        }
    }

    @Test
    fun `update chat preview lists all flags`() {
        val preview = AgentToolHost.preview(
            "update_chat",
            """{"chatId":"c1","pinned":true,"muted":false,"archived":true,"markedUnread":false}"""
        )
        assertEquals("改会话 c1 pinned=true muted=false archived=true unread=false", preview)
    }

    @Test
    fun `message action previews stay human readable`() {
        assertEquals(
            "星标 m1 → true",
            AgentToolHost.preview("star_message", """{"messageId":"m1","starred":true}""")
        )
        assertEquals(
            "撤回 m1",
            AgentToolHost.preview("revoke_message", """{"messageId":"m1"}""")
        )
        assertEquals(
            "本机删除消息 m1",
            AgentToolHost.preview("delete_local_message", """{"messageId":"m1"}""")
        )
        assertEquals(
            "通知已读 n1",
            AgentToolHost.preview("mark_notification_read", """{"itemId":"n1"}""")
        )
        assertEquals(
            "置顶消息 m1",
            AgentToolHost.preview("pin_message", """{"messageId":"m1"}""")
        )
        assertEquals(
            "反应 👍 → m1",
            AgentToolHost.preview("react_to_message", """{"messageId":"m1","emoji":"👍"}""")
        )
        assertEquals(
            "开聊 u1",
            AgentToolHost.preview("create_direct_chat", """{"userId":"u1"}""")
        )
        assertEquals(
            "禁言 u2",
            AgentToolHost.preview("mute_group_member", """{"chatId":"c1","memberId":"u2"}""")
        )
    }

    @Test
    fun `update chat preview shows null for missing flags`() {
        assertEquals(
            "改会话 c1 pinned=null muted=null archived=null unread=null",
            AgentToolHost.preview("update_chat", """{"chatId":"c1"}""")
        )
    }

    @Test
    fun `social friend decision previews stay human readable`() {
        assertEquals(
            "同意好友 r1",
            AgentToolHost.preview("accept_friend_request", """{"requestId":"r1"}""")
        )
        assertEquals(
            "拒绝好友 r1",
            AgentToolHost.preview("reject_friend_request", """{"requestId":"r1"}""")
        )
        assertEquals(
            "评论动态 p1",
            AgentToolHost.preview("comment_on_post", """{"postId":"p1"}""")
        )
        assertEquals(
            "删除动态 p1",
            AgentToolHost.preview("delete_post", """{"postId":"p1"}""")
        )
        assertEquals(
            "取消拉黑 u1",
            AgentToolHost.preview("unblock_user", """{"userId":"u1"}""")
        )
    }

    @Test
    fun `draft and post previews truncate text to eighty chars`() {
        assertEquals(
            "草稿 c1：" + "x".repeat(80),
            AgentToolHost.preview("set_draft", """{"chatId":"c1","text":"""" + "x".repeat(100) + """"}""")
        )
        assertEquals(
            "发动态：" + "y".repeat(80),
            AgentToolHost.preview("create_text_post", """{"text":"""" + "y".repeat(100) + """","visibility":"PUBLIC"}""")
        )
    }

    @Test
    fun `parseArgs keeps unicode negative and empty string values`() {
        val args = AgentToolHost.parseArgs("""{"text":"你好世界","n":-3,"e":""}""")
        assertEquals("你好世界", args["text"])
        assertEquals("-3", args["n"])
        assertEquals("", args["e"])
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
    fun `preview fallback keeps empty json as trailing space`() {
        assertEquals("no_such_tool ", AgentToolHost.preview("no_such_tool", ""))
    }

    @Test
    fun `parseArgs keeps negative float and rejects duplicate keys`() {
        assertEquals("-1.5", AgentToolHost.parseArgs("""{"f":-1.5}""")["f"])
        // org.json 20240303 在重复键上抛错，parseArgs 兜底成空表。
        assertTrue(AgentToolHost.parseArgs("""{"a":"1","a":"2"}""").isEmpty())
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
    fun `read tool previews fall back to raw name and json`() {
        assertEquals(
            """get_chat_history {"chatId":"c1"}""",
            AgentToolHost.preview("get_chat_history", """{"chatId":"c1"}""")
        )
    }

    @Test
    fun `list and search limits scale off chat history limit`() {
        assertEquals(AgentToolPolicy.MAX_CHAT_HISTORY * 2, AgentToolPolicy.MAX_LIST_CHATS)
        assertEquals(AgentToolPolicy.MAX_CHAT_HISTORY / 2, AgentToolPolicy.MAX_SEARCH_HITS)
    }

    @Test
    fun `parseArgs keeps empty object and empty array as strings`() {
        val args = AgentToolHost.parseArgs("""{"o":{},"a":[]}""")
        assertEquals("{}", args["o"])
        assertEquals("[]", args["a"])
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
                assertEquals(spec.name + ":" + name, param.description, props[name]?.get("description"))
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
            assertEquals(spec.name, spec.parameters.keys, props.keys)
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
    fun `parseArgs rejects non-object top level json`() {
        assertTrue(AgentToolHost.parseArgs("[1,2]").isEmpty())
        assertTrue(AgentToolHost.parseArgs("\"hi\"").isEmpty())
        assertTrue(AgentToolHost.parseArgs("123").isEmpty())
        assertTrue(AgentToolHost.parseArgs("true").isEmpty())
    }

    @Test
    fun `parseBool rejects leading zero numerics`() {
        assertEquals(null, AgentToolHost.parseBool("01"))
        assertEquals(null, AgentToolHost.parseBool("00"))
        assertEquals(null, AgentToolHost.parseBool(" 01 "))
    }

}
