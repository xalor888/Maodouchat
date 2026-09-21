package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * G69 安全网：`ChatExportGuard` 的导出准入契约。
 *
 * 这七条准入户此前全内联在 `ChatDetailViewModel.exportToUri()`（71 行）里，**一个用例都没有**。
 * 它们每一条都是用户可见的故障点，且漏一条的后果不同：
 * - 功能关着还导出 → 绕过运营开关，导出开关形同虚设；
 * - 密聊还导出 → **隐私事故**（密聊内容落到用户自选 URI，可能被其它应用读走）；
 * - 已锁未解锁还导出 → 绕过应用锁；
 * - 空消息/空序列化还导出 → 写出一个空文件，用户以为导出成功。
 */
class ChatExportGuardTest {

    private fun state(
        isChatLocked: Boolean? = null,
        isChatUnlocked: Boolean = false,
        isSecretChat: Boolean? = null,
        hasChat: Boolean = true,
        messages: List<Message> = listOf(
            Message(id = "m1", chatId = "c1", senderId = "u2", content = "hi", type = MessageType.TEXT, timestamp = 1L),
        ),
    ) = ChatDetailUiState(
        isChatLocked = isChatLocked,
        isChatUnlocked = isChatUnlocked,
        isSecretChat = isSecretChat,
        chat = if (hasChat) Chat(id = "c1", participants = listOf(User(id = "u2", name = "U2"))) else null,
        messages = messages,
    )

    @Test
    fun `export is refused when the feature is off`() {
        val result = ChatExportGuard.check(
            state = state(),
            exportEnabled = false,
            ownerUserId = "u1",
            token = "tok",
            sessionMayContinue = true,
            serializedJson = """{"messages":[]}""",
        )
        assertIs<ChatExportGuard.Decision.Reject>(result)
        assertEquals(ChatExportGuard.RejectReason.DISABLED, result.reason)
    }

    @Test
    fun `export is refused without a real session`() {
        listOf(
            Triple("u1", "", "空 token"),
            Triple("", "tok", "空 owner"),
            Triple(ChatExportGuard.PLACEHOLDER_OWNER, "tok", "占位 owner"),
        ).forEach { (owner, token, label) ->
            val result = ChatExportGuard.check(
                state = state(),
                exportEnabled = true,
                ownerUserId = owner,
                token = token,
                sessionMayContinue = true,
                serializedJson = """{"messages":[]}""",
            )
            assertIs<ChatExportGuard.Decision.Reject>(result, "$label 必须被拒")
            assertEquals(ChatExportGuard.RejectReason.NO_SESSION, result.reason, label)
        }
    }

    @Test
    fun `export is refused when the session gate says the owner changed`() {
        val result = ChatExportGuard.check(
            state = state(),
            exportEnabled = true,
            ownerUserId = "u1",
            token = "tok",
            sessionMayContinue = false,
            serializedJson = """{"messages":[]}""",
        )
        assertIs<ChatExportGuard.Decision.Reject>(result)
        assertEquals(ChatExportGuard.RejectReason.STALE_SESSION, result.reason)
    }

    @Test
    fun `export is refused while the chat is locked and not yet unlocked`() {
        val result = ChatExportGuard.check(
            state = state(isChatLocked = true, isChatUnlocked = false),
            exportEnabled = true,
            ownerUserId = "u1",
            token = "tok",
            sessionMayContinue = true,
            serializedJson = """{"messages":[]}""",
        )
        assertIs<ChatExportGuard.Decision.Reject>(result)
        assertEquals(ChatExportGuard.RejectReason.LOCKED, result.reason, "已锁未解锁必须拒——否则应用锁被绕过")
    }

    @Test
    fun `export is allowed once a locked chat is unlocked`() {
        val result = ChatExportGuard.check(
            state = state(isChatLocked = true, isChatUnlocked = true),
            exportEnabled = true,
            ownerUserId = "u1",
            token = "tok",
            sessionMayContinue = true,
            serializedJson = """{"messages":[]}""",
        )
        assertIs<ChatExportGuard.Decision.Allow>(result, "解锁之后应当放行")
    }

    @Test
    fun `export is refused for a secret chat`() {
        val result = ChatExportGuard.check(
            state = state(isSecretChat = true),
            exportEnabled = true,
            ownerUserId = "u1",
            token = "tok",
            sessionMayContinue = true,
            serializedJson = """{"messages":[]}""",
        )
        assertIs<ChatExportGuard.Decision.Reject>(result)
        assertEquals(ChatExportGuard.RejectReason.SECRET_CHAT, result.reason, "密聊导出是隐私事故，必须拒")
    }

    @Test
    fun `export is refused without a chat`() {
        val result = ChatExportGuard.check(
            state = state(hasChat = false),
            exportEnabled = true,
            ownerUserId = "u1",
            token = "tok",
            sessionMayContinue = true,
            serializedJson = """{"messages":[]}""",
        )
        assertIs<ChatExportGuard.Decision.Reject>(result)
        assertEquals(ChatExportGuard.RejectReason.NO_CHAT, result.reason)
    }

    @Test
    fun `export is refused when there are no messages`() {
        val result = ChatExportGuard.check(
            state = state(messages = emptyList()),
            exportEnabled = true,
            ownerUserId = "u1",
            token = "tok",
            sessionMayContinue = true,
            serializedJson = """{"messages":[]}""",
        )
        assertIs<ChatExportGuard.Decision.Reject>(result)
        assertEquals(ChatExportGuard.RejectReason.EMPTY, result.reason, "空会话导出只会产出一个空文件，用户会以为成功")
    }

    @Test
    fun `export is refused when serialization produced nothing usable`() {
        // 空串与裸 "{}" 都算「没内容」——后者是 serialize 出空对象的典型表现
        listOf("", "{}", "   ").forEach { json ->
            val result = ChatExportGuard.check(
                state = state(),
                exportEnabled = true,
                ownerUserId = "u1",
                token = "tok",
                sessionMayContinue = true,
                serializedJson = json,
            )
            assertIs<ChatExportGuard.Decision.Reject>(result, "序列化结果为空必须拒：\"$json\"")
            assertEquals(ChatExportGuard.RejectReason.SERIALIZATION_EMPTY, result.reason)
        }
    }

    @Test
    fun `a fully qualified export is allowed and carries the message count`() {
        val messages = (1..4).map {
            Message(id = "m$it", chatId = "c1", senderId = "u2", content = "b$it", type = MessageType.TEXT, timestamp = it.toLong())
        }
        val result = ChatExportGuard.check(
            state = state(messages = messages),
            exportEnabled = true,
            ownerUserId = "u1",
            token = "tok",
            sessionMayContinue = true,
            serializedJson = """{"messages":["a","b","c","d"]}""",
        )
        assertIs<ChatExportGuard.Decision.Allow>(result)
        assertEquals(4, result.messageCount, "Allow 必须带上消息数，供成功文案（复数形式）用")
    }

    @Test
    fun `guard order puts privacy checks before convenience checks`() {
        // 同时踩中「密聊」和「空消息」时，必须先报密聊——用户看到的原因必须是最重要的那条
        val result = ChatExportGuard.check(
            state = state(isSecretChat = true, messages = emptyList()),
            exportEnabled = true,
            ownerUserId = "u1",
            token = "tok",
            sessionMayContinue = true,
            serializedJson = "{}",
        )
        assertEquals(ChatExportGuard.RejectReason.SECRET_CHAT, (result as ChatExportGuard.Decision.Reject).reason)
    }
}
