package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.NearbyLocationStatusResponse
import com.maodouchat.network.NearbyUserResponse

/**
 * 「附近的人」的**远端**调用（G328c）。
 *
 * 四个端点是一台小状态机：查开没开 → 上报位置（= 开启/续期）→ 停止共享 → 拉附近用户。
 * 放在一个仓库里是因为它们的失败语义相同（定位被系统拒绝、共享过期、半径无人），
 * 并且 `updateNearbyLocation` 既是「开启」也是「续期」——拆开会让这两条路径各写一遍处理。
 *
 * 刻意很薄（不缓存、不重试）：位置必须每次读服务端的过期状态，
 * 缓存它会让「共享已过期」在本机看起来还开着，继续上报位置。
 */
internal class NearbyNetworkRepository(
    private val statusApi: suspend (String) -> Result<NearbyLocationStatusResponse> =
        { token -> ApiService.getNearbyLocationStatus(token) },
    private val updateApi: suspend (String, Double, Double) -> Result<NearbyLocationStatusResponse> =
        { token, latitude, longitude -> ApiService.updateNearbyLocation(token, latitude, longitude) },
    private val stopApi: suspend (String) -> Result<NearbyLocationStatusResponse> =
        { token -> ApiService.stopNearbyLocationSharing(token) },
    private val usersApi: suspend (String, Double, Int) -> Result<List<NearbyUserResponse>> =
        { token, radiusKm, limit -> ApiService.getNearbyUsers(token, radiusKm, limit) },
) {
    /** 当前共享状态（含过期时间）。 */
    suspend fun status(token: String): Result<NearbyLocationStatusResponse> = statusApi(token)

    /**
     * 上报位置。语义上同时是**开启**与**续期**——服务端两者走同一端点，
     * 调用方不必区分（这也是它们共用一个仓库的原因之一）。
     */
    suspend fun updateLocation(token: String, latitude: Double, longitude: Double): Result<NearbyLocationStatusResponse> =
        updateApi(token, latitude, longitude)

    suspend fun stopSharing(token: String): Result<NearbyLocationStatusResponse> = stopApi(token)

    suspend fun nearbyUsers(token: String, radiusKm: Double = 10.0, limit: Int = 50): Result<List<NearbyUserResponse>> =
        usersApi(token, radiusKm, limit)
}
