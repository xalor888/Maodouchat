package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.bot.BotCommandPolicy
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import com.maodouchat.network.ApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class ChatBotGroupActionController(
    private val scope: CoroutineScope,
    private val groupLifecycleCoordinator: GroupLifecycleCoordinator,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val activeChatId: () -> String,
    private val sessionActive: (String) -> Boolean,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val text: (Int, Array<out Any>) -> String,
    private val quantityText: (Int, Int, Array<out Any>) -> String,
) {
    fun loadGroupCandidates() {
        val chat = currentState().chat ?: return
        val owner = ownerUserId()
        if (!chat.isGroup) return
        if (token().isBlank() || owner.isBlank()) {
            warn(R.string.error_session_expired)
            return
        }
        scope.launch {
            if (!sessionActive(owner)) return@launch
            ApiService.getAllSearchableUsers(token()).fold(
                onSuccess = { users ->
                    if (!sessionActive(owner)) return@fold
                    val existingIds = currentState().chat?.participants.orEmpty().map { it.id }.toSet()
                    val candidates = users
                        .filter { it.id !in existingIds && it.id != ownerUserId() }
                        .map { User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status, lastSeen = it.lastSeen) }
                    updateState { it.copy(groupCandidates = candidates) }
                },
                onFailure = { error -> warn(error.message ?: string(R.string.contacts_load_failed)) },
            )
        }
    }

    fun renameGroup(newName: String) {
        if (currentState().isUpdatingGroup) return
        val chat = currentState().chat ?: return
        val trimmed = newName.trim()
        if (!chat.isGroup) return
        if (trimmed.isBlank() || trimmed.length > 50) {
            warn(R.string.chat_group_name_length)
            return
        }
        mutateGroup(
            chat = chat,
            successMessage = string(R.string.chat_group_name_updated),
            failureMessage = string(R.string.chat_group_name_failed),
            fallbackChat = { it.copy(groupName = trimmed) },
        ) { liveToken ->
            ApiService.renameGroup(liveToken, chat.id, trimmed).getOrThrow()
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
            successMessage = string(R.string.chat_group_member_added_key),
            failureMessage = string(R.string.chat_group_add_failed),
        ) { liveToken ->
            ApiService.addGroupMembers(liveToken, chat.id, listOf(userId)).getOrThrow()
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
            successMessage = string(R.string.chat_group_member_removed_key),
            failureMessage = string(R.string.chat_group_remove_failed),
        ) { liveToken ->
            ApiService.removeGroupMember(liveToken, chat.id, userId).getOrThrow()
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
        ApiService.postBotInbox(liveToken, chatId, plaintext).onFailure { error ->
            if (error is CancellationException) throw error
            android.util.Log.w("ChatDetail", "bot-inbox failed: ${error.message}")
        }
    }

    fun refreshBotCommands(chatId: String) {
        if (chatId.isBlank()) return
        val owner = ownerUserId()
        scope.launch(Dispatchers.IO) {
            val liveToken = token()
            if (liveToken.isBlank()) return@launch
            val raw = ApiService.listChatBotCommands(liveToken, chatId).getOrNull() ?: return@launch
            if (!sessionActive(owner)) return@launch
            updateState { it.copy(botCommands = parseBotCommands(raw)) }
        }
    }

    fun inviteFirstOwnedBot() {
        scope.launch {
            val liveToken = token()
            if (liveToken.isBlank()) {
                warn(R.string.error_session_expired)
                return@launch
            }
            withContext(Dispatchers.IO) { ApiService.listBots(liveToken) }
                .onSuccess { raw ->
                    val array = runCatching { JSONArray(raw) }.getOrNull()
                    val firstId = if (array != null && array.length() > 0) {
                        array.optJSONObject(0)?.optString("id").orEmpty()
                    } else {
                        ""
                    }
                    if (firstId.isBlank()) warn(R.string.group_play_bot_invite_failed) else inviteBot(firstId)
                }
                .onFailure { warn(R.string.group_play_bot_invite_failed) }
        }
    }

    fun inviteBot(botId: String) {
        val targetChatId = activeChatId()
        if (targetChatId.isBlank() || botId.isBlank()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ApiService.inviteBotToChat(token(), targetChatId, botId)
            }
            result.onSuccess { warn(R.string.group_play_bot_invited) }
                .onFailure { warn(R.string.group_play_bot_invite_failed) }
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
                it.copy(isUpdatingGroup = false, groupEncryptionWarning = string(R.string.error_session_expired))
            }
            return
        }
        scope.launch {
            updateState { it.copy(isUpdatingGroup = true, groupEncryptionWarning = null) }
            try {
                val commit = withContext(Dispatchers.IO) {
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
                    name = updated.groupName ?: string(R.string.chat_group),
                    avatar = updated.groupAvatar,
                    status = quantityText(
                        R.plurals.chat_members_count,
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

    private fun parseBotCommands(raw: String): List<BotCommandPolicy.BotCommandItem> {
        val array = runCatching { JSONObject(raw) }.getOrNull()?.optJSONArray("commands") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val command = item.optString("command").trim()
                val username = item.optString("username").trim()
                val botId = item.optString("botId").trim()
                if (command.isBlank() || username.isBlank() || botId.isBlank()) continue
                add(
                    BotCommandPolicy.BotCommandItem(
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

    private fun warn(resourceId: Int, vararg args: Any) = warn(string(resourceId, *args))

    private fun warn(message: String) {
        updateState { it.copy(groupEncryptionWarning = message) }
    }

    private fun string(resourceId: Int, vararg args: Any): String = text(resourceId, args)
}
