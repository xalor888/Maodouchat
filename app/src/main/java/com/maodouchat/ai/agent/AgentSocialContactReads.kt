package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.conversation.LikeQueryPolicy
import com.maodouchat.domain.messaging.AgentContactQueryPolicy
import com.maodouchat.domain.messaging.AgentContactRowPolicy
import com.maodouchat.domain.messaging.AgentReadEmptyLinePolicy
import com.maodouchat.domain.messaging.AgentReadGatePolicy
import com.maodouchat.domain.messaging.AgentReadPreviewPolicy
import com.maodouchat.domain.messaging.AgentToolHostPolicy
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
                AgentContactRowPolicy.contactRow(it.id, display, AgentReadPreviewPolicy.contactStatus(it.status))
            }
            return if (lines.isEmpty()) AgentReadEmptyLinePolicy.NO_CONTACTS else lines.joinToString("\n")
        }

        internal suspend fun getMe(app: MaodouchatApp, userId: String): String {
            val me = app.database.userDao().getUserById(userId)
            return AgentContactRowPolicy.meDetail(userId, me?.name, me?.nickname, me?.status)
        }

        internal suspend fun listFriendRequests(app: MaodouchatApp, direction: String): String {
            val token = AgentToolHost.token(app) ?: return AgentToolHostPolicy.NOT_SIGNED_IN
            val incoming = AgentContactQueryPolicy.isIncoming(direction)
            val rows = if (incoming) {
                ApiService.getIncomingFriendRequests(token).getOrElse { return AgentToolHost.fail(it) }
            } else {
                ApiService.getOutgoingFriendRequests(token).getOrElse { return AgentToolHost.fail(it) }
            }
            if (rows.isEmpty()) return AgentReadEmptyLinePolicy.NO_FRIEND_REQUESTS
            return rows.take(AgentContactQueryPolicy.MAX_FRIEND_REQUEST_ROWS).joinToString("\n") { req ->
                AgentContactRowPolicy.friendRequestRow(
                    req.id,
                    req.status,
                    req.fromUser.id,
                    req.fromUser.name,
                    req.toUser.id,
                    req.toUser.name,
                    AgentReadPreviewPolicy.friendRequestMessage(req.message),
                )
            }
        }

        internal suspend fun listFriends(app: MaodouchatApp): String {
            val token = AgentToolHost.token(app) ?: return AgentToolHostPolicy.NOT_SIGNED_IN
            val rows = ApiService.getFriends(token).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return AgentReadEmptyLinePolicy.NO_FRIENDS
            return rows.take(AgentContactQueryPolicy.MAX_CONTACT_ROWS).joinToString("\n") { AgentContactRowPolicy.personRow(it.id, it.name, AgentReadPreviewPolicy.contactStatus(it.status)) }
        }

        internal suspend fun searchUsers(app: MaodouchatApp, query: String, limit: Int): String {
            val q = AgentContactQueryPolicy.normalizeQuery(query)
            if (q.isBlank()) return AgentReadGatePolicy.blankQueryError()
            val token = AgentToolHost.token(app) ?: return AgentToolHostPolicy.NOT_SIGNED_IN
            val rows = ApiService.searchUsers(token, q, AgentContactQueryPolicy.userSearchLimit(limit)).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return AgentReadEmptyLinePolicy.NO_USERS
            return rows.joinToString("\n") { AgentContactRowPolicy.personRow(it.id, it.name, AgentReadPreviewPolicy.contactStatus(it.status)) }
        }

        internal suspend fun listBlocked(app: MaodouchatApp): String {
            val token = AgentToolHost.token(app) ?: return AgentToolHostPolicy.NOT_SIGNED_IN
            val rows = ApiService.getBlockedUserDetails(token).getOrElse { return AgentToolHost.fail(it) }
            if (rows.isEmpty()) return AgentReadEmptyLinePolicy.NO_BLOCKED_USERS
            return rows.joinToString("\n") { AgentContactRowPolicy.blockedRow(it.id, it.name) }
        }
}

