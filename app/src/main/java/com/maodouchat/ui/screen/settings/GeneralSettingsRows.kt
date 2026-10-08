package com.maodouchat.ui.screen.settings

import com.maodouchat.security.findActivity
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.util.AppLocaleManager
import com.maodouchat.ui.theme.LocalChatPalette

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
