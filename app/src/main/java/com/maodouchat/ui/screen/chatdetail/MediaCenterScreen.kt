package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.ui.theme.LocalChatPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import com.maodouchat.ui.component.SearchHighlightAccent
import androidx.compose.ui.text.AnnotatedString
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
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.ui.component.ZoomableAsyncImage
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.ui.component.secretPageBlindWatermark
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.MediaCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import com.maodouchat.navigation.AppLinkOpener

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
    // U02 延伸：仓库入口收进非 ui 的 AppRepositories（不再从 Application 强转后自取 database）。
    private val repository = com.maodouchat.data.repository.AppRepositories.messages
    private val chatLockRepo = com.maodouchat.data.repository.AppRepositories.chatLocks

    /** Capture at open so logout/account switch cannot paint the next owner's media grid. */
    private val ownerUserId: String = com.maodouchat.session.CurrentSession.ownerUserId()
    private val _uiState = MutableStateFlow(MediaCenterUiState())
    val uiState: StateFlow<MediaCenterUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            if (
                ownerUserId.isBlank() ||
                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
            ) {
                _uiState.update { MediaCenterUiState(items = emptyList(), isLoading = false, isChatLocked = false) }
                return@launch
            }
            val caps = try {
                com.maodouchat.security.SecretChatCapabilities.forChat(chatId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                com.maodouchat.domain.messaging.ConversationPrivacyCapabilities(isSecretChat = true, isLocked = false)
            }
            val locked = caps.isLocked
            val secret = caps.isSecretChat
            if (secret) {
                com.maodouchat.security.SecretChatSession.markSurfaceActive(chatId)
            } else {
                com.maodouchat.security.SecretChatSession.clearSurfaceMarker(chatId)
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

    internal val mediaExportUseCase: MediaExportUseCase = DefaultMediaExportUseCase(application)

    fun saveMessageMedia(message: Message, onResult: (MediaExportResult) -> Unit) {
        viewModelScope.launch {
            val res = mediaExportUseCase.saveMessageMedia(message, _uiState.value.isSecretChat)
            onResult(res)
        }
    }

    fun shareMessageMedia(message: Message, chooserTitle: String, onResult: (MediaExportResult) -> Unit) {
        viewModelScope.launch {
            val res = mediaExportUseCase.shareMessageMedia(message, _uiState.value.isSecretChat, chooserTitle)
            onResult(res)
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
                )
            ) {
                _uiState.update { MediaCenterUiState(items = emptyList(), isLoading = false, isChatLocked = false) }
                return@launch
            }
            repository.observeMediaCenterMessages(chatId).collect { messages ->
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
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
            val entity = com.maodouchat.data.repository.AppRepositories.chatEntityOrNull(chatId) ?: return ""
            entity.groupName?.takeIf { it.isNotBlank() }
                ?: entity.participantIds
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != ownerUserId }
                    .firstOrNull()
                    ?.let { peerId ->
                        com.maodouchat.data.repository.AppRepositories.users.getUserById(peerId)?.let { u ->
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
fun MediaCenterScreen(
    onBack: () -> Unit,
    onOpenMessage: (String) -> Unit,
    viewModel: MediaCenterViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf(MediaCenterCategory.MEDIA) }
    val categories = MediaCenterCategory.entries
    val context = LocalContext.current
    val secretPagePayload = rememberSecretPageWatermarkPayload(
        isSecretChat = state.isSecretChat,
        userId = com.maodouchat.session.CurrentSession.ownerUserId(),
        chatId = viewModel.chatId,
        deviceHint = com.maodouchat.watermark.DeviceHint.androidId(context)
    )

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
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    } else {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(secretPagePayload)
    ) {
    Scaffold(
        containerColor = LocalChatPalette.current.chatBackground,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.media_center_title), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.headingSemantics()) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
                PrimaryTabRow(selectedTabIndex = category.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
                    categories.forEach { tab ->
                        val count = state.items.count { it.category == tab }
                        Tab(
                            selected = category == tab,
                            onClick = { category = tab },
                            text = { Text("${stringResource(tab.labelResource())} ($count)", maxLines = 1) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        MediaCenterCategoryContent(
            category = category,
            state = state,
            viewModel = viewModel,
            onOpenMessage = onOpenMessage,
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }

    } // secret watermark Box
    } // unlocked branch
}
