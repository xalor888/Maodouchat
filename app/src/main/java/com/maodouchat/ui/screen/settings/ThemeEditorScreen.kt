package com.maodouchat.ui.screen.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.util.CustomThemeStore

/**
 * 9.253：主题编辑器（TG 式高自定义）——逐槽位调色 + 单槽重置 + .attheme 导入/导出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeEditorScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val revision by CustomThemeStore.revision.collectAsState()
    var variant by remember { mutableStateOf("light") }
    var editingSlot by remember { mutableStateOf<String?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    // .attheme 导入（SAF）
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            }.getOrNull().orEmpty()
            val parsed = CustomThemeStore.parseAtTheme(text)
            if (parsed.isEmpty()) {
                toastMsg = context.getString(R.string.theme_import_no_keys)
            } else {
                parsed.forEach { (slot, color) -> CustomThemeStore.setColor(context, variant, slot, color) }
                toastMsg = context.getString(R.string.theme_import_ok, parsed.size)
            }
        }
    }
    // .attheme 导出（SAF）
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(CustomThemeStore.exportAtTheme(context, variant).toByteArray())
                }
            }
            toastMsg = context.getString(R.string.theme_export_ok)
        }
    }

    LaunchedEffect(toastMsg) {
        val msg = toastMsg
        if (msg != null) {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            toastMsg = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.theme_editor_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            },
            actions = {
                TextButton(onClick = { runCatching { importLauncher.launch(arrayOf("*/*")) } }) {
                    Text(stringResource(R.string.theme_import))
                }
                TextButton(onClick = { runCatching { exportLauncher.launch("maodouchat-$variant.attheme") } }) {
                    Text(stringResource(R.string.theme_export))
                }
                TextButton(onClick = { CustomThemeStore.clearAll(context, variant) }) {
                    Text(stringResource(R.string.theme_reset_all))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        // 浅 / 深变体切换（两套独立存储）
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            listOf("light" to R.string.theme_variant_light, "dark" to R.string.theme_variant_dark).forEach { (id, label) ->
                val selected = variant == id
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { variant = id }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        Text(
            stringResource(R.string.theme_editor_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // 颜色槽位列表（revision 驱动重绘）
        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
            items(CustomThemeStore.SLOTS, key = { it }) { slot ->
                val current = remember(revision, variant, slot) { CustomThemeStore.getColor(context, variant, slot) }
                ThemeColorRow(
                    name = slotDisplayName(slot),
                    slotKey = slot,
                    current = current,
                    previewDefault = defaultSlotColor(slot, variant),
                    onClick = { editingSlot = slot },
                    onReset = { CustomThemeStore.clearColor(context, variant, slot) }
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
            }
        }
    }

    // 取色器弹窗
    editingSlot?.let { slot ->
        val initial = CustomThemeStore.getColor(context, variant, slot) ?: defaultSlotColor(slot, variant)
        ColorPickerDialog(
            title = slotDisplayName(slot),
            initial = initial,
            onDismiss = { editingSlot = null },
            onPick = { color ->
                CustomThemeStore.setColor(context, variant, slot, color)
                editingSlot = null
            }
        )
    }
}

@Composable
private fun slotDisplayName(slot: String): String = stringResource(
    when (slot) {
        "accent" -> R.string.theme_slot_accent
        "chat_background" -> R.string.theme_slot_chat_background
        "chat_inBubble" -> R.string.theme_slot_in_bubble
        "chat_inText" -> R.string.theme_slot_in_text
        "chat_outBubble" -> R.string.theme_slot_out_bubble
        "chat_outText" -> R.string.theme_slot_out_text
        "text_primary" -> R.string.theme_slot_text_primary
        "input_background" -> R.string.theme_slot_input_background
        "unread_badge" -> R.string.theme_slot_unread_badge
        "system_message" -> R.string.theme_slot_system_message
        else -> R.string.theme_slot_window_background
    }
)

/** 槽位的主题家族默认色预览（未覆盖时展示的参考值）。 */
private fun defaultSlotColor(slot: String, variant: String): Color {
    val dark = variant == "dark"
    val paint = com.maodouchat.ui.theme.resolveThemePaint(
        com.maodouchat.ui.theme.ThemeFamily.normalize(com.maodouchat.util.ThemePreferences.family.value),
        dark
    )
    return when (slot) {
        "accent" -> paint.colorScheme.primary
        "chat_background" -> paint.chatPalette.chatBackground
        "chat_inBubble" -> paint.chatPalette.chatBubbleReceived
        "chat_inText" -> paint.chatPalette.textPrimary
        "chat_outBubble" -> paint.sentBubbleSpec?.color ?: paint.colorScheme.primary
        "chat_outText" -> paint.sentBubbleSpec?.content ?: Color.White
        "text_primary" -> paint.chatPalette.textPrimary
        "input_background" -> paint.chatPalette.chatInputBackground
        "unread_badge" -> paint.chatPalette.unreadRed
        "system_message" -> paint.chatPalette.systemMessageBackground
        else -> paint.colorScheme.background
    }
}

@Composable
private fun ThemeColorRow(
    name: String,
    slotKey: String,
    current: Color?,
    previewDefault: Color,
    onClick: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(current ?: previewDefault)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (current != null) CustomThemeStore.formatArgb(current) else stringResource(R.string.theme_slot_default),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (current != null) {
            IconButton(onClick = onReset, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.theme_slot_reset), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

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
                rgbSlider(R.string.theme_color_red, r, Color.Red) { r = it }
                rgbSlider(R.string.theme_color_green, g, Color.Green) { g = it }
                rgbSlider(R.string.theme_color_blue, b, Color.Blue) { b = it }
                Spacer(modifier = Modifier.height(10.dp))
                // HEX 直接输入
                var hexInput by remember { mutableStateOf(CustomThemeStore.formatArgb(picked).removePrefix("#")) }
                LaunchedEffect(picked) { hexInput = CustomThemeStore.formatArgb(picked).removePrefix("#") }
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { v ->
                        hexInput = v.take(8)
                        runCatching {
                            if (hexInput.length == 6 || hexInput.length == 8) {
                                val full = if (hexInput.length == 6) "FF$hexInput" else hexInput
                                val c = Color(full.toLong(16).toInt())
                                r = c.red; g = c.green; b = c.blue
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
private fun rgbSlider(labelRes: Int, value: Float, tint: Color, onChange: (Float) -> Unit) {
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
