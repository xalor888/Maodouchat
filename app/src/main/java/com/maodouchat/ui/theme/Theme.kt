package com.maodouchat.ui.theme

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.maodouchat.util.ThemePreferences
import kotlinx.coroutines.launch

val MaodouLightScheme = lightColorScheme(
    primary = Primary, onPrimary = OnPrimary, primaryContainer = PrimaryContainer, onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary, onSecondary = OnSecondary, secondaryContainer = SecondaryContainer, onSecondaryContainer = OnSecondaryContainer,
    background = Background, onBackground = OnBackground, surface = Surface, onSurface = OnSurface,
    surfaceVariant = SurfaceVariant, onSurfaceVariant = OnSurfaceVariant, error = Error, onError = OnError,
    errorContainer = ErrorContainer, onErrorContainer = OnErrorContainer, outline = Outline, outlineVariant = OutlineVariant
)

private val LightColorScheme = MaodouLightScheme

// 9.3xx：补全深色 token（此前缺 secondaryContainer/tertiary/errorContainer/inverse* 等，
// 依赖 darkColorScheme 兜底导致弹窗/卡片/进度条等组件深色下配色错乱）。
val MaodouDarkScheme = darkColorScheme(
    primary = Color(0xFF0A84FF), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF004880), onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF8E8E93), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF3A3A3C), onSecondaryContainer = Color(0xFFF2F2F7),
    tertiary = Color(0xFFBF5AF2), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF4A2C63), onTertiaryContainer = Color(0xFFF0D9FF),
    background = Color(0xFF1C1C1E), onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF2C2C2E), onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF3A3A3C), onSurfaceVariant = Color(0xFFEBEBF5),
    error = Color(0xFFFF453A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF6E1A1A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF48484A), outlineVariant = Color(0xFF636366),
    inverseSurface = Color(0xFFF2F2F7), inverseOnSurface = Color(0xFF2C2C2E),
    inversePrimary = Color(0xFF004880),
    surfaceTint = Color(0xFF0A84FF), scrim = Color(0xFF000000)
)

private val DarkColorScheme = MaodouDarkScheme

val MaodouchatTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.02).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.01).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 14.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp)
)

val MaodouchatShapes = Shapes(small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(24.dp))

/** 当前时刻的当日分钟数（0..1439）。 */
private fun currentMinuteOfDay(): Int {
    val cal = java.util.Calendar.getInstance()
    return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
}

@Composable
fun MaodouchatTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // 用户主题偏好（general_settings + 云同步）：system / light / dark；可响应多端拉取
    val ctx = LocalContext.current
    ThemePreferences.ensureSeeded(ctx)
    val themePref by ThemePreferences.mode.collectAsState()
    val themeStylePref by ThemePreferences.family.collectAsState()
    val accentPref by ThemePreferences.accent.collectAsState()
    // 9.211：定时深色——分钟级 ticker 驱动窗口边界自动切换（仅 scheduled 模式活跃）
    val nightStart by ThemePreferences.nightStart.collectAsState()
    val nightEnd by ThemePreferences.nightEnd.collectAsState()
    // 9.258：OLED 纯黑（TG Amoled Black 式）
    val oledBlack by ThemePreferences.oledBlack.collectAsState()
    var currentMinute by remember { mutableIntStateOf(currentMinuteOfDay()) }
    LaunchedEffect(themePref) {
        if (themePref == "scheduled") {
            while (true) {
                currentMinute = currentMinuteOfDay()
                kotlinx.coroutines.delay(30_000L)
            }
        }
    }
    val useDark = when (themePref) {
        "dark" -> true
        "light" -> false
        "scheduled" -> ThemePreferences.isWithinNightWindow(currentMinute, nightStart, nightEnd)
        else -> darkTheme
    }
    // Telegram 级主题风格：按家族 + 深浅解析完整绘制参数；强调色可覆盖主题默认 primary
    // 9.253：自定义主题覆盖层——用户改过的颜色槽位叠加在家族默认之上（revision 驱动刷新）
    val customRevision by com.maodouchat.util.CustomThemeStore.revision.collectAsState()
    val paint = remember(themeStylePref, accentPref, useDark, customRevision, oledBlack) {
        val base = resolveThemePaint(com.maodouchat.ui.theme.ThemeFamily.normalize(themeStylePref), useDark)
        val accent = com.maodouchat.ui.theme.accentFor(accentPref, useDark)
        var withAccent = if (accent == null) base else base.copy(
            colorScheme = base.colorScheme.copy(primary = accent, onPrimary = Color.White)
        )
        // 9.258：OLED 纯黑——深色下背景/surface 压到纯黑，OLED 屏像素关闭省电；
        // 卡片 surface 保留极深灰维持层次，聊天背景同步纯黑
        if (useDark && oledBlack) {
            withAccent = withAccent.copy(
                colorScheme = withAccent.colorScheme.copy(
                    background = Color.Black,
                    surface = Color(0xFF121212),
                    surfaceVariant = Color(0xFF1A1A1A)
                ),
                chatPalette = withAccent.chatPalette.copy(
                    chatBackground = Color.Black,
                    chatInputBackground = Color(0xFF121212),
                    chatElevatedSurface = Color(0xFF121212),
                    chatElevatedSurfaceHigh = Color(0xFF1A1A1A)
                )
            )
        }
        com.maodouchat.util.CustomThemeStore.applyOverrides(ctx, if (useDark) "dark" else "light", withAccent)
    }
    val motionSettings = rememberSystemMotionSettings()

    // 9.205：主题切换帷幕过渡——颜色瞬时切换会生硬，用与新主题同色的帷幕快进快出遮一下
    val themeKey = "$themeStylePref|$themePref|$accentPref"
    val veilAlpha = remember { Animatable(0f) }
    var lastThemeKey by remember { mutableStateOf(themeKey) }
    val veilScope = rememberCoroutineScope()
    LaunchedEffect(themeKey) {
        if (lastThemeKey != themeKey) {
            lastThemeKey = themeKey
            if (motionSettings.animationsEnabled) {
                veilScope.launch {
                    veilAlpha.snapTo(1f)
                    veilAlpha.animateTo(0f, tween(durationMillis = 340, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    val view = LocalView.current
    DisposableEffect(useDark) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !useDark
            WindowInsetsControllerCompat(window, view).isAppearanceLightNavigationBars = !useDark
        }
        onDispose {}
    }
    CompositionLocalProvider(
        LocalChatPalette provides paint.chatPalette,
        LocalSentBubbleSpec provides paint.sentBubbleSpec,
        LocalDarkTheme provides useDark,
        LocalMotionSettings provides motionSettings
    ) {
        MaterialTheme(colorScheme = paint.colorScheme, typography = MaodouchatTypography, shapes = MaodouchatShapes) {
            Box {
                content()
                if (veilAlpha.value > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(paint.colorScheme.background.copy(alpha = veilAlpha.value))
                    )
                }
            }
        }
    }
}
