package com.maodouchat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.maodouchat.util.ThemePreferences

private val LightColorScheme = lightColorScheme(
    primary = Primary, onPrimary = OnPrimary, primaryContainer = PrimaryContainer, onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary, onSecondary = OnSecondary, secondaryContainer = SecondaryContainer, onSecondaryContainer = OnSecondaryContainer,
    background = Background, onBackground = OnBackground, surface = Surface, onSurface = OnSurface,
    surfaceVariant = SurfaceVariant, onSurfaceVariant = OnSurfaceVariant, error = Error, onError = OnError,
    errorContainer = ErrorContainer, onErrorContainer = OnErrorContainer, outline = Outline, outlineVariant = OutlineVariant
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF0A84FF), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFF004880), onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF8E8E93), onSecondary = Color(0xFFFFFFFF), background = Color(0xFF1C1C1E), onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF2C2C2E), onSurface = Color(0xFFF2F2F7), surfaceVariant = Color(0xFF3A3A3C), onSurfaceVariant = Color(0xFFEBEBF5),
    error = Color(0xFFFF453A), outline = Color(0xFF48484A), outlineVariant = Color(0xFF636366)
)

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

@Composable
fun MaodouchatTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // 用户主题偏好（general_settings + 云同步）：system / light / dark；可响应多端拉取
    val ctx = LocalContext.current
    ThemePreferences.ensureSeeded(ctx)
    val themePref by ThemePreferences.mode.collectAsState()
    val useDark = when (themePref) { "dark" -> true; "light" -> false; else -> darkTheme }
    val motionSettings = rememberSystemMotionSettings()

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
        LocalChatPalette provides (if (useDark) DarkChatPalette else LightChatPalette),
        LocalMotionSettings provides motionSettings
    ) {
        MaterialTheme(colorScheme = if (useDark) DarkColorScheme else LightColorScheme, typography = MaodouchatTypography, shapes = MaodouchatShapes, content = content)
    }
}
