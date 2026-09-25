package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.NotificationSettingsRequest
import com.maodouchat.network.NotificationSettingsResponse

/**
 * 通知设置的**远端**对账（G328c）。
 *
 * 与 `ClientPrefsNetworkRepository` 分开：那份是客户端偏好（主题/壁纸之类），
 * 这份是**服务端要用的**通知开关（决定推不推、推什么）。分开的理由是它们的
 * 真源不同——通知设置必须与服务端一致（否则推送行为与用户看到的不符），
 * 偏好则是本机权威。
 *
 * 刻意很薄（不缓存、不重试）：缓存它会让「在另一台设备关掉了推送」在本机看起来还开着。
 */
internal class NotificationSettingsNetworkRepository(
    private val getSettingsApi: suspend (String) -> Result<NotificationSettingsResponse> =
        { token -> ApiService.getNotificationSettings(token) },
    private val updateSettingsApi: suspend (String, NotificationSettingsRequest) -> Result<NotificationSettingsResponse> =
        { token, request -> ApiService.updateNotificationSettings(token, request) },
) {
    suspend fun settings(token: String? = null): Result<NotificationSettingsResponse> =
        getSettingsApi(token ?: currentAccessToken())

    /** 更新；返回值是**服务端最终保存的**设置（可能与请求不同，比如被后台策略强制）。 */
    suspend fun updateSettings(
        token: String? = null,
        request: NotificationSettingsRequest,
    ): Result<NotificationSettingsResponse> =
        updateSettingsApi(token ?: currentAccessToken(), request)
}
