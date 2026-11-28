package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatListPreviewPolicy
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.NudgeDisplayPolicy
import com.maodouchat.core.model.Templates
import com.maodouchat.core.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr209b(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit209b(b: Boolean): String = if (b) "1" else "0"

private data class PrvUser209b(
    val id: String,
    val name: String = "",
)

// op kind：0 emitListPreviewForDecrypted，1 indexSearchableMessage。
// chatMode：0 返回缓存会话、1 返回 null。
// allowsMode：0 允许索引、1 拒绝索引。
// indexMode：0 成功、1 抛 RuntimeException 带消息、2 抛 RuntimeException 空消息、
// 3 抛 CancellationException。
private data class PrvOp209b(
    val kind: Int,
    val msgId: String = "m1",
    val msgChatId: String = "c1",
    val senderId: String = "u1",
    val content: String = "hi",
    val typeIdx: Int = 0,
    val chatMode: Int = 0,
    val chatId: String = "c1",
    val chatIsGroup: Boolean = false,
    val participants: List<PrvUser209b> = emptyList(),
    val allowsMode: Int = 0,
    val indexMode: Int = 0,
)

private data class PrvCase209b(
    val contactName: String = "Ann",
    val currentUserId: String = "me",
    val activeChatId: String = "c1",
    val ops: List<PrvOp209b> = emptyList(),
)

private fun prvText209b(kind: ChatMessagePreviewText, args: Array<out Any>): String = when (kind) {
    ChatMessagePreviewText.NUDGE_YOU_NUDGED -> "you-nudged:" + args[0]
    ChatMessagePreviewText.NUDGE_THEY_NUDGED_YOU -> "they-nudged-you:" + args[0]
    ChatMessagePreviewText.NUDGE_THEY_NUDGED_TARGET -> "they-nudged-target:" + args[0] + ":" + args[1]
    ChatMessagePreviewText.PREVIEW_ENCRYPTED -> "[encrypted]"
    ChatMessagePreviewText.PREVIEW_IMAGE -> "[image]"
    ChatMessagePreviewText.PREVIEW_GIF -> "[gif]"
    ChatMessagePreviewText.PREVIEW_STICKER -> "[sticker]"
    ChatMessagePreviewText.PREVIEW_LOCATION -> "[location]"
    ChatMessagePreviewText.PREVIEW_VOICE -> "[voice]"
    ChatMessagePreviewText.PREVIEW_VIDEO -> "[video]"
    ChatMessagePreviewText.PREVIEW_FILE -> "[file]"
    ChatMessagePreviewText.MESSAGE_REVOKED_PLACEHOLDER -> "[revoked]"
}

private fun prvOracle209b(c: PrvCase209b): String {
    val effects = mutableListOf<String>()
    var curOp = PrvOp209b(0)
    fun chat(): Chat? {
        effects += "CALL:chat:" + curOp.chatMode
        return when (curOp.chatMode) {
            0 -> Chat(
                id = curOp.chatId,
                participants = curOp.participants.map { User(it.id, it.name) },
                lastMessageTime = 0L,
                isGroup = curOp.chatIsGroup,
            )
            else -> null
        }
    }
    fun contact(): User {
        effects += "CALL:contact"
        return User(id = "contact", name = c.contactName)
    }
    fun detailNudgePreview(message: Message): String {
        val ch = chat()
        val senderName = ch?.participants?.firstOrNull { it.id == message.senderId }?.name?.takeIf { it.isNotBlank() }
            ?: contact().name.takeIf { it.isNotBlank() }
            ?: message.senderId
        return NudgeDisplayPolicy.displayText(
            isOwnMessage = message.senderId == c.currentUserId,
            storedContent = message.content,
            senderDisplayName = senderName,
            isDirectChat = ch?.isGroup != true,
            templates = Templates(
                youNudged = { target -> prvText209b(ChatMessagePreviewText.NUDGE_YOU_NUDGED, arrayOf(target)) },
                theyNudgedYou = { sender -> prvText209b(ChatMessagePreviewText.NUDGE_THEY_NUDGED_YOU, arrayOf(sender)) },
                theyNudgedTarget = { sender, target ->
                    prvText209b(ChatMessagePreviewText.NUDGE_THEY_NUDGED_TARGET, arrayOf(sender, target))
                },
            ),
        )
    }
    fun listPreviewTextForMessage(message: Message): String {
        if (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) {
            val body = message.parsedContent()
            return if (body.isBlank() || WireEnvelopePreviewPolicy.looksLikeWireEnvelope(body)) {
                prvText209b(ChatMessagePreviewText.PREVIEW_ENCRYPTED, emptyArray())
            } else {
                body.take(200)
            }
        }
        val preview = ChatListPreviewPolicy.fromLatestMessage(
            latest = message,
            mediaLabel = { type ->
                when (type) {
                    MessageType.IMAGE -> prvText209b(ChatMessagePreviewText.PREVIEW_IMAGE, emptyArray())
                    MessageType.GIF -> prvText209b(ChatMessagePreviewText.PREVIEW_GIF, emptyArray())
                    MessageType.STICKER -> prvText209b(ChatMessagePreviewText.PREVIEW_STICKER, emptyArray())
                    MessageType.LOCATION -> prvText209b(ChatMessagePreviewText.PREVIEW_LOCATION, emptyArray())
                    MessageType.VOICE -> prvText209b(ChatMessagePreviewText.PREVIEW_VOICE, emptyArray())
                    MessageType.VIDEO -> prvText209b(ChatMessagePreviewText.PREVIEW_VIDEO, emptyArray())
                    MessageType.FILE -> prvText209b(ChatMessagePreviewText.PREVIEW_FILE, emptyArray())
                    else -> prvText209b(ChatMessagePreviewText.PREVIEW_ENCRYPTED, emptyArray())
                }
            },
            encryptedPlaceholder = prvText209b(ChatMessagePreviewText.PREVIEW_ENCRYPTED, emptyArray()),
            revokedPlaceholder = prvText209b(ChatMessagePreviewText.MESSAGE_REVOKED_PLACEHOLDER, emptyArray()),
            nudgeText = { detailNudgePreview(it) },
        )
        return preview.text.ifBlank { prvText209b(ChatMessagePreviewText.PREVIEW_ENCRYPTED, emptyArray()) }
    }
    fun emitListPreviewForDecrypted(message: Message) {
        if (message.type == MessageType.SK_DIST) return
        val chatId = message.chatId.ifBlank { c.activeChatId }
        if (chatId.isBlank()) return
        effects += "EMIT:" + encStr209b(chatId) + ":" + encStr209b(listPreviewTextForMessage(message)) +
            ":" + message.type.name + ":0"
    }
    fun indexSearchableMessage(message: Message) {
        val allows = curOp.allowsMode == 0
        effects += "CALL:allows:" + encStr209b(message.chatId) + ":" + bit209b(allows)
        if (!allows) return
        try {
            effects += "CALL:index:" + curOp.indexMode
            when (curOp.indexMode) {
                1 -> throw RuntimeException("boom")
                2 -> throw RuntimeException()
                3 -> throw CancellationException("cancel-boom")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            effects += "WARN:" + encStr209b("indexSearchableMessage failed") + ":" + e.javaClass.simpleName
        }
    }
    for (op in c.ops) {
        curOp = op
        val message = Message(
            id = op.msgId,
            chatId = op.msgChatId,
            senderId = op.senderId,
            content = op.content,
            type = MessageType.entries[op.typeIdx],
            timestamp = 0L,
        )
        try {
            when (op.kind) {
                0 -> emitListPreviewForDecrypted(message)
                else -> indexSearchableMessage(message)
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        effects += "FIN"
    }
    return effects.joinToString(",")
}

private fun prvProd209b(c: PrvCase209b): String {
    val effects = mutableListOf<String>()
    var curOp = PrvOp209b(0)
    val controller = ChatMessagePreviewController(
        chatProvider = {
            effects += "CALL:chat:" + curOp.chatMode
            when (curOp.chatMode) {
                0 -> Chat(
                    id = curOp.chatId,
                    participants = curOp.participants.map { User(it.id, it.name) },
                    lastMessageTime = 0L,
                    isGroup = curOp.chatIsGroup,
                )
                else -> null
            }
        },
        contactProvider = {
            effects += "CALL:contact"
            User(id = "contact", name = c.contactName)
        },
        currentUserId = { c.currentUserId },
        activeChatId = { c.activeChatId },
        textProvider = { kind, args -> prvText209b(kind, args) },
        emitListPreview = { chatId, preview, typeName, playSendSound ->
            effects += "EMIT:" + encStr209b(chatId) + ":" + encStr209b(preview) + ":" +
                typeName + ":" + bit209b(playSendSound)
        },
        allowsSearchIndex = { chatId ->
            val allows = curOp.allowsMode == 0
            effects += "CALL:allows:" + encStr209b(chatId) + ":" + bit209b(allows)
            allows
        },
        indexMessage = {
            effects += "CALL:index:" + curOp.indexMode
            when (curOp.indexMode) {
                1 -> throw RuntimeException("boom")
                2 -> throw RuntimeException()
                3 -> throw CancellationException("cancel-boom")
            }
        },
        logWarning = { msg, err ->
            effects += "WARN:" + encStr209b(msg) + ":" + (err?.javaClass?.simpleName ?: "~")
        },
    )
    for (op in c.ops) {
        curOp = op
        val message = Message(
            id = op.msgId,
            chatId = op.msgChatId,
            senderId = op.senderId,
            content = op.content,
            type = MessageType.entries[op.typeIdx],
            timestamp = 0L,
        )
        try {
            when (op.kind) {
                0 -> controller.emitListPreviewForDecrypted(message)
                else -> runBlocking { controller.indexSearchableMessage(message) }
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        effects += "FIN"
    }
    return effects.joinToString(",")
}

private val PRV_CONTENTS209b = listOf(
    "",
    " ",
    "hi",
    "hello world",
    "  spaced  ",
    "a".repeat(250),
    "x".repeat(199) + "y",
    "pre<meta>{\"k\":1}</meta>",
    "hello <meta>{\"k\":1}</meta>",
    "<meta>{\"k\":1}</meta>",
    "{\"ciphertext\":\"abc\",\"senderDeviceId\":1}",
    "{\"signal-x\":\"y\",\"algorithm\":\"z\"}",
    "[{\"ciphertext\":\"abc\"}]",
    "[\"abc\"]",
    "[图片]",
    "[GIF]",
    "eyJhYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5ejEyMzQ1Njc4OTA",
    "file:///storage/photo.jpg",
    "content://media/photo",
    "maodou-attachment://abc123",
    "无法解密",
    "decrypt failed",
    "Decrypt Failed",
    "[加密消息]",
    "[unable to decrypt message]",
    "session missing",
    "https://example.com/share?id=1",
    "https://x.com/api/attachments/123",
    "https://x.com/dl?ciphertext=abc",
    "https://x.com/join?senderDeviceId=5",
    "www.example.com/page",
    "你拍了拍Ann",
    "你拍了拍",
    "You nudged Ann",
    "YOU NUDGED Ann",
    "you nudged Ann",
    "Bob 拍了拍你",
    "a &amp; b",
    "&#65;BC",
    "&#x41;BC",
    "&#8364;100",
    "a&lt;b&gt;c",
    "Tom &amp; Jerry",
    "&nbsp;hi&nbsp;",
    "ünïcödé",
    "line1\nline2",
    "tab\there",
)

private val PRV_IDS209b = listOf("", " ", "m1", "m2", "x\ty")
private val PRV_CHAT_IDS209b = listOf("", " ", "c1", "c2", "x\ty")
private val PRV_SENDERS209b = listOf("u1", "u2", "me", "", " ")

private fun randomPrvUser209b(r: Random): PrvUser209b = PrvUser209b(
    id = PRV_SENDERS209b[r.nextInt(PRV_SENDERS209b.size)],
    name = listOf("", "Ann", "Bob", "  ")[r.nextInt(4)],
)

private fun randomPrvOp209b(r: Random): PrvOp209b {
    val kind = listOf(0, 0, 0, 1)[r.nextInt(4)]
    return PrvOp209b(
        kind = kind,
        msgId = PRV_IDS209b[r.nextInt(PRV_IDS209b.size)],
        msgChatId = PRV_CHAT_IDS209b[r.nextInt(PRV_CHAT_IDS209b.size)],
        senderId = PRV_SENDERS209b[r.nextInt(PRV_SENDERS209b.size)],
        content = PRV_CONTENTS209b[r.nextInt(PRV_CONTENTS209b.size)],
        typeIdx = r.nextInt(13),
        chatMode = r.nextInt(2),
        chatId = PRV_CHAT_IDS209b[r.nextInt(PRV_CHAT_IDS209b.size)],
        chatIsGroup = r.nextInt(2) == 0,
        participants = List(r.nextInt(0, 4)) { randomPrvUser209b(r) },
        allowsMode = r.nextInt(2),
        indexMode = r.nextInt(4),
    )
}

private fun randomPrvCase209b(r: Random): PrvCase209b = PrvCase209b(
    contactName = listOf("Ann", "", "  ", "Bob")[r.nextInt(4)],
    currentUserId = PRV_SENDERS209b[r.nextInt(PRV_SENDERS209b.size)],
    activeChatId = PRV_CHAT_IDS209b[r.nextInt(PRV_CHAT_IDS209b.size)],
    ops = List(r.nextInt(0, 9)) { randomPrvOp209b(r) },
)

private fun boundaryPrvCase209b(r: Random): PrvCase209b {
    val base = PrvCase209b()
    val ann = PrvUser209b("u1", "Ann")
    val me = PrvUser209b("me", "Me")
    return when (r.nextInt(32)) {
        0 -> base.copy(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 0)))
        1 -> base.copy(ops = listOf(PrvOp209b(0, content = "", typeIdx = 0)))
        2 -> base.copy(ops = listOf(PrvOp209b(0, content = "  ", typeIdx = 0)))
        3 -> base.copy(ops = listOf(PrvOp209b(0, content = "{\"ciphertext\":\"abc\"}", typeIdx = 0)))
        4 -> base.copy(ops = listOf(PrvOp209b(0, content = "pre<meta>{\"k\":1}</meta>", typeIdx = 0)))
        5 -> base.copy(ops = listOf(PrvOp209b(0, content = "a".repeat(250), typeIdx = 0)))
        6 -> base.copy(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 1)))
        7 -> base.copy(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 2)))
        8 -> base.copy(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 3)))
        9 -> base.copy(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 4)))
        10 -> base.copy(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 5)))
        11 -> base.copy(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 6)))
        12 -> base.copy(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 7)))
        13 -> base.copy(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 8)))
        14 -> base.copy(ops = listOf(PrvOp209b(0, content = "你拍了拍Ann", typeIdx = 9, participants = listOf(ann))))
        15 -> base.copy(ops = listOf(PrvOp209b(0, content = "你拍了拍Ann", typeIdx = 9, senderId = "me", participants = listOf(ann))))
        16 -> base.copy(ops = listOf(PrvOp209b(0, content = "You nudged Ann", typeIdx = 9, chatIsGroup = true, participants = listOf(ann))))
        17 -> base.copy(ops = listOf(PrvOp209b(0, content = "你拍了拍Ann", typeIdx = 9, chatMode = 1)))
        18 -> base.copy(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 9, participants = listOf(me))))
        19 -> base.copy(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 10)))
        20 -> base.copy(ops = listOf(PrvOp209b(0, content = "sys notice", typeIdx = 11)))
        21 -> base.copy(ops = listOf(PrvOp209b(0, content = "gone", typeIdx = 12)))
        22 -> base.copy(ops = listOf(PrvOp209b(0, msgChatId = "", content = "hi", typeIdx = 0)), activeChatId = "")
        23 -> base.copy(ops = listOf(PrvOp209b(0, msgChatId = "", content = "hi", typeIdx = 0)), activeChatId = "c9")
        24 -> base.copy(ops = listOf(PrvOp209b(0, content = "无法解密", typeIdx = 0)))
        25 -> base.copy(ops = listOf(PrvOp209b(0, content = "https://x.com/api/attachments/123", typeIdx = 0)))
        26 -> base.copy(ops = listOf(PrvOp209b(0, content = "a &amp; b", typeIdx = 0)))
        27 -> base.copy(ops = listOf(PrvOp209b(0, content = "&#65;BC", typeIdx = 0)))
        28 -> base.copy(ops = listOf(PrvOp209b(1, allowsMode = 1)))
        29 -> base.copy(ops = listOf(PrvOp209b(1, indexMode = 1)))
        30 -> base.copy(ops = listOf(PrvOp209b(1, indexMode = 3)))
        else -> base.copy(ops = listOf(PrvOp209b(1, indexMode = 0)))
    }
}

// ---------- tests ----------

class ChatMessagePreviewControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(209205)
        repeat(300) {
            val c = randomPrvCase209b(r)
            assertEquals(prvOracle209b(c), prvProd209b(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(209206)
        repeat(200) {
            val c = boundaryPrvCase209b(r)
            assertEquals(prvOracle209b(c), prvProd209b(c))
        }
    }

    @Test
    fun pinnedCases() {
        val ann = PrvUser209b("u1", "Ann")
        val cases = listOf(
            PrvCase209b(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 0))),
            PrvCase209b(ops = listOf(PrvOp209b(0, content = "", typeIdx = 0))),
            PrvCase209b(ops = listOf(PrvOp209b(0, content = "{\"ciphertext\":\"abc\"}", typeIdx = 0))),
            PrvCase209b(ops = listOf(PrvOp209b(0, content = "a".repeat(250), typeIdx = 0))),
            PrvCase209b(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 2))),
            PrvCase209b(ops = listOf(PrvOp209b(0, content = "你拍了拍Ann", typeIdx = 9, participants = listOf(ann)))),
            PrvCase209b(ops = listOf(PrvOp209b(0, content = "You nudged Ann", typeIdx = 9, chatIsGroup = true, participants = listOf(ann)))),
            PrvCase209b(ops = listOf(PrvOp209b(0, content = "hi", typeIdx = 10))),
            PrvCase209b(ops = listOf(PrvOp209b(0, content = "gone", typeIdx = 12))),
            PrvCase209b(ops = listOf(PrvOp209b(0, msgChatId = "", content = "hi", typeIdx = 0)), activeChatId = ""),
            PrvCase209b(ops = listOf(PrvOp209b(1, allowsMode = 1))),
            PrvCase209b(ops = listOf(PrvOp209b(1, indexMode = 1))),
            PrvCase209b(ops = listOf(PrvOp209b(1, indexMode = 3))),
            PrvCase209b(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(prvOracle209b(c), prvProd209b(c))
        }
    }
}
