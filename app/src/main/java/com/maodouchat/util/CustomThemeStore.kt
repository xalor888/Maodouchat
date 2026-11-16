package com.maodouchat.util

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import com.maodouchat.domain.messaging.ThemeFileColorPolicy
import com.maodouchat.theme.SentBubbleSpec
import com.maodouchat.theme.ThemePaint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 9.253：自定义主题颜色覆盖存储（TG 式高自定义主题的第一步）。
 *
 * 设计对齐 Telegram 主题系统：
 * - 每个颜色槽位有稳定键名（与 .attheme 生态部分兼容，见 ThemeFileColorPolicy）
 * - 浅 / 深色分开存储（key 前缀 light_/dark_）
 * - 覆盖层只动用户改过的槽位，其余沿用当前主题家族默认值
 * - .attheme 文本格式导入导出：`key=#AARRGGBB` 或 `key=<int>`，未知键忽略
 */
object CustomThemeStore {

    private const val PREFS = "custom_theme_prefs"
    /** 主题覆盖版本号：任何变更 bump，供 Theme.kt 的 remember 键感知。 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun storageKey(variant: String, slot: String) = "${variant}_$slot"

    fun getColor(ctx: Context, variant: String, slot: String): Color? {
        val raw = prefs(ctx).getInt(storageKey(variant, slot), Int.MIN_VALUE)
        return if (raw == Int.MIN_VALUE) null else Color(raw)
    }

    fun setColor(ctx: Context, variant: String, slot: String, color: Color) {
        prefs(ctx).edit { putInt(storageKey(variant, slot), color.toArgb()) }
        _revision.value++
    }

    fun clearColor(ctx: Context, variant: String, slot: String) {
        prefs(ctx).edit { remove(storageKey(variant, slot)) }
        _revision.value++
    }

    /** 重置某变体全部覆盖（恢复主题家族默认）。 */
    fun clearAll(ctx: Context, variant: String) {
        prefs(ctx).edit {
            ThemeFileColorPolicy.SLOTS.forEach { remove(storageKey(variant, it)) }
        }
        _revision.value++
    }

    fun hasOverrides(ctx: Context, variant: String): Boolean =
        ThemeFileColorPolicy.SLOTS.any { prefs(ctx).contains(storageKey(variant, it)) }

    /**
     * 解析 .attheme 文本：每行 `key=value`，value 为 #hex（RGB/ARGB）或十进制 int。
     * 只保留 ThemeFileColorPolicy 内 TG 键表能映射的键；返回 槽位→颜色。
     */
    fun parseAtTheme(text: String): Map<String, Color> =
        ThemeFileColorPolicy.parseAtTheme(text).mapValues { Color(it.value) }

    /** 导出当前变体覆盖为 .attheme 文本（TG 兼容键名）。 */
    fun exportAtTheme(ctx: Context, variant: String): String {
        val sb = StringBuilder()
        sb.appendLine("// Maodouchat custom theme ($variant)")
        ThemeFileColorPolicy.SLOTS.forEach { slot ->
            val color = getColor(ctx, variant, slot) ?: return@forEach
            val tgKey = ThemeFileColorPolicy.exportKeyForSlot(slot) ?: return@forEach
            sb.appendLine("$tgKey=${formatArgb(color)}")
        }
        return sb.toString()
    }

    fun formatArgb(color: Color): String = ThemeFileColorPolicy.formatArgb(color.toArgb())

    /** 应用覆盖到解析出的主题三元组（在 Theme.kt 的 remember 里调用）。 */
    fun applyOverrides(
        ctx: Context,
        variant: String,
        paint: ThemePaint
    ): ThemePaint {
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
        getColor(ctx, variant, "chat_inText")?.let {
            // 接收气泡正文用 textPrimary 渲染；边框同步派生保持层次
            palette = palette.copy(
                textPrimary = it,
                chatBubbleReceivedBorder = it.copy(alpha = 0.25f)
            )
            scheme = scheme.copy(onSurface = it)
        }
        getColor(ctx, variant, "input_background")?.let {
            palette = palette.copy(chatInputBackground = it)
        }
        getColor(ctx, variant, "unread_badge")?.let {
            palette = palette.copy(unreadRed = it)
        }
        getColor(ctx, variant, "system_message")?.let {
            palette = palette.copy(systemMessageBackground = it.copy(alpha = 0.6f))
        }
        val outBubble = getColor(ctx, variant, "chat_outBubble")
        val outText = getColor(ctx, variant, "chat_outText")
        if (outBubble != null || outText != null) {
            val base = sentSpec ?: SentBubbleSpec(
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
        return ThemePaint(scheme, palette, sentSpec)
    }
}
