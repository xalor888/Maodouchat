package com.maodouchat.server.service

data class ReasonTemplate(
    val code: String,
    val labelZh: String,
    val defaultDays: Int,
    val requiresCustomNote: Boolean = false
)

/**
 * Group mute templates (hours). Used by admin catalog + future group-admin UX;
 * site-wide ban still uses [banReasonTemplates].
 */
data class MuteTemplate(
    val code: String,
    val labelZh: String,
    val durationHours: Int,
    val requiresCustomNote: Boolean = false
)

/**
 * Site-wide post (moments) restriction templates (days).
 * Enforced by rejectIfPostRestricted on create/upload paths.
 */
data class PostRestrictTemplate(
    val code: String,
    val labelZh: String,
    val defaultDays: Int,
    val requiresCustomNote: Boolean = false
)

data class MessageRestrictTemplate(
    val code: String,
    val labelZh: String,
    val defaultDays: Int,
    val requiresCustomNote: Boolean = false
)
