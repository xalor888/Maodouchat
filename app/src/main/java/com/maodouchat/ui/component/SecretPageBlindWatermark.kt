package com.maodouchat.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.watermark.FrequencyWatermark
import com.maodouchat.watermark.SecretPageWatermark
import com.maodouchat.watermark.SecretWatermarkPolicy

/**
 * 进入密聊即铺满整页的不可见盲水印纹理（DWT+SVD 载波 + Overlay）。
 * 不绘制可读对角字；[payload] 为空时为 no-op。
 */
@Composable
fun Modifier.secretPageBlindWatermark(payload: ByteArray?): Modifier {
    if (payload == null || payload.size != FrequencyWatermark.PAYLOAD_BYTES) return this
    val payloadHex = FrequencyWatermark.decodePayloadHex(payload)
    val tileImage = remember(payloadHex) {
        val size = SecretPageWatermark.TILE
        val pixels = SecretPageWatermark.composeInvisibleTile(payload, size)
        android.graphics.Bitmap.createBitmap(
            pixels,
            size,
            size,
            android.graphics.Bitmap.Config.ARGB_8888
        ).asImageBitmap()
    }
    return this.drawWithContent {
        drawContent()
        val step = SecretPageWatermark.TILE.toFloat()
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                drawImage(
                    image = tileImage,
                    topLeft = Offset(x, y),
                    alpha = 0.04f,
                    blendMode = BlendMode.Overlay
                )
                x += step
            }
            y += step
        }
    }
}

/** 密聊 + 盲水印开关打开时构造整页载荷；非密聊返回 null。 */
@Composable
fun rememberSecretPageWatermarkPayload(
    isSecretChat: Boolean,
    userId: String?,
    chatId: String?,
    deviceHint: String? = null
): ByteArray? {
    val context = LocalContext.current
    val blindOn = RuntimeFlags.isEnabled(context, RuntimeFlags.BLIND_WATERMARK)
    if (!SecretWatermarkPolicy.pageBlindWatermarkEnabled(isSecretChat, blindOn)) return null
    return remember(userId, chatId, deviceHint) {
        SecretPageWatermark.buildPayload(userId, chatId, deviceHint)
    }
}
