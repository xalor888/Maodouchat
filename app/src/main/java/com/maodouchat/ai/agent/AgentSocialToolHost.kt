package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp

// Agent 工具分发：社交簇（联系人/好友/动态/黑名单）。从 AgentToolHost.execute/preview 按簇搬出，分支逐字一致。
internal object AgentSocialToolHost {
    internal suspend fun execute(name: String, app: MaodouchatApp, userId: String, args: Map<String, String>): String? = when (name) {
        "get_contacts" -> AgentSocialContactReads.getContacts(app, args["query"])
        "get_me" -> AgentSocialContactReads.getMe(app, userId)
        "list_friend_requests" -> AgentSocialContactReads.listFriendRequests(app, args["direction"].orEmpty())
        "list_friends" -> AgentSocialContactReads.listFriends(app)
        "search_users" -> AgentSocialContactReads.searchUsers(app, args["query"].orEmpty(), args["limit"]?.toIntOrNull() ?: 20)
        "list_posts" -> AgentSocialPostReads.listPosts(app, args["limit"]?.toIntOrNull() ?: 20)
        "get_post" -> AgentSocialPostReads.getPost(app, args["postId"].orEmpty())
        "list_post_comments" -> AgentSocialPostReads.listPostComments(app, args["postId"].orEmpty(), args["limit"]?.toIntOrNull() ?: 30)
        "list_blocked_users" -> AgentSocialContactReads.listBlocked(app)
        "send_friend_request" -> AgentFriendWriteTools.sendFriend(app, args["userId"].orEmpty(), args["message"].orEmpty())
        "accept_friend_request" -> AgentFriendWriteTools.acceptFriend(app, args["requestId"].orEmpty())
        "reject_friend_request" -> AgentFriendWriteTools.rejectFriend(app, args["requestId"].orEmpty())
        "cancel_friend_request" -> AgentFriendWriteTools.cancelFriend(app, args["requestId"].orEmpty())
        "remove_friend" -> AgentFriendWriteTools.removeFriend(app, args["userId"].orEmpty())
        "block_user" -> AgentFriendWriteTools.blockUser(app, args["userId"].orEmpty())
        "unblock_user" -> AgentFriendWriteTools.unblockUser(app, args["userId"].orEmpty())
        "create_text_post" -> AgentPostWriteTools.createPost(app, args["text"].orEmpty(), args["visibility"])
        "like_post" -> AgentPostWriteTools.likePost(app, args["postId"].orEmpty(), AgentToolHost.parseBool(args["liked"]))
        "comment_on_post" -> AgentPostWriteTools.commentPost(app, args["postId"].orEmpty(), args["text"].orEmpty())
        "delete_post" -> AgentPostWriteTools.deletePost(app, args["postId"].orEmpty())
        else -> null
    }

    internal fun preview(name: String, args: Map<String, String>): String? = when (name) {
        "send_friend_request" -> "好友申请 ${args["userId"].orEmpty().take(24)}"
        "accept_friend_request" -> "同意好友 ${args["requestId"].orEmpty().take(24)}"
        "reject_friend_request" -> "拒绝好友 ${args["requestId"].orEmpty().take(24)}"
        "create_text_post" -> "发动态：${args["text"].orEmpty().take(80)}"
        "like_post" -> "赞动态 ${args["postId"].orEmpty().take(24)} liked=${args["liked"]}"
        "comment_on_post" -> "评论动态 ${args["postId"].orEmpty().take(24)}"
        "delete_post" -> "删除动态 ${args["postId"].orEmpty().take(24)}"
        "block_user" -> "拉黑 ${args["userId"].orEmpty().take(24)}"
        "unblock_user" -> "取消拉黑 ${args["userId"].orEmpty().take(24)}"
        else -> null
    }
}
