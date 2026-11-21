package com.maodouchat.data

import com.maodouchat.data.local.entity.AgentSessionEntity
import com.maodouchat.data.local.entity.LocalAiProviderEntity
import com.maodouchat.data.local.entity.encodeAgentMessages
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.local.entity.toModel
import com.maodouchat.domain.messaging.AgentChatMessage
import com.maodouchat.domain.messaging.AgentSession
import com.maodouchat.domain.messaging.AgentSessionCodec
import com.maodouchat.domain.messaging.AgentSessionTitlePolicy
import com.maodouchat.domain.messaging.LocalAiProtocol
import com.maodouchat.domain.messaging.LocalAiProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiRoomMappingTest {

    @Test
    fun `provider round trip keeps all fields`() {
        val provider = LocalAiProvider(
            id = "p1",
            name = "sf",
            baseUrl = "https://api.siliconflow.cn/v1",
            apiKey = "sk-test",
            model = "deepseek-ai/DeepSeek-V3",
            protocol = LocalAiProtocol.ANTHROPIC_MESSAGES,
            anthropicVersion = "2023-06-01",
            organization = "org",
            extraHeadersJson = "{\"X\":\"y\"}",
            temperature = 0.7,
            topP = null,
            maxTokens = 100,
            contextWindowTokens = 200,
            historyMessageLimit = 5,
            timeoutSeconds = 30,
            stream = false,
            supportsVision = true
        )
        assertEquals(provider, provider.toEntity("u1", 3).toModel())
    }

    @Test
    fun `provider unknown protocol falls back to chat completions`() {
        val entity = LocalAiProviderEntity(
            ownerUserId = "u1",
            id = "p1",
            name = "n",
            baseUrl = "https://x",
            apiKey = "",
            model = "m",
            protocol = "SOMETHING_ELSE",
            anthropicVersion = "2023-06-01",
            organization = "",
            extraHeadersJson = "{}",
            temperature = null,
            topP = null,
            maxTokens = 4096,
            contextWindowTokens = 128000,
            historyMessageLimit = 24,
            timeoutSeconds = 120,
            stream = true,
            supportsVision = false,
            sortOrder = 0
        )
        assertEquals(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, entity.toModel().protocol)
    }

    @Test
    fun `session round trip keeps messages and drops empty tool rows`() {
        val session = AgentSession(
            id = "s1",
            title = "t",
            createdAt = 7L,
            messages = listOf(
                AgentChatMessage(role = "user", content = "hi"),
                AgentChatMessage(role = "tool", content = "", toolName = "list_chats"),
                AgentChatMessage(role = "tool", content = "ok", toolCallId = "c1", toolName = "list_chats")
            )
        )
        val restored = session.toEntity("u1", 0).toModel()
        assertEquals("s1", restored?.id)
        assertEquals("t", restored?.title)
        assertEquals(7L, restored?.createdAt)
        assertEquals(
            listOf(
                AgentChatMessage(role = "user", content = "hi"),
                AgentChatMessage(role = "tool", content = "ok", toolCallId = "c1", toolName = "list_chats")
            ),
            restored?.messages
        )
    }

    @Test
    fun `session messagesJson matches codec shape`() {
        val session = AgentSession(
            id = "s1",
            title = "t",
            createdAt = 1L,
            messages = listOf(AgentChatMessage(role = "user", content = "hi"))
        )
        val entity = session.toEntity("u1", 0)
        val codecMessages = JSONArray(AgentSessionCodec.encodeSessions(listOf(session)))
            .getJSONObject(0).getJSONArray("messages")
        val storedMessages = JSONArray(entity.messagesJson)
        assertEquals(codecMessages.length(), storedMessages.length())
        assertEquals(codecMessages.getJSONObject(0).getString("role"), storedMessages.getJSONObject(0).getString("role"))
        assertEquals(codecMessages.getJSONObject(0).getString("content"), storedMessages.getJSONObject(0).getString("content"))
        assertEquals(
            codecMessages.getJSONObject(0).getString("toolCallId"),
            storedMessages.getJSONObject(0).getString("toolCallId")
        )
    }

    @Test
    fun `session blank title defaults on decode`() {
        val entity = AgentSessionEntity(
            ownerUserId = "u1",
            id = "s1",
            title = "",
            createdAt = 1L,
            messagesJson = encodeAgentMessages(listOf(AgentChatMessage(role = "user", content = "hi"))),
            sortOrder = 0
        )
        assertEquals(AgentSessionTitlePolicy.DEFAULT_TITLE, entity.toModel()?.title)
    }

    @Test
    fun `session with blank id decodes to null`() {
        val entity = AgentSessionEntity(
            ownerUserId = "u1",
            id = "",
            title = "t",
            createdAt = 1L,
            messagesJson = encodeAgentMessages(listOf(AgentChatMessage(role = "user", content = "hi"))),
            sortOrder = 0
        )
        assertNull(entity.toModel())
    }

    @Test
    fun `provider entity keeps null doubles`() {
        val provider = LocalAiProvider(id = "p1", name = "n", baseUrl = "https://x", apiKey = "", model = "m")
        val entity = provider.toEntity("u1", 0)
        assertNull(entity.temperature)
        assertNull(entity.topP)
        assertTrue(entity.toModel().temperature == null && entity.toModel().topP == null)
    }
}
