package com.maodouchat.ui.screen.settings

import com.maodouchat.security.findActivity
import android.app.Activity
import android.annotation.SuppressLint
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.maodouchat.R
import com.maodouchat.util.AppLocaleManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.LocalChatPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 「通用」页（G102 从 `SettingsSubScreens.kt` 拆出，原 748 行）。
 *
 * 深色模式 + 缓存清理 + 关于 + 主题/外观/语言/壁纸/字号等一页聚合。
 * 含它专属的一批行组件（`AccentColorRow` / `ChatBubbleShapeRow` / `ThemeRow` /
 * `OledBlackRow` / `NightWindowRow` / `LanguageRow` / `LinkPreviewSwitchRow` /
 * `UnreadPrioritySwitchRow` / `EnterToSendSwitchRow` / `MediaAutoDownloadRow` /
 * `ChatWallpaperRow` / `ChatBubbleColorRow` / `ChatFontScaleRow` / `ThemeChoiceChip`）
 * 与 `Context.findActivity()` 扩展。
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
            title = { Text(stringResource(R.string.settings_general), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
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

/**
 * 9.205：强调色选择行（TG 式）：默认（跟随主题）+ 7 色圆点，选中态带描边环。
 */
@Composable
private fun AccentColorRow(
    current: String,
    onChange: (String) -> Unit
) {
    val isDark = com.maodouchat.ui.theme.LocalDarkTheme.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.general_accent_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(2.dp))
        Text(stringResource(R.string.general_accent_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 默认（跟随主题）选项
            val defaultSelected = current == "none"
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(
                        if (defaultSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    )
                    .clickable { onChange("none") },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.general_accent_default_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            com.maodouchat.ui.theme.ACCENT_OPTIONS.forEach { option ->
                val selected = current == option.id
                val color = if (isDark) option.dark else option.light
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(
                            if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                        .clickable { onChange(option.id) }
                )
            }
        }
    }
}

/**
 * 9.205：气泡圆角风格选择（默认尾角小圆角 / TG 全圆 / 大圆角），按账号本地存储。
 */
@Composable
private fun ChatBubbleShapeRow(
    current: String,
    onChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.general_bubble_shape_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoiceChip(stringResource(R.string.general_bubble_shape_default), selected = current == "default", onClick = { onChange("default") })
            ThemeChoiceChip(stringResource(R.string.general_bubble_shape_tg), selected = current == "tg", onClick = { onChange("tg") })
            ThemeChoiceChip(stringResource(R.string.general_bubble_shape_round), selected = current == "round", onClick = { onChange("round") })
        }
    }
}

@Composable
private fun ThemeRow(currentTheme: String, onThemeChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.general_theme_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoiceChip(stringResource(R.string.general_theme_system), selected = currentTheme == "system", onClick = { onThemeChange("system") })
            ThemeChoiceChip(stringResource(R.string.general_theme_light), selected = currentTheme == "light", onClick = { onThemeChange("light") })
            ThemeChoiceChip(stringResource(R.string.general_theme_dark), selected = currentTheme == "dark", onClick = { onThemeChange("dark") })
            ThemeChoiceChip(stringResource(R.string.general_theme_scheduled), selected = currentTheme == "scheduled", onClick = { onThemeChange("scheduled") })
        }
        // 9.211：定时深色（TG 式）——仅 scheduled 模式显示时段设置
        if (currentTheme == "scheduled") {
            Spacer(modifier = Modifier.height(10.dp))
            NightWindowRow()
        }
        // 9.258：OLED 纯黑（TG Amoled Black 式）——深色可能生效的模式下显示
        if (currentTheme == "dark" || currentTheme == "system" || currentTheme == "scheduled") {
            Spacer(modifier = Modifier.height(10.dp))
            OledBlackRow()
        }
    }
}

/**
 * 9.258：OLED 纯黑开关行（深色模式下背景纯黑，OLED 屏省电）。
 */
@Composable
private fun OledBlackRow() {
    val context = LocalContext.current
    val oledBlack by com.maodouchat.util.ThemePreferences.oledBlack.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.general_oled_black_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                stringResource(R.string.general_oled_black_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatPalette.current.textSecondary
            )
        }
        androidx.compose.material3.Switch(
            checked = oledBlack,
            onCheckedChange = { com.maodouchat.util.ThemePreferences.setOledBlack(context, it) }
        )
    }
}

/**
 * 9.211：夜间时段选择行（开始/结束整点，支持跨午夜）。设备本地存储。
 */
@Composable
private fun NightWindowRow() {
    val context = LocalContext.current
    val nightStart by com.maodouchat.util.ThemePreferences.nightStart.collectAsState()
    val nightEnd by com.maodouchat.util.ThemePreferences.nightEnd.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }
    fun formatMinutes(minutes: Int): String = String.format("%02d:00", minutes / 60)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.general_night_window_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { editing = "start" }) {
            Text(formatMinutes(nightStart), color = MaterialTheme.colorScheme.primary)
        }
        Text("→", style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
        TextButton(onClick = { editing = "end" }) {
            Text(formatMinutes(nightEnd), color = MaterialTheme.colorScheme.primary)
        }
    }
    if (editing != null) {
        val isStart = editing == "start"
        AlertDialog(
            onDismissRequest = { editing = null },
            title = {
                Text(
                    stringResource(if (isStart) R.string.general_night_start else R.string.general_night_end),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(modifier = Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
                    for (hour in 0..23) {
                        TextButton(
                            onClick = {
                                if (isStart) {
                                    com.maodouchat.util.ThemePreferences.setNightWindow(context, hour * 60, nightEnd)
                                } else {
                                    com.maodouchat.util.ThemePreferences.setNightWindow(context, nightStart, hour * 60)
                                }
                                editing = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                String.format("%02d:00", hour),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) }
            }
        )
    }
}

private fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun LanguageRow(currentLanguage: String, onLanguageChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.general_language_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoiceChip(stringResource(R.string.general_language_system), selected = currentLanguage == AppLocaleManager.MODE_SYSTEM, onClick = { onLanguageChange(AppLocaleManager.MODE_SYSTEM) })
            ThemeChoiceChip(stringResource(R.string.general_language_chinese), selected = currentLanguage == AppLocaleManager.MODE_CHINESE, onClick = { onLanguageChange(AppLocaleManager.MODE_CHINESE) })
            ThemeChoiceChip(stringResource(R.string.general_language_english), selected = currentLanguage == AppLocaleManager.MODE_ENGLISH, onClick = { onLanguageChange(AppLocaleManager.MODE_ENGLISH) })
        }
    }
}

@Composable
private fun LinkPreviewSwitchRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.general_link_preview_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                stringResource(R.string.general_link_preview_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

@Composable
private fun UnreadPrioritySwitchRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.general_unread_priority_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                stringResource(R.string.general_unread_priority_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

// 1.175：回车发送
@Composable
private fun EnterToSendSwitchRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.general_enter_to_send_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                stringResource(R.string.general_enter_to_send_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

// 1.89：媒体自动下载档位（仅 Wi-Fi / 始终 / 关闭）
@Composable
private fun MediaAutoDownloadRow(
    currentMode: String,
    onModeChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            stringResource(R.string.general_media_auto_download_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            stringResource(R.string.general_media_auto_download_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoiceChip(
                stringResource(R.string.general_media_auto_download_wifi),
                selected = currentMode == com.maodouchat.util.MediaAutoDownloadPreferences.MODE_WIFI_ONLY,
                onClick = { onModeChange(com.maodouchat.util.MediaAutoDownloadPreferences.MODE_WIFI_ONLY) }
            )
            ThemeChoiceChip(
                stringResource(R.string.general_media_auto_download_always),
                selected = currentMode == com.maodouchat.util.MediaAutoDownloadPreferences.MODE_ALWAYS,
                onClick = { onModeChange(com.maodouchat.util.MediaAutoDownloadPreferences.MODE_ALWAYS) }
            )
            ThemeChoiceChip(
                stringResource(R.string.general_media_auto_download_off),
                selected = currentMode == com.maodouchat.util.MediaAutoDownloadPreferences.MODE_OFF,
                onClick = { onModeChange(com.maodouchat.util.MediaAutoDownloadPreferences.MODE_OFF) }
            )
        }
    }
}

@Composable
private fun ChatWallpaperRow(
    current: String,
    onChange: (String) -> Unit,
    customWallpaperUri: String? = null,
    onPickCustomWallpaper: () -> Unit = {},
    onClearCustomWallpaper: () -> Unit = {}
) {
    val options = listOf(
        com.maodouchat.util.ChatWallpaperPreset.DEFAULT.id to stringResource(R.string.general_chat_wallpaper_default),
        com.maodouchat.util.ChatWallpaperPreset.MINT.id to stringResource(R.string.general_chat_wallpaper_mint),
        com.maodouchat.util.ChatWallpaperPreset.LAVENDER.id to stringResource(R.string.general_chat_wallpaper_lavender),
        com.maodouchat.util.ChatWallpaperPreset.SAND.id to stringResource(R.string.general_chat_wallpaper_sand),
        com.maodouchat.util.ChatWallpaperPreset.NIGHT.id to stringResource(R.string.general_chat_wallpaper_night),
        com.maodouchat.util.ChatWallpaperPreset.ROSE.id to stringResource(R.string.general_chat_wallpaper_rose),
        com.maodouchat.util.ChatWallpaperPreset.SKY.id to stringResource(R.string.general_chat_wallpaper_sky),
        com.maodouchat.util.ChatWallpaperPreset.SLATE.id to stringResource(R.string.general_chat_wallpaper_slate),
        com.maodouchat.util.ChatWallpaperPreset.PEACH.id to stringResource(R.string.general_chat_wallpaper_peach),
        com.maodouchat.util.ChatWallpaperPreset.OLIVE.id to stringResource(R.string.general_chat_wallpaper_olive),
        com.maodouchat.util.ChatWallpaperPreset.CORAL.id to stringResource(R.string.general_chat_wallpaper_coral),
        com.maodouchat.util.ChatWallpaperPreset.PLUM.id to stringResource(R.string.general_chat_wallpaper_plum),
        com.maodouchat.util.ChatWallpaperPreset.INDIGO.id to stringResource(R.string.general_chat_wallpaper_indigo),
        com.maodouchat.util.ChatWallpaperPreset.AMBER.id to stringResource(R.string.general_chat_wallpaper_amber),
            com.maodouchat.util.ChatWallpaperPreset.TEAL.id to stringResource(R.string.general_chat_wallpaper_teal),
            com.maodouchat.util.ChatWallpaperPreset.GRAPHITE.id to stringResource(R.string.general_chat_wallpaper_graphite),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.general_chat_wallpaper_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(2.dp))
        Text(stringResource(R.string.general_chat_wallpaper_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            options.forEach { (id, label) ->
                ThemeChoiceChip(label, selected = current == id, onClick = { onChange(id) })
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoiceChip(
                stringResource(if (customWallpaperUri != null) R.string.general_custom_wallpaper_active else R.string.general_custom_wallpaper),
                selected = customWallpaperUri != null,
                onClick = onPickCustomWallpaper
            )
            if (customWallpaperUri != null) {
                TextButton(onClick = onClearCustomWallpaper) {
                    Text(stringResource(R.string.general_custom_wallpaper_clear), color = LocalChatPalette.current.unreadRed)
                }
            }
        }
        Text(
            stringResource(R.string.general_custom_wallpaper_hint),
            style = MaterialTheme.typography.labelSmall,
            color = LocalChatPalette.current.textHint
        )
    }
}

@Composable
private fun ChatBubbleColorRow(
    current: String,
    onChange: (String) -> Unit
) {
    val options = listOf(
        com.maodouchat.ui.theme.ChatBubbleColorPalette.BLUE to stringResource(R.string.general_chat_bubble_blue),
        com.maodouchat.ui.theme.ChatBubbleColorPalette.GREEN to stringResource(R.string.general_chat_bubble_green),
        com.maodouchat.ui.theme.ChatBubbleColorPalette.PURPLE to stringResource(R.string.general_chat_bubble_purple),
        com.maodouchat.ui.theme.ChatBubbleColorPalette.ORANGE to stringResource(R.string.general_chat_bubble_orange),
        com.maodouchat.ui.theme.ChatBubbleColorPalette.PINK to stringResource(R.string.general_chat_bubble_pink),
        com.maodouchat.ui.theme.ChatBubbleColorPalette.TEAL to stringResource(R.string.general_chat_bubble_teal)
    )
    val isDark = com.maodouchat.ui.theme.LocalDarkTheme.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.general_chat_bubble_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(2.dp))
        Text(stringResource(R.string.general_chat_bubble_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (id, label) ->
                val color = if (isDark) com.maodouchat.ui.theme.ChatBubbleColorPalette.dark(id)
                else com.maodouchat.ui.theme.ChatBubbleColorPalette.light(id)
                ThemeChoiceChip(
                    label = label,
                    selected = current == id,
                    onClick = { onChange(id) }
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatFontScaleRow(
    current: String,
    onChange: (String) -> Unit
) {
    val options = listOf(
        com.maodouchat.util.ChatFontScale.SMALL.id to stringResource(R.string.general_chat_font_small),
        com.maodouchat.util.ChatFontScale.NORMAL.id to stringResource(R.string.general_chat_font_normal),
        com.maodouchat.util.ChatFontScale.LARGE.id to stringResource(R.string.general_chat_font_large),
        com.maodouchat.util.ChatFontScale.XLARGE.id to stringResource(R.string.general_chat_font_xlarge),
        com.maodouchat.util.ChatFontScale.XXLARGE.id to stringResource(R.string.general_chat_font_xxlarge),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.general_chat_font_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(2.dp))
        Text(stringResource(R.string.general_chat_font_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (id, label) ->
                ThemeChoiceChip(label, selected = current == id, onClick = { onChange(id) })
            }
        }
    }
}

@Composable
private fun ThemeChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    val backgroundColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else LocalChatPalette.current.chatInputBackground, tween(180), label = "choiceBackground")
    val textColor by animateColorAsState(if (selected) Color.White else MaterialTheme.colorScheme.onSurface, tween(180), label = "choiceText")
    val scale by animateFloatAsState(if (selected) 1f else 0.98f, tween(180), label = "choiceScale")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(backgroundColor, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clickable { onClick() }
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(label, color = textColor, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
private fun SettingsSubScreensPreview() {
    MaodouchatTheme { GeneralSettingsScreen(onBack = {}) }
}
