package com.maodouchat.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolPolicyTest {

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
    fun `read tool previews fall back to raw name and json`() {
        assertEquals(
            """get_chat_history {"chatId":"c1"}""",
            AgentToolHost.preview("get_chat_history", """{"chatId":"c1"}""")
        )
    }

    @Test
    fun `parseArgs keeps empty object and empty array as strings`() {
        val args = AgentToolHost.parseArgs("""{"o":{},"a":[]}""")
        assertEquals("{}", args["o"])
        assertEquals("[]", args["a"])
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

    @Test
    fun `parseBool rejects numerics other than zero and one`() {
        listOf("2", "-1", "10", "1.0").forEach {
            assertEquals(it, null, AgentToolHost.parseBool(it))
        }
        assertEquals(true, AgentToolHost.parseBool(" 1 "))
        assertEquals(false, AgentToolHost.parseBool(" 0 "))
    }

    @Test
    fun `parseArgs keeps nested array of objects as string`() {
        val args = AgentToolHost.parseArgs("""{"a":[{"b":1}]}""")
        assertEquals("""[{"b":1}]""", args["a"])
    }

    @Test
    fun `preview fallback keeps spaced json verbatim`() {
        assertEquals(
            """no_such_tool {"a": "b"}""",
            AgentToolHost.preview("no_such_tool", """{"a": "b"}""")
        )
    }

    @Test
    fun `parseBool accepts mixed case spellings`() {
        assertEquals(true, AgentToolHost.parseBool("True"))
        assertEquals(false, AgentToolHost.parseBool("FALSE"))
        assertEquals(true, AgentToolHost.parseBool("YeS"))
        assertEquals(false, AgentToolHost.parseBool("nO"))
    }

    @Test
    fun `parseArgs stringifies scalars and null`() {
        val args = AgentToolHost.parseArgs("""{"n":2,"b":true}""")
        assertEquals("2", args["n"])
        assertEquals("true", args["b"])
        assertEquals("null", AgentToolHost.parseArgs("""{"a":null}""")["a"])
    }

    @Test
    fun `parseArgs rejects malformed and blank json`() {
        assertTrue(AgentToolHost.parseArgs("not json").isEmpty())
        assertTrue(AgentToolHost.parseArgs("  ").isEmpty())
    }
}
