package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
    participantNamesById: Map<String, String>,
    unknownLabel: String = "",
    groupMemberLabel: String = "",
): String? {
    if (isOwn) return null
    val mapped = participantNamesById[message.senderId]?.trim()?.takeIf { it.isNotEmpty() }
    val truncatedId = truncatedSenderId(message.senderId)
    if (!state.chatIsGroup) {
        return firstNonBlank(
            state.contact.displayName,
            mapped,
            truncatedId,
            unknownLabel,
        )
    }
    return firstNonBlank(
        mapped,
        truncatedId,
        groupMemberLabel,
        unknownLabel,
    )
}

internal fun truncatedSenderId(senderId: String): String? {
    val id = senderId.trim()
    if (id.isEmpty()) return null
    return if (id.length <= 8) id else id.take(8)
}

private fun firstNonBlank(vararg values: String?): String? {
    for (value in values) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isNotEmpty()) return trimmed
    }
    return null
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
    val itemColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    val itemAlpha = if (enabled) 0.10f else 0.05f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .clickable(onClick = if (enabled) onClick else onDisabledClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = itemAlpha), RoundedCornerShape(12.dp))
        ) {
            Icon(icon, contentDescription = label, tint = itemColor, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = itemColor,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun messageSafetyWarning(text: String, enabled: Boolean): String? {
    if (!enabled) return null
    val findings = remember(text) { MessageSafetyScanner.scan(text) }
    if (findings.isEmpty()) return null
    val primary = findings.first()
    // Android 的 getString(id, vararg) 忽略多余实参，所以统一把 host 传下去，
    // 只有「可疑链接（带主机）」那一支真的用到它。
    val (detailRes, host) = safetyDetailText(primary.code, primary.matched)
    val detail = if (host == null) stringResource(detailRes) else stringResource(detailRes, host)
    return stringResource(R.string.chat_safety_banner, detail)
}

/**
 * 安全告警码 → 详情文案（G162 从 `messageSafetyWarning` 抽出的纯映射）。
 *
 * 抽开之前这段 `when` 和 `stringResource` 耦在一起，只能靠仪器测试覆盖；
 * 现在返回 (资源 id, 是否带 host 实参) 二元组，普通 JVM 单测就能逐分支断言。
 *
 * `CODE_SUSPICIOUS_LINK` 按 `matched` 是否空白二选一：有主机时文案带上主机名，
 * 用户才知道是哪个链接可疑——漏了 host 会变成一句泛泛的「请谨慎点击」。
 */
internal fun safetyDetailText(code: String, matched: String?): Pair<Int, String?> =
    when (code) {
        MessageSafetyScanner.CODE_SUSPICIOUS_LINK ->
            if (matched.isNullOrBlank()) {
                R.string.chat_safety_suspicious_link to null
            } else {
                R.string.chat_safety_suspicious_link_host to matched
            }
        MessageSafetyScanner.CODE_PAYMENT_INDUCEMENT -> R.string.chat_safety_payment to null
        MessageSafetyScanner.CODE_IMPERSONATION -> R.string.chat_safety_impersonation to null
        MessageSafetyScanner.CODE_CREDENTIAL_REQUEST -> R.string.chat_safety_credential to null
        MessageSafetyScanner.CODE_SENSITIVE_DATA -> R.string.chat_safety_sensitive_data to null
        else -> R.string.chat_safety_generic to null
    }
