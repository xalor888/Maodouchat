package com.maodouchat.explore.usecase

import com.maodouchat.network.NearbyLocationStatusResponse
import com.maodouchat.network.NearbyUserResponse
import com.maodouchat.network.api.SocialApi
import com.maodouchat.network.api.SocialApiClient

class ResolveNearbyUseCase(
    private val socialApi: SocialApi = SocialApiClient
) {
    suspend fun getNearbyLocationStatus(token: String): Result<NearbyLocationStatusResponse> =
        socialApi.getNearbyLocationStatus(token)

    suspend fun updateNearbyLocation(
        token: String,
        latitude: Double,
        longitude: Double
    ): Result<NearbyLocationStatusResponse> =
        socialApi.updateNearbyLocation(token, latitude, longitude)

    suspend fun stopNearbyLocationSharing(token: String): Result<NearbyLocationStatusResponse> =
        socialApi.stopNearbyLocationSharing(token)

    suspend fun getNearbyUsers(
        token: String,
        radiusKm: Double = 10.0,
        limit: Int = 50
    ): Result<List<NearbyUserResponse>> =
        socialApi.getNearbyUsers(token, radiusKm, limit)
}
