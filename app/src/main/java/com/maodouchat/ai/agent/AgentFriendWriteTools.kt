package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.messaging.AgentFriendResultPolicy
import com.maodouchat.domain.messaging.AgentToolInputPolicy
import com.maodouchat.network.ApiService

// Agent 工具执行簇：好友写操作（好友请求/接受/拒绝/撤销/删除/拉黑）。
// 从 AgentMessagingWriteTools 按簇搬出，函数体逐字一致；token/fail 由 AgentToolHost 提供。
internal object AgentFriendWriteTools {
        internal suspend fun sendFriend(app: MaodouchatApp, userId: String, message: String): String {
            if (userId.isBlank()) return "Error: userId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val result = ApiService.sendFriendRequest(token, userId, AgentToolInputPolicy.cleanFriendRequestMessage(message)).getOrElse { return AgentToolHost.fail(it) }
            return AgentFriendResultPolicy.friendRequest(result.id, result.status)
        }

        internal suspend fun acceptFriend(app: MaodouchatApp, requestId: String): String {
            if (requestId.isBlank()) return "Error: requestId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val result = ApiService.acceptFriendRequest(token, requestId).getOrElse { return AgentToolHost.fail(it) }
            return AgentFriendResultPolicy.accepted(result.id, result.status)
        }

        internal suspend fun rejectFriend(app: MaodouchatApp, requestId: String): String {
            if (requestId.isBlank()) return "Error: requestId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val result = ApiService.rejectFriendRequest(token, requestId).getOrElse { return AgentToolHost.fail(it) }
            return AgentFriendResultPolicy.rejected(result.id, result.status)
        }

        internal suspend fun cancelFriend(app: MaodouchatApp, requestId: String): String {
            if (requestId.isBlank()) return "Error: requestId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val result = ApiService.cancelFriendRequest(token, requestId).getOrElse { return AgentToolHost.fail(it) }
            return AgentFriendResultPolicy.cancelled(result.id, result.status)
        }

        internal suspend fun removeFriend(app: MaodouchatApp, userId: String): String {
            if (userId.isBlank()) return "Error: userId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.removeFriend(token, userId).getOrElse { return AgentToolHost.fail(it) }
            return AgentFriendResultPolicy.removed(userId)
        }

        internal suspend fun blockUser(app: MaodouchatApp, userId: String): String {
            if (userId.isBlank()) return "Error: userId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.blockUser(token, userId).getOrElse { return AgentToolHost.fail(it) }
            return AgentFriendResultPolicy.blocked(userId)
        }

        internal suspend fun unblockUser(app: MaodouchatApp, userId: String): String {
            if (userId.isBlank()) return "Error: userId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.unblockUser(token, userId).getOrElse { return AgentToolHost.fail(it) }
            return AgentFriendResultPolicy.unblocked(userId)
        }
}
