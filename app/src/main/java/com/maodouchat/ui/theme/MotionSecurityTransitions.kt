package com.maodouchat.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically

fun MotionSettings.copyLockEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(175)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}


fun MotionSettings.exportSealEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}


fun MotionSettings.leakWallEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(185)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.92f)
}


fun MotionSettings.forwardSealEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(175)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}


fun MotionSettings.chatExportLockEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}


fun MotionSettings.vaultFenceEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(185)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.91f)
}


fun MotionSettings.sealSprintEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(175)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}


fun MotionSettings.pqxdhDashEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}


fun MotionSettings.certRelayEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(185)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.92f)
}


fun MotionSettings.markSprintEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(175)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}


fun MotionSettings.stampRelayEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(185)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.92f)
}


fun MotionSettings.linkLockEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(175)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}


fun MotionSettings.urlFenceEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(185)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.92f)
}


fun MotionSettings.reactLockEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(175)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}


fun MotionSettings.starSealEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}


fun MotionSettings.metaFenceEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(185)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.92f)
}


fun MotionSettings.invitePanelEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 7 }
    )
}
