package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.network.ApiService

// Agent 工具执行簇：动态写操作（发布/点赞/评论/删除）。
// 从 AgentMessagingWriteTools 按簇搬出，函数体逐字一致；token/fail 由 AgentToolHost 提供。
internal object AgentPostWriteTools {
        internal suspend fun createPost(app: MaodouchatApp, text: String, visibility: String?): String {
            val body = text.trim().take(2_000)
            if (body.isBlank()) return "Error: text required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            // G154c：可见性白名单与隐私设置页共用一份
            val vis = visibility?.trim()?.uppercase()
                ?.takeIf { com.maodouchat.settings.VISIBILITY_VALUES.contains(it) }
            val post = ApiService.createPost(token, body, emptyList(), vis).getOrElse { return AgentToolHost.fail(it) }
            return "Created post ${post.id}"
        }

        internal suspend fun likePost(app: MaodouchatApp, postId: String, liked: Boolean?): String {
            if (postId.isBlank() || liked == null) return "Error: postId and liked required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val post = if (liked) {
                ApiService.likePost(token, postId).getOrElse { return AgentToolHost.fail(it) }
            } else {
                ApiService.unlikePost(token, postId).getOrElse { return AgentToolHost.fail(it) }
            }
            return "Post ${post.id} likedByMe=${post.likedByMe} likes=${post.likeCount}"
        }

        internal suspend fun commentPost(app: MaodouchatApp, postId: String, text: String): String {
            val body = text.trim().take(800)
            if (postId.isBlank() || body.isBlank()) return "Error: postId and text required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val comment = ApiService.createPostComment(token, postId, body).getOrElse { return AgentToolHost.fail(it) }
            return "Commented ${comment.id} on $postId"
        }

        internal suspend fun deletePost(app: MaodouchatApp, postId: String): String {
            if (postId.isBlank()) return "Error: postId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.deletePost(token, postId).getOrElse { return AgentToolHost.fail(it) }
            return "Deleted post $postId"
        }
}
