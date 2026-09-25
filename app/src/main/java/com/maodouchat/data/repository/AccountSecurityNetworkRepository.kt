package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.CurrentUserPublicResponse
import com.maodouchat.network.DeleteAccountResponse
import com.maodouchat.network.DeviceInfoDto
import com.maodouchat.network.SetUsernameResponse
import com.maodouchat.network.UserDto

/**
 * 账号与设备安全相关的**远端**调用（G328c）。
 *
 * 覆盖「设置 → 账号与安全」那一屏真正需要的东西：资料与头像、用户名、设备清单与设备确认、
 * 全端下线、注销账号、拉黑名单。
 *
 * 为什么不塞进 `settings/repository/VersionedSettingsRepository`：那个仓库管的是
 * **可版本化的偏好**（隐私开关、客户端偏好，带冲突合并与乐观并发），而这里的操作是
 * 一次性命令（改名字、删设备、注销），没有版本语义。混在一起会让「哪些操作需要版本号」
 * 变成需要读实现的隐知识。
 *
 * 刻意很薄（不缓存、不重试），构造器接受 lambda 因而自身可测。
 */
internal class AccountSecurityNetworkRepository(
    private val updateProfileApi: suspend (String, String?, String?) -> Result<UserDto> =
        { token, name, status -> ApiService.updateProfile(token, name, status) },
    private val uploadAvatarApi: suspend (String, String) -> Result<String> =
        { token, base64 -> ApiService.uploadAvatar(token, base64) },
    private val removeAvatarApi: suspend (String) -> Result<Unit> = { token -> ApiService.removeAvatar(token) },
    private val setUsernameApi: suspend (String, String) -> Result<SetUsernameResponse> =
        { token, username -> ApiService.setUsername(token, username) },
    private val clearUsernameApi: suspend (String) -> Result<Unit> = { token -> ApiService.clearUsername(token) },
    private val getDevicesApi: suspend (String, String, Int?) -> Result<List<DeviceInfoDto>> =
        { token, userId, currentDeviceId -> ApiService.getDevices(token, userId, currentDeviceId) },
    private val renameDeviceApi: suspend (String, Int, String) -> Result<Unit> =
        { token, deviceId, name -> ApiService.renameMyDevice(token, deviceId, name) },
    private val removeDeviceApi: suspend (String, Int) -> Result<Unit> =
        { token, deviceId -> ApiService.removeMyDevice(token, deviceId) },
    private val confirmDeviceApi: suspend (String, Int, Int, String) -> Result<Unit> =
        { token, deviceId, approverDeviceId, signature ->
            ApiService.confirmMyDevice(token, deviceId, approverDeviceId, signature)
        },
    private val logoutAllApi: suspend (String) -> Result<Unit> = { token -> ApiService.logoutAll(token) },
    private val deleteAccountApi: suspend (String, String) -> Result<DeleteAccountResponse> =
        { token, password -> ApiService.deleteAccount(token, password) },
    private val currentUserPublicApi: suspend (String) -> Result<CurrentUserPublicResponse> =
        { token -> ApiService.getCurrentUserPublic(token) },
    private val blockedDetailsApi: suspend (String) -> Result<List<UserDto>> =
        { token -> ApiService.getBlockedUserDetails(token) },
    private val unblockApi: suspend (String, String) -> Result<Unit> =
        { token, userId -> ApiService.unblockUser(token, userId) },
    private val privacyApi: suspend (String) -> Result<com.maodouchat.network.UserPrivacyDto> =
        { token -> ApiService.getPrivacy(token) },
    private val updatePrivacyApi: suspend (String, String?) -> Result<com.maodouchat.network.UserPrivacyDto> =
        { token, defaultPostVisibility -> ApiService.updatePrivacy(token, defaultPostVisibility = defaultPostVisibility) },
    private val changePasswordApi: suspend (String, String, String) -> Result<Unit> =
        { token, oldPassword, newPassword -> ApiService.changePassword(token, oldPassword, newPassword) },
) {
    suspend fun updateProfile(token: String, name: String? = null, status: String? = null): Result<UserDto> =
        updateProfileApi(token, name, status)

    suspend fun uploadAvatar(token: String, base64Data: String): Result<String> =
        uploadAvatarApi(token, base64Data)

    suspend fun removeAvatar(token: String): Result<Unit> = removeAvatarApi(token)

    suspend fun setUsername(token: String, username: String): Result<SetUsernameResponse> =
        setUsernameApi(token, username)

    suspend fun clearUsername(token: String): Result<Unit> = clearUsernameApi(token)

    suspend fun devices(token: String, userId: String, currentDeviceId: Int? = null): Result<List<DeviceInfoDto>> =
        getDevicesApi(token, userId, currentDeviceId)

    suspend fun renameDevice(token: String, deviceId: Int, deviceName: String): Result<Unit> =
        renameDeviceApi(token, deviceId, deviceName)

    suspend fun removeDevice(token: String, deviceId: Int): Result<Unit> = removeDeviceApi(token, deviceId)

    suspend fun confirmDevice(token: String, deviceId: Int, approverDeviceId: Int, signature: String): Result<Unit> =
        confirmDeviceApi(token, deviceId, approverDeviceId, signature)

    suspend fun logoutAll(token: String): Result<Unit> = logoutAllApi(token)

    suspend fun deleteAccount(token: String, password: String): Result<DeleteAccountResponse> =
        deleteAccountApi(token, password)

    suspend fun currentUserPublic(token: String): Result<CurrentUserPublicResponse> =
        currentUserPublicApi(token)

    suspend fun blockedUserDetails(token: String? = null): Result<List<UserDto>> =
        blockedDetailsApi(token ?: currentAccessToken())

    suspend fun unblock(token: String? = null, userId: String): Result<Unit> =
        unblockApi(token ?: currentAccessToken(), userId)

    /**
     * 改密码。归在本类而不是「设置页仓库」：服务端通常要求旧密码 + 会失效其他会话，
     * 与 `deleteAccount`/`logoutAll` 是同一族「账号级凭据操作」。
     */
    suspend fun changePassword(token: String, oldPassword: String, newPassword: String): Result<Unit> =
        changePasswordApi(token, oldPassword, newPassword)

    /**
     * 隐私设置（谁能看到在线状态、能不能被搜到、动态默认可见性）。
     *
     * `updatePrivacy` 在 `ApiService` 上有 5 个可空字段，这里**只暴露改得动的那个**：
     * 目前只有「动态默认可见性」在客户端有入口，其余由服务端/其他端维护。
     * 把 5 个都透传会让调用方以为能改别的，其实是给了一个改不动的旋钮。
     */
    suspend fun privacy(token: String): Result<com.maodouchat.network.UserPrivacyDto> = privacyApi(token)

    suspend fun updateDefaultPostVisibility(
        token: String,
        defaultPostVisibility: String?,
    ): Result<com.maodouchat.network.UserPrivacyDto> = updatePrivacyApi(token, defaultPostVisibility)
}
