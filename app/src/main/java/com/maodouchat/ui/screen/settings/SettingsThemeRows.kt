package com.maodouchat.ui.screen.settings

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
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** 通用设置页的主题相关行组件，从 GeneralSettingsRows.kt 按簇搬出。 */

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
        // 定时深色只在 scheduled 模式显示时段设置
        if (currentTheme == "scheduled") {
            Spacer(modifier = Modifier.height(10.dp))
            NightWindowRow()
        }
        // OLED 纯黑只在可能进入深色的模式显示
        if (currentTheme == "dark" || currentTheme == "system" || currentTheme == "scheduled") {
            Spacer(modifier = Modifier.height(10.dp))
            OledBlackRow()
        }
    }
}



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
            // 整块圆角背景都可点（含内边距），触控目标不小于 48dp。
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
