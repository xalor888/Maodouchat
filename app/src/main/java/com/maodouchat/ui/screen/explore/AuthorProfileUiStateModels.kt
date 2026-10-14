package com.maodouchat.ui.screen.explore

import com.maodouchat.network.PostDto
import com.maodouchat.network.UserDto

data class AuthorProfileUiState(
    val currentUserId: String = "",
    val author: UserDto? = null,
    val posts: List<PostDto> = emptyList(),
    val updatingPostIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
    // 1.287：拉黑/解除拉黑
    val isBlocked: Boolean = false,
    val isBlocking: Boolean = false,
    val infoMessage: String? = null
)
