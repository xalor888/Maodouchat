package com.maodouchat.settings.model

/**
 * 设置页的界面状态（从 `ui/screen/settings/SettingsViewModel.kt` 抽出）。
 *
 * 拆它的理由：SettingsViewModel 是 1300+ 行的热点文件，而状态模型与逻辑混在一个文件里。
 * 顺带说明 `PrivacyField`/`LoadedPrivacy` 从 private 改为 internal——换文件后同一模块内
 * 仍可用，但不对外暴露。
 */
import com.maodouchat.network.UserDto
import com.maodouchat.network.DeviceInfoDto

data class SettingsUiState(
    val userName: String = "",
    val userId: String = "",
    val userAvatar: String? = null,
    val userStatus: String = "",
    val userUsername: String? = null,
    val publicProfileUrl: String? = null,
    val isModerator: Boolean = false,
    val isLoggedOut: Boolean = false,
    val isEditing: Boolean = false,
    val editName: String = "",
    val showUsernameDialog: Boolean = false,
    val editUsername: String = "",
    val showPrivacyDialog: Boolean = false,
    val showStatusDialog: Boolean = false,
    val showBlockedUsersDialog: Boolean = false,
    val showOnline: Boolean = true,
    val onlineVisibility: String = "everyone",
    val showStatus: Boolean = true,
    val editStatus: String = "",
    val searchable: Boolean = true,
    val defaultPostVisibility: String = "PUBLIC",
    val blockedUsers: List<UserDto> = emptyList(),
    val devices: List<DeviceInfoDto> = emptyList(),
    val currentDeviceId: Int = 1,
    val isUploading: Boolean = false,
    val isSaving: Boolean = false,
    val isSavingPrivacy: Boolean = false,
    val isDeletingAccount: Boolean = false,
    val isLoggingOutAll: Boolean = false,
    val isLoadingBlockedUsers: Boolean = false,
    val isUpdatingBlockedUsers: Boolean = false,
    val isLoadingDevices: Boolean = false,
    val removingDeviceId: Int? = null,
    val renamingDeviceId: Int? = null,
    val confirmingDeviceId: Int? = null,
    val successMessage: String? = null,
    val errorMessage: String? = null
)

internal enum class PrivacyField { SHOW_ONLINE, SHOW_STATUS, SEARCHABLE, DEFAULT_POST_VISIBILITY, ONLINE_VISIBILITY }

internal data class LoadedPrivacy(
    val ownerUserId: String,
    val showOnline: Boolean,
    val showStatus: Boolean,
    val searchable: Boolean,
    val defaultPostVisibility: String,
    val onlineVisibility: String
)

