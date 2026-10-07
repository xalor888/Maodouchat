package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.network.AiAuditLogResponse
import com.maodouchat.R
import com.maodouchat.ui.theme.Error
import androidx.compose.ui.graphics.Color
import com.maodouchat.ui.theme.LocalChatPalette

@Composable
internal fun AiAuditLogRow(log: AiAuditLogResponse) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(aiFeatureLabel(log.feature), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    aiStatusLabel(log.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = aiStatusColor(log.status)
                )
            }
            Text(
                listOfNotNull(
                    log.model?.takeIf { it.isNotBlank() },
                    stringResource(R.string.ai_privacy_input_chars, log.inputChars),
                    if (log.contextMessages > 0) stringResource(R.string.ai_privacy_context_messages, log.contextMessages) else null,
                    log.durationMs?.let { "${it}ms" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary
            )
            log.error?.takeIf { it.isNotBlank() }?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(formatAuditTime(log.createdAt), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
    }
}

@Composable
internal fun aiFeatureLabel(feature: String): String = when (feature.lowercase()) {
    "rewrite" -> stringResource(R.string.ai_feature_rewrite)
    "suggest_replies" -> stringResource(R.string.ai_feature_suggest_replies)
    "summarize" -> stringResource(R.string.ai_feature_summarize)
    "transcribe_voice" -> stringResource(R.string.ai_feature_transcribe_voice)
    "translate_message" -> stringResource(R.string.ai_feature_translate_message)
    "semantic_search" -> stringResource(R.string.ai_feature_semantic_search)
    "global_semantic_search" -> stringResource(R.string.ai_feature_global_semantic_search)
    "group_assistant" -> stringResource(R.string.ai_feature_group_assistant)
    "image_analyze" -> stringResource(R.string.ai_feature_image_analyze)
    "file_analyze" -> stringResource(R.string.ai_feature_file_analyze)
    else -> if (feature.isBlank()) stringResource(R.string.ai_feature_generic) else feature
}

@Composable
internal fun aiStatusLabel(status: String): String = when (status.lowercase()) {
    "success" -> stringResource(R.string.ai_status_success)
    "failed", "error" -> stringResource(R.string.ai_status_failed)
    "disabled" -> stringResource(R.string.ai_status_disabled)
    "rate_limited" -> stringResource(R.string.ai_status_rate_limited)
    else -> if (status.isBlank()) stringResource(R.string.ai_status_unknown) else status
}

@Composable
internal fun aiStatusColor(status: String): Color = when (status.lowercase()) {
    "success" -> MaterialTheme.colorScheme.primary
    "failed", "error", "rate_limited" -> Error
    else -> LocalChatPalette.current.textHint
}
