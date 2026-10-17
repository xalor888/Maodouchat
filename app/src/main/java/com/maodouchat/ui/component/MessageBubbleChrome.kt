package com.maodouchat.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

// 语音波形高度常量
internal val WaveHeights = listOf(0.3f, 0.7f, 1f, 0.5f, 0.9f, 0.4f, 0.6f, 0.2f)

// 语音波形高度按 message.id 派生确定性伪随机，每条语音拥有稳定独特的波形
internal fun waveHeightsFor(messageId: String): List<Float> {
    var seed = messageId.hashCode().toLong()
    return List(WaveHeights.size) {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        0.25f + ((seed ushr 33) % 76) / 100f
    }
}

internal fun Modifier.captureBubbleBounds(onBoundsMeasured: ((IntOffset, IntSize) -> Unit)?): Modifier {
    if (onBoundsMeasured == null) return this
    return onGloballyPositioned { coordinates ->
        val position = coordinates.positionInRoot()
        onBoundsMeasured(
            IntOffset(position.x.roundToInt(), position.y.roundToInt()),
            IntSize(coordinates.size.width, coordinates.size.height)
        )
    }
}
