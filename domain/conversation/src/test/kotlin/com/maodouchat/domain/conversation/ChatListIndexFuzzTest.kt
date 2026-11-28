package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.User
import com.maodouchat.domain.contacts.findLetterIndex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Contacts alphabet index + unread counts restated by hand. Ground
// truth, read from ContactsIndexPolicy.kt / ContactsLetterIndex.kt /
// UnreadPriorityPolicy.kt / ChatListVisibleChats.kt:
//   - leadingFixedItemCount: trailing action rows (default 3, floored
//     at 0), then +count + 1 per non-empty request bucket.
//   - letterListIndex: -1 when the target is absent; otherwise the
//     leading offset plus one header + N contacts per preceding letter.
//   - findLetterIndex: letter order is the grouped map's key order.
//   - countUnreadChats: walks max(unreadCounts, markedUnreadFlags)
//     lengths, missing entries defaulting to 0 / false.
//   - unreadChatCount: archived and secret chats never count.
//   - showUnreadPriorityHint: needs the feature on, a non-zero unread
//     chat count, and no active search.
internal fun leadingFixedItemCountOracle185(
    incomingCount: Int,
    outgoingCount: Int,
    groupInviteCount: Int,
    trailingActionRows: Int = 3,
): Int {
    var n = trailingActionRows.coerceAtLeast(0)
    if (incomingCount > 0) n += incomingCount + 1
    if (outgoingCount > 0) n += outgoingCount + 1
    if (groupInviteCount > 0) n += groupInviteCount + 1
    return n
}

internal fun letterListIndexOracle185(
    orderedLetters: List<String>,
    sizes: Map<String, Int>,
    targetLetter: String,
    leadingFixedItems: Int = 1,
): Int {
    if (targetLetter !in orderedLetters) return -1
    var index = leadingFixedItems
    for (letter in orderedLetters) {
        if (letter == targetLetter) return index
        index += 1 + (sizes[letter] ?: 0)
    }
    return -1
}

internal fun findLetterIndexOracle185(
    grouped: Map<String, List<User>>,
    incomingCount: Int,
    outgoingCount: Int,
    groupInviteCount: Int,
    targetLetter: String,
): Int {
    val leadingFixedItems = leadingFixedItemCountOracle185(
        incomingCount = incomingCount,
        outgoingCount = outgoingCount,
        groupInviteCount = groupInviteCount,
    )
    val ordered = grouped.keys.toList()
    val sizes = grouped.mapValues { it.value.size }
    return letterListIndexOracle185(
        orderedLetters = ordered,
        sizes = sizes,
        targetLetter = targetLetter,
        leadingFixedItems = leadingFixedItems,
    )
}

internal fun countUnreadChatsOracle185(
    unreadCounts: List<Int>,
    markedUnreadFlags: List<Boolean>,
): Int {
    val n = maxOf(unreadCounts.size, markedUnreadFlags.size)
    var count = 0
    for (i in 0 until n) {
        val u = unreadCounts.getOrElse(i) { 0 }
        val m = markedUnreadFlags.getOrElse(i) { false }
        if (u > 0 || m) count++
    }
    return count
}

internal fun unreadChatCountOracle185(chats: List<Chat>): Int =
    countUnreadChatsOracle185(
        unreadCounts = chats.filter { !it.archived && !it.isSecret }.map { it.unreadCount },
        markedUnreadFlags = chats.filter { !it.archived && !it.isSecret }.map { it.markedUnread },
    )

internal fun showUnreadPriorityHintOracle185(
    unreadPriorityEnabled: Boolean,
    chats: List<Chat>,
    searchQuery: String,
): Boolean =
    unreadPriorityEnabled &&
        unreadChatCountOracle185(chats) > 0 &&
        searchQuery.isBlank()

internal fun escJsonIndex185(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun userWireJson185(u: User): String =
    "{\"id\":${escJsonIndex185(u.id)},\"name\":${escJsonIndex185(u.name)}," +
        "\"avatar\":${u.avatar?.let { escJsonIndex185(it) } ?: "null"}," +
        "\"email\":${escJsonIndex185(u.email)},\"isOnline\":${u.isOnline}," +
        "\"status\":${escJsonIndex185(u.status)}," +
        "\"nickname\":${u.nickname?.let { escJsonIndex185(it) } ?: "null"}," +
        "\"lastSeen\":${u.lastSeen}}"

internal fun chatWireJson185(c: Chat): String {
    val participants = c.participants.joinToString(",") { userWireJson185(it) }
    return "{\"id\":${escJsonIndex185(c.id)},\"participants\":[$participants]," +
        "\"lastMessage\":${escJsonIndex185(c.lastMessage)}," +
        "\"lastMessageType\":${escJsonIndex185(c.lastMessageType.name)}," +
        "\"lastMessageTime\":${c.lastMessageTime},\"unreadCount\":${c.unreadCount}," +
        "\"isGroup\":${c.isGroup},\"chatType\":${escJsonIndex185(c.chatType)}," +
        "\"groupName\":${c.groupName?.let { escJsonIndex185(it) } ?: "null"}," +
        "\"groupAnnouncement\":${c.groupAnnouncement?.let { escJsonIndex185(it) } ?: "null"}," +
        "\"groupAvatar\":${c.groupAvatar?.let { escJsonIndex185(it) } ?: "null"}," +
        "\"memberRevision\":${c.memberRevision},\"pinnedAt\":${c.pinnedAt}," +
        "\"notificationsMuted\":${c.notificationsMuted},\"archived\":${c.archived}," +
        "\"markedUnread\":${c.markedUnread},\"settingsUpdatedAt\":${c.settingsUpdatedAt}," +
        "\"disappearingMessageSeconds\":${c.disappearingMessageSeconds}}"
}

// ---------- production ----------

internal fun findLetterIndexProduction185(
    grouped: Map<String, List<User>>,
    incomingCount: Int,
    outgoingCount: Int,
    groupInviteCount: Int,
    targetLetter: String,
): Int = findLetterIndex(grouped, incomingCount, outgoingCount, groupInviteCount, targetLetter)

internal fun unreadChatCountProduction185(chats: List<Chat>): Int =
    ChatListVisibleChats.unreadChatCount(chats)

internal fun showUnreadPriorityHintProduction185(
    unreadPriorityEnabled: Boolean,
    chats: List<Chat>,
    searchQuery: String,
): Boolean = ChatListVisibleChats.showUnreadPriorityHint(unreadPriorityEnabled, chats, searchQuery)

// ---------- generators ----------

internal val INDEX_LETTERS185: List<String> = listOf("A", "B", "C", "D", "Z", "#", "中", "Ä", "a")

internal val INDEX_COUNTS185: List<Int> = listOf(
    Int.MIN_VALUE, -2, -1, 0, 1, 2, 3, 5, 100, Int.MAX_VALUE - 1, Int.MAX_VALUE,
)

internal val INDEX_UNREAD_COUNTS185: List<Int> = listOf(-1, 0, 1, 2, 5, 999, 100000)

internal val INDEX_QUERIES185: List<String> = listOf("", " ", "x", "  x  ", "alice", "群")

internal fun randomUser185(rnd: Random): User = User(
    id = listOf("u1", "u2", "alice", "bob").random(rnd),
    name = listOf("Alice", "Bob", "", "  ", "张三", "Änne").random(rnd),
    avatar = if (rnd.nextBoolean()) null else "https://x/y.png",
    email = listOf("", "a@b.c").random(rnd),
    isOnline = rnd.nextBoolean(),
    status = listOf("", "busy").random(rnd),
    nickname = if (rnd.nextBoolean()) null else "nick",
    lastSeen = rnd.nextLong(-1000L, 100000L),
)

internal fun randomChat185(rnd: Random): Chat = Chat(
    id = "c${rnd.nextInt(0, 8)}",
    participants = List(rnd.nextInt(0, 3)) { randomUser185(rnd) },
    lastMessage = listOf("", "hi", "hello world").random(rnd),
    lastMessageType = MessageType.entries.random(rnd),
    lastMessageTime = rnd.nextLong(-1000L, 100000L),
    unreadCount = INDEX_UNREAD_COUNTS185.random(rnd),
    isGroup = rnd.nextBoolean(),
    chatType = listOf("DIRECT", "GROUP", "CHANNEL", "SECRET").random(rnd),
    groupName = if (rnd.nextBoolean()) null else "g",
    pinnedAt = rnd.nextLong(-1000L, 100000L),
    notificationsMuted = rnd.nextBoolean(),
    archived = rnd.nextBoolean(),
    markedUnread = rnd.nextBoolean(),
)

internal fun randomGrouped185(rnd: Random): Map<String, List<User>> {
    val letters = INDEX_LETTERS185.shuffled(rnd).take(rnd.nextInt(0, 6))
    val out = linkedMapOf<String, List<User>>()
    for (letter in letters) {
        out[letter] = List(rnd.nextInt(0, 4)) { randomUser185(rnd) }
    }
    return out
}

internal data class LetterCase185(
    val grouped: Map<String, List<User>>,
    val incomingCount: Int,
    val outgoingCount: Int,
    val groupInviteCount: Int,
    val targetLetter: String,
)

internal data class UnreadCase185(val chats: List<Chat>)

internal data class HintCase185(val enabled: Boolean, val chats: List<Chat>, val query: String)

internal fun randomLetterCase185(rnd: Random): LetterCase185 = LetterCase185(
    grouped = randomGrouped185(rnd),
    incomingCount = INDEX_COUNTS185.random(rnd),
    outgoingCount = INDEX_COUNTS185.random(rnd),
    groupInviteCount = INDEX_COUNTS185.random(rnd),
    targetLetter = (INDEX_LETTERS185 + listOf("", "Q", "中", "ä")).random(rnd),
)

internal fun randomUnreadCase185(rnd: Random): UnreadCase185 =
    UnreadCase185(List(rnd.nextInt(0, 6)) { randomChat185(rnd) })

internal fun randomHintCase185(rnd: Random): HintCase185 = HintCase185(
    enabled = rnd.nextBoolean(),
    chats = List(rnd.nextInt(0, 6)) { randomChat185(rnd) },
    query = INDEX_QUERIES185.random(rnd),
)

internal fun groupedWireJson185(grouped: Map<String, List<User>>): String =
    "[" + grouped.entries.joinToString(",") { (letter, users) ->
        "[${escJsonIndex185(letter)},[" + users.joinToString(",") { userWireJson185(it) } + "]]"
    } + "]"

internal fun letterCaseWireJson185(c: LetterCase185): String =
    "{\"grouped\":${groupedWireJson185(c.grouped)}," +
        "\"incoming\":${c.incomingCount},\"outgoing\":${c.outgoingCount}," +
        "\"groupInvite\":${c.groupInviteCount},\"target\":${escJsonIndex185(c.targetLetter)}}"

internal fun unreadCaseWireJson185(c: UnreadCase185): String =
    "{\"chats\":[" + c.chats.joinToString(",") { chatWireJson185(it) } + "]}"

internal fun hintCaseWireJson185(c: HintCase185): String =
    "{\"enabled\":${c.enabled},\"chats\":[" +
        c.chats.joinToString(",") { chatWireJson185(it) } + "]," +
        "\"query\":${escJsonIndex185(c.query)}}"

internal fun chatIndex185(
    archived: Boolean = false,
    chatType: String = "DIRECT",
    unreadCount: Int = 0,
    markedUnread: Boolean = false,
): Chat = Chat(
    id = "c1", unreadCount = unreadCount, chatType = chatType,
    archived = archived, markedUnread = markedUnread,
)

class ChatListIndexFuzzTest {

    private fun checkLetter185(c: LetterCase185) {
        assertEquals(
            findLetterIndexOracle185(c.grouped, c.incomingCount, c.outgoingCount, c.groupInviteCount, c.targetLetter),
            findLetterIndexProduction185(c.grouped, c.incomingCount, c.outgoingCount, c.groupInviteCount, c.targetLetter),
            "letter($c)",
        )
    }

    private fun checkUnread185(c: UnreadCase185) {
        assertEquals(
            unreadChatCountOracle185(c.chats),
            unreadChatCountProduction185(c.chats),
            "unread($c)",
        )
    }

    private fun checkHint185(c: HintCase185) {
        assertEquals(
            showUnreadPriorityHintOracle185(c.enabled, c.chats, c.query),
            showUnreadPriorityHintProduction185(c.enabled, c.chats, c.query),
            "hint($c)",
        )
    }

    @Test
    fun pinnedChatListIndex() {
        // Letter index: leading rows, header + N per letter, absent target -1.
        val grouped = linkedMapOf(
            "A" to listOf(User(id = "u1", name = "Alice"), User(id = "u2", name = "Amy")),
            "B" to listOf(User(id = "u3", name = "Bob")),
            "#" to emptyList(),
        )
        checkLetter185(LetterCase185(grouped, 0, 0, 0, "A"))
        checkLetter185(LetterCase185(grouped, 0, 0, 0, "B"))
        checkLetter185(LetterCase185(grouped, 0, 0, 0, "#"))
        checkLetter185(LetterCase185(grouped, 0, 0, 0, "Z"))
        checkLetter185(LetterCase185(grouped, 2, 0, 0, "A"))
        checkLetter185(LetterCase185(grouped, 0, 3, 1, "B"))
        checkLetter185(LetterCase185(emptyMap(), 0, 0, 0, "A"))
        checkLetter185(LetterCase185(emptyMap(), 5, 5, 5, "A"))
        // Count edges, including Int wrap on the leading rows.
        for (n in INDEX_COUNTS185) {
            checkLetter185(LetterCase185(grouped, n, 0, 0, "A"))
            checkLetter185(LetterCase185(grouped, 0, n, 0, "B"))
            checkLetter185(LetterCase185(grouped, 0, 0, n, "B"))
        }
        // Unread count: archived / secret excluded, marked-unread counts.
        checkUnread185(UnreadCase185(emptyList()))
        checkUnread185(UnreadCase185(listOf(chatIndex185(unreadCount = 3))))
        checkUnread185(UnreadCase185(listOf(chatIndex185(unreadCount = 0, markedUnread = true))))
        checkUnread185(UnreadCase185(listOf(chatIndex185(unreadCount = 3, archived = true))))
        checkUnread185(UnreadCase185(listOf(chatIndex185(unreadCount = 3, chatType = "SECRET"))))
        checkUnread185(UnreadCase185(listOf(
            chatIndex185(unreadCount = 3),
            chatIndex185(unreadCount = 0),
            chatIndex185(unreadCount = -1),
            chatIndex185(unreadCount = 5, markedUnread = true),
        )))
        // Hint: needs enabled + unread + no search.
        for (enabled in listOf(true, false)) {
            for (query in INDEX_QUERIES185) {
                checkHint185(HintCase185(enabled, listOf(chatIndex185(unreadCount = 2)), query))
                checkHint185(HintCase185(enabled, emptyList(), query))
                checkHint185(HintCase185(enabled, listOf(chatIndex185(unreadCount = 0, markedUnread = true)), query))
            }
        }
    }

    @Test
    fun randomChatListIndexMatchProduction() {
        val rnd = Random(185002)
        repeat(1500) { checkLetter185(randomLetterCase185(rnd)) }
        repeat(1500) { checkUnread185(randomUnreadCase185(rnd)) }
        repeat(1000) { checkHint185(randomHintCase185(rnd)) }
    }

    @Test
    fun sweepChatListIndexBoundaries() {
        // Every letter x every bucket count edge.
        val grouped = linkedMapOf(
            "A" to List(2) { User(id = "u$it", name = "A$it") },
            "B" to List(0) { User(id = "x", name = "x") },
            "C" to List(5) { User(id = "u$it", name = "C$it") },
        )
        for (target in INDEX_LETTERS185 + listOf("", "Q")) {
            for (inc in listOf(0, 1, Int.MAX_VALUE)) {
                checkLetter185(LetterCase185(grouped, inc, 0, 0, target))
            }
        }
        // Single-letter groups, empty groups, reversed key order.
        checkLetter185(LetterCase185(linkedMapOf("Z" to listOf(User(id = "u1", name = "Zed"))), 0, 0, 0, "Z"))
        checkLetter185(LetterCase185(linkedMapOf("B" to emptyList(), "A" to emptyList()), 1, 1, 1, "A"))
        checkLetter185(LetterCase185(linkedMapOf("B" to emptyList(), "A" to emptyList()), 1, 1, 1, "B"))
        // Unread: every chatType x archived x marked.
        for (type in listOf("DIRECT", "GROUP", "CHANNEL", "SECRET")) {
            for (archived in listOf(false, true)) {
                for (marked in listOf(false, true)) {
                    checkUnread185(UnreadCase185(listOf(
                        chatIndex185(archived = archived, chatType = type, unreadCount = 4, markedUnread = marked),
                    )))
                }
            }
        }
    }
}
