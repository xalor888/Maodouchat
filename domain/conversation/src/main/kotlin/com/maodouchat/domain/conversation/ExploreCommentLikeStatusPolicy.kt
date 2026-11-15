package com.maodouchat.domain.conversation

// 评论点赞成功后最终点赞态判定：服务端 status 非空时以服务端为准
// （等于 LIKED 才算已赞），空 status 沿用本地乐观切换值。
object ExploreCommentLikeStatusPolicy {

    fun resolve(status: String, toggledLikedByMe: Boolean): Boolean =
        if (status.isNotBlank()) status == "LIKED" else toggledLikedByMe
}
