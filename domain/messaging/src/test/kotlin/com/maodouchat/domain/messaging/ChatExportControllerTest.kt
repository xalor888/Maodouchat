package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.User
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatExportControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val me = User(id = "user_me", name = "Me")
    private val peer = User(id = "u2", name = "U2")
    private val chat = Chat(id = "c1", participants = listOf(me, peer))

    private inner class Harness(
        initial: ChatDetailUiState,
        private val dispatcher: CoroutineDispatcher,
    ) {
        val uiState = MutableStateFlow(initial)
        val scopeJob = Job()
        val scope = CoroutineScope(dispatcher + scopeJob)

        var exportEnabled = true
        var ownerId: String? = "user_me"
        var token: String? = "valid_token"
        var sessionMayContinue = true
        var secretCaps: ConversationPrivacyCapabilities? = null
        var recentMessages: List<Message> = emptyList()
        var recentThrows: Exception? = null
        val recentCalls = mutableListOf<Pair<String, Int>>()
        var writtenFile: File? = null
        var writeCalls = 0
        var writtenChatName: String? = null
        var writtenOwnerId: String? = null
        var writtenMessages: List<Message> = emptyList()
        var writtenSenderNames: List<String> = emptyList()
        var shareResult = true
        val sharedFiles = mutableListOf<File>()

        fun controller() = ChatExportController(
            getRecentMessages = { chatId, limit ->
                recentCalls += chatId to limit
                recentThrows?.let { throw it }
                recentMessages
            },
            uiState = uiState,
            textProvider = { key, _ -> "text_$key" },
            scope = scope,
            ioDispatcher = dispatcher,
            exportEnabled = { exportEnabled },
            currentUserId = { ownerId },
            sessionToken = { token },
            sessionMayContinue = { sessionMayContinue },
            secretChatCapabilities = { secretCaps },
            maxMessages = 2000,
            formatVersion = 1,
            writeExportFile = { _, chatName, owner, messages, resolveSenderName, _ ->
                writeCalls++
                writtenChatName = chatName
                writtenOwnerId = owner
                writtenMessages = messages.toList()
                writtenSenderNames = writtenMessages.map { resolveSenderName(it.senderId) }
                writtenFile
            },
            shareExportFile = { file, _ -> sharedFiles += file; shareResult },
        )

        suspend fun settle() {
            scopeJob.children.forEach { it.join() }
        }
    }

    private fun state(
        chat: Chat? = this.chat,
        messages: List<Message> = emptyList(),
        isSecretChat: Boolean? = null,
        hasMoreOlderMessages: Boolean = false,
    ) = ChatDetailUiState(
        chat = chat,
        messages = messages,
        isSecretChat = isSecretChat,
        hasMoreOlderMessages = hasMoreOlderMessages,
    )

    private fun message(
        id: String,
        senderId: String = "user_me",
        content: String = "one",
        type: MessageType = MessageType.TEXT,
        timestamp: Long = 0L,
        status: MessageStatus = MessageStatus.SENT,
    ) = Message(
        id = id,
        chatId = "c1",
        senderId = senderId,
        content = content,
        type = type,
        timestamp = timestamp,
        status = status,
    )

    @Test
    fun exportChatAsJson_includesFormatVersionAndMessages() {
        val harness = Harness(state(messages = listOf(message("m1", content = "Export json test"))), dispatcher)

        val json = harness.controller().exportChatAsJson()

        assertTrue(json.contains("\"formatVersion\":1"))
        assertTrue(json.contains("\"chatId\":\"c1\""))
        assertTrue(json.contains("\"messageCount\":1"))
        assertTrue(json.contains("Export json test"))
    }

    @Test
    fun exportChatAsJson_blocksSecretChat() {
        val secretChat = Chat(id = "c_secret", isGroup = false, chatType = "SECRET")
        val harness = Harness(
            state(chat = secretChat, messages = listOf(message("m_sec", content = "Secret content")), isSecretChat = true),
            dispatcher
        )

        assertEquals("{}", harness.controller().exportChatAsJson())
    }

    @Test
    fun exportChatAsJson_reportsEmptyWhenChatMissing() {
        val harness = Harness(state(chat = null), dispatcher)

        assertEquals("{}", harness.controller().exportChatAsJson())
    }

    @Test
    fun exportChatAsJson_blocksWhenTokenBlank() {
        val harness = Harness(state(), dispatcher).apply { token = null }

        assertEquals("{}", harness.controller().exportChatAsJson())
    }

    @Test
    fun exportChatAsJson_marksPartialWhenOlderMessagesRemain() {
        val harness = Harness(state(hasMoreOlderMessages = true), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("\"partial\":true"))
    }

    @Test
    fun exportChatAsJson_includesGroupNameForGroups() {
        val harness = Harness(state(chat = Chat(id = "g1", isGroup = true, groupName = "Fam")), dispatcher)

        val json = harness.controller().exportChatAsJson()

        assertTrue(json.contains("\"isGroup\":true"))
        assertTrue(json.contains("\"groupName\":\"Fam\""))
    }

    @Test
    fun exportChatAsJson_reportsEmptyWhenUserIdBlank() {
        val harness = Harness(state(), dispatcher).apply { ownerId = "" }

        assertEquals("{}", harness.controller().exportChatAsJson())
    }

    @Test
    fun exportChatAsJson_reportsEmptyWhenUserIdMissing() {
        val harness = Harness(state(), dispatcher).apply { ownerId = null }

        assertEquals("{}", harness.controller().exportChatAsJson())
    }

    @Test
    fun exportChatAsJson_blocksSecretChatByModelFlag() {
        val harness = Harness(
            state(chat = Chat(id = "c_secret", isGroup = false, chatType = "SECRET"), isSecretChat = false),
            dispatcher
        )

        assertEquals("{}", harness.controller().exportChatAsJson())
    }

    @Test
    fun exportChatAsJson_marksNotPartialByDefault() {
        val harness = Harness(state(), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("\"partial\":false"))
    }

    @Test
    fun clearExportInfo_clearsExportInfoMessage() {
        val harness = Harness(ChatDetailUiState(exportInfoMessage = "done"), dispatcher)

        harness.controller().clearExportInfo()

        assertNull(harness.uiState.value.exportInfoMessage)
    }

    @Test
    fun exportChatAsJson_includesPerMessageFields() {
        val harness = Harness(
            state(
                messages = listOf(
                    message("m1", content = "hello", timestamp = 12345L, status = MessageStatus.READ),
                    message("m2", senderId = "other", content = "pic", type = MessageType.IMAGE, timestamp = 12346L),
                )
            ),
            dispatcher
        )

        val json = harness.controller().exportChatAsJson()

        assertTrue(json.contains("\"sender\":\"user_me\""))
        assertTrue(json.contains("\"type\":\"IMAGE\""))
        assertTrue(json.contains("\"status\":\"READ\""))
        assertTrue(json.contains("\"timestamp\":12345"))
        assertTrue(json.contains("\"id\":\"m2\""))
    }

    @Test
    fun exportChatAsJson_returnsEmptyWhenOwnerIsFallbackMe() {
        val harness = Harness(state(), dispatcher).apply { ownerId = "me" }

        assertEquals("{}", harness.controller().exportChatAsJson())
    }

    @Test
    fun exportChatAsJson_countsAllLoadedMessages() {
        val harness = Harness(
            state(messages = listOf(message("a"), message("b", content = "two"), message("c", content = "three"))),
            dispatcher
        )

        val json = harness.controller().exportChatAsJson()

        assertTrue(json.contains("\"messageCount\":3"))
        assertTrue(json.contains("one") && json.contains("two") && json.contains("three"))
    }

    @Test
    fun exportChatAsJson_includesExportTimestamp() {
        val harness = Harness(state(), dispatcher)

        val json = harness.controller().exportChatAsJson()

        assertTrue(json.contains("\"exportedAt\":"))
        val raw = json.substringAfter("\"exportedAt\":").substringBefore(",").substringBefore("}")
        assertTrue((raw.trim().toLongOrNull() ?: 0L) > 0L)
    }

    @Test
    fun `exportChatAsJson exports a positive numeric exportedAt`() {
        val harness = Harness(state(), dispatcher)

        val json = harness.controller().exportChatAsJson()
        val raw = json.substringAfter("\"exportedAt\":").substringBefore(",").substringBefore("}")
        assertTrue((raw.trim().toLongOrNull() ?: 0L) > 0L)
    }

    @Test
    fun exportChatAsJson_marksDirectChatNotGroup() {
        val harness = Harness(state(), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("\"isGroup\":false"))
    }

    @Test
    fun `exportChatAsJson reports zero count for empty messages`() {
        val harness = Harness(state(), dispatcher)

        val json = harness.controller().exportChatAsJson()

        assertTrue(json.contains("\"messageCount\":0"))
        assertTrue(json.contains("\"partial\":false"))
    }

    @Test
    fun `exportChatAsJson includes blank group name verbatim`() {
        val harness = Harness(state(chat = Chat(id = "g1", isGroup = true, groupName = "")), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("\"groupName\":\"\""))
    }

    @Test
    fun `exportChatAsJson preserves message order`() {
        val harness = Harness(
            state(messages = listOf(message("m1"), message("m2", content = "two"), message("m3", content = "three"))),
            dispatcher
        )

        val json = harness.controller().exportChatAsJson()
        val first = json.indexOf("\"id\":\"m1\"")
        val second = json.indexOf("\"id\":\"m2\"")
        val third = json.indexOf("\"id\":\"m3\"")
        assertTrue(first >= 0 && first < second && second < third)
    }

    @Test
    fun `exportChatAsJson includes plain text content verbatim`() {
        val harness = Harness(state(messages = listOf(message("m1", content = "order 12345 confirmed"))), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("order 12345 confirmed"))
    }

    @Test
    fun `clearExportInfo leaves other info message untouched`() {
        val harness = Harness(ChatDetailUiState(exportInfoMessage = "done", infoMessage = "kept"), dispatcher)

        harness.controller().clearExportInfo()

        assertNull(harness.uiState.value.exportInfoMessage)
        assertEquals("kept", harness.uiState.value.infoMessage)
    }

    @Test
    fun exportChatAsJson_blocksSecretChatByStateFlagAlone() {
        val harness = Harness(state(chat = Chat(id = "c_flag", isGroup = false), isSecretChat = true), dispatcher)

        assertEquals("{}", harness.controller().exportChatAsJson())
    }

    @Test
    fun exportChatAsJson_reportsEmptyWhenUserIdWhitespace() {
        val harness = Harness(state(), dispatcher).apply { ownerId = "   " }

        assertEquals("{}", harness.controller().exportChatAsJson())
    }

    @Test
    fun `exportChatAsJson includes spaces group name verbatim`() {
        val harness = Harness(state(chat = Chat(id = "g1", isGroup = true, groupName = "   ")), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("\"groupName\":\"   \""))
    }

    @Test
    fun `exportChatAsJson includes empty content verbatim`() {
        val harness = Harness(state(messages = listOf(message("m1", content = ""))), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("\"content\":\"\""))
    }

    @Test
    fun `exportChatAsJson includes zero timestamp`() {
        val harness = Harness(state(messages = listOf(message("m1", content = "hi", timestamp = 0L))), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("\"timestamp\":0"))
    }

    @Test
    fun `exportChatAsJson counts messages from other chats without filtering`() {
        val harness = Harness(
            state(
                messages = listOf(
                    Message(id = "m1", chatId = "c1", senderId = "user_me", content = "one", type = MessageType.TEXT, timestamp = 0L),
                    Message(id = "m2", chatId = "c-other", senderId = "user_me", content = "two", type = MessageType.TEXT, timestamp = 0L),
                )
            ),
            dispatcher
        )

        val json = harness.controller().exportChatAsJson()

        assertTrue(json.contains("\"messageCount\":2"))
        assertTrue(json.contains("one") && json.contains("two"))
    }

    @Test
    fun `exportChatAsJson reports empty when token is whitespace`() {
        val harness = Harness(state(), dispatcher).apply { token = "   " }

        assertEquals("{}", harness.controller().exportChatAsJson())
    }

    @Test
    fun `exportChatAsJson marks partial with loaded messages`() {
        val harness = Harness(
            state(messages = listOf(message("m1"), message("m2", content = "two")), hasMoreOlderMessages = true),
            dispatcher
        )

        val json = harness.controller().exportChatAsJson()

        assertTrue(json.contains("\"partial\":true"))
        assertTrue(json.contains("\"messageCount\":2"))
    }

    @Test
    fun `exportChatAsJson keeps list order when timestamps run backwards`() {
        val harness = Harness(
            state(
                messages = listOf(
                    message("m1", content = "first", timestamp = 300L),
                    message("m2", content = "second", timestamp = 100L),
                )
            ),
            dispatcher
        )

        val json = harness.controller().exportChatAsJson()
        val first = json.indexOf("\"id\":\"m1\"")
        val second = json.indexOf("\"id\":\"m2\"")
        assertTrue(first >= 0 && first < second)
    }

    @Test
    fun `exportChatAsJson twice returns full json both times`() {
        val harness = Harness(state(messages = listOf(message("m1"))), dispatcher)
        val controller = harness.controller()

        val first = controller.exportChatAsJson()
        val second = controller.exportChatAsJson()

        assertTrue(first.contains("\"chatId\":\"c1\"") && first.contains("\"messageCount\":1"))
        assertTrue(second.contains("\"chatId\":\"c1\"") && second.contains("\"messageCount\":1"))
    }

    @Test
    fun `exportChatAsJson reports null group name for direct chat`() {
        val harness = Harness(state(), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("\"groupName\":null"))
    }

    @Test
    fun `exportChatAsJson escapes quotes backslash and newline in content`() {
        val harness = Harness(
            state(messages = listOf(message("m1", content = "say \"hi\" \\ path\nline"))),
            dispatcher
        )

        val json = harness.controller().exportChatAsJson()
        assertTrue(json.contains("say \\\"hi\\\" \\\\ path\\nline"))
    }

    @Test
    fun `exportChatAsJson uses default sent status when message omits status`() {
        val harness = Harness(state(messages = listOf(message("m1"))), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("\"status\":\"SENT\""))
    }

    @Test
    fun `exportChatAsJson keeps negative timestamp verbatim`() {
        val harness = Harness(state(messages = listOf(message("m1", timestamp = -1L))), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("\"timestamp\":-1"))
    }

    @Test
    fun `exportChatAsJson reports zero count for empty group chat`() {
        val harness = Harness(state(chat = Chat(id = "g1", isGroup = true, groupName = "G")), dispatcher)

        val json = harness.controller().exportChatAsJson()

        assertTrue(json.contains("\"messageCount\":0"))
        assertTrue(json.contains("\"isGroup\":true"))
        assertTrue(json.contains("\"partial\":false"))
    }

    @Test
    fun `exportChatAsJson includes unicode content verbatim`() {
        val harness = Harness(state(messages = listOf(message("m1", content = "你好世界 🌍"))), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("你好世界 🌍"))
    }

    @Test
    fun `exportChatAsJson includes video failed message fields`() {
        val harness = Harness(
            state(
                messages = listOf(
                    message("m1", content = "clip", type = MessageType.VIDEO, timestamp = 777L, status = MessageStatus.FAILED),
                )
            ),
            dispatcher
        )

        val json = harness.controller().exportChatAsJson()

        assertTrue(json.contains("\"type\":\"VIDEO\""))
        assertTrue(json.contains("\"status\":\"FAILED\""))
        assertTrue(json.contains("\"timestamp\":777"))
    }

    @Test
    fun `clearExportInfo keeps null export info null`() {
        val harness = Harness(ChatDetailUiState(exportInfoMessage = null, infoMessage = "kept"), dispatcher)

        harness.controller().clearExportInfo()

        assertNull(harness.uiState.value.exportInfoMessage)
        assertEquals("kept", harness.uiState.value.infoMessage)
    }

    @Test
    fun `exportChatAsJson escapes tab and carriage return in content`() {
        val harness = Harness(state(messages = listOf(message("m1", content = "a\tb\rc"))), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("a\\tb\\rc"))
    }

    @Test
    fun `exportChatAsJson includes id sender and type for each message`() {
        val harness = Harness(
            state(
                messages = listOf(
                    message("m-a", content = "one"),
                    message("m-b", senderId = "other", content = "pic", type = MessageType.IMAGE),
                )
            ),
            dispatcher
        )

        val json = harness.controller().exportChatAsJson()

        assertTrue(json.contains("\"id\":\"m-a\""))
        assertTrue(json.contains("\"sender\":\"user_me\""))
        assertTrue(json.contains("\"type\":\"TEXT\""))
        assertTrue(json.contains("\"id\":\"m-b\""))
        assertTrue(json.contains("\"sender\":\"other\""))
        assertTrue(json.contains("\"type\":\"IMAGE\""))
    }

    @Test
    fun `exportChatAsJson includes failed status verbatim`() {
        val harness = Harness(
            state(messages = listOf(message("m1", content = "oops", status = MessageStatus.FAILED))),
            dispatcher
        )

        assertTrue(harness.controller().exportChatAsJson().contains("\"status\":\"FAILED\""))
    }

    @Test
    fun `exportChatAsJson keeps max timestamp verbatim`() {
        val harness = Harness(state(messages = listOf(message("m1", timestamp = Long.MAX_VALUE))), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("\"timestamp\":9223372036854775807"))
    }

    @Test
    fun `exportChatAsJson includes group id with messages and count`() {
        val harness = Harness(
            state(
                chat = Chat(id = "g9", isGroup = true, groupName = "Team"),
                messages = listOf(message("m1"), message("m2", senderId = "other", content = "two")),
            ),
            dispatcher
        )

        val json = harness.controller().exportChatAsJson()

        assertTrue(json.contains("\"chatId\":\"g9\""))
        assertTrue(json.contains("\"isGroup\":true"))
        assertTrue(json.contains("\"groupName\":\"Team\""))
        assertTrue(json.contains("\"messageCount\":2"))
    }

    @Test
    fun `exportChatAsJson includes punctuation content verbatim`() {
        val harness = Harness(state(messages = listOf(message("m1", content = "a+b=c & d<e>"))), dispatcher)

        assertTrue(harness.controller().exportChatAsJson().contains("a+b=c & d<e>"))
    }

    @Test
    fun exportChatHistory_blocksSecretChat() = runTest(dispatcher) {
        val secretChat = Chat(id = "c_secret", isGroup = false, chatType = "SECRET")
        val harness = Harness(state(chat = secretChat, isSecretChat = true), dispatcher)

        harness.controller().exportChatHistory()
        harness.settle()

        assertTrue(harness.uiState.value.infoMessage.orEmpty().contains("text_"))
        assertTrue(harness.recentCalls.isEmpty())
    }

    @Test
    fun exportChatHistory_blocksWhenSecretCapsOverride() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher).apply {
            secretCaps = ConversationPrivacyCapabilities(isSecretChat = true, isLocked = false)
        }

        harness.controller().exportChatHistory()
        harness.settle()

        assertEquals("text_SECRET_CHAT_EXPORT_BLOCKED", harness.uiState.value.infoMessage)
        assertTrue(harness.recentCalls.isEmpty())
    }

    @Test
    fun exportChatHistory_reportsDisabledAndTouchesNoRepoWhenFlagHardOff() = runTest(dispatcher) {
        val harness = Harness(state(chat = null), dispatcher).apply { exportEnabled = false }

        harness.controller().exportChatHistory()
        harness.settle()

        assertEquals("text_EXPORT_DISABLED", harness.uiState.value.infoMessage)
        assertTrue(harness.recentCalls.isEmpty())
    }

    @Test
    fun exportChatHistory_reportsSessionExpiredWhenTokenBlank() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher).apply { token = null }

        harness.controller().exportChatHistory()
        harness.settle()

        assertEquals("text_SESSION_EXPIRED", harness.uiState.value.groupEncryptionWarning)
        assertTrue(harness.recentCalls.isEmpty())
    }

    @Test
    fun exportChatHistory_reportsEmptyWhenRepoEmpty() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)

        harness.controller().exportChatHistory()
        harness.settle()

        assertEquals("text_EXPORT_EMPTY", harness.uiState.value.infoMessage)
        assertEquals(listOf("c1" to 2000), harness.recentCalls)
        assertEquals(0, harness.writeCalls)
    }

    @Test
    fun exportChatHistory_treatsRepoErrorAsEmpty() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher).apply { recentThrows = RuntimeException("boom") }

        harness.controller().exportChatHistory()
        harness.settle()

        assertEquals("text_EXPORT_EMPTY", harness.uiState.value.infoMessage)
        assertEquals(0, harness.writeCalls)
    }

    @Test
    fun exportChatHistory_writesAndSharesOnSuccess() = runTest(dispatcher) {
        val file = File.createTempFile("export", ".txt")
        val harness = Harness(state(), dispatcher).apply {
            recentMessages = listOf(
                message("m1", senderId = "u2", content = "hi"),
                message("m2", content = "hello"),
            )
            writtenFile = file
        }

        harness.controller().exportChatHistory()
        harness.settle()

        assertEquals("text_EXPORT_DONE", harness.uiState.value.infoMessage)
        assertEquals(listOf("c1" to 2000), harness.recentCalls)
        assertEquals(1, harness.writeCalls)
        assertEquals("U2", harness.writtenChatName)
        assertEquals("user_me", harness.writtenOwnerId)
        assertEquals(listOf("m2", "m1"), harness.writtenMessages.map { it.id })
        assertEquals(listOf("Me", "U2"), harness.writtenSenderNames)
        assertEquals(listOf(file), harness.sharedFiles)
    }

    @Test
    fun exportChatHistory_reportsFailedWhenWriteReturnsNull() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher).apply {
            recentMessages = listOf(message("m1"))
            writtenFile = null
        }

        harness.controller().exportChatHistory()
        harness.settle()

        assertEquals("text_EXPORT_FAILED", harness.uiState.value.infoMessage)
        assertEquals(1, harness.writeCalls)
        assertTrue(harness.sharedFiles.isEmpty())
    }

    @Test
    fun exportChatHistory_reportsFailedWhenShareFails() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher).apply {
            recentMessages = listOf(message("m1"))
            writtenFile = File.createTempFile("export", ".txt")
            shareResult = false
        }

        harness.controller().exportChatHistory()
        harness.settle()

        assertEquals("text_EXPORT_FAILED", harness.uiState.value.infoMessage)
        assertEquals(1, harness.sharedFiles.size)
    }

    @Test
    fun exportChatHistory_usesGroupNameFallbackWhenBlank() = runTest(dispatcher) {
        val harness = Harness(state(chat = Chat(id = "g1", isGroup = true, groupName = null)), dispatcher).apply {
            recentMessages = listOf(message("m1"))
            writtenFile = File.createTempFile("export", ".txt")
        }

        harness.controller().exportChatHistory()
        harness.settle()

        assertEquals("text_GROUP_NAME", harness.writtenChatName)
        assertEquals("text_EXPORT_DONE", harness.uiState.value.infoMessage)
    }

    @Test
    fun exportChatHistory_skipsWhenChatMissing() = runTest(dispatcher) {
        val harness = Harness(state(chat = null), dispatcher)

        harness.controller().exportChatHistory()
        harness.settle()

        assertNull(harness.uiState.value.infoMessage)
        assertTrue(harness.recentCalls.isEmpty())
    }

    @Test
    fun exportChatAsJson_blocksWhenSessionGateRejects() {
        val harness = Harness(state(), dispatcher).apply { sessionMayContinue = false }

        assertEquals("{}", harness.controller().exportChatAsJson())
    }
}
