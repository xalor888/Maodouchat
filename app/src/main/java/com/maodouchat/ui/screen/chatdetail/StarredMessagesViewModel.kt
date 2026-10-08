package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.data.repository.PinStarNetworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class StarredMessagesUiState(
    val chat: Chat? = null,
    val chatsById: Map<String, Chat> = emptyMap(),
    val messages: List<Message> = emptyList(),
    val currentUserId: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    /** true when opened without a chat scope (settings / all favorites) */
    val globalScope: Boolean = false,
    /** Chat-scoped secret mode (for blind watermark / FLAG_SECURE surface). */
    val isSecretChat: Boolean = false,
    val secretChatId: String = "",
    /** Chat-scoped PIN lock: true when this chat is PIN-locked (and the feature is enabled). */
    val isChatLocked: Boolean = false,
    /** True after the user has unlocked the PIN for this session. */
    val isChatUnlocked: Boolean = false,
)

class StarredMessagesViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val chatId: String = savedStateHandle.get<String>("chatId")?.takeIf { it.isNotBlank() }.orEmpty()
    private val globalScope: Boolean = chatId.isBlank()
    // U02 延伸：仓库入口收进非 ui 的 AppRepositories（不再从 Application 强转后自取 database）。
    private val messageRepo = com.maodouchat.data.repository.AppRepositories.messages
    private val chatLockRepo = com.maodouchat.data.repository.AppRepositories.chatLocks
    private val token: String get() = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
    private val currentUserId: String get() = com.maodouchat.session.CurrentSession.ownerUserId()

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private val _uiState = MutableStateFlow(
        StarredMessagesUiState(currentUserId = currentUserId, globalScope = globalScope)
    )
    val uiState: StateFlow<StarredMessagesUiState> = _uiState.asStateFlow()
    private val loadGeneration = AtomicInteger(0)

    init {
        load()
    }

    fun load() {
        val loadOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (token.isBlank() || loadOwnerUserId.isBlank()) {
            // Default isLoading=true; blank session must not leave the spinner stuck.
            loadGeneration.incrementAndGet()
            _uiState.update {
                it.copy(isLoading = false, error = text(R.string.error_session_expired))
            }
            return
        }
        viewModelScope.launch {
            val generation = loadGeneration.incrementAndGet()
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = loadOwnerUserId,
                )
                ) {
                    if (loadGeneration.get() == generation) {
                        _uiState.update { it.copy(isLoading = false, error = text(R.string.error_session_expired)) }
                    }
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    try {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = loadOwnerUserId,
                        )
                        ) {
                            throw kotlinx.coroutines.CancellationException("starred_session_changed")
                        }
                        val chats = ChatNetworkRepository().chats().getOrThrow().map { it.toDomainChat() }
                        val chatsById = chats.associateBy { it.id }
                        val chat = chatsById[chatId]
                        val remote = PinStarNetworkRepository().starred(
                            chatId = chatId.takeIf { it.isNotBlank() }
                        ).getOrThrow()
                        val localById = messageRepo.getMessagesByIds(remote.map { it.messageId })
                            .associateBy { it.id }
                        // U02 延伸：两组隐藏集合收进非 ui 的 ChatVisibilitySets（每组独立容错语义保留）。
                        val redactionSets = com.maodouchat.data.repository.ChatVisibilitySets.safeRedactionSets()
                        val lockedChatIds = redactionSets.locked
                        val secretChatIds = redactionSets.secret
                        val isSecretScoped = !globalScope && chatId.isNotBlank() && chatId in secretChatIds
                        if (isSecretScoped) {
                            com.maodouchat.security.SecretChatSession.markSurfaceActive(chatId)
                        }
                        val isChatLockedNow = !globalScope && chatId.isNotBlank() &&
                            RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_LOCK) &&
                            chatId in lockedChatIds
                        val messages = remote.mapNotNull { reference ->
                            // Global starred must not surface bodies from PIN-locked or secret chats.
                            if (globalScope && reference.chatId in lockedChatIds) return@mapNotNull null
                            if (globalScope && reference.chatId in secretChatIds) return@mapNotNull null
                            if (!globalScope && chatId in lockedChatIds &&
                                !com.maodouchat.security.ChatLockSession.isUnlocked(chatId)
                            ) {
                                return@mapNotNull null
                            }
                            localById[reference.messageId]
                                ?.takeIf { it.chatId == reference.chatId }
                                ?.copy(starred = true)
                        }
                        Result.success(StarredLoadPayload(chat, chatsById, messages, isSecretScoped, isChatLockedNow))
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
                result.fold(
                    onSuccess = { payload ->
                        if (loadGeneration.get() != generation) return@fold
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = loadOwnerUserId,
                        )
                        ) {
                            return@fold
                        }
                        if (payload.isSecretScoped) {
                            com.maodouchat.security.SecretChatSession.markSurfaceActive(chatId)
                        }
                        _uiState.update {
                            it.copy(
                                chat = payload.chat,
                                chatsById = payload.chatsById,
                                messages = payload.messages,
                                isLoading = false,
                                globalScope = globalScope,
                                isSecretChat = payload.isSecretScoped,
                                secretChatId = if (payload.isSecretScoped) chatId else "",
                                isChatLocked = payload.isChatLocked,
                                // 8.39：若该聊天已在 ChatDetail 解锁过（ChatLockSession 已 markUnlocked），
                                // 收藏页不应再次要求输入 PIN——此前不回填导致消息已解密却仍被 PIN 门挡住
                                isChatUnlocked = !payload.isChatLocked ||
                                    com.maodouchat.security.ChatLockSession.isUnlocked(chatId),
                            )
                        }
                    },
                    onFailure = { error ->
                        if (loadGeneration.get() == generation) {
                            _uiState.update { it.copy(isLoading = false, error = error.message ?: text(R.string.starred_load_failed)) }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (loadGeneration.get() == generation) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                throw error
            }
        }
    }

    fun unlockChatWithPin(pin: String, onResult: (Boolean) -> Unit) {
        if (chatId.isBlank()) {
            onResult(true)
            return
        }
        viewModelScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { chatLockRepo.verify(chatId, pin) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (ok) {
                com.maodouchat.security.ChatLockSession.markUnlocked(chatId)
                _uiState.update { it.copy(isChatUnlocked = true) }
                load()
            }
            onResult(ok)
        }
    }

    // 1.91：收藏列表直接取消收藏（乐观移除该行；服务端仍收藏或失败时恢复）
    fun unstarMessage(messageId: String) {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession()) return
        val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        loadGeneration.incrementAndGet()
        _uiState.update { it.copy(messages = it.messages.filter { m -> m.id != messageId }) }
        viewModelScope.launch {
            val result = PinStarNetworkRepository().toggleStar(messageId = messageId)
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) {
                return@launch
            }
            result.fold(
                onSuccess = { response ->
                    // toggle 语义：响应 starred=true 说明服务端仍是收藏态（此前已在他端取消、
                    // 本次 toggle 又把它收藏回来）——只有列表当前没有该行时才回灌，
                    // 避免覆盖刷新后的最新列表
                    if (response.starred && _uiState.value.messages.none { it.id == messageId }) {
                        _uiState.update { state ->
                            state.copy(messages = (state.messages + message).distinctBy { m -> m.id })
                        }
                    }
                },
                onFailure = { _ ->
                    if (_uiState.value.messages.none { it.id == messageId }) {
                        _uiState.update { state ->
                            state.copy(messages = (state.messages + message).distinctBy { m -> m.id })
                        }
                    }
                }
            )
        }
    }

    // 1.161：清空全部收藏（逐条取消收藏；失败恢复该条）
    fun clearAllStarred() {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession()) return
        val all = _uiState.value.messages
        if (all.isEmpty()) return
        loadGeneration.incrementAndGet()
        _uiState.update { it.copy(messages = emptyList()) }
        viewModelScope.launch {
            // 9.152：此前逐条 toggle 并发执行且每个回调把消息回灌进已清空列表——
            // toggle 语义下（他端已取消的条目会被重新收藏）并发回调必然把条目拉回列表。
            // 改为顺序执行、不再由回调回灌，结束后从服务端权威重拉：失败/被重新收藏的
            // 条目如实恢复显示，避免「清空」动作自我撤销。
            for (message in all) {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    return@launch
                }
                PinStarNetworkRepository().toggleStar(messageId = message.id)
            }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) {
                return@launch
            }
            load()
        }
    }

}

private data class StarredLoadPayload(
    val chat: Chat?,
    val chatsById: Map<String, Chat>,
    val messages: List<Message>,
    val isSecretScoped: Boolean,
    val isChatLocked: Boolean,
)

