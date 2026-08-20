package com.maodouchat.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * 主题风格家族（Telegram 级可切换主题）。
 *
 * 每个家族提供浅 / 深两个变体，由「主题模式」(system/light/dark) 决定当前使用哪一个：
 * - MAODOU     品牌默认（现有浅 / 深色）
 * - TG_CLASSIC Telegram 经典 1:1（浅=经典绿气泡，深=TG Dark #0E1621）
 * - TG_MIDNIGHT Telegram 朝霞/午夜（浅=Daybreak 暖色，深=Midnight 深蓝）
 * - TG_GRAPHITE Telegram 冰蓝/石墨（浅=Ice 冷色，深=Graphite 灰黑）
 */
enum class ThemeFamily(val id: String) {
    MAODOU("maodou"),
    TG_CLASSIC("tg_classic"),
    TG_MIDNIGHT("tg_midnight"),
    TG_GRAPHITE("tg_graphite");

    companion object {
        fun normalize(raw: String?): ThemeFamily {
            val id = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == id } ?: MAODOU
        }

        val ALL: List<ThemeFamily> = entries
    }
}

/**
 * 主题对「发送气泡」的接管规格：TG 各主题的发送气泡有专属配色（如经典浅色的 #EFFDDE 绿气泡
 * 需配深色文字）。null 表示沿用用户自选气泡色 + 白色文字（品牌默认）。
 */
data class SentBubbleSpec(
    val color: Color,
    val content: Color,
    val contentSecondary: Color
)

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
    sent = androidx.compose.foundation.shape.RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
    received = androidx.compose.foundation.shape.RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
)
val BUBBLE_SHAPE_TG = BubbleShapes(
    sent = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    received = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
)
val BUBBLE_SHAPE_ROUND = BubbleShapes(
    sent = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    received = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
)

fun bubbleShapesFor(styleId: String): BubbleShapes = when (styleId) {
    "tg" -> BUBBLE_SHAPE_TG
    "round" -> BUBBLE_SHAPE_ROUND
    else -> BUBBLE_SHAPE_DEFAULT
}

val LocalBubbleShapes = compositionLocalOf { BUBBLE_SHAPE_DEFAULT }

/** 当前主题对发送气泡的接管（maodou 家族为 null）。 */
val LocalSentBubbleSpec = compositionLocalOf<SentBubbleSpec?> { null }

/** 发送气泡上的主文字色（浅色气泡时为深色墨字，默认白色）。 */
val LocalSentBubbleContent = compositionLocalOf { TextWhite }

/** 发送气泡上的次要文字色（时间戳等）。 */
val LocalSentBubbleContentSecondary = compositionLocalOf { TextWhiteSecondary }

/** 一组完整的主题绘制参数：Material 色板 + 聊天调色板 + 发送气泡规格。 */
data class ThemePaint(
    val colorScheme: ColorScheme,
    val chatPalette: ChatPalette,
    val sentBubbleSpec: SentBubbleSpec?
)

// ─── Telegram 经典浅色（Classic） ──────────────────────────────
private val TgClassicScheme = lightColorScheme(
    primary = Color(0xFF3390EC), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E9FF), onPrimaryContainer = Color(0xFF00325B),
    secondary = Color(0xFF527DA3), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E7F5), onSecondaryContainer = Color(0xFF0E3450),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF1F1F4), onSurfaceVariant = Color(0xFF707579),
    error = Color(0xFFDF3828), onError = Color(0xFFFFFFFF),
    outline = Color(0xFF707579), outlineVariant = Color(0xFFE4E6EA)
)

private val TgClassicPalette = ChatPalette(
    chatBackground = Color(0xFFE7EBEE),
    chatBubbleReceived = Color(0xFFFFFFFF),
    chatBubbleReceivedBorder = Color(0xFFE1E5EA),
    chatInputBackground = Color(0xFFF4F4F5),
    chatInputBorder = Color(0xFFD9DCE0),
    // 9.263：TG 浅色文字对比度校准——TG 官方 #9BA1A6 时间戳仅 2.18:1，
    // 保持同色相提亮到 #6D7378（浅背景 4.3:1 / 白气泡 4.8:1）
    chatInputPlaceholder = Color(0xFF6D7378),
    systemMessageBackground = Color(0x26FFFFFF),
    systemMessageText = Color(0xFF60666B),
    textHint = Color(0xFF6D7378),
    textPrimary = Color(0xFF000000),
    // #707579→#5F6574（浅背景 3.88→5.2 达标）
    textSecondary = Color(0xFF5F6574),
    divider = Color(0xFFE4E6EA),
    unreadRed = Color(0xFFDF3828),
    onlineGreen = Color(0xFF34C759),
    chatElevatedSurface = Color(0xFFF7F7F8),
    chatElevatedSurfaceHigh = Color(0xFFEFF0F2)
)

// ─── Telegram Dark（Android 经典深色 #0E1621） ─────────────────
private val TgDarkScheme = darkColorScheme(
    primary = Color(0xFF5EB5F7), onPrimary = Color(0xFF0E1621),
    primaryContainer = Color(0xFF1E3A52), onPrimaryContainer = Color(0xFFCFE8FF),
    secondary = Color(0xFF708499), onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFF0E1621), onBackground = Color(0xFFF1F5F8),
    surface = Color(0xFF17212B), onSurface = Color(0xFFF1F5F8),
    surfaceVariant = Color(0xFF242F3D), onSurfaceVariant = Color(0xFFAAB6C2),
    error = Color(0xFFFF6B5E), onError = Color(0xFF2B0D0A),
    outline = Color(0xFF708499), outlineVariant = Color(0xFF242F3D)
)

private val TgDarkPalette = ChatPalette(
    chatBackground = Color(0xFF0E1621),
    chatBubbleReceived = Color(0xFF182533),
    chatBubbleReceivedBorder = Color(0xFF20303F),
    chatInputBackground = Color(0xFF17212B),
    chatInputBorder = Color(0xFF243B53),
    chatInputPlaceholder = Color(0xFF708499),
    systemMessageBackground = Color(0x33182533),
    systemMessageText = Color(0xFF8CA0B3),
    textHint = Color(0xFF708499),
    textPrimary = Color(0xFFF1F5F8),
    textSecondary = Color(0xFF708499),
    divider = Color(0xFF101921),
    unreadRed = Color(0xFFFF6B5E),
    onlineGreen = Color(0xFF4DCD5E),
    chatElevatedSurface = Color(0xFF17212B),
    chatElevatedSurfaceHigh = Color(0xFF1F2C3A)
)

// ─── Telegram Daybreak（朝霞 · 暖色浅色） ──────────────────────
private val TgDaybreakScheme = lightColorScheme(
    primary = Color(0xFFE8703A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC9), onPrimaryContainer = Color(0xFF4A1D05),
    secondary = Color(0xFFB0705A), onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFF7F1), onBackground = Color(0xFF2B1D14),
    surface = Color(0xFFFFFDFB), onSurface = Color(0xFF2B1D14),
    surfaceVariant = Color(0xFFF7EAE0), onSurfaceVariant = Color(0xFF7A645A),
    error = Color(0xFFDF3828), onError = Color(0xFFFFFFFF),
    outline = Color(0xFF8A7468), outlineVariant = Color(0xFFEEDFD4)
)

private val TgDaybreakPalette = ChatPalette(
    chatBackground = Color(0xFFFBE9DC),
    chatBubbleReceived = Color(0xFFFFFFFF),
    chatBubbleReceivedBorder = Color(0xFFEFDCCC),
    chatInputBackground = Color(0xFFFAF0E7),
    chatInputBorder = Color(0xFFE8D5C4),
    chatInputPlaceholder = Color(0xFF75635A),
    systemMessageBackground = Color(0x26FFFFFF),
    systemMessageText = Color(0xFF8A7468),
    // 9.263：Daybreak 对比度校准——#AD9684 仅 2.38:1，同色相加深到 #75635A
    //（暖背景 4.8:1 / 白气泡 5.7:1）
    textHint = Color(0xFF75635A),
    textPrimary = Color(0xFF2B1D14),
    // #8A7468→#6E5C50（3.72→5.38）
    textSecondary = Color(0xFF6E5C50),
    divider = Color(0xFFF0E2D5),
    unreadRed = Color(0xFFE85D4A),
    onlineGreen = Color(0xFF34C759),
    chatElevatedSurface = Color(0xFFFBF1E8),
    chatElevatedSurfaceHigh = Color(0xFFF6E8DA)
)

// ─── Telegram Midnight（午夜 · 深蓝） ──────────────────────────
private val TgMidnightScheme = darkColorScheme(
    primary = Color(0xFF5288C1), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF2B5278), onPrimaryContainer = Color(0xFFD7E7F7),
    secondary = Color(0xFF7A8A99), onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFF1C2733), onBackground = Color(0xFFE9EDF0),
    surface = Color(0xFF232E3C), onSurface = Color(0xFFE9EDF0),
    surfaceVariant = Color(0xFF2C3947), onSurfaceVariant = Color(0xFF9AA8B5),
    error = Color(0xFFFF6B5E), onError = Color(0xFF2B0D0A),
    outline = Color(0xFF7A8A99), outlineVariant = Color(0xFF2C3947)
)

private val TgMidnightPalette = ChatPalette(
    chatBackground = Color(0xFF1C2733),
    chatBubbleReceived = Color(0xFF232E3C),
    chatBubbleReceivedBorder = Color(0xFF2B3947),
    chatInputBackground = Color(0xFF232E3C),
    chatInputBorder = Color(0xFF35455A),
    chatInputPlaceholder = Color(0xFF7A8A99),
    systemMessageBackground = Color(0x33232E3C),
    systemMessageText = Color(0xFF93A2B0),
    textHint = Color(0xFF7A8A99),
    textPrimary = Color(0xFFE9EDF0),
    textSecondary = Color(0xFF7A8A99),
    divider = Color(0xFF1B242F),
    unreadRed = Color(0xFFFF6B5E),
    onlineGreen = Color(0xFF4DCD5E),
    chatElevatedSurface = Color(0xFF232E3C),
    chatElevatedSurfaceHigh = Color(0xFF2A3848)
)

// ─── Telegram Ice（冰蓝 · 冷色浅色） ───────────────────────────
private val TgIceScheme = lightColorScheme(
    primary = Color(0xFF4A9DE0), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9ECFC), onPrimaryContainer = Color(0xFF0A3251),
    secondary = Color(0xFF5E87AC), onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF9FBFD), onBackground = Color(0xFF14202B),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF14202B),
    surfaceVariant = Color(0xFFEDF2F7), onSurfaceVariant = Color(0xFF6F7C8A),
    error = Color(0xFFDF3828), onError = Color(0xFFFFFFFF),
    outline = Color(0xFF7A8794), outlineVariant = Color(0xFFE3E9EF)
)

private val TgIcePalette = ChatPalette(
    chatBackground = Color(0xFFE9F0F6),
    chatBubbleReceived = Color(0xFFFFFFFF),
    chatBubbleReceivedBorder = Color(0xFFDDE6EE),
    chatInputBackground = Color(0xFFEFF4F9),
    chatInputBorder = Color(0xFFD8E2EB),
    chatInputPlaceholder = Color(0xFF62707D),
    systemMessageBackground = Color(0x26FFFFFF),
    systemMessageText = Color(0xFF6F7C8A),
    // 9.263：Ice 对比度校准——#98A6B4 仅 2.16:1，同色相加深到 #62707D
    //（冷背景 4.4:1 / 白气泡 5.1:1）
    textHint = Color(0xFF62707D),
    textPrimary = Color(0xFF14202B),
    // #6F7C8A→#576470（3.71→5.27）
    textSecondary = Color(0xFF576470),
    divider = Color(0xFFE3E9EF),
    unreadRed = Color(0xFFDF3828),
    onlineGreen = Color(0xFF34C759),
    chatElevatedSurface = Color(0xFFF2F6FA),
    chatElevatedSurfaceHigh = Color(0xFFEAF0F6)
)

// ─── Telegram Graphite（石墨 · 灰黑深色） ──────────────────────
private val TgGraphiteScheme = darkColorScheme(
    primary = Color(0xFF7CB3EF), onPrimary = Color(0xFF101010),
    primaryContainer = Color(0xFF333C46), onPrimaryContainer = Color(0xFFD8E7F7),
    secondary = Color(0xFF8A8A8A), onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFF1F1F1F), onBackground = Color(0xFFECECEC),
    surface = Color(0xFF242424), onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF2E2E2E), onSurfaceVariant = Color(0xFFA3A3A3),
    error = Color(0xFFFF6B5E), onError = Color(0xFF2B0D0A),
    outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFF383838)
)

private val TgGraphitePalette = ChatPalette(
    chatBackground = Color(0xFF171717),
    chatBubbleReceived = Color(0xFF242424),
    chatBubbleReceivedBorder = Color(0xFF2E2E2E),
    chatInputBackground = Color(0xFF242424),
    chatInputBorder = Color(0xFF3A3A3A),
    chatInputPlaceholder = Color(0xFF8A8A8A),
    systemMessageBackground = Color(0x332A2A2A),
    systemMessageText = Color(0xFF9C9C9C),
    textHint = Color(0xFF8A8A8A),
    textPrimary = Color(0xFFECECEC),
    textSecondary = Color(0xFF8A8A8A),
    divider = Color(0xFF2A2A2A),
    unreadRed = Color(0xFFFF6B5E),
    onlineGreen = Color(0xFF4DCD5E),
    chatElevatedSurface = Color(0xFF242424),
    chatElevatedSurfaceHigh = Color(0xFF2C2C2C)
)

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

/** 解析当前主题家族 + 深浅 → 完整绘制参数。maodou 家族返回 null 保持原有行为。 */
fun resolveThemePaint(family: ThemeFamily, dark: Boolean): ThemePaint = when (family) {
    ThemeFamily.MAODOU -> ThemePaint(
        colorScheme = if (dark) MaodouDarkScheme else MaodouLightScheme,
        chatPalette = if (dark) DarkChatPalette else LightChatPalette,
        sentBubbleSpec = null
    )
    ThemeFamily.TG_CLASSIC -> if (dark) {
        ThemePaint(
            colorScheme = TgDarkScheme,
            chatPalette = TgDarkPalette,
            sentBubbleSpec = SentBubbleSpec(Color(0xFF2B5278), Color(0xFFF5F8FA), Color(0xB3FFFFFF))
        )
    } else {
        ThemePaint(
            colorScheme = TgClassicScheme,
            chatPalette = TgClassicPalette,
            // 经典绿气泡 + 深色文字（Telegram 1:1）
            sentBubbleSpec = SentBubbleSpec(Color(0xFFEFFDDE), Color(0xFF212121), Color(0xFF52914A))
        )
    }
    ThemeFamily.TG_MIDNIGHT -> if (dark) {
        ThemePaint(
            colorScheme = TgMidnightScheme,
            chatPalette = TgMidnightPalette,
            sentBubbleSpec = SentBubbleSpec(Color(0xFF2B5278), Color(0xFFF1F5F8), Color(0xB3FFFFFF))
        )
    } else {
        ThemePaint(
            colorScheme = TgDaybreakScheme,
            chatPalette = TgDaybreakPalette,
            sentBubbleSpec = SentBubbleSpec(Color(0xFFF5A96B), Color(0xFF3B2A1A), Color(0xFFA9683C))
        )
    }
    ThemeFamily.TG_GRAPHITE -> if (dark) {
        ThemePaint(
            colorScheme = TgGraphiteScheme,
            chatPalette = TgGraphitePalette,
            sentBubbleSpec = SentBubbleSpec(Color(0xFF3C3C3C), Color(0xFFF0F0F0), Color(0xB3FFFFFF))
        )
    } else {
        ThemePaint(
            colorScheme = TgIceScheme,
            chatPalette = TgIcePalette,
            sentBubbleSpec = SentBubbleSpec(Color(0xFFD8E9F9), Color(0xFF1C2733), Color(0xFF5E87AC))
        )
    }
}
