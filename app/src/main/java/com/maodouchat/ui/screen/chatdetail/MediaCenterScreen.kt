package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.ui.component.ZoomableAsyncImage
import com.maodouchat.ui.component.blindWatermark
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.MessageRepository
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.util.MediaCache
import com.maodouchat.util.MediaExport
import com.maodouchat.util.MediaViewerPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

data class MediaCenterUiState(
    val items: List<MediaCenterItem> = emptyList(),
    val isLoading: Boolean = true,
    /** null while checking lock; true when PIN required and process not unlocked */
    val isChatLocked: Boolean? = null,
    val chatName: String = "",
    val isSecretChat: Boolean = false,
)

class MediaCenterViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    val chatId: String = savedStateHandle["chatId"] ?: ""
    private val app = application as MaodouchatApp
    private val repository = MessageRepository(app.database.messageDao(), app.database)
    private val chatLockRepo = com.maodouchat.data.repository.ChatLockRepository(app.database.chatLockDao())
    private val secretChatRepo = com.maodouchat.data.repository.SecretChatRepository(app.database.secretChatDao())
    private val tokenManager = com.maodouchat.network.TokenManager.getInstance(application)
    /** Capture at open so logout/account switch cannot paint the next owner's media grid. */
    private val ownerUserId: String = tokenManager.getUserId().orEmpty()
    private val _uiState = MutableStateFlow(MediaCenterUiState())
    val uiState: StateFlow<MediaCenterUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            if (
                ownerUserId.isBlank() ||
                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                _uiState.update { MediaCenterUiState(items = emptyList(), isLoading = false, isChatLocked = false) }
                return@launch
            }
            val locked = try {
                chatLockRepo.get(chatId) != null
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            val secret = try {
                secretChatRepo.isSecret(chatId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            if (secret) {
                com.maodouchat.security.SecretChatSession.markSurfaceActive(chatId)
            } else {
                com.maodouchat.security.SecretChatSession.markSurfaceInactive(chatId, getApplication())
            }
            val unlocked = !locked || com.maodouchat.security.ChatLockSession.isUnlocked(chatId)
            val displayName = resolveChatName()
            if (!unlocked) {
                _uiState.update {
                    MediaCenterUiState(
                        items = emptyList(),
                        isLoading = false,
                        isChatLocked = true,
                        chatName = displayName,
                        isSecretChat = secret,
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(isChatLocked = false, chatName = displayName, isSecretChat = secret) }
            observeMedia(displayName)
        }
    }

    fun unlockWithPin(pin: String, onResult: (Boolean) -> Unit) {
        if (chatId.isBlank()) {
            onResult(true)
            return
        }
        viewModelScope.launch {
            val ok = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    chatLockRepo.verify(chatId, pin)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (ok) {
                com.maodouchat.security.ChatLockSession.markUnlocked(chatId)
                val displayName = _uiState.value.chatName.ifBlank { resolveChatName() }
                _uiState.update { it.copy(isChatLocked = false, isLoading = true, chatName = displayName) }
                observeMedia(displayName)
            }
            onResult(ok)
        }
    }

    // 8.48 修复 M6：订阅 Job——解锁/重进时先取消旧 collector，
    // 避免 Room Flow 双订阅（重复写状态/重复媒体恢复）
    private var observeMediaJob: kotlinx.coroutines.Job? = null

    private fun observeMedia(displayName: String) {
        observeMediaJob?.cancel()
        observeMediaJob = viewModelScope.launch {
            if (
                ownerUserId.isBlank() ||
                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                _uiState.update { MediaCenterUiState(items = emptyList(), isLoading = false, isChatLocked = false) }
                return@launch
            }
            repository.observeMediaCenterMessages(chatId).collect { messages ->
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { MediaCenterUiState(items = emptyList(), isLoading = false, isChatLocked = false) }
                    return@collect
                }
                val lockedNow = try {
                    chatLockRepo.get(chatId) != null
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
                if (lockedNow &&
                    !com.maodouchat.security.ChatLockSession.isUnlocked(chatId)
                ) {
                    _uiState.update {
                        MediaCenterUiState(
                            items = emptyList(),
                            isLoading = false,
                            isChatLocked = true,
                            chatName = displayName,
                        )
                    }
                    return@collect
                }
                _uiState.update {
                    MediaCenterUiState(
                        items = buildMediaCenterItems(messages),
                        isLoading = false,
                        isChatLocked = false,
                        chatName = displayName,
                    )
                }
            }
        }
    }

    private suspend fun resolveChatName(): String {
        return try {
            val entity = app.database.chatDao().getChatById(chatId) ?: return ""
            entity.groupName?.takeIf { it.isNotBlank() }
                ?: entity.participantIds
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != ownerUserId }
                    .firstOrNull()
                    ?.let { peerId ->
                        app.database.userDao().getUserById(peerId)?.let { u ->
                            u.nickname?.takeIf { it.isNotBlank() } ?: u.name
                        }
                    }
                ?: ""
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            ""
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
fun MediaCenterScreen(
    onBack: () -> Unit,
    onOpenMessage: (String) -> Unit,
    viewModel: MediaCenterViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf(MediaCenterCategory.MEDIA) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val categories = MediaCenterCategory.entries
    var previewMessage by remember { mutableStateOf<Message?>(null) }
    var exportTarget by remember { mutableStateOf<Message?>(null) }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val mediaSecretLabel = com.maodouchat.ui.component.rememberSecretBlindWatermarkLabel(
        userId = com.maodouchat.network.TokenManager.getInstance(context).getUserId(),
        chatId = viewModel.chatId,
        deviceHint = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ),
        enabled = state.isSecretChat
    )

    val categoryItems = remember(state.items, category, searchQuery) {
        val inCategory = state.items.filter { it.category == category }
        val query = searchQuery.trim()
        if (query.isBlank()) {
            inCategory
        } else {
            inCategory.filter { item -> mediaCenterItemMatches(item, query) }
        }
    }

    if (state.isChatLocked == true) {
        Box(modifier = Modifier.fillMaxSize()) {
            ChatLockGate(
                chatName = state.chatName.ifBlank { stringResource(R.string.chat_this_chat) },
                onUnlock = { pin, onResult -> viewModel.unlockWithPin(pin, onResult) },
                onForgotPin = onBack
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = Primary
                )
            }
        }
    } else {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (state.isSecretChat && mediaSecretLabel.isNotBlank()) {
                    Modifier.blindWatermark(label = mediaSecretLabel, enabled = RuntimeFlags.isEnabled(LocalContext.current, RuntimeFlags.VISIBLE_WATERMARK))
                } else Modifier
            )
    ) {
    Scaffold(
        containerColor = LocalChatPalette.current.chatBackground,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.media_center_title), color = MaterialTheme.colorScheme.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = Primary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
                PrimaryTabRow(selectedTabIndex = category.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
                    categories.forEach { tab ->
                        val count = state.items.count { it.category == tab }
                        Tab(
                            selected = category == tab,
                            onClick = {
                                category = tab
                                searchQuery = ""
                            },
                            text = { Text("${stringResource(tab.labelResource())} ($count)", maxLines = 1) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!state.isLoading && state.isChatLocked == false && state.items.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(200) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.media_center_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        AnimatedContent(
            targetState = category,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "mediaCenterCategory",
            modifier = Modifier.fillMaxSize().weight(1f)
        ) { selected ->
            val selectedItems = if (selected == category) {
                categoryItems
            } else {
                val query = searchQuery.trim()
                val inCategory = state.items.filter { it.category == selected }
                if (query.isBlank()) inCategory else inCategory.filter { mediaCenterItemMatches(it, query) }
            }
            if (state.isLoading || state.isChatLocked == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Primary) }
            } else if (selectedItems.isEmpty()) {
                if (searchQuery.isNotBlank()) {
                    MediaCenterSearchEmpty()
                } else {
                    MediaCenterEmpty(selected)
                }
            } else when (selected) {
                MediaCenterCategory.MEDIA -> MediaGrid(
                    items = selectedItems,
                    onOpenMessage = onOpenMessage,
                    onPreview = { previewMessage = it },
                    onExportActions = { exportTarget = it },
                    secretChatId = if (state.isSecretChat) viewModel.chatId else null,
                    currentUserId = com.maodouchat.network.TokenManager.getInstance(context).getUserId()
                )
                MediaCenterCategory.FILES -> FileList(
                    items = selectedItems,
                    onOpenMessage = onOpenMessage,
                    onExportActions = { exportTarget = it },
                    // 1.320：搜索关键词高亮
                    highlightQuery = searchQuery
                )
                MediaCenterCategory.VOICE -> VoiceList(
                    items = selectedItems,
                    onOpenMessage = onOpenMessage
                )
                MediaCenterCategory.LINKS -> LinkList(selectedItems, onOpenMessage, highlightQuery = searchQuery)
                MediaCenterCategory.LOCATION -> LocationList(selectedItems, onOpenMessage)
            }
        }
        } // Column
    }

    previewMessage?.let { msg ->
        MediaCenterImageViewer(
            message = msg,
            onDismiss = { previewMessage = null },
            secretChatId = if (state.isSecretChat) viewModel.chatId else null,
            currentUserId = com.maodouchat.network.TokenManager.getInstance(context).getUserId()
        )
    }

    exportTarget?.let { msg ->
        val isFile = msg.type == MessageType.FILE
        AlertDialog(
            onDismissRequest = { exportTarget = null },
            title = {
                Text(
                    stringResource(
                        if (isFile) R.string.media_center_files else R.string.media_center_media_item
                    )
                )
            },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            if (state.isSecretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_MEDIA_EXPORT_BLOCK)) {
                                Toast.makeText(context, context.getString(R.string.secret_chat_media_export_blocked), Toast.LENGTH_SHORT).show()
                                exportTarget = null
                                return@TextButton
                            }
                            val meta = msg.parsedMeta()
                            val mime = MediaViewerPolicy.defaultMime(msg.type.name, meta.fileMimeType)
                            val name = MediaViewerPolicy.defaultFileName(msg.type.name, meta.fileName, mime)
                            scope.launch {
                                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    MediaExport.saveToGallery(context, msg.parsedContent(), mime, name)
                                }
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        if (ok) {
                                            if (isFile) R.string.media_export_saved_file else R.string.media_export_saved
                                        } else {
                                            R.string.media_export_save_failed
                                        }
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                                exportTarget = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(if (isFile) R.string.media_center_save_file else R.string.common_save),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(
                        onClick = {
                            if (state.isSecretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_MEDIA_EXPORT_BLOCK)) {
                                Toast.makeText(context, context.getString(R.string.secret_chat_media_export_blocked), Toast.LENGTH_SHORT).show()
                                exportTarget = null
                                return@TextButton
                            }
                            val meta = msg.parsedMeta()
                            val mime = MediaViewerPolicy.defaultMime(msg.type.name, meta.fileMimeType)
                            val ok = MediaExport.share(
                                context,
                                msg.parsedContent(),
                                mime,
                                context.getString(if (isFile) R.string.media_center_share_file else R.string.common_share)
                            )
                            if (!ok) {
                                Toast.makeText(context, context.getString(R.string.media_export_share_failed), Toast.LENGTH_SHORT).show()
                            }
                            exportTarget = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(if (isFile) R.string.media_center_share_file else R.string.common_share),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(
                        onClick = {
                            onOpenMessage(msg.id)
                            exportTarget = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.media_center_open_message), modifier = Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {
                TextButton(onClick = { exportTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
    } // secret watermark Box
    } // unlocked branch
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
private fun MediaGrid(
    items: List<MediaCenterItem>,
    onOpenMessage: (String) -> Unit,
    onPreview: (Message) -> Unit,
    onExportActions: (Message) -> Unit,
    secretChatId: String? = null,
    currentUserId: String? = null
) {
    val context = LocalContext.current
    val gridSecretPayload = remember(secretChatId, currentUserId) {
        if (secretChatId.isNullOrBlank() || !RuntimeFlags.isEnabled(context, RuntimeFlags.BLIND_WATERMARK)) null
        else {
            val dh = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            com.maodouchat.watermark.FrequencyWatermark.buildPayload(currentUserId, secretChatId, dh)
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxSize().padding(3.dp)
    ) {
        items(items, key = { it.message.id }, contentType = { "media_${it.message.type.name}" }) { item ->
            val message = item.message
            val localAvailable = remember(message.id, message.content) {
                MediaCache.isReadableLocalUri(context, message.parsedContent())
            }
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(
                        onClick = {
                            if (!localAvailable) {
                                onOpenMessage(message.id)
                                return@combinedClickable
                            }
                            if (message.type == MessageType.VIDEO) {
                                openLocalContent(context, message.parsedContent(), message.parsedMeta().fileMimeType)
                            } else {
                                onPreview(message)
                            }
                        },
                        onLongClick = {
                            if (localAvailable) onExportActions(message)
                            else Toast.makeText(context, context.getString(R.string.media_export_need_cache), Toast.LENGTH_SHORT).show()
                        }
                    )
            ) {
                if (localAvailable) {
                    AsyncImage(
                        model = OwnerScopedImageKeys.request(
                            context = LocalContext.current,
                            data = message.parsedContent(),
                            secretPayload = gridSecretPayload,
                        ),
                        contentDescription = message.parsedMeta().fileName ?: stringResource(R.string.media_center_media_item),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Outlined.Image, stringResource(R.string.media_center_cache_missing), tint = TextHint, modifier = Modifier.size(42.dp).align(Alignment.Center))
                }
                if (message.type == MessageType.VIDEO) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.message_preview_video), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp).align(Alignment.Center).background(Primary.copy(alpha = 0.75f), RoundedCornerShape(18.dp)).padding(5.dp))
                }
                IconButton(onClick = { onOpenMessage(message.id) }, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) {
                    Icon(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.media_center_open_message), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
private fun MediaCenterImageViewer(
    message: Message,
    onDismiss: () -> Unit,
    secretChatId: String? = null,
    currentUserId: String? = null
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val meta = remember(message.id, message.content) { message.parsedMeta() }
    val mime = MediaViewerPolicy.defaultMime(message.type.name, meta.fileMimeType)
    val displayName = MediaViewerPolicy.defaultFileName(message.type.name, meta.fileName, mime)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
    val secretPayload = remember(secretChatId, currentUserId) {
        if (secretChatId.isNullOrBlank() || !RuntimeFlags.isEnabled(context, RuntimeFlags.BLIND_WATERMARK)) null
        else {
            val dh = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            com.maodouchat.watermark.FrequencyWatermark.buildPayload(currentUserId, secretChatId, dh)
        }
    }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            ZoomableAsyncImage(
                model = OwnerScopedImageKeys.request(
                    context = context,
                    data = message.parsedContent(),
                    secretPayload = secretPayload,
                ),
                contentDescription = stringResource(R.string.chat_fullscreen_image),
                onSingleTap = onDismiss
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_close), tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.media_viewer_hint),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (!secretChatId.isNullOrBlank() && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_MEDIA_EXPORT_BLOCK)) {
                                Toast.makeText(context, context.getString(R.string.secret_chat_media_export_blocked), Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            scope.launch {
                                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    MediaExport.saveToGallery(context, message.parsedContent(), mime, displayName)
                                }
                                Toast.makeText(
                                    context,
                                    context.getString(if (ok) R.string.media_export_saved else R.string.media_export_save_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) { Text(stringResource(R.string.common_save), color = Color.White) }
                    TextButton(
                        onClick = {
                            if (!secretChatId.isNullOrBlank() && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_MEDIA_EXPORT_BLOCK)) {
                                Toast.makeText(context, context.getString(R.string.secret_chat_media_export_blocked), Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            val ok = MediaExport.share(
                                context,
                                message.parsedContent(),
                                mime,
                                context.getString(R.string.common_share)
                            )
                            if (!ok) {
                                Toast.makeText(context, context.getString(R.string.media_export_share_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) { Text(stringResource(R.string.common_share), color = Color.White) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
 private fun FileList(
    items: List<MediaCenterItem>,
    onOpenMessage: (String) -> Unit,
    onExportActions: (Message) -> Unit = {},
    // 1.320：搜索关键词高亮
    highlightQuery: String = ""
) {
    val context = LocalContext.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.message.id }, contentType = { "file" }) { item ->
            val message = item.message
            val meta = message.parsedMeta()
            val localAvailable = remember(message.id, message.content) { MediaCache.isReadableLocalUri(context, message.parsedContent()) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            if (localAvailable) openLocalContent(context, message.parsedContent(), meta.fileMimeType)
                            else onOpenMessage(message.id)
                        },
                        onLongClick = {
                            if (localAvailable) onExportActions(message)
                            else Toast.makeText(context, context.getString(R.string.media_export_need_cache), Toast.LENGTH_SHORT).show()
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Description, stringResource(R.string.message_preview_file), tint = Primary, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        // 1.320：搜索时高亮匹配文件名
                        if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(meta.fileName?.takeIf(String::isNotBlank) ?: stringResource(R.string.message_preview_file))
                        else highlightedText(meta.fileName?.takeIf(String::isNotBlank) ?: stringResource(R.string.message_preview_file), highlightQuery),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(meta.fileSizeBytes?.let(::formatBytes), formatDate(message.timestamp), if (localAvailable) stringResource(R.string.media_center_cached) else stringResource(R.string.media_center_cache_missing)).joinToString(" · "),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
                if (localAvailable) {
                    IconButton(onClick = { onExportActions(message) }) {
                        Icon(Icons.Outlined.Share, stringResource(R.string.media_center_share_file), tint = TextSecondary)
                    }
                }
                IconButton(onClick = { onOpenMessage(message.id) }) {
                    Icon(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.media_center_open_message), tint = TextSecondary)
                }
            }
            HorizontalDivider(color = Outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 62.dp))
        }
    }
}

@Composable
private fun VoiceList(
    items: List<MediaCenterItem>,
    onOpenMessage: (String) -> Unit
) {
    val context = LocalContext.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.message.id }, contentType = { "voice" }) { item ->
            val message = item.message
            val meta = message.parsedMeta()
            val localAvailable = remember(message.id, message.content) {
                MediaCache.isReadableLocalUri(context, message.parsedContent())
            }
            val durationSec = meta.voiceDurationMs?.takeIf { it > 0 }?.let { (it + 999) / 1000 }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenMessage(message.id) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = stringResource(R.string.message_preview_voice),
                    tint = Primary,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.message_preview_voice),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            durationSec?.let { stringResource(R.string.media_center_voice_duration, it) },
                            formatDate(message.timestamp),
                            if (localAvailable) {
                                stringResource(R.string.media_center_cached)
                            } else {
                                stringResource(R.string.media_center_cache_missing)
                            }
                        ).joinToString(" · "),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
                IconButton(onClick = { onOpenMessage(message.id) }) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = stringResource(R.string.media_center_open_message),
                        tint = TextSecondary
                    )
                }
            }
            HorizontalDivider(color = Outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 62.dp))
        }
    }
}

@Composable
private fun LocationList(
    items: List<MediaCenterItem>,
    onOpenMessage: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.message.id }, contentType = { "location" }) { item ->
            val message = item.message
            val payload = remember(message.content) { message.parsedLocation() }
            val label = payload?.label?.takeIf { it.isNotBlank() && it != "当前位置" }
                ?: stringResource(R.string.message_preview_location)
            val coord = payload?.let {
                stringResource(R.string.media_center_location_coords, it.latitude, it.longitude)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenMessage(message.id) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.LocationOn,
                    contentDescription = stringResource(R.string.message_preview_location),
                    tint = Primary,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(coord, formatDate(message.timestamp)).joinToString(" · "),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { onOpenMessage(message.id) }) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = stringResource(R.string.media_center_open_message),
                        tint = TextSecondary
                    )
                }
            }
            HorizontalDivider(color = Outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 62.dp))
        }
    }
}

@Composable
private fun LinkList(items: List<MediaCenterItem>, onOpenMessage: (String) -> Unit, highlightQuery: String = "") {
    val context = LocalContext.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { "${it.message.id}:${it.linkUrl}" }, contentType = { "link" }) { item ->
            val url = item.linkUrl.orEmpty()
            Row(
                modifier = Modifier.fillMaxWidth().clickable { openWebLink(context, url) }.padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Link, stringResource(R.string.media_center_links), tint = Primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // 1.320：搜索时高亮匹配域名/链接
                    Text(
                        if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(Uri.parse(url).host ?: url)
                        else highlightedText(Uri.parse(url).host ?: url, highlightQuery),
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(url)
                        else highlightedText(url, highlightQuery),
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(formatDate(item.message.timestamp), color = TextHint, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { onOpenMessage(item.message.id) }) {
                    Icon(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.media_center_open_message), tint = TextSecondary)
                }
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.media_center_open_link), tint = TextHint, modifier = Modifier.size(18.dp))
            }
            HorizontalDivider(color = Outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 56.dp))
        }
    }
}

private fun mediaCenterItemMatches(item: MediaCenterItem, query: String): Boolean {
    val message = item.message
    val meta = message.parsedMeta()
    val fileName = meta.fileName.orEmpty()
    val mime = meta.fileMimeType.orEmpty()
    val link = item.linkUrl.orEmpty()
    val locationLabel = runCatching {
        val payload = message.parsedLocation()
        payload?.label.orEmpty()
    }.getOrDefault("")
    val content = when (item.category) {
        MediaCenterCategory.LINKS -> link
        MediaCenterCategory.LOCATION -> locationLabel.ifBlank { message.parsedContent() }
        MediaCenterCategory.FILES -> fileName.ifBlank { message.parsedContent() }
        else -> listOf(fileName, mime, message.parsedContent()).joinToString(" ")
    }
    return content.contains(query, ignoreCase = true) ||
        fileName.contains(query, ignoreCase = true) ||
        link.contains(query, ignoreCase = true) ||
        mime.contains(query, ignoreCase = true) ||
        locationLabel.contains(query, ignoreCase = true)
}

@Composable
private fun MediaCenterSearchEmpty() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = TextHint, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.media_center_search_empty), color = TextSecondary)
        }
    }
}

@Composable
private fun MediaCenterEmpty(category: MediaCenterCategory) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = when (category) {
                    MediaCenterCategory.MEDIA -> Icons.Outlined.Image
                    MediaCenterCategory.FILES -> Icons.Outlined.Description
                    MediaCenterCategory.VOICE -> Icons.Outlined.Mic
                    MediaCenterCategory.LINKS -> Icons.Outlined.Link
                    MediaCenterCategory.LOCATION -> Icons.Outlined.LocationOn
                },
                contentDescription = null,
                tint = TextHint,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(stringResource(R.string.media_center_empty), color = TextHint)
        }
    }
}

private fun MediaCenterCategory.labelResource(): Int = when (this) {
    MediaCenterCategory.MEDIA -> R.string.media_center_media
    MediaCenterCategory.FILES -> R.string.media_center_files
    MediaCenterCategory.VOICE -> R.string.media_center_voice
    MediaCenterCategory.LINKS -> R.string.media_center_links
    MediaCenterCategory.LOCATION -> R.string.media_center_location
}

private fun openWebLink(context: Context, url: String) {
    val uri = runCatching { Uri.parse(url) }.getOrNull()?.takeIf { it.scheme in setOf("http", "https") } ?: return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

private fun openLocalContent(context: Context, rawUri: String, mimeType: String?) {
    runCatching {
        val parsed = Uri.parse(rawUri)
        val uri = if (parsed.scheme == "file") {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(requireNotNull(parsed.path)))
        } else parsed
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType?.takeIf(String::isNotBlank) ?: context.contentResolver.getType(uri) ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatDate(timestamp: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))

/** 1.320：媒体中心搜索关键词高亮（复用 GlobalSearchTextHighlight，与 Explore/收藏/通知中心一致）。 */
@Composable
private fun highlightedText(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val snippet = remember(text, query) {
        com.maodouchat.ui.screen.chatlist.GlobalSearchTextHighlight.buildSnippet(text, query)
    }
    return androidx.compose.ui.text.buildAnnotatedString {
        if (snippet.highlights.isEmpty()) {
            append(snippet.text)
            return@buildAnnotatedString
        }
        var cursor = 0
        snippet.highlights.forEach { span ->
            if (span.start > cursor) append(snippet.text.substring(cursor, span.start))
            pushStyle(androidx.compose.ui.text.SpanStyle(color = Primary, fontWeight = FontWeight.SemiBold, background = Primary.copy(alpha = 0.12f)))
            append(snippet.text.substring(span.start, span.end))
            pop()
            cursor = span.end
        }
        if (cursor < snippet.text.length) append(snippet.text.substring(cursor))
    }
}
