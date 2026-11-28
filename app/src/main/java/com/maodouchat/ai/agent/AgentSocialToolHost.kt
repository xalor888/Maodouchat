package com.maodouchat.ai.agent

import com.maodouchat.domain.messaging.AgentToolLimitPolicy
import com.maodouchat.domain.messaging.AgentToolNamePolicy
import com.maodouchat.domain.messaging.AgentToolPreview

// Agent 工具分发：社交簇（联系人/好友/动态/黑名单）。从 AgentToolHost.execute/preview 按簇搬出，分支逐字一致。
internal object AgentSocialToolHost {
    internal suspend fun execute(name: String, ports: AgentToolPorts, userId: String, args: Map<String, String>): String? = when (name) {
        AgentToolNamePolicy.GET_CONTACTS -> AgentSocialContactReads.getContacts(ports, args["query"])
        AgentToolNamePolicy.GET_ME -> AgentSocialContactReads.getMe(ports, userId)
        AgentToolNamePolicy.LIST_FRIEND_REQUESTS -> AgentSocialContactReads.listFriendRequests(ports, args["direction"].orEmpty())
        AgentToolNamePolicy.LIST_FRIENDS -> AgentSocialContactReads.listFriends(ports)
        AgentToolNamePolicy.SEARCH_USERS -> AgentSocialContactReads.searchUsers(ports, args["query"].orEmpty(), args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit(AgentToolNamePolicy.SEARCH_USERS))
        AgentToolNamePolicy.LIST_POSTS -> AgentSocialPostReads.listPosts(ports, args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit(AgentToolNamePolicy.LIST_POSTS))
        AgentToolNamePolicy.GET_POST -> AgentSocialPostReads.getPost(ports, args["postId"].orEmpty())
        AgentToolNamePolicy.LIST_POST_COMMENTS -> AgentSocialPostReads.listPostComments(ports, args["postId"].orEmpty(), args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit(AgentToolNamePolicy.LIST_POST_COMMENTS))
        AgentToolNamePolicy.LIST_BLOCKED_USERS -> AgentSocialContactReads.listBlocked(ports)
        AgentToolNamePolicy.SEND_FRIEND_REQUEST -> AgentFriendWriteTools.sendFriend(ports, args["userId"].orEmpty(), args["message"].orEmpty())
        AgentToolNamePolicy.ACCEPT_FRIEND_REQUEST -> AgentFriendWriteTools.acceptFriend(ports, args["requestId"].orEmpty())
        AgentToolNamePolicy.REJECT_FRIEND_REQUEST -> AgentFriendWriteTools.rejectFriend(ports, args["requestId"].orEmpty())
        AgentToolNamePolicy.CANCEL_FRIEND_REQUEST -> AgentFriendWriteTools.cancelFriend(ports, args["requestId"].orEmpty())
        AgentToolNamePolicy.REMOVE_FRIEND -> AgentFriendWriteTools.removeFriend(ports, args["userId"].orEmpty())
        AgentToolNamePolicy.BLOCK_USER -> AgentFriendWriteTools.blockUser(ports, args["userId"].orEmpty())
        AgentToolNamePolicy.UNBLOCK_USER -> AgentFriendWriteTools.unblockUser(ports, args["userId"].orEmpty())
        AgentToolNamePolicy.CREATE_TEXT_POST -> AgentPostWriteTools.createPost(ports, args["text"].orEmpty(), args["visibility"])
        AgentToolNamePolicy.LIKE_POST -> AgentPostWriteTools.likePost(ports, args["postId"].orEmpty(), AgentToolHost.parseBool(args["liked"]))
        AgentToolNamePolicy.COMMENT_ON_POST -> AgentPostWriteTools.commentPost(ports, args["postId"].orEmpty(), args["text"].orEmpty())
        AgentToolNamePolicy.DELETE_POST -> AgentPostWriteTools.deletePost(ports, args["postId"].orEmpty())
        else -> null
    }

    internal fun preview(name: String, args: Map<String, String>): String? =
        AgentToolPreview.preview(name, args)
}
