package com.maodouchat.core.model

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

enum class PrivacyField { SHOW_ONLINE, SHOW_STATUS, SEARCHABLE, DEFAULT_POST_VISIBILITY, ONLINE_VISIBILITY }

data class LoadedPrivacy(
    val ownerUserId: String,
    val showOnline: Boolean,
    val showStatus: Boolean,
    val searchable: Boolean,
    val defaultPostVisibility: String,
    val onlineVisibility: String
)

