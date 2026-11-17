package com.maodouchat.domain.messaging

// Agent 动态列表/详情/评论行的纯格式化逻辑，自 app 侧 ai/agent 社交读簇收敛；
// 拉取与 token 仍在 app 侧，这里只定行格式。
object AgentPostRowPolicy {

    fun postListRow(
        id: String,
        authorName: String,
        likeCount: Int,
        commentCount: Int,
        contentPreview: String,
    ): String = "$id\t$authorName\tlikes=$likeCount\tcomments=$commentCount\t$contentPreview"

    fun postDetail(
        id: String,
        authorId: String,
        authorName: String,
        likeCount: Int,
        commentCount: Int,
        isMine: Boolean,
        contentDetail: String,
    ): String =
        "id=$id\tauthor=$authorId/$authorName\tlikes=$likeCount\tcomments=$commentCount\tmine=$isMine\n$contentDetail"

    fun commentRow(id: String, authorName: String, content: String): String =
        "$id\t$authorName\t$content"
}
