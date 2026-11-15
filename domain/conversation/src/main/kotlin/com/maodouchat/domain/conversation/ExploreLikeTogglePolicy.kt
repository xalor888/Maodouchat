package com.maodouchat.domain.conversation

// Explore 动态/评论点赞切换：乐观更新时点赞态取反、点赞数 ±1 且不低于 0。
// 自三处逐字相同的行内算术收敛：PostMutationRepository（动态/评论）与
// AuthorProfileViewModel（作者资料页动态）。
object ExploreLikeTogglePolicy {

    data class Toggled(val likedByMe: Boolean, val likeCount: Int)

    fun toggle(likeCount: Int, likedByMe: Boolean): Toggled {
        val newLiked = !likedByMe
        val newCount = (likeCount + if (newLiked) 1 else -1).coerceAtLeast(0)
        return Toggled(newLiked, newCount)
    }
}
