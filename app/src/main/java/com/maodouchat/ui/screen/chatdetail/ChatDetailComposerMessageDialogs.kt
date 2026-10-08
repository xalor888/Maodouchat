package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.PrimaryFixed

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
