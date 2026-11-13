package com.maodouchat.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

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
