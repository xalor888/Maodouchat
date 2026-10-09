package com.maodouchat.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically

/** Soft scale+fade for dialogs / confirm sheets. */
fun MotionSettings.dialogEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.94f
    )
}


/** Soft bottom sheet enter for media pickers / overflow panels. */
fun MotionSettings.sheetEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(210)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 3 }
    )
}


/** Soft enter for ephemeral toasts / capture alerts. */
fun MotionSettings.toastEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 4 }
    )
}


/** Soft enter for in-chat banners (secret / disappear / live location). */
fun MotionSettings.bannerEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { -it / 6 }
    )
}


/** Soft enter for chips / sealed TTL labels. */
fun MotionSettings.chipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.92f
    )
}


/** Soft enter for archive / inbox switch chips. */
fun MotionSettings.archiveChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.92f
    )
}


/** Soft enter for app-lock overlays. */
fun MotionSettings.appLockEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(200)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.92f
    )
}


/** Soft enter for chat-lock overlays / PIN sheets. */
fun MotionSettings.chatLockEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.94f
    )
}


/** Soft enter for block / report confirmation panels. */
fun MotionSettings.safetyPanelEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 7 }
    )
}


/** Soft enter for safety-code verification panels. */
fun MotionSettings.safetyCodePanelEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 8 }
    )
}


/** Soft enter for QR panels. */
fun MotionSettings.qrPanelEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.94f
    )
}


fun MotionSettings.secureShieldEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.9f)
}


fun MotionSettings.secretBannerEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(animationSpec = tween(d), initialOffsetY = { it / 8 })
}


fun MotionSettings.shieldEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.95f)
}
