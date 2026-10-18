package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import kotlinx.coroutines.launch

// 贴纸包管理对话框：内置包开关 + 远程包按需拉取/下载（纯搬移，判定逐字保留）。
@Composable
internal fun StickerPackManagerDialog(
    stickerState: ExpressionStickerState,
) {
    val context = LocalContext.current
    var stickerTab by stickerState.stickerTab
    var enabledPackIds by stickerState.enabledPackIds
    var showPackManager by stickerState.showPackManager
    // 远程贴纸包（OnDemandStickerStore）：按需拉取清单 + 下载，B1 包体瘦身闭环
    var remotePacks by remember { mutableStateOf<List<String>>(emptyList()) }
    var remoteStatus by remember { mutableStateOf<String?>(null) }
    var remoteDownloading by remember { mutableStateOf<String?>(null) }
    val remoteScope = rememberCoroutineScope()
    val remoteContext = context
    fun refreshRemotePacks() {
        remoteScope.launch {
            remoteStatus = null
            com.maodouchat.slim.OnDemandStickerStore.refreshManifest(remoteContext)
                .onSuccess { remotePacks = it }
                .onFailure { remoteStatus = remoteContext.getString(R.string.sticker_store_download_failed) }
        }
    }
    fun downloadRemotePack(packId: String) {
        if (remoteDownloading != null) return
        remoteScope.launch {
            remoteDownloading = packId
            remoteStatus = null
            val result = com.maodouchat.slim.OnDemandStickerStore.ensurePack(remoteContext, packId)
            remoteStatus = result.message
            remoteDownloading = null
        }
    }
    AlertDialog(
        onDismissRequest = { showPackManager = false },
        title = { Text(stringResource(R.string.sticker_pack_manage)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())
            ) {
                com.maodouchat.util.StickerCatalog.BUILT_IN_PACKS.forEach { pack ->
                    val enabled = pack.id in enabledPackIds
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = com.maodouchat.util.StickerPolicy.togglePackEnabled(
                                    enabledIds = enabledPackIds,
                                    packId = pack.id,
                                    enable = !enabled
                                )
                                enabledPackIds = next
                                com.maodouchat.util.StickerPreferences.setEnabledPackIds(context, next)
                                if (!enabled && stickerTab == pack.id) {
                                    stickerTab = com.maodouchat.util.StickerCatalog.PACK_RECENT
                                }
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = stickerPackLabel(pack.nameKey),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(if (enabled) R.string.sticker_pack_enabled else R.string.sticker_pack_disabled),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (enabled) Primary else TextSecondary
                        )
                    }
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.textHint.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.sticker_store_remote_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { refreshRemotePacks() }) {
                        Text(stringResource(R.string.sticker_store_remote_refresh))
                    }
                }
                if (remoteStatus != null) {
                    Text(
                        remoteStatus.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                }
                if (remotePacks.isEmpty() && remoteStatus == null) {
                    Text(
                        stringResource(R.string.sticker_store_remote_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                remotePacks.forEach { packId ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            packId,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (remoteDownloading == packId) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            TextButton(onClick = { downloadRemotePack(packId) }) {
                                Text(stringResource(R.string.sticker_store_remote_download))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showPackManager = false }) {
                Text(stringResource(R.string.common_done))
            }
        }
    )
}
