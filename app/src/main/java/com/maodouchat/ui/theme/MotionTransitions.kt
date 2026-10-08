package com.maodouchat.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState

@Composable
fun rememberMotionPulse(
    initialValue: Float,
    targetValue: Float,
    durationMillis: Int,
    label: String,
    active: Boolean = true,
    staticValue: Float = targetValue
): State<Float> {
    val motion = LocalMotionSettings.current
    if (!active || !motion.animationsEnabled) return rememberUpdatedState(staticValue)

    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        initialValue = initialValue,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = motion.duration(durationMillis)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "${label}Value"
    )
}


/** Telegram-like soft enter for chat list rows / bubbles. */
fun MotionSettings.listItemEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(220)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 8 }
    )
}

/** Soft bottom-sheet / composer accessory enter. */
fun MotionSettings.composerBarEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(200)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 5 }
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

fun MotionSettings.bubblePressScale(): Float = if (animationsEnabled) 0.98f else 1f


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


/** Soft enter for ephemeral toasts / capture alerts. */
fun MotionSettings.toastEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 4 }
    )
}

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

/** Soft enter for chat header status / presence strip. */
fun MotionSettings.statusBarEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(150)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { -it / 8 }
    )
}

/** Soft enter for reply / edit strip above composer. */
fun MotionSettings.replyStripEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 6 }
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

/** Soft enter for pin / secret pin banners. */
fun MotionSettings.pinBannerEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { -it / 5 }
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

/** Soft pop for reaction pickers / emoji trays. */
fun MotionSettings.reactionTrayEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.9f
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

/** Soft enter for contacts / friend request rows. */
fun MotionSettings.contactRowEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 10 }
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

/** Soft enter for block / report confirmation panels. */
fun MotionSettings.safetyPanelEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 7 }
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

/** Soft enter for nearby person rows. */
fun MotionSettings.nearbyRowEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + slideInHorizontally(
        animationSpec = tween(d),
        initialOffsetX = { it / 10 }
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

/** Soft enter for chat-lock overlays / PIN sheets. */
fun MotionSettings.chatLockEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.94f
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

/** Soft enter for poll cards / vote sheets. */
fun MotionSettings.pollCardEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 7 }
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

/** Soft enter for composer draft restore strip. */
fun MotionSettings.draftStripEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 8 }
    )
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

/** Soft enter for invite link panels. */

/** Soft enter for nudge action chips. */
fun MotionSettings.nudgeChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + scaleIn(
        animationSpec = tween(d),
        initialScale = 0.9f
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

/** Soft enter for contact cards. */
fun MotionSettings.contactCardEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(
        animationSpec = tween(d),
        initialOffsetY = { it / 9 }
    )
}


fun MotionSettings.spoilerRevealEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(200)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.96f)
}
fun MotionSettings.downloadChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(150)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(animationSpec = tween(d), initialOffsetY = { it / 10 })
}


fun MotionSettings.locationPinEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.92f)
}
fun MotionSettings.fileChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(animationSpec = tween(d), initialOffsetY = { it / 10 })
}
fun MotionSettings.secretBannerEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(animationSpec = tween(d), initialOffsetY = { it / 8 })
}
fun MotionSettings.secureShieldEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.9f)
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
fun MotionSettings.aiSummaryEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + slideInVertically(animationSpec = tween(d), initialOffsetY = { it / 8 })
}
fun MotionSettings.aiRewriteEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}

fun MotionSettings.aiSuggestEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}

fun MotionSettings.aiTranscribeEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}

fun MotionSettings.aiAnalyzeEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}

fun MotionSettings.aiGroupAssistEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}

fun MotionSettings.aiFileAnalyzeEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}

fun MotionSettings.aiSemanticEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}

fun MotionSettings.gifChipEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.92f)
}

fun MotionSettings.watermarkEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(200)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.96f)
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

fun MotionSettings.unreadEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}

fun MotionSettings.ringtoneEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}

fun MotionSettings.soundEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
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

fun MotionSettings.taskReminderEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}

fun MotionSettings.dndEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(190)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.96f)
}

fun MotionSettings.offlineAiEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
}

fun MotionSettings.feelEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(160)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}

fun MotionSettings.hapticsEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(150)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.96f)
}

fun MotionSettings.chatAnimEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.94f)
}

fun MotionSettings.navSlideEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + slideInHorizontally(animationSpec = tween(d), initialOffsetX = { it / 8 })
}

fun MotionSettings.shieldEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.95f)
}

fun MotionSettings.recentsEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(170)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.96f)
}

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

fun MotionSettings.fadeTimerEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
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

fun MotionSettings.previewMuteEnter(): EnterTransition {
    if (!animationsEnabled) return EnterTransition.None
    val d = duration(180)
    return fadeIn(animationSpec = tween(d)) + scaleIn(animationSpec = tween(d), initialScale = 0.93f)
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
