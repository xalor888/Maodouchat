package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.PrimaryFixed
import com.maodouchat.security.MessageSafetyScanner
import java.util.Calendar
import java.util.Date

/**
 * 从 ChatDetailScreen.kt 拆分的纯工具函数和小型 Composable。
 * 这些函数不依赖 ChatDetailScreen 的局部状态，可在同包内复用。
 */

// ── 纯函数 ────────────────────────────

internal fun senderDisplayName(
    state: ChatDetailUiState,
    message: Message,
    isOwn: Boolean,
    participantNamesById: Map<String, String>
): String? {
    if (isOwn) return null
    if (!state.chatIsGroup) return state.contact.name
    return participantNamesById[message.senderId]
}

internal fun forwardTargetName(context: Context, chat: Chat, currentUserId: String): String {
    return if (chat.isGroup) {
        chat.groupName ?: context.resources.getQuantityString(R.plurals.chat_group_summary, chat.participants.size, chat.participants.size)
    } else {
        chat.participants.firstOrNull { it.id != currentUserId }?.displayName ?: context.getString(R.string.chat_private)
    }
}

internal fun formatDateLabel(context: Context, timestamp: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = Calendar.getInstance()
    return when {
        isSameDay(now, cal) -> context.getString(R.string.chat_today)
        isYesterday(now, cal) -> context.getString(R.string.chat_yesterday)
        else -> android.text.format.DateFormat.getMediumDateFormat(context).format(Date(timestamp))
    }
}

internal fun formatDateTime(context: Context, timestamp: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, context.resources.configuration.locales[0]).format(Date(timestamp))

internal fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

internal fun isYesterday(today: Calendar, other: Calendar): Boolean {
    val y = today.clone() as Calendar; y.add(Calendar.DAY_OF_YEAR, -1); return isSameDay(y, other)
}

// ── 小型 Composable ──────────────────

@Composable
internal fun AttachMenuItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onDisabledClick: () -> Unit = {}
) {
    val itemColor = if (enabled) Primary else Outline
    val itemAlpha = if (enabled) 0.2f else 0.08f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = if (enabled) onClick else onDisabledClick)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryFixed.copy(alpha = itemAlpha), RoundedCornerShape(12.dp))) {
            Icon(icon, contentDescription = label, tint = itemColor, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = itemColor)
    }
}

@Composable
internal fun messageSafetyWarning(text: String, enabled: Boolean): String? {
    if (!enabled) return null
    val findings = remember(text) { MessageSafetyScanner.scan(text) }
    if (findings.isEmpty()) return null
    val primary = findings.first()
    val detail = when (primary.code) {
        MessageSafetyScanner.CODE_SUSPICIOUS_LINK -> {
            val host = primary.matched?.takeIf { it.isNotBlank() }
            if (host == null) stringResource(R.string.chat_safety_suspicious_link)
            else stringResource(R.string.chat_safety_suspicious_link_host, host)
        }
        MessageSafetyScanner.CODE_PAYMENT_INDUCEMENT -> stringResource(R.string.chat_safety_payment)
        MessageSafetyScanner.CODE_IMPERSONATION -> stringResource(R.string.chat_safety_impersonation)
        MessageSafetyScanner.CODE_CREDENTIAL_REQUEST -> stringResource(R.string.chat_safety_credential)
        MessageSafetyScanner.CODE_SENSITIVE_DATA -> stringResource(R.string.chat_safety_sensitive_data)
        else -> stringResource(R.string.chat_safety_generic)
    }
    return stringResource(R.string.chat_safety_banner, detail)
}
