package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.messaging.AgentToolLimitPolicy
import com.maodouchat.domain.messaging.AgentToolNamePolicy
import com.maodouchat.domain.messaging.AgentToolPreview

// Agent 工具分发：社交簇（联系人/好友/动态/黑名单）。从 AgentToolHost.execute/preview 按簇搬出，分支逐字一致。
internal object AgentSocialToolHost {
    internal suspend fun execute(name: String, app: MaodouchatApp, userId: String, args: Map<String, String>): String? = when (name) {
        AgentToolNamePolicy.GET_CONTACTS -> AgentSocialContactReads.getContacts(app, args["query"])
        AgentToolNamePolicy.GET_ME -> AgentSocialContactReads.getMe(app, userId)
        AgentToolNamePolicy.LIST_FRIEND_REQUESTS -> AgentSocialContactReads.listFriendRequests(app, args["direction"].orEmpty())
        AgentToolNamePolicy.LIST_FRIENDS -> AgentSocialContactReads.listFriends(app)
        AgentToolNamePolicy.SEARCH_USERS -> AgentSocialContactReads.searchUsers(app, args["query"].orEmpty(), args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit(AgentToolNamePolicy.SEARCH_USERS))
        AgentToolNamePolicy.LIST_POSTS -> AgentSocialPostReads.listPosts(app, args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit(AgentToolNamePolicy.LIST_POSTS))
        AgentToolNamePolicy.GET_POST -> AgentSocialPostReads.getPost(app, args["postId"].orEmpty())
        AgentToolNamePolicy.LIST_POST_COMMENTS -> AgentSocialPostReads.listPostComments(app, args["postId"].orEmpty(), args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit(AgentToolNamePolicy.LIST_POST_COMMENTS))
        AgentToolNamePolicy.LIST_BLOCKED_USERS -> AgentSocialContactReads.listBlocked(app)
        AgentToolNamePolicy.SEND_FRIEND_REQUEST -> AgentFriendWriteTools.sendFriend(app, args["userId"].orEmpty(), args["message"].orEmpty())
        AgentToolNamePolicy.ACCEPT_FRIEND_REQUEST -> AgentFriendWriteTools.acceptFriend(app, args["requestId"].orEmpty())
        AgentToolNamePolicy.REJECT_FRIEND_REQUEST -> AgentFriendWriteTools.rejectFriend(app, args["requestId"].orEmpty())
        AgentToolNamePolicy.CANCEL_FRIEND_REQUEST -> AgentFriendWriteTools.cancelFriend(app, args["requestId"].orEmpty())
        AgentToolNamePolicy.REMOVE_FRIEND -> AgentFriendWriteTools.removeFriend(app, args["userId"].orEmpty())
        AgentToolNamePolicy.BLOCK_USER -> AgentFriendWriteTools.blockUser(app, args["userId"].orEmpty())
        AgentToolNamePolicy.UNBLOCK_USER -> AgentFriendWriteTools.unblockUser(app, args["userId"].orEmpty())
        AgentToolNamePolicy.CREATE_TEXT_POST -> AgentPostWriteTools.createPost(app, args["text"].orEmpty(), args["visibility"])
        AgentToolNamePolicy.LIKE_POST -> AgentPostWriteTools.likePost(app, args["postId"].orEmpty(), AgentToolHost.parseBool(args["liked"]))
        AgentToolNamePolicy.COMMENT_ON_POST -> AgentPostWriteTools.commentPost(app, args["postId"].orEmpty(), args["text"].orEmpty())
        AgentToolNamePolicy.DELETE_POST -> AgentPostWriteTools.deletePost(app, args["postId"].orEmpty())
        else -> null
    }

    internal fun preview(name: String, args: Map<String, String>): String? =
        AgentToolPreview.preview(name, args)
}
