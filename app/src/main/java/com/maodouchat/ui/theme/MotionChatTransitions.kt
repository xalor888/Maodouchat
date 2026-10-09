package com.maodouchat.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically

/** Soft bottom-sheet / composer accessory enter. */
fun MotionSettings.composerBarEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(200)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 5 }
    )
}


fun MotionSettings.bubblePressScale(): Float = if (animationsEnabled) 0.98f else 1f



/** Soft enter for reply / edit strip above composer. */
fun MotionSettings.replyStripEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 6 }
    )
}


/** Soft pop for reaction pickers / emoji trays. */
fun MotionSettings.reactionTrayEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.9f
    )
}


fun MotionSettings.spoilerRevealEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(200)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.96f)
}


/** Soft enter for AI translate result chips. */
fun MotionSettings.translateChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.92f
    )
}


/** Soft enter for mention suggestion chips. */
fun MotionSettings.mentionChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(150)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.9f
    )
}


/** Soft enter for nudge action chips. */
fun MotionSettings.nudgeChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.9f
    )
}


/** Soft enter for composer draft restore strip. */
fun MotionSettings.draftStripEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 8 }
    )
}


fun MotionSettings.chatAnimEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}


fun MotionSettings.recentsEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.96f)
}


fun MotionSettings.unreadEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}


fun MotionSettings.taskReminderEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}
