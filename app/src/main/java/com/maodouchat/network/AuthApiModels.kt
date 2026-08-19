package com.maodouchat.network

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val email: String, val password: String, val totpCode: String = "")

@Serializable
data class RegisterRequest(val name: String, val email: String, val password: String)

@Serializable
data class AuthResponse(
    val token: String = "",
    val userId: String = "",
    val name: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val refreshExpiresAt: Long = 0,
    val requiresTotp: Boolean = false,
    val totpEnabled: Boolean = false
)

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
    val deviceId: String = ""
)

@Serializable
data class UserDto(val id: String, val name: String, val email: String = "", val avatar: String? = null, val status: String = "", val isOnline: Boolean = false, val isModerator: Boolean = false, val lastSeen: Long = 0, val username: String? = null)

@Serializable
data class CurrentUserPublicResponse(val user: UserDto? = null, val publicProfileUrl: String? = null)

@Serializable
data class PublicProfileResponse(val ok: Boolean = false, val user: PublicProfileUserDto? = null)

@Serializable
data class PublicProfileUserDto(val id: String, val name: String, val username: String? = null, val avatar: String? = null, val status: String = "", val isOnline: Boolean = false, val isModerator: Boolean = false, val lastSeen: Long = 0)

@Serializable
data class SetUsernameRequest(val username: String)

@Serializable
data class SetUsernameResponse(val ok: Boolean = false, val username: String? = null)

@Serializable
data class AdvancedSearchRequest(val query: String, val type: String = "", val dateFrom: Long = 0, val dateTo: Long = 0, val chatId: String = "", val limit: Int = 50)

@Serializable
data class AdvancedSearchResponse(val ok: Boolean = false, val results: List<MessageSearchResultDto> = emptyList())

@Serializable
data class MessageSearchResultDto(val id: String, val chatId: String, val senderId: String, val content: String, val type: String = "TEXT", val timestamp: Long = 0, val status: String = "SENT")

@Serializable
data class UserPrivacyDto(
    val showOnline: Boolean = true,
    val showStatus: Boolean = true,
    val searchable: Boolean = true,
    val defaultPostVisibility: String = "PUBLIC"
)

@Serializable
data class UpdatePrivacyRequest(
    val showOnline: Boolean? = null,
    val showStatus: Boolean? = null,
    val searchable: Boolean? = null,
    val defaultPostVisibility: String? = null
)

@Serializable
data class UpdateDeviceNameRequest(val deviceName: String)

@Serializable
data class ConfirmDeviceRequest(
    val approverDeviceId: Int,
    val signature: String
)

@Serializable
data class DeviceInfoDto(
    val userId: String,
    val deviceId: Int,
    // 8.52 契约修复：与服务端默认值「我的设备」对齐（encodeDefaults=false 时默认键被省略）
    val deviceName: String = "我的设备",
    val identityKey: String,
    val lastSeenAt: Long? = null,
    val isCurrent: Boolean = false,
    val status: String = "CONFIRMED",
    val confirmedAt: Long? = null,
    val confirmedByDeviceId: Int? = null
)

@Serializable
data class PreKeyDataDto(val keyId: Int, val publicKey: String)

@Serializable
data class UploadKeysRequest(
    val registrationId: Int,
    val deviceId: Int,
    val deviceName: String? = null,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val preKeys: List<PreKeyDataDto>
)

@Serializable
data class PreKeyBundleDto(
    val registrationId: Int,
    val deviceId: Int,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val preKeyId: Int? = null,
    val preKey: String? = null
)

@Serializable
data class DevicePreKeyBundleDto(
    val userId: String,
    val deviceId: Int,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val preKeyId: Int? = null,
    val preKey: String? = null
)

@Serializable
data class ErrorResponse(val error: String, val code: String? = null, val retryAfterSeconds: Long? = null)

@Serializable
data class SendCodeRequest(val email: String, val purpose: String = "register")

@Serializable
data class RegisterWithCodeRequest(val name: String, val email: String, val password: String, val code: String)

@Serializable
data class ResetPasswordRequest(val email: String, val code: String, val newPassword: String)

@Serializable
data class SendFriendRequestBody(val toUserId: String, val message: String = "")

@Serializable
data class FriendRequestDto(
    val id: String,
    val fromUser: UserDto,
    val toUser: UserDto,
    val message: String = "",
    val status: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@Serializable
data class ChatFolderDto(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val chatIds: List<String> = emptyList(),
    val updatedAt: Long = 0
)

@Serializable
data class ChatFoldersSyncRequest(val folders: List<ChatFolderDto> = emptyList())

@Serializable
data class ChatFoldersSyncResponse(
    val folders: List<ChatFolderDto> = emptyList(),
    val updatedAt: Long = 0
)

@Serializable
data class ClientPrefsDto(
    val themeMode: String = "system",
    val themeStyle: String = "maodou",
    val accentColor: String = "none",
    val languageMode: String = "system",
    val chatWallpaper: String = "default",
    val chatFontScale: String = "normal",
    val linkPreviewEnabled: Boolean = true,
    val unreadPriorityEnabled: Boolean = true,
    val writingStyleEnabled: Boolean = false,
    val writingStylePreset: String = "none",
    val writingStyleCustom: String = "",
    val appLockTimeoutMinutes: Long = 5,
    val screenSecureEnabled: Boolean = false,
    val sensitiveGateEnabled: Boolean = true,
    val updatedAt: Long = 0
)

@Serializable
data class ClientPrefsUpdateRequest(
    val themeMode: String? = null,
    val themeStyle: String? = null,
    val accentColor: String? = null,
    val languageMode: String? = null,
    val chatWallpaper: String? = null,
    val chatFontScale: String? = null,
    val linkPreviewEnabled: Boolean? = null,
    val unreadPriorityEnabled: Boolean? = null,
    val writingStyleEnabled: Boolean? = null,
    val writingStylePreset: String? = null,
    val writingStyleCustom: String? = null,
    val appLockTimeoutMinutes: Long? = null,
    val screenSecureEnabled: Boolean? = null,
    val sensitiveGateEnabled: Boolean? = null
)

@Serializable
data class UploadAvatarRequest(val base64Data: String)

@Serializable
data class UpdateProfileRequest(val name: String? = null, val status: String? = null)

@Serializable
data class AvatarResponse(val status: String, val avatarUrl: String)

@Serializable
data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)

@Serializable
data class DeleteAccountRequest(val password: String)

@Serializable
data class DeleteAccountResponse(val status: String = "ok", val deletedAt: Long)
