package com.maodouchat.ui.screen.chatdetail

data class MediaCenterUiState(
    val items: List<MediaCenterItem> = emptyList(),
    val isLoading: Boolean = true,
    /** null while checking lock; true when PIN required and process not unlocked */
    val isChatLocked: Boolean? = null,
    val chatName: String = "",
    val isSecretChat: Boolean = false,
)
