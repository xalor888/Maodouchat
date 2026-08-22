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
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Legacy readable diagonal overlay. Secret-chat surfaces no longer use this
 * (XAL-45: full-page invisible DWT+SVD via [secretPageBlindWatermark]).
 * Kept for unit tests of [buildBlindWatermarkLabel] and any non-secret caller.
 */
@Composable
fun Modifier.blindWatermark(
    label: String,
    enabled: Boolean,
    alpha: Float = 0.14f,
    textColor: Color = Color(0xFF1A1A1A)
): Modifier {
    if (!enabled || label.isBlank()) return this
    val density = LocalDensity.current
    val textSizePx = with(density) { 11.dp.toPx() }
    val stepX = with(density) { 148.dp.toPx() }
    val stepY = with(density) { 72.dp.toPx() }
    val paintColor = textColor.copy(alpha = alpha.coerceIn(0.04f, 0.22f)).toArgb()
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
