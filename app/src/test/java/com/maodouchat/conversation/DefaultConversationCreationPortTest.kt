package com.maodouchat.conversation

import com.maodouchat.network.ChatDto
import com.maodouchat.network.UserDto
import com.maodouchat.security.SecretChatPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DefaultConversationCreationPortTest {

    private fun mockChatDto(
        id: String,
        isGroup: Boolean = false,
        chatType: String = if (isGroup) "GROUP" else "DIRECT",
        groupName: String? = null
    ): ChatDto = ChatDto(
        id = id,
        participants = listOf(
            UserDto(id = "user-1", name = "Alice"),
            UserDto(id = "user-2", name = "Bob")
        ),
        lastMessage = "",
        lastMessageType = "TEXT",
        isGroup = isGroup,
        chatType = chatType,
        groupName = groupName
    )

    @Test
    fun createDirectChat_failsOnBlankPeerId() = runBlocking {
        val port = DefaultConversationCreationPort(
            sessionProvider = { "owner-1" to "token-1" }
        )
        val result = port.createDirectChat("   ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun createDirectChat_failsOnExpiredSession() = runBlocking {
        val port = DefaultConversationCreationPort(
            sessionProvider = { null to null }
        )
        val result = port.createDirectChat("user-2")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun createDirectChat_success() = runBlocking {
        val onCreatedCalled = AtomicBoolean(false)
        val port = DefaultConversationCreationPort(
            sessionProvider = { "owner-1" to "token-1" },
            createChatApi = { token, ids, isGroup, name, type ->
                assertEquals("token-1", token)
                assertEquals(listOf("user-2"), ids)
                assertFalse(isGroup)
                assertEquals("DIRECT", type)
                Result.success(mockChatDto("chat-direct-1", isGroup = false, chatType = "DIRECT"))
            },
            onChatCreated = { chat ->
                assertEquals("chat-direct-1", chat.id)
                onCreatedCalled.set(true)
            }
        )

        val result = port.createDirectChat("user-2")
        assertTrue(result.isSuccess)
        val chat = result.getOrNull()
        assertEquals("chat-direct-1", chat?.id)
        assertFalse(chat?.isGroup == true)
        assertEquals("DIRECT", chat?.chatType)
        assertTrue(onCreatedCalled.get())
    }

    @Test
    fun createSecretChat_failsWhenFeatureDisabled() = runBlocking {
        val apiCalled = AtomicBoolean(false)
        val port = DefaultConversationCreationPort(
            sessionProvider = { "owner-1" to "token-1" },
            createChatApi = { _, _, _, _, _ ->
                apiCalled.set(true)
                Result.success(mockChatDto("chat-secret-1"))
            },
            isSecretChatFeatureEnabled = { false }
        )

        val result = port.createSecretChat("user-2")
        assertTrue(result.isFailure)
        assertFalse(apiCalled.get())
    }

    @Test
    fun createSecretChat_successWhenFeatureEnabled() = runBlocking {
        val port = DefaultConversationCreationPort(
            sessionProvider = { "owner-1" to "token-1" },
            createChatApi = { _, ids, isGroup, _, type ->
                assertEquals(listOf("user-2"), ids)
                assertFalse(isGroup)
                assertEquals(SecretChatPolicy.CHAT_TYPE, type)
                Result.success(mockChatDto("chat-secret-1", isGroup = false, chatType = SecretChatPolicy.CHAT_TYPE))
            },
            isSecretChatFeatureEnabled = { true }
        )

        val result = port.createSecretChat("user-2")
        assertTrue(result.isSuccess)
        val chat = result.getOrNull()
        assertEquals("chat-secret-1", chat?.id)
        assertTrue(chat?.isSecret == true)
    }

    @Test
    fun createGroupChat_failsOnBlankName() = runBlocking {
        val port = DefaultConversationCreationPort(
            sessionProvider = { "owner-1" to "token-1" }
        )
        val result = port.createGroupChat("  ", listOf("user-2", "user-3"))
        assertTrue(result.isFailure)
    }

    @Test
    fun createGroupChat_success() = runBlocking {
        val port = DefaultConversationCreationPort(
            sessionProvider = { "owner-1" to "token-1" },
            createChatApi = { _, ids, isGroup, name, type ->
                assertEquals(listOf("user-2", "user-3"), ids)
                assertTrue(isGroup)
                assertEquals("My Team", name)
                assertEquals("GROUP", type)
                Result.success(mockChatDto("chat-group-1", isGroup = true, chatType = "GROUP", groupName = "My Team"))
            }
        )

        val result = port.createGroupChat("My Team", listOf("user-2", "user-3", "user-2"))
        assertTrue(result.isSuccess)
        val chat = result.getOrNull()
        assertEquals("chat-group-1", chat?.id)
        assertTrue(chat?.isGroup == true)
        assertEquals("My Team", chat?.groupName)
    }

    @Test
    fun createChannelChat_success() = runBlocking {
        val port = DefaultConversationCreationPort(
            sessionProvider = { "owner-1" to "token-1" },
            createChatApi = { _, ids, isGroup, name, type ->
                assertEquals(listOf("user-2"), ids)
                assertTrue(isGroup)
                assertEquals("Announcements", name)
                assertEquals("CHANNEL", type)
                Result.success(mockChatDto("chat-channel-1", isGroup = true, chatType = "CHANNEL", groupName = "Announcements"))
            }
        )

        val result = port.createChannelChat("Announcements", listOf("user-2"))
        assertTrue(result.isSuccess)
        val chat = result.getOrNull()
        assertEquals("chat-channel-1", chat?.id)
        assertTrue(chat?.isChannel == true)
    }

    @Test
    fun accountSwitchedDuringCreation_abortsResult() = runBlocking {
        var currentAccount = "owner-1" to "token-1"
        val onCreatedCalled = AtomicBoolean(false)

        val port = DefaultConversationCreationPort(
            sessionProvider = { currentAccount },
            createChatApi = { _, _, _, _, _ ->
                // 切号模拟：在 API 响应后，账号已切换为 owner-2
                currentAccount = "owner-2" to "token-2"
                Result.success(mockChatDto("chat-leaked-1"))
            },
            onChatCreated = {
                onCreatedCalled.set(true)
            }
        )

        val result = port.createDirectChat("user-2")
        // 应阻断跨账号写入与回调
        assertTrue(result.isFailure)
        assertFalse(onCreatedCalled.get())
    }
}
