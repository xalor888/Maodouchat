package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// copyableName restated by hand: a channel or group shows its
// non-blank group name, a DM shows its first participant's display
// name (nickname, else name, else id), and anything missing is "".
internal fun copyableNameOracle174(chat: Chat): String =
    when {
        chat.isChannel -> chat.groupName?.takeIf(String::isNotBlank) ?: ""
        chat.isGroup -> chat.groupName?.takeIf(String::isNotBlank) ?: ""
        else -> chat.participants.firstOrNull()?.displayName174() ?: ""
    }

// displayName restated by hand: nickname, else name, else id.
// A blank nickname or name falls through, but a blank id is kept.
internal fun User.displayName174(): String =
    nickname?.takeIf(String::isNotBlank)
        ?: name.takeIf(String::isNotBlank)
        ?: id

// Production copyableName returns the copyable chat name.
internal fun copyableNameProduction174(chat: Chat): String = chat.copyableName()

// ---------- generators ----------

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJsonName174(s: String): String {
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

internal fun escJsonOrNullName174(s: String?): String =
    if (s == null) "null" else escJsonName174(s)

internal data class NameUser174(val id: String, val name: String, val nickname: String?)

internal data class NameCase174(
    val id: String,
    val isGroup: Boolean,
    val chatType: String,
    val groupName: String?,
    val participants: List<NameUser174>,
)

internal val NAME_ID_POOL174: List<String> = listOf(
    "", "c1", "chat-9", "group-42", "x".repeat(20)
)

internal val NAME_CHATTYPE_POOL174: List<String> = listOf(
    "DIRECT", "GROUP", "CHANNEL", "SECRET", "group", "channel", "", "OTHER"
)

internal val NAME_GROUP_NAME_POOL174: List<String?> = listOf(
    null, "", "  ", "Family", " 工作群 ", "g".repeat(20), "\u00a0", "\u2007"
)

internal val NAME_PARTICIPANT_NAME_POOL174: List<String> = listOf(
    "", "  ", "Alice", "Bob", "\u00a0", "  Bob  ", "🎉"
)

internal val NAME_NICKNAME_POOL174: List<String?> = listOf(
    null, "", "  ", "Ally", "\u00a0", "  Ally  "
)

internal val NAME_PARTICIPANT_SHAPES174: List<List<NameUser174>> = listOf(
    emptyList(),
    listOf(NameUser174("u1", "Alice", null)),
    listOf(NameUser174("u1", "Alice", "Ally")),
    listOf(NameUser174("u1", "", "")),
    listOf(NameUser174("u1", "  ", "  ")),
    listOf(NameUser174("", "", null)),
    listOf(NameUser174("u1", "Alice", null), NameUser174("u2", "Bob", "Bobby")),
    listOf(NameUser174("u1", "", "Ally"), NameUser174("u2", "Bob", null)),
)

internal fun buildNameChat174(c: NameCase174): Chat = Chat(
    id = c.id,
    participants = c.participants.map { User(id = it.id, name = it.name, nickname = it.nickname) },
    isGroup = c.isGroup,
    chatType = c.chatType,
    groupName = c.groupName,
)

internal fun randomNameCase174(rnd: Random): NameCase174 = NameCase174(
    id = NAME_ID_POOL174[rnd.nextInt(NAME_ID_POOL174.size)],
    isGroup = rnd.nextBoolean(),
    chatType = NAME_CHATTYPE_POOL174[rnd.nextInt(NAME_CHATTYPE_POOL174.size)],
    groupName = NAME_GROUP_NAME_POOL174[rnd.nextInt(NAME_GROUP_NAME_POOL174.size)],
    participants = NAME_PARTICIPANT_SHAPES174[rnd.nextInt(NAME_PARTICIPANT_SHAPES174.size)],
)

internal fun nameWireJson174(c: NameCase174): String {
    val parts = c.participants.joinToString(",") {
        "{\"id\":${escJsonName174(it.id)}," +
            "\"name\":${escJsonName174(it.name)}," +
            "\"nickname\":${escJsonOrNullName174(it.nickname)}}"
    }
    return "{\"id\":${escJsonName174(c.id)}," +
        "\"isGroup\":${c.isGroup}," +
        "\"chatType\":${escJsonName174(c.chatType)}," +
        "\"groupName\":${escJsonOrNullName174(c.groupName)}," +
        "\"participants\":[$parts]}"
}

class ChatCopyableNameFuzzTest {

    private fun checkName174(c: NameCase174) {
        val chat = buildNameChat174(c)
        assertEquals(copyableNameOracle174(chat), copyableNameProduction174(chat), "name($c)")
    }

    @Test
    fun pinnedBoundaries() {
        // Channel and group take the group name (blank and null fall
        // to ""); a DM takes the first participant's display name.
        checkName174(NameCase174("c1", false, "CHANNEL", "公告频道", emptyList()))
        checkName174(NameCase174("c1", false, "CHANNEL", "  ", emptyList()))
        checkName174(NameCase174("c1", false, "CHANNEL", null, emptyList()))
        checkName174(NameCase174("c1", true, "CHANNEL", "公告频道", emptyList()))
        checkName174(NameCase174("g1", true, "GROUP", "小组", emptyList()))
        checkName174(NameCase174("g1", true, "GROUP", "", emptyList()))
        // chatType is case-sensitive: lowercase "channel" is not a
        // channel, so a non-group chat falls through to the DM branch.
        checkName174(
            NameCase174(
                "c1", false, "channel", "公告频道",
                listOf(NameUser174("u1", "Alice", "Ally"))
            )
        )
        // isGroup wins over chatType: a group-typed DIRECT chat still
        // takes the group name, not the first participant.
        checkName174(
            NameCase174(
                "g1", true, "DIRECT", "小组",
                listOf(NameUser174("u1", "Alice", "Ally"))
            )
        )
        // DM display-name chain: nickname, else name, else id.
        checkName174(
            NameCase174(
                "d1", false, "DIRECT", null,
                listOf(NameUser174("u1", "Alice", "Ally"))
            )
        )
        checkName174(
            NameCase174(
                "d1", false, "DIRECT", null,
                listOf(NameUser174("u1", "Alice", "  "))
            )
        )
        checkName174(
            NameCase174(
                "d1", false, "DIRECT", null,
                listOf(NameUser174("u1", "  ", null))
            )
        )
        checkName174(NameCase174("d1", false, "DIRECT", null, emptyList()))
        checkName174(
            NameCase174(
                "d1", false, "DIRECT", null,
                listOf(NameUser174("", "", null))
            )
        )
        // Only the first participant counts, even when it is blank.
        checkName174(
            NameCase174(
                "d1", false, "DIRECT", null,
                listOf(
                    NameUser174("u1", "", null),
                    NameUser174("u2", "Bob", "Bobby")
                )
            )
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(174002)
        repeat(4000) { checkName174(randomNameCase174(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // chatType x isGroup x groupName x participant shape.
        for (chatType in NAME_CHATTYPE_POOL174) {
            for (isGroup in listOf(false, true)) {
                for (groupName in NAME_GROUP_NAME_POOL174) {
                    for (participants in NAME_PARTICIPANT_SHAPES174) {
                        checkName174(NameCase174("c1", isGroup, chatType, groupName, participants))
                    }
                }
            }
        }
    }
}
