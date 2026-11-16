package com.maodouchat.ui.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.domain.messaging.DisappearCountdownKind
import com.maodouchat.domain.messaging.disappearCountdownKind
import com.maodouchat.domain.messaging.disappearCountdownRefreshDelayMs
import com.maodouchat.ui.theme.LocalSentBubbleContentSecondary
import com.maodouchat.ui.theme.Primary

/** 阅后即焚剩余时间文案；无期限或已过期返回 null。 */
@Composable
internal fun formatDisappearCountdown(expiresAt: Long?, nowMs: Long): String? {
    return when (val kind = disappearCountdownKind(expiresAt, nowMs)) {
        null -> null
        is DisappearCountdownKind.Seconds -> stringResource(R.string.disappear_countdown_s, kind.totalSec)
        is DisappearCountdownKind.Minutes -> stringResource(R.string.disappear_countdown_m, kind.minutes, kind.seconds)
        is DisappearCountdownKind.Hours -> stringResource(R.string.disappear_countdown_h, kind.hours, kind.minutes)
    }
}

@Composable
internal fun DisappearCountdownLabel(
    expiresAt: Long?,
    isOwnMessage: Boolean,
    modifier: Modifier = Modifier
) {
    if (expiresAt == null || expiresAt <= 0L) return
    var nowMs by remember(expiresAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        while (true) {
            nowMs = System.currentTimeMillis()
            val remaining = com.maodouchat.core.model.DisappearingMessagePolicy.remainingMs(expiresAt, nowMs)
            if (remaining <= 0L) break
            val delayMs = disappearCountdownRefreshDelayMs(remaining)
            kotlinx.coroutines.delay(delayMs)
        }
    }
    val label = formatDisappearCountdown(expiresAt, nowMs) ?: return
    Spacer(modifier = Modifier.width(4.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (isOwnMessage) LocalSentBubbleContentSecondary.current else Primary,
        modifier = modifier
    )
}
