package com.maodouchat.explore.policy

import com.maodouchat.network.PostDto

data class Cursor(val createdAt: Long, val postId: String)

data class LikeToggle(
    val optimistic: PostDto,
    val previous: PostDto,
    val willLike: Boolean
)
