package com.maodouchat.domain.messaging

// Agent 工具名常量：三簇 ToolHost 的 when 分发、预览与默认上限共用的工具名。
// 自 app 侧三簇 ToolHost 与既在 domain 内的预览/上限分支收敛，值逐字不变。
// 工具定义侧（:core:model 的 catalog/specs）仍用字面量：:core:model 不依赖
// :domain:messaging，那里的名字是工具注册的源头，不在此收敛。
object AgentToolNamePolicy {

    // 消息簇
    const val LIST_CHATS: String = "list_chats"

    const val GET_CHAT_HISTORY: String = "get_chat_history"

    const val SEARCH_MESSAGES: String = "search_messages"

    const val GET_CHAT: String = "get_chat"

    const val LIST_STARRED_MESSAGES: String = "list_starred_messages"

    const val LIST_PINNED_MESSAGES: String = "list_pinned_messages"

    const val SEND_TEXT_MESSAGE: String = "send_text_message"

    const val UPDATE_CHAT: String = "update_chat"

    const val SET_DRAFT: String = "set_draft"

    const val STAR_MESSAGE: String = "star_message"

    const val DELETE_LOCAL_MESSAGE: String = "delete_local_message"

    const val SET_CONTACT_NICKNAME: String = "set_contact_nickname"

    const val MARK_NOTIFICATION_READ: String = "mark_notification_read"

    const val REVOKE_MESSAGE: String = "revoke_message"

    const val REACT_TO_MESSAGE: String = "react_to_message"

    const val PIN_MESSAGE: String = "pin_message"

    const val CREATE_DIRECT_CHAT: String = "create_direct_chat"

    const val CREATE_GROUP: String = "create_group"

    const val RENAME_GROUP: String = "rename_group"

    const val UPDATE_GROUP_ANNOUNCEMENT: String = "update_group_announcement"

    const val ADD_GROUP_MEMBERS: String = "add_group_members"

    const val REMOVE_GROUP_MEMBER: String = "remove_group_member"

    const val MUTE_GROUP_MEMBER: String = "mute_group_member"

    const val DELETE_CHAT: String = "delete_chat"

    // 社交簇
    const val GET_CONTACTS: String = "get_contacts"

    const val GET_ME: String = "get_me"

    const val LIST_FRIEND_REQUESTS: String = "list_friend_requests"

    const val LIST_FRIENDS: String = "list_friends"

    const val SEARCH_USERS: String = "search_users"

    const val LIST_POSTS: String = "list_posts"

    const val GET_POST: String = "get_post"

    const val LIST_POST_COMMENTS: String = "list_post_comments"

    const val LIST_BLOCKED_USERS: String = "list_blocked_users"

    const val SEND_FRIEND_REQUEST: String = "send_friend_request"

    const val ACCEPT_FRIEND_REQUEST: String = "accept_friend_request"

    const val REJECT_FRIEND_REQUEST: String = "reject_friend_request"

    const val CANCEL_FRIEND_REQUEST: String = "cancel_friend_request"

    const val REMOVE_FRIEND: String = "remove_friend"

    const val BLOCK_USER: String = "block_user"

    const val UNBLOCK_USER: String = "unblock_user"

    const val CREATE_TEXT_POST: String = "create_text_post"

    const val LIKE_POST: String = "like_post"

    const val COMMENT_ON_POST: String = "comment_on_post"

    const val DELETE_POST: String = "delete_post"

    // 任务簇
    const val LIST_DRAFTS: String = "list_drafts"

    const val GET_DRAFT: String = "get_draft"

    const val LIST_LOCAL_TASKS: String = "list_local_tasks"

    const val LIST_MISSED_CALLS: String = "list_missed_calls"

    const val LIST_NOTIFICATIONS: String = "list_notifications"

    const val CREATE_LOCAL_TASK: String = "create_local_task"

    const val COMPLETE_LOCAL_TASK: String = "complete_local_task"

    const val DELETE_LOCAL_TASK: String = "delete_local_task"
}
