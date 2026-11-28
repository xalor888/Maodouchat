package com.maodouchat.core.model

import kotlinx.coroutines.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr209c(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit209c(b: Boolean): String = if (b) "1" else "0"

private fun opt209c(s: String?): String = s?.let { encStr209c(it) } ?: "~"

private fun optLong209c(v: Long?): String = v?.toString() ?: "~"

private fun schEncItem209c(it: ScheduledMessage): String = listOf(
    encStr209c(it.id),
    encStr209c(it.chatId),
    encStr209c(it.peerUserId),
    encStr209c(it.text),
    it.sendAtMillis.toString(),
    it.createdAtMillis.toString(),
    bit209c(it.isGroup),
    encStr209c(it.ownerUserId),
    it.repeatIntervalMs.toString(),
    it.repeatCount.toString(),
    bit209c(it.weekdaysOnly),
    encStr209c(it.status),
    it.attempt.toString(),
    encStr209c(it.timeZoneId),
    encStr209c(it.idempotencyKey),
).joinToString(":")

private fun schEncRequest209c(r: ScheduledMessageRequest): String = listOf(
    encStr209c(r.chatId),
    encStr209c(r.peerUserId),
    encStr209c(r.text),
    r.sendAtMillis.toString(),
    bit209c(r.isGroup),
    r.repeatIntervalMs.toString(),
    r.repeatCount.toString(),
    bit209c(r.weekdaysOnly),
).joinToString(":")

private fun schEncMutOutcome209c(o: ChatScheduleMutationOutcome): String = when (o) {
    is ChatScheduleMutationOutcome.Applied -> "APPLIED:" +
        o.scheduledMessages.joinToString(";") { schEncItem209c(it) } + ":" +
        optLong209c(o.effectiveAtMillis) + ":~"
    is ChatScheduleMutationOutcome.Rejected -> "REJ:~:~:" + o.reason.name
}

private fun schEncImmOutcome209c(o: ChatScheduleImmediateOutcome): String = when (o) {
    is ChatScheduleImmediateOutcome.Ready -> "READY:" + schEncItem209c(o.item) + ":~"
    is ChatScheduleImmediateOutcome.Rejected -> "REJ:~:" + o.reason.name
}

// 脚本化后端：内存表 + 单点故障注入。faultTarget 只 fault 本次 op 内
// 对应方法的每一次调用；faultMode：0 正常、1 空结果（null/false/空表，
// void 方法等同正常）、2 抛 RuntimeException 带消息、3 抛空消息、4 抛 CE。
private class FakeBackend209c(
    val effects: MutableList<String>,
    val nowBase: Long,
    seedItems: List<ScheduledMessage>,
) : ConversationScheduleBackend {
    val store = LinkedHashMap<String, ScheduledMessage>()
    var seq = 1000
    var faultTarget = 0
    var faultMode = 0

    init {
        for (item in seedItems) store[item.id] = item
    }

    private fun faulted(target: Int): Boolean = faultTarget == target

    private fun throwFault(): Nothing {
        when (faultMode) {
            2 -> throw RuntimeException("boom")
            3 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }

    override fun listAllScheduled(ownerUserId: String): List<ScheduledMessage> {
        effects += "CALL:listAll:" + encStr209c(ownerUserId)
        return store.values.filter { it.ownerUserId == ownerUserId }
    }

    override fun listScheduled(ownerUserId: String, chatId: String): List<ScheduledMessage> {
        effects += "CALL:listScheduled:" + encStr209c(ownerUserId) + ":" + encStr209c(chatId)
        if (faulted(1)) {
            when (faultMode) {
                1 -> return emptyList()
                2, 3, 4 -> throwFault()
            }
        }
        return store.values.filter { it.ownerUserId == ownerUserId && it.chatId == chatId }
    }

    override fun getScheduled(ownerUserId: String, id: String): ScheduledMessage? {
        effects += "CALL:getScheduled:" + encStr209c(ownerUserId) + ":" + encStr209c(id)
        if (faulted(2)) {
            when (faultMode) {
                1 -> return null
                2, 3, 4 -> throwFault()
            }
        }
        val item = store[id]
        return if (item != null && item.ownerUserId == ownerUserId) item else null
    }

    override fun addScheduled(ownerUserId: String, request: ScheduledMessageRequest): ScheduledMessage? {
        effects += "CALL:addScheduled:" + encStr209c(ownerUserId) + ":" + schEncRequest209c(request)
        if (faulted(3)) {
            when (faultMode) {
                1 -> return null
                2, 3, 4 -> throwFault()
            }
        }
        val item = ScheduledMessage(
            id = "sm-" + seq++,
            chatId = request.chatId,
            peerUserId = request.peerUserId,
            text = request.text,
            sendAtMillis = request.sendAtMillis,
            createdAtMillis = nowBase,
            isGroup = request.isGroup,
            ownerUserId = ownerUserId,
            repeatIntervalMs = request.repeatIntervalMs,
            repeatCount = request.repeatCount,
            weekdaysOnly = request.weekdaysOnly,
        )
        store[item.id] = item
        return item
    }

    override fun updateScheduled(ownerUserId: String, id: String, text: String?, sendAtMillis: Long?): ScheduledMessage? {
        effects += "CALL:updateScheduled:" + encStr209c(ownerUserId) + ":" + encStr209c(id) + ":" +
            opt209c(text) + ":" + optLong209c(sendAtMillis)
        if (faulted(4)) {
            when (faultMode) {
                1 -> return null
                2, 3, 4 -> throwFault()
            }
        }
        val item = store[id]
        if (item == null || item.ownerUserId != ownerUserId) return null
        val updated = item.copy(
            text = text ?: item.text,
            sendAtMillis = sendAtMillis ?: item.sendAtMillis,
        )
        store[id] = updated
        return updated
    }

    override fun removeScheduled(ownerUserId: String, id: String): Boolean {
        effects += "CALL:removeScheduled:" + encStr209c(ownerUserId) + ":" + encStr209c(id)
        if (faulted(5)) {
            when (faultMode) {
                1 -> return false
                2, 3, 4 -> throwFault()
            }
        }
        val item = store[id]
        if (item == null || item.ownerUserId != ownerUserId) return false
        store.remove(id)
        return true
    }

    override fun clearScheduled(ownerUserId: String, chatId: String): List<String> {
        effects += "CALL:clearScheduled:" + encStr209c(ownerUserId) + ":" + encStr209c(chatId)
        if (faulted(6)) {
            when (faultMode) {
                1 -> return emptyList()
                2, 3, 4 -> throwFault()
            }
        }
        val removed = store.values
            .filter { it.ownerUserId == ownerUserId && it.chatId == chatId }
            .map { it.id }
        for (id in removed) store.remove(id)
        return removed
    }

    override fun scheduleJob(item: ScheduledMessage) {
        effects += "CALL:scheduleJob:" + encStr209c(item.id)
        if (faulted(7) && faultMode in 2..4) throwFault()
    }

    override fun cancelJob(id: String) {
        effects += "CALL:cancelJob:" + encStr209c(id)
        if (faulted(8) && faultMode in 2..4) throwFault()
    }

    override fun rescheduleJob(item: ScheduledMessage) {
        effects += "CALL:rescheduleJob:" + encStr209c(item.id)
        if (faulted(9) && faultMode in 2..4) throwFault()
    }

    override fun listReminders(ownerUserId: String): List<MessageReminder> {
        effects += "CALL:listReminders:" + encStr209c(ownerUserId)
        return emptyList()
    }

    override fun upsertReminder(reminder: MessageReminder) {
        effects += "CALL:upsertReminder:" + encStr209c(reminder.id)
    }

    override fun removeReminder(ownerUserId: String, id: String) {
        effects += "CALL:removeReminder:" + encStr209c(ownerUserId) + ":" + encStr209c(id)
    }

    override fun clearReminders(ownerUserId: String, chatId: String) {
        effects += "CALL:clearReminders:" + encStr209c(ownerUserId) + ":" + encStr209c(chatId)
    }

    override fun scheduleReminderJob(reminder: MessageReminder) {
        effects += "CALL:scheduleReminderJob:" + encStr209c(reminder.id)
    }

    override fun cancelReminderJob(id: String) {
        effects += "CALL:cancelReminderJob:" + encStr209c(id)
    }
}

private data class SchItem209c(
    val id: String,
    val chatId: String,
    val peerUserId: String = "u1",
    val text: String = "hello",
    val sendAtMillis: Long = 1700000000000L,
    val createdAtMillis: Long = 1700000000000L,
    val isGroup: Boolean = false,
    val repeatIntervalMs: Long = 0L,
    val repeatCount: Int = 0,
    val weekdaysOnly: Boolean = false,
    val status: String = "PENDING",
    val attempt: Int = 0,
    val timeZoneId: String = "",
    val idempotencyKey: String = "",
) {
    fun toItem(owner: String): ScheduledMessage = ScheduledMessage(
        id = id,
        chatId = chatId,
        peerUserId = peerUserId,
        text = text,
        sendAtMillis = sendAtMillis,
        createdAtMillis = createdAtMillis,
        isGroup = isGroup,
        ownerUserId = owner,
        repeatIntervalMs = repeatIntervalMs,
        repeatCount = repeatCount,
        weekdaysOnly = weekdaysOnly,
        status = status,
        attempt = attempt,
        timeZoneId = timeZoneId,
        idempotencyKey = idempotencyKey,
    )
}

// op kind：0 listScheduled，1 queue，2 queueAfter，3 cancel，4 cancelAll，
// 5 reschedule，6 rescheduleAfter，7 beginImmediateSend，
// 8 completeImmediateSend，9 restoreImmediateSend。
// faultTarget：0 无、1 listScheduled、2 getScheduled、3 addScheduled、
// 4 updateScheduled、5 removeScheduled、6 clearScheduled、7 scheduleJob、
// 8 cancelJob、9 rescheduleJob。faultMode：0 正常、1 空结果、2 抛 RT 带消息、
// 3 抛 RT 空消息、4 抛 CE。
private data class SchOp209c(
    val kind: Int,
    val chatId: String = "c1",
    val peerUserId: String = "u1",
    val text: String = "hello",
    val isGroup: Boolean = false,
    val sessionAvailable: Boolean = true,
    val blocked: Boolean = false,
    val sendAtMillis: Long = 1700000000000L,
    val repeatIntervalMs: Long = 0L,
    val repeatCount: Int = 0,
    val weekdaysOnly: Boolean = false,
    val delayMs: Long = 120000L,
    val id: String = "seed-1",
    val newText: String? = null,
    val ownerUserId: String = "me",
    val faultTarget: Int = 0,
    val faultMode: Int = 0,
) {
    fun toCommand(): ChatScheduleCommand = ChatScheduleCommand(
        chatId = chatId,
        peerUserId = peerUserId,
        text = text,
        isGroup = isGroup,
        sessionAvailable = sessionAvailable,
        blocked = blocked,
        sendAtMillis = sendAtMillis,
        repeatIntervalMs = repeatIntervalMs,
        repeatCount = repeatCount,
        weekdaysOnly = weekdaysOnly,
    )
}

private data class SchCase209c(
    val owner: String = "me",
    val enabled: Boolean = true,
    val nowBase: Long = 1700000000000L,
    val seedItems: List<SchItem209c> = emptyList(),
    val ops: List<SchOp209c> = emptyList(),
)

private fun schOracle209c(c: SchCase209c): String {
    val effects = mutableListOf<String>()
    val backend = FakeBackend209c(effects, c.nowBase, c.seedItems.map { it.toItem(c.owner) })
    val coordinator = ConversationScheduleCoordinator(
        ownerUserId = { c.owner },
        backend = backend,
        now = { c.nowBase },
        reminderId = { "mr-x" },
    )
    // 只镜像控制器层（准入门 + 结果映射），协调器用真实实现。
    fun toRejection(f: ConversationScheduleFailure): ChatScheduleRejection = when (f) {
        ConversationScheduleFailure.SESSION_MISSING -> ChatScheduleRejection.SESSION_MISSING
        ConversationScheduleFailure.INVALID_REQUEST -> ChatScheduleRejection.INVALID_REQUEST
        ConversationScheduleFailure.LIMIT_REACHED -> ChatScheduleRejection.LIMIT_REACHED
        ConversationScheduleFailure.NOT_FOUND -> ChatScheduleRejection.NOT_FOUND
        ConversationScheduleFailure.STORAGE -> ChatScheduleRejection.STORAGE
    }
    fun applied(chatId: String) = ChatScheduleMutationOutcome.Applied(
        scheduledMessages = coordinator.listScheduled(chatId),
    )
    fun rejected(reason: ChatScheduleRejection) = ChatScheduleMutationOutcome.Rejected(reason)
    fun queue(command: ChatScheduleCommand): ChatScheduleMutationOutcome {
        if (!c.enabled) return rejected(ChatScheduleRejection.DISABLED)
        if (!command.sessionAvailable) return rejected(ChatScheduleRejection.SESSION_MISSING)
        if (command.blocked) return rejected(ChatScheduleRejection.BLOCKED)
        if (command.chatId.isBlank() || !ScheduledMessagePolicy.isValidText(command.text)) {
            return rejected(ChatScheduleRejection.INVALID_REQUEST)
        }
        return when (
            val result = coordinator.queue(
                ScheduledMessageRequest(
                    chatId = command.chatId,
                    peerUserId = if (command.isGroup) "" else command.peerUserId,
                    text = command.text,
                    sendAtMillis = command.sendAtMillis,
                    isGroup = command.isGroup,
                    repeatIntervalMs = command.repeatIntervalMs,
                    repeatCount = command.repeatCount,
                    weekdaysOnly = command.weekdaysOnly,
                ),
            )
        ) {
            is ConversationScheduleResult.Success -> ChatScheduleMutationOutcome.Applied(
                scheduledMessages = coordinator.listScheduled(command.chatId),
                effectiveAtMillis = result.value.sendAtMillis,
            )
            is ConversationScheduleResult.Failure -> rejected(toRejection(result.reason))
        }
    }
    fun cancel(chatId: String, id: String): ChatScheduleMutationOutcome =
        when (val result = coordinator.cancel(id)) {
            is ConversationScheduleResult.Success -> applied(chatId)
            is ConversationScheduleResult.Failure -> rejected(toRejection(result.reason))
        }
    fun cancelAll(chatId: String): ChatScheduleMutationOutcome =
        when (val result = coordinator.cancelAll(chatId)) {
            is ConversationScheduleResult.Success -> applied(chatId)
            is ConversationScheduleResult.Failure -> rejected(toRejection(result.reason))
        }
    fun reschedule(chatId: String, id: String, sendAtMillis: Long, text: String?): ChatScheduleMutationOutcome =
        when (val result = coordinator.reschedule(id, sendAtMillis, text)) {
            is ConversationScheduleResult.Success -> ChatScheduleMutationOutcome.Applied(
                scheduledMessages = coordinator.listScheduled(chatId),
                effectiveAtMillis = result.value.sendAtMillis,
            )
            is ConversationScheduleResult.Failure -> rejected(toRejection(result.reason))
        }
    fun beginImmediateSend(id: String): ChatScheduleImmediateOutcome =
        when (val result = coordinator.beginImmediateSend(id)) {
            is ConversationScheduleResult.Success -> ChatScheduleImmediateOutcome.Ready(result.value)
            is ConversationScheduleResult.Failure -> ChatScheduleImmediateOutcome.Rejected(toRejection(result.reason))
        }
    fun storeSnap(): String = backend.store.values.joinToString(";") { schEncItem209c(it) }
    for (op in c.ops) {
        backend.faultTarget = op.faultTarget
        backend.faultMode = op.faultMode
        try {
            when (op.kind) {
                0 -> effects += "OUT:list:" + coordinator.listScheduled(op.chatId).joinToString(";") { schEncItem209c(it) }
                1 -> effects += "OUT:mut:" + schEncMutOutcome209c(queue(op.toCommand()))
                2 -> effects += "OUT:mut:" + schEncMutOutcome209c(
                    queue(
                        op.toCommand().copy(
                            sendAtMillis = c.nowBase + op.delayMs.coerceAtLeast(ScheduledMessagePolicy.MIN_DELAY_MS),
                        ),
                    ),
                )
                3 -> effects += "OUT:mut:" + schEncMutOutcome209c(cancel(op.chatId, op.id))
                4 -> effects += "OUT:mut:" + schEncMutOutcome209c(cancelAll(op.chatId))
                5 -> effects += "OUT:mut:" + schEncMutOutcome209c(reschedule(op.chatId, op.id, op.sendAtMillis, op.newText))
                6 -> effects += "OUT:mut:" + schEncMutOutcome209c(
                    reschedule(
                        op.chatId,
                        op.id,
                        c.nowBase + op.delayMs.coerceAtLeast(ScheduledMessagePolicy.MIN_DELAY_MS),
                        op.newText,
                    ),
                )
                7 -> effects += "OUT:imm:" + schEncImmOutcome209c(beginImmediateSend(op.id))
                8 -> {
                    coordinator.completeImmediateSend(op.ownerUserId, op.id)
                    effects += "OUT:unit"
                }
                else -> {
                    coordinator.restoreImmediateSend(op.ownerUserId, op.id)
                    effects += "OUT:unit"
                }
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        effects += "FIN:" + storeSnap()
    }
    return effects.joinToString(",")
}

private fun schProd209c(c: SchCase209c): String {
    val effects = mutableListOf<String>()
    val backend = FakeBackend209c(effects, c.nowBase, c.seedItems.map { it.toItem(c.owner) })
    val coordinator = ConversationScheduleCoordinator(
        ownerUserId = { c.owner },
        backend = backend,
        now = { c.nowBase },
        reminderId = { "mr-x" },
    )
    val controller = ChatScheduleController(
        coordinator,
        scheduledMessagesEnabled = { c.enabled },
        clock = { c.nowBase },
    )
    fun storeSnap(): String = backend.store.values.joinToString(";") { schEncItem209c(it) }
    for (op in c.ops) {
        backend.faultTarget = op.faultTarget
        backend.faultMode = op.faultMode
        try {
            when (op.kind) {
                0 -> effects += "OUT:list:" + controller.listScheduled(op.chatId).joinToString(";") { schEncItem209c(it) }
                1 -> effects += "OUT:mut:" + schEncMutOutcome209c(controller.queue(op.toCommand()))
                2 -> effects += "OUT:mut:" + schEncMutOutcome209c(controller.queueAfter(op.toCommand(), op.delayMs))
                3 -> effects += "OUT:mut:" + schEncMutOutcome209c(controller.cancel(op.chatId, op.id))
                4 -> effects += "OUT:mut:" + schEncMutOutcome209c(controller.cancelAll(op.chatId))
                5 -> effects += "OUT:mut:" + schEncMutOutcome209c(controller.reschedule(op.chatId, op.id, op.sendAtMillis, op.newText))
                6 -> effects += "OUT:mut:" + schEncMutOutcome209c(controller.rescheduleAfter(op.chatId, op.id, op.delayMs, op.newText))
                7 -> effects += "OUT:imm:" + schEncImmOutcome209c(controller.beginImmediateSend(op.id))
                8 -> {
                    controller.completeImmediateSend(op.ownerUserId, op.id)
                    effects += "OUT:unit"
                }
                else -> {
                    controller.restoreImmediateSend(op.ownerUserId, op.id)
                    effects += "OUT:unit"
                }
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        effects += "FIN:" + storeSnap()
    }
    return effects.joinToString(",")
}

private val SCH_CHAT_IDS209c = listOf("", " ", "c1", "c2", "x\ty")
private val SCH_TEXTS209c = listOf("hello", "", " ", "  spaced  ", "x".repeat(4001), "ünïcödé", "a&b")
private val SCH_IDS209c = listOf("seed-1", "seed-2", "sm-1000", "sm-1001", "nope", "", " ")
private val SCH_SEND_ATS209c = listOf(0L, 1700000000000L, -5L, 1700003600000L, 60000L)
private val SCH_DELAYS209c = listOf(-5000L, 0L, 59000L, 60000L, 120000L, 700000000L)

private fun randomSchItem209c(r: Random, n: Int): SchItem209c = SchItem209c(
    id = "seed-" + n,
    chatId = SCH_CHAT_IDS209c[r.nextInt(SCH_CHAT_IDS209c.size)],
    peerUserId = listOf("u1", "", " ", "p1")[r.nextInt(4)],
    text = SCH_TEXTS209c[r.nextInt(SCH_TEXTS209c.size)],
    sendAtMillis = SCH_SEND_ATS209c[r.nextInt(SCH_SEND_ATS209c.size)],
    createdAtMillis = 1700000000000L,
    isGroup = r.nextInt(2) == 0,
    repeatIntervalMs = listOf(0L, 60000L, -1L)[r.nextInt(3)],
    repeatCount = listOf(0, 1, 3)[r.nextInt(3)],
    weekdaysOnly = r.nextInt(2) == 0,
)

private fun randomSchOp209c(r: Random): SchOp209c {
    val kind = listOf(0, 1, 1, 2, 3, 4, 5, 6, 7, 8, 9)[r.nextInt(11)]
    return SchOp209c(
        kind = kind,
        chatId = SCH_CHAT_IDS209c[r.nextInt(SCH_CHAT_IDS209c.size)],
        peerUserId = listOf("u1", "", " ", "p1")[r.nextInt(4)],
        text = SCH_TEXTS209c[r.nextInt(SCH_TEXTS209c.size)],
        isGroup = r.nextInt(2) == 0,
        sessionAvailable = r.nextInt(5) != 0,
        blocked = r.nextInt(5) == 0,
        sendAtMillis = SCH_SEND_ATS209c[r.nextInt(SCH_SEND_ATS209c.size)],
        repeatIntervalMs = listOf(0L, 60000L, -1L)[r.nextInt(3)],
        repeatCount = listOf(0, 1, 3)[r.nextInt(3)],
        weekdaysOnly = r.nextInt(2) == 0,
        delayMs = SCH_DELAYS209c[r.nextInt(SCH_DELAYS209c.size)],
        id = SCH_IDS209c[r.nextInt(SCH_IDS209c.size)],
        newText = listOf<String?>(null, "new text", "", "  ")[r.nextInt(4)],
        ownerUserId = listOf("me", "", " ", "other")[r.nextInt(4)],
        faultTarget = if (r.nextInt(3) == 0) r.nextInt(10) else 0,
        faultMode = r.nextInt(5),
    )
}

private fun randomSchCase209c(r: Random): SchCase209c = SchCase209c(
    owner = listOf("me", "", " ", "other")[r.nextInt(4)],
    enabled = r.nextInt(5) != 0,
    nowBase = listOf(1700000000000L, 0L, 1000000000000L)[r.nextInt(3)],
    seedItems = List(r.nextInt(0, 4)) { randomSchItem209c(r, it + 1) },
    ops = List(r.nextInt(0, 9)) { randomSchOp209c(r) },
)

private fun boundarySchCase209c(r: Random): SchCase209c {
    val base = SchCase209c()
    val oneSeed = listOf(SchItem209c(id = "seed-1", chatId = "c1"))
    val limitSeeds = List(56) { SchItem209c(id = "seed-" + (it + 1), chatId = "c1") }
    val limit55 = List(55) { SchItem209c(id = "seed-" + (it + 1), chatId = "c1") }
    return when (r.nextInt(38)) {
        0 -> base.copy(ops = listOf(SchOp209c(0, chatId = "c1")))
        1 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(0, chatId = "c1")))
        2 -> base.copy(ops = listOf(SchOp209c(0, chatId = "")), owner = "")
        3 -> base.copy(ops = listOf(SchOp209c(1)))
        4 -> base.copy(ops = listOf(SchOp209c(1)), enabled = false)
        5 -> base.copy(ops = listOf(SchOp209c(1, sessionAvailable = false)))
        6 -> base.copy(ops = listOf(SchOp209c(1, blocked = true)))
        7 -> base.copy(ops = listOf(SchOp209c(1, chatId = "")))
        8 -> base.copy(ops = listOf(SchOp209c(1, text = "")))
        9 -> base.copy(ops = listOf(SchOp209c(1, text = " ")))
        10 -> base.copy(ops = listOf(SchOp209c(1, isGroup = true, peerUserId = "u1")))
        11 -> base.copy(seedItems = limitSeeds, ops = listOf(SchOp209c(1)))
        12 -> base.copy(seedItems = limit55, ops = listOf(SchOp209c(1), SchOp209c(1)))
        13 -> base.copy(ops = listOf(SchOp209c(1, faultTarget = 3, faultMode = 1)))
        14 -> base.copy(ops = listOf(SchOp209c(1, faultTarget = 3, faultMode = 2)))
        15 -> base.copy(ops = listOf(SchOp209c(1, faultTarget = 7, faultMode = 2)))
        16 -> base.copy(ops = listOf(SchOp209c(1, faultTarget = 1, faultMode = 2)))
        17 -> base.copy(ops = listOf(SchOp209c(2, delayMs = -5000L)))
        18 -> base.copy(ops = listOf(SchOp209c(2, delayMs = 700000000L)))
        19 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(3, chatId = "c1", id = "seed-1")))
        20 -> base.copy(ops = listOf(SchOp209c(3, chatId = "c1", id = "nope")))
        21 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(3, chatId = "c1", id = "seed-1", faultTarget = 5, faultMode = 1)))
        22 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(3, chatId = "c1", id = "seed-1", faultTarget = 5, faultMode = 2)))
        23 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(3, chatId = "c1", id = "seed-1", faultTarget = 8, faultMode = 2)))
        24 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(4, chatId = "c1")))
        25 -> base.copy(ops = listOf(SchOp209c(4, chatId = "c1", faultTarget = 6, faultMode = 2)))
        26 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(5, chatId = "c1", id = "seed-1", sendAtMillis = 1700007200000L, newText = "new text")))
        27 -> base.copy(ops = listOf(SchOp209c(5, chatId = "c1", id = "nope", sendAtMillis = 1700007200000L)))
        28 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(5, chatId = "c1", id = "seed-1", sendAtMillis = 1700007200000L, faultTarget = 4, faultMode = 1)))
        29 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(5, chatId = "c1", id = "seed-1", sendAtMillis = 1700007200000L, faultTarget = 4, faultMode = 2)))
        30 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(5, chatId = "c1", id = "seed-1", sendAtMillis = 1700007200000L, faultTarget = 9, faultMode = 2)))
        31 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(6, chatId = "c1", id = "seed-1", delayMs = 120000L, newText = null)))
        32 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(7, id = "seed-1")))
        33 -> base.copy(ops = listOf(SchOp209c(7, id = "nope")))
        34 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(7, id = "seed-1", faultTarget = 8, faultMode = 2)))
        35 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(8, id = "seed-1", ownerUserId = "me")))
        36 -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(9, id = "seed-1", ownerUserId = "me")))
        else -> base.copy(seedItems = oneSeed, ops = listOf(SchOp209c(8, id = "seed-1", ownerUserId = "")))
    }
}

// ---------- tests ----------

class ChatScheduleControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(209305)
        repeat(300) {
            val c = randomSchCase209c(r)
            assertEquals(schOracle209c(c), schProd209c(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(209306)
        repeat(200) {
            val c = boundarySchCase209c(r)
            assertEquals(schOracle209c(c), schProd209c(c))
        }
    }

    @Test
    fun pinnedCases() {
        val oneSeed = listOf(SchItem209c(id = "seed-1", chatId = "c1"))
        val limitSeeds = List(56) { SchItem209c(id = "seed-" + (it + 1), chatId = "c1") }
        val cases = listOf(
            SchCase209c(ops = listOf(SchOp209c(0, chatId = "c1"))),
            SchCase209c(seedItems = oneSeed, ops = listOf(SchOp209c(0, chatId = "c1"))),
            SchCase209c(ops = listOf(SchOp209c(1))),
            SchCase209c(ops = listOf(SchOp209c(1)), enabled = false),
            SchCase209c(ops = listOf(SchOp209c(1, blocked = true))),
            SchCase209c(seedItems = limitSeeds, ops = listOf(SchOp209c(1))),
            SchCase209c(ops = listOf(SchOp209c(1, faultTarget = 7, faultMode = 2))),
            SchCase209c(ops = listOf(SchOp209c(2, delayMs = -5000L))),
            SchCase209c(seedItems = oneSeed, ops = listOf(SchOp209c(3, chatId = "c1", id = "seed-1"))),
            SchCase209c(seedItems = oneSeed, ops = listOf(SchOp209c(5, chatId = "c1", id = "seed-1", sendAtMillis = 1700007200000L, newText = "new text"))),
            SchCase209c(seedItems = oneSeed, ops = listOf(SchOp209c(5, chatId = "c1", id = "seed-1", sendAtMillis = 1700007200000L, faultTarget = 9, faultMode = 2))),
            SchCase209c(seedItems = oneSeed, ops = listOf(SchOp209c(7, id = "seed-1"))),
            SchCase209c(seedItems = oneSeed, ops = listOf(SchOp209c(8, id = "seed-1", ownerUserId = "me"))),
            SchCase209c(seedItems = oneSeed, ops = listOf(SchOp209c(9, id = "seed-1", ownerUserId = "me"))),
            SchCase209c(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(schOracle209c(c), schProd209c(c))
        }
    }
}
