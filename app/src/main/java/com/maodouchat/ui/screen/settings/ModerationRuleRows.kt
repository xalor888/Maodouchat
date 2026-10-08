package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.network.RiskEventResponse
import com.maodouchat.network.ModerationRuleResponse
import com.maodouchat.R
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.LocalChatPalette

@Composable
internal fun RiskEventRow(event: RiskEventResponse, enabled: Boolean, onAcknowledge: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(riskActionLabel(event.action), style = MaterialTheme.typography.bodyLarge, color = if (event.needsReview) Error else MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(R.string.moderation_risk_summary, reportTargetLabel(event.source), event.userId, formatAuditTime(event.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            event.matched?.let { Text(stringResource(R.string.moderation_matched, it), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textHint, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        TextButton(onClick = onAcknowledge, enabled = enabled) { Text(stringResource(R.string.moderation_acknowledge)) }
    }
}

@Composable
internal fun ModerationRuleRow(
    rule: ModerationRuleResponse,
    enabled: Boolean,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(rule.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                rule.description.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.moderation_rule_summary, reportTargetLabel(rule.scope), riskActionLabel(rule.action), rule.hitThreshold),
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatPalette.current.textHint
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onEnabledChange, enabled = enabled)
    }
}

@Composable
internal fun ModerationRuleDialog(
    rule: ModerationRuleResponse,
    isUpdating: Boolean,
    onDismiss: () -> Unit,
    onSave: (action: String, hitThreshold: Int, windowMinutes: Long) -> Unit
) {
    var selectedAction by rememberSaveable(rule.id) { mutableStateOf(rule.action) }
    var threshold by rememberSaveable(rule.id) { mutableStateOf(rule.hitThreshold.coerceAtLeast(1).toString()) }
    var windowMinutes by rememberSaveable(rule.id) { mutableStateOf((rule.windowMs / 60_000L).coerceAtLeast(1).toString()) }
    val actions = listOf("WARN_MOD", "AUTO_RATE_LIMIT", "AUTO_HOLD", "AUTO_DELETE")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(rule.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rule.description?.let { Text(it, color = LocalChatPalette.current.textSecondary, style = MaterialTheme.typography.bodySmall) }
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    actions.forEach { action ->
                        TextButton(
                            onClick = { selectedAction = action },
                            modifier = Modifier.background(
                                if (selectedAction == action) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                        ) { Text(riskActionLabel(action), color = if (selectedAction == action) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary) }
                    }
                }
                OutlinedTextField(
                    value = threshold,
                    onValueChange = { threshold = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.moderation_hit_threshold)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = windowMinutes,
                    onValueChange = { windowMinutes = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.moderation_window_minutes)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isUpdating && threshold.toIntOrNull() != null && windowMinutes.toLongOrNull() != null,
                onClick = { onSave(selectedAction, threshold.toIntOrNull() ?: 1, windowMinutes.toLongOrNull() ?: 1L) }
            ) { Text(stringResource(R.string.common_save), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isUpdating) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) } }
    )
}

@Composable
internal fun riskActionLabel(action: String): String = when (action) {
    "WARN_MOD" -> stringResource(R.string.moderation_action_review)
    "AUTO_HOLD" -> stringResource(R.string.moderation_action_hold)
    "AUTO_DELETE" -> stringResource(R.string.moderation_action_delete)
    "AUTO_RATE_LIMIT" -> stringResource(R.string.moderation_action_rate_limit)
    "OBSERVED" -> stringResource(R.string.moderation_action_observed)
    else -> action
}

@Composable
internal fun reportTargetLabel(type: String): String = when (type) {
    "USER" -> stringResource(R.string.moderation_target_user)
    "MESSAGE" -> stringResource(R.string.moderation_target_message)
    "MESSAGE_META" -> stringResource(R.string.moderation_target_message_meta)
    "POST" -> stringResource(R.string.moderation_target_post)
    "COMMENT" -> stringResource(R.string.moderation_target_comment)
    "ALL" -> stringResource(R.string.moderation_target_all_public)
    else -> type
}
