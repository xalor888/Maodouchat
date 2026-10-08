package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** 第三方服务器身份卡，从 SettingsServer.kt 按簇搬出。 */

@Composable
internal fun ThirdPartyServerCard() {
    val info by com.maodouchat.network.ServerIdentity.current.collectAsState()
    LaunchedEffect(Unit) {
        com.maodouchat.network.ServerIdentity.refresh()
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = Icons.Outlined.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.settings_server_third_party_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.settings_server_third_party_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_server_third_party_desc),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
        val serverInfo = info
        if (serverInfo != null) {
            Spacer(modifier = Modifier.height(10.dp))
            androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = serverInfo.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (serverInfo.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = serverInfo.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
            }
            if (serverInfo.version.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_server_info_version, serverInfo.version),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
            if (serverInfo.announcement.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_server_info_announcement),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = serverInfo.announcement,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
            }
        }
    }
}
