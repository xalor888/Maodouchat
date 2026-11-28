package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ChatScheduleController
import com.maodouchat.core.model.ConversationScheduleBackend
import com.maodouchat.core.model.ConversationScheduleCoordinator
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageReminder
import com.maodouchat.core.model.ScheduledMessage
import com.maodouchat.core.model.ScheduledMessageRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr203b(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun warn203b(w: String?): String = w?.let { encStr203b(it) } ?: "~"

// op kind: 0 schedule，1 list，2 cancel，3 clear。
private data class RemOp203b(
    val kind: Int,
    val messageId: String = "",
    val content: String = "",
    val offset: Long = 0L,
    val chatId: String = "",
    val reminderId: String = "",
)

private data class RemCase203b(
    val nowBase: Long = System.currentTimeMillis(),
    val owner: String = "u1",
    val activeChat: String = "c1",
    val chatId: String = "c1",
    val ops: List<RemOp203b> = emptyList(),
)

private fun encReminder203b(r: MessageReminder): String =
    encStr203b(r.id) + ":" + encStr203b(r.chatId) + ":" + encStr203b(r.messageId) + ":" +
        encStr203b(r.messagePreview) + ":" + r.remindAtMillis + ":" + r.createdAtMillis + ":" +
        encStr203b(r.ownerUserId)

private fun encReminders203b(list: List<MessageReminder>): String =
    list.joinToString(",") { encReminder203b(it) }

// 相对时间档位：生产把 System.currentTimeMillis() 传进 relativeTimeText，
// oracle 用 Kotlin 侧实测传入的 now 值（harvested，生产 fake 记录）建模，
// 不自己另取 now。
private fun relBucket203b(remindAt: Long, now: Long): Long = (remindAt - now) / 60_000L

private fun remOracle203b(c: RemCase203b, nows: List<Long>): String {
    val effects = mutableListOf<String>()
    val backend = mutableListOf<MessageReminder>()
    var seq = 0
    var warn: String? = null
    var nowIdx = 0
    fun snap() {
        effects += "SNAP:" + warn203b(warn) + ":" + encReminders203b(backend)
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                val chat = c.activeChat.ifBlank { c.chatId }
                if (chat.isBlank()) {
                    // fall through to SNAP
                } else {
                    val owner = c.owner.trim()
                    if (owner.isBlank()) {
                        warn = "[SESSION_EXPIRED]"
                    } else if (op.messageId.isBlank()) {
                        warn = "[SCHEDULE_FAILED]"
                    } else {
                        seq++
                        val preview = Message(op.messageId, c.chatId, "u1", op.content)
                            .parsedContent().trim().take(80)
                        val remindAt = (c.nowBase + op.offset).coerceIn(
                            c.nowBase + 60_000L,
                            c.nowBase + 30L * 24L * 60L * 60L * 1_000L,
                        )
                        val reminder = MessageReminder(
                            id = "mr-$seq",
                            chatId = chat,
                            messageId = op.messageId,
                            messagePreview = preview,
                            remindAtMillis = remindAt,
                            createdAtMillis = c.nowBase,
                            ownerUserId = owner,
                        )
                        backend.removeAll { it.id == reminder.id }
                        backend += reminder
                        val now = nows[nowIdx++]
                        warn = "[REMINDER_SCHEDULED:REL:" + relBucket203b(remindAt, now) + "]"
                    }
                }
            }
            1 -> {
                val owner = c.owner.trim()
                val listed = if (owner.isBlank() || op.chatId.isBlank()) {
                    emptyList()
                } else {
                    backend.filter { it.chatId == op.chatId && it.remindAtMillis > c.nowBase }
                        .sortedBy { it.remindAtMillis }
                }
                effects += "LIST:" + encStr203b(op.chatId) + ":" + encReminders203b(listed)
            }
            2 -> {
                val owner = c.owner.trim()
                if (owner.isBlank() || op.reminderId.isBlank()) {
                    // noop
                } else {
                    backend.removeAll { it.id == op.reminderId }
                }
            }
            3 -> {
                val owner = c.owner.trim()
                if (owner.isBlank() || op.chatId.isBlank()) {
                    // noop
                } else {
                    backend.removeAll { it.chatId == op.chatId }
                }
            }
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        snap()
    }
    return effects.joinToString(",")
}

private class FakeScheduleBackend203b : ConversationScheduleBackend {
    val reminders = mutableListOf<MessageReminder>()
    val scheduledJobs = mutableListOf<String>()
    val cancelledJobs = mutableListOf<String>()

    override fun listAllScheduled(ownerUserId: String): List<ScheduledMessage> = emptyList()
    override fun listScheduled(ownerUserId: String, chatId: String): List<ScheduledMessage> = emptyList()
    override fun getScheduled(ownerUserId: String, id: String): ScheduledMessage? = null
    override fun addScheduled(ownerUserId: String, request: ScheduledMessageRequest): ScheduledMessage? = null
    override fun updateScheduled(ownerUserId: String, id: String, text: String?, sendAtMillis: Long?): ScheduledMessage? = null
    override fun removeScheduled(ownerUserId: String, id: String): Boolean = false
    override fun clearScheduled(ownerUserId: String, chatId: String): List<String> = emptyList()
    override fun scheduleJob(item: ScheduledMessage) {}
    override fun cancelJob(id: String) {}
    override fun rescheduleJob(item: ScheduledMessage) {}
    override fun listReminders(ownerUserId: String): List<MessageReminder> = reminders.toList()
    override fun upsertReminder(reminder: MessageReminder) {
        reminders.removeAll { it.id == reminder.id }
        reminders += reminder
    }
    override fun removeReminder(ownerUserId: String, id: String) {
        reminders.removeAll { it.id == id }
    }
    override fun clearReminders(ownerUserId: String, chatId: String) {
        reminders.removeAll { it.chatId == chatId }
    }
    override fun scheduleReminderJob(reminder: MessageReminder) {
        scheduledJobs += reminder.id
    }
    override fun cancelReminderJob(id: String) {
        cancelledJobs += id
    }
}

private fun remProd203b(c: RemCase203b, harvested: MutableList<Long>): String {
    val effects = mutableListOf<String>()
    val uiState = MutableStateFlow(ChatDetailUiState())
    val backend = FakeScheduleBackend203b()
    var seq = 0
    val coordinator = ConversationScheduleCoordinator(
        ownerUserId = { c.owner },
        backend = backend,
        now = { c.nowBase },
        reminderId = { "mr-${++seq}" },
    )
    val controller = ChatReminderController(
        chatScheduleController = ChatScheduleController(coordinator, scheduledMessagesEnabled = { true }, clock = { c.nowBase }),
        uiState = uiState,
        text = { message, args ->
            "[" + message.name + (if (args.isEmpty()) "" else ":" + args.joinToString(",")) + "]"
        },
        relativeTimeText = { remindAt, now ->
            harvested += now
            "REL:" + relBucket203b(remindAt, now)
        },
        activeChatId = { c.activeChat },
        chatId = c.chatId,
    )
    fun snap() {
        effects += "SNAP:" + warn203b(uiState.value.groupEncryptionWarning) + ":" +
            encReminders203b(backend.reminders)
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> controller.scheduleMessageReminder(
                Message(id = op.messageId, chatId = c.chatId, senderId = "u1", content = op.content),
                c.nowBase + op.offset,
            )
            1 -> {
                val listed = controller.listRemindersForChat(op.chatId)
                effects += "LIST:" + encStr203b(op.chatId) + ":" + encReminders203b(listed)
            }
            2 -> controller.cancelReminder(op.reminderId)
            3 -> controller.clearRemindersForChat(op.chatId)
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        snap()
    }
    return effects.joinToString(",")
}

private val REM_CONTENTS203b = listOf(
    "hello",
    "  spaced  ",
    "a&b",
    "x &amp; y",
    "&#65;BC",
    "&#x41;BC",
    "pre<meta>{\"k\":1}</meta>",
    "long " + "x".repeat(100),
    "",
    " ",
    "ünïcödé",
    "a<b>&quot;q&quot;",
    "&#8364;100",
    "tab\there",
    "a &lt;tag&gt; &amp; &#39;q&#39;",
    "&#x1F600;?",
)

private val REM_OFFSETS203b = listOf(
    -3_600_000L, 0L, 30_000L, 600_000L, 900_000L, 3_900_000L, 7_500_000L, 2_678_400_000L,
)

private fun randomRemOp203b(r: Random): RemOp203b {
    return when (r.nextInt(4)) {
        0 -> RemOp203b(
            0,
            messageId = listOf("", " ", "m1", "m2", "m3")[r.nextInt(5)],
            content = REM_CONTENTS203b[r.nextInt(REM_CONTENTS203b.size)],
            offset = REM_OFFSETS203b[r.nextInt(REM_OFFSETS203b.size)],
        )
        1 -> RemOp203b(1, chatId = listOf("", " ", "c1", "c2")[r.nextInt(4)])
        2 -> RemOp203b(2, reminderId = listOf("mr-1", "mr-2", "mr-3", "ghost", "")[r.nextInt(5)])
        else -> RemOp203b(3, chatId = listOf("", " ", "c1", "c2")[r.nextInt(4)])
    }
}

private fun randomRemCase203b(r: Random): RemCase203b {
    return RemCase203b(
        nowBase = System.currentTimeMillis() + r.nextLong(0L, 10_000_000L),
        owner = listOf("", " ", "u1", "me")[r.nextInt(4)],
        activeChat = listOf("", " ", "c1", "c2")[r.nextInt(4)],
        chatId = listOf("", " ", "c1", "c2")[r.nextInt(4)],
        ops = List(r.nextInt(0, 9)) { randomRemOp203b(r) },
    )
}

private fun boundaryRemCase203b(r: Random): RemCase203b {
    val base = RemCase203b()
    val cases = listOf(
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = -3_600_000L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = 0L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = 30_000L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = 600_000L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = 3_900_000L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = 7_500_000L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = 2_678_400_000L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = "", content = "hello", offset = 900_000L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = " ", content = "hello", offset = 900_000L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L)), owner = ""),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L)), owner = " "),
        base.copy(
            ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L)),
            activeChat = "", chatId = "",
        ),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "long " + "x".repeat(100), offset = 900_000L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "pre<meta>{\"k\":1}</meta>", offset = 900_000L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "a &lt;tag&gt; &amp; &#39;q&#39;", offset = 900_000L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "&#65;BC", offset = 900_000L))),
        base.copy(ops = listOf(RemOp203b(0, messageId = "m1", content = "&#8364;100", offset = 900_000L))),
        base.copy(
            ops = listOf(
                RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L),
                RemOp203b(1, chatId = "c1"),
            ),
        ),
        base.copy(
            ops = listOf(
                RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L),
                RemOp203b(2, reminderId = "mr-1"),
                RemOp203b(1, chatId = "c1"),
            ),
        ),
        base.copy(
            ops = listOf(
                RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L),
                RemOp203b(2, reminderId = "ghost"),
                RemOp203b(1, chatId = "c1"),
            ),
        ),
        base.copy(
            ops = listOf(
                RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L),
                RemOp203b(0, messageId = "m2", content = "world", offset = 600_000L),
                RemOp203b(1, chatId = "c1"),
            ),
        ),
        base.copy(
            ops = listOf(
                RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L),
                RemOp203b(0, messageId = "m2", content = "world", offset = 900_000L),
                RemOp203b(3, chatId = "c1"),
                RemOp203b(1, chatId = "c1"),
            ),
        ),
        base.copy(
            ops = listOf(
                RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L),
                RemOp203b(3, chatId = "c2"),
                RemOp203b(1, chatId = "c1"),
            ),
        ),
        base.copy(ops = listOf(RemOp203b(1, chatId = "c1"))),
        base.copy(ops = listOf(RemOp203b(1, chatId = ""))),
        base.copy(ops = listOf(RemOp203b(2, reminderId = "mr-1"))),
        base.copy(ops = listOf(RemOp203b(3, chatId = "c1"))),
        base.copy(
            activeChat = "c2",
            ops = listOf(
                RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L),
                RemOp203b(1, chatId = "c1"),
                RemOp203b(1, chatId = "c2"),
            ),
        ),
        base.copy(ops = emptyList()),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class ChatReminderControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(203201)
        repeat(300) {
            val c = randomRemCase203b(r)
            val harvested = mutableListOf<Long>()
            val prod = remProd203b(c, harvested)
            assertEquals(remOracle203b(c, harvested), prod)
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(203202)
        repeat(200) {
            val c = boundaryRemCase203b(r)
            val harvested = mutableListOf<Long>()
            val prod = remProd203b(c, harvested)
            assertEquals(remOracle203b(c, harvested), prod)
        }
    }

    @Test
    fun pinnedCases() {
        val cases = listOf(
            RemCase203b(ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L))),
            RemCase203b(ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = -3_600_000L))),
            RemCase203b(ops = listOf(RemOp203b(0, messageId = "", content = "hello", offset = 900_000L))),
            RemCase203b(owner = "", ops = listOf(RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L))),
            RemCase203b(
                ops = listOf(
                    RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L),
                    RemOp203b(1, chatId = "c1"),
                    RemOp203b(2, reminderId = "mr-1"),
                    RemOp203b(1, chatId = "c1"),
                ),
            ),
            RemCase203b(
                ops = listOf(
                    RemOp203b(0, messageId = "m1", content = "hello", offset = 900_000L),
                    RemOp203b(0, messageId = "m2", content = "world", offset = 600_000L),
                    RemOp203b(1, chatId = "c1"),
                    RemOp203b(3, chatId = "c1"),
                    RemOp203b(1, chatId = "c1"),
                ),
            ),
            RemCase203b(ops = listOf(RemOp203b(0, messageId = "m1", content = "pre<meta>{\"k\":1}</meta>", offset = 900_000L))),
            RemCase203b(ops = emptyList()),
        )
        for (c in cases) {
            val harvested = mutableListOf<Long>()
            val prod = remProd203b(c, harvested)
            assertEquals(remOracle203b(c, harvested), prod)
        }
    }
}
