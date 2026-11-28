package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// A user's display name restated by hand: nickname when non-blank,
// else the name when non-blank, else the id itself.
internal fun userDisplayNameOracle172(user: User): String =
    user.nickname?.takeIf { it.isNotBlank() }
        ?: user.name.takeIf { it.isNotBlank() }
        ?: user.id

// chatTitle restated by hand: groups show the group name when
// non-blank, else the first 12 chars of the id; direct chats show
// the first non-owner participant's display name (blank display
// name falls back to the raw name), else the group name when
// non-blank, else the first 12 chars of the id.
internal fun chatTitleOracle172(chat: Chat, ownerUserId: String): String {
    if (chat.isGroup) return chat.groupName?.takeIf { it.isNotBlank() } ?: chat.id.take(12)
    return chat.participants
        .firstOrNull { it.id != ownerUserId }
        ?.let { userDisplayNameOracle172(it).ifBlank { it.name } }
        ?: chat.groupName?.takeIf { it.isNotBlank() }
        ?: chat.id.take(12)
}

// Production chatTitle returns the conversation title.
internal fun chatTitleProduction172(chat: Chat, ownerUserId: String): String =
    chatTitle(chat, ownerUserId)

// ---------- generators ----------

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJsonTitle172(s: String): String {
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
                if (ch.code < 0x20) {
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

internal fun escJsonOrNullTitle172(s: String?): String =
    if (s == null) "null" else escJsonTitle172(s)

internal fun user172(id: String, name: String, nickname: String?): User =
    User(id = id, name = name, nickname = nickname)

internal fun chat172(
    id: String,
    isGroup: Boolean,
    groupName: String?,
    participants: List<User>
): Chat = Chat(id = id, isGroup = isGroup, groupName = groupName, participants = participants)

internal data class TitleCase172(
    val id: String,
    val isGroup: Boolean,
    val groupName: String?,
    val participants: List<User>,
    val ownerUserId: String
)

internal val TITLE_ID_POOL172: List<String> = listOf(
    "", "c1", "chat-123456789012345", "  ", "x".repeat(12), "x".repeat(13)
)

internal val TITLE_GROUP_NAME_POOL172: List<String?> = listOf(
    null, "", "  ", "Family", " 工作群 ", "g".repeat(20)
)

internal val TITLE_NAME_POOL172: List<String> = listOf(
    "", "Alice", "  ", "Bob", "陈", "a b"
)

internal val TITLE_NICK_POOL172: List<String?> = listOf(
    null, "", "  ", "Ali", "小明"
)

internal val TITLE_OWNER_POOL172: List<String> = listOf(
    "", "u1", "u2", "owner"
)

internal fun randomUser172(rnd: Random): User =
    user172(
        id = TITLE_OWNER_POOL172[rnd.nextInt(TITLE_OWNER_POOL172.size)],
        name = TITLE_NAME_POOL172[rnd.nextInt(TITLE_NAME_POOL172.size)],
        nickname = TITLE_NICK_POOL172[rnd.nextInt(TITLE_NICK_POOL172.size)]
    )

internal fun randomTitleCase172(rnd: Random): TitleCase172 {
    val n = rnd.nextInt(4)
    return TitleCase172(
        id = TITLE_ID_POOL172[rnd.nextInt(TITLE_ID_POOL172.size)],
        isGroup = rnd.nextBoolean(),
        groupName = TITLE_GROUP_NAME_POOL172[rnd.nextInt(TITLE_GROUP_NAME_POOL172.size)],
        participants = List(n) { randomUser172(rnd) },
        ownerUserId = TITLE_OWNER_POOL172[rnd.nextInt(TITLE_OWNER_POOL172.size)]
    )
}

internal fun titleWireJson172(c: TitleCase172): String {
    val parts = c.participants.joinToString(",") {
        "{\"id\":${escJsonTitle172(it.id)}," +
            "\"name\":${escJsonTitle172(it.name)}," +
            "\"nickname\":${escJsonOrNullTitle172(it.nickname)}}"
    }
    return "{\"id\":${escJsonTitle172(c.id)}," +
        "\"isGroup\":${c.isGroup}," +
        "\"groupName\":${escJsonOrNullTitle172(c.groupName)}," +
        "\"participants\":[$parts]," +
        "\"ownerUserId\":${escJsonTitle172(c.ownerUserId)}}"
}

class ChatTitleFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val me = user172("u1", "Me", null)
        val bob = user172("u2", "Bob", null)
        val ali = user172("u2", "Bob", "Ali")
        val blankBoth = user172("u2", "", null)
        val blankName = user172("u2", "  ", "  ")
        val cases = listOf(
            Triple(chat172("chat-123456789012345", true, "Family", listOf(me, bob)), "u1", "Family"),
            Triple(chat172("chat-123456789012345", true, "", listOf(me, bob)), "u1", "chat-1234567"),
            Triple(chat172("chat-123456789012345", true, "  ", listOf(me, bob)), "u1", "chat-1234567"),
            Triple(chat172("chat-123456789012345", true, null, listOf(me, bob)), "u1", "chat-1234567"),
            Triple(chat172("short", true, null, emptyList()), "u1", "short"),
            Triple(chat172("123456789012", true, null, emptyList()), "u1", "123456789012"),
            Triple(chat172("1234567890123", true, null, emptyList()), "u1", "123456789012"),
            Triple(chat172("c9", false, null, listOf(me, bob)), "u1", "Bob"),
            Triple(chat172("c9", false, null, listOf(me, ali)), "u1", "Ali"),
            // displayName falls back to the id when nickname and name
            // are both blank, so the title is the id, not "".
            Triple(chat172("c9", false, null, listOf(me, blankBoth)), "u1", "u2"),
            Triple(chat172("c9", false, null, listOf(me, blankName)), "u1", "u2"),
            // displayName is only blank when the id itself is blank;
            // then the ifBlank branch returns the (blank) name.
            Triple(chat172("c9", false, null, listOf(me, user172("", "", null))), "u1", ""),
            Triple(chat172("c9", false, null, listOf(me, user172("", "N", null))), "u1", "N"),
            // No non-owner participant: group name wins over the id,
            // blank group name falls through to the id.
            Triple(chat172("chat-123456789012345", false, "Saved", listOf(me)), "u1", "Saved"),
            Triple(chat172("chat-123456789012345", false, "  ", listOf(me)), "u1", "chat-1234567"),
            Triple(chat172("chat-123456789012345", false, null, listOf(me)), "u1", "chat-1234567"),
            Triple(chat172("chat-123456789012345", false, null, emptyList()), "u1", "chat-1234567"),
            // The first non-owner participant wins even when a later
            // one has a nickname; the owner id itself is skipped.
            Triple(chat172("c9", false, null, listOf(bob, ali)), "u1", "Bob"),
            Triple(chat172("c9", false, null, listOf(bob, me)), "u2", "Me")
        )
        for ((chat, owner, expected) in cases) {
            assertEquals(expected, chatTitleProduction172(chat, owner), "title($chat, $owner)")
            assertEquals(expected, chatTitleOracle172(chat, owner), "oracle($chat, $owner)")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(172007)
        repeat(4000) {
            val c = randomTitleCase172(rnd)
            val chat = chat172(c.id, c.isGroup, c.groupName, c.participants)
            assertEquals(
                chatTitleOracle172(chat, c.ownerUserId),
                chatTitleProduction172(chat, c.ownerUserId),
                "title(random $c)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every group-name shape on group and direct chats, every
        // participant shape, every owner.
        val shapes = listOf(
            emptyList<User>(),
            listOf(user172("u1", "Me", null)),
            listOf(user172("u2", "Bob", null)),
            listOf(user172("u1", "Me", null), user172("u2", "Bob", "Ali")),
            listOf(user172("u2", "Bob", null), user172("u3", "", null)),
            listOf(user172("u2", "  ", "  "))
        )
        for (isGroup in listOf(false, true)) {
            for (groupName in TITLE_GROUP_NAME_POOL172) {
                for (participants in shapes) {
                    for (owner in TITLE_OWNER_POOL172) {
                        val chat = chat172("chat-123456789012345", isGroup, groupName, participants)
                        assertEquals(
                            chatTitleOracle172(chat, owner),
                            chatTitleProduction172(chat, owner),
                            "sweep(group=$isGroup, name=$groupName, parts=$participants, owner=$owner)"
                        )
                    }
                }
            }
        }
        // Every id in the pool as group and direct chat id.
        for (id in TITLE_ID_POOL172) {
            for (isGroup in listOf(false, true)) {
                val chat = chat172(id, isGroup, null, listOf(user172("u2", "Bob", null)))
                assertEquals(
                    chatTitleOracle172(chat, "u1"),
                    chatTitleProduction172(chat, "u1"),
                    "sweep-id($id, group=$isGroup)"
                )
            }
        }
    }
}
