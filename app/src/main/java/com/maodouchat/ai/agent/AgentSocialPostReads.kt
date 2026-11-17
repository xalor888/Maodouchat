package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.messaging.AgentPostReadLimitPolicy
import com.maodouchat.domain.messaging.AgentReadPreviewPolicy
import com.maodouchat.network.ApiService
internal object AgentSocialPostReads {

        internal suspend fun listPosts(app: MaodouchatApp, limit: Int): String {
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.getPosts(token, limit = AgentPostReadLimitPolicy.postsLimit(limit)).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No posts."
            return rows.joinToString("\n") { post ->
                "${post.id}\t${post.author.name}\tlikes=${post.likeCount}\tcomments=${post.commentCount}\t${AgentReadPreviewPolicy.postListContent(post.content)}"
            }
        }

        internal suspend fun getPost(app: MaodouchatApp, postId: String): String {
            if (postId.isBlank()) return "Error: postId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val post = ApiService.getPost(token, postId).getOrElse { return AgentToolHost.fail(it) }
            return "id=${post.id}\tauthor=${post.author.id}/${post.author.name}\tlikes=${post.likeCount}\tcomments=${post.commentCount}\tmine=${post.isMine}\n${AgentReadPreviewPolicy.postDetailContent(post.content)}"
        }

        internal suspend fun listPostComments(app: MaodouchatApp, postId: String, limit: Int): String {
            if (postId.isBlank()) return "Error: postId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.getPostComments(token, postId, AgentPostReadLimitPolicy.commentsLimit(limit)).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No comments."
            return rows.joinToString("\n") { "${it.id}\t${it.author.name}\t${AgentReadPreviewPolicy.commentContent(it.content)}" }
        }
}

