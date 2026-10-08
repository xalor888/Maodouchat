package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.local.entity.AiTaskEntity

data class AiTasksUiState(
    val tasks: List<AiTaskEntity> = emptyList(),
    val isLoading: Boolean = true,
    val mutatingTaskIds: Set<String> = emptySet(),
    val error: String? = null,
    /** null while checking; true when PIN required and process not unlocked */
    val isChatLocked: Boolean? = null,
    val chatName: String = "",
    val isSecretChat: Boolean = false,
)

internal enum class AiTaskFilter { ALL, PENDING, COMPLETED }
