// Fuzz / random-oracle tests for ChatListVisibleChats.filterVisibleChats
// (checklist line 931, 156th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: archived split,
// folder filter (all / groups / direct / unread / secret / locked /
// custom / unknown), search over group name, participant names, last
// message and draft text (case-insensitive, locked chats match by title
// only), then the pinned / activity / muted sort. The folder system ids
// and the unread/activity weights are inlined as literals; the oracle
// never calls a production function.
package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun activityScoreOracle156(
    lastMessageTime: Long,
    draftUpdatedAt: Long = 0L,
    unreadCount: Int = 0,
    markedUnread: Boolean = false,
    muted: Boolean = false,
): Long {
    val activity = maxOf(lastMessageTime, draftUpdatedAt).coerceAtLeast(0L)
    if (muted) return activity
    var score = activity
    if (markedUnread) score += 1_000_000_000L
    val unread = unreadCount.coerceIn(0, 999)
    if (unread > 0) score += 500_000_000L + unread
    return score
}

internal fun filterVisibleChatsOracle156(
    chats: List<Chat>,
    showArchived: Boolean,
    selectedFolderId: String?,
    folders: List<ChatFolder>,
    secretChatIds: Set<String>,
    lockedChatIds: Set<String>,
    searchQuery: String,
    messageMatchedChatIds: Set<String>,
    drafts: Map<String, ChatDraft>,
    unreadPriorityEnabled: Boolean,
): List<String> {
    val visible = chats.filter { it.archived == showArchived }
    val folderFiltered = when {
        selectedFolderId.isNullOrBlank() ->
            visible.filter { !it.isSecret }
        selectedFolderId == "system:groups" ->
            visible.filter { it.isGroup }
        selectedFolderId == "system:direct" ->
            visible.filter { !it.isGroup && !it.isSecret }
        selectedFolderId == "system:unread" ->
            visible.filter { !it.isSecret && (it.unreadCount > 0 || it.markedUnread) }
        selectedFolderId == "system:secret" ->
            visible.filter { it.isSecret || it.id in secretChatIds }
        selectedFolderId == "system:locked" ->
            visible.filter { it.id in lockedChatIds && !it.isSecret }
        else -> {
            val folder = folders.firstOrNull { it.id == selectedFolderId }
            if (folder == null) visible.filter { !it.isSecret }
            else {
                val ids = folder.chatIds.toSet()
                visible.filter { it.id in ids && !it.isSecret }
            }
        }
    }
    val matched = if (searchQuery.isBlank()) folderFiltered else folderFiltered.filter { chat ->
        val name = chat.groupName
            ?: chat.participants.firstOrNull()?.displayName
            ?: chat.participants.firstOrNull()?.name
            ?: ""
        val nameHit = name.contains(searchQuery, ignoreCase = true) ||
            chat.participants.any { user ->
                user.displayName.contains(searchQuery, ignoreCase = true) ||
                    user.name.contains(searchQuery, ignoreCase = true) ||
                    (user.nickname?.contains(searchQuery, ignoreCase = true) == true) ||
                    user.email.contains(searchQuery, ignoreCase = true)
            }
        if (chat.id in lockedChatIds) return@filter nameHit
        nameHit ||
            chat.lastMessage.contains(searchQuery, ignoreCase = true) ||
            drafts[chat.id]?.text?.contains(searchQuery, ignoreCase = true) == true ||
            chat.id in messageMatchedChatIds
    }
    return matched.sortedWith(
        compareByDescending<Chat> { it.pinnedAt > 0 }
            .thenByDescending { it.pinnedAt }
            .thenByDescending { chat ->
                if (unreadPriorityEnabled) {
                    activityScoreOracle156(
                        lastMessageTime = chat.lastMessageTime,
                        draftUpdatedAt = drafts[chat.id]?.updatedAt ?: 0L,
                        unreadCount = chat.unreadCount,
                        markedUnread = chat.markedUnread,
                        muted = chat.notificationsMuted,
                    )
                } else {
                    maxOf(chat.lastMessageTime, drafts[chat.id]?.updatedAt ?: 0L)
                }
            }
            .thenBy { if (!unreadPriorityEnabled && it.notificationsMuted) 1 else 0 }
    ).map { it.id }
}

// ---------- generators ----------

internal data class VisibleCase156(
    val chats: List<Chat>,
    val showArchived: Boolean,
    val selectedFolderId: String?,
    val folders: List<ChatFolder>,
    val secretChatIds: Set<String>,
    val lockedChatIds: Set<String>,
    val searchQuery: String,
    val messageMatchedChatIds: Set<String>,
    val drafts: Map<String, ChatDraft>,
    val unreadPriorityEnabled: Boolean,
)

internal val VISIBLE_CHAT_IDS156 = listOf("c0", "c1", "c2", "c3")

internal val VISIBLE_FOLDER_IDS156 = listOf<String?>(
    null, "", "all", "system:groups", "system:direct", "system:unread",
    "system:secret", "system:locked", "f1", "f2", "no-such-folder",
)

internal val VISIBLE_QUERY_POOL156 = listOf(
    "", " ", "a", "ALICE", "alice", "张三", "发布计划", "unrelated",
    "Σ", "σ", "İ", "hello", "c0", "小组", "new", "old",
)

internal val VISIBLE_NAME_POOL156 = listOf(
    "Alice", "ALICE", "alice", "Bob", "张三", "群名", "", " ", "Σ", "σ",
)

internal val VISIBLE_MESSAGE_POOL156 = listOf(
    "", "hello", "HELLO", "new", "old", "unrelated", "明天讨论发布计划",
    "ALICE", "Σσ", "  ",
)

internal val VISIBLE_TIME_POOL156 = listOf(0L, 50L, 100L, 200L, 300L, 500L, 900L, -100L)

internal val VISIBLE_UNREAD_POOL156 = listOf(0, 1, 2, 3, 999, 1000, -1)

internal val VISIBLE_PINNED_POOL156 = listOf(0L, 1L, 100L, 200L, 300L, -1L)

internal val VISIBLE_CHAT_TYPE_POOL156 = listOf("DIRECT", "GROUP", "CHANNEL", "SECRET", "")

internal fun visibleUser156(rnd: Random): User = User(
    id = listOf("u1", "u2")[rnd.nextInt(2)],
    name = VISIBLE_NAME_POOL156[rnd.nextInt(VISIBLE_NAME_POOL156.size)],
    email = when (rnd.nextInt(4)) {
        0 -> "alice@example.com"
        1 -> "ALICE@EXAMPLE.COM"
        else -> ""
    },
    nickname = when (rnd.nextInt(4)) {
        0 -> null
        else -> VISIBLE_NAME_POOL156[rnd.nextInt(VISIBLE_NAME_POOL156.size)]
    },
)

internal fun visibleChat156(rnd: Random, id: String): Chat = Chat(
    id = id,
    participants = List(rnd.nextInt(0, 3)) { visibleUser156(rnd) },
    lastMessage = VISIBLE_MESSAGE_POOL156[rnd.nextInt(VISIBLE_MESSAGE_POOL156.size)],
    lastMessageTime = VISIBLE_TIME_POOL156[rnd.nextInt(VISIBLE_TIME_POOL156.size)],
    unreadCount = VISIBLE_UNREAD_POOL156[rnd.nextInt(VISIBLE_UNREAD_POOL156.size)],
    isGroup = rnd.nextBoolean(),
    chatType = VISIBLE_CHAT_TYPE_POOL156[rnd.nextInt(VISIBLE_CHAT_TYPE_POOL156.size)],
    groupName = when (rnd.nextInt(5)) {
        0 -> null
        1 -> "小组"
        2 -> "官方频道"
        else -> VISIBLE_NAME_POOL156[rnd.nextInt(VISIBLE_NAME_POOL156.size)]
    },
    pinnedAt = VISIBLE_PINNED_POOL156[rnd.nextInt(VISIBLE_PINNED_POOL156.size)],
    notificationsMuted = rnd.nextBoolean(),
    archived = rnd.nextInt(10) < 2,
    markedUnread = rnd.nextBoolean(),
)

internal fun randomVisibleCase156(rnd: Random): VisibleCase156 {
    val ids = VISIBLE_CHAT_IDS156.shuffled(rnd).take(rnd.nextInt(0, 5))
    val chats = ids.map { visibleChat156(rnd, it) }
    val folders = List(rnd.nextInt(0, 3)) { i ->
        ChatFolder(
            id = "f${i + 1}",
            name = "文件夹${i + 1}",
            chatIds = ids.filter { rnd.nextBoolean() },
        )
    }
    fun randomIdSet(): Set<String> = ids.filter { rnd.nextInt(10) < 3 }.toSet()
    val drafts = ids.filter { rnd.nextInt(10) < 3 }.associateWith {
        ChatDraft(
            chatId = it,
            text = VISIBLE_MESSAGE_POOL156[rnd.nextInt(VISIBLE_MESSAGE_POOL156.size)],
            updatedAt = VISIBLE_TIME_POOL156[rnd.nextInt(VISIBLE_TIME_POOL156.size)],
        )
    }
    return VisibleCase156(
        chats = chats,
        showArchived = rnd.nextInt(10) < 2,
        selectedFolderId = VISIBLE_FOLDER_IDS156[rnd.nextInt(VISIBLE_FOLDER_IDS156.size)],
        folders = folders,
        secretChatIds = randomIdSet(),
        lockedChatIds = randomIdSet(),
        searchQuery = VISIBLE_QUERY_POOL156[rnd.nextInt(VISIBLE_QUERY_POOL156.size)],
        messageMatchedChatIds = randomIdSet(),
        drafts = drafts,
        unreadPriorityEnabled = rnd.nextBoolean(),
    )
}

internal fun visibleJsonEscape156(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20) {
                    val v = c.code
                    sb.append("\\u")
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

internal fun visibleCaseWireJson156(case: VisibleCase156): String {
    fun esc(s: String): String = "\"" + visibleJsonEscape156(s) + "\""
    fun strList(xs: List<String>): String = xs.joinToString(",", "[", "]") { esc(it) }
    val sb = StringBuilder()
    sb.append("{\"chats\":[")
    case.chats.forEachIndexed { i, chat ->
        if (i > 0) sb.append(",")
        sb.append("{\"id\":").append(esc(chat.id))
        sb.append(",\"ps\":[")
        chat.participants.forEachIndexed { j, u ->
            if (j > 0) sb.append(",")
            sb.append("{\"id\":").append(esc(u.id))
            sb.append(",\"n\":").append(esc(u.name))
            sb.append(",\"em\":").append(esc(u.email))
            val nickname = u.nickname
            if (nickname != null) sb.append(",\"nn\":").append(esc(nickname))
            sb.append("}")
        }
        sb.append("]")
        sb.append(",\"lm\":").append(esc(chat.lastMessage))
        sb.append(",\"lmt\":").append(chat.lastMessageTime)
        sb.append(",\"uc\":").append(chat.unreadCount)
        sb.append(",\"ig\":").append(if (chat.isGroup) 1 else 0)
        sb.append(",\"ct\":").append(esc(chat.chatType))
        val groupName = chat.groupName
        if (groupName != null) sb.append(",\"gn\":").append(esc(groupName))
        sb.append(",\"pin\":").append(chat.pinnedAt)
        sb.append(",\"mut\":").append(if (chat.notificationsMuted) 1 else 0)
        sb.append(",\"arc\":").append(if (chat.archived) 1 else 0)
        sb.append(",\"mu\":").append(if (chat.markedUnread) 1 else 0)
        sb.append("}")
    }
    sb.append("]")
    sb.append(",\"sa\":").append(if (case.showArchived) 1 else 0)
    if (case.selectedFolderId != null) sb.append(",\"sf\":").append(esc(case.selectedFolderId))
    sb.append(",\"fo\":[")
    case.folders.forEachIndexed { i, f ->
        if (i > 0) sb.append(",")
        sb.append("{\"id\":").append(esc(f.id))
        sb.append(",\"ids\":").append(strList(f.chatIds))
        sb.append("}")
    }
    sb.append("]")
    sb.append(",\"sec\":").append(strList(case.secretChatIds.toList()))
    sb.append(",\"loc\":").append(strList(case.lockedChatIds.toList()))
    sb.append(",\"q\":").append(esc(case.searchQuery))
    sb.append(",\"mm\":").append(strList(case.messageMatchedChatIds.toList()))
    sb.append(",\"dr\":{")
    case.drafts.entries.forEachIndexed { i, (id, d) ->
        if (i > 0) sb.append(",")
        sb.append(esc(id)).append(":{\"t\":").append(esc(d.text))
        sb.append(",\"u\":").append(d.updatedAt).append("}")
    }
    sb.append("}")
    sb.append(",\"up\":").append(if (case.unreadPriorityEnabled) 1 else 0)
    sb.append("}")
    return sb.toString()
}

class ChatListVisibleChatsFuzzTest {

    private fun chat(
        id: String,
        lastMessage: String = "",
        lastMessageTime: Long = 0L,
        unreadCount: Int = 0,
        isGroup: Boolean = false,
        chatType: String = "DIRECT",
        groupName: String? = null,
        participants: List<User> = emptyList(),
        pinnedAt: Long = 0L,
        notificationsMuted: Boolean = false,
        archived: Boolean = false,
        markedUnread: Boolean = false,
    ): Chat = Chat(
        id = id,
        participants = participants,
        lastMessage = lastMessage,
        lastMessageTime = lastMessageTime,
        unreadCount = unreadCount,
        isGroup = isGroup,
        chatType = chatType,
        groupName = groupName,
        pinnedAt = pinnedAt,
        notificationsMuted = notificationsMuted,
        archived = archived,
        markedUnread = markedUnread,
    )

    private fun case(
        chats: List<Chat>,
        showArchived: Boolean = false,
        selectedFolderId: String? = null,
        folders: List<ChatFolder> = emptyList(),
        secretChatIds: Set<String> = emptySet(),
        lockedChatIds: Set<String> = emptySet(),
        searchQuery: String = "",
        messageMatchedChatIds: Set<String> = emptySet(),
        drafts: Map<String, ChatDraft> = emptyMap(),
        unreadPriorityEnabled: Boolean = true,
    ): VisibleCase156 = VisibleCase156(
        chats = chats,
        showArchived = showArchived,
        selectedFolderId = selectedFolderId,
        folders = folders,
        secretChatIds = secretChatIds,
        lockedChatIds = lockedChatIds,
        searchQuery = searchQuery,
        messageMatchedChatIds = messageMatchedChatIds,
        drafts = drafts,
        unreadPriorityEnabled = unreadPriorityEnabled,
    )

    private fun check(c: VisibleCase156) {
        val expected = filterVisibleChatsOracle156(
            chats = c.chats,
            showArchived = c.showArchived,
            selectedFolderId = c.selectedFolderId,
            folders = c.folders,
            secretChatIds = c.secretChatIds,
            lockedChatIds = c.lockedChatIds,
            searchQuery = c.searchQuery,
            messageMatchedChatIds = c.messageMatchedChatIds,
            drafts = c.drafts,
            unreadPriorityEnabled = c.unreadPriorityEnabled,
        )
        val actual = ChatListVisibleChats.filterVisibleChats(
            chats = c.chats,
            showArchived = c.showArchived,
            selectedFolderId = c.selectedFolderId,
            folders = c.folders,
            secretChatIds = c.secretChatIds,
            lockedChatIds = c.lockedChatIds,
            searchQuery = c.searchQuery,
            messageMatchedChatIds = c.messageMatchedChatIds,
            drafts = c.drafts,
            unreadPriorityEnabled = c.unreadPriorityEnabled,
        ).map { it.id }
        assertEquals(expected, actual)
    }

    @Test
    fun pinnedBoundaries() {
        // (case, expected ids)
        val cases = listOf(
            // Newer draft moves its chat ahead of a newer message.
            case(
                chats = listOf(
                    chat("recent-message", lastMessage = "new", lastMessageTime = 200L),
                    chat("draft-chat", lastMessage = "old", lastMessageTime = 100L),
                ),
                drafts = mapOf("draft-chat" to ChatDraft("draft-chat", "unfinished", 300L)),
            ) to listOf("draft-chat", "recent-message"),
            // Pinned stays ahead of a newer message; larger pinnedAt wins.
            case(
                chats = listOf(
                    chat("newer", lastMessageTime = 500L),
                    chat("pinned", lastMessageTime = 100L, pinnedAt = 200L),
                ),
            ) to listOf("pinned", "newer"),
            case(
                chats = listOf(
                    chat("pin100", lastMessageTime = 900L, pinnedAt = 100L),
                    chat("pin300", lastMessageTime = 100L, pinnedAt = 300L),
                ),
            ) to listOf("pin300", "pin100"),
            // Archived split.
            case(
                chats = listOf(
                    chat("inbox", archived = false),
                    chat("archived", archived = true),
                ),
            ) to listOf("inbox"),
            case(
                chats = listOf(
                    chat("inbox", archived = false),
                    chat("archived", archived = true),
                ),
                showArchived = true,
            ) to listOf("archived"),
            // Locked chat: draft text does not match search.
            case(
                chats = listOf(chat("draft-chat", lastMessage = "unrelated", lastMessageTime = 100L)),
                searchQuery = "发布计划",
                drafts = mapOf("draft-chat" to ChatDraft("draft-chat", "明天讨论发布计划", 200L)),
                lockedChatIds = setOf("draft-chat"),
            ) to emptyList(),
            // Same chat unlocked: draft text matches.
            case(
                chats = listOf(chat("draft-chat", lastMessage = "unrelated", lastMessageTime = 100L)),
                searchQuery = "发布计划",
                drafts = mapOf("draft-chat" to ChatDraft("draft-chat", "明天讨论发布计划", 200L)),
            ) to listOf("draft-chat"),
            // Muted sorts after a same-time chat only when unread priority is off.
            case(
                chats = listOf(
                    chat("muted", lastMessageTime = 200L, notificationsMuted = true),
                    chat("plain", lastMessageTime = 200L),
                ),
                unreadPriorityEnabled = false,
            ) to listOf("plain", "muted"),
            // Unread beats newer read when priority is on; reversed when off.
            case(
                chats = listOf(
                    chat("read-newer", lastMessageTime = 200L),
                    chat("unread-older", lastMessageTime = 100L, unreadCount = 3),
                ),
            ) to listOf("unread-older", "read-newer"),
            case(
                chats = listOf(
                    chat("read-newer", lastMessageTime = 200L),
                    chat("unread-older", lastMessageTime = 100L, unreadCount = 3),
                ),
                unreadPriorityEnabled = false,
            ) to listOf("read-newer", "unread-older"),
            // Marked-unread outranks plain unread (1e9 vs 5e8 weight).
            case(
                chats = listOf(
                    chat("unread", unreadCount = 2, lastMessageTime = 300L),
                    chat("marked", markedUnread = true, lastMessageTime = 200L),
                ),
                selectedFolderId = "system:unread",
            ) to listOf("marked", "unread"),
            // Groups folder shows only groups.
            case(
                chats = listOf(
                    chat("direct", lastMessageTime = 300L),
                    chat("group", isGroup = true, lastMessageTime = 200L),
                ),
                selectedFolderId = "system:groups",
            ) to listOf("group"),
            // Direct folder excludes groups and secrets.
            case(
                chats = listOf(
                    chat("direct", lastMessageTime = 300L),
                    chat("group", isGroup = true, lastMessageTime = 200L),
                    chat("secret", chatType = "SECRET", lastMessageTime = 100L),
                ),
                selectedFolderId = "system:direct",
            ) to listOf("direct"),
            // Secret folder shows secrets plus secret ids.
            case(
                chats = listOf(
                    chat("direct", lastMessageTime = 400L),
                    chat("secret", chatType = "SECRET", lastMessageTime = 300L),
                    chat("secret-id", lastMessageTime = 200L),
                ),
                selectedFolderId = "system:secret",
                secretChatIds = setOf("secret-id"),
            ) to listOf("secret", "secret-id"),
            // Locked folder shows locked non-secret chats.
            case(
                chats = listOf(
                    chat("locked", lastMessageTime = 400L),
                    chat("locked-secret", chatType = "SECRET", lastMessageTime = 300L),
                    chat("plain", lastMessageTime = 200L),
                ),
                selectedFolderId = "system:locked",
                lockedChatIds = setOf("locked", "locked-secret"),
            ) to listOf("locked"),
            // Custom folder shows only its members, excluding secrets.
            case(
                chats = listOf(
                    chat("a", lastMessageTime = 300L),
                    chat("b", lastMessageTime = 200L),
                    chat("c", lastMessageTime = 100L),
                    chat("secret-in-folder", chatType = "SECRET", lastMessageTime = 400L),
                ),
                selectedFolderId = "f1",
                folders = listOf(ChatFolder(id = "f1", name = "工作", chatIds = listOf("a", "c", "secret-in-folder"))),
            ) to listOf("a", "c"),
            // Unknown folder falls back to non-secret chats.
            case(
                chats = listOf(
                    chat("direct", lastMessageTime = 200L),
                    chat("secret", chatType = "SECRET", lastMessageTime = 100L),
                ),
                selectedFolderId = "no-such-folder",
            ) to listOf("direct"),
            // All-chats view excludes secrets.
            case(
                chats = listOf(
                    chat("direct", lastMessageTime = 200L),
                    chat("secret", chatType = "SECRET", lastMessageTime = 100L),
                ),
            ) to listOf("direct"),
            // Search matches message-matched ids only.
            case(
                chats = listOf(chat("hit", lastMessage = "unrelated", lastMessageTime = 100L)),
                searchQuery = "关键词",
                messageMatchedChatIds = setOf("hit"),
            ) to listOf("hit"),
            case(
                chats = listOf(chat("hit", lastMessage = "unrelated", lastMessageTime = 100L)),
                searchQuery = "关键词",
            ) to emptyList(),
            // Search matches participant name, case-insensitively.
            case(
                chats = listOf(
                    chat(
                        "dm", lastMessage = "unrelated", lastMessageTime = 100L,
                        participants = listOf(User(id = "u1", name = "张三")),
                    ),
                ),
                searchQuery = "张三",
            ) to listOf("dm"),
            case(
                chats = listOf(
                    chat(
                        "dm", lastMessage = "unrelated", lastMessageTime = 100L,
                        participants = listOf(User(id = "u1", name = "Alice")),
                    ),
                ),
                searchQuery = "ALICE",
            ) to listOf("dm"),
            // Blank search returns the folder filter untouched.
            case(
                chats = listOf(
                    chat("direct", lastMessageTime = 200L),
                    chat("secret", chatType = "SECRET", lastMessageTime = 100L),
                ),
                searchQuery = " ",
            ) to listOf("direct"),
            // Group name is the search title when present.
            case(
                chats = listOf(
                    chat("g", lastMessage = "unrelated", lastMessageTime = 100L, groupName = "小组"),
                ),
                searchQuery = "小组",
            ) to listOf("g"),
        )
        for ((c, expected) in cases) {
            assertEquals(
                expected,
                filterVisibleChatsOracle156(
                    chats = c.chats,
                    showArchived = c.showArchived,
                    selectedFolderId = c.selectedFolderId,
                    folders = c.folders,
                    secretChatIds = c.secretChatIds,
                    lockedChatIds = c.lockedChatIds,
                    searchQuery = c.searchQuery,
                    messageMatchedChatIds = c.messageMatchedChatIds,
                    drafts = c.drafts,
                    unreadPriorityEnabled = c.unreadPriorityEnabled,
                ),
            )
            check(c)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(156003)
        repeat(2000) {
            check(randomVisibleCase156(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        val chats = listOf(
            chat("c0", lastMessage = "hello", lastMessageTime = 300L),
            chat("c1", lastMessage = "old", lastMessageTime = 100L, unreadCount = 3),
            chat("c2", lastMessage = "new", lastMessageTime = 200L, pinnedAt = 100L),
            chat("c3", chatType = "SECRET", lastMessageTime = 400L),
        )
        val folders = listOf(ChatFolder(id = "f1", name = "工作", chatIds = listOf("c0", "c2")))
        for (folderId in VISIBLE_FOLDER_IDS156) {
            for (showArchived in listOf(false, true)) {
                for (query in listOf("", "a", "ALICE", "发布计划", " ")) {
                    for (unreadPriority in listOf(false, true)) {
                        check(
                            case(
                                chats = chats,
                                showArchived = showArchived,
                                selectedFolderId = folderId,
                                folders = folders,
                                secretChatIds = setOf("c3"),
                                lockedChatIds = setOf("c1"),
                                searchQuery = query,
                                messageMatchedChatIds = setOf("c0"),
                                drafts = mapOf("c1" to ChatDraft("c1", "明天讨论发布计划", 500L)),
                                unreadPriorityEnabled = unreadPriority,
                            ),
                        )
                    }
                }
            }
        }
    }
}
