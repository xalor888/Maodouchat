package com.maodouchat.server.model

import kotlinx.serialization.Serializable

// 隐私与附近 DTO：隐私设置、位置上报与附近的人。


@Serializable
data class UserPrivacyResponse(
    val showOnline: Boolean = true,
    val showStatus: Boolean = true,
    val searchable: Boolean = true,
    val defaultPostVisibility: String = "PUBLIC",
    val onlineVisibility: String = "everyone"
)


@Serializable
data class UpdatePrivacyRequest(
    val showOnline: Boolean? = null,
    val showStatus: Boolean? = null,
    val searchable: Boolean? = null,
    val defaultPostVisibility: String? = null,
    val onlineVisibility: String? = null
)


@Serializable
data class UpdateNearbyLocationRequest(
    val latitude: Double,
    val longitude: Double
)


@Serializable
data class NearbyLocationStatusResponse(
    val sharing: Boolean,
    val expiresAt: Long = 0
)


@Serializable
data class NearbyUserResponse(
    val user: UserResponse,
    val distanceMeters: Int,
    val locationUpdatedAt: Long
)
