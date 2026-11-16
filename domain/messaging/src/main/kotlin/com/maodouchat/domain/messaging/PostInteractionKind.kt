package com.maodouchat.domain.messaging

// 动态互动文案种类；R.string 映射留在 app 侧。
enum class PostInteractionKind {
    COMMENT_LIKE,
    REPLY,
    COMMENT,
    LIKE,
}

fun postInteractionKind(interaction: String): PostInteractionKind =
    when (interaction) {
        "COMMENT_LIKE" -> PostInteractionKind.COMMENT_LIKE
        "REPLY" -> PostInteractionKind.REPLY
        "COMMENT" -> PostInteractionKind.COMMENT
        else -> PostInteractionKind.LIKE
    }

// 通知中心条目 id 后缀：评论赞固定 "cl"，其余按是否评论区分，与原内联分支一致。
fun postInteractionCenterSuffix(kind: PostInteractionKind, isComment: Boolean): String =
    when {
        kind == PostInteractionKind.COMMENT_LIKE -> "cl"
        isComment -> "c"
        else -> "l"
    }

// 通知中心 extra kind：评论赞固定 "comment_like"，其余按是否评论区分，与原内联分支一致。
fun postInteractionCenterKind(kind: PostInteractionKind, isComment: Boolean): String =
    when {
        kind == PostInteractionKind.COMMENT_LIKE -> "comment_like"
        isComment -> "comment"
        else -> "like"
    }
