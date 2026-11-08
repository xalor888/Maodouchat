package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.core.model.ChatDetailMessageLabels
import com.maodouchat.core.model.ChatHeaderStatus
import com.maodouchat.core.model.resolveChatHeaderStatus

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.core.model.ChatDetailUiState

@Composable
internal fun rememberChatDetailMessageLabels(): ChatDetailMessageLabels =
    ChatDetailMessageLabels(
        sensitiveAuthTitle = stringResource(R.string.sensitive_auth_title),
        sensitiveAuthExport = stringResource(R.string.sensitive_auth_export_chat),
        sensitiveAuthClearHistory = stringResource(R.string.sensitive_auth_clear_chat),
        sensitiveAuthFailed = stringResource(R.string.sensitive_auth_failed),
        chatPermissionRecordMsg = stringResource(R.string.chat_permission_record),
        chatPermissionVoiceCallMsg = stringResource(R.string.chat_permission_voice_call),
        chatPermissionVideoCallMsg = stringResource(R.string.chat_permission_video_call),
        chatPermissionLocationMsg = stringResource(R.string.chat_permission_location),
        chatCopiedMsg = stringResource(R.string.chat_copied),
        chatAiImageResultTitle = stringResource(R.string.chat_ai_image_result_title),
        chatAiFileResultTitle = stringResource(R.string.chat_ai_file_result_title),
        chatGroupAiTitle = stringResource(R.string.chat_group_ai_title),
        chatGroupAiCopiedMsg = stringResource(R.string.chat_group_ai_copied),
        chatClipboardMessageLabel = stringResource(R.string.chat_clipboard_message),
        chatClipboardTranslationLabel = stringResource(R.string.chat_clipboard_translation),
        chatTranslationCopiedMsg = stringResource(R.string.chat_translation_copied),
        chatClipboardTranscriptLabel = stringResource(R.string.chat_clipboard_transcript),
        chatTranscriptCopiedMsg = stringResource(R.string.chat_transcript_copied),
        unknownSenderLabel = stringResource(R.string.chat_unknown),
        groupMemberLabel = stringResource(R.string.chat_group_member),
    )

/** 头部在线状态：纯函数派生，原在 ChatDetailRoute 内联计算，纯搬移。 */
@Composable
internal fun rememberChatHeaderStatus(
    state: ChatDetailUiState,
    context: Context,
): ChatHeaderStatus = resolveChatHeaderStatus(
    typingUserId = state.typingContact,
    isOnline = state.contact.isOnline,
    customStatus = state.contact.status,
    isGroup = state.chatIsGroup,
    lastSeen = if (state.isSecretChat == true && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_LAST_SEEN_BLOCK)) 0L else state.contact.lastSeen,
)
