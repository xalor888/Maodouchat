package com.maodouchat.domain.groups

import com.maodouchat.core.model.BotCommandItem
import com.maodouchat.core.model.BotCommandPolicy
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.maodouchat.core.model.GroupLifecycleCoordinator
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.toDomainChat

/**
 * 机器人群操作向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧
 * botGroupActionMessageResId 映射提供——domain 模块不引用 app 的 R。
 */
enum class BotGroupActionMessage {
    SESSION_EXPIRED,
    CONTACTS_LOAD_FAILED,
    GROUP_NAME_LENGTH,
    GROUP_NAME_UPDATED,
    GROUP_NAME_FAILED,
    GROUP_MEMBER_ADDED_KEY,
    GROUP_MEMBER_REMOVED_KEY,
    GROUP_ADD_FAILED,
    GROUP_REMOVE_FAILED,
    BOT_INVITE_FAILED,
    BOT_INVITED,
    GROUP_FALLBACK_NAME,
    CHAT_MEMBERS_COUNT,
}

class ChatBotGroupActionController(
    private val scope: CoroutineScope,
    private val groupLifecycleCoordinator: GroupLifecycleCoordinator,
    private val botGroupActionGateway: BotGroupActionGateway,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val activeChatId: () -> String,
    private val sessionActive: (String) -> Boolean,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val text: (BotGroupActionMessage, Array<out Any>) -> String,
    private val quantityText: (BotGroupActionMessage, Int, Array<out Any>) -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logWarning: (String) -> Unit = {},
) {
    fun loadGroupCandidates() {
        val chat = currentState().chat ?: return
        val owner = ownerUserId()
        if (!chat.isGroup) return
        if (token().isBlank() || owner.isBlank()) {
            warn(BotGroupActionMessage.SESSION_EXPIRED)
            return
        }
        scope.launch {
            if (!sessionActive(owner)) return@launch
            botGroupActionGateway.allSearchableUsers(token()).fold(
                onSuccess = { users ->
                    if (!sessionActive(owner)) return@fold
                    val existingIds = currentState().chat?.participants.orEmpty().map { it.id }.toSet()
                    val candidates = users
                        .filter { it.id !in existingIds && it.id != ownerUserId() }
                        .map { User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status, lastSeen = it.lastSeen) }
                    updateState { it.copy(groupCandidates = candidates) }
                },
                onFailure = { error -> warn(error.message ?: string(BotGroupActionMessage.CONTACTS_LOAD_FAILED)) },
            )
        }
    }

    fun renameGroup(newName: String) {
        if (currentState().isUpdatingGroup) return
        val chat = currentState().chat ?: return
        val trimmed = newName.trim()
        if (!chat.isGroup) return
        if (trimmed.isBlank() || trimmed.length > 50) {
            warn(BotGroupActionMessage.GROUP_NAME_LENGTH)
            return
        }
        mutateGroup(
            chat = chat,
            successMessage = string(BotGroupActionMessage.GROUP_NAME_UPDATED),
            failureMessage = string(BotGroupActionMessage.GROUP_NAME_FAILED),
            fallbackChat = { it.copy(groupName = trimmed) },
        ) { liveToken ->
            botGroupActionGateway.renameGroup(liveToken, chat.id, trimmed).getOrThrow()
        }
    }

    fun addGroupMember(userId: String) {
        if (currentState().isUpdatingGroup) return
        val chat = currentState().chat ?: return
        if (!chat.isGroup || userId.isBlank()) return
        mutateGroup(
            chat = chat,
            rotateSenderKey = true,
            refreshCandidates = true,
            successMessage = string(BotGroupActionMessage.GROUP_MEMBER_ADDED_KEY),
            failureMessage = string(BotGroupActionMessage.GROUP_ADD_FAILED),
        ) { liveToken ->
            botGroupActionGateway.addMembers(liveToken, chat.id, listOf(userId)).getOrThrow()
        }
    }

    fun removeGroupMember(userId: String) {
        if (currentState().isUpdatingGroup) return
        val chat = currentState().chat ?: return
        val owner = ownerUserId()
        if (!chat.isGroup || userId.isBlank() || userId == owner) return
        mutateGroup(
            chat = chat,
            rotateSenderKey = true,
            refreshCandidates = true,
            successMessage = string(BotGroupActionMessage.GROUP_MEMBER_REMOVED_KEY),
            failureMessage = string(BotGroupActionMessage.GROUP_REMOVE_FAILED),
        ) { liveToken ->
            botGroupActionGateway.removeMember(liveToken, chat.id, userId).getOrThrow()
        }
    }

    suspend fun maybeForwardBotInbox(
        liveToken: String,
        chatId: String,
        plaintext: String,
        isGroup: Boolean,
        peerId: String,
    ) {
        val state = currentState()
        val isDirectWithBot = !isGroup && BotCommandPolicy.isBotUserId(peerId)
        val hasGroupBots = isGroup && (
            state.botCommands.isNotEmpty() || state.chat?.participants.orEmpty().any { BotCommandPolicy.isBotUserId(it.id) }
        )
        if (!BotCommandPolicy.shouldSendInbox(plaintext, isDirectWithBot, hasGroupBots)) return
        botGroupActionGateway.postBotInbox(liveToken, chatId, plaintext).onFailure { error ->
            if (error is CancellationException) throw error
            logWarning("bot-inbox failed: ${error.message}")
        }
    }

    fun refreshBotCommands(chatId: String) {
        if (chatId.isBlank()) return
        val owner = ownerUserId()
        scope.launch(ioDispatcher) {
            val liveToken = token()
            if (liveToken.isBlank()) return@launch
            val raw = botGroupActionGateway.chatCommands(liveToken, chatId).getOrNull() ?: return@launch
            if (!sessionActive(owner)) return@launch
            updateState { it.copy(botCommands = parseBotCommands(raw)) }
        }
    }

    fun inviteFirstOwnedBot() {
        scope.launch {
            val liveToken = token()
            if (liveToken.isBlank()) {
                warn(BotGroupActionMessage.SESSION_EXPIRED)
                return@launch
            }
            withContext(ioDispatcher) { botGroupActionGateway.listBots(liveToken) }
                .onSuccess { raw ->
                    val array = runCatching { JSONArray(raw) }.getOrNull()
                    val firstId = if (array != null && array.length() > 0) {
                        array.optJSONObject(0)?.optString("id").orEmpty()
                    } else {
                        ""
                    }
                    if (firstId.isBlank()) warn(BotGroupActionMessage.BOT_INVITE_FAILED) else inviteBot(firstId)
                }
                .onFailure { warn(BotGroupActionMessage.BOT_INVITE_FAILED) }
        }
    }

    fun inviteBot(botId: String) {
        val targetChatId = activeChatId()
        if (targetChatId.isBlank() || botId.isBlank()) return
        scope.launch {
            val result = withContext(ioDispatcher) {
                botGroupActionGateway.inviteBotToChat(token(), targetChatId, botId)
            }
            result.onSuccess { warn(BotGroupActionMessage.BOT_INVITED) }
                .onFailure { warn(BotGroupActionMessage.BOT_INVITE_FAILED) }
        }
    }

    private fun mutateGroup(
        chat: Chat,
        rotateSenderKey: Boolean = false,
        refreshCandidates: Boolean = false,
        successMessage: String,
        failureMessage: String,
        fallbackChat: (Chat) -> Chat = { it },
        mutation: suspend (token: String) -> Unit,
    ) {
        val owner = ownerUserId()
        if (token().isBlank() || owner.isBlank() || !sessionActive(owner)) {
            updateState {
                it.copy(isUpdatingGroup = false, groupEncryptionWarning = string(BotGroupActionMessage.SESSION_EXPIRED))
            }
            return
        }
        scope.launch {
            updateState { it.copy(isUpdatingGroup = true, groupEncryptionWarning = null) }
            try {
                val commit = withContext(ioDispatcher) {
                    groupLifecycleCoordinator.mutate(
                        chatId = chat.id,
                        rotateSenderKey = rotateSenderKey,
                        mutation = mutation,
                    )
                }
                if (!sessionActive(owner)) {
                    updateState { it.copy(isUpdatingGroup = false) }
                    return@launch
                }
                val updated = commit.refreshedChat?.toDomainChat() ?: fallbackChat(chat)
                val groupContact = User(
                    id = updated.id,
                    name = updated.groupName ?: string(BotGroupActionMessage.GROUP_FALLBACK_NAME),
                    avatar = updated.groupAvatar,
                    status = quantityText(
                        BotGroupActionMessage.CHAT_MEMBERS_COUNT,
                        updated.participants.size,
                        arrayOf(updated.participants.size),
                    ),
                )
                updateState {
                    it.copy(
                        chat = updated,
                        contact = groupContact,
                        isUpdatingGroup = false,
                        groupEncryptionWarning = successMessage,
                    )
                }
                if (refreshCandidates) loadGroupCandidates()
            } catch (error: CancellationException) {
                updateState { it.copy(isUpdatingGroup = false) }
                throw error
            } catch (error: Throwable) {
                updateState {
                    it.copy(
                        isUpdatingGroup = false,
                        groupEncryptionWarning = error.message ?: failureMessage,
                    )
                }
            }
        }
    }

    private fun parseBotCommands(raw: String): List<BotCommandItem> {
        val array = runCatching { JSONObject(raw) }.getOrNull()?.optJSONArray("commands") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val command = item.optString("command").trim()
                val username = item.optString("username").trim()
                val botId = item.optString("botId").trim()
                if (command.isBlank() || username.isBlank() || botId.isBlank()) continue
                add(
                    BotCommandItem(
                        botId = botId,
                        username = username,
                        name = item.optString("name").ifBlank { username },
                        command = command,
                        description = item.optString("description"),
                    ),
                )
            }
        }
    }

    private fun warn(message: BotGroupActionMessage, vararg args: Any) = warn(string(message, *args))

    private fun warn(message: String) {
        updateState { it.copy(groupEncryptionWarning = message) }
    }

    private fun string(message: BotGroupActionMessage, vararg args: Any): String = text(message, args)
}
