/*
 * 液态按压（LiquidPress）— 移植自 Murexide 的 liquidglass 组件族，
 * 原始实现来自 AndroidLiquidGlass catalog（Copyright 2025 Kyant, Apache License 2.0）。
 * 只取纯 Compose 可移植部分：阻尼按压缩放 + 跟随触点的柔和加色高光；
 * 不引入 com.kyant.backdrop / RuntimeShader 依赖。
 */
package com.maodouchat.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 触点手势检查：按下→拖动→抬起/取消。与 Murexide 的 DragGestureInspector 一致（Apache 2.0）。 */
private suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val down = awaitFirstDown(requireUnconsumed = false)
        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        val upEvent = drag(initialDown.id, onDrag)
        if (upEvent == null) onDragCancel() else onDragEnd(upEvent)
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
): PointerInputChange? {
    if (currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true) return null
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change, change.positionChange())
        pointer = change.id
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) return dragEvent
            pointer = otherDown.id
        } else if (dragEvent.previousPosition != dragEvent.position) {
            return dragEvent
        }
    }
}

/**
 * 液态按压修饰符：
 * - 按下时以阻尼弹簧缩到 [pressedScale]，抬起弹回；缩放以内容中心为轴
 * - 按压期间在触点位置渲染柔和加色高光，移动跟随，抬起回落淡出
 * - 不消费任何指针事件，不影响点击/长按/滑动手势语义
 */
fun Modifier.liquidPress(
    pressedScale: Float = 0.975f,
    highlightAlpha: Float = 0.10f,
    enabled: Boolean = true,
): Modifier = composed {
    if (!enabled) return@composed this
    val scope: CoroutineScope = rememberCoroutineScope()

    // 弹簧参数沿用 DampedDragAnimation：scaleX d=0.6/stiff=250，scaleY d=0.7/stiff=250，
    // pressProgress/highlight d=0.5/stiff=300
    val scale = remember { Animatable(1f) }
    val pressProgress = remember { Animatable(0f, 0.001f) }
    val position = remember { Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold) }
    var startPosition by remember { mutableStateOf(Offset.Zero) }

    fun release() {
        scope.launch {
            launch { pressProgress.animateTo(0f, spring(0.5f, 300f, 0.001f)) }
            launch { position.animateTo(startPosition, spring(0.5f, 300f, Offset.VisibilityThreshold)) }
            launch { scale.animateTo(1f, spring(0.7f, 250f, 0.001f)) }
        }
    }

    Modifier
        .drawWithContent {
            val s = scale.value
            if (s != 1f) {
                drawContext.canvas.save()
                drawContext.transform.scale(s, s, pivot = center)
                drawContent()
                drawContext.canvas.restore()
            } else {
                drawContent()
            }
            val progress = pressProgress.value
            if (progress > 0f) {
                // 无 RuntimeShader 的退化路径：整面微亮 + 触点多层同心圆近似径向高光
                drawRect(Color.White.copy(alpha = 0.05f * progress), blendMode = BlendMode.Plus)
                val clamped = Offset(
                    position.value.x.coerceIn(0f, size.width),
                    position.value.y.coerceIn(0f, size.height)
                )
                val radiusBase = size.minDimension * 1.1f
                for (layer in 0 until 4) {
                    val t = layer / 3f
                    drawCircle(
                        color = Color.White.copy(alpha = highlightAlpha * progress * (1f - t)),
                        radius = radiusBase * (0.35f + 0.55f * t),
                        center = clamped,
                        blendMode = BlendMode.Plus
                    )
                }
            }
        }
        .pointerInput(Unit) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPosition = down.position
                    scope.launch {
                        launch { pressProgress.animateTo(1f, spring(0.5f, 300f, 0.001f)) }
                        launch { position.snapTo(startPosition) }
                        launch { scale.animateTo(pressedScale, spring(0.6f, 250f, 0.001f)) }
                    }
                },
                onDragEnd = { release() },
                onDragCancel = { release() }
            ) { change, _ ->
                scope.launch { position.snapTo(change.position) }
            }
        }
}
