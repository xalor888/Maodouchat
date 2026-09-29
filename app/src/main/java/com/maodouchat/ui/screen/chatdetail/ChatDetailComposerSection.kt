package com.maodouchat.ui.screen.chatdetail

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * G355：输入区（`ComposerPane`）的接线从 `ChatDetailRoute.kt` 抽出（纯搬移不改判断）——
 * 发送/定时/附件/位置/录音/名片等回调、AI 面板入口与提及参数的装配。
 *
 * 依赖全经参数注入；组合期内不新增状态所有权。
 */
@Suppress("LocalContextGetResourceValueCall") // 资源字符串在回调内读取，非组合作用域；lint 无法区分（同 ChatDetailRoute）
@Composable
internal fun ChatDetailComposerSection(
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    aiPanels: ChatDetailAiPanelState,
    aiResults: ChatDetailAiResultState,
    schedule: ChatDetailScheduleState,
    targets: ChatDetailMessageTargetState,
    flows: ChatDetailConversationFlowState,
    pickers: ChatDetailPickers,
    sendPending: ChatDetailSendPendingState,
    listScrollScope: CoroutineScope,
    onOpenAiTasks: (chatId: String) -> Unit,
) {
    val context = LocalContext.current
    val secretActive = state.isSecretChat == true
    ComposerPane(
        value = state.inputText,
        onValueChange = { viewModel.onInputChange(it) },
        onSend = {
            if (state.isSending) return@ComposerPane
            viewModel.sendMessage(replyTarget = targets.replyTarget)
            targets.replyTarget = null
        },
        onScheduleSend = {
            if (state.inputText.isBlank()) {
                Toast.makeText(context, context.getString(R.string.schedule_need_text), Toast.LENGTH_SHORT).show()
            } else {
                schedule.showScheduleDialog = true
            }
        },
        onOpenConversationProfile = { aiPanels.showConversationProfile = true },
        onOpenWeeklyReport = { aiPanels.showWeeklyReport = true },
        onEmotionReply = { aiResults.emotionReplyRequested = true },
        onOpenMessageClassify = { aiPanels.showMessageClassify = true },
        isSecretChat = secretActive,
        contactCardTargets = state.forwardTargets,
        onLoadForwardTargets = { viewModel.loadForwardTargets() },
        onSendContactCard = { userId, name -> viewModel.sendContactCard(userId, name) },
        onSendImage = {
            sendPending.viewOnce = false
            sendPending.spoiler = false
            listScrollScope.launch {
                kotlinx.coroutines.yield()
                runCatching { pickers.image.launch("image/*") }
                    .onFailure {
                        Toast.makeText(context, context.getString(R.string.chat_image_picker_unavailable), Toast.LENGTH_SHORT).show()
                    }
            }
        },
        onSendViewOnceImage = {
            if (state.chat?.isGroup == true) {
                Toast.makeText(context, context.getString(R.string.view_once_direct_only), Toast.LENGTH_SHORT).show()
            } else {
                sendPending.viewOnce = true
                sendPending.spoiler = false
                listScrollScope.launch {
                    kotlinx.coroutines.yield()
                    runCatching { pickers.image.launch("image/*") }
                        .onFailure {
                            Toast.makeText(context, context.getString(R.string.chat_image_picker_unavailable), Toast.LENGTH_SHORT).show()
                        }
                }
            }
        },
        onSendSpoilerImage = {
            sendPending.spoiler = true
            sendPending.viewOnce = false
            listScrollScope.launch {
                kotlinx.coroutines.yield()
                runCatching { pickers.image.launch("image/*") }
                    .onFailure {
                        Toast.makeText(context, context.getString(R.string.chat_image_picker_unavailable), Toast.LENGTH_SHORT).show()
                    }
            }
        },
        // 8.48：从系统剪贴板粘贴图片直接进入确认发送流程
        onPasteFromClipboard = {
            // 8.48 修复：重置阅后即焚/剧透意图——否则上一次取消选图器残留的标志
            // 会泄漏到后续普通视频/图片发送
            sendPending.viewOnce = false
            sendPending.spoiler = false
            listScrollScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val resultUri = runCatching {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = cm.primaryClip ?: return@runCatching null
                    if (clip.itemCount <= 0) return@runCatching null
                    val item = clip.getItemAt(0)
                    val uri = item.uri
                    val mime = uri?.let { u ->
                        runCatching { context.contentResolver.getType(u) }.getOrNull()
                    }
                    if (uri != null && mime?.startsWith("image/") == true) {
                        return@runCatching uri
                    }
                    val bmp = uri?.let { source ->
                        context.contentResolver.openInputStream(source)?.use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream)
                        }
                    } ?: return@runCatching null
                    val dir = java.io.File(context.cacheDir, "attachment-sources").apply { mkdirs() }
                    val file = java.io.File(dir, "paste_${System.currentTimeMillis()}.png")
                    file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                    bmp.recycle()
                    android.net.Uri.fromFile(file)
                }.getOrNull()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (resultUri != null) {
                        sendPending.imageConfirm = PendingImageSend(resultUri, false, false)
                    } else {
                        Toast.makeText(context, context.getString(R.string.chat_clipboard_no_image), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
        onSendVideo = {
            listScrollScope.launch {
                kotlinx.coroutines.yield()
                runCatching { pickers.video.launch("video/*") }
                    .onFailure {
                        Toast.makeText(context, context.getString(R.string.chat_video_picker_unavailable), Toast.LENGTH_SHORT).show()
                    }
            }
        },
        onSendFile = {
            listScrollScope.launch {
                kotlinx.coroutines.yield()
                runCatching { pickers.file.launch(arrayOf("*/*")) }
                    .onFailure {
                        Toast.makeText(context, context.getString(R.string.chat_image_picker_unavailable), Toast.LENGTH_SHORT).show()
                    }
            }
        },
        onSendGif = { aiPanels.showGifSearch = true },
        onSendSticker = { viewModel.sendSticker(it) },
        onSendLocation = {
            if (com.maodouchat.util.LocationProvider.hasLocationPermission(context)) viewModel.sendCurrentLocation()
            else {
                flows.pendingLiveLocationPermission = false
                pickers.locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        },
        onSendLiveLocation = {
            if (com.maodouchat.util.LocationProvider.hasLocationPermission(context)) flows.showLiveLocationDuration = true
            else {
                flows.pendingLiveLocationPermission = true
                pickers.locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        },
        onRecordStart = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                viewModel.startRecording()
            } else {
                pickers.recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onRecordStop = { viewModel.stopRecordingAndSend() },
        onRecordCancel = { viewModel.cancelRecording() },
        isRecording = state.isRecording,
        isAiWorking = state.isAiWorking,
        isAiDraftStreaming = state.isAiDraftStreaming,
        aiSuggestions = state.aiSuggestions,
        isAiReplyStreaming = state.isAiReplyStreaming,
        aiReplyStreamErrorCode = state.aiReplyStreamErrorCode,
        onAiRewrite = { mode, targetLanguage -> viewModel.requestAiRewrite(mode, targetLanguage) },
        onAiSuggestReplies = { tone -> viewModel.requestAiSuggestions(tone) },
        onAiSummarize = { aiPanels.showAiSummaryScopeDialog = true },
        onOpenAiSummaryHistory = { viewModel.openAiSummaryHistory() },
        onOpenAiTasks = { state.chat?.id?.let(onOpenAiTasks) },
        onAiSuggestionClick = { viewModel.applyAiSuggestion(it) },
        onClearAiSuggestions = { viewModel.clearAiSuggestions() },
        onCancelAiReplyStream = { viewModel.cancelAiReplyStream() },
        onRetryAiReplyStream = viewModel::retryAiReplyStream,
        aiEnabled = state.aiEnabled,
        isUpdatingAiSetting = state.isUpdatingAiSetting,
        onAiEnabledChange = { viewModel.setAiEnabledForChat(it) },
        isGroupChat = state.chatIsGroup,
        isChannelChat = state.chat?.isChannel == true,
        onSendNudge = { viewModel.sendNudge() },
        mentionParticipants = state.chat?.participants.orEmpty(),
        currentUserId = state.currentUserId,
        // 1.37：仅群主/管理员可选「@所有人」（1.45：角色未加载时 fail-open 避免误拦管理员）
        canMentionEveryone = !state.chatIsGroup || run {
            val role = state.myMemberRole?.uppercase()
            role == null || role == "OWNER" || role == "ADMIN"
        },
        silentSend = state.silentSend,
        onToggleSilentSend = viewModel::toggleSilentSend,
        isSending = state.isSending,
        readOnly = state.chat?.isChannel == true && state.myMemberRole != "OWNER",
        botCommands = state.botCommands,
    )
}
