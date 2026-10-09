package com.maodouchat.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically

/** Telegram-like soft enter for chat list rows / bubbles. */
fun MotionSettings.listItemEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(220)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 8 }
    )
}


fun MotionSettings.listItemExit(): ExitTransition {
    if (!animationsEnabled) return ExitTransition.None
    val d = duration(160)
    return fadeOut(animationSpec = tween(d)) + slideOutVertically(
        animationSpec = tween(d),
        targetOffsetY = { it / 10 }
    )
}


/** Soft enter for contacts / friend request rows. */
fun MotionSettings.contactRowEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 10 }
    )
}


/** Soft enter for nearby person rows. */
fun MotionSettings.nearbyRowEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + slideInHorizontally(
        animationSpec = tween(d),
        initialOffsetX = { it / 10 }
    )
}


/** Soft enter for chat folder chip strip. */
fun MotionSettings.folderStripEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + slideInHorizontally(
        animationSpec = tween(d),
        initialOffsetX = { -it / 8 }
    )
}


/** Soft enter for moments / post cards. */
fun MotionSettings.momentsCardEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(200)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.97f
    )
}


/** Soft enter for marked-unread indicators. */
fun MotionSettings.markedUnreadEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + slideInHorizontally(
        animationSpec = tween(d),
        initialOffsetX = { -it / 6 }
    )
}


/** Soft enter for mute chips / icons. */
fun MotionSettings.muteChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(150)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.9f
    )
}


/** Soft enter for disappearing-timer pickers. */
fun MotionSettings.disappearTimerEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 8 }
    )
}


/** Soft enter for multi-select action bar. */
fun MotionSettings.selectionBarEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 5 }
    )
}


/** Soft enter for message edit composer strip. */
fun MotionSettings.editStripEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 6 }
    )
}


/** Soft enter for pinned-message banners. */
fun MotionSettings.messagePinEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { -it / 7 }
    )
}


/** Soft enter for revoke / delete confirmation chips. */
fun MotionSettings.revokeChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(150)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.9f
    )
}


/** Soft enter for pinned chat badges. */
fun MotionSettings.pinBadgeEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(150)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.85f
    )
}


/** Soft enter for pin / secret pin banners. */
fun MotionSettings.pinBannerEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { -it / 5 }
    )
}


/** Soft enter for chat header status / presence strip. */
fun MotionSettings.statusBarEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(150)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { -it / 8 }
    )
}


/** Soft enter for media preview tiles. */
fun MotionSettings.mediaEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(200)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.96f
    )
}


fun MotionSettings.photoThumbEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}


fun MotionSettings.videoChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(animationSpec = tween(d), initialOffsetY = { it / 9 })
}


fun MotionSettings.gifChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.92f)
}


fun MotionSettings.fileChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(animationSpec = tween(d), initialOffsetY = { it / 10 })
}


fun MotionSettings.locationPinEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.92f)
}


fun MotionSettings.downloadChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(150)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(animationSpec = tween(d), initialOffsetY = { it / 10 })
}


/** Soft enter for contact cards. */
fun MotionSettings.contactCardEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 9 }
    )
}


/** Soft enter for poll cards / vote sheets. */
fun MotionSettings.pollCardEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 7 }
    )
}
