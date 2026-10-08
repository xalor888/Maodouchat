package com.maodouchat.ui.screen.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.util.CustomThemeStore

/**
 * 9.253：主题编辑器（TG 式高自定义）——逐槽位调色 + 单槽重置 + .attheme 导入/导出。
 * 快速配色预设簇已按簇搬到同包 `ThemeEditorPresets.kt`；
 * 取色器对话框簇已按簇搬到同包 `ThemeColorPickerDialog.kt`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeEditorScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val revision by CustomThemeStore.revision.collectAsState()
    var variant by remember { mutableStateOf("light") }
    var editingSlot by remember { mutableStateOf<String?>(null) }
    // 9.290：launcher 回调只记录结果，字符串在组合期用 stringResource 解析——
    // 回调里用捕获的 LocalContext 查资源会被 compose lint（LocalContextGetResourceValueCall）判为 error 导致 CI 挂
    var importOutcome by remember { mutableStateOf<Int?>(null) } // null=无事件，0=导入空，n=导入 n 槽位
    var exportDone by remember { mutableStateOf(false) }
    var savedSlotCount by remember { mutableStateOf<Int?>(null) }

    // .attheme 导入（SAF）
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            }.getOrNull().orEmpty()
            val parsed = parseThemeFile(text)
            parsed.forEach { (slot, color) -> CustomThemeStore.setColor(context, variant, slot, color) }
            importOutcome = parsed.size
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
            exportDone = true
        }
    }

    val outcome = importOutcome
    val toastMsg: String? = when {
        outcome == 0 -> stringResource(R.string.theme_import_no_keys)
        outcome != null && outcome > 0 -> pluralStringResource(R.plurals.theme_import_ok, outcome, outcome)
        exportDone -> stringResource(R.string.theme_export_ok)
        savedSlotCount != null && savedSlotCount!! > 0 -> pluralStringResource(R.plurals.theme_import_ok, savedSlotCount!!, savedSlotCount!!)
        else -> null
    }
    LaunchedEffect(toastMsg) {
        val msg = toastMsg
        if (msg != null) {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            importOutcome = null
            exportDone = false
            savedSlotCount = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.theme_editor_title), modifier = Modifier.semantics { heading() }) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            },
            actions = {
                TextButton(onClick = { runCatching { importLauncher.launch(arrayOf("*/*")) } }) {
                    Text(stringResource(R.string.theme_import), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { runCatching { exportLauncher.launch("maodouchat-$variant.attheme") } }) {
                    Text(stringResource(R.string.theme_export), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { CustomThemeStore.clearAll(context, variant) }) {
                    Text(stringResource(R.string.theme_reset_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
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

        // 9.260：快速配色预设（TG 社区热门配色一键应用，浅/深变体各一套）
        val presets = if (variant == "dark") DARK_QUICK_PRESETS else LIGHT_QUICK_PRESETS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            presets.forEach { preset ->
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clickable {
                            preset.slots.forEach { (slot, color) ->
                                CustomThemeStore.setColor(context, variant, slot, color)
                            }
                            savedSlotCount = preset.slots.size
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        preset.swatches.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        stringResource(preset.nameRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

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
                savedSlotCount = 1
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
    return slotDefaultColor(paint, slot)
}

/**
 * 槽位 → 主题绘制参数的映射（G160 从 `defaultSlotColor` 抽出）。
 *
 * 原先这段映射和「读全局 ThemePreferences 解析 paint」耦在一个函数里，
 * 导致它无法被单测覆盖。拆开后映射本身是纯函数，用任意 ThemePaint 都能验。
 *
 * 注意 `chat_outBubble` / `chat_outText` 有兜底：发送气泡规格缺失时
 * 分别回落到 `colorScheme.primary` 与 `Color.White`——不兜底会 NPE。
 */
internal fun slotDefaultColor(paint: com.maodouchat.theme.ThemePaint, slot: String): Color {
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

/**
 * 屏幕层解析：先吃 [CustomThemeStore.parseAtTheme] 的 TG 键，再补原生槽位名
 * （`parseAtTheme` 只认 TG_KEY_MAP，导出/手写 native slot 会被丢掉）。
 */
internal fun parseThemeFile(text: String): Map<String, Color> {
    val out = LinkedHashMap(CustomThemeStore.parseAtTheme(text))
    val known = CustomThemeStore.SLOTS.toSet()
    text.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//")) return@forEach
        val eq = trimmed.indexOf('=')
        if (eq <= 0) return@forEach
        val key = trimmed.substring(0, eq).trim()
        if (key !in known || out.containsKey(key)) return@forEach
        val value = trimmed.substring(eq + 1).trim()
        val color = parseHexColor(value) ?: runCatching { Color(value.toInt()) }.getOrNull()
        if (color != null) out[key] = color
    }
    return out
}

internal fun parseHexColor(raw: String): Color? {
    val hex = raw.trim().removePrefix("#").filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    return runCatching {
        when (hex.length) {
            6 -> Color(("FF$hex").toLong(16).toInt())
            8 -> Color(hex.toLong(radix = 16).toInt())
            else -> null
        }
    }.getOrNull()
}

