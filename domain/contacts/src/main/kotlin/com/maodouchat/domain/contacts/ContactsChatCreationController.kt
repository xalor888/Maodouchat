package com.maodouchat.domain.contacts

import com.maodouchat.core.model.ConversationCreationPort
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ContactsUiState
import com.maodouchat.core.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 会话创建一族：私聊/密聊/群聊/频道创建。从 ContactsViewModel 纯搬移；
// VM 只留同签名委托，状态经 lambda 注入。
class ContactsChatCreationController(
    private val scope: CoroutineScope,
    private val updateState: ((ContactsUiState) -> ContactsUiState) -> Unit,
    private val isCreatingChat: () -> Boolean,
    private val text: (ContactsMessage, Array<out Any>) -> String,
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
            updateState { it.copy(errorMessage = text(ContactsMessage.SECRET_CHAT_FEATURE_DISABLED, emptyArray())) }
            return
        }
        launchChatCreation {
            conversationCreationPort.createSecretChat(peer.id)
        }
    }

    fun createGroupChat(groupName: String, members: List<User>) {
        val name = groupName.trim()
        if (name.isBlank()) {
            updateState { it.copy(errorMessage = text(ContactsMessage.CONTACTS_ENTER_GROUP_NAME, emptyArray())) }
            return
        }
        launchChatCreation {
            conversationCreationPort.createGroupChat(name, members.map { it.id })
        }
    }

    fun createChannelChat(channelName: String, members: List<User>) {
        val name = channelName.trim()
        if (name.isBlank()) {
            updateState { it.copy(errorMessage = text(ContactsMessage.CHAT_CHANNEL_NAME_HINT, emptyArray())) }
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
                                errorMessage = error.message ?: text(ContactsMessage.CONTACTS_CREATE_CHAT_FAILED, emptyArray())
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
                        errorMessage = error.message ?: text(ContactsMessage.CONTACTS_CREATE_CHAT_FAILED, emptyArray())
                    )
                }
            }
        }
    }
}
