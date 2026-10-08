package com.maodouchat.ui.screen.settings

import com.maodouchat.security.findActivity
import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.maodouchat.R
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 「通用」页（G102 从 `SettingsSubScreens.kt` 拆出，原 748 行）。
 *
 * 深色模式 + 缓存清理 + 关于 + 主题/外观/语言/壁纸/字号等一页聚合。
 * 行组件簇（`AccentColorRow` 等 13 个行组件 + `accentLabelRes` + `ThemeChoiceChip`
 * + `Context.findActivity()` 扩展）已于 2026-10-08 按簇拆至同包 `GeneralSettingsRows.kt`。
 *
 * **拆解约束**：不直接抓应用级数据库单例（`ui/` 红线，读库只能经 ViewModel/repository）。
 * 纯搬移，不改判断。
 */

/**
 * 「通用」页 — 深色模式 + 缓存清理 + 关于
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
fun GeneralSettingsScreen(
    onBack: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenWatermarkForensic: () -> Unit = {},
    onOpenDeveloperBots: () -> Unit = {},
    // 9.253：主题编辑器入口（TG 式高自定义 + .attheme 导入导出）
    onOpenThemeEditor: () -> Unit = {},
    onOpenThemeWorkbench: () -> Unit = {},
    viewModel: GeneralSettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }
    val appearanceVersion by com.maodouchat.util.ChatAppearancePreferences.appearanceVersion.collectAsState()
    var enterToSend by remember { mutableStateOf(com.maodouchat.util.ComposerPreferences.enterToSend(context)) }
    val bubbleColor = remember(appearanceVersion) {
        com.maodouchat.util.ChatAppearancePreferences.getBubbleColor(context)
    }
    val bubbleShape = remember(appearanceVersion) {
        com.maodouchat.util.ChatAppearancePreferences.getBubbleShape(context)
    }
    var customWallpaperUri by remember {
        mutableStateOf(com.maodouchat.util.ChatAppearancePreferences.getCustomWallpaperUri(context))
    }
    LaunchedEffect(state.infoMessage) {
        val message = state.infoMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.consumeInfoMessage()
    }
    val customWallpaperPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val stored = com.maodouchat.util.ChatAppearancePreferences.persistCustomWallpaper(context, uri.toString())
            if (stored != null) {
                customWallpaperUri = stored
                Toast.makeText(context, context.getString(R.string.general_custom_wallpaper_set), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.general_custom_wallpaper_set_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_general), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() }) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            ) {
                ThemeRow(currentTheme = state.themeMode, onThemeChange = viewModel::setThemeMode)
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))

                // 组件排版工作台（支持组件长按拖拽排序与预览）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenThemeWorkbench)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "组件排版工作台",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "支持组件拖拽编排与顺序自定义",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        tint = LocalChatPalette.current.textHint,
                        modifier = Modifier.size(18.dp)
                    )
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))

                // TG 式高级调色编辑器入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenThemeEditor)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.theme_editor_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.theme_editor_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        tint = LocalChatPalette.current.textHint,
                        modifier = Modifier.size(18.dp)
                    )
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                val floatingDockOn by com.maodouchat.util.ChromePreferences.floatingDock.collectAsState()
                val isDefaultTheme = true
                SwitchRow(
                    title = stringResource(R.string.general_floating_dock),
                    subtitle = stringResource(R.string.general_floating_dock_subtitle),
                    checked = if (isDefaultTheme) floatingDockOn else false,
                    enabled = isDefaultTheme,
                    onCheckedChange = { com.maodouchat.util.ChromePreferences.setFloatingDockEnabled(context, it) }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                AccentColorRow(
                    current = state.accentColor,
                    onChange = viewModel::setAccentColor
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                LanguageRow(
                    currentLanguage = state.languageMode,
                    onLanguageChange = { mode ->
                        viewModel.setLanguageMode(mode)
                        // 预 Android 13：AppLocaleManager.setMode 收到的是 Application 上下文，
                        // (context as? Activity)?.recreate() 不会触发，必须在此用 Activity 上下文重建
                        // 才能使 attachBaseContext 的 wrap() 重新套用语言，否则需重启 App 才生效。
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            context.findActivity()?.recreate()
                        }
                    }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                LinkPreviewSwitchRow(
                    enabled = state.linkPreviewEnabled,
                    onEnabledChange = viewModel::setLinkPreviewEnabled
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                UnreadPrioritySwitchRow(
                    enabled = state.unreadPriorityEnabled,
                    onEnabledChange = viewModel::setUnreadPriorityEnabled
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                // 1.175：回车发送（本地偏好；set 在无 userId 时是 no-op，需回读后才切 UI）
                EnterToSendSwitchRow(
                    enabled = enterToSend,
                    onEnabledChange = { next ->
                        com.maodouchat.util.ComposerPreferences.setEnterToSend(context, next)
                        enterToSend = com.maodouchat.util.ComposerPreferences.enterToSend(context)
                    }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                // 1.89：媒体自动下载（仅 Wi-Fi / 始终 / 关闭）
                MediaAutoDownloadRow(
                    currentMode = state.mediaAutoDownloadMode,
                    onModeChange = viewModel::setMediaAutoDownloadMode
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ChatWallpaperRow(
                    current = state.chatWallpaper,
                    onChange = viewModel::setChatWallpaper,
                    customWallpaperUri = customWallpaperUri,
                    onPickCustomWallpaper = {
                        customWallpaperPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onClearCustomWallpaper = {
                        com.maodouchat.util.ChatAppearancePreferences.clearCustomWallpaperUri(context)
                        customWallpaperUri = null
                        Toast.makeText(context, context.getString(R.string.general_custom_wallpaper_cleared), Toast.LENGTH_SHORT).show()
                    }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ChatBubbleColorRow(
                    current = bubbleColor,
                    onChange = { id ->
                        com.maodouchat.util.ChatAppearancePreferences.setBubbleColor(context, id)
                    }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ChatBubbleShapeRow(
                    current = bubbleShape,
                    onChange = { id ->
                        com.maodouchat.util.ChatAppearancePreferences.setBubbleShape(context, id)
                    }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ChatFontScaleRow(
                    current = state.chatFontScale,
                    onChange = viewModel::setChatFontScale
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ActionRow(label = stringResource(R.string.general_clear_cache), subtitle = stringResource(R.string.general_cache_summary, state.cacheSizeText)) {
                    showClearConfirm = true
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ActionRow(label = stringResource(R.string.general_about), subtitle = stringResource(R.string.general_about_summary)) {
                    onOpenAbout()
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ActionRow(label = stringResource(R.string.watermark_forensic_entry), subtitle = stringResource(R.string.watermark_forensic_desc)) {
                    onOpenWatermarkForensic()
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ActionRow(label = stringResource(R.string.developer_bots_entry), subtitle = stringResource(R.string.developer_bots_desc)) {
                    onOpenDeveloperBots()
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.general_clear_cache)) },
            text = { Text(stringResource(R.string.general_clear_cache_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearCache()
                }) { Text(stringResource(R.string.common_clear), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) } }
        )
    }
}

@Composable
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
private fun SettingsSubScreensPreview() {
    MaodouchatTheme { GeneralSettingsScreen(onBack = {}) }
}
