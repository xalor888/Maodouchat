package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostCommentDto

// 评论单条替换：按 id 把更新后的评论写回列表，其余项原样保留；
// 收敛动态评论控制器里 3 处逐字相同的行内 map。
object ExploreCommentReplacePolicy {

    fun replace(
        comments: List<PostCommentDto>,
        commentId: String,
        updated: PostCommentDto
    ): List<PostCommentDto> =
        comments.map { if (it.id == commentId) updated else it }
}
