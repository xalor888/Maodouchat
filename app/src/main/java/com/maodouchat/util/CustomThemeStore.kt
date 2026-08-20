package com.maodouchat.util

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 9.253：自定义主题颜色覆盖存储（TG 式高自定义主题的第一步）。
 *
 * 设计对齐 Telegram 主题系统：
 * - 每个颜色槽位有稳定键名（与 .attheme 生态部分兼容，见 [TG_KEY_MAP]）
 * - 浅 / 深色分开存储（key 前缀 light_/dark_）
 * - 覆盖层只动用户改过的槽位，其余沿用当前主题家族默认值
 * - .attheme 文本格式导入导出：`key=#AARRGGBB` 或 `key=<int>`，未知键忽略
 */
object CustomThemeStore {

    private const val PREFS = "custom_theme_prefs"
    /** 主题覆盖版本号：任何变更 bump，供 Theme.kt 的 remember 键感知。 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    /** 可自定义的颜色槽位：id → (显示名资源无关的键, 说明)。顺序即编辑器展示顺序。 */
    val SLOTS = listOf(
        "accent",            // 强调色（primary）
        "chat_background",   // 聊天背景
        "chat_inBubble",     // 接收气泡
        "chat_outBubble",    // 发送气泡
        "chat_outText",      // 发送气泡文字
        "text_primary",      // 主文字色
        "window_background"  // 页面背景
    )

    /**
     * TG .attheme 键 → 本应用槽位映射（社区主题包部分兼容子集）。
     * TG 有 700+ 键，这里只映射能对应到我们设计系统的核心键，其余忽略。
     */
    private val TG_KEY_MAP = mapOf(
        "actionBarDefaultAction" to "accent",
        "actionBarDefaultIcon" to "accent",
        "chat_inBubble" to "chat_inBubble",
        "chat_messagePanelBackground" to "chat_background",
        "chat_outBubble" to "chat_outBubble",
        "chat_outTextColor" to "chat_outText",
        "chat_wallpaper" to "chat_background",
        "windowBackgroundWhite" to "window_background",
        "windowBackgroundWhiteBlackText" to "text_primary"
    )

    /** 槽位 → 导出时的 TG 键（反向映射取第一个）。 */
    private val EXPORT_KEY_MAP = TG_KEY_MAP.entries.groupBy { it.value }.mapValues { it.value.first().key }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun storageKey(variant: String, slot: String) = "${variant}_$slot"

    fun getColor(ctx: Context, variant: String, slot: String): Color? {
        val raw = prefs(ctx).getInt(storageKey(variant, slot), Int.MIN_VALUE)
        return if (raw == Int.MIN_VALUE) null else Color(raw)
    }

    fun setColor(ctx: Context, variant: String, slot: String, color: Color) {
        prefs(ctx).edit().putInt(storageKey(variant, slot), color.toArgb()).apply()
        _revision.value++
    }

    fun clearColor(ctx: Context, variant: String, slot: String) {
        prefs(ctx).edit().remove(storageKey(variant, slot)).apply()
        _revision.value++
    }

    /** 重置某变体全部覆盖（恢复主题家族默认）。 */
    fun clearAll(ctx: Context, variant: String) {
        val editor = prefs(ctx).edit()
        SLOTS.forEach { editor.remove(storageKey(variant, it)) }
        editor.apply()
        _revision.value++
    }

    fun hasOverrides(ctx: Context, variant: String): Boolean =
        SLOTS.any { prefs(ctx).contains(storageKey(variant, it)) }

    /**
     * 解析 .attheme 文本：每行 `key=value`，value 为 #hex（RGB/ARGB）或十进制 int。
     * 只保留 [TG_KEY_MAP] 能映射的键；返回 槽位→颜色。
     */
    fun parseAtTheme(text: String): Map<String, Color> {
        val out = HashMap<String, Color>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//")) return@forEach
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@forEach
            val tgKey = trimmed.substring(0, eq).trim()
            val slot = TG_KEY_MAP[tgKey] ?: return@forEach
            val value = trimmed.substring(eq + 1).trim()
            val color = parseColorValue(value) ?: return@forEach
            out[slot] = color
        }
        return out
    }

    private fun parseColorValue(value: String): Color? = runCatching {
        when {
            value.startsWith("#") -> {
                val hex = value.removePrefix("#")
                when (hex.length) {
                    6 -> Color(("FF$hex").toLong(16).toInt())
                    8 -> Color(hex.toLong(16).toInt())
                    else -> null
                }
            }
            else -> Color(value.toInt())
        }
    }.getOrNull()

    /** 导出当前变体覆盖为 .attheme 文本（TG 兼容键名）。 */
    fun exportAtTheme(ctx: Context, variant: String): String {
        val sb = StringBuilder()
        sb.appendLine("// Maodouchat custom theme ($variant)")
        SLOTS.forEach { slot ->
            val color = getColor(ctx, variant, slot) ?: return@forEach
            val tgKey = EXPORT_KEY_MAP[slot] ?: return@forEach
            sb.appendLine("$tgKey=${formatArgb(color)}")
        }
        return sb.toString()
    }

    fun formatArgb(color: Color): String {
        val argb = color.toArgb()
        return String.format("#%08X", argb)
    }

    /** 应用覆盖到解析出的主题三元组（在 Theme.kt 的 remember 里调用）。 */
    fun applyOverrides(
        ctx: Context,
        variant: String,
        paint: com.maodouchat.ui.theme.ThemePaint
    ): com.maodouchat.ui.theme.ThemePaint {
        if (!hasOverrides(ctx, variant)) return paint
        var scheme = paint.colorScheme
        var palette = paint.chatPalette
        var sentSpec = paint.sentBubbleSpec

        getColor(ctx, variant, "accent")?.let {
            scheme = scheme.copy(primary = it, onPrimary = Color.White)
        }
        getColor(ctx, variant, "window_background")?.let {
            scheme = scheme.copy(background = it, surface = it)
        }
        getColor(ctx, variant, "text_primary")?.let {
            scheme = scheme.copy(onBackground = it, onSurface = it)
            palette = palette.copy(textPrimary = it)
        }
        getColor(ctx, variant, "chat_background")?.let {
            palette = palette.copy(chatBackground = it)
        }
        getColor(ctx, variant, "chat_inBubble")?.let {
            palette = palette.copy(chatBubbleReceived = it)
        }
        val outBubble = getColor(ctx, variant, "chat_outBubble")
        val outText = getColor(ctx, variant, "chat_outText")
        if (outBubble != null || outText != null) {
            val base = sentSpec ?: com.maodouchat.ui.theme.SentBubbleSpec(
                color = scheme.primary,
                content = Color.White,
                contentSecondary = Color.White.copy(alpha = 0.7f)
            )
            sentSpec = base.copy(
                color = outBubble ?: base.color,
                content = outText ?: base.content,
                contentSecondary = outText?.copy(alpha = 0.7f) ?: base.contentSecondary
            )
        }
        return com.maodouchat.ui.theme.ThemePaint(scheme, palette, sentSpec)
    }
}
