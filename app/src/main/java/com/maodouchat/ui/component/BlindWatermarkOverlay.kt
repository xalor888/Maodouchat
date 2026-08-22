package com.maodouchat.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.LocalDarkTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Dark-on-light default; previously used on dark bubbles and was effectively invisible. */
val BlindWatermarkColorLightSurface = Color(0xFF1A1A1A)
/** Light-on-dark so the tile remains perceivable on secret-chat dark bubbles. */
val BlindWatermarkColorDarkSurface = Color(0xFFE8E8E8)

const val BLIND_WATERMARK_ALPHA_DEFAULT = 0.26f
const val BLIND_WATERMARK_ALPHA_MIN = 0.12f
const val BLIND_WATERMARK_ALPHA_MAX = 0.38f

fun shouldDrawBlindWatermark(enabled: Boolean, label: String): Boolean =
    enabled && label.isNotBlank()

fun coerceBlindWatermarkAlpha(alpha: Float): Float =
    alpha.coerceIn(BLIND_WATERMARK_ALPHA_MIN, BLIND_WATERMARK_ALPHA_MAX)

fun blindWatermarkTextColor(darkTheme: Boolean): Color =
    if (darkTheme) BlindWatermarkColorDarkSurface else BlindWatermarkColorLightSurface

/**
 * Diagonal tiled blind watermark drawn above content without consuming pointer events.
 * Label should include user id + wall-clock time so leaked captures remain attributable.
 *
 * Contrast is theme-aware: light text on dark surfaces, dark text on light.
 * Alpha is high enough to be readable in a still capture, low enough not to block reading.
 */
@Composable
fun Modifier.blindWatermark(
    label: String,
    enabled: Boolean,
    alpha: Float = BLIND_WATERMARK_ALPHA_DEFAULT,
    textColor: Color? = null
): Modifier {
    if (!shouldDrawBlindWatermark(enabled, label)) return this
    val density = LocalDensity.current
    val resolvedColor = textColor ?: blindWatermarkTextColor(LocalDarkTheme.current)
    val textSizePx = with(density) { 11.dp.toPx() }
    val stepX = with(density) { 148.dp.toPx() }
    val stepY = with(density) { 72.dp.toPx() }
    val paintColor = resolvedColor.copy(alpha = coerceBlindWatermarkAlpha(alpha)).toArgb()
    val paint = remember(paintColor, textSizePx) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = paintColor
            textSize = textSizePx
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.NORMAL
            )
        }
    }
    return this.drawWithContent {
        drawContent()
        val canvasWidth = size.width
        val canvasHeight = size.height
        val pad = stepX
        var y = -pad
        var row = 0
        val native = drawContext.canvas.nativeCanvas
        while (y < canvasHeight + pad) {
            var x = -pad + if (row % 2 == 0) 0f else stepX / 2f
            while (x < canvasWidth + pad) {
                native.save()
                native.rotate(-28f, x, y)
                native.drawText(label, x, y, paint)
                native.restore()
                x += stepX
            }
            y += stepY
            row++
        }
    }
}

/**
 * Live-updating watermark label for secret surfaces.
 * Refreshes every 8s so a still capture always carries a recent timestamp and userId/chatId forensic payload.
 */
@Composable
fun rememberSecretBlindWatermarkLabel(
    userId: String?,
    chatId: String?,
    deviceHint: String? = null,
    enabled: Boolean
): String {
    if (!enabled) return ""
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(enabled, userId, chatId, deviceHint) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(8_000L)
        }
    }
    return remember(userId, chatId, deviceHint, nowMs) {
        buildBlindWatermarkLabel(
            userId = userId,
            chatId = chatId,
            deviceHint = deviceHint,
            timestampMs = nowMs
        )
    }
}

/**
 * Compact visible attribution tile: MC · uid · chat · device · local time.
 * Truncated so tiles stay readable while still attributing a leaked capture.
 */
fun buildBlindWatermarkLabel(
    userId: String?,
    chatId: String?,
    deviceHint: String? = null,
    timestampMs: Long = System.currentTimeMillis()
): String {
    val uid = userId?.takeIf { it.isNotBlank() }?.let { shortenId(it) } ?: "anon"
    val cid = chatId?.takeIf { it.isNotBlank() }?.let { shortenId(it) } ?: "chat"
    val device = deviceHint?.takeIf { it.isNotBlank() }?.let { shortenId(it, 4) }
    val time = formatWatermarkTime(timestampMs)
    return if (device != null) {
        "MC·$uid·$cid·$device·$time"
    } else {
        "MC·$uid·$cid·$time"
    }
}

fun formatWatermarkTime(timestampMs: Long): String {
    val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    fmt.timeZone = TimeZone.getDefault()
    return fmt.format(Date(timestampMs.coerceAtLeast(0L)))
}

private fun shortenId(value: String, max: Int = 8): String {
    val cleaned = value.filter { it.isLetterOrDigit() }
    if (cleaned.isEmpty()) return value.take(max)
    return if (cleaned.length <= max) cleaned else cleaned.take(max)
}
