package com.maodouchat.network

import kotlinx.serialization.Serializable

@Serializable
data class NotificationSettingsRequest(
    val enableNotifications: Boolean? = null,
    val soundEnabled: Boolean? = null,
    val previewEnabled: Boolean? = null,
    val ringtoneEnabled: Boolean? = null,
    val dndStartHour: Int? = null,
    val dndEndHour: Int? = null,
    val dndEnabled: Boolean? = null,
    val dndStartMinute: Int? = null,
    val dndEndMinute: Int? = null
)

@Serializable
data class NotificationSettingsResponse(
    val enableNotifications: Boolean = true,
    val soundEnabled: Boolean = true,
    val previewEnabled: Boolean = true,
    val ringtoneEnabled: Boolean = true,
    val dndStartHour: Int = 22,
    val dndEndHour: Int = 7,
    val dndEnabled: Boolean = false,
    val dndStartMinute: Int = 22 * 60,
    val dndEndMinute: Int = 7 * 60,
    val updatedAt: Long = 0
)

@Serializable
data class RegisterPushTokenRequest(
    val deviceId: String,
    val token: String,
    val platform: String = "ANDROID",
    val timezoneOffsetMinutes: Int = 0
)

@Serializable
data class RemovePushTokenRequest(val deviceId: String)
