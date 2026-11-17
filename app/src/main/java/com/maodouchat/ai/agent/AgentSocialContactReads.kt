package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.core.model.LikeQueryPolicy
import com.maodouchat.domain.messaging.AgentContactQueryPolicy
import com.maodouchat.domain.messaging.AgentReadPreviewPolicy
import com.maodouchat.network.ApiService
import kotlinx.coroutines.flow.first
internal object AgentSocialContactReads {

        internal suspend fun getContacts(app: MaodouchatApp, query: String?): String {
            val q = AgentContactQueryPolicy.normalizeQuery(query)
            val all = if (q.isBlank()) {
                app.database.userDao().getAllUsers().first()
            } else {
                val escaped = LikeQueryPolicy.escapeForContains(AgentContactQueryPolicy.searchQuery(q))
                if (escaped.isBlank()) emptyList() else app.database.userDao().searchUsers(escaped, AgentContactQueryPolicy.MAX_CONTACT_ROWS)
            }
            val lines = all.take(AgentContactQueryPolicy.MAX_CONTACT_ROWS).map {
                val display = AgentContactQueryPolicy.displayName(it.nickname, it.name)
                "${it.id}\t$display\t${AgentReadPreviewPolicy.contactStatus(it.status)}"
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
            val incoming = AgentContactQueryPolicy.isIncoming(direction)
            val rows = if (incoming) {
                ApiService.getIncomingFriendRequests(token).getOrElse { return AgentToolHost.fail(it) }
            } else {
                ApiService.getOutgoingFriendRequests(token).getOrElse { return AgentToolHost.fail(it) }
            }
            if (rows.isEmpty()) return "No friend requests."
            return rows.take(AgentContactQueryPolicy.MAX_FRIEND_REQUEST_ROWS).joinToString("\n") { req ->
                "${req.id}\t${req.status}\tfrom=${req.fromUser.id}/${req.fromUser.name}\tto=${req.toUser.id}/${req.toUser.name}\t${AgentReadPreviewPolicy.friendRequestMessage(req.message)}"
            }
        }

        internal suspend fun listFriends(app: MaodouchatApp): String {
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.getFriends(token).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No friends."
            return rows.take(AgentContactQueryPolicy.MAX_CONTACT_ROWS).joinToString("\n") { "${it.id}\t${it.name}\t${AgentReadPreviewPolicy.contactStatus(it.status)}" }
        }

        internal suspend fun searchUsers(app: MaodouchatApp, query: String, limit: Int): String {
            val q = AgentContactQueryPolicy.normalizeQuery(query)
            if (q.isBlank()) return "Error: query required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.searchUsers(token, q, AgentContactQueryPolicy.userSearchLimit(limit)).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No users."
            return rows.joinToString("\n") { "${it.id}\t${it.name}\t${AgentReadPreviewPolicy.contactStatus(it.status)}" }
        }

        internal suspend fun listBlocked(app: MaodouchatApp): String {
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.getBlockedUserDetails(token).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No blocked users."
            return rows.joinToString("\n") { "${it.id}\t${it.name}" }
        }
}

