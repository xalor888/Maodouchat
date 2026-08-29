@file:Suppress("DEPRECATION")

package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePicker
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.R
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.local.entity.AiOperationState
import com.maodouchat.data.local.entity.AiOperationType
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.network.AiGroupTask
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.InlineTypingDots
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.ui.component.secretPageBlindWatermark
import com.maodouchat.security.MessageSafetyScanner
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate
import com.maodouchat.security.findActivity
import com.maodouchat.ui.component.FloatingGlassTopBar
import com.maodouchat.ui.component.MessageBubble
import com.maodouchat.ui.theme.LocalLiquidGlassBackdrop
import com.maodouchat.ui.theme.LocalLiquidGlassEnabled
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.maodouchat.ui.component.ReplyPreview
import com.maodouchat.ui.component.ReplyTargetBar
import com.maodouchat.ui.component.ParticleDeleteEffect
import com.maodouchat.ui.component.ParticleState
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens
import com.maodouchat.ui.theme.bannerEnter
import com.maodouchat.ui.theme.composerBarEnter
import com.maodouchat.ui.theme.MotionPolicy
import com.maodouchat.ui.theme.rememberMotionPulse
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.Divider
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.OnlineGreen
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.PrimaryFixed
import com.maodouchat.ui.theme.Secondary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.util.DisappearingMessagePolicy
import com.maodouchat.util.QrCodeGenerator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
@Composable
internal fun AiOperationStatusBar(
    operations: List<AiOperationUi>,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    val operation = operations.firstOrNull() ?: return
    val isFailed = operation.state == AiOperationState.FAILED
    val context = LocalContext.current
    val title = when (operation.type) {
        AiOperationType.TRANSCRIBE_VOICE -> stringResource(R.string.chat_ai_operation_transcribe)
        AiOperationType.TRANSLATE_MESSAGE -> stringResource(R.string.chat_ai_operation_translate)
        AiOperationType.SUMMARIZE_MESSAGES -> stringResource(R.string.chat_ai_operation_summary)
        AiOperationType.ANALYZE_IMAGE -> stringResource(R.string.chat_ai_operation_image)
        AiOperationType.ANALYZE_FILE -> stringResource(R.string.chat_ai_operation_file)
        else -> stringResource(R.string.chat_ai_consent_title)
    }
    val errorBase = com.maodouchat.ai.AiCostVisibilityPolicy.baseErrorCode(operation.lastErrorCode)
    val waitSeconds = operation.retryAfterSeconds
        ?: com.maodouchat.ai.AiCostVisibilityPolicy.waitSecondsFor(operation.lastErrorCode)
    val status = when {
        errorBase == AiOperationError.INTERRUPTED ->
            stringResource(R.string.chat_ai_operation_outcome_unknown)
        errorBase == AiOperationError.OUTCOME_UNKNOWN ||
            errorBase == AiOperationError.TIMEOUT ||
            errorBase == AiOperationError.UNKNOWN ->
            stringResource(R.string.chat_ai_operation_outcome_unknown)
        errorBase == AiOperationError.RATE_LIMITED ->
            stringResource(
                R.string.chat_ai_operation_rate_limited,
                waitSeconds.coerceAtLeast(1L)
            )
        errorBase == AiOperationError.QUOTA_EXCEEDED ->
            stringResource(R.string.chat_ai_operation_quota_exceeded)
        errorBase == AiOperationError.CONNECTION_NOT_ESTABLISHED &&
            operation.nextRetryAtMs != null -> stringResource(
                R.string.chat_ai_operation_retry_scheduled,
                android.text.format.DateFormat.getTimeFormat(context).format(Date(operation.nextRetryAtMs))
            )
        errorBase == AiOperationError.CONTEXT_MISSING ->
            stringResource(R.string.chat_ai_operation_context_missing)
        errorBase in setOf(
            AiOperationError.NETWORK,
            AiOperationError.CONNECTION_NOT_ESTABLISHED
        ) ->
            stringResource(R.string.chat_ai_operation_network_failed)
        errorBase == AiOperationError.SERVER ->
            stringResource(R.string.chat_ai_operation_server_failed)
        errorBase in setOf(AiOperationError.EMPTY_RESULT, AiOperationError.INVALID_RESPONSE) ->
            stringResource(R.string.chat_ai_operation_invalid_result)
        operation.state == AiOperationState.QUEUED -> stringResource(R.string.chat_ai_operation_queued)
        operation.state == AiOperationState.RUNNING ->
            stringResource(R.string.chat_ai_operation_running_billing)
        else -> stringResource(R.string.chat_ai_operation_failed)
    }
    val showRetryBillHint = isFailed &&
        com.maodouchat.ai.AiCostVisibilityPolicy.shouldWarnRetryBills(operation.lastErrorCode)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryFixed.copy(alpha = 0.42f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isFailed) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(status, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            if (showRetryBillHint) {
                Text(
                    stringResource(R.string.chat_ai_operation_retry_may_bill),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
            if (operation.attempts > 0) {
                Text(
                    stringResource(R.string.chat_ai_operation_attempts, operation.attempts),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
            if (operations.size > 1) {
                Text(
                    stringResource(R.string.chat_ai_operation_more, operations.size - 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        }
        if (isFailed) {
            IconButton(onClick = { onRetry(operation.id) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.chat_ai_operation_retry),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        IconButton(
            onClick = {
                if (isFailed) onDismiss(operation.id) else onCancel(operation.id)
            },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(
                    if (isFailed) R.string.chat_ai_operation_dismiss else R.string.chat_ai_operation_cancel
                ),
                tint = LocalChatPalette.current.textSecondary
            )
        }
    }
}

@Composable
internal fun AiDraftStreamBar(
    preview: String,
    isStreaming: Boolean,
    errorCode: String?,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val isCancelled = errorCode == "CANCELLED"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.chat_ai_draft_preview),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isStreaming) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (isStreaming || errorCode != null) {
                Text(
                    if (isStreaming) stringResource(R.string.chat_ai_stream_generating)
                    else aiStreamStatusText(errorCode),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (errorCode != null && !isCancelled) UnreadRed else TextSecondary
                )
            }
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        when {
            isStreaming -> {
                IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.chat_ai_stream_cancel), tint = LocalChatPalette.current.textSecondary)
                }
            }
            errorCode == null -> {
                IconButton(onClick = onApply, enabled = preview.isNotBlank(), modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Check, stringResource(R.string.chat_ai_stream_apply), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDiscard, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.chat_ai_stream_discard), tint = LocalChatPalette.current.textSecondary)
                }
            }
            else -> {
                if (isCancelled && preview.isNotBlank()) {
                    IconButton(onClick = onApply, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Check, stringResource(R.string.chat_ai_stream_apply), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = onRetry, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.chat_ai_stream_retry), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDiscard, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.chat_ai_stream_discard), tint = LocalChatPalette.current.textSecondary)
                }
            }
        }
    }
}

@Composable
internal fun aiStreamStatusText(errorCode: String?): String {
    val base = com.maodouchat.ai.AiCostVisibilityPolicy.baseErrorCode(errorCode)
    val wait = com.maodouchat.ai.AiCostVisibilityPolicy.waitSecondsFor(errorCode).coerceAtLeast(1L)
    return when (base) {
        "CANCELLED" -> stringResource(R.string.chat_ai_stream_cancelled)
        AiOperationError.RATE_LIMITED -> stringResource(R.string.chat_ai_stream_rate_limited, wait)
        AiOperationError.QUOTA_EXCEEDED -> stringResource(R.string.chat_ai_stream_quota_exceeded)
        AiOperationError.NETWORK -> stringResource(R.string.chat_ai_operation_network_failed)
        AiOperationError.TIMEOUT, AiOperationError.OUTCOME_UNKNOWN, AiOperationError.UNKNOWN ->
            stringResource(R.string.chat_ai_operation_outcome_unknown)
        AiOperationError.SERVER -> stringResource(R.string.chat_ai_operation_server_failed)
        AiOperationError.EMPTY_RESULT, AiOperationError.INVALID_RESPONSE ->
            stringResource(R.string.chat_ai_operation_invalid_result)
        else -> stringResource(R.string.chat_ai_operation_failed)
    }
}

internal fun openFile(context: android.content.Context, contentUri: String) {
    runCatching {
        val parsed = android.net.Uri.parse(contentUri)
        val uri = if (parsed.scheme == "file") {
            val file = java.io.File(requireNotNull(parsed.path))
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else parsed
        val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(parsed.toString()).lowercase()
        val mime = context.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            android.widget.Toast.makeText(context, context.getString(R.string.chat_no_file_app), android.widget.Toast.LENGTH_SHORT).show()
        }
    }.onFailure { android.widget.Toast.makeText(context, context.getString(R.string.chat_open_file_failed), android.widget.Toast.LENGTH_SHORT).show() }
}

internal fun requestVoiceCallPermission(
    context: Context,
    launchPermissionRequest: (String) -> Unit,
    contactId: String,
    contactName: String,
    onVoiceCall: (String, String) -> Unit
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
        onVoiceCall(contactId, contactName)
    } else {
        launchPermissionRequest(Manifest.permission.RECORD_AUDIO)
    }
}

/** 单聊联系人资料卡：头像、ID、状态、最后在线、常用操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactProfileSheet(
    contact: com.maodouchat.data.model.User,
    isBlocked: Boolean,
    isBlocking: Boolean,
    hideCalls: Boolean = false,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onToggleBlock: () -> Unit,
    onReport: () -> Unit
) {
    val context = LocalContext.current
    var showAvatarFull by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showAvatarFull && !contact.avatar.isNullOrBlank()) {
                // 头像大图：全屏缩放查看，点按关闭
                Dialog(onDismissRequest = { showAvatarFull = false }) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.92f))
                            .clickable { showAvatarFull = false }
                    ) {
                        com.maodouchat.ui.component.ZoomableAsyncImage(
                            model = contact.avatar,
                            contentDescription = contact.displayName,
                            modifier = Modifier.fillMaxSize(),
                            onSingleTap = { showAvatarFull = false }
                        )
                    }
                }
            }
            com.maodouchat.ui.component.Avatar(
                name = contact.displayName,
                avatarUrl = contact.avatar,
                size = com.maodouchat.ui.component.AvatarSize.LG,
                isOnline = contact.isOnline,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = !contact.avatar.isNullOrBlank()) { showAvatarFull = true }
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                contact.displayName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                contact.id,
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (contact.status.isNotBlank()) {
                Text(
                    contact.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                when {
                    contact.isOnline -> stringResource(R.string.chat_online)
                    contact.lastSeen > 0 -> stringResource(R.string.user_last_seen_prefix) + " " +
                        android.text.format.DateUtils.getRelativeTimeSpanString(
                            contact.lastSeen,
                            System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS
                        )
                    else -> stringResource(R.string.chat_offline)
                },
                style = MaterialTheme.typography.labelMedium,
                color = LocalChatPalette.current.textSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ProfileAction(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.chat_send), onClick = onMessage)
                if (!hideCalls) {
                    ProfileAction(Icons.Outlined.Call, stringResource(R.string.chat_voice_call), onClick = onVoiceCall)
                    ProfileAction(Icons.Outlined.Videocam, stringResource(R.string.chat_video_call), onClick = onVideoCall)
                }
                ProfileAction(
                    if (isBlocked) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                    stringResource(if (isBlocked) R.string.chat_unblock_user else R.string.chat_block_user),
                    enabled = !isBlocking,
                    onClick = onToggleBlock
                )
                ProfileAction(Icons.Outlined.Warning, stringResource(R.string.chat_report_user), onClick = onReport)
            }
        }
    }
}

@Composable
internal fun ProfileAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Primary else TextHint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) OnSurface else TextHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun requestVideoCallPermissions(
    context: Context,
    launchPermissionRequest: (Array<String>) -> Unit,
    contactId: String,
    contactName: String,
    onVideoCall: (String, String) -> Unit
) {
    val missingPermissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    if (missingPermissions.isEmpty()) {
        onVideoCall(contactId, contactName)
    } else {
        launchPermissionRequest(missingPermissions.toTypedArray())
    }
}

@Composable
internal fun GroupEncryptionWarningBanner(warning: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(warning, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun GifSearchDialog(
    onPickUri: (Uri, String?) -> Unit,
    onBrowseFiles: () -> Unit,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<com.maodouchat.util.LocalGifItem>>(emptyList()) }
    val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(granted) {
        if (!granted) {
            loading = false
            items = emptyList()
            return@LaunchedEffect
        }
        loading = true
        items = withContext(Dispatchers.IO) {
            com.maodouchat.util.GifLibrary.queryLocalGifs(context)
        }
        loading = false
    }

    val recentIds = remember { com.maodouchat.util.GifSearchPreferences.getRecentIds(context) }
    val filtered = remember(items, query, recentIds) {
        com.maodouchat.util.GifSearchPolicy.filterAndSort(items, query, recentIds)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gif_search_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.gif_search_local_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (!granted) {
                    Text(
                        text = stringResource(R.string.gif_search_permission),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onRequestPermission) {
                        Text(stringResource(R.string.gif_search_grant))
                    }
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.gif_search_hint)) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Outline
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        loading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.gif_search_loading),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalChatPalette.current.textSecondary
                                )
                            }
                        }
                        filtered.isEmpty() -> {
                            Text(
                                text = stringResource(
                                    if (query.isBlank()) R.string.gif_search_empty
                                    else R.string.gif_search_empty_query
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalChatPalette.current.textSecondary
                            )
                        }
                        else -> {
                            Text(
                                text = pluralStringResource(R.plurals.gif_search_count, filtered.size, filtered.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textHint,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                filtered.chunked(3).forEach { row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        row.forEach { item ->
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(88.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                                    .clickable {
                                                        onPickUri(Uri.parse(item.uriString), item.id)
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                coil.compose.AsyncImage(
                                                    model = item.uriString,
                                                    contentDescription = item.displayName,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                                )
                                            }
                                        }
                                        repeat(3 - row.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onBrowseFiles, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.gif_search_browse), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}

@Composable
internal fun ScheduledMessagesBanner(
    items: List<com.maodouchat.util.ScheduledMessage>,
    onCancel: (String) -> Unit,
    onReschedule: (String) -> Unit = {},
    onViewAll: () -> Unit = {}
) {
    val context = LocalContext.current
    val preview = items.take(3)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
            .clickable(onClick = onViewAll)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.schedule_pending_title, items.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            if (items.size > 3) {
                Text(
                    text = stringResource(R.string.schedule_view_all),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        preview.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatScheduleTime(item.sendAtMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                    // 1.15：重复定时消息显示重复标识（1.21：含次数上限）
                                scheduleRepeatLabel(context, item.repeatIntervalMs, item.repeatCount, item.occurrencesSent, item.weekdaysOnly)?.let { repeatLabel ->
                        Text(
                            text = repeatLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                TextButton(onClick = { onReschedule(item.id) }) {
                    Text(stringResource(R.string.schedule_reschedule), color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { onCancel(item.id) }) {
                    Text(stringResource(R.string.schedule_cancel_one), color = LocalChatPalette.current.unreadRed)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledMessagesListSheet(
    items: List<com.maodouchat.util.ScheduledMessage>,
    onCancel: (String) -> Unit,
    onReschedule: (String) -> Unit,
    onSendNow: (String) -> Unit,
    onCancelAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 1.174：全部取消确认
    var showCancelAllConfirm by rememberSaveable { mutableStateOf(false) }
    val filteredItems = remember(items, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            items
        } else {
            items.filter { it.text.contains(query, ignoreCase = true) }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.schedule_pending_title, items.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            // 1.174：全部取消
            if (items.isNotEmpty()) {
                TextButton(onClick = { showCancelAllConfirm = true }) {
                    Text(stringResource(R.string.schedule_cancel_all), color = LocalChatPalette.current.unreadRed)
                }
            }
            if (items.size >= 4) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(120) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.schedule_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }
            if (filteredItems.isEmpty()) {
                Text(
                    text = stringResource(R.string.schedule_search_empty),
                    color = LocalChatPalette.current.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    filteredItems.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatScheduleTime(item.sendAtMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalChatPalette.current.textSecondary
                                )
                                // 1.15：重复定时消息显示重复标识（1.21：含次数上限）
                    scheduleRepeatLabel(context, item.repeatIntervalMs, item.repeatCount, item.occurrencesSent, item.weekdaysOnly)?.let { repeatLabel ->
                                    Text(
                                        text = repeatLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            TextButton(onClick = { onReschedule(item.id) }) {
                                Text(stringResource(R.string.schedule_reschedule), color = MaterialTheme.colorScheme.primary)
                            }
                            // 1.168：立即发送
                            TextButton(onClick = { onSendNow(item.id) }) {
                                Text(stringResource(R.string.schedule_send_now), color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { onCancel(item.id) }) {
                                Text(stringResource(R.string.schedule_cancel_one), color = LocalChatPalette.current.unreadRed)
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_close))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // 1.174：全部取消确认
    if (showCancelAllConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelAllConfirm = false },
            title = { Text(stringResource(R.string.schedule_cancel_all)) },
            text = { Text(stringResource(R.string.schedule_cancel_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showCancelAllConfirm = false
                    onCancelAll()
                }) { Text(stringResource(R.string.chat_clear_history_yes), color = LocalChatPalette.current.unreadRed) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAllConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
internal fun ScheduleSendDialog(
    onPickDelay: (Long) -> Unit,
    onPickAt: (Long) -> Unit = {},
    onDismiss: () -> Unit,
    titleRes: Int = R.string.schedule_title,
    // 1.07：重复定时（间隔 + 1.21 可选次数 + 1.62 工作日）
    onPickRepeat: (Long, Int, Boolean) -> Unit = { _, _, _ -> },
    /** 1.43：重排时允许编辑文案（onTextEdited 非空时显示文本输入框）。 */
    initialText: String = "",
    onTextEdited: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    // 1.21：重复次数选择（0=不限）
    var repeatCountChoice by remember { mutableIntStateOf(0) }
    // 1.43：重排文案草稿
    var textDraft by remember(initialText) { mutableStateOf(initialText) }
    val options = com.maodouchat.util.ScheduledMessagePolicy.QUICK_DELAYS_MS.zip(
        listOf(
            R.string.schedule_delay_1m,
            R.string.schedule_delay_5m,
            R.string.schedule_delay_15m,
            R.string.schedule_delay_30m,
            R.string.schedule_delay_1h,
            R.string.schedule_delay_2h,
            R.string.schedule_delay_3h,
            R.string.schedule_delay_4h,
            R.string.schedule_delay_6h,
            R.string.schedule_delay_12h,
            R.string.schedule_delay_24h,
            R.string.schedule_delay_2d,
            R.string.schedule_delay_3d,
            R.string.schedule_delay_7d
        )
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 1.43：重排时编辑文案
                if (onTextEdited != null) {
                    OutlinedTextField(
                        value = textDraft,
                        onValueChange = {
                            textDraft = it.take(com.maodouchat.util.ScheduledMessagePolicy.MAX_TEXT_LENGTH)
                            onTextEdited(textDraft)
                        },
                        placeholder = { Text(stringResource(R.string.schedule_edit_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                options.forEach { (delay, label) ->
                    TextButton(
                        onClick = { onPickDelay(delay) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(label), color = MaterialTheme.colorScheme.primary)
                    }
                }
                TextButton(
                    onClick = {
                        openScheduleDateTimePicker(
                            context = context,
                            onPicked = onPickAt,
                            onTooSoon = {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.schedule_custom_too_soon),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onTooLate = {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.schedule_custom_too_late),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.schedule_custom), color = MaterialTheme.colorScheme.primary)
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.textHint.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    stringResource(R.string.schedule_repeat_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
                // 1.07：重复定时（每日/每周）；1.21：应用所选次数；1.62：工作日重复
                TextButton(
                    onClick = { onPickRepeat(24L * 3600_000L, repeatCountChoice, false) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.schedule_repeat_daily), color = MaterialTheme.colorScheme.primary) }
                TextButton(
                    onClick = { onPickRepeat(7L * 24 * 3600_000L, repeatCountChoice, false) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.schedule_repeat_weekly), color = MaterialTheme.colorScheme.primary) }
                TextButton(
                    onClick = { onPickRepeat(24L * 3600_000L, repeatCountChoice, true) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.schedule_repeat_weekdays), color = MaterialTheme.colorScheme.primary) }
                Text(
                    stringResource(R.string.schedule_repeat_count_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(3, 7, 30, 0).forEach { count ->
                        TextButton(
                            onClick = { repeatCountChoice = count },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (count > 0) context.resources.getQuantityString(R.plurals.schedule_repeat_count, count, count)
                                else context.getString(R.string.schedule_repeat_count_unlimited),
                                color = if (repeatCountChoice == count) Primary else OnSurface,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 消息「稍后提醒」时间选择：复用定时发送档位文案，窗口 1 分钟 ~ 30 天。 */
@Composable
internal fun MessageReminderTimeDialog(
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_reminder_menu), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            val delays = com.maodouchat.util.MessageReminderPolicy.QUICK_DELAYS_MS
            val labels = listOf(
                R.string.schedule_delay_1m, R.string.schedule_delay_5m, R.string.schedule_delay_15m,
                R.string.schedule_delay_30m, R.string.schedule_delay_1h, R.string.schedule_delay_2h,
                R.string.schedule_delay_3h, R.string.schedule_delay_4h, R.string.schedule_delay_6h,
                R.string.schedule_delay_12h, R.string.schedule_delay_24h, R.string.schedule_delay_2d,
                R.string.schedule_delay_3d, R.string.schedule_delay_7d
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                delays.zip(labels).forEach { (delayMs, labelRes) ->
                    TextButton(
                        onClick = { onPick(delayMs) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(labelRes), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * System date → time picker; enforces [ScheduledMessagePolicy] min/max window before [onPicked].
 */
internal fun openScheduleDateTimePicker(
    context: Context,
    onPicked: (Long) -> Unit,
    onTooSoon: () -> Unit,
    onTooLate: () -> Unit
) {
    val now = java.util.Calendar.getInstance()
    val minCal = java.util.Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis + com.maodouchat.util.ScheduledMessagePolicy.MIN_DELAY_MS
    }
    val maxCal = java.util.Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis + com.maodouchat.util.ScheduledMessagePolicy.MAX_DELAY_MS
    }
    android.app.DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            android.app.TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    val picked = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.YEAR, year)
                        set(java.util.Calendar.MONTH, month)
                        set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)
                        set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
                        set(java.util.Calendar.MINUTE, minute)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    when {
                        picked < minCal.timeInMillis -> onTooSoon()
                        picked > maxCal.timeInMillis -> onTooLate()
                        else -> onPicked(picked)
                    }
                },
                minCal.get(java.util.Calendar.HOUR_OF_DAY),
                minCal.get(java.util.Calendar.MINUTE),
                true
            ).show()
        },
        minCal.get(java.util.Calendar.YEAR),
        minCal.get(java.util.Calendar.MONTH),
        minCal.get(java.util.Calendar.DAY_OF_MONTH)
    ).apply {
        datePicker.minDate = minCal.timeInMillis
        datePicker.maxDate = maxCal.timeInMillis
    }.show()
}

 private fun formatScheduleTime(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(millis))
}

/** 1.15：定时消息重复间隔 → 展示文案（0=一次性返回 null 不显示）。1.21/1.48：含次数与剩余次数。1.62：工作日重复。 */
internal fun scheduleRepeatLabel(context: android.content.Context, intervalMs: Long, repeatCount: Int, occurrencesSent: Int, weekdaysOnly: Boolean): String? {
    val base = when {
        weekdaysOnly -> context.getString(R.string.schedule_repeat_weekdays)
        intervalMs == 24L * 3600_000L -> context.getString(R.string.schedule_repeat_daily)
        intervalMs == 7L * 24 * 3600_000L -> context.getString(R.string.schedule_repeat_weekly)
        else -> if (intervalMs <= 0L) return null else context.getString(R.string.schedule_repeat_badge)
    }
    return if (repeatCount > 0) {
        val remaining = (repeatCount - occurrencesSent).coerceAtLeast(0)
        "$base · ${context.resources.getQuantityString(R.plurals.schedule_repeat_remaining, remaining, remaining)}"
    } else base
}

/** 8.48：禁言剩余时长（分钟/小时/天）本地化显示。 */
internal fun formatMuteRemaining(context: android.content.Context, remainingMs: Long): String {
    val minutes = remainingMs / 60_000L
    return when {
        minutes <= 0L -> context.getString(R.string.time_just_now)
        minutes < 60L -> context.getString(R.string.chat_mute_minutes, minutes)
        minutes < 24 * 60L -> context.getString(R.string.chat_mute_hours, minutes / 60L)
        else -> context.getString(R.string.chat_mute_days, minutes / (24 * 60L))
    }
}

@Composable
internal fun SecretChatBanner(
    onManage: () -> Unit = {},
    sealedSenderReady: Boolean = false,
    sealedSenderExpiresInSec: Long = 0L,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.secret_chat_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.secret_chat_banner_limit),
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.secret_chat_safety_hint),
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    Icons.Outlined.Security,
                    contentDescription = null,
                    tint = if (sealedSenderReady) Primary else TextSecondary,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = if (sealedSenderReady) {
                        stringResource(
                            R.string.secret_chat_sealed_chip,
                            (sealedSenderExpiresInSec / 3600L).coerceAtLeast(0L)
                        )
                    } else {
                        stringResource(R.string.secret_chat_sealed_chip_pending)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sealedSenderReady) Primary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}



@Composable
internal fun LiveLocationSharingBanner(
    untilMs: Long?,
    onStop: () -> Unit
) {
    var remaining by remember(untilMs) {
        mutableLongStateOf(com.maodouchat.util.LiveLocationPolicy.remainingFromUntil(untilMs))
    }
    LaunchedEffect(untilMs) {
        while (true) {
            remaining = com.maodouchat.util.LiveLocationPolicy.remainingFromUntil(untilMs)
            if (remaining <= 0L) break
            kotlinx.coroutines.delay(1000L)
        }
    }
    val label = com.maodouchat.util.LiveLocationPolicy.formatRemaining(remaining)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.NearMe,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.live_location_sharing_banner, label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.live_location_stop),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onStop)
        )
    }
}

@Composable
internal fun DisappearingMessagesBanner(
    seconds: Int,
    onChange: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .clickable(onClick = onChange)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.disappear_banner_on, disappearSecondsLabel(seconds)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.disappear_banner_change),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
internal fun ChatQuietHoursDialog(
    current: com.maodouchat.notification.ChatQuietHoursStore.QuietWindow,
    onPick: (com.maodouchat.notification.ChatQuietHoursStore.QuietWindow) -> Unit,
    onDismiss: () -> Unit
) {
    // 快捷时段：预置的常用静音窗口（start, end, labelRes）
    val presets = listOf(
        Triple(22 * 60, 7 * 60, R.string.chat_quiet_hours_night),
        Triple(12 * 60, 14 * 60, R.string.chat_quiet_hours_lunch),
        Triple(23 * 60, 8 * 60, R.string.chat_quiet_hours_sleep),
        Triple(9 * 60, 18 * 60, R.string.chat_quiet_hours_workday)
    )
    val enabled = current.enabled
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_quiet_hours_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        if (enabled) R.string.chat_quiet_hours_active_summary
                        else R.string.chat_quiet_hours_inactive_summary
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                presets.forEach { (start, end, labelRes) ->
                    val selected = enabled && current.startMinute == start && current.endMinute == end
                    TextButton(
                        onClick = {
                            onPick(
                                com.maodouchat.notification.ChatQuietHoursStore.QuietWindow(
                                    enabled = true,
                                    startMinute = start,
                                    endMinute = end
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                    ) {
                        Text(stringResource(labelRes), color = if (selected) Primary else OnSurface, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (enabled) {
                    TextButton(
                        onClick = {
                            onPick(com.maodouchat.notification.ChatQuietHoursStore.QuietWindow.OFF)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.chat_quiet_hours_clear), color = LocalChatPalette.current.unreadRed, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text(
                    stringResource(R.string.chat_quiet_hours_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        }
    )
}

@Composable
internal fun DisappearingMessagesDialog(
    selectedSeconds: Int,
    isUpdating: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = DisappearingMessagePolicy.ALLOWED_SECONDS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disappear_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.disappear_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                Text(
                    text = stringResource(R.string.disappear_limit_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
                Spacer(modifier = Modifier.height(4.dp))
                options.forEach { seconds ->
                    val selected = seconds == selectedSeconds
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isUpdating) { onSelect(seconds) }
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (seconds == 0) Icons.Outlined.Schedule else Icons.Outlined.VisibilityOff,
                            contentDescription = null,
                            tint = if (selected) Primary else TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = disappearSecondsLabel(seconds),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) Primary else OnSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (isUpdating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
internal fun disappearSecondsLabel(seconds: Int): String = when (seconds) {
    0 -> stringResource(R.string.disappear_off)
    30 -> stringResource(R.string.disappear_30s)
    60 -> stringResource(R.string.disappear_1m)
    2 * 60 -> stringResource(R.string.disappear_2m)
    5 * 60 -> stringResource(R.string.disappear_5m)
    15 * 60 -> stringResource(R.string.disappear_15m)
    60 * 60 -> stringResource(R.string.disappear_1h)
    2 * 60 * 60 -> stringResource(R.string.disappear_2h)
    4 * 60 * 60 -> stringResource(R.string.disappear_4h)
    8 * 60 * 60 -> stringResource(R.string.disappear_8h)
    12 * 60 * 60 -> stringResource(R.string.disappear_12h)
    24 * 60 * 60 -> stringResource(R.string.disappear_24h)
    7 * 24 * 60 * 60 -> stringResource(R.string.disappear_7d)
    30 * 24 * 60 * 60 -> stringResource(R.string.disappear_30d)
    else -> stringResource(R.string.disappear_off)
}

@Composable
internal fun PinnedMessagesBanner(
    pins: List<com.maodouchat.network.PinnedMessageDto>,
    messages: List<Message>,
    canManage: Boolean,
    onOpen: (String) -> Unit,
    onUnpin: (String) -> Unit,
    // 1.49：显示置顶者（解析 userId → 显示名）
    resolvePinnerName: (String) -> String = { it },
    // 1.53：点击置顶者名称 → 打开其资料
    onPinnerClick: ((String) -> Unit)? = null
) {
    if (pins.isEmpty()) return
    var pinIndex by remember(pins.map { it.messageId }.joinToString()) { mutableIntStateOf(0) }
    val safeIndex = pinIndex.coerceIn(0, pins.lastIndex)
    val current = pins[safeIndex]
    val message = messages.firstOrNull { it.id == current.messageId }
    val preview = pinnedPreviewText(message)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .clickable {
                onOpen(current.messageId)
                if (pins.size > 1) {
                    pinIndex = (safeIndex + 1) % pins.size
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.PushPin,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (pins.size > 1) {
                    stringResource(R.string.chat_pinned_banner_title) +
                        " · ${safeIndex + 1}/${pins.size}"
                } else {
                    stringResource(R.string.chat_pinned_banner_title)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 1.49：置顶者（本地解析显示名，服务端不可见时回退 userId）；1.56：附带置顶时间；1.58：pinnedAt<=0 回退无时间格式
            if (current.pinnedBy.isNotBlank()) {
                Text(
                    text = if (current.pinnedAt > 0) {
                        stringResource(
                            R.string.chat_pinned_by_time,
                            resolvePinnerName(current.pinnedBy),
                            android.text.format.DateUtils.getRelativeTimeSpanString(
                                current.pinnedAt,
                                System.currentTimeMillis(),
                                android.text.format.DateUtils.MINUTE_IN_MILLIS
                            ).toString()
                        )
                    } else {
                        stringResource(R.string.chat_pinned_by, resolvePinnerName(current.pinnedBy))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 1.53：点击置顶者名称打开其资料
                    modifier = if (onPinnerClick != null) {
                        Modifier.clickable { onPinnerClick(current.pinnedBy) }
                    } else {
                        Modifier
                    }
                )
            }
        }
        if (canManage) {
            TextButton(onClick = { onUnpin(current.messageId) }) {
                Text(stringResource(R.string.chat_message_unpin), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun GroupAnnouncementBanner(
    announcement: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.group_announcement_banner_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = announcement,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.common_close),
                tint = LocalChatPalette.current.textSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
internal fun pinnedPreviewText(message: Message?): String {
    if (message == null) return stringResource(R.string.chat_pinned_preview_generic)
    return when (MessagePinPolicy.previewKind(message.type)) {
        MessagePinPolicy.PreviewKind.TEXT -> {
            val text = MessagePinPolicy.textPreview(message.content)
            if (text.isBlank()) stringResource(R.string.chat_pinned_preview_generic) else text
        }
        MessagePinPolicy.PreviewKind.IMAGE -> stringResource(R.string.chat_pinned_preview_image)
        MessagePinPolicy.PreviewKind.VOICE -> stringResource(R.string.chat_pinned_preview_voice)
        MessagePinPolicy.PreviewKind.VIDEO -> stringResource(R.string.chat_pinned_preview_video)
        MessagePinPolicy.PreviewKind.FILE -> stringResource(R.string.chat_pinned_preview_file)
        MessagePinPolicy.PreviewKind.LOCATION -> stringResource(R.string.chat_pinned_preview_location)
        MessagePinPolicy.PreviewKind.STICKER -> stringResource(R.string.chat_pinned_preview_sticker)
        MessagePinPolicy.PreviewKind.GENERIC -> stringResource(R.string.chat_pinned_preview_generic)
    }
}

/**
 * Identity / safety warning strip above the timeline.
 * CHANGED (sticky) uses stronger fill so it cannot read as a soft tip.
 */
@Composable
internal fun SecurityWarningBanner(
    warning: String,
    sticky: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(UnreadRed.copy(alpha = if (sticky) 0.16f else 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Outlined.Security, contentDescription = null, tint = LocalChatPalette.current.unreadRed, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            warning,
            color = LocalChatPalette.current.unreadRed,
            style = if (sticky) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun ReactionPickerRow(onPick: (String) -> Unit) {
    val reactions = remember {
        listOf(
            "\uD83D\uDC4D", // 👍
            "\uD83D\uDC4E", // 👎
            "\u2764\uFE0F", // ❤️
            "\uD83D\uDE02", // 😂
            "\uD83D\uDE0D", // 😍
            "\uD83D\uDE2E", // 😮
            "\uD83D\uDE22", // 😢
            "\uD83D\uDE21", // 😠
            "\uD83D\uDD25", // 🔥
            "\uD83C\uDF89", // 🎉
            "\uD83D\uDC4F", // 👏
            "\uD83D\uDE4F", // 🙏
            "\uD83D\uDC40", // 👀
            "\uD83E\uDD14", // 🤔
            "\uD83D\uDCAF", // 💯
            "\u2705",      // ✅
            "\uD83D\uDE80", // 🚀
            "\u2B50",      // ⭐
            "\uD83C\uDF1F", // 🌟
            "\uD83E\uDD73", // 🥳
            "\uD83E\uDD70", // 🥰
            "\uD83D\uDCAA", // 💪
            "\uD83E\uDD1D", // 🤝
            "\uD83D\uDE0A", // 😊
            "\uD83D\uDE4C", // 🙌
            "\uD83E\uDD29", // 🤩
            "\uD83E\uDD72", // 🥲
            "\uD83E\uDD23", // 🤣
            "\uD83D\uDC4C", // 👌
            "\uD83E\uDEF6"  // 🫶
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp)
    ) {
        reactions.forEach { emoji ->
            TextButton(
                onClick = { onPick(emoji) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(emoji, fontSize = 20.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
internal fun UnreadSummaryBanner(
    summary: String?,
    messageCount: Int,
    isLoading: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    // 1.194：复制未读摘要
    onCopy: () -> Unit = {}
) {
    val pulse by rememberMotionPulse(
        initialValue = 0.6f,
        targetValue = 1f,
        durationMillis = 1_200,
        label = "unreadSummaryPulse"
    )
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    colors = listOf(
                        Primary.copy(alpha = 0.10f),
                        Primary.copy(alpha = 0.04f)
                    )
                )
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer { alpha = pulse }
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape)
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = if (messageCount > 0) pluralStringResource(R.plurals.chat_unread_summary_count, messageCount, messageCount) else stringResource(R.string.chat_unread_summary),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = summary ?: stringResource(R.string.chat_generating),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (summary != null) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.chat_view)) }
            // 1.194：复制未读摘要
            IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.chat_copy), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_close_unread_summary), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
internal fun SafetyCodeDialog(
    contactName: String,
    contactId: String,
    trustState: SignalProtocol.IdentityTrustState,
    isGroup: Boolean,
    safetyCode: String?,
    warning: String?,
    currentUserId: String,
    currentDeviceId: Int,
    currentIdentityFingerprint: String,
    contactIdentityFingerprint: String?,
    deviceSafetyWarning: String?,
    isLoadingDeviceSafety: Boolean,
    deviceSafetyStates: List<SignalProtocol.DeviceSafetyState>,
    onDismiss: () -> Unit,
    onVerifyDevice: (Int) -> Unit
) {
    val context = LocalContext.current
    val sticky = com.maodouchat.crypto.SafetyCodePolicy.isStickyIdentityWarning(trustState)
    val displayCode = com.maodouchat.crypto.SafetyCodePolicy.formatForDisplay(safetyCode)
    AlertDialog(
        // CHANGED identity: back/outside dismiss still allowed, but primary path forces verify dialog content.
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_safety_title, contactName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.unreadRed) }
                if (sticky) {
                    Text(
                        stringResource(R.string.chat_safety_changed_sticky_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.unreadRed
                    )
                }
                deviceSafetyWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.unreadRed) }
                if (isLoadingDeviceSafety) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.chat_safety_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }

                val devices = deviceSafetyStates
                var deviceSearch by remember { mutableStateOf("") }
                val filteredDevices = remember(devices, deviceSearch) {
                    val q = deviceSearch.trim()
                    if (q.isEmpty()) {
                        devices
                    } else {
                        devices.filter { device ->
                            device.deviceId.toString().contains(q, ignoreCase = true) ||
                                device.trustState.name.contains(q, ignoreCase = true) ||
                                device.safetyCode.orEmpty().contains(q, ignoreCase = true) ||
                                device.identityKey.orEmpty().contains(q, ignoreCase = true)
                        }
                    }
                }
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.chat_safety_status, if (isGroup) stringResource(R.string.chat_group_sender_key_enabled) else trustState.toLabel()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        displayCode ?: stringResource(R.string.chat_safety_not_ready),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    displayCode?.let { code ->
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        context.getString(R.string.chat_safety_code),
                                        com.maodouchat.crypto.SafetyCodePolicy.formatForCopy(code).orEmpty()
                                    )
                                )
                                Toast.makeText(context, context.getString(R.string.chat_safety_code_copied), Toast.LENGTH_SHORT).show()
                            }
                        ) { Text(stringResource(R.string.chat_safety_copy_code)) }
                        if (!contactIdentityFingerprint.isNullOrBlank() && currentIdentityFingerprint.isNotBlank()) {
                            SafetyQrCard(
                                ownerUserId = currentUserId,
                                ownerDeviceId = currentDeviceId,
                                peerUserId = contactId,
                                peerDeviceId = 1,
                                ownerIdentityFingerprint = currentIdentityFingerprint,
                                peerIdentityFingerprint = contactIdentityFingerprint
                            )
                        }
                    }
                } else {
                    if (devices.size >= 4) {
                        OutlinedTextField(
                            value = deviceSearch,
                            onValueChange = { deviceSearch = it.take(64) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.chat_safety_devices_search_hint)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Search, contentDescription = null, tint = Secondary)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = Outline,
                                focusedTextColor = OnSurface,
                                unfocusedTextColor = OnSurface,
                                cursorColor = Primary
                            )
                        )
                    }
                    if (filteredDevices.isEmpty()) {
                        Text(
                            stringResource(R.string.chat_safety_devices_search_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textHint
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            filteredDevices.forEach { device ->
                                DeviceSafetyRow(
                                    device = device,
                                    ownerUserId = currentUserId,
                                    ownerDeviceId = currentDeviceId,
                                    ownerIdentityFingerprint = currentIdentityFingerprint,
                                    peerUserId = contactId,
                                    onVerify = { onVerifyDevice(device.deviceId) }
                                )
                            }
                        }
                    }
                }
                Text(stringResource(R.string.chat_safety_scan_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Text(
                    stringResource(R.string.chat_safety_format_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        if (sticky) R.string.chat_safety_review_later else R.string.common_done
                    )
                )
            }
        },
        dismissButton = {
            if (!sticky) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_later)) }
            }
        }
    )
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
internal fun DeviceSafetyRow(
    device: SignalProtocol.DeviceSafetyState,
    ownerUserId: String,
    ownerDeviceId: Int,
    ownerIdentityFingerprint: String,
    peerUserId: String,
    onVerify: () -> Unit
) {
    val context = LocalContext.current
    val displayCode = com.maodouchat.crypto.SafetyCodePolicy.formatForDisplay(device.safetyCode)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (device.isCurrent) R.string.chat_device_current else R.string.chat_device_number, device.deviceId),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(device.trustState.toLabel(), style = MaterialTheme.typography.labelMedium, color = if (device.trustState == SignalProtocol.IdentityTrustState.CHANGED) UnreadRed else Primary)
        }
        Text(
            displayCode ?: stringResource(R.string.chat_device_session_missing),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (displayCode == null) {
            Text(stringResource(R.string.chat_identity_fingerprint, device.identityKey.take(16)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        } else {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            context.getString(R.string.chat_safety_code),
                            com.maodouchat.crypto.SafetyCodePolicy.formatForCopy(displayCode).orEmpty()
                        )
                    )
                    Toast.makeText(context, context.getString(R.string.chat_safety_code_copied), Toast.LENGTH_SHORT).show()
                }
            ) { Text(stringResource(R.string.chat_safety_copy_code)) }
            SafetyQrCard(
                ownerUserId = ownerUserId,
                ownerDeviceId = ownerDeviceId,
                peerUserId = peerUserId,
                peerDeviceId = device.deviceId,
                ownerIdentityFingerprint = ownerIdentityFingerprint,
                peerIdentityFingerprint = device.identityFingerprint
            )
        }
        Button(onClick = onVerify, enabled = displayCode != null && device.trustState != SignalProtocol.IdentityTrustState.VERIFIED) {
            Text(stringResource(if (device.trustState == SignalProtocol.IdentityTrustState.VERIFIED) R.string.chat_verified else R.string.chat_mark_verified))
        }
    }
}

@Composable
internal fun SafetyQrCard(
    ownerUserId: String,
    ownerDeviceId: Int,
    peerUserId: String,
    peerDeviceId: Int,
    ownerIdentityFingerprint: String,
    peerIdentityFingerprint: String
) {
    val bitmap = remember(ownerUserId, ownerDeviceId, peerUserId, peerDeviceId, ownerIdentityFingerprint, peerIdentityFingerprint) {
        QrCodeGenerator.generateBitmap(
            QrCodeGenerator.encodeSafetyQrPayload(
                ownerUserId = ownerUserId,
                ownerDeviceId = ownerDeviceId,
                peerUserId = peerUserId,
                peerDeviceId = peerDeviceId,
                ownerIdentityFingerprint = ownerIdentityFingerprint,
                peerIdentityFingerprint = peerIdentityFingerprint
            ),
            320
        )
    }
    if (bitmap != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp)) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.chat_identity_qr),
                    modifier = Modifier.size(96.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(stringResource(R.string.chat_scan_device_hint, peerDeviceId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
internal fun SignalProtocol.IdentityTrustState.toLabel(): String = stringResource(when (this) {
    SignalProtocol.IdentityTrustState.UNKNOWN -> R.string.chat_trust_unknown
    SignalProtocol.IdentityTrustState.TRUSTED -> R.string.chat_trust_first
    SignalProtocol.IdentityTrustState.VERIFIED -> R.string.chat_verified
    SignalProtocol.IdentityTrustState.CHANGED -> R.string.chat_trust_changed
})

@Composable
internal fun AiImageAnalysisModeDialog(
    onSelect: (AiImageAnalysisMode) -> Unit,
    onDismiss: () -> Unit
) {
    val modes = AiImageAnalysisMode.entries
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_image_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                modes.forEach { mode ->
                    val icon = when (mode) {
                        AiImageAnalysisMode.DESCRIBE -> Icons.Outlined.Image
                        AiImageAnalysisMode.OCR -> Icons.Outlined.Search
                        AiImageAnalysisMode.SAFETY -> Icons.Outlined.Security
                    }
                    val description = stringResource(when (mode) {
                        AiImageAnalysisMode.DESCRIBE -> R.string.chat_ai_image_mode_describe_description
                        AiImageAnalysisMode.OCR -> R.string.chat_ai_image_mode_ocr_description
                        AiImageAnalysisMode.SAFETY -> R.string.chat_ai_image_mode_safety_description
                    })
                    TextButton(
                        onClick = { onSelect(mode) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.localizedLabel(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                description,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textHint,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(
                    stringResource(R.string.chat_ai_image_privacy),
                    modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
internal fun AiImageAnalysisResultDialog(
    result: String,
    mode: AiImageAnalysisMode,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_ai_image_result_title))
                }
                Text(mode.localizedLabel(), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(
                    stringResource(R.string.chat_ai_image_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
        dismissButton = { TextButton(onClick = onCopy) { Text(stringResource(R.string.chat_copy)) } }
    )
}

@Composable
internal fun AiFileAnalysisModeDialog(
    fileName: String,
    onSelect: (AiFileAnalysisMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_file_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (fileName.isNotBlank()) {
                    Text(fileName, style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                }
                AiFileAnalysisMode.entries.forEach { mode ->
                    val icon = if (mode == AiFileAnalysisMode.SUMMARIZE) Icons.Outlined.Description else Icons.AutoMirrored.Outlined.HelpOutline
                    val description = stringResource(if (mode == AiFileAnalysisMode.SUMMARIZE) R.string.chat_ai_file_mode_summarize_description else R.string.chat_ai_file_mode_question_description)
                    TextButton(
                        onClick = { onSelect(mode) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.localizedLabel(), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface)
                            Text(description, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(
                    stringResource(R.string.chat_ai_file_privacy),
                    modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
internal fun AiFileQuestionDialog(
    fileName: String,
    question: String,
    onQuestionChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_ai_file_question_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fileName.isNotBlank()) {
                    Text(fileName, style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextField(
                    value = question,
                    onValueChange = onQuestionChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    placeholder = { Text(stringResource(R.string.chat_ai_file_question_placeholder)) },
                    supportingText = { Text("${question.length}/500") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = question.isNotBlank()) {
                Text(stringResource(R.string.chat_submit))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
internal fun AiFileAnalysisResultDialog(
    result: String,
    fileName: String,
    mode: AiFileAnalysisMode,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_ai_file_result_title))
                }
                Text(
                    listOf(fileName.takeIf(String::isNotBlank), mode.localizedLabel()).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(stringResource(R.string.chat_ai_file_disclaimer), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
        dismissButton = { TextButton(onClick = onCopy) { Text(stringResource(R.string.chat_copy)) } }
    )
}

@Composable
internal fun AiSummaryScopeDialog(
    searchResultCount: Int,
    onDismiss: () -> Unit,
    onSelect: (AiSummaryScope, String) -> Unit
) {
    var selectedStyle by rememberSaveable { mutableStateOf("brief") }
    val scopes = listOf(
        AiSummaryScope.RECENT,
        AiSummaryScope.TODAY,
        AiSummaryScope.SEVEN_DAYS,
        AiSummaryScope.THIRTY_DAYS,
        AiSummaryScope.SEARCH_RESULTS
    )
    val styleOptions = listOf(
        "brief" to R.string.chat_ai_summary_style_brief,
        "detailed" to R.string.chat_ai_summary_style_detailed,
        "decisions" to R.string.chat_ai_summary_style_decisions,
        "tasks" to R.string.chat_ai_summary_style_tasks,
        "timeline" to R.string.chat_ai_summary_style_timeline,
        "risks" to R.string.chat_ai_summary_style_risks
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_summary_scope_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.chat_ai_summary_style_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    styleOptions.forEach { (styleId, labelRes) ->
                        FilterChip(
                            selected = selectedStyle == styleId,
                            onClick = { selectedStyle = styleId },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }
                scopes.forEach { scope ->
                    val enabled = scope != AiSummaryScope.SEARCH_RESULTS || searchResultCount > 0
                    val icon = when (scope) {
                        AiSummaryScope.RECENT -> Icons.Outlined.History
                        AiSummaryScope.TODAY -> Icons.Outlined.Today
                        AiSummaryScope.SEVEN_DAYS -> Icons.Outlined.DateRange
                        AiSummaryScope.THIRTY_DAYS -> Icons.Outlined.DateRange
                        AiSummaryScope.SEARCH_RESULTS -> Icons.Outlined.Search
                        AiSummaryScope.UNREAD -> Icons.Outlined.History
                    }
                    val description = when (scope) {
                        AiSummaryScope.RECENT -> stringResource(R.string.chat_ai_summary_scope_recent_description)
                        AiSummaryScope.TODAY -> stringResource(R.string.chat_ai_summary_scope_today_description)
                        AiSummaryScope.SEVEN_DAYS -> stringResource(R.string.chat_ai_summary_scope_week_description)
                        AiSummaryScope.THIRTY_DAYS -> stringResource(R.string.chat_ai_summary_scope_month_description)
                        AiSummaryScope.SEARCH_RESULTS -> if (searchResultCount > 0) {
                            stringResource(R.string.chat_ai_summary_scope_search_count, searchResultCount)
                        } else {
                            stringResource(R.string.chat_ai_summary_scope_search_empty)
                        }
                        AiSummaryScope.UNREAD -> ""
                    }
                    TextButton(
                        enabled = enabled,
                        onClick = { onSelect(scope, selectedStyle) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (enabled) Primary else TextHint,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                scope.localizedLabel(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (enabled) OnSurface else TextHint
                            )
                            Text(
                                description,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textHint,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Text(
                    stringResource(R.string.chat_ai_summary_scope_privacy),
                    modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
internal fun AiSummaryHistoryDialog(
    summaries: List<AiSummaryHistoryUi>,
    isLoading: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var historySearch by rememberSaveable { mutableStateOf("") }
    val filteredSummaries = remember(summaries, historySearch) {
        val q = historySearch.trim()
        if (q.isEmpty()) {
            summaries
        } else {
            summaries.filter { item ->
                item.summary.contains(q, ignoreCase = true) ||
                    item.scope.name.contains(q, ignoreCase = true) ||
                    item.cacheKey.contains(q, ignoreCase = true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_summary_history_title))
            }
        },
        text = {
            when {
                isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                }
                summaries.isEmpty() -> Text(
                    stringResource(R.string.chat_ai_summary_history_empty),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textHint,
                    textAlign = TextAlign.Center
                )
                else -> Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (summaries.size >= 4) {
                        OutlinedTextField(
                            value = historySearch,
                            onValueChange = { historySearch = it.take(120) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.chat_ai_summary_history_search_hint)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Search, contentDescription = null, tint = Secondary)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = Outline,
                                focusedTextColor = OnSurface,
                                unfocusedTextColor = OnSurface,
                                cursorColor = Primary
                            )
                        )
                    }
                    if (filteredSummaries.isEmpty()) {
                        Text(
                            stringResource(R.string.chat_ai_summary_history_search_empty),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalChatPalette.current.textHint,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        filteredSummaries.forEachIndexed { index, item ->
                            TextButton(
                                onClick = { onSelect(item.cacheKey) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            item.scope.localizedLabel(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            dateFormat.format(Date(item.createdAt)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalChatPalette.current.textHint
                                        )
                                    }
                                    Text(
                                        pluralStringResource(R.plurals.chat_ai_summary_messages, item.messageCount, item.messageCount),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalChatPalette.current.textSecondary
                                    )
                                    Text(
                                        item.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (index != filteredSummaries.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        }
    )
}

@Composable
internal fun AiConsentDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_ai_consent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.chat_ai_consent_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.chat_ai_consent_privacy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.chat_ai_accept)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
internal fun AiSummaryDialog(
    summary: String,
    scope: AiSummaryScope,
    messageCount: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_ai_summary_title))
                }
                if (messageCount > 0) {
                    Text(
                        "${scope.localizedLabel()} · ${pluralStringResource(R.plurals.chat_ai_summary_messages, messageCount, messageCount)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.chat_ai_summary_disclaimer), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        }
    )
}

// B4：会话画像对话框（本地统计 + 可选叙事摘要）
@Composable
internal fun ConversationProfileDialog(
    loading: Boolean,
    profile: com.maodouchat.ai.AiConversationProfile.ConversationProfile?,
    failed: Boolean,
    onDismiss: () -> Unit,
    // 1.317：复制会话画像
    onCopyProfile: (String) -> Unit = {}
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_conversation_profile_title))
            }
        },
        text = {
            when {
                loading -> Text(stringResource(R.string.chat_ai_conversation_profile_loading), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                failed -> Text(stringResource(R.string.chat_ai_conversation_profile_failed), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.unreadRed)
                profile == null -> Text(stringResource(R.string.chat_ai_profile_no_data), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                else -> Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(
                            R.string.chat_ai_conversation_profile_stats,
                            profile.local.messageCount,
                            profile.local.activeDays
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalChatPalette.current.textSecondary
                    )
                    Text(
                        stringResource(
                            R.string.chat_ai_profile_time_distribution,
                            profile.local.morning, profile.local.afternoon,
                            profile.local.evening, profile.local.night
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalChatPalette.current.textSecondary
                    )
                    if (profile.local.topTerms.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.chat_ai_profile_top_terms,
                                profile.local.topTerms.take(8).joinToString(" · ")
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    Text(
                        profile.narrative?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_ai_profile_no_narrative),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        // 1.317：复制会话画像（转发/归档）
        dismissButton = {
            if (profile != null && !failed && !loading) {
                TextButton(onClick = { onCopyProfile(profileText(context, profile)) }) {
                    Text(stringResource(R.string.chat_copy), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}

/** 1.317：将会话画像序列化为纯文本（复制用）。 */
internal fun profileText(context: android.content.Context, profile: com.maodouchat.ai.AiConversationProfile.ConversationProfile): String = buildString {
    val ctx = context
    append(
        ctx.getString(
            R.string.chat_ai_conversation_profile_stats,
            profile.local.messageCount,
            profile.local.activeDays
        )
    )
    append("\n")
    append(
        ctx.getString(
            R.string.chat_ai_profile_time_distribution,
            profile.local.morning, profile.local.afternoon,
            profile.local.evening, profile.local.night
        )
    )
    if (profile.local.topTerms.isNotEmpty()) {
        append("\n")
        append(
            ctx.getString(
                R.string.chat_ai_profile_top_terms,
                profile.local.topTerms.take(8).joinToString(" · ")
            )
        )
    }
    profile.narrative?.takeIf { it.isNotBlank() }?.let {
        append("\n\n").append(it)
    }
}

// B4：本周周报对话框（生成并缓存到本地）
@Composable
internal fun WeeklyReportDialog(
    loading: Boolean,
    report: com.maodouchat.ai.AiWeeklyReport.WeeklyReport?,
    failed: Boolean,
    onDismiss: () -> Unit,
    // 1.310：复制周报全文
    onCopyReport: (String) -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_ai_weekly_report_title))
            }
        },
        text = {
            when {
                loading -> Text(stringResource(R.string.chat_ai_weekly_report_loading), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                failed -> Text(stringResource(R.string.chat_ai_weekly_report_failed), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.unreadRed)
                report == null -> Text(stringResource(R.string.chat_ai_weekly_report_failed), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.unreadRed)
                else -> Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(report.report, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        stringResource(R.string.chat_ai_summary_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        // 1.310：复制周报（转发/归档）
        dismissButton = {
            if (report != null && !failed && !loading) {
                TextButton(onClick = { onCopyReport(report.report) }) {
                    Text(stringResource(R.string.chat_copy), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}

/** 8.47：消息分类统计对话框（纯本地词典分类，结果仅本机）。 */
@Composable
internal fun MessageClassifyDialog(
    loading: Boolean,
    categories: List<com.maodouchat.data.repository.AiProfileRepository.CategoryCount>,
    failed: Boolean,
    onDismiss: () -> Unit,
    // 1.349：复制分类结果
    onCopyClassify: (String) -> Unit = {}
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.ai_enhance_classify_title))
            }
        },
        text = {
            when {
                loading -> Text(stringResource(R.string.ai_enhance_classify_hint), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                failed -> Text(stringResource(R.string.ai_enhance_classify_failed), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.unreadRed)
                categories.isEmpty() -> Text(stringResource(R.string.ai_enhance_classify_empty), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                else -> Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.ai_enhance_classify_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                    categories.forEach { row ->
                        val label = when (row.category) {
                            "notice" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_notice)
                            "todo" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_todo)
                            "finance" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_finance)
                            "study" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_study)
                            "tech" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_tech)
                            "social" -> stringResource(com.maodouchat.R.string.ai_enhance_classify_social)
                            else -> stringResource(com.maodouchat.R.string.ai_enhance_classify_other)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                stringResource(R.string.ai_enhance_classify_count, row.count),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalChatPalette.current.textSecondary
                            )
                        }
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (row.count.toFloat() / (categories.maxOfOrNull { it.count }?.takeIf { it > 0 } ?: 1).toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        stringResource(R.string.ai_enhance_classify_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        // 1.349：复制分类结果（dismissButton 位，仅分类成功时显示）
        dismissButton = {
            if (categories.isNotEmpty() && !failed && !loading) {
                val classifyCopyText = classifyText(context, categories)
                TextButton(onClick = { onCopyClassify(classifyCopyText) }) {
                    Text(stringResource(R.string.chat_copy), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}

/** 1.349：将消息分类结果序列化为纯文本（复制用）。 */
@Composable
internal fun classifyText(context: android.content.Context, categories: List<com.maodouchat.data.repository.AiProfileRepository.CategoryCount>): String = buildString {
    val ctx = context
    categories.forEach { row ->
        val label = when (row.category) {
            "notice" -> ctx.getString(R.string.ai_enhance_classify_notice)
            "todo" -> ctx.getString(R.string.ai_enhance_classify_todo)
            "finance" -> ctx.getString(R.string.ai_enhance_classify_finance)
            "study" -> ctx.getString(R.string.ai_enhance_classify_study)
            "tech" -> ctx.getString(R.string.ai_enhance_classify_tech)
            "social" -> ctx.getString(R.string.ai_enhance_classify_social)
            else -> ctx.getString(R.string.ai_enhance_classify_other)
        }
        append(label).append(": ").append(ctx.getString(R.string.ai_enhance_classify_count, row.count)).append('\n')
    }
    append(ctx.getString(R.string.ai_enhance_classify_disclaimer))
}

@Composable
internal fun GroupAiAssistantDialog(
    question: String,
    answer: String,
    tasks: List<AiGroupTask>,
    isSavingTasks: Boolean,
    tasksSaved: Boolean,
    taskSaveError: String?,
    shareEnabled: Boolean = true,
    onCopy: () -> Unit,
    onSaveTasks: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    val motion = LocalMotionSettings.current
    // 私有预览模式指示
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_group_ai_title))
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.chat_group_ai_private_preview),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.chat_group_ai_question, question), style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary)
                // 回答带渐入
                AnimatedContent(
                    targetState = answer,
                    transitionSpec = {
                        (fadeIn(tween(motion.duration(MotionTokens.Emphasized))) +
                            expandVertically(tween(motion.duration(MotionTokens.Emphasized))))
                            .togetherWith(
                                fadeOut(tween(motion.duration(MotionTokens.Standard))) +
                                    shrinkVertically(tween(motion.duration(MotionTokens.Standard)))
                            )
                    },
                    label = "groupAiAnswer"
                ) { animatedAnswer ->
                    Text(animatedAnswer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                if (tasks.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
                    Text(
                        pluralStringResource(R.plurals.ai_tasks_preview_count, tasks.size, tasks.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    tasks.forEachIndexed { index, task ->
                        val animateInitialEntry = MotionPolicy.shouldAnimateInitialListEntry(index, motion)
                        var visible by remember(task.title, animateInitialEntry) { mutableStateOf(!animateInitialEntry) }
                        LaunchedEffect(task.title, animateInitialEntry) {
                            if (animateInitialEntry) kotlinx.coroutines.delay(
                                MotionPolicy.initialListEntryDelay(index, motion).toLong()
                            )
                            visible = true
                        }
                        AnimatedVisibility(
                            visible = visible,
                            enter = if (animateInitialEntry) {
                                fadeIn(tween(motion.duration(MotionTokens.Emphasized))) +
                                    expandVertically(tween(motion.duration(MotionTokens.Emphasized)))
                            } else {
                                androidx.compose.animation.EnterTransition.None
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Outlined.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(task.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    task.owner?.takeIf(String::isNotBlank)?.let { owner ->
                                        Text(
                                            stringResource(R.string.ai_tasks_owner, owner),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalChatPalette.current.textSecondary
                                        )
                                    }
                                    val due = task.dueText?.takeIf(String::isNotBlank)
                                        ?: task.dueAt?.let {
                                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
                                        }
                                    due?.let {
                                        Text(
                                            stringResource(R.string.ai_tasks_due, it),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalChatPalette.current.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    taskSaveError?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = onSaveTasks,
                        enabled = !isSavingTasks && !tasksSaved,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        when {
                            isSavingTasks -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            tasksSaved -> {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.ai_tasks_saved))
                            }
                            else -> Text(stringResource(R.string.ai_tasks_save))
                        }
                    }
                    Text(
                        stringResource(R.string.ai_tasks_local_private),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
                Text(stringResource(R.string.chat_group_ai_private_result), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.chat_group_ai_disclaimer), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
                TextButton(onClick = onCopy) { Text(stringResource(R.string.chat_group_ai_copy)) }
            }
        },
        confirmButton = {
            Button(onClick = onShare, enabled = shareEnabled) {
                Text(stringResource(R.string.chat_group_ai_share))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}

/**
 * 录音指示器：脉冲点 + 实时时长 + 振幅波形。
 */
@Composable
internal fun RecordingIndicator(
    elapsedMs: Long = 0L,
    waveform: List<Float> = emptyList(),
    amplitude: Float = 0f,
) {
    val alpha by rememberMotionPulse(
        initialValue = 0.3f,
        targetValue = 1.0f,
        durationMillis = 800,
        label = "recordingPulse"
    )
    val durationLabel = com.maodouchat.util.VoiceRecorder.formatDuration(elapsedMs)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UnreadRed.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).graphicsLayer { this.alpha = alpha }.background(UnreadRed, CircleShape))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.chat_recording), color = LocalChatPalette.current.unreadRed, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.weight(1f))
            Text(durationLabel, color = LocalChatPalette.current.unreadRed, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.height(8.dp))
        RecordingWaveformRow(waveform = waveform, liveAmplitude = amplitude)
    }
}

@Composable
internal fun RecordingWaveformRow(
    waveform: List<Float>,
    liveAmplitude: Float,
    barCount: Int = 32,
) {
    val bars = remember(waveform, liveAmplitude, barCount) {
        val base = if (waveform.isEmpty()) {
            List(barCount) { 0.08f }
        } else {
            val src = waveform
            List(barCount) { i ->
                val idx = ((i.toFloat() / (barCount - 1).coerceAtLeast(1)) * (src.size - 1).coerceAtLeast(0)).toInt()
                    .coerceIn(0, src.lastIndex.coerceAtLeast(0))
                src.getOrElse(idx) { 0f }.coerceIn(0f, 1f)
            }
        }
        // 末尾条跟瞬时振幅，增强“正在说话”感
        base.mapIndexed { i, v ->
            if (i >= barCount - 3) maxOf(v, liveAmplitude * (0.7f + 0.1f * (i - (barCount - 3)))) else v
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        bars.forEach { h ->
            val heightFrac = (0.12f + h * 0.88f).coerceIn(0.12f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height((36 * heightFrac).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(UnreadRed.copy(alpha = 0.35f + h * 0.55f))
            )
        }
    }
}

@Composable
internal fun VoicePreviewBar(
    durationMs: Long,
    onPlay: () -> Unit,
    onDiscard: () -> Unit,
    onSend: () -> Unit,
) {
    val playerState by com.maodouchat.util.VoicePlayer.state.collectAsState()
    val isPreviewPlaying =
        playerState.messageId == com.maodouchat.ui.screen.chatdetail.ChatDetailViewModel.VOICE_PREVIEW_MESSAGE_ID &&
            playerState.isPlaying
    val durationLabel = com.maodouchat.util.VoiceRecorder.formatDuration(durationMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onDiscard, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.chat_voice_preview_discard),
                tint = LocalChatPalette.current.textHint
            )
        }
        IconButton(
            onClick = {
                if (isPreviewPlaying) {
                    com.maodouchat.util.VoicePlayer.stop()
                } else {
                    onPlay()
                }
            },
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        ) {
            Icon(
                imageVector = if (isPreviewPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPreviewPlaying) R.string.chat_voice_preview_stop else R.string.chat_voice_preview_play
                ),
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.chat_voice_preview_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(durationLabel, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textSecondary)
            if (isPreviewPlaying) {
                LinearProgressIndicator(
                    progress = { playerState.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Primary.copy(alpha = 0.15f),
                )
            }
        }
        TextButton(onClick = onSend) {
            Text(stringResource(R.string.chat_voice_preview_send), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
internal fun ComposerPane(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onScheduleSend: () -> Unit = {},
    onSendImage: () -> Unit,
    onSendViewOnceImage: () -> Unit = {},
    onSendSpoilerImage: () -> Unit = {},
    onPasteFromClipboard: () -> Unit = {},
    onSendVideo: () -> Unit,
    onSendFile: () -> Unit,
    onSendGif: () -> Unit,
    onSendSticker: (String) -> Unit,
    onSendLocation: () -> Unit,
    onSendLiveLocation: () -> Unit = {},
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onRecordCancel: () -> Unit,
    isRecording: Boolean,
    isAiWorking: Boolean,
    isAiDraftStreaming: Boolean,
    aiSuggestions: List<String>,
    isAiReplyStreaming: Boolean,
    aiReplyStreamErrorCode: String?,
    onAiRewrite: (mode: String, targetLanguage: String?) -> Unit,
    onAiSuggestReplies: (tone: String) -> Unit,
    onAiSummarize: () -> Unit,
    onOpenAiSummaryHistory: () -> Unit,
    onOpenAiTasks: () -> Unit,
    onOpenConversationProfile: () -> Unit = {},
    onOpenWeeklyReport: () -> Unit = {},
    onEmotionReply: () -> Unit = {},
    onOpenMessageClassify: () -> Unit = {},
    onAiSuggestionClick: (String) -> Unit,
    onClearAiSuggestions: () -> Unit,
    onCancelAiReplyStream: () -> Unit,
    onRetryAiReplyStream: () -> Unit,
    aiEnabled: Boolean,
    isUpdatingAiSetting: Boolean,
    onAiEnabledChange: (Boolean) -> Unit,
    isGroupChat: Boolean,
    isChannelChat: Boolean = false,
    onSendNudge: () -> Unit = {},
    mentionParticipants: List<com.maodouchat.data.model.User> = emptyList(),
    currentUserId: String = "",
    /** 1.37：仅群主/管理员可选「@所有人」候选。 */
    canMentionEveryone: Boolean = true,
    attachmentsEnabled: Boolean = true,
    disabledAttachmentMessage: String? = null,
    silentSend: Boolean = false,
    onToggleSilentSend: () -> Unit = {},
    isSending: Boolean = false,
    /** 只读模式（广播频道订阅者）：隐藏输入区，显示单向广播提示。 */
    readOnly: Boolean = false,
    readOnlyMessage: String? = null,
    /** 密聊会话：禁用会话画像/周报等本地 AI 聚合（结果不应落可搜索缓存）。 */
    isSecretChat: Boolean = false,
    /** 1.11：发送名片——转发目标列表与回调（ChatInputBar 无 viewModel 引用）。 */
    contactCardTargets: List<com.maodouchat.data.model.Chat> = emptyList(),
    onLoadForwardTargets: () -> Unit = {},
    onSendContactCard: (userId: String, displayName: String) -> Unit = { _, _ -> },
    botCommands: List<com.maodouchat.bot.BotCommandPolicy.BotCommandItem> = emptyList(),
    composerState: ComposerState = rememberComposerState(),
) {
    var showAttachMenu by composerState.attachMenu
    var showExpressionPanel by composerState.expressionPanel
    var expressionMode by composerState.expressionMode
    var showAiMenu by composerState.aiMenu
    var showDraftTranslationLanguages by composerState.translationLanguages
    var showQuickPhrases by composerState.quickPhrases
    var showContactCardPicker by composerState.contactCardPicker
    val context = LocalContext.current
    val attachmentDisabledText = disabledAttachmentMessage ?: stringResource(R.string.chat_attachment_unsupported)
    val mentionQuery = remember(value, isGroupChat) {
        if (RuntimeFlags.isEnabled(context, RuntimeFlags.MENTIONS) &&
            MentionPolicy.shouldShowPicker(value, isGroupChat)
        ) {
            MentionPolicy.activeQuery(value)
        } else null
    }
    val mentionCandidates = remember(mentionQuery, mentionParticipants, currentUserId) {
        val q = mentionQuery ?: return@remember emptyList()
        MentionPolicy.filterCandidates(
            participants = mentionParticipants,
            currentUserId = currentUserId,
            filter = q.filter,
            includeEveryone = canMentionEveryone,
        )
    }
    val everyoneLabel = stringResource(R.string.chat_mention_everyone)
    BackHandler(enabled = showAttachMenu || showExpressionPanel || showAiMenu || showQuickPhrases) {
        composerState.dismissTopPanel()
    }

    if (showDraftTranslationLanguages) {
        TranslationLanguageDialog(
            onDismiss = { showDraftTranslationLanguages = false },
            onSelect = { language ->
                showDraftTranslationLanguages = false
                onAiRewrite("translate", language)
            }
        )
    }

    // 9.4xx：外层 Column 已 imePadding()，这里再去 navigationBarsPadding 会在键盘弹出时叠出
    // 双份空隙（ime inset 已含导航栏高度）；改为仅保留背景色，insets 由外层统一处理
    // ChatInputBar 画在 layerBackdrop 采样层内部。对同一层 drawBackdrop 会让
    // RenderThread prepareTree 无限递归（SIGSEGV stack overflow，打开任意会话即崩）。
    // 输入栏只用不透明表面；顶栏仍可安全采样，因为它在 Scaffold.topBar 外层。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (readOnly) {
            // 广播频道订阅者：单向只读，仅显示提示条
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                Icon(Icons.Outlined.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    readOnlyMessage ?: stringResource(R.string.chat_channel_read_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
            return@Column
        }
        AnimatedVisibility(
            visible = (isAiWorking && !isAiDraftStreaming) || aiSuggestions.isNotEmpty() || aiReplyStreamErrorCode != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                when {
                    isAiReplyStreaming -> {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        if (aiSuggestions.isEmpty()) {
                            Text(
                                stringResource(R.string.chat_ai_working),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Row(
                                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                aiSuggestions.forEach { suggestion ->
                                    TextButton(
                                        onClick = { onAiSuggestionClick(suggestion) },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                                    ) {
                                        Text(suggestion, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        IconButton(onClick = onCancelAiReplyStream, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.chat_ai_stream_cancel), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
                        }
                    }
                    isAiWorking -> {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_ai_working), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                    aiSuggestions.isNotEmpty() -> {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Row(
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            aiSuggestions.forEach { suggestion ->
                                TextButton(
                                    onClick = { onAiSuggestionClick(suggestion) },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                                ) {
                                    Text(suggestion, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (aiReplyStreamErrorCode != null) {
                            IconButton(onClick = onRetryAiReplyStream, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Refresh, stringResource(R.string.chat_ai_stream_retry), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = onClearAiSuggestions, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_clear_ai_suggestions), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
                        }
                    }
                    else -> {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = LocalChatPalette.current.unreadRed, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            aiStreamStatusText(aiReplyStreamErrorCode),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalChatPalette.current.unreadRed,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onRetryAiReplyStream, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Refresh, stringResource(R.string.chat_ai_stream_retry), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onClearAiSuggestions, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.chat_clear_ai_suggestions), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // 附件菜单（带动画）
        AnimatedVisibility(
            visible = showExpressionPanel,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            ExpressionPanel(
                mode = expressionMode,
                onModeChange = { expressionMode = it },
                onEmojiClick = { onValueChange(value + it) },
                onStickerClick = onSendSticker,
                onGifClick = {
                    if (attachmentsEnabled) {
                        onSendGif()
                        showExpressionPanel = false
                    } else {
                        Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show()
                    }
                },
                stickersEnabled = attachmentsEnabled
            )
        }

        AnimatedVisibility(
            visible = showAttachMenu,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val attachKinds = AttachMenuPolicy.items(
                isGroup = isGroupChat,
                isChannel = isChannelChat,
                viewOnceEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.VIEW_ONCE),
                contactCardEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.CONTACT_CARD),
                nudgeEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.NUDGE),
                aiEnabled = com.maodouchat.ai.AiEntryPolicy.isComposerEntryActive(context, aiEnabled),
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                attachKinds.forEach { kind ->
                    if ((isGroupChat || isChannelChat) &&
                        (kind == AttachMenuKind.VIEW_ONCE || kind == AttachMenuKind.LIVE_LOCATION || kind == AttachMenuKind.NUDGE)
                    ) {
                        return@forEach
                    }
                    when (kind) {
                        AttachMenuKind.IMAGE -> AttachMenuItem(
                            icon = Icons.Outlined.Image,
                            label = stringResource(R.string.chat_attachment_image),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendImage() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.VIEW_ONCE -> AttachMenuItem(
                            icon = Icons.Outlined.VisibilityOff,
                            label = stringResource(R.string.chat_view_once_send),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendViewOnceImage() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.SPOILER -> AttachMenuItem(
                            icon = Icons.Outlined.Apps,
                            label = stringResource(R.string.chat_spoiler_media_send),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendSpoilerImage() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.PASTE -> AttachMenuItem(
                            icon = Icons.Outlined.ContentCopy,
                            label = stringResource(R.string.chat_attachment_paste),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onPasteFromClipboard() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.VIDEO -> AttachMenuItem(
                            icon = Icons.Outlined.CameraAlt,
                            label = stringResource(R.string.chat_attachment_video),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendVideo() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.FILE -> AttachMenuItem(
                            icon = Icons.Outlined.AttachFile,
                            label = stringResource(R.string.chat_attachment_file),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendFile() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.VOICE -> AttachMenuItem(
                            icon = Icons.Outlined.Mic,
                            label = stringResource(R.string.chat_attachment_voice),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onRecordStart() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.LOCATION -> AttachMenuItem(
                            icon = Icons.Outlined.LocationOn,
                            label = stringResource(R.string.chat_attachment_location),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendLocation() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.LIVE_LOCATION -> AttachMenuItem(
                            icon = Icons.Outlined.NearMe,
                            label = stringResource(R.string.chat_live_location_send),
                            enabled = attachmentsEnabled,
                            onClick = { showAttachMenu = false; onSendLiveLocation() },
                            onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                        )
                        AttachMenuKind.SCHEDULE -> AttachMenuItem(
                            icon = Icons.Outlined.Schedule,
                            label = stringResource(R.string.schedule_send),
                            enabled = attachmentsEnabled && value.isNotBlank(),
                            onClick = { showAttachMenu = false; onScheduleSend() },
                            onDisabledClick = {
                                Toast.makeText(
                                    context,
                                    if (value.isBlank()) context.getString(R.string.schedule_need_text) else attachmentDisabledText,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                        AttachMenuKind.QUICK_PHRASES -> AttachMenuItem(
                            icon = Icons.Outlined.SentimentSatisfied,
                            label = stringResource(R.string.chat_quick_phrases),
                            enabled = true,
                            onClick = composerState::openQuickPhrases
                        )
                        AttachMenuKind.CONTACT_CARD -> AttachMenuItem(
                            icon = Icons.Outlined.ContactPage,
                            label = stringResource(R.string.chat_send_contact_card),
                            enabled = true,
                            onClick = {
                                onLoadForwardTargets()
                                composerState.openContactCardPicker()
                            }
                        )
                        AttachMenuKind.AI -> AttachMenuItem(
                            icon = Icons.Outlined.AutoAwesome,
                            label = stringResource(R.string.chat_ai_assistant),
                            enabled = !isAiWorking && !isUpdatingAiSetting,
                            onClick = composerState::showAiMenu
                        )
                        AttachMenuKind.SILENT -> AttachMenuItem(
                            icon = if (silentSend) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                            label = stringResource(
                                if (silentSend) R.string.chat_silent_send_on else R.string.chat_silent_send_off
                            ),
                            enabled = true,
                            onClick = { onToggleSilentSend() }
                        )
                        AttachMenuKind.NUDGE -> AttachMenuItem(
                            icon = Icons.Outlined.TouchApp,
                            label = stringResource(R.string.chat_nudge),
                            enabled = true,
                            onClick = { showAttachMenu = false; onSendNudge() }
                        )
                    }
                }
            }
        }

        if (showQuickPhrases) {
            QuickPhrasesDialog(
                onDismiss = { showQuickPhrases = false },
                onPick = { phrase ->
                    showQuickPhrases = false
                    val trimmed = phrase.trim()
                    if (trimmed.isNotEmpty()) {
                        onValueChange(if (value.isBlank()) trimmed else value + trimmed)
                    }
                }
            )
        }

        // 1.11：发送名片——联系人选择对话框（单聊会话对端用户）
        if (showContactCardPicker) {
            val pickerContacts = remember(contactCardTargets, currentUserId) {
                contactCardTargets
                    .filter { !it.isGroup }
                    .mapNotNull { chat ->
                        val other = chat.participants.firstOrNull { it.id != currentUserId }
                        if (other == null) null else chat to other
                    }
                    .sortedBy { it.second.displayName.lowercase() }
            }
            ContactCardPickerDialog(
                contacts = pickerContacts,
                onDismiss = { showContactCardPicker = false },
                onPick = { _, user ->
                    showContactCardPicker = false
                    onSendContactCard(user.id, user.displayName)
                }
            )
        }

        // 输入区
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
            AnimatedVisibility(
                visible = mentionCandidates.isNotEmpty(),
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(140))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.chat_mention_picker_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        mentionCandidates.forEach { candidate ->
                            val label = if (candidate.isEveryone) everyoneLabel else candidate.displayName
                            TextButton(
                                onClick = {
                                    val q = mentionQuery ?: return@TextButton
                                    val insertLabel = if (candidate.isEveryone) everyoneLabel else candidate.displayName
                                    val result = MentionPolicy.insertMention(
                                        text = value,
                                        cursor = value.length,
                                        displayName = insertLabel,
                                        query = q,
                                    )
                                    onValueChange(result.text)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (candidate.isEveryone) "@$everyoneLabel" else "@$label",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (mentionCandidates.size >= 8) {
                        Text(
                            stringResource(R.string.chat_mention_picker_count, mentionCandidates.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textHint,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            val slashCandidates = remember(value, botCommands) {
                com.maodouchat.bot.BotCommandPolicy.filterCommands(botCommands, value)
            }
            AnimatedVisibility(
                visible = slashCandidates.isNotEmpty(),
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(140))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.chat_bot_slash_picker_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    val multiBot = slashCandidates.map { it.botId }.distinct().size > 1
                    slashCandidates.forEach { item ->
                        TextButton(
                            onClick = {
                                onValueChange(com.maodouchat.bot.BotCommandPolicy.insertCommand(item, multiBot))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "/${item.command}" + if (multiBot) " @${item.username}" else "",
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (item.description.isNotBlank()) {
                                    Text(
                                        text = item.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalChatPalette.current.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (isGroupChat && value.startsWith("@AI", ignoreCase = true)) {
                val showHint = value.length >= 3
                AnimatedVisibility(
                    visible = showHint,
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.chat_group_ai_input_hint),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val modeChips = listOf(
                                "answer" to R.string.chat_group_ai_mode_answer,
                                "summary" to R.string.chat_group_ai_mode_summary,
                                "decisions" to R.string.chat_group_ai_mode_decisions,
                                "tasks" to R.string.chat_group_ai_mode_tasks,
                                "timeline" to R.string.chat_group_ai_mode_timeline,
                                "risks" to R.string.chat_group_ai_mode_risks
                            )
                            val currentQuery = value.trim().removePrefix("@AI").removePrefix("@ai").trimStart()
                            val activeMode = when {
                                currentQuery.startsWith("总结") || currentQuery.startsWith("概括") ||
                                    currentQuery.startsWith("summary", ignoreCase = true) ||
                                    currentQuery.startsWith("summarize", ignoreCase = true) -> "summary"
                                currentQuery.startsWith("决策") || currentQuery.startsWith("决定") ||
                                    currentQuery.startsWith("decisions", ignoreCase = true) -> "decisions"
                                currentQuery.startsWith("待办") || currentQuery.startsWith("任务") ||
                                    currentQuery.startsWith("tasks", ignoreCase = true) ||
                                    currentQuery.startsWith("todo", ignoreCase = true) -> "tasks"
                                currentQuery.startsWith("时间线") || currentQuery.startsWith("时间轴") ||
                                    currentQuery.startsWith("timeline", ignoreCase = true) ||
                                    currentQuery.startsWith("chronology", ignoreCase = true) -> "timeline"
                                currentQuery.startsWith("风险") || currentQuery.startsWith("隐患") ||
                                    currentQuery.startsWith("risk", ignoreCase = true) ||
                                    currentQuery.startsWith("blocker", ignoreCase = true) -> "risks"
                                else -> "answer"
                            }
                            modeChips.forEach { (mode, labelRes) ->
                                val prefix = when (mode) {
                                    "summary" -> "总结 "
                                    "decisions" -> "决策 "
                                    "tasks" -> "待办 "
                                    "timeline" -> "时间线 "
                                    "risks" -> "风险 "
                                    else -> ""
                                }
                                FilterChip(
                                    selected = activeMode == mode,
                                    onClick = {
                                        val body = when {
                                            currentQuery.startsWith("总结") || currentQuery.startsWith("概括") ->
                                                currentQuery.removePrefix("总结").removePrefix("概括").trimStart()
                                            currentQuery.startsWith("summary", ignoreCase = true) ->
                                                currentQuery.drop(7).trimStart()
                                            currentQuery.startsWith("summarize", ignoreCase = true) ->
                                                currentQuery.drop(9).trimStart()
                                            currentQuery.startsWith("决策") || currentQuery.startsWith("决定") ->
                                                currentQuery.removePrefix("决策").removePrefix("决定").trimStart()
                                            currentQuery.startsWith("decisions", ignoreCase = true) ->
                                                currentQuery.drop(9).trimStart()
                                            currentQuery.startsWith("待办") || currentQuery.startsWith("任务") ->
                                                currentQuery.removePrefix("待办").removePrefix("任务").trimStart()
                                            currentQuery.startsWith("tasks", ignoreCase = true) ->
                                                currentQuery.drop(5).trimStart()
                                            currentQuery.startsWith("todo", ignoreCase = true) ->
                                                currentQuery.drop(4).trimStart()
                                            else -> currentQuery
                                        }
                                        onValueChange("@AI $prefix$body".trimEnd())
                                    },
                                    label = { Text(stringResource(labelRes)) }
                                )
                            }
                        }
                    }
                }
            }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            IconButton(
                onClick = composerState::toggleAttachMenu,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.chat_attachment),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(LocalChatPalette.current.chatInputBackground)) {
                // 1.175：回车发送偏好（开 → 单行 + IME Send；关 → 多行回车换行）
                val enterToSend = com.maodouchat.util.ComposerPreferences.enterToSend(context)
                TextField(
                    value = value, onValueChange = onValueChange,
                    placeholder = { Text(stringResource(R.string.chat_message_placeholder), style = MaterialTheme.typography.bodyLarge, color = LocalChatPalette.current.textHint) },
                    singleLine = enterToSend,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = if (enterToSend) androidx.compose.ui.text.input.ImeAction.Send else androidx.compose.ui.text.input.ImeAction.Default
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { if (enterToSend && !isSending) onSend() }
                    ),
                    trailingIcon = if (value.length >= com.maodouchat.ui.screen.chatdetail.ChatDetailViewModel.MAX_COMPOSER_TEXT_LENGTH * 8 / 10) {
                        {
                            Text(
                                text = "${value.length}/${com.maodouchat.ui.screen.chatdetail.ChatDetailViewModel.MAX_COMPOSER_TEXT_LENGTH}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (value.length >= com.maodouchat.ui.screen.chatdetail.ChatDetailViewModel.MAX_COMPOSER_TEXT_LENGTH * 9 / 10) UnreadRed else TextHint
                            )
                        }
                    } else null,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 40.dp)
                )
            }
            IconButton(
                onClick = composerState::toggleExpressionPanel,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Outlined.SentimentSatisfied,
                    contentDescription = stringResource(R.string.chat_emoji),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
            Box {
                DropdownMenu(expanded = showAiMenu, onDismissRequest = { showAiMenu = false }) {
                    Text(
                        stringResource(R.string.chat_ai_entry_primary_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    // 草稿改写：只动输入框内容
                    Text(
                        stringResource(R.string.chat_ai_section_draft),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_polish)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("polish", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_shorter)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("shorten", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_formal)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("formal", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_gentle)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("gentle", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_casual)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("casual", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_professional)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("professional", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_expand)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("expand", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_bullet)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("bullet", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_clarify)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiRewrite("clarify", null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_translate_draft)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = {
                            showAiMenu = false
                            showDraftTranslationLanguages = true
                        }
                    )
                    HorizontalDivider(color = LocalChatPalette.current.textHint.copy(alpha = 0.25f))
                    // 聊天辅助：读上下文
                    Text(
                        stringResource(R.string.chat_ai_section_chat),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    Text(
                        stringResource(R.string.chat_ai_smart_replies),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    listOf(
                        "friendly" to R.string.chat_ai_reply_tone_friendly,
                        "natural" to R.string.chat_ai_reply_tone_natural,
                        "formal" to R.string.chat_ai_reply_tone_formal,
                        "concise" to R.string.chat_ai_reply_tone_concise,
                        "warm" to R.string.chat_ai_reply_tone_warm,
                        "humorous" to R.string.chat_ai_reply_tone_humorous,
                        "direct" to R.string.chat_ai_reply_tone_direct,
                        "empathetic" to R.string.chat_ai_reply_tone_empathetic,
                        "encouraging" to R.string.chat_ai_reply_tone_encouraging
                    ).forEach { (tone, labelRes) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(labelRes)) },
                            enabled = aiEnabled && !isSecretChat,
                            onClick = { showAiMenu = false; onAiSuggestReplies(tone) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_summarize_recent)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onAiSummarize() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_conversation_profile)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onOpenConversationProfile() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_weekly_report)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onOpenWeeklyReport() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_emotion_reply)) },
                        enabled = aiEnabled && !isSecretChat,
                        onClick = { showAiMenu = false; onEmotionReply() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_ai_summary_history_menu)) },
                        onClick = { showAiMenu = false; onOpenAiSummaryHistory() }
                    )
                    if (isGroupChat) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_group_ai_title)) },
                            enabled = aiEnabled && !isSecretChat,
                            onClick = {
                                showAiMenu = false
                                if (!value.trimStart().startsWith("@AI", ignoreCase = true)) {
                                    onValueChange("@AI ${value.trimStart()}")
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_tasks_menu)) },
                            onClick = {
                                showAiMenu = false
                                onOpenAiTasks()
                            }
                        )
                    }
                    // 8.47：消息分类（纯本地词典统计，不依赖 AI 开关；密聊不参与）
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_enhance_classify_title)) },
                        enabled = !isSecretChat,
                        onClick = { showAiMenu = false; onOpenMessageClassify() }
                    )
                    HorizontalDivider(color = LocalChatPalette.current.textHint.copy(alpha = 0.25f))
                    Text(
                        stringResource(R.string.chat_ai_section_settings),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (aiEnabled) R.string.chat_ai_disable else R.string.chat_ai_enable)) },
                        onClick = { showAiMenu = false; onAiEnabledChange(!aiEnabled) }
                    )
                    Text(
                        stringResource(R.string.chat_ai_entry_context_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                    )
                    Text(
                        stringResource(R.string.chat_ai_menu_footer),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
            val canSend = value.isNotBlank()
            val haptic = LocalHapticFeedback.current
            val hapticContext = LocalContext.current
            val density = LocalDensity.current
            var holdCancelArmed by remember { mutableStateOf(false) }
            if (isRecording) {
                // 按住说话过程中：显示取消/发送提示；也可点按图标
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        stringResource(
                            if (holdCancelArmed) R.string.chat_slide_up_to_cancel
                            else R.string.chat_release_to_preview
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (holdCancelArmed) UnreadRed else TextSecondary,
                        modifier = Modifier.padding(end = 4.dp, bottom = 2.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onRecordCancel, modifier = Modifier.size(40.dp).background(Secondary, CircleShape)) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_cancel_recording), tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = onRecordStop, modifier = Modifier.size(40.dp).background(UnreadRed, CircleShape)) {
                            Icon(Icons.Outlined.Mic, contentDescription = stringResource(R.string.chat_stop_recording), tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            } else if (canSend) {
                val sendInteraction = remember { MutableInteractionSource() }
                val sendPressed by sendInteraction.collectIsPressedAsState()
                val sendScale by animateFloatAsState(
                    targetValue = if (sendPressed) 0.9f else 1f,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f),
                    label = "sendButtonScale"
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer {
                            scaleX = sendScale
                            scaleY = sendScale
                        }
                        .shadow(2.dp, CircleShape)
                        .background(if (silentSend || isSending) Outline else Primary, CircleShape)
                        .combinedClickable(
                            enabled = !isSending,
                            interactionSource = sendInteraction,
                            indication = null,
                            onClick = {
                                com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.TextHandleMove)
                                onSend()
                            },
                            onLongClick = {
                                com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.LongPress)
                                onScheduleSend()
                            }
                        )
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = stringResource(R.string.chat_send),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                // 空输入：按住说话（松开发送，上滑取消）
                val cancelThresholdPx = with(density) { 56.dp.toPx() }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .shadow(1.dp, CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f), CircleShape)
                        .pointerInput(attachmentsEnabled) {
                            if (!attachmentsEnabled) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                holdCancelArmed = false
                                com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.LongPress)
                                onRecordStart()
                                var cancelled = false
                                var completed = false
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null) { completed = true; break }
                                        val dy = change.position.y - down.position.y
                                        val arm = dy < -cancelThresholdPx
                                        if (arm != holdCancelArmed) {
                                            holdCancelArmed = arm
                                            if (arm) com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.TextHandleMove)
                                        }
                                        if (!change.pressed) {
                                            cancelled = holdCancelArmed
                                            change.consume()
                                            completed = true
                                            break
                                        }
                                        change.consume()
                                    }
                                } finally {
                                    // 协程被取消（旋转/退后台/pointerInput 重启）时也兜底释放麦克风，
                                    // 否则 MediaRecorder 持续持有录音、50ms 电平循环后台空转
                                    if (completed) {
                                        if (cancelled) onRecordCancel() else onRecordStop()
                                    } else {
                                        onRecordCancel()
                                    }
                                    holdCancelArmed = false
                                }
                            }
                        }
                        .then(
                            if (!attachmentsEnabled) {
                                Modifier.clickable {
                                    Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show()
                                }
                            } else Modifier
                        )
                ) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = stringResource(R.string.chat_hold_to_talk),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
}

@Composable
internal fun TranslationLanguageDialog(
    translatedLanguages: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var languageSearch by rememberSaveable { mutableStateOf("") }
    val q = languageSearch.trim()
    val labeledOptions = translationLanguageOptions.map { option ->
        option to stringResource(option.labelResource)
    }
    val filteredLanguages = if (q.isEmpty()) {
        labeledOptions
    } else {
        labeledOptions.filter { (option, label) ->
            label.contains(q, ignoreCase = true) ||
                option.wireValue.contains(q, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_translation_language_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedTextField(
                    value = languageSearch,
                    onValueChange = { languageSearch = it.take(64) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    placeholder = { Text(stringResource(R.string.chat_translation_language_search_hint)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = Secondary)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Outline,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface,
                        cursorColor = Primary
                    )
                )
                if (filteredLanguages.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_translation_language_search_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    filteredLanguages.forEach { (language, label) ->
                        TextButton(
                            onClick = { onSelect(language.wireValue) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (language.wireValue in translatedLanguages) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = stringResource(R.string.chat_translation_available),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
internal fun ReportDialog(
    title: String,
    onDismiss: () -> Unit,
    onReport: (reason: String, description: String?) -> Unit
) {
    val reasons = stringArrayResource(R.array.chat_report_reasons).toList()
    // 8.49 防御：资源数组为空时回退空串（此前 reasons.first() 依赖资源不被清空）
    var selectedReason by rememberSaveable { mutableStateOf(reasons.firstOrNull().orEmpty()) }
    var description by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                reasons.forEach { reason ->
                    val selected = reason == selectedReason
                    TextButton(
                        onClick = { selectedReason = reason },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (selected) PrimaryFixed.copy(alpha = 0.42f) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                    ) {
                        Text(
                            text = reason,
                            modifier = Modifier.fillMaxWidth(),
                            color = if (selected) Primary else OnSurface
                        )
                    }
                }
                TextField(
                    value = description,
                    onValueChange = { description = it.take(800) },
                    placeholder = { Text(stringResource(R.string.chat_report_description), color = LocalChatPalette.current.textHint) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Primary,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface
                    ),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onReport(selectedReason, description.trim().takeIf { it.isNotBlank() })
                }
            ) {
                Text(stringResource(R.string.chat_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
internal fun QuickPhrasesDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val context = LocalContext.current
    var phrases by remember {
        mutableStateOf(com.maodouchat.util.QuickPhrasePreferences.getPhrases(context))
    }
    var customIds by remember {
        mutableStateOf(com.maodouchat.util.QuickPhrasePreferences.getCustomPhrases(context).toSet())
    }
    var newPhrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_quick_phrases), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = newPhrase,
                    onValueChange = {
                        newPhrase = it.take(com.maodouchat.util.QuickPhrasePolicy.MAX_PHRASE_LENGTH)
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_quick_phrases_add_hint, com.maodouchat.util.QuickPhrasePolicy.MAX_PHRASE_LENGTH)) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Outline
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (error != null) {
                        Text(error.orEmpty(), color = LocalChatPalette.current.unreadRed, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        enabled = newPhrase.isNotBlank(),
                        onClick = {
                            if (!com.maodouchat.util.QuickPhrasePolicy.isAddable(
                                    com.maodouchat.util.QuickPhrasePreferences.getCustomPhrases(context),
                                    newPhrase
                                )
                            ) {
                                error = context.getString(R.string.chat_quick_phrases_add_invalid)
                                return@TextButton
                            }
                            com.maodouchat.util.QuickPhrasePreferences.addPhrase(context, newPhrase.trim())
                            phrases = com.maodouchat.util.QuickPhrasePreferences.getPhrases(context)
                            customIds = com.maodouchat.util.QuickPhrasePreferences.getCustomPhrases(context).toSet()
                            newPhrase = ""
                            Toast.makeText(context, context.getString(R.string.chat_quick_phrases_added), Toast.LENGTH_SHORT).show()
                        }
                    ) { Text(stringResource(R.string.chat_quick_phrases_add)) }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    phrases.forEach { phrase ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = { onPick(phrase) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    phrase,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (phrase in customIds) {
                                IconButton(onClick = {
                                    com.maodouchat.util.QuickPhrasePreferences.removePhrase(context, phrase)
                                    phrases = com.maodouchat.util.QuickPhrasePreferences.getPhrases(context)
                                    customIds = com.maodouchat.util.QuickPhrasePreferences.getCustomPhrases(context).toSet()
                                }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.common_delete),
                                        tint = LocalChatPalette.current.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.chat_quick_phrases_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } }
    )
}

@Composable
internal fun ExpressionPanel(
    mode: String,
    onModeChange: (String) -> Unit,
    onEmojiClick: (String) -> Unit,
    onStickerClick: (String) -> Unit,
    onGifClick: () -> Unit,
    stickersEnabled: Boolean
) {
    val context = LocalContext.current
    var stickerTab by rememberSaveable { mutableStateOf(com.maodouchat.util.StickerCatalog.PACK_RECENT) }
    var stickerQuery by rememberSaveable { mutableStateOf("") }
    var showPackManager by rememberSaveable { mutableStateOf(false) }
    var enabledPackIds by remember {
        mutableStateOf(com.maodouchat.util.StickerPreferences.getEnabledPackIds(context))
    }
    var recentStickers by remember {
        mutableStateOf(com.maodouchat.util.StickerPreferences.getRecent(context))
    }
    var recentEmojis by remember {
        mutableStateOf(com.maodouchat.util.EmojiRecentPreferences.getRecent(context))
    }
    val enabledPacks = remember(enabledPackIds) {
        com.maodouchat.util.StickerPolicy.enabledPacks(enabledPackIds)
    }
    val searchHits = remember(stickerQuery, enabledPackIds) {
        com.maodouchat.util.StickerPolicy.searchStickers(stickerQuery, enabledPacks)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (mode == "STICKER") 280.dp else 224.dp)
            .background(LocalChatPalette.current.chatInputBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("EMOJI" to stringResource(R.string.chat_emoji), "STICKER" to stringResource(R.string.chat_sticker)).forEach { (key, label) ->
                    val selected = mode == key
                    TextButton(
                        onClick = { onModeChange(key) },
                        modifier = Modifier.background(
                            if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                    ) { Text(label, color = if (selected) Primary else TextSecondary) }
                }
            }
            if (mode == "STICKER") {
                IconButton(onClick = { showPackManager = true }) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.sticker_pack_manage), tint = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onGifClick, enabled = stickersEnabled) {
                Icon(Icons.Outlined.GifBox, contentDescription = stringResource(R.string.chat_select_gif), tint = if (stickersEnabled) Primary else TextHint)
            }
        }

        if (mode == "STICKER") {
            OutlinedTextField(
                value = stickerQuery,
                onValueChange = { stickerQuery = it.take(64) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.sticker_search_hint)) },
                textStyle = MaterialTheme.typography.bodySmall,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Outline
                )
            )
            if (stickerQuery.isBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StickerPackChip(
                        label = stringResource(R.string.sticker_pack_recent),
                        selected = stickerTab == com.maodouchat.util.StickerCatalog.PACK_RECENT,
                        onClick = { stickerTab = com.maodouchat.util.StickerCatalog.PACK_RECENT }
                    )
                    enabledPacks.forEach { pack ->
                        StickerPackChip(
                            label = stickerPackLabel(pack.nameKey),
                            selected = stickerTab == pack.id,
                            onClick = { stickerTab = pack.id }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
            val displayItems = when {
                stickerQuery.isNotBlank() -> searchHits
                stickerTab == com.maodouchat.util.StickerCatalog.PACK_RECENT -> recentStickers
                else -> enabledPacks.firstOrNull { it.id == stickerTab }?.stickers
                    ?: enabledPacks.firstOrNull()?.stickers
                    ?: emptyList()
            }
            if (displayItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(
                            when {
                                stickerQuery.isNotBlank() -> R.string.sticker_search_empty
                                stickerTab == com.maodouchat.util.StickerCatalog.PACK_RECENT -> R.string.sticker_recent_empty
                                else -> R.string.sticker_search_empty
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    displayItems.chunked(6).forEach { rowItems ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            rowItems.forEach { item ->
                                // 9.223：贴纸按压回弹（TG 式手感，尊重系统动效开关）
                                PressScaleGlyphItem(
                                    glyph = item,
                                    fontSize = 32.sp,
                                    enabled = stickersEnabled,
                                    onClick = {
                                        onStickerClick(item)
                                        recentStickers = com.maodouchat.util.StickerPolicy.pushRecent(recentStickers, item)
                                    }
                                )
                            }
                            repeat(6 - rowItems.size) { Spacer(modifier = Modifier.size(48.dp)) }
                        }
                    }
                }
            }
        } else {
            var emojiSearch by rememberSaveable { mutableStateOf("") }
            val filteredEmojis = remember(emojiSearch) {
                val q = emojiSearch.trim()
                if (q.isEmpty()) {
                    BUILT_IN_EMOJIS
                } else {
                    // emoji itself or common keyword aliases
                    val aliases = EMOJI_SEARCH_ALIASES
                    BUILT_IN_EMOJIS.filter { emoji ->
                        emoji.contains(q) ||
                            aliases[emoji].orEmpty().any { it.contains(q, ignoreCase = true) }
                    }
                }
            }
            OutlinedTextField(
                value = emojiSearch,
                onValueChange = { emojiSearch = it.take(64) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.chat_emoji_search_hint)) },
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = Secondary, modifier = Modifier.size(18.dp))
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Outline
                )
            )
            if (filteredEmojis.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.chat_emoji_search_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                }
            } else {
                // 点击表情：上屏 + 记录最近使用（去重，本地按账号隔离）
                val pushRecentEmoji: (String) -> Unit = { emoji ->
                    onEmojiClick(emoji)
                    com.maodouchat.util.EmojiRecentPreferences.recordRecent(context, emoji)
                    recentEmojis = com.maodouchat.util.EmojiRecentPreferences.getRecent(context)
                }
                Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    // 最近使用行：仅无搜索词时展示
                    if (emojiSearch.isBlank() && recentEmojis.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_emoji_recent),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textSecondary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            recentEmojis.forEach { item ->
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { pushRecentEmoji(item) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(item, fontSize = 22.sp, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    filteredEmojis.chunked(6).forEach { rowItems ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            rowItems.forEach { item ->
                                PressScaleGlyphItem(
                                    glyph = item,
                                    fontSize = 24.sp,
                                    enabled = true,
                                    onClick = { pushRecentEmoji(item) }
                                )
                            }
                            repeat(6 - rowItems.size) { Spacer(modifier = Modifier.size(48.dp)) }
                        }
                    }
                }
            }
        }
    }

    if (showPackManager) {
        // 远程贴纸包（OnDemandStickerStore）：按需拉取清单 + 下载，B1 包体瘦身闭环
        var remotePacks by remember { mutableStateOf<List<String>>(emptyList()) }
        var remoteStatus by remember { mutableStateOf<String?>(null) }
        var remoteDownloading by remember { mutableStateOf<String?>(null) }
        val remoteScope = rememberCoroutineScope()
        val remoteContext = context
        fun refreshRemotePacks() {
            remoteScope.launch {
                remoteStatus = null
                com.maodouchat.slim.OnDemandStickerStore.refreshManifest(remoteContext)
                    .onSuccess { remotePacks = it }
                    .onFailure { remoteStatus = remoteContext.getString(R.string.sticker_store_download_failed) }
            }
        }
        fun downloadRemotePack(packId: String) {
            if (remoteDownloading != null) return
            remoteScope.launch {
                remoteDownloading = packId
                remoteStatus = null
                val result = com.maodouchat.slim.OnDemandStickerStore.ensurePack(remoteContext, packId)
                remoteStatus = result.message
                remoteDownloading = null
            }
        }
        AlertDialog(
            onDismissRequest = { showPackManager = false },
            title = { Text(stringResource(R.string.sticker_pack_manage)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())
                ) {
                    com.maodouchat.util.StickerCatalog.BUILT_IN_PACKS.forEach { pack ->
                        val enabled = pack.id in enabledPackIds
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = com.maodouchat.util.StickerPolicy.togglePackEnabled(
                                        enabledIds = enabledPackIds,
                                        packId = pack.id,
                                        enable = !enabled
                                    )
                                    enabledPackIds = next
                                    com.maodouchat.util.StickerPreferences.setEnabledPackIds(context, next)
                                    if (!enabled && stickerTab == pack.id) {
                                        stickerTab = com.maodouchat.util.StickerCatalog.PACK_RECENT
                                    }
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = stickerPackLabel(pack.nameKey),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(if (enabled) R.string.sticker_pack_enabled else R.string.sticker_pack_disabled),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (enabled) Primary else TextSecondary
                            )
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.textHint.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.sticker_store_remote_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalChatPalette.current.textSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { refreshRemotePacks() }) {
                            Text(stringResource(R.string.sticker_store_remote_refresh))
                        }
                    }
                    if (remoteStatus != null) {
                        Text(
                            remoteStatus.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                    if (remotePacks.isEmpty() && remoteStatus == null) {
                        Text(
                            stringResource(R.string.sticker_store_remote_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textHint,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    remotePacks.forEach { packId ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(
                                packId,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (remoteDownloading == packId) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                TextButton(onClick = { downloadRemotePack(packId) }) {
                                    Text(stringResource(R.string.sticker_store_remote_download))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPackManager = false }) {
                    Text(stringResource(R.string.common_done))
                }
            }
        )
    }
}

@Composable
internal fun StickerPackChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Primary else TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
internal fun stickerPackLabel(nameKey: String): String = when (nameKey) {
    "mood" -> stringResource(R.string.sticker_pack_mood)
    "gesture" -> stringResource(R.string.sticker_pack_gesture)
    "party" -> stringResource(R.string.sticker_pack_party)
    else -> nameKey
}

private val BUILT_IN_EMOJIS = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅",
    "😂", "🤣", "🥹", "😊", "😇", "🙂",
    "😉", "😍", "🥰", "😘", "😗", "😙",
    "😚", "😋", "😜", "🤪", "😝", "🤑",
    "🤗", "🤭", "🤫", "🤔", "🤐", "🤨",
    "😐", "😑", "😶", "😏", "😒", "🙄",
    "😬", "😮‍💨", "🤥", "😌", "😔", "😪",
    "🤤", "😴", "😷", "🤒", "🤕", "🤢",
    "🤮", "🥵", "🥶", "🥴", "😵", "🤯",
    "🤠", "🥳", "😎", "🤓", "🧐", "😕",
    "😟", "🙁", "☹️", "😮", "😯", "😲",
    "😳", "🥺", "😦", "😧", "😨", "😰",
    "😥", "😢", "😭", "😱", "😖", "😣",
    "😞", "😓", "😩", "😫", "🥱", "😤",
    "😡", "😠", "🤬", "😈", "👿", "💀",
    "👍", "👎", "👊", "✊", "🤛", "🤜",
    "👏", "🙌", "👐", "🤲", "🤝", "🙏",
    "💪", "🦾", "🦵", "🦶", "👂", "👃",
    "👀", "👁️", "👅", "👄", "💋", "💘",
    "❤️", "🧡", "💛", "💚", "💙", "💜",
    "🖤", "🤍", "🤎", "💔", "❣️", "💕",
    "💞", "💓", "💗", "💖", "💝", "💟",
    "🔥", "✨", "⭐", "🌟", "💫", "🎉",
    "🎊", "🎈", "🎁", "🏆", "🥇", "💯",
    "✅", "❌", "❗", "❓", "💤", "💢",
    "🫡", "🫠", "🫥", "🫢", "🫣", "🫤",
    "🫶", "🫰", "🫵", "🖖", "🤙", "👋",
    "🚀", "🌈", "🍀", "🌸", "☀️", "🌙",
    "🐱", "🐶", "🐼", "🦊", "🐰", "🐻",
    "🐯", "🦁", "🐨", "🐸", "🐵", "🐔",
    "🍕", "🍔", "☕", "🍺", "🎂", "🍩",
    "⚽", "🏀", "🎮", "🎧", "📚", "✈️",
    "💡", "🎯",
    "🧭",
    "📌",
    "🧩",
    "🛡️",
    "🔔", "📎", "🔗", "📝"

)

/** Lightweight keyword aliases for emoji panel search (zh/en). */
private val EMOJI_SEARCH_ALIASES: Map<String, List<String>> = mapOf<String, List<String>>(
    "😂" to listOf("laugh", "lol", "笑", "哈哈"),
    "🤣" to listOf("rofl", "laugh", "笑"),
    "😍" to listOf("love", "heart eyes", "爱", "喜欢"),
    "🥰" to listOf("love", "cute", "爱", "可爱"),
    "😘" to listOf("kiss", "亲", "么么"),
    "😎" to listOf("cool", "酷"),
    "🤔" to listOf("think", "thinking", "思考", "嗯"),
    "😴" to listOf("sleep", "困", "睡"),
    "😭" to listOf("cry", "sad", "哭", "泪"),
    "😡" to listOf("angry", "怒", "生气"),
    "🤯" to listOf("mind blown", "震惊", "炸"),
    "🥳" to listOf("party", "庆祝", "派对"),
    "👍" to listOf("ok", "yes", "like", "赞", "好"),
    "👎" to listOf("no", "dislike", "踩", "不好"),
    "👏" to listOf("clap", "applaud", "鼓掌"),
    "🙏" to listOf("pray", "please", "thanks", "拜托", "谢谢"),
    "💪" to listOf("strong", "muscle", "加油", "💪"),
    "🤝" to listOf("handshake", "deal", "握手", "合作"),
    "😊" to listOf("smile", "happy", "笑", "开心"),
    "🙌" to listOf("raise hands", "celebration", "举手", "耶"),
    "🤩" to listOf("star eyes", "wow", "惊喜", "崇拜"),
    "🥲" to listOf("tear smile", "bittersweet", "含泪笑", "无奈"),
    "🤣" to listOf("rofl", "laugh hard", "大笑", "笑哭"),
    "👌" to listOf("ok hand", "perfect", "没问题", "好的"),
    "🫶" to listOf("heart hands", "care", "比心", "抱抱"),
    "❤️" to listOf("heart", "love", "心", "爱"),
    "💔" to listOf("broken", "heartbreak", "心碎"),
    "🔥" to listOf("fire", "hot", "火", "热"),
    "✨" to listOf("sparkle", "shine", "亮", "闪光"),
    "🎉" to listOf("party", "tada", "庆祝", "撒花"),
    "💯" to listOf("100", "perfect", "满分"),
    "✅" to listOf("check", "done", "ok", "完成", "对"),
    "❌" to listOf("cross", "no", "错", "叉"),
    "👀" to listOf("eyes", "look", "看", "👀"),
    "💀" to listOf("skull", "dead", "死", "骷髅"),
    "💤" to listOf("zzz", "sleep", "困"),
    "🎁" to listOf("gift", "present", "礼物"),
    "🏆" to listOf("trophy", "win", "冠军", "奖杯"),
    "⭐" to listOf("star", "星"),
    "🌟" to listOf("star", "glow", "星"),
    "❓" to listOf("question", "?", "问"),
    "❗" to listOf("exclaim", "!", "感叹"),
    "🫡" to listOf("salute", "respect", "敬礼", "收到"),
    "🫠" to listOf("melt", "awkward", "融化", "尴尬"),
    "🫶" to listOf("heart hands", "care", "比心", "爱心"),
    "🚀" to listOf("rocket", "launch", "火箭", "起飞"),
    "🍕" to listOf("pizza", "food", "披萨", "吃"),
    "🍔" to listOf("burger", "food", "汉堡"),
    "☕" to listOf("coffee", "tea", "咖啡", "茶"),
    "🍺" to listOf("beer", "drink", "啤酒"),
    "🎂" to listOf("cake", "birthday", "蛋糕", "生日"),
    "⚽" to listOf("soccer", "football", "足球"),
    "🏀" to listOf("basketball", "篮球"),
    "🎮" to listOf("game", "play", "游戏"),
    "🎧" to listOf("music", "headphones", "音乐", "耳机"),
    "📚" to listOf("books", "study", "书", "学习"),
    "✈️" to listOf("plane", "travel", "飞机", "旅行"),
    "🐯" to listOf("tiger", "虎"),
    "🦁" to listOf("lion", "狮"),
    "🐸" to listOf("frog", "蛙"),
    "🐵" to listOf("monkey", "猴"),
    "🌈" to listOf("rainbow", "彩虹"),
    "🐱" to listOf("cat", "猫"),
    "🐶" to listOf("dog", "狗"),
    "☀️" to listOf("sun", "sunny", "太阳", "晴"),
    "🌙" to listOf("moon", "night", "月亮", "夜"),
    "💡" to listOf("idea", "bulb", "灵感", "灯泡"),
    "🧭" to listOf("compass", "navigate", "导航", "指南针"),
    "📌" to listOf("pin", "bookmark", "图钉", "标记"),
    "🧩" to listOf("puzzle", "piece", "拼图"),
    "🛡️" to listOf("shield", "protect", "盾牌", "防护"),
)

/**
 * 日历跳转对话框：选日期后跳到该日第一条消息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateJumpDialog(
    onDismiss: () -> Unit,
    onJump: (dayStartMillis: Long) -> Unit
) {
    val context = LocalContext.current
    val today = java.time.LocalDate.now()
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= System.currentTimeMillis()
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        }
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_jump_date_title)) },
        text = {
            DatePicker(state = datePickerState, showModeToggle = false)
        },
        confirmButton = {
            TextButton(
                enabled = datePickerState.selectedDateMillis != null,
                onClick = {
                    val utcMillis = datePickerState.selectedDateMillis ?: return@TextButton
                    // 选中的是 UTC 当天 00:00；换算成本地时区当天 00:00
                    val localStart = java.time.Instant.ofEpochMilli(utcMillis)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    onJump(localStart)
                }
            ) { Text(stringResource(R.string.chat_jump_date_jump)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_later)) }
        }
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
internal fun ChatDetailScreenPreview() { MaodouchatTheme { ChatDetailRoute() } }

/** 1.11：发送名片——选择要分享的联系人（单聊会话对端用户）。1.27：支持搜索过滤。 */
@Composable
internal fun ContactCardPickerDialog(
    contacts: List<Pair<Chat, com.maodouchat.data.model.User>>,
    onDismiss: () -> Unit,
    onPick: (Chat, com.maodouchat.data.model.User) -> Unit
) {
    var pickerQuery by rememberSaveable { mutableStateOf("") }
    val filteredContacts = remember(contacts, pickerQuery) {
        val q = pickerQuery.trim()
        if (q.isEmpty()) {
            contacts
        } else {
            contacts.filter { (_, user) ->
                user.displayName.contains(q, ignoreCase = true) || user.name.contains(q, ignoreCase = true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_card_picker_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = pickerQuery,
                    onValueChange = { pickerQuery = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.contact_card_picker_search)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (filteredContacts.isEmpty()) {
                    Text(
                        stringResource(if (contacts.isEmpty()) R.string.contact_card_picker_empty else R.string.contact_card_picker_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        filteredContacts.forEach { (chat, user) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(chat, user) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                Avatar(name = user.displayName, avatarUrl = user.avatar, size = AvatarSize.SM)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        user.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "@${user.name}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LocalChatPalette.current.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * 9.223：表情/贴纸格子项——按压缩放回弹（TG 式手感）。
 * 系统关闭动画时退化为无动效点击；无 indication（缩放即反馈）。
 */
@Composable
internal fun PressScaleGlyphItem(
    glyph: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val motion = LocalMotionSettings.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && motion.animationsEnabled) 0.82f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "glyphPressScale"
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            glyph,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        )
    }
}
