package com.maodouchat.ui.screen.contacts

import com.maodouchat.R
import com.maodouchat.conversation.ConversationCreationPort
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 会话创建一族：私聊/密聊/群聊/频道创建。从 ContactsViewModel 纯搬移；
// VM 只留同签名委托，状态经 lambda 注入。
internal class ContactsChatCreationController(
    private val scope: CoroutineScope,
    private val updateState: ((ContactsUiState) -> ContactsUiState) -> Unit,
    private val isCreatingChat: () -> Boolean,
    private val text: (Int, Array<out Any>) -> String,
    private val conversationCreationPort: ConversationCreationPort,
    private val secretChatEnabled: () -> Boolean,
) {
    fun createDirectChat(user: User) {
        launchChatCreation {
            conversationCreationPort.createDirectChat(user.id)
        }
    }

    fun startSecretChat(peer: User) {
        if (!secretChatEnabled()) {
            updateState { it.copy(errorMessage = text(R.string.secret_chat_feature_disabled, emptyArray())) }
            return
        }
        launchChatCreation {
            conversationCreationPort.createSecretChat(peer.id)
        }
    }

    fun createGroupChat(groupName: String, members: List<User>) {
        val name = groupName.trim()
        if (name.isBlank()) {
            updateState { it.copy(errorMessage = text(R.string.contacts_enter_group_name, emptyArray())) }
            return
        }
        launchChatCreation {
            conversationCreationPort.createGroupChat(name, members.map { it.id })
        }
    }

    fun createChannelChat(channelName: String, members: List<User>) {
        val name = channelName.trim()
        if (name.isBlank()) {
            updateState { it.copy(errorMessage = text(R.string.chat_channel_name_hint, emptyArray())) }
            return
        }
        launchChatCreation {
            conversationCreationPort.createChannelChat(name, members.map { it.id })
        }
    }

    // 会话创建骨架：忙 guard → 置忙 → 端口调用 → 成功回 chatId / 失败文案。
    private fun launchChatCreation(block: suspend () -> Result<Chat>) {
        if (isCreatingChat()) return
        scope.launch {
            updateState { it.copy(isCreatingChat = true, errorMessage = null) }
            try {
                val result = block()
                result.fold(
                    onSuccess = { chat ->
                        updateState { it.copy(isCreatingChat = false, createdChatId = chat.id) }
                    },
                    onFailure = { error ->
                        updateState {
                            it.copy(
                                isCreatingChat = false,
                                errorMessage = error.message ?: text(R.string.contacts_create_chat_failed, emptyArray())
                            )
                        }
                    }
                )
            } catch (error: CancellationException) {
                updateState { it.copy(isCreatingChat = false) }
                throw error
            } catch (error: Exception) {
                updateState {
                    it.copy(
                        isCreatingChat = false,
                        errorMessage = error.message ?: text(R.string.contacts_create_chat_failed, emptyArray())
                    )
                }
            }
        }
    }
}
