package com.maodouchat.ai.agent

/**
 * Pure rules for the on-device Maodou assistant.
 *
 * Chat ciphertext never goes to the Maodou server. The model is a user-configured
 * OpenAI-compatible endpoint. Tools read already-decrypted SQLCipher rows and send
 * only through the existing E2EE outbox.
 */
object AgentToolPolicy {
    const val MAX_TOOL_ROUNDS = 12
    const val MAX_HISTORY_MESSAGES = 24
    const val MAX_CHAT_HISTORY = 40
    const val MAX_SEARCH_HITS = 20
    const val MAX_TOOL_RESULT_CHARS = 6_000
    const val MAX_TEXT_SEND_CHARS = 4_000
    const val MAX_LIST_CHATS = 80
    const val MAX_DRAFT_CHARS = 4_000
    const val MAX_NICKNAME_CHARS = 40

    enum class Risk {
        READ,
        WRITE,
        SEND
    }

    enum class Approval {
        ALLOW,
        NEED_USER,
        DENY
    }

    data class ToolSpec(
        val name: String,
        val description: String,
        val parameters: Map<String, Parameter>,
        val required: List<String>,
        val risk: Risk
    ) {
        data class Parameter(
            val type: String,
            val description: String,
            val enumValues: List<String>? = null
        )
    }

    val tools: List<ToolSpec> = listOf(
        ToolSpec(
            name = "list_chats",
            description = "List local conversations (id, title, last preview). Secret chats are omitted. PIN-locked chats that are not unlocked in this process are omitted.",
            parameters = mapOf(
                "query" to ToolSpec.Parameter("string", "Optional title/preview substring filter")
            ),
            required = emptyList(),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "get_chat_history",
            description = "Read already-decrypted local messages for one chat, newest first. Secret chats are never readable. PIN-locked chats must be unlocked.",
            parameters = mapOf(
                "chatId" to ToolSpec.Parameter("string", "Local chat id"),
                "limit" to ToolSpec.Parameter("integer", "How many messages, default 20, max 40")
            ),
            required = listOf("chatId"),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "search_messages",
            description = "Keyword search over the on-device message index. Secret chats are excluded. PIN-locked chats are excluded unless already unlocked.",
            parameters = mapOf(
                "query" to ToolSpec.Parameter("string", "Search query"),
                "limit" to ToolSpec.Parameter("integer", "Max hits, default 12, max 20")
            ),
            required = listOf("query"),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "get_contacts",
            description = "List local contacts (id, display name, status).",
            parameters = mapOf(
                "query" to ToolSpec.Parameter("string", "Optional name substring")
            ),
            required = emptyList(),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "get_me",
            description = "Read the signed-in account id and local profile if cached.",
            parameters = emptyMap(),
            required = emptyList(),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "get_chat",
            description = "Read one local conversation: title, mute/pin/archive/unread, last preview. PIN-locked chats must be unlocked.",
            parameters = mapOf("chatId" to ToolSpec.Parameter("string", "Local chat id")),
            required = listOf("chatId"),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "list_starred_messages",
            description = "List locally starred messages. Secret chats are excluded.",
            parameters = mapOf("limit" to ToolSpec.Parameter("integer", "Max rows, default 20, max 40")),
            required = emptyList(),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "list_drafts",
            description = "List this account's unsent chat drafts.",
            parameters = emptyMap(),
            required = emptyList(),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "get_draft",
            description = "Read the unsent draft for one chat.",
            parameters = mapOf("chatId" to ToolSpec.Parameter("string", "Local chat id")),
            required = listOf("chatId"),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "list_local_tasks",
            description = "List on-device AI task reminders.",
            parameters = mapOf("limit" to ToolSpec.Parameter("integer", "Max rows, default 20, max 40")),
            required = emptyList(),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "list_missed_calls",
            description = "List recent missed calls stored on this device.",
            parameters = mapOf("limit" to ToolSpec.Parameter("integer", "Max rows, default 12, max 30")),
            required = emptyList(),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "list_notifications",
            description = "List in-app notification-center items (messages, missed calls, posts, tasks).",
            parameters = mapOf("limit" to ToolSpec.Parameter("integer", "Max rows, default 20, max 40")),
            required = emptyList(),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "rewrite_text",
            description = "Rewrite the given draft locally using the configured model. Does not send a message.",
            parameters = mapOf(
                "text" to ToolSpec.Parameter("string", "Draft to rewrite"),
                "mode" to ToolSpec.Parameter(
                    "string",
                    "polish | shorten | formal | gentle | casual | professional | expand | bullet | clarify | translate",
                    enumValues = listOf(
                        "polish", "shorten", "formal", "gentle", "casual",
                        "professional", "expand", "bullet", "clarify", "translate"
                    )
                ),
                "targetLanguage" to ToolSpec.Parameter("string", "Target language when mode=translate")
            ),
            required = listOf("text"),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "create_local_task",
            description = "Create a local AI task reminder (not a chat message).",
            parameters = mapOf(
                "chatId" to ToolSpec.Parameter("string", "Chat to attach the task to"),
                "title" to ToolSpec.Parameter("string", "Task title"),
                "dueText" to ToolSpec.Parameter("string", "Human due date text")
            ),
            required = listOf("chatId", "title"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "send_text_message",
            description = "Queue a plaintext text message locally; the existing E2EE outbox encrypts and delivers it. Always requires user approval.",
            parameters = mapOf(
                "chatId" to ToolSpec.Parameter("string", "Target chat id"),
                "text" to ToolSpec.Parameter("string", "Message body")
            ),
            required = listOf("chatId", "text"),
            risk = Risk.SEND
        ),
        ToolSpec(
            name = "update_chat",
            description = "Pin, mute, archive, or mark unread on a local conversation. Requires approval.",
            parameters = mapOf(
                "chatId" to ToolSpec.Parameter("string", "Local chat id"),
                "pinned" to ToolSpec.Parameter("boolean", "true=pin, false=unpin"),
                "muted" to ToolSpec.Parameter("boolean", "true=mute notifications"),
                "archived" to ToolSpec.Parameter("boolean", "true=archive"),
                "markedUnread" to ToolSpec.Parameter("boolean", "true=mark unread")
            ),
            required = listOf("chatId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "set_draft",
            description = "Save or replace the unsent draft for a chat. Empty text clears it. Requires approval.",
            parameters = mapOf(
                "chatId" to ToolSpec.Parameter("string", "Local chat id"),
                "text" to ToolSpec.Parameter("string", "Draft body; blank clears")
            ),
            required = listOf("chatId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "star_message",
            description = "Star or unstar a local message, then sync the star flag. Secret chats are blocked. Requires approval.",
            parameters = mapOf(
                "messageId" to ToolSpec.Parameter("string", "Message id"),
                "starred" to ToolSpec.Parameter("boolean", "true=star, false=unstar")
            ),
            required = listOf("messageId", "starred"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "complete_local_task",
            description = "Mark a local AI task completed or not. Requires approval.",
            parameters = mapOf(
                "taskId" to ToolSpec.Parameter("string", "Task id"),
                "completed" to ToolSpec.Parameter("boolean", "true=done")
            ),
            required = listOf("taskId", "completed"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "delete_local_task",
            description = "Delete a local AI task reminder. Requires approval.",
            parameters = mapOf("taskId" to ToolSpec.Parameter("string", "Task id")),
            required = listOf("taskId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "set_contact_nickname",
            description = "Set a local contact remark. Does not change the other person's account name. Requires approval.",
            parameters = mapOf(
                "userId" to ToolSpec.Parameter("string", "Contact user id"),
                "nickname" to ToolSpec.Parameter("string", "Remark; blank clears")
            ),
            required = listOf("userId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "delete_local_message",
            description = "Delete a message from this device only (not a server revoke). Requires approval.",
            parameters = mapOf("messageId" to ToolSpec.Parameter("string", "Message id")),
            required = listOf("messageId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "mark_notification_read",
            description = "Mark one in-app notification-center item read. Requires approval.",
            parameters = mapOf("itemId" to ToolSpec.Parameter("string", "Notification item id")),
            required = listOf("itemId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "list_pinned_messages",
            description = "List pinned messages in a chat via the existing pin API.",
            parameters = mapOf("chatId" to ToolSpec.Parameter("string", "Chat id")),
            required = listOf("chatId"),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "list_friend_requests",
            description = "List incoming or outgoing friend requests.",
            parameters = mapOf(
                "direction" to ToolSpec.Parameter("string", "incoming or outgoing", enumValues = listOf("incoming", "outgoing"))
            ),
            required = emptyList(),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "list_friends",
            description = "List friends from the server.",
            parameters = emptyMap(),
            required = emptyList(),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "search_users",
            description = "Search users by name or id (server directory, not a dump).",
            parameters = mapOf(
                "query" to ToolSpec.Parameter("string", "Name or id substring"),
                "limit" to ToolSpec.Parameter("integer", "Max rows, default 20")
            ),
            required = listOf("query"),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "list_posts",
            description = "List Explore feed posts the signed-in account can see.",
            parameters = mapOf("limit" to ToolSpec.Parameter("integer", "Max posts, default 20, max 40")),
            required = emptyList(),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "get_post",
            description = "Read one Explore post.",
            parameters = mapOf("postId" to ToolSpec.Parameter("string", "Post id")),
            required = listOf("postId"),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "list_post_comments",
            description = "List comments on an Explore post.",
            parameters = mapOf(
                "postId" to ToolSpec.Parameter("string", "Post id"),
                "limit" to ToolSpec.Parameter("integer", "Max comments, default 30")
            ),
            required = listOf("postId"),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "list_blocked_users",
            description = "List blocked user ids.",
            parameters = emptyMap(),
            required = emptyList(),
            risk = Risk.READ
        ),
        ToolSpec(
            name = "revoke_message",
            description = "Revoke a message for everyone via the existing server revoke API. Requires approval.",
            parameters = mapOf("messageId" to ToolSpec.Parameter("string", "Message id")),
            required = listOf("messageId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "react_to_message",
            description = "Add or change an emoji reaction. Requires approval.",
            parameters = mapOf(
                "messageId" to ToolSpec.Parameter("string", "Message id"),
                "emoji" to ToolSpec.Parameter("string", "Emoji")
            ),
            required = listOf("messageId", "emoji"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "pin_message",
            description = "Pin or unpin a chat message via the existing pin API. Requires approval.",
            parameters = mapOf(
                "chatId" to ToolSpec.Parameter("string", "Chat id"),
                "messageId" to ToolSpec.Parameter("string", "Message id")
            ),
            required = listOf("chatId", "messageId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "send_friend_request",
            description = "Send a friend request. Requires approval.",
            parameters = mapOf(
                "userId" to ToolSpec.Parameter("string", "Target user id"),
                "message" to ToolSpec.Parameter("string", "Optional verification note")
            ),
            required = listOf("userId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "accept_friend_request",
            description = "Accept an incoming friend request. Requires approval.",
            parameters = mapOf("requestId" to ToolSpec.Parameter("string", "Request id")),
            required = listOf("requestId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "reject_friend_request",
            description = "Reject an incoming friend request. Requires approval.",
            parameters = mapOf("requestId" to ToolSpec.Parameter("string", "Request id")),
            required = listOf("requestId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "cancel_friend_request",
            description = "Cancel an outgoing friend request. Requires approval.",
            parameters = mapOf("requestId" to ToolSpec.Parameter("string", "Request id")),
            required = listOf("requestId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "remove_friend",
            description = "Remove a friend. Requires approval.",
            parameters = mapOf("userId" to ToolSpec.Parameter("string", "Friend user id")),
            required = listOf("userId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "block_user",
            description = "Block a user. Requires approval.",
            parameters = mapOf("userId" to ToolSpec.Parameter("string", "User id")),
            required = listOf("userId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "unblock_user",
            description = "Unblock a user. Requires approval.",
            parameters = mapOf("userId" to ToolSpec.Parameter("string", "User id")),
            required = listOf("userId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "create_text_post",
            description = "Create a text Explore post (no images). Requires approval.",
            parameters = mapOf(
                "text" to ToolSpec.Parameter("string", "Post body"),
                "visibility" to ToolSpec.Parameter("string", "PUBLIC, CONTACTS, or PRIVATE", enumValues = listOf("PUBLIC", "CONTACTS", "PRIVATE"))
            ),
            required = listOf("text"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "like_post",
            description = "Like or unlike an Explore post. Requires approval.",
            parameters = mapOf(
                "postId" to ToolSpec.Parameter("string", "Post id"),
                "liked" to ToolSpec.Parameter("boolean", "true=like, false=unlike")
            ),
            required = listOf("postId", "liked"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "comment_on_post",
            description = "Comment on an Explore post. Requires approval.",
            parameters = mapOf(
                "postId" to ToolSpec.Parameter("string", "Post id"),
                "text" to ToolSpec.Parameter("string", "Comment body")
            ),
            required = listOf("postId", "text"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "delete_post",
            description = "Delete an Explore post you own. Requires approval.",
            parameters = mapOf("postId" to ToolSpec.Parameter("string", "Post id")),
            required = listOf("postId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "create_direct_chat",
            description = "Open or create a 1:1 chat with a user. Requires approval.",
            parameters = mapOf("userId" to ToolSpec.Parameter("string", "Peer user id")),
            required = listOf("userId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "create_group",
            description = "Create a group with member ids. Requires approval.",
            parameters = mapOf(
                "name" to ToolSpec.Parameter("string", "Group name"),
                "memberIds" to ToolSpec.Parameter("string", "Comma-separated user ids")
            ),
            required = listOf("name", "memberIds"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "rename_group",
            description = "Rename a group. Requires approval.",
            parameters = mapOf(
                "chatId" to ToolSpec.Parameter("string", "Group chat id"),
                "name" to ToolSpec.Parameter("string", "New name")
            ),
            required = listOf("chatId", "name"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "update_group_announcement",
            description = "Set group announcement. Requires approval.",
            parameters = mapOf(
                "chatId" to ToolSpec.Parameter("string", "Group chat id"),
                "announcement" to ToolSpec.Parameter("string", "Announcement text")
            ),
            required = listOf("chatId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "add_group_members",
            description = "Add members to a group. Requires approval.",
            parameters = mapOf(
                "chatId" to ToolSpec.Parameter("string", "Group chat id"),
                "memberIds" to ToolSpec.Parameter("string", "Comma-separated user ids")
            ),
            required = listOf("chatId", "memberIds"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "remove_group_member",
            description = "Remove a group member. Requires approval.",
            parameters = mapOf(
                "chatId" to ToolSpec.Parameter("string", "Group chat id"),
                "memberId" to ToolSpec.Parameter("string", "Member user id")
            ),
            required = listOf("chatId", "memberId"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "mute_group_member",
            description = "Mute a group member until unix-millis. 0 unmutes. Requires approval.",
            parameters = mapOf(
                "chatId" to ToolSpec.Parameter("string", "Group chat id"),
                "memberId" to ToolSpec.Parameter("string", "Member user id"),
                "mutedUntil" to ToolSpec.Parameter("integer", "Unix millis; 0 clears mute")
            ),
            required = listOf("chatId", "memberId", "mutedUntil"),
            risk = Risk.WRITE
        ),
        ToolSpec(
            name = "delete_chat",
            description = "Delete a chat on the server for this account. Requires approval.",
            parameters = mapOf("chatId" to ToolSpec.Parameter("string", "Chat id")),
            required = listOf("chatId"),
            risk = Risk.WRITE
        )
    )

    fun toolByName(name: String): ToolSpec? = tools.firstOrNull { it.name == name }

    fun approvalFor(name: String, arguments: Map<String, String>): Approval {
        val spec = toolByName(name) ?: return Approval.DENY
        return when (spec.risk) {
            Risk.READ -> Approval.ALLOW
            Risk.WRITE, Risk.SEND -> Approval.NEED_USER
        }
    }

    fun openaiToolsJson(): List<Map<String, Any?>> = tools.map { spec ->
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to spec.name,
                "description" to spec.description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to spec.parameters.mapValues { (_, p) ->
                        buildMap<String, Any?> {
                            put("type", p.type)
                            put("description", p.description)
                            if (p.enumValues != null) put("enum", p.enumValues)
                        }
                    },
                    "required" to spec.required
                )
            )
        )
    }

    fun systemPrompt(nowLabel: String, styleHint: String?): String = buildString {
        appendLine("你是毛豆助手，运行在用户的毛豆聊天 Android 客户端进程里。")
        appendLine("当前时间：$nowLabel（会话锚点，需要实时时间时仍以此时为准）。")
        appendLine("你可以读本机已解密的会话、消息、草稿、星标、联系人、待办、未接来电、通知中心，以及动态/好友申请（走现有 API，不是 SQL dump）。")
        appendLine("你可以在用户批准后：置顶/免打扰/归档、改草稿、星标、撤回、反应、消息置顶、好友申请、拉黑、发纯文本动态/评论、建单聊/群、改群公告、发文本。")
        appendLine("禁止要求用户把聊天明文贴到服务器。禁止声称能点屏幕、写任意 SQL、发红包、通话、改系统设置、编辑已发出的加密消息（编辑会走明文 REST）。")
        appendLine("人对人、群成员互发仍是端到端加密：代发必须走 send_text_message，由本机 outbox 加密。")
        appendLine("密聊与未解锁的 PIN 会话不可读、不可发、不可改草稿。不要编造已发送、已删除、已转账等特权结果。")
        appendLine("写操作与发消息会先弹出用户审批；被拒绝后改方案，不要死循环同一调用。")
        appendLine("回复简洁。没有工具结果就不要声称已经操作成功。")
        if (!styleHint.isNullOrBlank()) {
            appendLine("写作偏好（不可覆盖安全规则）：$styleHint")
        }
    }

    fun rewriteInstruction(mode: String, targetLanguage: String?): String {
        val safe = when (mode.trim().lowercase()) {
            "shorten" -> "缩短，保留原意"
            "formal" -> "更正式礼貌"
            "gentle" -> "更温和"
            "casual" -> "更口语"
            "professional" -> "更专业商务"
            "expand" -> "稍加展开，不编造事实"
            "bullet" -> "改成简洁条目"
            "clarify" -> "更清楚，不改变立场"
            "translate" -> "翻译成 ${targetLanguage?.trim().orEmpty().ifBlank { "中文" }}，只输出译文"
            else -> "润色，保持原意和语气"
        }
        return "改写下面的草稿：$safe。只输出改写结果，不要解释。"
    }

    fun suggestInstruction(tone: String, count: Int): String {
        val safeTone = when (tone.trim().lowercase()) {
            "natural", "friendly", "formal", "concise", "warm",
            "humorous", "direct", "empathetic", "encouraging" -> tone.trim().lowercase()
            else -> "friendly"
        }
        val n = count.coerceIn(1, 4)
        return "根据对话写 $n 条可直接发送的回复，语气 $safeTone。每条一行，不要编号以外的解释。"
    }

    fun summarizeInstruction(style: String): String {
        val safe = when (style.trim().lowercase()) {
            "detailed" -> "详细叙述"
            "decisions" -> "只列已做出的决定"
            "tasks" -> "只列待办，每行一条"
            "timeline" -> "按时间顺序列要点"
            "risks" -> "只列风险和阻塞"
            else -> "简要概括"
        }
        return "总结这些本机已解密消息。风格：$safe。不要声称你已在聊天里执行了任何操作。"
    }

    fun groupAssistantInstruction(mode: String, query: String): String {
        val safeMode = when (mode.trim().lowercase()) {
            "summary", "decisions", "tasks", "timeline", "risks" -> mode.trim().lowercase()
            else -> "answer"
        }
        return "用本机已解密的群聊上下文回答。模式=$safeMode。用户问题：${query.trim().take(700)}"
    }

    fun translateInstruction(targetLanguage: String): String =
        "把下面文本翻译成 ${targetLanguage.trim().ifBlank { "中文" }}。只输出译文。"
}
