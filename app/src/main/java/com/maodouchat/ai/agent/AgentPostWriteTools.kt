package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.messaging.AgentPostResultPolicy
import com.maodouchat.domain.messaging.AgentToolInputPolicy
import com.maodouchat.domain.messaging.AgentWriteInputPolicy
import com.maodouchat.domain.messaging.AgentWriteErrorPolicy
import com.maodouchat.network.ApiService

// Agent 工具执行簇：动态写操作（发布/点赞/评论/删除）。
// 从 AgentMessagingWriteTools 按簇搬出，函数体逐字一致；token/fail 由 AgentToolHost 提供。
internal object AgentPostWriteTools {
        internal suspend fun createPost(app: MaodouchatApp, text: String, visibility: String?): String {
            val body = AgentToolInputPolicy.cleanPostBody(text)
            if (body.isBlank()) return AgentWriteErrorPolicy.required("text")
            val token = AgentToolHost.token(app) ?: return AgentWriteErrorPolicy.notSignedIn()
            // G154c：可见性白名单与隐私设置页共用一份
            val vis = AgentWriteInputPolicy.resolvePostVisibility(visibility)
            val post = ApiService.createPost(token, body, emptyList(), vis).getOrElse { return AgentToolHost.fail(it) }
            return AgentPostResultPolicy.created(post.id)
        }

        internal suspend fun likePost(app: MaodouchatApp, postId: String, liked: Boolean?): String {
            if (postId.isBlank() || liked == null) return AgentWriteErrorPolicy.required("postId", "liked")
            val token = AgentToolHost.token(app) ?: return AgentWriteErrorPolicy.notSignedIn()
            val post = if (liked) {
                ApiService.likePost(token, postId).getOrElse { return AgentToolHost.fail(it) }
            } else {
                ApiService.unlikePost(token, postId).getOrElse { return AgentToolHost.fail(it) }
            }
            return AgentPostResultPolicy.liked(post.id, post.likedByMe, post.likeCount)
        }

        internal suspend fun commentPost(app: MaodouchatApp, postId: String, text: String): String {
            val body = AgentToolInputPolicy.cleanCommentBody(text)
            if (postId.isBlank() || body.isBlank()) return AgentWriteErrorPolicy.required("postId", "text")
            val token = AgentToolHost.token(app) ?: return AgentWriteErrorPolicy.notSignedIn()
            val comment = ApiService.createPostComment(token, postId, body).getOrElse { return AgentToolHost.fail(it) }
            return AgentPostResultPolicy.commented(comment.id, postId)
        }

        internal suspend fun deletePost(app: MaodouchatApp, postId: String): String {
            if (postId.isBlank()) return AgentWriteErrorPolicy.required("postId")
            val token = AgentToolHost.token(app) ?: return AgentWriteErrorPolicy.notSignedIn()
            ApiService.deletePost(token, postId).getOrElse { return AgentToolHost.fail(it) }
            return AgentPostResultPolicy.deleted(postId)
        }
}
