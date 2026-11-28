package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatSendGuardFuzzTest {

    private companion object {
        private const val ITERATIONS = 200
        private val ME = User(id = "me", name = "Me")
        private val PARTICIPANTS = listOf(ME, User(id = "u2", name = "U2"), User(id = "u3", name = "U3"))
        // ChatSendGuard 内部 TEXT_RETRY_TYPES 是 private，此处逐字复述同一集合（生产侧改动会使穷尽测试立刻变红）。
        private val TEXT_RETRY_TYPES = setOf(
            MessageType.TEXT,
            MessageType.MARKDOWN,
            MessageType.STICKER,
            MessageType.LOCATION,
            MessageType.NUDGE,
        )
        private val TEXT_FRAGMENTS = listOf(
            "hello", "world", "你好", "看这里", "@所有人", "@u2", "@u3", "@AI", "# 标题",
            "**粗体**", "`代码`", "> 引用", "- 列表", "https://x.cn", "a", "x1", " ",
        )

        private fun randomText(random: Random): String {
            return when (random.nextInt(0, 12)) {
                0 -> ""
                1 -> "   \n\t  "
                2 -> "x".repeat(random.nextInt(ChatSendGuard.MAX_TEXT_LENGTH - 100, ChatSendGuard.MAX_TEXT_LENGTH + 500))
                else -> {
                    val parts = random.nextInt(1, 6)
                    List(parts) { TEXT_FRAGMENTS.random(random) }.joinToString(" ")
                }
            }
        }

        private fun randomState(random: Random, inputText: String): ChatDetailUiState {
            val isGroup = random.nextBoolean()
            return ChatDetailUiState(
                inputText = inputText,
                isSending = random.nextInt(0, 8) == 0,
                isContactBlocked = random.nextInt(0, 8) == 0,
                chatIsGroup = isGroup,
                myMemberRole = listOf(
                    null, "OWNER", "ADMIN", "MEMBER", "owner", "admin", "member", "MODERATOR",
                ).random(random),
                silentSend = random.nextBoolean(),
                chat = Chat(id = "c1", participants = PARTICIPANTS, isGroup = isGroup),
                messages = emptyList(),
            )
        }

        private fun randomMsg(random: Random, id: String): Message = Message(
            id = id,
            chatId = "c1",
            senderId = listOf("me", "u2", "u3").random(random),
            content = "hello",
            type = MessageType.entries.random(random),
            status = MessageStatus.entries.random(random),
            timestamp = 1L,
        )

        /** checkSend 的判定表：逐字复述生产侧守卫顺序（重入→空白→会话→屏蔽→@所有人→放行组装）。 */
        private fun expectedSend(
            state: ChatDetailUiState,
            ownerUserId: String,
            token: String,
            silent: Boolean?,
            forceText: String?,
            mentionsEnabled: Boolean,
            replyTarget: Message?,
            forcedMeta: MessageMeta?,
        ): SendDecision {
            if (state.isSending) return SendDecision.Reject(SendRejectReason.ALREADY_SENDING)
            val rawText = (forceText ?: state.inputText).trim()
            val text = if (rawText.length > ChatSendGuard.MAX_TEXT_LENGTH) {
                rawText.take(ChatSendGuard.MAX_TEXT_LENGTH)
            } else {
                rawText
            }
            if (text.isBlank()) return SendDecision.Reject(SendRejectReason.BLANK)
            if (token.isBlank() || ownerUserId.isBlank()) {
                return SendDecision.Reject(SendRejectReason.NO_SESSION)
            }
            if (state.isContactBlocked) return SendDecision.Reject(SendRejectReason.BLOCKED)
            val isGroup = state.chat?.isGroup == true
            val wantSilent = silent ?: state.silentSend
            val participants = state.chat?.participants.orEmpty()
            val extractedMentions = if (isGroup && mentionsEnabled) {
                MentionPolicy.extractMentionIds(text, participants, ownerUserId)
            } else {
                emptyList()
            }
            val mentionEveryoneAllowed = ChatSendGuard.canMentionEveryone(state.myMemberRole, isGroup)
            if (isGroup && extractedMentions.contains(MentionPolicy.EVERYONE_ID) && !mentionEveryoneAllowed) {
                return SendDecision.Reject(SendRejectReason.MENTION_EVERYONE_FORBIDDEN)
            }
            val looksMd = MarkdownDetectionPolicy.looksLikeMarkdown(text)
            val meta = forcedMeta ?: MessageMeta(
                mentions = extractedMentions,
                replyToId = replyTarget?.id,
                markdown = looksMd,
                silent = wantSilent,
            )
            val messageType = if (looksMd) MessageType.MARKDOWN else MessageType.TEXT
            return SendDecision.Allow(
                text = text,
                meta = meta,
                messageType = messageType,
                clearDraft = forceText == null,
                consumeSilent = silent == null && state.silentSend,
            )
        }

        /** checkRetry 的判定表：逐字复述生产侧守卫顺序（存在→归属→失败态→会话→附件分流→类型）。 */
        private fun expectedRetry(
            state: ChatDetailUiState,
            messageId: String,
            ownerUserId: String,
            token: String,
        ): ChatRetryDecision {
            val failed = state.messages.find { it.id == messageId }
                ?: return ChatRetryDecision.Reject(RetryRejectReason.NOT_FOUND)
            if (failed.senderId != ownerUserId) {
                return ChatRetryDecision.Reject(RetryRejectReason.NOT_MY_MESSAGE)
            }
            if (failed.status != MessageStatus.FAILED) {
                return ChatRetryDecision.Reject(RetryRejectReason.NOT_FAILED)
            }
            if (token.isBlank() || ownerUserId.isBlank()) {
                return ChatRetryDecision.Reject(RetryRejectReason.NO_SESSION)
            }
            if (failed.type in RELIABLE_ATTACHMENT_TYPES) {
                return ChatRetryDecision.NeedsAttachmentRetry(failed)
            }
            if (failed.type !in TEXT_RETRY_TYPES) {
                return ChatRetryDecision.Reject(RetryRejectReason.UNSUPPORTED_TYPE)
            }
            return ChatRetryDecision.Allow(failed)
        }
    }

    @Test
    fun `random checkSend never throws and is deterministic`() {
        val random = Random(0xA3_2026)
        repeat(ITERATIONS) { i ->
            val state = randomState(random, randomText(random))
            val owner = listOf("me", "", "u2").random(random)
            val token = listOf("tok", "", "   ").random(random)
            val silent: Boolean? = listOf(null, true, false).random(random)
            val forceText: String? = if (random.nextBoolean()) null else randomText(random)
            val mentionsEnabled = random.nextBoolean()
            val first = ChatSendGuard.checkSend(state, owner, token, silent, forceText, mentionsEnabled)
            val second = ChatSendGuard.checkSend(state, owner, token, silent, forceText, mentionsEnabled)
            assertEquals(first, second, "fuzz 迭代 #$i：同一输入两次判定必须相同")
        }
    }

    @Test
    fun `random checkSend matches the decision table`() {
        val random = Random(0xA3_2027)
        repeat(ITERATIONS) { i ->
            val state = randomState(random, randomText(random))
            val owner = listOf("me", "", "u2").random(random)
            val token = listOf("tok", "", "   ").random(random)
            val silent: Boolean? = listOf(null, true, false).random(random)
            val forceText: String? = if (random.nextBoolean()) null else randomText(random)
            val mentionsEnabled = random.nextBoolean()
            val replyTarget: Message? = if (random.nextInt(0, 4) == 0) randomMsg(random, "r${random.nextInt(100)}") else null
            val forcedMeta: MessageMeta? = if (random.nextInt(0, 4) == 0) {
                MessageMeta(mentions = listOf("u9"), replyToId = "r1", silent = random.nextBoolean())
            } else {
                null
            }
            val actual = ChatSendGuard.checkSend(
                state, owner, token, silent, forceText, mentionsEnabled, replyTarget, forcedMeta,
            )
            val expected = expectedSend(
                state, owner, token, silent, forceText, mentionsEnabled, replyTarget, forcedMeta,
            )
            assertEquals(expected, actual, "fuzz 迭代 #$i：发送判定必须与判定表一致")
            if (actual is SendDecision.Allow) {
                assertTrue(actual.text.isNotBlank(), "fuzz 迭代 #$i：放行文本不得空白")
                assertTrue(actual.text.length <= ChatSendGuard.MAX_TEXT_LENGTH, "fuzz 迭代 #$i：放行文本不得超长")
            }
        }
    }

    @Test
    fun `random checkRetry matches the decision table`() {
        val random = Random(0xA3_2028)
        repeat(ITERATIONS) { i ->
            val ids = listOf("m0", "m1", "m2", "m3").shuffled(random).take(random.nextInt(0, 5))
            val messages = ids.map { randomMsg(random, it) }
            val state = ChatDetailUiState(messages = messages)
            val messageId = listOf("m0", "m1", "m2", "m3", "missing").random(random)
            val owner = listOf("me", "", "u2").random(random)
            val token = listOf("tok", "").random(random)
            val actual = ChatSendGuard.checkRetry(state, messageId, owner, token)
            val expected = expectedRetry(state, messageId, owner, token)
            assertEquals(expected, actual, "fuzz 迭代 #$i：重试判定必须与判定表一致")
        }
    }

    @Test
    fun `random canMentionEveryone matches the role table`() {
        val random = Random(0xA3_2029)
        val roles = listOf(
            null, "OWNER", "ADMIN", "MEMBER", "owner", "admin", "member",
            "Owner", "Admin", "ADMIN ", " ADMIN", "MODERATOR", "",
        )
        repeat(ITERATIONS) { i ->
            val role = if (random.nextInt(0, 4) == 0) {
                List(random.nextInt(1, 8)) { ('A'..'Z').random(random) }.joinToString("")
            } else {
                roles.random(random)
            }
            val isGroup = random.nextBoolean()
            val expected = !isGroup || role == null || role.uppercase() == "OWNER" || role.uppercase() == "ADMIN"
            assertEquals(
                expected, ChatSendGuard.canMentionEveryone(role, isGroup),
                "fuzz 迭代 #$i：@所有人权限必须与角色表一致：role=$role group=$isGroup",
            )
        }
    }

    @Test
    fun `retry covers every message type and status combination`() {
        MessageType.entries.forEach { type ->
            MessageStatus.entries.forEach { status ->
                val mine = Message(
                    id = "m-${type.name}-${status.name}", chatId = "c1", senderId = "me",
                    content = "hello", type = type, status = status, timestamp = 1L,
                )
                val state = ChatDetailUiState(messages = listOf(mine))
                val result = ChatSendGuard.checkRetry(state, mine.id, "me", "tok")
                assertEquals(
                    expectedRetry(state, mine.id, "me", "tok"), result,
                    "类型 $type × 状态 $status 的重试判定必须与判定表一致",
                )
                if (status == MessageStatus.FAILED) {
                    when {
                        type in RELIABLE_ATTACHMENT_TYPES -> assertIs<ChatRetryDecision.NeedsAttachmentRetry>(result)
                        type in TEXT_RETRY_TYPES -> assertIs<ChatRetryDecision.Allow>(result)
                        else -> assertEquals(
                            RetryRejectReason.UNSUPPORTED_TYPE,
                            (result as ChatRetryDecision.Reject).reason,
                            "类型 $type 的失败消息必须判 UNSUPPORTED_TYPE",
                        )
                    }
                } else {
                    assertEquals(
                        RetryRejectReason.NOT_FAILED,
                        (result as ChatRetryDecision.Reject).reason,
                        "非 FAILED 消息必须判 NOT_FAILED：$type × $status",
                    )
                }
            }
        }
        // 会话判定排在附件分流之前：附件型失败消息 + 空 token 仍是 NO_SESSION。
        val image = Message(
            id = "m-img", chatId = "c1", senderId = "me", content = "hello",
            type = MessageType.IMAGE, status = MessageStatus.FAILED, timestamp = 1L,
        )
        val noSession = ChatSendGuard.checkRetry(ChatDetailUiState(messages = listOf(image)), "m-img", "me", "")
        assertEquals(RetryRejectReason.NO_SESSION, (noSession as ChatRetryDecision.Reject).reason)
    }
}
