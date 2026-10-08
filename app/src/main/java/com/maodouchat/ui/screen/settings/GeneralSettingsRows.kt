package com.maodouchat.ui.screen.settings

import com.maodouchat.security.findActivity
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.util.AppLocaleManager
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 「通用」页的行组件簇（2026-10-08 从 `SettingsGeneral.kt` 按簇拆出，主屏留在原文件）。
 *
 * 纯搬移：函数体、签名（`private`→`internal`，同包引用）零改动。
 */

/**
 * 9.205：强调色选择行（TG 式）：默认（跟随主题）+ 7 色圆点，选中态带描边环。
 */
@Composable
internal fun AccentColorRow(
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
                val accentLabel = stringResource(accentLabelRes(option.id))
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(
                            if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                        // 纯色圆点没有文字——挂本地化颜色名，否则 TalkBack 读「按钮，未命名」。
                        .semantics { contentDescription = accentLabel }
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
internal fun ChatBubbleShapeRow(
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
internal fun ThemeRow(currentTheme: String, onThemeChange: (String) -> Unit) {
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
internal fun OledBlackRow() {
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
        val oledLabel = stringResource(R.string.general_oled_black_title)
        androidx.compose.material3.Switch(
            checked = oledBlack,
            onCheckedChange = { com.maodouchat.util.ThemePreferences.setOledBlack(context, it) },
            // 只有开关本身可点——挂行标题，避免 TalkBack 读「开关」不带名字。
            modifier = Modifier.semantics { contentDescription = oledLabel }
        )
    }
}

/**
 * 9.211：夜间时段选择行（开始/结束整点，支持跨午夜）。设备本地存储。
 */
@Composable
internal fun NightWindowRow() {
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

internal fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
internal fun LanguageRow(currentLanguage: String, onLanguageChange: (String) -> Unit) {
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
internal fun LinkPreviewSwitchRow(
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
        val switchLabel = stringResource(R.string.general_link_preview_title)
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            // 只有开关本身可点——挂行标题，避免 TalkBack 读「开关」不带名字。
            modifier = Modifier.semantics { contentDescription = switchLabel }
        )
    }
}

@Composable
internal fun UnreadPrioritySwitchRow(
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
        val switchLabel = stringResource(R.string.general_unread_priority_title)
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            // 只有开关本身可点——挂行标题，避免 TalkBack 读「开关」不带名字。
            modifier = Modifier.semantics { contentDescription = switchLabel }
        )
    }
}

// 1.175：回车发送
@Composable
internal fun EnterToSendSwitchRow(
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
        val switchLabel = stringResource(R.string.general_enter_to_send_title)
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            // 只有开关本身可点——挂行标题，避免 TalkBack 读「开关」不带名字。
            modifier = Modifier.semantics { contentDescription = switchLabel }
        )
    }
}

// 1.89：媒体自动下载档位（仅 Wi-Fi / 始终 / 关闭）
@Composable
internal fun MediaAutoDownloadRow(
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
internal fun ChatWallpaperRow(
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
internal fun ChatBubbleColorRow(
    current: String,
    onChange: (String) -> Unit
) {
    val options = listOf(
        com.maodouchat.theme.ChatBubbleColorPalette.BLUE to stringResource(R.string.general_chat_bubble_blue),
        com.maodouchat.theme.ChatBubbleColorPalette.GREEN to stringResource(R.string.general_chat_bubble_green),
        com.maodouchat.theme.ChatBubbleColorPalette.PURPLE to stringResource(R.string.general_chat_bubble_purple),
        com.maodouchat.theme.ChatBubbleColorPalette.ORANGE to stringResource(R.string.general_chat_bubble_orange),
        com.maodouchat.theme.ChatBubbleColorPalette.PINK to stringResource(R.string.general_chat_bubble_pink),
        com.maodouchat.theme.ChatBubbleColorPalette.TEAL to stringResource(R.string.general_chat_bubble_teal)
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
                val color = if (isDark) com.maodouchat.theme.ChatBubbleColorPalette.dark(id)
                else com.maodouchat.theme.ChatBubbleColorPalette.light(id)
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
internal fun ChatFontScaleRow(
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

/** 强调色 id → 本地化颜色名（无障碍朗读用）。 */
internal fun accentLabelRes(id: String): Int = when (id) {
    "blue" -> R.string.general_accent_blue
    "green" -> R.string.general_accent_green
    "purple" -> R.string.general_accent_purple
    "orange" -> R.string.general_accent_orange
    "pink" -> R.string.general_accent_pink
    "red" -> R.string.general_accent_red
    else -> R.string.general_accent_teal
}

@Composable
internal fun ThemeChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    val backgroundColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else LocalChatPalette.current.chatInputBackground, tween(180), label = "choiceBackground")
    val textColor by animateColorAsState(if (selected) Color.White else MaterialTheme.colorScheme.onSurface, tween(180), label = "choiceText")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // 触控目标下限 48dp；clickable 在 padding 之前——原来点击区只有文字本身，
            // 圆角背景的内边距点了没反应，现在整块可点。
            // 原 0.98 的未选中缩放会等比缩小 48dp 触控扩展（48×0.98=47.04，探针实测 47dp），
            // 已移除——选中态仍由背景/文字颜色动画表达。
            .heightIn(min = 48.dp)
            .background(backgroundColor, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(label, color = textColor, style = MaterialTheme.typography.bodyMedium)
    }
}
