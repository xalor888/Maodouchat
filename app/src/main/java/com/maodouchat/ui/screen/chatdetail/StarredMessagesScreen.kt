package com.maodouchat.ui.screen.chatdetail

import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.DeleteSweep
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.ui.component.secretPageBlindWatermark
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.MessageRepository
import com.maodouchat.network.ApiService
import com.maodouchat.network.ChatDto
import com.maodouchat.network.MessageDto
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.ShimmerBox
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.util.MediaCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.concurrent.atomic.AtomicInteger
import java.util.Date
import java.util.Locale

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
    private val app = application as MaodouchatApp
    private val messageRepo = MessageRepository(app.database.messageDao(), app.database)
    private val chatLockRepo = com.maodouchat.data.repository.ChatLockRepository(app.database.chatLockDao())
    private val signalProtocol = app.signalProtocol
    private val tokenManager = TokenManager.getInstance(application)
    private val token: String get() = tokenManager.getToken().orEmpty()
    private val currentUserId: String get() = tokenManager.getUserId().orEmpty()

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
        val loadOwnerUserId = tokenManager.getUserId().orEmpty()
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
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
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
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            throw kotlinx.coroutines.CancellationException("starred_session_changed")
                        }
                        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                        val chats = ApiService.getChats(liveToken).getOrThrow().map { it.toDomainChat() }
                        val chatsById = chats.associateBy { it.id }
                        val chat = chatsById[chatId]
                        val cached = if (chatId.isNotBlank()) {
                            messageRepo.getRecentMessages(chatId, 500).associateBy { it.id }
                        } else {
                            emptyMap()
                        }
                        val remote = ApiService.getStarredMessages(
                            liveToken,
                            chatId.takeIf { it.isNotBlank() }
                        ).getOrThrow()
                        val lockedChatIds = try {
                            app.database.chatLockDao().listLockedChatIds().toSet()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptySet()
                        }
                        val secretChatIds = try {
                            app.database.secretChatDao().listSecretChatIds().toSet()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptySet()
                        }
                        val isSecretScoped = !globalScope && chatId.isNotBlank() && chatId in secretChatIds
                        if (isSecretScoped) {
                            com.maodouchat.security.SecretChatSession.markSurfaceActive(chatId)
                        }
                        val isChatLockedNow = !globalScope && chatId.isNotBlank() &&
                            RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_LOCK) &&
                            chatId in lockedChatIds
                        val messages = remote.mapNotNull { dto ->
                            // Global starred must not surface bodies from PIN-locked or secret chats.
                            if (globalScope && dto.chatId in lockedChatIds) return@mapNotNull null
                            if (globalScope && dto.chatId in secretChatIds) return@mapNotNull null
                            if (!globalScope && chatId in lockedChatIds &&
                                !com.maodouchat.security.ChatLockSession.isUnlocked(chatId)
                            ) {
                                return@mapNotNull null
                            }
                            val scopeChat = chatsById[dto.chatId] ?: chat
                            cached[dto.id]?.copy(
                                starred = true,
                                editedAt = dto.editedAt ?: cached[dto.id]?.editedAt,
                                reactions = dto.reactions.ifEmpty { cached[dto.id]?.reactions.orEmpty() }
                            ) ?: decryptDto(dto, scopeChat, if (isSecretScoped) chatId else null).copy(starred = true)
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
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
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
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val liveToken = tokenManager.getToken().orEmpty()
        if (liveToken.isBlank() || ownerUserId.isBlank()) return
        val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        loadGeneration.incrementAndGet()
        _uiState.update { it.copy(messages = it.messages.filter { m -> m.id != messageId }) }
        viewModelScope.launch {
            val result = ApiService.toggleStarMessage(liveToken, messageId)
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
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
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val liveToken = tokenManager.getToken().orEmpty()
        if (liveToken.isBlank() || ownerUserId.isBlank()) return
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
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                ApiService.toggleStarMessage(liveToken, message.id)
            }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            load()
        }
    }

    private fun decryptDto(dto: MessageDto, chat: Chat?, secretChatId: String? = null): Message {
        val base = Message(
            id = dto.id,
            chatId = dto.chatId,
            senderId = dto.senderId,
            content = dto.content,
            type = MessageType.fromWire(dto.type),
            timestamp = dto.timestamp,
            status = MessageStatus.fromWire(dto.status),
            editedAt = dto.editedAt,
            starred = dto.starred,
            reactions = dto.reactions,
            expiresAt = dto.expiresAt, sealedSender = dto.sealedSender
        )
        // 8.48 修复 M3：自己消息不再无条件显示占位符——本地自有设备会话存在时尝试解密
        //（自己的文本消息本地就是明文存储，收藏页此前全局列表每条自己消息都隐藏正文）。
        // 解密失败（无会话/多设备格式）才回退占位，行为与聊天详情一致。
        if (dto.senderId == currentUserId) {
            val own = runCatching {
                if (base.type == MessageType.TEXT || base.type == MessageType.MARKDOWN) {
                    signalProtocol.decryptTextEnvelope(dto.senderId, dto.content)
                } else {
                    signalProtocol.decryptContentEnvelope(dto.senderId, dto.content)
                }
            }.getOrNull()
            if (own is SignalProtocol.DecryptResult.Success &&
                (base.type == MessageType.TEXT || base.type == MessageType.MARKDOWN)
            ) {
                return base.copy(content = own.plaintext)
            }
            return base.copy(content = encryptedPreview(base.type))
        }
        val result = if (chat?.isGroup == true && signalProtocol.isSenderKeyEnvelope(dto.content)) {
            signalProtocol.decryptGroupContentEnvelope(dto.senderId, dto.content)
        } else if (base.type == MessageType.TEXT || base.type == MessageType.MARKDOWN) {
            signalProtocol.decryptTextEnvelope(dto.senderId, dto.content)
        } else {
            signalProtocol.decryptContentEnvelope(dto.senderId, dto.content)
        }
        return when (result) {
            is SignalProtocol.DecryptResult.Success -> {
                if (base.type == MessageType.TEXT || base.type == MessageType.MARKDOWN) base.copy(content = result.plaintext)
                else {
                    val restored = MediaCache.restoreDecryptedMedia(getApplication(), result.plaintext, base.id, base.type, secretChatId)
                    val metadata = restored?.fileMetadata
                    base.copy(
                        content = restored?.uri ?: encryptedPreview(base.type),
                        meta = if (metadata == null) base.meta else base.meta.copy(
                            fileName = metadata.fileName,
                            fileMimeType = metadata.mimeType,
                            fileSizeBytes = metadata.sizeBytes
                        )
                    )
                }
            }
            else -> base.copy(content = encryptedPreview(base.type))
        }
    }

    private fun encryptedPreview(type: MessageType): String = when (type) {
        MessageType.IMAGE -> text(R.string.starred_encrypted_image)
        MessageType.GIF -> text(R.string.starred_encrypted_gif)
        MessageType.STICKER -> text(R.string.starred_encrypted_sticker)
        MessageType.LOCATION -> text(R.string.starred_encrypted_location)
        MessageType.VIDEO -> text(R.string.starred_encrypted_video)
        MessageType.VOICE -> text(R.string.starred_encrypted_voice)
        MessageType.FILE -> text(R.string.starred_encrypted_file)
        else -> text(R.string.starred_encrypted_message)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
fun StarredMessagesScreen(
    onBack: () -> Unit,
    onOpenMessage: (chatId: String, messageId: String) -> Unit = { _, _ -> },
    viewModel: StarredMessagesViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val motion = LocalMotionSettings.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 1.161：清空全部确认
    var showClearStarredConfirm by rememberSaveable { mutableStateOf(false) }
    val title = if (state.globalScope) {
        stringResource(R.string.settings_starred_messages)
    } else {
        stringResource(R.string.chat_starred_messages)
    }
    val meLabel = stringResource(R.string.chat_sender_me)
    val groupLabel = stringResource(R.string.chat_group)
    val memberLabel = stringResource(R.string.chat_group_member)
    val filteredMessages = remember(state.messages, state.chatsById, state.chat, state.currentUserId, searchQuery, meLabel, groupLabel, memberLabel) {
        val q = searchQuery.trim()
        if (q.isEmpty()) state.messages
        else state.messages.filter { message ->
            val scopeChat = state.chatsById[message.chatId] ?: state.chat
            val sender = when {
                message.senderId == state.currentUserId -> meLabel
                else -> scopeChat?.participants?.firstOrNull { it.id == message.senderId }?.displayName ?: memberLabel
            }
            val chatName = when {
                scopeChat == null -> groupLabel
                scopeChat.isGroup -> scopeChat.groupName?.takeIf { it.isNotBlank() } ?: groupLabel
                else -> {
                    val other = scopeChat.participants.firstOrNull { it.id != state.currentUserId }
                        ?: scopeChat.participants.firstOrNull()
                    other?.displayName ?: memberLabel
                }
            }
            // 9.153：与正文解析口径一致——meta 恒在末尾，取最后一个 <meta> 之前的内容做搜索预览
            val preview = message.content.substringBeforeLast("<meta>").trim()
            sender.contains(q, ignoreCase = true) ||
                chatName.contains(q, ignoreCase = true) ||
                preview.contains(q, ignoreCase = true)
        }
    }
    val secretPagePayload = rememberSecretPageWatermarkPayload(
        isSecretChat = state.isSecretChat,
        userId = state.currentUserId,
        chatId = state.secretChatId,
        deviceHint = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(secretPagePayload)
    ) {
    if (state.isChatLocked && !state.isChatUnlocked) {
        ChatLockGate(
            chatName = state.chat?.groupName?.takeIf { it.isNotBlank() }
                ?: state.chat?.participants?.firstOrNull { it.id != state.currentUserId }?.displayName
                ?: stringResource(R.string.chat_this_chat),
            onUnlock = { pin, onResult -> viewModel.unlockChatWithPin(pin, onResult) },
            onForgotPin = onBack
        )
    } else {
    Scaffold(
        containerColor = LocalChatPalette.current.chatBackground,
        topBar = {
            TopAppBar(
                title = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    // 1.161：清空全部收藏
                    if (state.messages.isNotEmpty()) {
                        IconButton(onClick = { showClearStarredConfirm = true }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.starred_clear_all), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.shadow(1.dp)
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                repeat(6) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        ShimmerBox(modifier = Modifier.size(36.dp), cornerRadius = 18.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ShimmerBox(modifier = Modifier.width(120.dp).height(14.dp))
                            ShimmerBox(modifier = Modifier.fillMaxWidth(0.8f).height(12.dp))
                        }
                    }
                }
            }
            state.error != null -> EmptyState(
                title = stringResource(R.string.starred_load_failed),
                subtitle = state.error,
                type = EmptyStateType.NETWORK_ERROR,
                actionText = stringResource(R.string.chat_refresh),
                onAction = { viewModel.load() },
                modifier = Modifier.padding(padding)
            )
            state.messages.isEmpty() -> EmptyState(
                title = stringResource(R.string.starred_empty),
                type = EmptyStateType.GENERIC,
                modifier = Modifier.padding(padding)
            )
            else -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(160) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.starred_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (filteredMessages.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.starred_search_empty),
                        type = EmptyStateType.GENERIC,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredMessages, key = { it.id }, contentType = { "starred_${it.type.name}" }) { message ->
                            val scopeChat = state.chatsById[message.chatId] ?: state.chat
                            val starredCopyPreview = message.starredPreview(context)
                            StarredMessageRow(
                                message = message,
                                senderName = senderName(scopeChat, message, state.currentUserId),
                                chatTitle = if (state.globalScope) chatTitle(scopeChat, state.currentUserId) else null,
                                // 1.232：搜索高亮
                                searchQuery = searchQuery,
                                // 1.243：长按复制内容
                                onCopy = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.settings_starred_messages), starredCopyPreview))
                                    Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
                                },
                                onClick = {
                                    if (message.chatId.isNotBlank()) {
                                        onOpenMessage(message.chatId, message.id)
                                    }
                                },
                                onUnstar = { viewModel.unstarMessage(message.id) },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = motion.listItemFadeInSpec(),
                                    fadeOutSpec = motion.listItemFadeOutSpec(),
                                    placementSpec = motion.listItemPlacementSpec()
                                )
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), modifier = Modifier.padding(start = 68.dp))
                        }
                    }
                }
            }
        }
    }
    } // end else(Scaffold)
    } // secret watermark Box

    // 1.161：清空全部收藏确认
    if (showClearStarredConfirm) {
        AlertDialog(
            onDismissRequest = { showClearStarredConfirm = false },
            title = { Text(stringResource(R.string.starred_clear_all)) },
            text = { Text(stringResource(R.string.starred_clear_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearStarredConfirm = false
                    viewModel.clearAllStarred()
                }) { Text(stringResource(R.string.common_clear), color = LocalChatPalette.current.unreadRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearStarredConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

private data class StarredLoadPayload(
    val chat: Chat?,
    val chatsById: Map<String, Chat>,
    val messages: List<Message>,
    val isSecretScoped: Boolean,
    val isChatLocked: Boolean,
)

@Composable
private fun StarredMessageRow(
    message: Message,
    senderName: String,
    chatTitle: String?,
    // 1.232：搜索高亮
    searchQuery: String = "",
    // 1.243：长按复制内容
    onCopy: (() -> Unit)? = null,
    onClick: () -> Unit,
    onUnstar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (onCopy != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onCopy)
                else Modifier.clickable(onClick = onClick)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Avatar(name = senderName, size = AvatarSize.SM)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(senderName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onUnstar,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.starred_unstar), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
            if (!chatTitle.isNullOrBlank()) {
                Text(chatTitle, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // 1.232：搜索时高亮匹配关键词
            val previewText = message.starredPreview(context)
            Text(
                if (searchQuery.isNotBlank()) highlightedText(previewText, searchQuery) else androidx.compose.ui.text.AnnotatedString(previewText),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(formatStarredTime(message.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun senderName(chat: Chat?, message: Message, currentUserId: String): String {
    if (message.senderId == currentUserId) return stringResource(R.string.chat_sender_me)
    return chat?.participants?.firstOrNull { it.id == message.senderId }?.displayName
        ?: stringResource(R.string.chat_group_member)
}

@Composable
private fun chatTitle(chat: Chat?, currentUserId: String): String {
    if (chat == null) return stringResource(R.string.chat_group)
    if (chat.isGroup) {
        return chat.groupName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_group)
    }
    val other = chat.participants.firstOrNull { it.id != currentUserId } ?: chat.participants.firstOrNull()
    return other?.displayName ?: stringResource(R.string.chat_group_member)
}

@Composable
private fun Message.starredPreview(context: android.content.Context): String = when (type) {
    MessageType.TEXT, MessageType.MARKDOWN -> parsedContent()
    MessageType.IMAGE -> stringResource(R.string.message_preview_image)
    MessageType.GIF -> stringResource(R.string.message_preview_gif)
    MessageType.STICKER -> stringResource(R.string.message_preview_sticker)
    MessageType.LOCATION -> stringResource(R.string.message_preview_location)
    MessageType.VIDEO -> stringResource(R.string.message_preview_video)
    MessageType.VOICE -> stringResource(R.string.message_preview_voice)
    MessageType.FILE -> stringResource(R.string.message_preview_file)
    MessageType.REVOKED -> stringResource(R.string.chat_message_revoked_placeholder)
    else -> content
}

private fun formatStarredTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(timestamp))

// 1.232：搜索关键词高亮（与 ChatList 一致）
@Composable
private fun highlightedText(text: String, query: String): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    val snippet = remember(text, query) {
        com.maodouchat.ui.screen.chatlist.GlobalSearchTextHighlight.buildSnippet(text, query)
    }
    if (snippet.highlights.isEmpty()) {
        append(snippet.text)
        return@buildAnnotatedString
    }
    var cursor = 0
    snippet.highlights.forEach { span ->
        if (span.start > cursor) append(snippet.text.substring(cursor, span.start))
        pushStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)))
        append(snippet.text.substring(span.start, span.end))
        pop()
        cursor = span.end
    }
    if (cursor < snippet.text.length) append(snippet.text.substring(cursor))
}
