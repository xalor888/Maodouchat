package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.maodouchat.ui.component.linearPressEffect
import kotlin.math.roundToInt

/**
 * 工作台微件预览簇（拖拽包装 + 气泡/名片/快捷操作/统计微件），从 ThemeWorkbenchScreen.kt 按簇搬出。
 */

internal fun widgetTitle(id: String): String = when (id) {
    "bubble" -> "聊天消息气泡微件"
    "profile" -> "联系人卡片微件"
    "actions" -> "快捷操作控制微件"
    "stats" -> "核心指标概览微件"
    else -> "自定义微件"
}

internal fun widgetSubtitle(id: String): String = when (id) {
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
    paint: com.maodouchat.theme.ThemePaint,
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
    paint: com.maodouchat.theme.ThemePaint,
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
    paint: com.maodouchat.theme.ThemePaint,
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
    paint: com.maodouchat.theme.ThemePaint,
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
    paint: com.maodouchat.theme.ThemePaint,
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
    paint: com.maodouchat.theme.ThemePaint,
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

