package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed

/**
 * 会话详情页顶部横幅的安全簇：群加密警告、密聊、安全警告。
 *
 * 从 `ChatDetailBanners.kt` 纯搬移（同包 internal，调用方零改动）；不抓全局单例、不读数据库。
 */

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
