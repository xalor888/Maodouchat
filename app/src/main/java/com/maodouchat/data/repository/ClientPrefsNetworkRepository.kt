package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.ClientPrefsDto
import com.maodouchat.network.ClientPrefsUpdateRequest

/**
 * 客户端偏好（跨设备同步的那一份）的**远端**调用（G328c）。
 *
 * 与 `data/local` 里的设置存储分得很清：那个是**本机**的真源（立刻生效、离线可用）；
 * 本类只负责与服务器对账。读-改-写的顺序由调用方（`SettingsGeneralSettingsViewModel`）
 * 决定，因为它才知道哪些字段是「本机权威」不该被远端覆盖。
 *
 * 刻意很薄（不缓存、不重试）：缓存会让「在另一台设备改了偏好」永远同步不过来。
 */
internal class ClientPrefsNetworkRepository(
    private val getPrefsApi: suspend (String) -> Result<ClientPrefsDto> =
        { token -> ApiService.getClientPrefs(token) },
    private val putPrefsApi: suspend (String, ClientPrefsUpdateRequest) -> Result<ClientPrefsDto> =
        { token, request -> ApiService.putClientPrefs(token, request) },
) {
    suspend fun prefs(token: String): Result<ClientPrefsDto> = getPrefsApi(token)

    suspend fun putPrefs(token: String, request: ClientPrefsUpdateRequest): Result<ClientPrefsDto> =
        putPrefsApi(token, request)
}
