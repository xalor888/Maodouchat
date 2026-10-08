package com.maodouchat.ai.agent

internal val agentToolCatalog: List<AgentToolPolicy.ToolSpec> = listOf(
        AgentToolPolicy.ToolSpec(
            name = "list_chats",
            description = "List local conversations (id, title, last preview). Secret chats are omitted. PIN-locked chats that are not unlocked in this process are omitted.",
            parameters = mapOf(
                "query" to AgentToolPolicy.ToolSpec.Parameter("string", "Optional title/preview substring filter")
            ),
            required = emptyList(),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "get_chat_history",
            description = "Read already-decrypted local messages for one chat, newest first. Secret chats are never readable. PIN-locked chats must be unlocked.",
            parameters = mapOf(
                "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Local chat id"),
                "limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "How many messages, default 20, max 40")
            ),
            required = listOf("chatId"),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "search_messages",
            description = "Keyword search over the on-device message index. Secret chats are excluded. PIN-locked chats are excluded unless already unlocked.",
            parameters = mapOf(
                "query" to AgentToolPolicy.ToolSpec.Parameter("string", "Search query"),
                "limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max hits, default 12, max 20")
            ),
            required = listOf("query"),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "get_contacts",
            description = "List local contacts (id, display name, status).",
            parameters = mapOf(
                "query" to AgentToolPolicy.ToolSpec.Parameter("string", "Optional name substring")
            ),
            required = emptyList(),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "get_me",
            description = "Read the signed-in account id and local profile if cached.",
            parameters = emptyMap(),
            required = emptyList(),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "get_chat",
            description = "Read one local conversation: title, mute/pin/archive/unread, last preview. PIN-locked chats must be unlocked.",
            parameters = mapOf("chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Local chat id")),
            required = listOf("chatId"),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "list_starred_messages",
            description = "List locally starred messages. Secret chats are excluded.",
            parameters = mapOf("limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max rows, default 20, max 40")),
            required = emptyList(),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "list_drafts",
            description = "List this account's unsent chat drafts.",
            parameters = emptyMap(),
            required = emptyList(),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "get_draft",
            description = "Read the unsent draft for one chat.",
            parameters = mapOf("chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Local chat id")),
            required = listOf("chatId"),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "list_local_tasks",
            description = "List on-device AI task reminders.",
            parameters = mapOf("limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max rows, default 20, max 40")),
            required = emptyList(),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "list_missed_calls",
            description = "List recent missed calls stored on this device.",
            parameters = mapOf("limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max rows, default 12, max 30")),
            required = emptyList(),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "list_notifications",
            description = "List in-app notification-center items (messages, missed calls, posts, tasks).",
            parameters = mapOf("limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max rows, default 20, max 40")),
            required = emptyList(),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "rewrite_text",
            description = "Rewrite the given draft locally using the configured model. Does not send a message.",
            parameters = mapOf(
                "text" to AgentToolPolicy.ToolSpec.Parameter("string", "Draft to rewrite"),
                "mode" to AgentToolPolicy.ToolSpec.Parameter(
                    "string",
                    "polish | shorten | formal | gentle | casual | professional | expand | bullet | clarify | translate",
                    enumValues = listOf(
                        "polish", "shorten", "formal", "gentle", "casual",
                        "professional", "expand", "bullet", "clarify", "translate"
                    )
                ),
                "targetLanguage" to AgentToolPolicy.ToolSpec.Parameter("string", "Target language when mode=translate")
            ),
            required = listOf("text"),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "create_local_task",
            description = "Create a local AI task reminder (not a chat message).",
            parameters = mapOf(
                "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Chat to attach the task to"),
                "title" to AgentToolPolicy.ToolSpec.Parameter("string", "Task title"),
                "dueText" to AgentToolPolicy.ToolSpec.Parameter("string", "Human due date text")
            ),
            required = listOf("chatId", "title"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "send_text_message",
            description = "Queue a plaintext text message locally; the existing E2EE outbox encrypts and delivers it. Always requires user approval.",
            parameters = mapOf(
                "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Target chat id"),
                "text" to AgentToolPolicy.ToolSpec.Parameter("string", "Message body")
            ),
            required = listOf("chatId", "text"),
            risk = AgentToolPolicy.Risk.SEND
        ),
        AgentToolPolicy.ToolSpec(
            name = "update_chat",
            description = "Pin, mute, archive, or mark unread on a local conversation. Requires approval.",
            parameters = mapOf(
                "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Local chat id"),
                "pinned" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=pin, false=unpin"),
                "muted" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=mute notifications"),
                "archived" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=archive"),
                "markedUnread" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=mark unread")
            ),
            required = listOf("chatId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "set_draft",
            description = "Save or replace the unsent draft for a chat. Empty text clears it. Requires approval.",
            parameters = mapOf(
                "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Local chat id"),
                "text" to AgentToolPolicy.ToolSpec.Parameter("string", "Draft body; blank clears")
            ),
            required = listOf("chatId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "star_message",
            description = "Star or unstar a local message, then sync the star flag. Secret chats are blocked. Requires approval.",
            parameters = mapOf(
                "messageId" to AgentToolPolicy.ToolSpec.Parameter("string", "Message id"),
                "starred" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=star, false=unstar")
            ),
            required = listOf("messageId", "starred"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "complete_local_task",
            description = "Mark a local AI task completed or not. Requires approval.",
            parameters = mapOf(
                "taskId" to AgentToolPolicy.ToolSpec.Parameter("string", "Task id"),
                "completed" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=done")
            ),
            required = listOf("taskId", "completed"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "delete_local_task",
            description = "Delete a local AI task reminder. Requires approval.",
            parameters = mapOf("taskId" to AgentToolPolicy.ToolSpec.Parameter("string", "Task id")),
            required = listOf("taskId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "set_contact_nickname",
            description = "Set a local contact remark. Does not change the other person's account name. Requires approval.",
            parameters = mapOf(
                "userId" to AgentToolPolicy.ToolSpec.Parameter("string", "Contact user id"),
                "nickname" to AgentToolPolicy.ToolSpec.Parameter("string", "Remark; blank clears")
            ),
            required = listOf("userId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "delete_local_message",
            description = "Delete a message from this device only (not a server revoke). Requires approval.",
            parameters = mapOf("messageId" to AgentToolPolicy.ToolSpec.Parameter("string", "Message id")),
            required = listOf("messageId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "mark_notification_read",
            description = "Mark one in-app notification-center item read. Requires approval.",
            parameters = mapOf("itemId" to AgentToolPolicy.ToolSpec.Parameter("string", "Notification item id")),
            required = listOf("itemId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "list_pinned_messages",
            description = "List pinned messages in a chat via the existing pin API.",
            parameters = mapOf("chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Chat id")),
            required = listOf("chatId"),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "list_friend_requests",
            description = "List incoming or outgoing friend requests.",
            parameters = mapOf(
                "direction" to AgentToolPolicy.ToolSpec.Parameter("string", "incoming or outgoing", enumValues = listOf("incoming", "outgoing"))
            ),
            required = emptyList(),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "list_friends",
            description = "List friends from the server.",
            parameters = emptyMap(),
            required = emptyList(),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "search_users",
            description = "Search users by name or id (server directory, not a dump).",
            parameters = mapOf(
                "query" to AgentToolPolicy.ToolSpec.Parameter("string", "Name or id substring"),
                "limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max rows, default 20")
            ),
            required = listOf("query"),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "list_posts",
            description = "List Explore feed posts the signed-in account can see.",
            parameters = mapOf("limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max posts, default 20, max 40")),
            required = emptyList(),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "get_post",
            description = "Read one Explore post.",
            parameters = mapOf("postId" to AgentToolPolicy.ToolSpec.Parameter("string", "Post id")),
            required = listOf("postId"),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "list_post_comments",
            description = "List comments on an Explore post.",
            parameters = mapOf(
                "postId" to AgentToolPolicy.ToolSpec.Parameter("string", "Post id"),
                "limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max comments, default 30")
            ),
            required = listOf("postId"),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "list_blocked_users",
            description = "List blocked user ids.",
            parameters = emptyMap(),
            required = emptyList(),
            risk = AgentToolPolicy.Risk.READ
        ),
        AgentToolPolicy.ToolSpec(
            name = "revoke_message",
            description = "Revoke a message for everyone via the existing server revoke API. Requires approval.",
            parameters = mapOf("messageId" to AgentToolPolicy.ToolSpec.Parameter("string", "Message id")),
            required = listOf("messageId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "react_to_message",
            description = "Add or change an emoji reaction. Requires approval.",
            parameters = mapOf(
                "messageId" to AgentToolPolicy.ToolSpec.Parameter("string", "Message id"),
                "emoji" to AgentToolPolicy.ToolSpec.Parameter("string", "Emoji")
            ),
            required = listOf("messageId", "emoji"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "pin_message",
            description = "Pin or unpin a chat message via the existing pin API. Requires approval.",
            parameters = mapOf(
                "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Chat id"),
                "messageId" to AgentToolPolicy.ToolSpec.Parameter("string", "Message id")
            ),
            required = listOf("chatId", "messageId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "send_friend_request",
            description = "Send a friend request. Requires approval.",
            parameters = mapOf(
                "userId" to AgentToolPolicy.ToolSpec.Parameter("string", "Target user id"),
                "message" to AgentToolPolicy.ToolSpec.Parameter("string", "Optional verification note")
            ),
            required = listOf("userId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "accept_friend_request",
            description = "Accept an incoming friend request. Requires approval.",
            parameters = mapOf("requestId" to AgentToolPolicy.ToolSpec.Parameter("string", "Request id")),
            required = listOf("requestId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "reject_friend_request",
            description = "Reject an incoming friend request. Requires approval.",
            parameters = mapOf("requestId" to AgentToolPolicy.ToolSpec.Parameter("string", "Request id")),
            required = listOf("requestId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "cancel_friend_request",
            description = "Cancel an outgoing friend request. Requires approval.",
            parameters = mapOf("requestId" to AgentToolPolicy.ToolSpec.Parameter("string", "Request id")),
            required = listOf("requestId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "remove_friend",
            description = "Remove a friend. Requires approval.",
            parameters = mapOf("userId" to AgentToolPolicy.ToolSpec.Parameter("string", "Friend user id")),
            required = listOf("userId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "block_user",
            description = "Block a user. Requires approval.",
            parameters = mapOf("userId" to AgentToolPolicy.ToolSpec.Parameter("string", "User id")),
            required = listOf("userId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "unblock_user",
            description = "Unblock a user. Requires approval.",
            parameters = mapOf("userId" to AgentToolPolicy.ToolSpec.Parameter("string", "User id")),
            required = listOf("userId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "create_text_post",
            description = "Create a text Explore post (no images). Requires approval.",
            parameters = mapOf(
                "text" to AgentToolPolicy.ToolSpec.Parameter("string", "Post body"),
                "visibility" to AgentToolPolicy.ToolSpec.Parameter("string", "PUBLIC, CONTACTS, or PRIVATE", enumValues = listOf("PUBLIC", "CONTACTS", "PRIVATE"))
            ),
            required = listOf("text"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "like_post",
            description = "Like or unlike an Explore post. Requires approval.",
            parameters = mapOf(
                "postId" to AgentToolPolicy.ToolSpec.Parameter("string", "Post id"),
                "liked" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=like, false=unlike")
            ),
            required = listOf("postId", "liked"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "comment_on_post",
            description = "Comment on an Explore post. Requires approval.",
            parameters = mapOf(
                "postId" to AgentToolPolicy.ToolSpec.Parameter("string", "Post id"),
                "text" to AgentToolPolicy.ToolSpec.Parameter("string", "Comment body")
            ),
            required = listOf("postId", "text"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "delete_post",
            description = "Delete an Explore post you own. Requires approval.",
            parameters = mapOf("postId" to AgentToolPolicy.ToolSpec.Parameter("string", "Post id")),
            required = listOf("postId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "create_direct_chat",
            description = "Open or create a 1:1 chat with a user. Requires approval.",
            parameters = mapOf("userId" to AgentToolPolicy.ToolSpec.Parameter("string", "Peer user id")),
            required = listOf("userId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "create_group",
            description = "Create a group with member ids. Requires approval.",
            parameters = mapOf(
                "name" to AgentToolPolicy.ToolSpec.Parameter("string", "Group name"),
                "memberIds" to AgentToolPolicy.ToolSpec.Parameter("string", "Comma-separated user ids")
            ),
            required = listOf("name", "memberIds"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "rename_group",
            description = "Rename a group. Requires approval.",
            parameters = mapOf(
                "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Group chat id"),
                "name" to AgentToolPolicy.ToolSpec.Parameter("string", "New name")
            ),
            required = listOf("chatId", "name"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "update_group_announcement",
            description = "Set group announcement. Requires approval.",
            parameters = mapOf(
                "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Group chat id"),
                "announcement" to AgentToolPolicy.ToolSpec.Parameter("string", "Announcement text")
            ),
            required = listOf("chatId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "add_group_members",
            description = "Add members to a group. Requires approval.",
            parameters = mapOf(
                "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Group chat id"),
                "memberIds" to AgentToolPolicy.ToolSpec.Parameter("string", "Comma-separated user ids")
            ),
            required = listOf("chatId", "memberIds"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "remove_group_member",
            description = "Remove a group member. Requires approval.",
            parameters = mapOf(
                "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Group chat id"),
                "memberId" to AgentToolPolicy.ToolSpec.Parameter("string", "Member user id")
            ),
            required = listOf("chatId", "memberId"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "mute_group_member",
            description = "Mute a group member until unix-millis. 0 unmutes. Requires approval.",
            parameters = mapOf(
                "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Group chat id"),
                "memberId" to AgentToolPolicy.ToolSpec.Parameter("string", "Member user id"),
                "mutedUntil" to AgentToolPolicy.ToolSpec.Parameter("integer", "Unix millis; 0 clears mute")
            ),
            required = listOf("chatId", "memberId", "mutedUntil"),
            risk = AgentToolPolicy.Risk.WRITE
        ),
        AgentToolPolicy.ToolSpec(
            name = "delete_chat",
            description = "Delete a chat on the server for this account. Requires approval.",
            parameters = mapOf("chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Chat id")),
            required = listOf("chatId"),
            risk = AgentToolPolicy.Risk.WRITE
        )
    )
