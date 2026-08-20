package com.maodouchat.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

// ─── Primary ────────────────────────────────────────────────
val Primary = Color(0xFF007AFF)          // 主色蓝 - DESIGN.md "Primary Blue (#007AFF)"
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFF0070EB) // YAML primary-container
val OnPrimaryContainer = Color(0xFFFEFCFF)
val PrimaryFixed = Color(0xFFD8E2FF)     // YAML primary-fixed

// ─── Secondary ──────────────────────────────────────────────
val Secondary = Color(0xFF5D5E63)        // YAML secondary
val OnSecondary = Color(0xFFFFFFFF)
val SecondaryContainer = Color(0xFFE0DFE4)
val OnSecondaryContainer = Color(0xFF626267)

// ─── Background & Surface ───────────────────────────────────
val Background = Color(0xFFF8F9FA)       // YAML background
val OnBackground = Color(0xFF191C1D)     // YAML on-background
val Surface = Color(0xFFFFFFFF)          // YAML surface-bright / surface-container-lowest
val OnSurface = Color(0xFF191C1D)        // YAML on-surface
val SurfaceVariant = Color(0xFFE1E3E4)   // YAML surface-variant
val OnSurfaceVariant = Color(0xFF414755) // YAML on-surface-variant
val SurfaceDim = Color(0xFFD9DADB)       // YAML surface-dim
val SurfaceContainerLowest = Color(0xFFFFFFFF)
val SurfaceContainerLow = Color(0xFFF3F4F5)
val SurfaceContainer = Color(0xFFEDEEEF)
val SurfaceContainerHigh = Color(0xFFE7E8E9)
val SurfaceContainerHighest = Color(0xFFE1E3E4)

// ─── Chat ───────────────────────────────────────────────────
val ChatBackground = Color(0xFFF0F2F5)   // HTML chat-detail body bg
val ChatBubbleSent = Color(0xFF007AFF)   // Primary blue for sent bubbles
val ChatBubbleReceived = Color(0xFFFFFFFF) // 白色
val ChatBubbleReceivedBorder = Color(0xFFE9E9EB) // HTML chat-received-border
val ChatInputBackground = Color(0xFFF8F9FA) // DESIGN.md chat input bg
val ChatInputBorder = Color(0xFFC7C7CC)  // DESIGN.md chat input border
val ChatInputPlaceholder = Color(0xFFC7C7CC)

// ─── System Message ─────────────────────────────────────────
val SystemMessageBg = Color(0x0D000000)  // 5% 黑
val SystemMessageText = Color(0xFF717786) // YAML outline

// ─── Status ─────────────────────────────────────────────────
val OnlineGreen = Color(0xFF34C759)      // iOS system green
val UnreadRed = Color(0xFFFF3B30)        // iOS system red (badge)
val Error = Color(0xFFBA1A1A)            // YAML error
val OnError = Color(0xFFFFFFFF)
val ErrorContainer = Color(0xFFFFDAD6)   // YAML error-container
val OnErrorContainer = Color(0xFF93000A)

// ─── Text Hierarchy ─────────────────────────────────────────
val TextPrimary = Color(0xFF191C1D)      // YAML on-surface
val TextSecondary = Color(0xFF717786)    // YAML outline (用于次要文字)
val TextHint = Color(0xFFC7C7CC)         // 提示/占位文字
val TextWhite = Color(0xFFFFFFFF)
val TextWhiteSecondary = Color(0xCCFFFFFF) // 80% 白

// ─── Divider & Outline ──────────────────────────────────────
val Divider = Color(0xFFE1E3E4)          // YAML surface-variant
val Outline = Color(0xFF717786)          // YAML outline
val OutlineVariant = Color(0xFFC1C6D7)   // YAML outline-variant

// ─── Inverse ────────────────────────────────────────────────
val InverseSurface = Color(0xFF2E3132)
val InverseOnSurface = Color(0xFFF0F1F2)
val InversePrimary = Color(0xFFADC6FF)

// ─── Avatar Gradient Colors ─────────────────────────────────
val AvatarGradients = listOf(
    listOf(Color(0xFF60A5FA), Color(0xFF3B82F6)), // 蓝 (Tailwind blue-400 to blue-600)
    listOf(Color(0xFFA78BFA), Color(0xFF8B5CF6)), // 紫 (Tailwind violet-400 to violet-600)
    listOf(Color(0xFFF472B6), Color(0xFFEC4899)), // 粉 (Tailwind pink-400 to pink-500)
    listOf(Color(0xFF34D399), Color(0xFF10B981)), // 绿 (Tailwind emerald-400 to emerald-600)
    listOf(Color(0xFFFBBF24), Color(0xFFF59E0B)), // 黄 (Tailwind amber-400 to amber-600)
    listOf(Color(0xFFFB923C), Color(0xFFF97316))  // 橙 (Tailwind orange-400 to orange-600)
)

// Dark 调优使聊天背景在弱光下稳定、发送气泡仍然保持品牌色调但降饱和。
val ChatBackgroundDark = Color(0xFF0F1419)
val ChatBubbleReceivedDark = Color(0xFF1E2229)
val ChatBubbleReceivedBorderDark = Color(0xFF2A2E36)
val ChatInputBackgroundDark = Color(0xFF1A1D23)
val ChatInputBorderDark = Color(0xFF2F333B)
val ChatInputPlaceholderDark = Color(0xFF666B72)
val SystemMessageBgDark = Color(0x16FFFFFF)
val SystemMessageTextDark = Color(0xFF9AA1AB)
// 9.261：深色对比度校准——#6B7077 在深色背景仅 3.2:1（时间戳/提示看不清），
// 提亮到 #8A9099（背景上 5.76:1 / 深色气泡上 4.96:1，均达 WCAG AA）
val TextHintDark = Color(0xFF8A9099)
val TextPrimaryDark = Color(0xFFE4E6EA)
val TextSecondaryDark = Color(0xFF9AA1AB)
val DividerDark = Color(0xFF2A2E36)
val UnreadRedDark = Color(0xFFFF453A)
val OnlineGreenDark = Color(0xFF30D158)

// ─── Accent Colors (Telegram/QQ inspired) ───────────────────
val AccentPurple = Color(0xFF8B5CF6)
val AccentTeal = Color(0xFF06B6D4)
val AccentPink = Color(0xFFEC4899)
val AccentOrange = Color(0xFFF97316)
val AccentIndigo = Color(0xFF6366F1)

// ─── Chat Bubble Sent Gradients ─────────────────────────────
val ChatBubbleSentGradient = listOf(
    Color(0xFF007AFF),
    Color(0xFF0091FF)
)
val ChatBubbleSentGradientDark = listOf(
    Color(0xFF0A84FF),
    Color(0xFF0A6FFF)
)

// ─── 聊天气泡颜色（自定义主题色 · 仅影响聊天页发送气泡）────────
object ChatBubbleColorPalette {
    const val BLUE = "blue"
    const val GREEN = "green"
    const val PURPLE = "purple"
    const val ORANGE = "orange"
    const val PINK = "pink"
    const val TEAL = "teal"

    fun light(id: String): Color = when (id) {
        GREEN -> Color(0xFF34C759)
        PURPLE -> Color(0xFF8B5CF6)
        ORANGE -> Color(0xFFF97316)
        PINK -> Color(0xFFEC4899)
        TEAL -> Color(0xFF06B6D4)
        else -> Color(0xFF007AFF)
    }

    fun dark(id: String): Color = when (id) {
        GREEN -> Color(0xFF30D158)
        PURPLE -> Color(0xFFA78BFA)
        ORANGE -> Color(0xFFFF9F0A)
        PINK -> Color(0xFFF472B6)
        TEAL -> Color(0xFF22D3EE)
        else -> Color(0xFF0A84FF)
    }

    fun normalize(raw: String?): String {
        val id = raw?.trim()?.lowercase().orEmpty()
        return if (id in setOf(BLUE, GREEN, PURPLE, ORANGE, PINK, TEAL)) id else BLUE
    }

    /** 发送气泡渐变（浅色端略亮，保留原蓝色梯度观感）。 */
    fun gradient(base: Color): List<Color> {
        val lifted = runCatching {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(base.toArgb(), hsv)
            hsv[2] = (hsv[2] * 1.08f).coerceAtMost(1f)
            androidx.compose.ui.graphics.Color.hsv(hsv[0], hsv[1], hsv[2])
        }.getOrDefault(base)
        return listOf(base, lifted)
    }
}

/** 当前会话的发送气泡颜色（默认品牌蓝；由设置页「聊天气泡颜色」与账号偏好控制）。 */
val LocalChatBubbleColor = androidx.compose.runtime.staticCompositionLocalOf { Color(0xFF007AFF) }

// ─── Story / Moment Ring Gradient ───────────────────────────
val StoryRingGradient = listOf(
    Color(0xFF007AFF),
    Color(0xFF8B5CF6),
    Color(0xFFEC4899)
)

// ─── Shimmer Colors ─────────────────────────────────────────
val ShimmerLight = Color(0xFFE8E8EA)
val ShimmerDark = Color(0xFF2A2E36)

// ─── Dark Mode Extended Tokens ──────────────────────────────
val BackgroundDark = Color(0xFF1C1C1E)
val SurfaceDark = Color(0xFF2C2C2E)
val SurfaceVariantDark = Color(0xFF3A3A3C)
val SurfaceContainerLowestDark = Color(0xFF1C1C1E)
val SurfaceContainerLowDark = Color(0xFF242426)
val SurfaceContainerDark = Color(0xFF2C2C2E)
val SurfaceContainerHighDark = Color(0xFF343436)
val SurfaceContainerHighestDark = Color(0xFF3A3A3C)
val DividerDarkExtended = Color(0xFF38383A)
val OutlineDark = Color(0xFF9AA1AB)
val OutlineVariantDark = Color(0xFF48484A)
val ErrorDark = Color(0xFFFF453A)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
val SecondaryDark = Color(0xFF8E8E93)
val SecondaryContainerDark = Color(0xFF3A3A3C)
val OnSecondaryContainerDark = Color(0xFFDEDEE0)

// ─── Verification Badge Colors ──────────────────────────────
val VerifiedBlue = Color(0xFF007AFF)
val VerifiedBlueDark = Color(0xFF0A84FF)

// ─── Swipe Action Colors (chat list) ────────────────────────
val SwipePinColor = Color(0xFF007AFF)
val SwipeMuteColor = Color(0xFF8E8E93)
val SwipeArchiveColor = Color(0xFF34C759)
val SwipeDeleteColor = Color(0xFFFF3B30)
val SwipeReadColor = Color(0xFF8B5CF6)

// ─── Typing Indicator Colors ───────────────────────────────
val TypingDotColor = Color(0xFF007AFF)
val TypingDotColorDark = Color(0xFF0A84FF)
val TypingBgColor = Color(0x0D000000)
val TypingBgColorDark = Color(0x16FFFFFF)

// ─── Badge Gradient ────────────────────────────────────────
val BadgeGradient = listOf(
    Color(0xFFFF3B30),
    Color(0xFFFF6B6B)
)

// ─── Pull to Refresh Colors ────────────────────────────────
val PullToRefreshColor = Color(0xFF007AFF)
val PullToRefreshColorDark = Color(0xFF0A84FF)

// ─── Dark High-Contrast Tokens (WCAG AA, 2026-08-01) ──────
// 深色模式下原有 token 对比度不足：TextHintDark 3.41:1、ChatInputPlaceholderDark 3.14:1
// 均低于小字号 4.5:1 要求，OutlineVariantDark 1.86:1 低于 UI 组件 3:1 要求。
// 以下为达标替换值（对 BackgroundDark / ChatInputBackgroundDark 计算）：
val TextHintDarkHigh = Color(0xFFA6ACB2)            // 7.4:1 on BackgroundDark
val ChatInputPlaceholderDarkHigh = Color(0xFF9AA1AB) // 6.5:1 on ChatInputBackgroundDark
val OutlineVariantDarkHigh = Color(0xFF6E6E72)      // 3.3:1 on BackgroundDark（UI 组件 3:1）
