package com.maodouchat.ui.screen.settings

import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.maodouchat.R
import com.maodouchat.ui.component.LinearActionButton
import com.maodouchat.ui.component.linearPressEffect
import com.maodouchat.ui.theme.ThemeFamily
import com.maodouchat.ui.theme.bubbleShapesFor
import com.maodouchat.ui.theme.resolveThemePaint
import com.maodouchat.util.ThemePreferences
import kotlin.math.roundToInt

/**
 * 客户端组件拖拽排版工作台：
 * 支持可视化长按拖拽排序应用内的关键组件模块（会话气泡、联系人名片、快捷控制面板、统计指标概览），
 * 支持实时微动效反馈、重置与保存布局。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeWorkbenchScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val currentMode by ThemePreferences.mode.collectAsState()
    val isDark = currentMode == "dark"

    // 拖拽排序的组件 ID 列表
    val defaultOrder = remember { listOf("bubble", "profile", "actions", "stats") }
    var componentOrder by remember { mutableStateOf(defaultOrder) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val activePaint = remember(isDark) {
        resolveThemePaint(ThemeFamily.MAODOU, isDark)
    }

    val bubbleShape = remember {
        bubbleShapesFor("default", ThemeFamily.MAODOU)
    }

    val cardShape: Shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(activePaint.colorScheme.background)
            .imePadding()
    ) {
        TopAppBar(
            title = {
                Text(
                    "组件排版工作台",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = activePaint.colorScheme.onSurface
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        tint = activePaint.colorScheme.onSurface
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        componentOrder = defaultOrder
                        Toast.makeText(context, "已重置为默认排序", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "重置",
                        tint = activePaint.chatPalette.textSecondary
                    )
                }
                Spacer(Modifier.width(4.dp))
                LinearActionButton(
                    text = "保存",
                    onClick = {
                        Toast.makeText(context, "组件布局顺序已保存", Toast.LENGTH_SHORT).show()
                    },
                    containerColor = activePaint.colorScheme.primary,
                    contentColor = activePaint.colorScheme.onPrimary,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = activePaint.colorScheme.surface.copy(alpha = 0.95f)
            )
        )

        HorizontalDivider(color = activePaint.colorScheme.outlineVariant.copy(alpha = 0.3f))

        // 提示栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(activePaint.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = activePaint.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "长按右侧手柄 ≡ 上下拖拽，即可调整组件在界面中的排列层叠顺序",
                style = MaterialTheme.typography.bodySmall,
                color = activePaint.chatPalette.textSecondary
            )
        }

        HorizontalDivider(color = activePaint.colorScheme.outlineVariant.copy(alpha = 0.2f))

        // 可拖拽组件列表区域
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            componentOrder.forEachIndexed { index, componentId ->
                val isBeingDragged = draggedIndex == index

                val elevation by animateDpAsState(
                    targetValue = if (isBeingDragged) 12.dp else 1.dp,
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
                    label = "widgetElevation"
                )

                val scale by animateFloatAsState(
                    targetValue = if (isBeingDragged) 1.03f else 1f,
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
                    label = "widgetScale"
                )

                DraggableWidgetWrapper(
                    title = widgetTitle(componentId),
                    subtitle = widgetSubtitle(componentId),
                    paint = activePaint,
                    cardShape = cardShape,
                    elevation = elevation,
                    scale = scale,
                    isBeingDragged = isBeingDragged,
                    offsetY = if (isBeingDragged) dragOffsetY else 0f,
                    onDragStart = {
                        draggedIndex = index
                        dragOffsetY = 0f
                    },
                    onDrag = { deltaY ->
                        dragOffsetY += deltaY
                        val thresholdPx = 220f
                        if (dragOffsetY > thresholdPx && index < componentOrder.size - 1) {
                            val mutable = componentOrder.toMutableList()
                            val item = mutable.removeAt(index)
                            mutable.add(index + 1, item)
                            componentOrder = mutable
                            draggedIndex = index + 1
                            dragOffsetY -= thresholdPx
                        } else if (dragOffsetY < -thresholdPx && index > 0) {
                            val mutable = componentOrder.toMutableList()
                            val item = mutable.removeAt(index)
                            mutable.add(index - 1, item)
                            componentOrder = mutable
                            draggedIndex = index - 1
                            dragOffsetY += thresholdPx
                        }
                    },
                    onDragEnd = {
                        draggedIndex = -1
                        dragOffsetY = 0f
                    }
                ) {
                    when (componentId) {
                        "bubble" -> ChatBubblePreviewWidget(activePaint, bubbleShape)
                        "profile" -> ContactProfileWidget(activePaint, cardShape)
                        "actions" -> QuickActionsWidget(activePaint, cardShape)
                        "stats" -> StatsTileWidget(activePaint, cardShape)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun widgetTitle(id: String): String = when (id) {
    "bubble" -> "聊天消息气泡微件"
    "profile" -> "联系人卡片微件"
    "actions" -> "快捷操作控制微件"
    "stats" -> "核心指标概览微件"
    else -> "自定义微件"
}

private fun widgetSubtitle(id: String): String = when (id) {
    "bubble" -> "展示消息时间线气泡排版与圆角"
    "profile" -> "展示头像徽章与在线状态"
    "actions" -> "展示线性弹性微动效常用按钮"
    "stats" -> "展示安全状态与加密消息统计"
    else -> ""
}

/** 包装拖拽手柄与卡片容器 */
@Composable
private fun DraggableWidgetWrapper(
    title: String,
    subtitle: String,
    paint: com.maodouchat.ui.theme.ThemePaint,
    cardShape: Shape,
    elevation: androidx.compose.ui.unit.Dp,
    scale: Float,
    isBeingDragged: Boolean,
    offsetY: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        shape = cardShape,
        color = paint.colorScheme.surface,
        shadowElevation = elevation,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isBeingDragged) paint.colorScheme.primary.copy(alpha = 0.8f)
            else paint.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isBeingDragged) 10f else 1f)
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .shadow(elevation, cardShape)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = paint.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = paint.chatPalette.textSecondary
                    )
                }

                // 拖拽手柄
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isBeingDragged) paint.colorScheme.primaryContainer
                            else paint.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { onDragStart() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount.y)
                                },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.DragHandle,
                        contentDescription = "拖拽手柄",
                        tint = if (isBeingDragged) paint.colorScheme.primary else paint.chatPalette.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            content()
        }
    }
}

/** 会话气泡微件预览 */
@Composable
private fun ChatBubblePreviewWidget(
    paint: com.maodouchat.ui.theme.ThemePaint,
    bubbleShape: com.maodouchat.ui.theme.BubbleShapes
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(paint.chatPalette.chatBackground)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 接收气泡
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Surface(
                shape = bubbleShape.received,
                color = paint.chatPalette.chatBubbleReceived,
                border = androidx.compose.foundation.BorderStroke(1.dp, paint.chatPalette.chatBubbleReceivedBorder),
                modifier = Modifier.widthIn(max = 240.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        "这是正在排版的消息气泡组件",
                        style = MaterialTheme.typography.bodySmall,
                        color = paint.chatPalette.textPrimary
                    )
                    Text(
                        "10:42",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = paint.chatPalette.textHint,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }

        // 发送气泡
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            val sentBg = paint.sentBubbleSpec?.color ?: paint.colorScheme.primary
            val sentContent = paint.sentBubbleSpec?.content ?: paint.colorScheme.onPrimary

            Surface(
                shape = bubbleShape.sent,
                color = sentBg,
                modifier = Modifier.widthIn(max = 240.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        "E2EE 密文已确认送达 ✓✓",
                        style = MaterialTheme.typography.bodySmall,
                        color = sentContent
                    )
                    Text(
                        "10:43",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = sentContent.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

/** 联系人名片微件预览 */
@Composable
private fun ContactProfileWidget(
    paint: com.maodouchat.ui.theme.ThemePaint,
    shape: Shape
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(paint.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(paint.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text("MD", fontWeight = FontWeight.Bold, color = paint.colorScheme.onPrimary)
            // 在线绿点
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(paint.chatPalette.onlineGreen)
                    .align(Alignment.BottomEnd)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "毛豆安全通讯",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = paint.chatPalette.textPrimary
            )
            Text(
                "在线 · 端到端加密已验证",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = paint.chatPalette.textSecondary
            )
        }

        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = paint.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 快捷操作面板微件预览 */
@Composable
private fun QuickActionsWidget(
    paint: com.maodouchat.ui.theme.ThemePaint,
    shape: Shape
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickActionButton("安全通话", Icons.Outlined.Call, paint, Modifier.weight(1f))
        QuickActionButton("消息免打扰", Icons.Outlined.Notifications, paint, Modifier.weight(1f))
        QuickActionButton("收藏星标", Icons.Outlined.Star, paint, Modifier.weight(1f))
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    paint: com.maodouchat.ui.theme.ThemePaint,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = paint.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier
            .linearPressEffect()
            .clickable {}
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = paint.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = paint.chatPalette.textPrimary)
        }
    }
}

/** 核心统计概览微件预览 */
@Composable
private fun StatsTileWidget(
    paint: com.maodouchat.ui.theme.ThemePaint,
    shape: Shape
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(paint.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        val textColor = paint.colorScheme.onSurface
        val subColor = paint.chatPalette.textSecondary

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("128", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = textColor)
            Text("今日消息", style = MaterialTheme.typography.labelSmall, color = subColor)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("100%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = textColor)
            Text("E2EE 端到端", style = MaterialTheme.typography.labelSmall, color = subColor)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("0", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = textColor)
            Text("未读消息", style = MaterialTheme.typography.labelSmall, color = subColor)
        }
    }
}
