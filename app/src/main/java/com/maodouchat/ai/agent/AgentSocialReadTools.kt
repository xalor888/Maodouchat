package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.network.ApiService
import kotlinx.coroutines.flow.first

// Agent 工具执行簇：社交只读查询（联系人/用户/好友请求/动态/黑名单）。
// 从 AgentMessagingReadTools 按簇搬出，函数体逐字一致；token/fail 由 AgentToolHost 提供。
internal object AgentSocialReadTools {
        internal suspend fun getContacts(app: MaodouchatApp, query: String?): String {
            val q = query?.trim().orEmpty()
            val all = if (q.isBlank()) {
                app.database.userDao().getAllUsers().first()
            } else {
                val escaped = com.maodouchat.data.local.LikeQueryPolicy.escapeForContains(q.take(80))
                if (escaped.isBlank()) emptyList() else app.database.userDao().searchUsers(escaped, 80)
            }
            val lines = all.take(80).map {
                val display = it.nickname?.takeIf(String::isNotBlank) ?: it.name
                "${it.id}\t$display\t${it.status.take(40)}"
            }
            return if (lines.isEmpty()) "No contacts." else lines.joinToString("\n")
        }

        internal suspend fun getMe(app: MaodouchatApp, userId: String): String {
            val me = app.database.userDao().getUserById(userId)
            return buildString {
                appendLine("userId=$userId")
                if (me != null) {
                    appendLine("name=${me.name}")
                    appendLine("nickname=${me.nickname.orEmpty()}")
                    appendLine("status=${me.status}")
                }
            }.trim()
        }

        internal suspend fun listFriendRequests(app: MaodouchatApp, direction: String): String {
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val incoming = !direction.equals("outgoing", ignoreCase = true)
            val rows = if (incoming) {
                ApiService.getIncomingFriendRequests(token).getOrElse { return AgentToolHost.fail(it) }
            } else {
                ApiService.getOutgoingFriendRequests(token).getOrElse { return AgentToolHost.fail(it) }
            }
            if (rows.isEmpty()) return "No friend requests."
            return rows.take(50).joinToString("\n") { req ->
                "${req.id}\t${req.status}\tfrom=${req.fromUser.id}/${req.fromUser.name}\tto=${req.toUser.id}/${req.toUser.name}\t${req.message.take(80)}"
            }
        }

        internal suspend fun listFriends(app: MaodouchatApp): String {
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.getFriends(token).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No friends."
            return rows.take(80).joinToString("\n") { "${it.id}\t${it.name}\t${it.status.take(40)}" }
        }

        internal suspend fun searchUsers(app: MaodouchatApp, query: String, limit: Int): String {
            val q = query.trim()
            if (q.isBlank()) return "Error: query required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.searchUsers(token, q, limit.coerceIn(1, 30)).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No users."
            return rows.joinToString("\n") { "${it.id}\t${it.name}\t${it.status.take(40)}" }
        }

        internal suspend fun listPosts(app: MaodouchatApp, limit: Int): String {
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.getPosts(token, limit = limit.coerceIn(1, 40)).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No posts."
            return rows.joinToString("\n") { post ->
                "${post.id}\t${post.author.name}\tlikes=${post.likeCount}\tcomments=${post.commentCount}\t${post.content.take(120)}"
            }
        }

        internal suspend fun getPost(app: MaodouchatApp, postId: String): String {
            if (postId.isBlank()) return "Error: postId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val post = ApiService.getPost(token, postId).getOrElse { return AgentToolHost.fail(it) }
            return "id=${post.id}\tauthor=${post.author.id}/${post.author.name}\tlikes=${post.likeCount}\tcomments=${post.commentCount}\tmine=${post.isMine}\n${post.content.take(1_000)}"
        }

        internal suspend fun listPostComments(app: MaodouchatApp, postId: String, limit: Int): String {
            if (postId.isBlank()) return "Error: postId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.getPostComments(token, postId, limit.coerceIn(1, 100)).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No comments."
            return rows.joinToString("\n") { "${it.id}\t${it.author.name}\t${it.content.take(160)}" }
        }

        internal suspend fun listBlocked(app: MaodouchatApp): String {
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.getBlockedUserDetails(token).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No blocked users."
            return rows.joinToString("\n") { "${it.id}\t${it.name}" }
        }
}
