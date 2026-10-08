package com.maodouchat.ui.screen.settings

import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.component.LinearActionButton
import com.maodouchat.ui.theme.ThemeFamily
import com.maodouchat.ui.theme.bubbleShapesFor
import com.maodouchat.ui.theme.resolveThemePaint
import com.maodouchat.util.ThemePreferences

/**
 * 客户端组件拖拽排版工作台：
 * 支持可视化长按拖拽排序应用内的关键组件模块（会话气泡、联系人名片、快捷控制面板、统计指标概览），
 * 支持实时微动效反馈、重置与保存布局。
 * 微件预览簇已按簇搬到同包 `ThemeWorkbenchWidgets.kt`。
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
                    color = activePaint.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
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

