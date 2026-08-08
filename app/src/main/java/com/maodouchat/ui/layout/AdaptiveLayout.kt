package com.maodouchat.ui.layout

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.Divider
import kotlin.math.roundToInt

/**
 * B5 平板双栏布局（AdaptiveLayout）。
 *
 * - 屏幕宽度 ≥ [TWO_PANE_MIN_WIDTH_DP]（且非竖屏窄窗）时启用左右双栏：
 *   左 = [listPane]，右 = [detailPane]；
 * - 否则回退为 [narrowContent]（单栏，由调用方决定显示列表还是详情）；
 * - 分隔条可拖拽调节左栏宽度（手势优化：拖动即时更新、双击重置默认、边界夹持），
 *   宽度比例持久化到 SharedPreferences（设备级偏好，不随账号切换）。
 *
 * 纯新增组件：不触碰任何现有文件，供 NavGraph/主容器在平板模式接线。
 */

object AdaptiveLayoutConstants {
    /** 进入双栏的最小宽度 */
    const val TWO_PANE_MIN_WIDTH_DP = 840

    const val MIN_LIST_FRACTION = 0.28f
    const val MAX_LIST_FRACTION = 0.50f
    const val DEFAULT_LIST_FRACTION = 0.32f

    const val DIVIDER_WIDTH_DP = 4

    private const val PREFS = "adaptive_layout"
    private const val KEY_FRACTION = "list_fraction"

    internal fun savedFraction(context: Context): Float {
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_FRACTION, DEFAULT_LIST_FRACTION)
        }.getOrDefault(DEFAULT_LIST_FRACTION).coerceIn(MIN_LIST_FRACTION, MAX_LIST_FRACTION)
    }

    internal fun saveFraction(context: Context, fraction: Float) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_FRACTION, fraction.coerceIn(MIN_LIST_FRACTION, MAX_LIST_FRACTION))
                .apply()
        }
    }
}

/** 双栏状态（含可持久化的左栏宽度比例） */
class AdaptiveLayoutState internal constructor(
    val isTwoPane: Boolean,
    val listPaneFraction: Float,
    val onListPaneFractionChange: (Float) -> Unit,
    val onResetFraction: () -> Unit,
)

/** 依据当前窗口宽度判定是否启用双栏（宽屏且宽度 ≥ 高度，防竖屏窄窗误判） */
fun isTwoPaneEligible(configuration: android.content.res.Configuration): Boolean =
    configuration.screenWidthDp >= AdaptiveLayoutConstants.TWO_PANE_MIN_WIDTH_DP &&
        configuration.screenWidthDp >= configuration.screenHeightDp

@Composable
fun rememberAdaptiveLayoutState(): AdaptiveLayoutState {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isTwoPane = remember(configuration) { isTwoPaneEligible(configuration) }
    var fraction by rememberSaveable { mutableFloatStateOf(AdaptiveLayoutConstants.savedFraction(context)) }

    fun clamp(f: Float): Float = f.coerceIn(
        AdaptiveLayoutConstants.MIN_LIST_FRACTION,
        AdaptiveLayoutConstants.MAX_LIST_FRACTION,
    )

    return remember(isTwoPane) {
        AdaptiveLayoutState(
            isTwoPane = isTwoPane,
            listPaneFraction = clamp(fraction),
            onListPaneFractionChange = { f ->
                val c = clamp(f)
                fraction = c
                AdaptiveLayoutConstants.saveFraction(context, c)
            },
            onResetFraction = {
                fraction = AdaptiveLayoutConstants.DEFAULT_LIST_FRACTION
                AdaptiveLayoutConstants.saveFraction(context, AdaptiveLayoutConstants.DEFAULT_LIST_FRACTION)
            },
        )
    }
}

/**
 * 双栏布局主体。
 *
 * @param narrowContent 窄屏时渲染的内容（由调用方选择列表/详情）
 * @param listPane 左栏（列表）
 * @param detailPane 右栏（详情）
 */
@Composable
fun AdaptiveLayout(
    state: AdaptiveLayoutState,
    modifier: Modifier = Modifier,
    narrowContent: @Composable () -> Unit = {},
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
) {
    if (!state.isTwoPane) {
        narrowContent()
        return
    }
    val fraction = state.listPaneFraction
    Row(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(fraction)
                .fillMaxHeight()
        ) {
            listPane()
        }
        AdaptiveDivider(
            fraction = fraction,
            onFractionChange = state.onListPaneFractionChange,
            onReset = state.onResetFraction,
        )
        Box(
            modifier = Modifier
                .weight(1f - fraction)
                .fillMaxHeight()
        ) {
            detailPane()
        }
    }
}

/** 可拖拽分隔条：拖动调宽、双击重置默认宽度 */
@Composable
private fun AdaptiveDivider(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val dividerWidth = AdaptiveLayoutConstants.DIVIDER_WIDTH_DP.dp
    val min = AdaptiveLayoutConstants.MIN_LIST_FRACTION
    val max = AdaptiveLayoutConstants.MAX_LIST_FRACTION
    Box(
        modifier = Modifier
            .width(dividerWidth)
            .fillMaxHeight()
            .background(Divider)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onReset() })
            }
            .pointerInput(fraction) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val next = fraction + dragAmount.x / size.width
                    onFractionChange(next.coerceIn(min, max))
                }
            }
    )
}

