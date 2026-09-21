package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip

/**
 * 「服务器」页（G103 从 `SettingsSubScreens.kt` 拆出，原 308 行）。
 *
 * 运行时配置 API 服务器地址（8.45，免重新构建 APK）。含 `ThirdPartyServerCard`。
 *
 * 部署方安装通用 APK 后，在此填写自建服务器地址即可使用。服务器属于独立信任域；
 * 切换时会清理当前账号凭据和本机加密数据，再要求使用目标服务器账号登录。
 *
 * **拆解约束**：不直接抓应用级数据库单例（`ui/` 红线，读库只能经 ViewModel/repository）；
 * 切换服务器后需要 `rebuildImageLoader` / `disconnectRealtime` 等进程级副作用，
 * 这部分经应用实例调用，属既有形态，搬移时不改。
 */

/**
 * 「服务器」页 — 运行时配置 API 服务器地址（8.45，免重新构建 APK）。
 *
 * 部署方安装通用 APK 后，在此填写自建服务器地址即可使用。服务器属于独立信任域；
 * 切换时会清理当前账号凭据和本机加密数据，再要求使用目标服务器账号登录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    onBack: () -> Unit = {},
    onServerChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentBase = remember { com.maodouchat.network.ApiConfig.BASE_URL }
    val currentWs = remember { com.maodouchat.network.ApiConfig.WS_URL }
    var input by remember { mutableStateOf(currentBase) }
    var result by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val serverSavedText = stringResource(R.string.settings_server_saved)
    val serverTestingText = stringResource(R.string.settings_server_testing)
    val serverTestSuccessText = stringResource(R.string.settings_server_test_success)
    val serverTestFailedText = stringResource(R.string.settings_server_test_failed)
    val serverResetDoneText = stringResource(R.string.settings_server_reset_done)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_server), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(8.dp))
            // 9.202：第三方服务器模式身份卡（名称/简介/公告/版本）
            if (com.maodouchat.network.ApiConfig.isUsingRuntimeServer) {
                ThirdPartyServerCard()
                Spacer(modifier = Modifier.height(12.dp))
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            ) {
                Text(
                    text = stringResource(R.string.settings_server_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.padding(16.dp)
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_server_current),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = currentBase,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (com.maodouchat.network.ApiConfig.isUsingRuntimeServer) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textHint
                    )
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 16.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.settings_server_input_label)) },
                    placeholder = { Text("https://chat.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(
                    onClick = {
                        if (isWorking) return@TextButton
                        isWorking = true
                        result = serverTestingText
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                com.maodouchat.network.ApiConfig.testConnection(input)
                            }
                            result = if (ok) serverTestSuccessText else serverTestFailedText
                            isWorking = false
                        }
                    },
                    enabled = !isWorking,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.settings_server_test))
                }

                Button(
                    onClick = {
                        if (isWorking) return@Button
                        val validationError = com.maodouchat.network.ApiConfig.validateServerAddress(input, context)
                        if (validationError != null) {
                            result = validationError
                            return@Button
                        }
                        isWorking = true
                        result = serverTestingText
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                com.maodouchat.network.ApiConfig.testConnection(input)
                            }
                            if (!ok) {
                                result = serverTestFailedText
                                isWorking = false
                            } else {
                                when (val change = com.maodouchat.network.ApiConfig.switchServer(input, context)) {
                                    is com.maodouchat.network.ApiConfig.ServerChangeResult.Failed -> {
                                        result = change.message
                                    }
                                    com.maodouchat.network.ApiConfig.ServerChangeResult.Unchanged -> {
                                        result = serverTestSuccessText
                                    }
                                    com.maodouchat.network.ApiConfig.ServerChangeResult.Changed -> {
                                        result = serverSavedText
                                        com.maodouchat.MaodouchatApp.instance.rebuildImageLoader()
                                        com.maodouchat.MaodouchatApp.instance.disconnectRealtime()
                                        com.maodouchat.slim.OnDemandStickerStore.invalidateServerState()
                                        com.maodouchat.network.ServerIdentity.refreshAsync()
                                        onServerChanged()
                                    }
                                }
                                isWorking = false
                            }
                        }
                    },
                    enabled = !isWorking,
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text(stringResource(R.string.settings_server_save))
                }
            }

            Text(
                text = result ?: stringResource(R.string.settings_server_ws_hint),
                style = MaterialTheme.typography.bodySmall,
                color = result?.let {
                    if (it == serverSavedText || it == serverTestSuccessText || it == serverTestingText) MaterialTheme.colorScheme.primary else Error
                } ?: LocalChatPalette.current.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            ) {
                ActionRow(label = stringResource(R.string.settings_server_reset), subtitle = stringResource(R.string.settings_server_reset_subtitle)) {
                    showResetConfirm = true
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.settings_server_reset)) },
            text = { Text(stringResource(R.string.settings_server_reset_confirm)) },
            confirmButton = {
                TextButton(enabled = !isWorking, onClick = {
                    showResetConfirm = false
                    isWorking = true
                    scope.launch {
                        when (val change = com.maodouchat.network.ApiConfig.resetToDefault(context)) {
                            is com.maodouchat.network.ApiConfig.ServerChangeResult.Failed -> {
                                result = change.message
                            }
                            com.maodouchat.network.ApiConfig.ServerChangeResult.Unchanged -> {
                                input = com.maodouchat.network.ApiConfig.BASE_URL
                                result = serverResetDoneText
                            }
                            com.maodouchat.network.ApiConfig.ServerChangeResult.Changed -> {
                                input = com.maodouchat.network.ApiConfig.BASE_URL
                                result = serverResetDoneText
                                com.maodouchat.MaodouchatApp.instance.rebuildImageLoader()
                                com.maodouchat.MaodouchatApp.instance.disconnectRealtime()
                                com.maodouchat.slim.OnDemandStickerStore.invalidateServerState()
                                com.maodouchat.network.ServerIdentity.clear()
                                onServerChanged()
                            }
                        }
                        isWorking = false
                    }
                }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * 9.202：第三方服务器身份卡：展示当前连接服务器的名称/简介/版本与运营方公告。
 * 官方默认服务器不展示此卡片。
 */
@Composable
private fun ThirdPartyServerCard() {
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
