package com.maodouchat.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally

fun MotionSettings.wallpaperEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(200)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.97f)
}


fun MotionSettings.fontScaleEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.95f)
}


fun MotionSettings.soundEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}


fun MotionSettings.ringtoneEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}


fun MotionSettings.hapticsEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(150)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.96f)
}


fun MotionSettings.feelEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}


fun MotionSettings.navSlideEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + slideInHorizontally(animationSpec = tween(d), initialOffsetX = { it / 8 })
}


fun MotionSettings.dndEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.96f)
}


fun MotionSettings.previewEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.95f)
}


fun MotionSettings.pushEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}


fun MotionSettings.voiceCallEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}


fun MotionSettings.videoCallEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.92f)
}


fun MotionSettings.previewMuteEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}


fun MotionSettings.fadeTimerEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}
