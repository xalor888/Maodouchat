package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Secondary
import com.maodouchat.ui.theme.TextHint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 图片/文件分析对话框：分析模式选择、提问输入与结果展示。所需输入全经参数传入。 */

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
