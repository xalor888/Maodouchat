package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AgentToolPreviewTest {

    @Test
    fun `messaging previews compose id and text`() {
        assertEquals("发到 c1：hello",
            AgentToolPreview.preview("send_text_message", mapOf("chatId" to "c1", "text" to "hello")))
        assertEquals("改会话 c1 pinned=true muted=null archived=null unread=null",
            AgentToolPreview.preview("update_chat", mapOf("chatId" to "c1", "pinned" to "true")))
        assertEquals("星标 m1 → true",
            AgentToolPreview.preview("star_message", mapOf("messageId" to "m1", "starred" to "true")))
        assertEquals("撤回 m1",
            AgentToolPreview.preview("revoke_message", mapOf("messageId" to "m1")))
        assertEquals("建群 周末出游",
            AgentToolPreview.preview("create_group", mapOf("name" to "周末出游")))
    }

    @Test
    fun `empty draft text shows cleared marker`() {
        assertEquals("草稿 c1：(清空)",
            AgentToolPreview.preview("set_draft", mapOf("chatId" to "c1", "text" to "   ")))
        assertEquals("备注 u1 → (清除)",
            AgentToolPreview.preview("set_contact_nickname", mapOf("userId" to "u1", "nickname" to "")))
    }

    @Test
    fun `long ids are truncated to 24 chars`() {
        val long = "a".repeat(40)
        val expected = "发到 " + "a".repeat(24) + "：hi"
        assertEquals(expected,
            AgentToolPreview.preview("send_text_message", mapOf("chatId" to long, "text" to "hi")))
    }

    @Test
    fun `social previews`() {
        assertEquals("好友申请 u9",
            AgentToolPreview.preview("send_friend_request", mapOf("userId" to "u9")))
        assertEquals("赞动态 p3 liked=true",
            AgentToolPreview.preview("like_post", mapOf("postId" to "p3", "liked" to "true")))
        assertEquals("发动态：早安",
            AgentToolPreview.preview("create_text_post", mapOf("text" to "早安")))
        assertEquals("拉黑 u9",
            AgentToolPreview.preview("block_user", mapOf("userId" to "u9")))
    }

    @Test
    fun `task previews`() {
        assertEquals("任务「买菜」→ c1",
            AgentToolPreview.preview("create_local_task", mapOf("title" to "买菜", "chatId" to "c1")))
        assertEquals("待办 t1 completed=true",
            AgentToolPreview.preview("complete_local_task", mapOf("taskId" to "t1", "completed" to "true")))
        assertEquals("删除待办 t1",
            AgentToolPreview.preview("delete_local_task", mapOf("taskId" to "t1")))
    }

    @Test
    fun `unknown tool has no preview`() {
        assertNull(AgentToolPreview.preview("list_chats", mapOf("query" to "a")))
        assertNull(AgentToolPreview.preview("no_such_tool", emptyMap()))
    }
}
