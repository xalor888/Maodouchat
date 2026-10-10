package com.maodouchat.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.maodouchat.theme.SentBubbleSpec

/**
 * 主题风格家族。MAODOU（白底液态玻璃）为品牌默认；TG_* 为 Telegram/Nekogram 扁平主题，
 * 色板见下方 Tg*Scheme/Palette（已按 WCAG 4.5:1 校准）。
 */
enum class ThemeFamily(val id: String) {
    MAODOU("maodou"),
    TG_CLASSIC("tg_classic"),
    TG_MIDNIGHT("tg_midnight"),
    TG_GRAPHITE("tg_graphite");

    val isLiquidGlass: Boolean get() = this == MAODOU
    val isTelegram: Boolean get() = this == TG_CLASSIC || this == TG_MIDNIGHT || this == TG_GRAPHITE

    companion object {
        fun normalize(raw: String?): ThemeFamily = when (raw?.trim()?.lowercase()) {
            "tg_classic", "tg", "telegram", "telegram_classic" -> TG_CLASSIC
            "tg_midnight", "midnight" -> TG_MIDNIGHT
            "tg_graphite", "graphite" -> TG_GRAPHITE
            else -> MAODOU
        }

        val ALL: List<ThemeFamily> = listOf(MAODOU, TG_CLASSIC, TG_MIDNIGHT, TG_GRAPHITE)
        val PICKABLE: List<ThemeFamily> = listOf(MAODOU)
    }
}

/** 当前主题的深浅（由主题模式解析后的真实值，非系统深浅）。 */
val LocalDarkTheme = compositionLocalOf { false }

/** 可选强调色（TG 式自定义）：none 表示跟随当前主题默认强调色。 */
data class AccentOption(val id: String, val light: Color, val dark: Color)

val ACCENT_OPTIONS = listOf(
    AccentOption("blue", Color(0xFF007AFF), Color(0xFF0A84FF)),
    AccentOption("green", Color(0xFF34C759), Color(0xFF30D158)),
    AccentOption("purple", Color(0xFF8B5CF6), Color(0xFFA78BFA)),
    AccentOption("orange", Color(0xFFF97316), Color(0xFFFF9F0A)),
    AccentOption("pink", Color(0xFFEC4899), Color(0xFFF472B6)),
    AccentOption("red", Color(0xFFFF3B30), Color(0xFFFF453A)),
    AccentOption("teal", Color(0xFF06B6D4), Color(0xFF22D3EE))
)

fun normalizeAccentId(raw: String?): String {
    val id = raw?.trim()?.lowercase().orEmpty()
    return if (ACCENT_OPTIONS.any { it.id == id }) id else "none"
}

fun accentFor(id: String, dark: Boolean): Color? =
    ACCENT_OPTIONS.firstOrNull { it.id == id }?.let { if (dark) it.dark else it.light }

/** 气泡圆角风格（TG 式自定义）：尾角小圆角 / TG 全圆 / 大圆角。 */
data class BubbleShapes(val sent: androidx.compose.ui.graphics.Shape, val received: androidx.compose.ui.graphics.Shape)

val BUBBLE_SHAPE_DEFAULT = BubbleShapes(
    sent = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    received = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
)
val BUBBLE_SHAPE_TG = BubbleShapes(
    sent = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    received = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
)
val BUBBLE_SHAPE_ROUND = BubbleShapes(
    sent = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    received = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
)

fun bubbleShapesFor(styleId: String, family: ThemeFamily = ThemeFamily.MAODOU): BubbleShapes {
    if (styleId == "round") return BUBBLE_SHAPE_ROUND
    if (styleId == "tg" || family.isTelegram) return BUBBLE_SHAPE_TG
    return BUBBLE_SHAPE_DEFAULT
}

val LocalBubbleShapes = compositionLocalOf { BUBBLE_SHAPE_DEFAULT }

/** 当前主题对发送气泡的接管（maodou 家族为 null）。 */
val LocalSentBubbleSpec = compositionLocalOf<SentBubbleSpec?> { null }

/** 发送气泡上的主文字色（浅色气泡时为深色墨字，默认白色）。 */
val LocalSentBubbleContent = compositionLocalOf { TextWhite }

/** 发送气泡上的次要文字色（时间戳等）。 */
val LocalSentBubbleContentSecondary = compositionLocalOf { TextWhiteSecondary }

/** 最终生效的发送气泡配色（气泡色 + 气泡内主/次文字色）。 */
data class SentBubbleColors(
    val bubble: Color,
    val content: Color,
    val contentSecondary: Color
)

/**
 * 计算生效的发送气泡配色：
 * - 当前主题有专属发送气泡（TG 系列）且用户未自定义过气泡色 → 主题接管（1:1 还原）；
 * - 否则沿用用户自选气泡色，按 WCAG 4.5:1 对比度自动选深/浅文字
 *   （9.255：借鉴 Murexide 对比度保证机制——旧 0.6 亮度阈值在中灰气泡上
 *   两种文字都不够清晰，现直接算对比度达标性）。
 */
fun resolveSentBubble(spec: SentBubbleSpec?, userCustomized: Boolean, userColor: Color): SentBubbleColors {
    if (spec != null && !userCustomized) {
        return SentBubbleColors(spec.color, spec.content, spec.contentSecondary)
    }
    val darkTextContrast = contrastRatio(Color(0xFF212121), userColor)
    val whiteTextContrast = contrastRatio(TextWhite, userColor)
    return if (darkTextContrast >= whiteTextContrast && darkTextContrast >= MIN_TEXT_CONTRAST) {
        SentBubbleColors(userColor, Color(0xFF212121), Color(0x99212121))
    } else if (whiteTextContrast >= MIN_TEXT_CONTRAST) {
        SentBubbleColors(userColor, TextWhite, TextWhiteSecondary)
    } else {
        // 两边都不达标时取对比度更高的一侧（兜底，实际中灰以上必有一侧达标）
        if (darkTextContrast >= whiteTextContrast) {
            SentBubbleColors(userColor, Color(0xFF212121), Color(0x99212121))
        } else {
            SentBubbleColors(userColor, TextWhite, TextWhiteSecondary)
        }
    }
}

/** WCAG AA 正文最低对比度（与 Murexide DEFAULT_MINIMUM_TEXT_CONTRAST 同值）。 */
const val MIN_TEXT_CONTRAST = 4.5f

/** WCAG 对比度（(L1+0.05)/(L2+0.05)，1..21）。 */
fun contrastRatio(foreground: Color, background: Color): Float {
    val l1 = relativeLuminance(foreground)
    val l2 = relativeLuminance(background)
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/** WCAG 相对亮度（0=黑，1=白），用于自动选择气泡内文字深浅。 */
private fun relativeLuminance(color: Color): Float {
    fun lin(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f
        else kotlin.math.exp(2.4f * kotlin.math.ln((channel + 0.055f) / 1.055f))
    return 0.2126f * lin(color.red) + 0.7152f * lin(color.green) + 0.0722f * lin(color.blue)
}
