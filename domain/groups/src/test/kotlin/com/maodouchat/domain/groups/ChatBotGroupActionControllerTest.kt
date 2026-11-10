package com.maodouchat.domain.groups

import com.maodouchat.core.model.BotCommandItem
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.GroupLifecycleCoordinator
import com.maodouchat.core.model.User
import com.maodouchat.core.model.UserDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatBotGroupActionControllerTest {

    private class FakeGateway : BotGroupActionGateway {
        var users: (suspend (String) -> Result<List<UserDto>>)? = null
        var rename: (suspend (String, String, String) -> Result<Unit>)? = null
        var add: (suspend (String, String, List<String>) -> Result<Unit>)? = null
        var remove: (suspend (String, String, String) -> Result<Unit>)? = null
        var inbox: (suspend (String, String, String) -> Result<String>)? = null
        var commands: (suspend (String, String) -> Result<String>)? = null
        var bots: (suspend (String) -> Result<String>)? = null
        var invite: (suspend (String, String, String) -> Result<String>)? = null

        val calls = mutableListOf<String>()

        private fun <T> unexpected(name: String): T = error("unexpected gateway call: $name")

        override suspend fun allSearchableUsers(token: String): Result<List<UserDto>> {
            calls += "allSearchableUsers"
            return users?.invoke(token) ?: unexpected("allSearchableUsers")
        }

        override suspend fun renameGroup(token: String, chatId: String, name: String): Result<Unit> {
            calls += "renameGroup:$name"
            return rename?.invoke(token, chatId, name) ?: unexpected("renameGroup")
        }

        override suspend fun addMembers(token: String, chatId: String, userIds: List<String>): Result<Unit> {
            calls += "addMembers:$userIds"
            return add?.invoke(token, chatId, userIds) ?: unexpected("addMembers")
        }

        override suspend fun removeMember(token: String, chatId: String, userId: String): Result<Unit> {
            calls += "removeMember:$userId"
            return remove?.invoke(token, chatId, userId) ?: unexpected("removeMember")
        }

        override suspend fun postBotInbox(token: String, chatId: String, plaintext: String): Result<String> {
            calls += "postBotInbox:$plaintext"
            return inbox?.invoke(token, chatId, plaintext) ?: unexpected("postBotInbox")
        }

        override suspend fun chatCommands(token: String, chatId: String): Result<String> {
            calls += "chatCommands"
            return commands?.invoke(token, chatId) ?: unexpected("chatCommands")
        }

        override suspend fun listBots(token: String): Result<String> {
            calls += "listBots"
            return bots?.invoke(token) ?: unexpected("listBots")
        }

        override suspend fun inviteBotToChat(token: String, chatId: String, botId: String): Result<String> {
            calls += "inviteBotToChat:$botId"
            return invite?.invoke(token, chatId, botId) ?: unexpected("inviteBotToChat")
        }
    }

    private class Harness(
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
        val gateway: FakeGateway = FakeGateway(),
        chat: Chat? = Chat(id = "c1", isGroup = true, groupName = "G",
            participants = listOf(User("owner", "Owner"))),
        var token: String = "tok",
        var owner: String = "owner",
        var activeChatId: String = "c1",
    ) {
        var state = ChatDetailUiState(chat = chat)
        val logs = mutableListOf<String>()

        val controller = ChatBotGroupActionController(
            scope = scope,
            groupLifecycleCoordinator = GroupLifecycleCoordinator(
                ownerUserId = { owner },
                token = { token },
                sessionActive = { true },
                fetchChat = { _, _ -> Result.success(null) },
                invalidateEpoch = { _, _, _ -> },
            ),
            botGroupActionGateway = gateway,
            ownerUserId = { owner },
            token = { token },
            activeChatId = { activeChatId },
            sessionActive = { true },
            currentState = { state },
            updateState = { transform -> state = transform(state) },
            text = { message, _ -> "msg:${message.name}" },
            quantityText = { message, quantity, _ -> "qty:${message.name}:$quantity" },
            ioDispatcher = ioDispatcher,
            logWarning = { logs += it },
        )
    }

    private fun groupChat() = Chat(
        id = "c1",
        isGroup = true,
        groupName = "G",
        participants = listOf(User("owner", "Owner"), User("u2", "Two")),
    )

    @Test
    fun `rename blank name warns length`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher)
        h.controller.renameGroup("   ")
        advanceUntilIdle()
        assertEquals("msg:GROUP_NAME_LENGTH", h.state.groupEncryptionWarning)
        assertFalse(h.gateway.calls.any { it.startsWith("renameGroup") })
    }

    @Test
    fun `rename over 50 chars warns length`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher)
        h.controller.renameGroup("x".repeat(51))
        advanceUntilIdle()
        assertEquals("msg:GROUP_NAME_LENGTH", h.state.groupEncryptionWarning)
        assertFalse(h.gateway.calls.any { it.startsWith("renameGroup") })
    }

    @Test
    fun `rename non-group does nothing`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher, chat = Chat(id = "c1", isGroup = false))
        h.controller.renameGroup("New")
        advanceUntilIdle()
        assertTrue(h.gateway.calls.isEmpty())
        assertNull(h.state.groupEncryptionWarning)
    }

    @Test
    fun `rename success updates chat and warns updated`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher, chat = groupChat())
        h.gateway.rename = { _, _, _ -> Result.success(Unit) }
        h.controller.renameGroup("  New Name  ")
        advanceUntilIdle()
        assertEquals(listOf("renameGroup:New Name"), h.gateway.calls)
        assertEquals("New Name", h.state.chat?.groupName)
        assertEquals("msg:GROUP_NAME_UPDATED", h.state.groupEncryptionWarning)
        assertEquals("qty:CHAT_MEMBERS_COUNT:2", h.state.contact.status)
        assertFalse(h.state.isUpdatingGroup)
    }

    @Test
    fun `add blank member does nothing`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher, chat = groupChat())
        h.controller.addGroupMember("  ")
        advanceUntilIdle()
        assertTrue(h.gateway.calls.isEmpty())
    }

    @Test
    fun `add member success refreshes candidates`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher, chat = groupChat())
        h.gateway.add = { _, _, _ -> Result.success(Unit) }
        h.gateway.users = { _ ->
            Result.success(listOf(
                UserDto(id = "u3", name = "Three"),
                UserDto(id = "u2", name = "Two"),
                UserDto(id = "owner", name = "Owner"),
            ))
        }
        h.controller.addGroupMember("u3")
        advanceUntilIdle()
        assertTrue(h.gateway.calls.any { it == "addMembers:[u3]" })
        assertEquals(listOf("u3"), h.state.groupCandidates.map { it.id })
        assertEquals("msg:GROUP_MEMBER_ADDED_KEY", h.state.groupEncryptionWarning)
    }

    @Test
    fun `remove self does nothing`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher, chat = groupChat())
        h.controller.removeGroupMember("owner")
        advanceUntilIdle()
        assertTrue(h.gateway.calls.isEmpty())
    }

    @Test
    fun `load candidates skips non-group`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher, chat = Chat(id = "c1", isGroup = false))
        h.controller.loadGroupCandidates()
        advanceUntilIdle()
        assertTrue(h.gateway.calls.isEmpty())
    }

    @Test
    fun `load candidates blank token warns expired`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher, chat = groupChat(), token = "")
        h.controller.loadGroupCandidates()
        advanceUntilIdle()
        assertEquals("msg:SESSION_EXPIRED", h.state.groupEncryptionWarning)
        assertTrue(h.gateway.calls.isEmpty())
    }

    @Test
    fun `refresh bot commands parses and skips blank fields`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher, chat = groupChat())
        h.gateway.commands = { _, _ ->
            Result.success(
                """{"commands":[
                    {"command":"/start","username":"mybot","botId":"bot_1","name":"","description":"d"},
                    {"command":"","username":"u","botId":"bot_2"},
                    {"command":"/help","username":"helper","botId":"bot_3","name":"Help"}
                ]}"""
            )
        }
        h.controller.refreshBotCommands("c1")
        advanceUntilIdle()
        assertEquals(
            listOf(
                BotCommandItem(botId = "bot_1", username = "mybot", name = "mybot",
                    command = "/start", description = "d"),
                BotCommandItem(botId = "bot_3", username = "helper", name = "Help",
                    command = "/help", description = ""),
            ),
            h.state.botCommands,
        )
    }

    @Test
    fun `invite first owned bot with empty list warns failed`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher, chat = groupChat())
        h.gateway.bots = { _ -> Result.success("[]") }
        h.controller.inviteFirstOwnedBot()
        advanceUntilIdle()
        assertEquals("msg:BOT_INVITE_FAILED", h.state.groupEncryptionWarning)
        assertFalse(h.gateway.calls.any { it.startsWith("inviteBotToChat") })
    }

    @Test
    fun `invite first owned bot invites first id`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher, chat = groupChat())
        h.gateway.bots = { _ -> Result.success("""[{"id":"bot_9","username":"b"}]""") }
        h.gateway.invite = { _, _, _ -> Result.success("ok") }
        h.controller.inviteFirstOwnedBot()
        advanceUntilIdle()
        assertTrue(h.gateway.calls.contains("inviteBotToChat:bot_9"))
        assertEquals("msg:BOT_INVITED", h.state.groupEncryptionWarning)
    }

    @Test
    fun `maybe forward bot inbox sends for direct bot chat`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher,
            chat = Chat(id = "c1", isGroup = false, participants = listOf(User("bot_1", "B"))))
        h.gateway.inbox = { _, _, _ -> Result.success("ok") }
        h.controller.maybeForwardBotInbox("tok", "c1", "/start", isGroup = false, peerId = "bot_1")
        assertEquals(listOf("postBotInbox:/start"), h.gateway.calls)
        assertTrue(h.logs.isEmpty())
    }

    @Test
    fun `maybe forward bot inbox skips group without bots`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher, chat = groupChat())
        h.controller.maybeForwardBotInbox("tok", "c1", "/start", isGroup = true, peerId = "u2")
        assertTrue(h.gateway.calls.isEmpty())
    }

    @Test
    fun `maybe forward bot inbox failure logs warning`() = runTest {
        val ioDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, ioDispatcher,
            chat = Chat(id = "c1", isGroup = false, participants = listOf(User("bot_1", "B"))))
        h.gateway.inbox = { _, _, _ -> Result.failure(IllegalStateException("boom")) }
        h.controller.maybeForwardBotInbox("tok", "c1", "hi", isGroup = false, peerId = "bot_1")
        assertEquals(listOf("bot-inbox failed: boom"), h.logs)
    }
}
