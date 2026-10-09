package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.network.ApiService
import kotlinx.coroutines.flow.first
internal object AgentSocialContactReads {

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

        internal suspend fun listBlocked(app: MaodouchatApp): String {
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val rows = ApiService.getBlockedUserDetails(token).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return "No blocked users."
            return rows.joinToString("\n") { "${it.id}\t${it.name}" }
        }
}

