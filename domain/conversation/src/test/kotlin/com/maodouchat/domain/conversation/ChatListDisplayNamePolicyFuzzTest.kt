// Fuzz / random-oracle tests for ChatListDisplayNamePolicy
// (checklist line 931, 155th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: channels take
// the group name (channel default when blank), groups take the group
// name (group default when blank), direct chats take the first
// participant's display name (nickname, else name, else id, each
// skipped when blank; unknown label when none is usable); a secret
// flag or a SECRET chat type always shows "密聊" (the mosaic helper
// returns that constant for every input, so the oracle inlines it).
package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun resolveOracle155(
    chat: Chat,
    isSecret: Boolean,
    channelDefault: String,
    groupDefault: String,
    unknownLabel: String,
): String {
    val first = chat.participants.firstOrNull()
    // Mirrors User.displayName (nickname, else name, else id) plus the
    // takeIf(isNotBlank) the production else-branch applies to it.
    val firstName = first?.nickname?.takeIf(String::isNotBlank)
        ?: first?.name?.takeIf(String::isNotBlank)
        ?: first?.id?.takeIf(String::isNotBlank)
    val raw = when {
        chat.isChannel -> chat.groupName?.takeIf(String::isNotBlank) ?: channelDefault
        chat.isGroup -> chat.groupName?.takeIf(String::isNotBlank) ?: groupDefault
        else -> firstName ?: unknownLabel
    }
    return if (isSecret || chat.isSecret) "密聊" else raw
}

// ---------- generators ----------

internal data class DisplayNameCase155(
    val chat: Chat,
    val isSecret: Boolean,
    val channelDefault: String,
    val groupDefault: String,
    val unknownLabel: String,
)

internal val DISPLAY_NAME_POOL155 = listOf(
    "Alice", "Bob", "", " ", "\u00a0", "\u0085", "  x  ", "群名", "a",
)

internal val CHAT_TYPE_POOL155 = listOf(
    "DIRECT", "GROUP", "CHANNEL", "SECRET", "", "group", "channel", "secret",
)

internal val LABEL_POOL155 = listOf(
    "频道", "群聊", "未知", "", " ", "默认", "\u00a0",
)

internal fun chat155(
    id: String = "c1",
    isGroup: Boolean = false,
    chatType: String = "DIRECT",
    groupName: String? = null,
    participants: List<User> = emptyList(),
): Chat = Chat(
    id = id,
    participants = participants,
    lastMessageTime = 0L,
    isGroup = isGroup,
    chatType = chatType,
    groupName = groupName,
)

internal fun user155(id: String, name: String, nickname: String?): User =
    User(id = id, name = name, nickname = nickname)

internal fun randomUser155(rnd: Random): User {
    val id = DISPLAY_NAME_POOL155[rnd.nextInt(DISPLAY_NAME_POOL155.size)]
    val name = DISPLAY_NAME_POOL155[rnd.nextInt(DISPLAY_NAME_POOL155.size)]
    val nickname = when (rnd.nextInt(10)) {
        0 -> null
        else -> DISPLAY_NAME_POOL155[rnd.nextInt(DISPLAY_NAME_POOL155.size)]
    }
    return user155(id, name, nickname)
}

internal fun randomDisplayNameCase155(rnd: Random): DisplayNameCase155 {
    val chatType = CHAT_TYPE_POOL155[rnd.nextInt(CHAT_TYPE_POOL155.size)]
    val isGroup = rnd.nextBoolean()
    val groupName = when (rnd.nextInt(10)) {
        0 -> null
        1 -> ""
        2 -> " "
        3 -> "\u00a0"
        4 -> "\u0085"
        else -> DISPLAY_NAME_POOL155[rnd.nextInt(DISPLAY_NAME_POOL155.size)]
    }
    val participantCount = rnd.nextInt(0, 3)
    val participants = List(participantCount) { randomUser155(rnd) }
    return DisplayNameCase155(
        chat = chat155(
            isGroup = isGroup,
            chatType = chatType,
            groupName = groupName,
            participants = participants,
        ),
        isSecret = rnd.nextInt(10) < 3,
        channelDefault = LABEL_POOL155[rnd.nextInt(LABEL_POOL155.size)],
        groupDefault = LABEL_POOL155[rnd.nextInt(LABEL_POOL155.size)],
        unknownLabel = LABEL_POOL155[rnd.nextInt(LABEL_POOL155.size)],
    )
}

internal fun jsonEscape155(s: String): String {
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

internal fun displayCaseWireJson155(case: DisplayNameCase155): String {
    val sb = StringBuilder()
    sb.append("{\"id\":\"").append(jsonEscape155(case.chat.id)).append("\"")
    sb.append(",\"ig\":").append(if (case.chat.isGroup) 1 else 0)
    sb.append(",\"ct\":\"").append(jsonEscape155(case.chat.chatType)).append("\"")
    if (case.chat.groupName != null) {
        sb.append(",\"gn\":\"").append(jsonEscape155(case.chat.groupName)).append("\"")
    }
    sb.append(",\"ps\":[")
    case.chat.participants.forEachIndexed { i, u ->
        if (i > 0) sb.append(",")
        sb.append("{\"id\":\"").append(jsonEscape155(u.id)).append("\"")
        sb.append(",\"n\":\"").append(jsonEscape155(u.name)).append("\"")
        if (u.nickname != null) {
            sb.append(",\"nn\":\"").append(jsonEscape155(u.nickname)).append("\"")
        }
        sb.append("}")
    }
    sb.append("]")
    sb.append(",\"s\":").append(if (case.isSecret) 1 else 0)
    sb.append(",\"cd\":\"").append(jsonEscape155(case.channelDefault)).append("\"")
    sb.append(",\"gd\":\"").append(jsonEscape155(case.groupDefault)).append("\"")
    sb.append(",\"ul\":\"").append(jsonEscape155(case.unknownLabel)).append("\"")
    sb.append("}")
    return sb.toString()
}

class ChatListDisplayNamePolicyFuzzTest {

    private fun check(case: DisplayNameCase155) {
        assertEquals(
            resolveOracle155(case.chat, case.isSecret, case.channelDefault, case.groupDefault, case.unknownLabel),
            ChatListDisplayNamePolicy.resolve(case.chat, case.isSecret, case.channelDefault, case.groupDefault, case.unknownLabel),
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Hand-computed expectations; oracle must agree with both.
        // (chat, isSecret, channelDefault, groupDefault, unknownLabel) -> expected
        val cases = listOf(
            DisplayNameCase155(chat155(chatType = "CHANNEL", groupName = "官方频道"), false, "频道", "群聊", "未知") to "官方频道",
            DisplayNameCase155(chat155(chatType = "CHANNEL", groupName = null), false, "频道", "群聊", "未知") to "频道",
            DisplayNameCase155(chat155(chatType = "CHANNEL", groupName = " "), false, "频道", "群聊", "未知") to "频道",
            // NBSP is blank in Kotlin; NEL (U+0085) is not.
            DisplayNameCase155(chat155(chatType = "CHANNEL", groupName = "\u00a0"), false, "频道", "群聊", "未知") to "频道",
            DisplayNameCase155(chat155(chatType = "CHANNEL", groupName = "\u0085"), false, "频道", "群聊", "未知") to "\u0085",
            DisplayNameCase155(chat155(isGroup = true, chatType = "GROUP", groupName = "小组"), false, "频道", "群聊", "未知") to "小组",
            DisplayNameCase155(chat155(isGroup = true, chatType = "GROUP", groupName = null), false, "频道", "群聊", "未知") to "群聊",
            DisplayNameCase155(chat155(isGroup = true, chatType = "GROUP", groupName = "  "), false, "频道", "群聊", "未知") to "群聊",
            // Channel branch wins over the group flag (when order).
            DisplayNameCase155(chat155(isGroup = true, chatType = "CHANNEL", groupName = "频道名"), false, "频道", "群聊", "未知") to "频道名",
            DisplayNameCase155(chat155(isGroup = true, chatType = "CHANNEL", groupName = null), false, "频道", "群聊", "未知") to "频道",
            // Direct chat: nickname wins, then name, then id.
            DisplayNameCase155(chat155(participants = listOf(user155("u1", "Alice", "阿狸"))), false, "频道", "群聊", "未知") to "阿狸",
            DisplayNameCase155(chat155(participants = listOf(user155("u1", "Alice", null))), false, "频道", "群聊", "未知") to "Alice",
            DisplayNameCase155(chat155(participants = listOf(user155("u1", "Alice", " "))), false, "频道", "群聊", "未知") to "Alice",
            DisplayNameCase155(chat155(participants = listOf(user155("u1", " ", " "))), false, "频道", "群聊", "未知") to "u1",
            DisplayNameCase155(chat155(participants = listOf(user155("", " ", null))), false, "频道", "群聊", "未知") to "未知",
            DisplayNameCase155(chat155(participants = listOf(user155(" ", " ", null))), false, "频道", "群聊", "未知") to "未知",
            DisplayNameCase155(chat155(participants = emptyList()), false, "频道", "群聊", "未知") to "未知",
            // Only the first participant counts.
            DisplayNameCase155(chat155(participants = listOf(user155("u1", "First", null), user155("u2", "Second", null))), false, "频道", "群聊", "未知") to "First",
            // Secret flag or SECRET type always masks, whatever the raw name.
            DisplayNameCase155(chat155(chatType = "CHANNEL", groupName = "官方频道"), true, "频道", "群聊", "未知") to "密聊",
            DisplayNameCase155(chat155(chatType = "SECRET", groupName = "小组"), false, "频道", "群聊", "未知") to "密聊",
            DisplayNameCase155(chat155(chatType = "SECRET", groupName = "小组"), true, "频道", "群聊", "未知") to "密聊",
            DisplayNameCase155(chat155(participants = listOf(user155("u1", "Alice", null))), true, "频道", "群聊", "未知") to "密聊",
            // Unknown chat type falls through to the direct-chat branch.
            DisplayNameCase155(chat155(chatType = "", participants = listOf(user155("u1", "Alice", null))), false, "频道", "群聊", "未知") to "Alice",
            DisplayNameCase155(chat155(chatType = "group", groupName = "小组", participants = listOf(user155("u1", "Alice", null))), false, "频道", "群聊", "未知") to "Alice",
        )
        for ((case, expected) in cases) {
            assertEquals(
                expected,
                resolveOracle155(case.chat, case.isSecret, case.channelDefault, case.groupDefault, case.unknownLabel),
            )
            check(case)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(155002)
        repeat(2000) {
            check(randomDisplayNameCase155(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        val groupNames = listOf<String?>(null, "", " ", "\u00a0", "\u0085", "群名")
        val participantSets = listOf(
            emptyList(),
            listOf(user155("u1", "Alice", null)),
            listOf(user155("u1", "Alice", "阿狸")),
            listOf(user155("u1", " ", " ")),
            listOf(user155("", " ", null)),
            listOf(user155("u1", "Alice", null), user155("u2", "Bob", null)),
        )
        for (chatType in CHAT_TYPE_POOL155) {
            for (isGroup in listOf(false, true)) {
                for (groupName in groupNames) {
                    for (participants in participantSets) {
                        for (isSecret in listOf(false, true)) {
                            check(
                                DisplayNameCase155(
                                    chat155(isGroup = isGroup, chatType = chatType, groupName = groupName, participants = participants),
                                    isSecret = isSecret,
                                    channelDefault = "频道",
                                    groupDefault = "群聊",
                                    unknownLabel = "未知",
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
