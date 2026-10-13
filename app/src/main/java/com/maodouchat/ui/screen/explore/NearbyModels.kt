package com.maodouchat.ui.screen.explore

import com.maodouchat.data.model.User
import com.maodouchat.util.NearbyPolicy

data class NearbyPerson(val user: User, val distanceMeters: Int, val locationUpdatedAt: Long)

data class NearbyUiState(
    val items: List<NearbyPerson> = emptyList(),
    val isLoading: Boolean = false,
    val isSharing: Boolean = false,
    val expiresAt: Long = 0,
    val radiusKm: Double = NearbyPolicy.DEFAULT_RADIUS_KM,
    val errorMessage: String? = null
)

