package com.maodouchat.ui.theme

import com.maodouchat.util.RuntimeFlags
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

data class MotionSettings(
    val animationsEnabled: Boolean,
    val durationScale: Float
) {
    fun duration(baseMillis: Int): Int = MotionPolicy.scaledDuration(baseMillis, this)

    /** 关闭动画时瞬时到位；开启时用 spring，主路径按压/选中统一入口。 */
    fun springSpec(
        dampingRatio: Float = Spring.DampingRatioNoBouncy,
        stiffness: Float = Spring.StiffnessMedium
    ): FiniteAnimationSpec<Float> =
        if (!animationsEnabled) snap() else spring(dampingRatio = dampingRatio, stiffness = stiffness)

    /** 关闭动画时瞬时到位；开启时按系统 scale 缩放 tween 时长。 */
    fun tweenSpec(baseMillis: Int = MotionTokens.Standard): FiniteAnimationSpec<Float> {
        val millis = duration(baseMillis)
        return if (millis <= 0) snap() else tween(durationMillis = millis)
    }

    // ── 统一 LazyItem animateItem 规格（所有列表复用，消除跳变） ──

    /** 列表项重排/位移规格：轻弹 spring，接近 Telegram/微信列表滑动质感。 */
    fun listItemPlacementSpec(): FiniteAnimationSpec<androidx.compose.ui.unit.IntOffset>? =
        if (!animationsEnabled) null
        else spring(dampingRatio = 0.85f, stiffness = 380f)

    /** 列表项淡入规格：新项出现时柔和渐显。 */
    fun listItemFadeInSpec(): FiniteAnimationSpec<Float>? =
        if (!animationsEnabled) null
        else tween(durationMillis = duration(MotionTokens.Fast))

    /** 列表项淡出规格：移除时快速淡出，不阻塞后续项位移。 */
    fun listItemFadeOutSpec(): FiniteAnimationSpec<Float>? =
        if (!animationsEnabled) null
        else tween(durationMillis = duration(MotionTokens.Fast))

    // ── 统一消息气泡进出场过渡 ──

    /** 新消息入场：fade + 微缩放，接近 Telegram 气泡弹入感。 */
    fun messageEnterTransition(): EnterTransition {
        val ms = duration(MotionTokens.Emphasized)
        if (ms <= 0) return EnterTransition.None
        return fadeIn(tween(ms)) + scaleIn(tween(ms), initialScale = 0.92f)
    }

    /** 消息移除出场：fade + 收缩，为粒子特效让路时也保持柔和。 */
    fun messageExitTransition(): ExitTransition {
        val ms = duration(MotionTokens.Fast)
        if (ms <= 0) return ExitTransition.None
        return fadeOut(tween(ms)) + scaleOut(tween(ms), targetScale = 0.9f)
    }

    // ── 统一页面/Sheet 转场 ──

    /** 页面入场：从右侧滑入 + 淡入，标准 Material 推入感。 */
    fun pageEnterTransition(): EnterTransition {
        val ms = duration(MotionTokens.Standard)
        if (ms <= 0) return EnterTransition.None
        return fadeIn(tween(ms)) + slideInVertically(tween(ms)) { it / 12 }
    }

    /** 页面出场：淡出 + 微缩。 */
    fun pageExitTransition(): ExitTransition {
        val ms = duration(MotionTokens.Fast)
        if (ms <= 0) return ExitTransition.None
        return fadeOut(tween(ms)) + scaleOut(tween(ms), targetScale = 0.98f)
    }
}

object MotionTokens {
    const val Instant = 0
    const val Fast = 120
    const val Standard = 200
    const val Emphasized = 280
    const val Particle = 520
}

/** Pure policy kept separate from Android settings so duration behavior is unit-testable. */
object MotionPolicy {
    private const val MAX_DURATION_SCALE = 2f
    private const val MAX_INITIAL_LIST_ITEMS = 5
    private const val LIST_STAGGER_STEP_MILLIS = 32
    private const val MAX_LIST_STAGGER_MILLIS = 160

    fun resolve(animatorScale: Float, transitionScale: Float): MotionSettings {
        val enabled = animatorScale > 0f && transitionScale > 0f
        if (!enabled) return MotionSettings(animationsEnabled = false, durationScale = 0f)
        return MotionSettings(
            animationsEnabled = true,
            durationScale = minOf(animatorScale, transitionScale).coerceAtMost(MAX_DURATION_SCALE)
        )
    }

    fun scaledDuration(baseMillis: Int, settings: MotionSettings): Int {
        if (!settings.animationsEnabled || baseMillis <= 0) return 0
        return (baseMillis * settings.durationScale)
            .roundToInt()
            .coerceIn(1, baseMillis * MAX_DURATION_SCALE.toInt())
    }

    fun shouldAnimateInitialListEntry(
        index: Int,
        settings: MotionSettings,
        maxAnimatedItems: Int = MAX_INITIAL_LIST_ITEMS
    ): Boolean = settings.animationsEnabled && index >= 0 && index < maxAnimatedItems.coerceAtLeast(0)

    fun initialListEntryDelay(
        index: Int,
        settings: MotionSettings,
        maxAnimatedItems: Int = MAX_INITIAL_LIST_ITEMS
    ): Int {
        if (!shouldAnimateInitialListEntry(index, settings, maxAnimatedItems)) return 0
        return settings.duration(index * LIST_STAGGER_STEP_MILLIS).coerceAtMost(MAX_LIST_STAGGER_MILLIS)
    }
}

val LocalMotionSettings = compositionLocalOf {
    MotionSettings(animationsEnabled = true, durationScale = 1f)
}

@Composable
internal fun rememberSystemMotionSettings(): MotionSettings {
    val context = LocalContext.current
    val resolver = context.contentResolver

    fun readSettings(): MotionSettings {
        val base = MotionPolicy.resolve(
            animatorScale = Settings.Global.getFloat(
                resolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ),
            transitionScale = Settings.Global.getFloat(
                resolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f
            )
        )
        // Admin runtime can force-disable chat motion even when system animations are on.
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.CHAT_ANIMATIONS)) {
            return MotionSettings(animationsEnabled = false, durationScale = 0f)
        }
        return base
    }

    var motionSettings by remember(resolver) { mutableStateOf(readSettings()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                motionSettings = readSettings()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer
        )
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),
            false,
            observer
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return motionSettings
}

/**
 * Infinite state feedback that becomes a stable value when system animations are disabled.
 * [active] prevents work when the represented process is not actually running.
 */
