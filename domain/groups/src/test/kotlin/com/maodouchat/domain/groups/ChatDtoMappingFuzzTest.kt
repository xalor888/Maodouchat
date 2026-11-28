package com.maodouchat.domain.groups

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.User
import com.maodouchat.core.model.UserDto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr193d(s: String): String {
    val out = StringBuilder()
    for (ch in s) {
        val cp = ch.code
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            out.append("%04x".format(0xD800 + (v shr 10)))
            out.append("%04x".format(0xDC00 + (v and 0x3FF)))
        } else {
            out.append("%04x".format(cp))
        }
    }
    return out.toString()
}

private fun decStr193d(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

private fun encOpt193d(s: String?): String = if (s == null) "NULL" else "S:" + encStr193d(s)
private fun decOpt193d(h: String): String? = if (h == "NULL") null else decStr193d(h.substring(2))

private val TYPE_NAMES193 = listOf(
    "TEXT", "MARKDOWN", "IMAGE", "GIF", "STICKER", "LOCATION", "VOICE",
    "VIDEO", "FILE", "NUDGE", "SK_DIST", "SYSTEM", "REVOKED"
)

// fromWire 的独立重实现：大小写不敏感按名匹配，落空回 TEXT。
private fun fromWireOracle193(value: String?): String =
    TYPE_NAMES193.firstOrNull { it.equals(value, ignoreCase = true) } ?: "TEXT"

// toDomainChat 的独立重实现：逐字段手写映射，不走生产函数。
private fun mapOracle193(dto: ChatDto): Chat = Chat(
    id = dto.id,
    participants = dto.participants.map {
        User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status, lastSeen = it.lastSeen)
    },
    lastMessage = dto.lastMessage,
    lastMessageType = MessageType.valueOf(fromWireOracle193(dto.lastMessageType)),
    lastMessageTime = dto.lastMessageTime,
    unreadCount = dto.unreadCount,
    isGroup = dto.isGroup,
    chatType = dto.chatType,
    groupName = dto.groupName,
    groupAnnouncement = dto.groupAnnouncement,
    groupAvatar = dto.groupAvatar,
    memberRevision = dto.memberRevision,
    disappearingMessageSeconds = dto.disappearingMessageSeconds
)

private fun encodeChat193(c: Chat): String {
    val parts = ArrayList<String>()
    parts.add(encStr193d(c.id))
    parts.add(c.participants.joinToString(",") { u ->
        listOf(encStr193d(u.id), encStr193d(u.name), encOpt193d(u.avatar), encStr193d(u.email),
            if (u.isOnline) "1" else "0", encStr193d(u.status), u.lastSeen.toString()).joinToString(":")
    })
    parts.add(encStr193d(c.lastMessage))
    parts.add(c.lastMessageType.name)
    parts.add(c.lastMessageTime.toString())
    parts.add(c.unreadCount.toString())
    parts.add(if (c.isGroup) "1" else "0")
    parts.add(encStr193d(c.chatType))
    parts.add(encOpt193d(c.groupName))
    parts.add(encOpt193d(c.groupAnnouncement))
    parts.add(encOpt193d(c.groupAvatar))
    parts.add(c.memberRevision.toString())
    parts.add(c.disappearingMessageSeconds.toString())
    parts.add(c.pinnedAt.toString())
    parts.add(if (c.notificationsMuted) "1" else "0")
    parts.add(if (c.archived) "1" else "0")
    parts.add(if (c.markedUnread) "1" else "0")
    parts.add(c.settingsUpdatedAt.toString())
    return parts.joinToString("|")
}

private val DTO_STRS193 = listOf(
    "", " ", "c1", "chat-1", "a/b", "a?b", "a%b", "a b", "é", "中文", "😀",
    "x".repeat(80), "a\"b", "a'b", "a\nb", "a\tb", "0", "-1", "DIRECT", "GROUP"
)
private val WIRE_TYPES193: List<String?> = listOf(
    null, "", " ", "TEXT", "text", "Text", "tExT", "IMAGE", "image", "VIDEO", "video",
    "STICKER", "sticker", "LOCATION", "location", "VOICE", "FILE", "SYSTEM", "system",
    "REVOKED", "MARKDOWN", "GIF", "NUDGE", "SK_DIST", "sk_dist", "CALL", "unknown",
    " TEXT", "TEXT ", "TEXT\n", "TEX", "TEXTS", "IMAGE ", "é", "😀", "location2"
)

private fun randomDtoStr193(r: Random): String =
    if (r.nextInt(3) == 0) DTO_STRS193[r.nextInt(DTO_STRS193.size)]
    else (0 until r.nextInt(12)).joinToString("") { "abcxyz 019/-_é😀\n\t\"'"[r.nextInt(19)].toString() }

private fun randomDtoOpt193(r: Random): String? =
    when (r.nextInt(4)) {
        0 -> null
        1 -> ""
        2 -> " "
        else -> randomDtoStr193(r)
    }

private fun randomUserDto193(r: Random): UserDto = UserDto(
    id = randomDtoStr193(r),
    name = randomDtoStr193(r),
    email = randomDtoStr193(r),
    avatar = randomDtoOpt193(r),
    status = randomDtoStr193(r),
    isOnline = r.nextBoolean(),
    lastSeen = r.nextLong(-1000, 2_000_000_000)
)

private fun randomChatDto193(r: Random): ChatDto = ChatDto(
    id = randomDtoStr193(r),
    participants = List(r.nextInt(0, 6)) { randomUserDto193(r) },
    lastMessage = randomDtoStr193(r),
    lastMessageType = if (r.nextInt(3) == 0) WIRE_TYPES193[r.nextInt(WIRE_TYPES193.size)] ?: "TEXT" else randomDtoStr193(r),
    lastMessageTime = r.nextLong(-1000, 2_000_000_000),
    unreadCount = r.nextInt(-5, 10000),
    isGroup = r.nextBoolean(),
    chatType = if (r.nextBoolean()) randomDtoStr193(r) else listOf("DIRECT", "GROUP", "CHANNEL", "SECRET")[r.nextInt(4)],
    groupName = randomDtoOpt193(r),
    groupAnnouncement = randomDtoOpt193(r),
    groupAvatar = randomDtoOpt193(r),
    memberRevision = r.nextLong(-5, 100000),
    disappearingMessageSeconds = r.nextInt(-5, 100000)
)

private fun randomWire193(r: Random): String? =
    if (r.nextInt(3) == 0) WIRE_TYPES193[r.nextInt(WIRE_TYPES193.size)]
    else randomDtoStr193(r)

// ---------- tests ----------

class ChatDtoMappingFuzzTest {

    @Test
    fun dtoDifferential193() {
        val r = Random(193005)
        repeat(300) {
            val dto = randomChatDto193(r)
            assertEquals(mapOracle193(dto), dto.toDomainChat())
            assertEquals(encodeChat193(mapOracle193(dto)), encodeChat193(dto.toDomainChat()))
        }
    }

    @Test
    fun dtoWireDifferential193() {
        val r = Random(193006)
        repeat(300) {
            val wire = randomWire193(r)
            assertEquals(fromWireOracle193(wire), MessageType.fromWire(wire).name)
        }
    }

    @Test
    fun dtoPinned193() {
        // 固定 DTO 的逐字段映射：参与者只取映射字段，isModerator/username 不进 User。
        val dto = ChatDto(
            id = "c1",
            participants = listOf(
                UserDto("u1", "Alice", "a@x", "av1", "online", true, true, 111L, "alice"),
                UserDto("u2", "Bob", "", null, "", false, false, 0L, null)
            ),
            lastMessage = "hi",
            lastMessageType = "sticker",
            lastMessageTime = 5000L,
            unreadCount = 3,
            isGroup = true,
            chatType = "GROUP",
            groupName = "G",
            groupAnnouncement = null,
            groupAvatar = "gav",
            memberRevision = 7L,
            disappearingMessageSeconds = 60
        )
        val expected = Chat(
            id = "c1",
            participants = listOf(
                User("u1", "Alice", "av1", "a@x", true, "online", lastSeen = 111L),
                User("u2", "Bob", null, "", false, "", lastSeen = 0L)
            ),
            lastMessage = "hi",
            lastMessageType = MessageType.STICKER,
            lastMessageTime = 5000L,
            unreadCount = 3,
            isGroup = true,
            chatType = "GROUP",
            groupName = "G",
            groupAnnouncement = null,
            groupAvatar = "gav",
            memberRevision = 7L,
            disappearingMessageSeconds = 60
        )
        assertEquals(expected, dto.toDomainChat())
        assertEquals(mapOracle193(dto), dto.toDomainChat())
        // fromWire 边缘：null/空白/大小写/未知/前后空格。
        assertEquals("TEXT", MessageType.fromWire(null).name)
        assertEquals("TEXT", MessageType.fromWire("").name)
        assertEquals("TEXT", MessageType.fromWire("unknown").name)
        assertEquals("TEXT", MessageType.fromWire(" TEXT").name)
        assertEquals("TEXT", MessageType.fromWire("tExT").name)
        assertEquals("STICKER", MessageType.fromWire("sticker").name)
        assertEquals("SK_DIST", MessageType.fromWire("sk_dist").name)
        assertEquals("LOCATION", MessageType.fromWire("Location").name)
        assertEquals(fromWireOracle193(null), MessageType.fromWire(null).name)
        // 空 DTO：默认值直通。
        val empty = ChatDto(id = "e")
        assertEquals(Chat(id = "e", lastMessageType = MessageType.TEXT, lastMessageTime = 0L), empty.toDomainChat())
    }
}
