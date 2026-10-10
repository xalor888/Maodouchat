package com.maodouchat.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.maodouchat.theme.ChatPalette
import com.maodouchat.theme.SentBubbleSpec
import com.maodouchat.theme.ThemePaint

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


/** 解析当前主题家族 + 深浅 → 完整绘制参数。MAODOU 为白底液态玻璃；TG_* 为 Telegram 扁平主题。 */
fun resolveThemePaint(family: ThemeFamily, dark: Boolean): ThemePaint {
    val maodou = ThemePaint(
        colorScheme = if (dark) MaodouDarkScheme else MaodouLightScheme,
        chatPalette = if (dark) DarkChatPalette else LightChatPalette,
        sentBubbleSpec = if (dark) {
            SentBubbleSpec(Color(0xFF2A2A2A), Color(0xFFF2F2F2), Color(0xB3F2F2F2))
        } else {
            SentBubbleSpec(Color(0xFFF2F2F2), Color(0xFF1A1A1A), Color(0x991A1A1A))
        }
    )
    if (family == ThemeFamily.MAODOU) return maodou

    // TG 浅色发送气泡用经典浅绿 #EFFDDE（配深墨字）；TG 深色沿用近黑灰气泡（配浅字）。
    val tgLightSent = SentBubbleSpec(Color(0xFFEFFDDE), Color(0xFF1A1A1A), Color(0x991A1A1A))
    val tgDarkSent = SentBubbleSpec(Color(0xFF2A2A2A), Color(0xFFF2F2F2), Color(0xB3F2F2F2))
    return when (family) {
        ThemeFamily.TG_CLASSIC -> ThemePaint(
            colorScheme = if (dark) TgDarkScheme else TgClassicScheme,
            chatPalette = if (dark) TgDarkPalette else TgClassicPalette,
            sentBubbleSpec = if (dark) tgDarkSent else tgLightSent,
        )
        ThemeFamily.TG_MIDNIGHT -> ThemePaint(
            colorScheme = if (dark) TgMidnightScheme else TgClassicScheme,
            chatPalette = if (dark) TgMidnightPalette else TgClassicPalette,
            sentBubbleSpec = if (dark) tgDarkSent else tgLightSent,
        )
        ThemeFamily.TG_GRAPHITE -> ThemePaint(
            colorScheme = if (dark) TgGraphiteScheme else TgClassicScheme,
            chatPalette = if (dark) TgGraphitePalette else TgClassicPalette,
            sentBubbleSpec = if (dark) tgDarkSent else tgLightSent,
        )
        ThemeFamily.MAODOU -> maodou
    }
}
