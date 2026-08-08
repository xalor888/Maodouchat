package com.maodouchat.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.maodouchat.util.MediaViewerPolicy
import kotlinx.coroutines.launch

@Composable
fun ZoomableAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onSingleTap: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val scaleAnimatable = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }

    var currentScale by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var currentOffset by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }

    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )

    LaunchedEffect(scaleAnimatable.value) {
        currentScale = scaleAnimatable.value
    }
    // 桥接 offsetX/offsetY 到 currentOffset，否则双击缩放的回中动画无法生效（动画死代码）
    LaunchedEffect(offsetX.value, offsetY.value) {
        currentOffset = currentOffset.copy(x = offsetX.value, y = offsetY.value)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = MediaViewerPolicy.clampScale(currentScale * zoom)
                    currentScale = next
                    if (next > 1.01f) {
                        currentOffset += pan
                    } else {
                        currentOffset = Offset.Zero
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        val target = MediaViewerPolicy.nextDoubleTapScale(currentScale)
                        scope.launch {
                            launch {
                                scaleAnimatable.animateTo(
                                    targetValue = target,
                                    animationSpec = springSpec
                                )
                            }
                            if (target <= 1.01f) {
                                launch {
                                    offsetX.animateTo(0f, springSpec)
                                    offsetY.animateTo(0f, springSpec)
                                }
                            }
                        }
                    },
                    onTap = {
                        if (currentScale > 1.05f) {
                            scope.launch {
                                launch {
                                    scaleAnimatable.animateTo(1f, springSpec)
                                }
                                launch {
                                    offsetX.animateTo(0f, springSpec)
                                    offsetY.animateTo(0f, springSpec)
                                }
                            }
                        } else {
                            onSingleTap?.invoke()
                        }
                    }
                )
            }
    ) {
        AsyncImage(
            model = when (model) {
                is String, is android.net.Uri, is java.io.File ->
                    OwnerScopedImageKeys.request(
                        context = context,
                        data = model,
                        sizeWidth = 2048,
                        sizeHeight = 2048,
                    )
                else -> model
            },
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = currentScale
                    scaleY = currentScale
                    translationX = currentOffset.x
                    translationY = currentOffset.y
                }
        )
    }
}
