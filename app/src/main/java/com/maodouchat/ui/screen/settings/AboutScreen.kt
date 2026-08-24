package com.maodouchat.ui.screen.settings

import android.annotation.SuppressLint
import androidx.compose.foundation.background
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.update.AppUpdatePolicy
import com.maodouchat.update.OfficialApkInstaller
import kotlinx.coroutines.launch

/**
 * 关于 / 版本页 — 展示应用图标、名称、版本、版权与安全摘要。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串在点击/协程回调里读，非组合作用域
fun AboutScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0" }.getOrDefault("1.0.0")
    }
    val versionCode = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        }.getOrDefault(0)
    }
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var downloadUrl by remember { mutableStateOf<String?>(null) }
    // 9.209：关于页展示当前连接的服务器身份（第三方模式显示运营方名称）
    val serverIdentity by com.maodouchat.network.ServerIdentity.current.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            // 9.251：真 logo 替代「M」文字块（与登录页/启动图标一致）
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(22.dp))
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.about_version_format, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalChatPalette.current.textSecondary
            )
            Spacer(modifier = Modifier.height(32.dp))

            Column(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            ) {
                AboutRow(label = stringResource(R.string.about_version), value = versionName)
                HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                AboutRow(
                    label = stringResource(R.string.about_server),
                    value = if (com.maodouchat.network.ApiConfig.isUsingRuntimeServer) {
                        serverIdentity?.name?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.about_server_third_party)
                    } else {
                        stringResource(R.string.about_server_official)
                    }
                )
                HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                AboutRow(label = stringResource(R.string.about_privacy), value = stringResource(R.string.about_privacy_value))
                HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                AboutRow(label = stringResource(R.string.about_security), value = stringResource(R.string.about_security_value))
                HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.about_source_url)))
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.about_source))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            // 9.251：去「产品边界（摘要）」术语堆砌段——TG 式极简关于页：
            // logo/名称/版本/服务器 + 一行简介 + 版权
            Text(
                text = stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalChatPalette.current.textSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = {
                    if (checkingUpdate) return@TextButton
                    checkingUpdate = true
                    updateMessage = null
                    downloadUrl = null
                    scope.launch {
                        val result = ApiService.getPublicUpdates()
                        checkingUpdate = false
                        result.fold(
                            onSuccess = { remote ->
                                if (AppUpdatePolicy.shouldOfferUpdate(versionCode, remote.versionCode, remote.apkUrl)) {
                                    updateMessage = context.getString(
                                        R.string.about_update_available,
                                        remote.versionName.ifBlank { remote.versionCode.toString() }
                                    )
                                    downloadUrl = remote.apkUrl
                                } else {
                                    updateMessage = context.getString(R.string.about_update_latest)
                                }
                            },
                            onFailure = {
                                updateMessage = context.getString(R.string.about_update_failed)
                            }
                        )
                    }
                },
                enabled = !checkingUpdate && !downloadingUpdate
            ) {
                Text(stringResource(R.string.about_check_update))
            }
            updateMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
            }
            downloadUrl?.let { url ->
                TextButton(
                    onClick = {
                        if (downloadingUpdate) return@TextButton
                        downloadingUpdate = true
                        scope.launch {
                            val result = OfficialApkInstaller.downloadAndPromptInstall(
                                context = context,
                                apkUrl = url,
                                onProgress = { percent ->
                                    updateMessage = context.getString(R.string.about_update_downloading, percent)
                                },
                            )
                            downloadingUpdate = false
                            result.fold(
                                onSuccess = { },
                                onFailure = {
                                    updateMessage = context.getString(R.string.about_update_install_failed)
                                }
                            )
                        }
                    },
                    enabled = !downloadingUpdate
                ) {
                    Text(stringResource(R.string.about_update_download))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.about_copyright),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalChatPalette.current.textSecondary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
