package com.maodouchat.domain.messaging

// Agent 动态写操作的结果文案，自 app 侧 AgentPostWriteTools 收敛；
// 网络请求仍留在 app 侧。
object AgentPostResultPolicy {

    fun created(id: String): String =
        "Created post $id"

    fun liked(id: String, likedByMe: Boolean, likeCount: Int): String =
        "Post $id likedByMe=$likedByMe likes=$likeCount"

    fun commented(commentId: String, postId: String): String =
        "Commented $commentId on $postId"

    fun deleted(postId: String): String =
        "Deleted post $postId"
}
