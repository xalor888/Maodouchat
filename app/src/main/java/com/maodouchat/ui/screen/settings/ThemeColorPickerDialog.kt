package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.util.CustomThemeStore

/** 取色器：预设色板 + HEX 输入 + RGB 滑杆（紧凑三段式，替代 TG 色轮的实用实现）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerDialog(
    title: String,
    initial: Color,
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit
) {
    var r by remember { mutableFloatStateOf(initial.red) }
    var g by remember { mutableFloatStateOf(initial.green) }
    var b by remember { mutableFloatStateOf(initial.blue) }
    val picked = Color(r, g, b)
    val presets = remember {
        listOf(
            Color(0xFF3390EC), Color(0xFF2f7cf6), Color(0xFF34C759), Color(0xFFFF9500),
            Color(0xFFFF2D55), Color(0xFFAF52DE), Color(0xFF5856D6), Color(0xFF00C7BE),
            Color(0xFFEFFDDE), Color(0xFF2B5278), Color(0xFF0E1621), Color(0xFFF5F8FA),
            Color(0xFFFFFFFF), Color(0xFF1C1C1E), Color(0xFF8E8E93), Color(0xFF000000)
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // 当前色预览 + HEX
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(picked)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(CustomThemeStore.formatArgb(picked), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(modifier = Modifier.height(14.dp))
                // 预设色板
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { p ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(p)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable {
                                    r = p.red; g = p.green; b = p.blue
                                }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                // RGB 滑杆
                RgbSlider(R.string.theme_color_red, r, Color.Red) { r = it }
                RgbSlider(R.string.theme_color_green, g, Color.Green) { g = it }
                RgbSlider(R.string.theme_color_blue, b, Color.Blue) { b = it }
                Spacer(modifier = Modifier.height(10.dp))
                // HEX 直接输入
                var hexInput by remember { mutableStateOf(CustomThemeStore.formatArgb(picked).removePrefix("#")) }
                LaunchedEffect(picked) { hexInput = CustomThemeStore.formatArgb(picked).removePrefix("#") }
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { v ->
                        hexInput = v.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(8)
                        runCatching {
                            if (hexInput.length == 6 || hexInput.length == 8) {
                                parseHexColor(hexInput)?.let { c ->
                                    r = c.red; g = c.green; b = c.blue
                                }
                            }
                        }
                    },
                    label = { Text("HEX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(picked) }) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun RgbSlider(labelRes: Int, value: Float, tint: Color, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(20.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint)
        )
        Text((value * 255).toInt().toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(30.dp))
    }
}

