package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.PinnedMessageDto
import com.maodouchat.core.model.PinnedMessagesListResponse
import com.maodouchat.core.model.StarMessageResponse
import com.maodouchat.core.model.TogglePinResponse
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr203a(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun encIds203a(ids: Collection<String>): String =
    ids.joinToString(";") { encStr203a(it) }

private fun bit203a(b: Boolean): String = if (b) "1" else "0"

private fun warn203a(w: String?): String = w?.let { encStr203a(it) } ?: "~"

private data class PinMsg203a(
    val id: String,
    val type: MessageType = MessageType.TEXT,
    val starred: Boolean = false,
)

// op kind: 0 refresh，1 togglePin，2 togglePinMessages，3 toggleStar，4 toggleStarMessagesBatch。
// gw：单次 op（0/1/3）0 成功 1 失败 2 抛错；批量 op（2/4）0 全成功 1 全失败
// 2 首个成功其余失败 3 首个失败其余成功 4 首个调用抛错。
private data class PinOp203a(
    val kind: Int,
    val id: String = "",
    val ids: List<String> = emptyList(),
    val shouldPin: Boolean = false,
    val shouldStar: Boolean = false,
    val gw: Int = 0,
    val pinned: Boolean = false,
    val expectedUserId: String = "",
)

private data class PinCase203a(
    val pinEnabled: Boolean = true,
    val starEnabled: Boolean = true,
    val secretBlock: Boolean = true,
    val owner: String = "me",
    val token: String = "token",
    val chatId: String = "c1",
    val sessionActive: Boolean = true,
    val isGroup: Boolean = false,
    val myRole: String? = null,
    val secret: Boolean? = null,
    val initToggling: Boolean = false,
    val messages: List<PinMsg203a> = emptyList(),
    val initPins: List<String> = emptyList(),
    val ops: List<PinOp203a> = emptyList(),
)

private fun encPinState203a(
    msgs: List<PinMsg203a>,
    pins: List<String>,
    toggling: Boolean,
    warn: String?,
): String = msgs.joinToString(",") { encStr203a(it.id) + ":" + it.type.name + ":" + bit203a(it.starred) } +
    "|" + encIds203a(pins) + "|" + bit203a(toggling) + "|" + warn203a(warn)

private fun pinText203a(name: String, args: List<Any>): String =
    "[" + name + (if (args.isEmpty()) "" else ":" + args.joinToString(",")) + "]"

private fun pinCanPin203a(isGroup: Boolean, role: String?, type: MessageType): Boolean {
    if (!MessagePinPolicy.isPinnableType(type)) return false
    if (!isGroup) return true
    val r = role?.uppercase().orEmpty()
    return r == "OWNER" || r == "ADMIN"
}

private fun pinGwOutcome203a(gw: Int, batch: Boolean, idx: Int): Int {
    if (!batch) return gw
    return when (gw) {
        0 -> 0
        1 -> 1
        2 -> if (idx == 0) 0 else 1
        3 -> if (idx == 0) 1 else 0
        else -> if (idx == 0) 2 else 0
    }
}

private fun pinOracle203a(c: PinCase203a): String {
    val effects = mutableListOf<String>()
    var msgs = c.messages
    var pins = c.initPins
    var toggling = c.initToggling
    var warn: String? = null
    // 网关侧的服务端状态：置顶列表与星标表，随成功调用演进。
    var gwPins = c.initPins.toMutableList()
    val gwStars = c.messages.associate { it.id to it.starred }.toMutableMap()
    fun snap(prefix: String) {
        effects += prefix + ":" + encPinState203a(msgs, pins, toggling, warn)
    }
    fun warnMsg(name: String, vararg args: Any) {
        warn = pinText203a(name, args.toList())
        snap("ST")
    }
    fun replaceMessage(msg: PinMsg203a, warning: String? = null) {
        msgs = msgs.map { if (it.id == msg.id) msg else it }
        if (warning != null) warn = warning
        snap("ST")
    }
    fun canStar(): Boolean {
        if (!c.starEnabled) {
            warnMsg("STARRING_DISABLED")
            return false
        }
        if (c.secret == true && c.secretBlock) {
            warnMsg("SECRET_STAR_BLOCKED")
            return false
        }
        return true
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                val targetChatId = c.chatId
                if (op.expectedUserId.isBlank() || targetChatId.isBlank() || !c.sessionActive) {
                    // fall through to FIN
                } else if (c.token.isBlank()) {
                    // fall through to FIN
                } else {
                    when (pinGwOutcome203a(op.gw, false, 0)) {
                        0 -> {
                            if (c.sessionActive) {
                                pins = gwPins.toList()
                                snap("ST")
                            }
                        }
                        1 -> Unit
                        else -> effects += "THROW:RuntimeException"
                    }
                }
            }
            1 -> {
                if (!c.pinEnabled) {
                    warnMsg("FEATURE_DISABLED_BY_ADMIN")
                } else {
                    val message = msgs.find { it.id == op.id }
                    if (message == null) {
                        // fall through to FIN
                    } else if (c.token.isBlank() || c.owner.isBlank() || c.chatId.isBlank()) {
                        warnMsg("SESSION_EXPIRED")
                    } else if (!pinCanPin203a(c.isGroup, c.myRole, message.type)) {
                        warnMsg("PIN_FORBIDDEN")
                    } else if (MessagePinPolicy.wouldExceedLimit(pins.size, pins.any { it == op.id })) {
                        warnMsg("PIN_LIMIT", MessagePinPolicy.MAX_PINS)
                    } else if (toggling) {
                        // fall through to FIN
                    } else {
                        toggling = true
                        snap("ST")
                        if (!c.sessionActive) {
                            toggling = false
                            warn = pinText203a("SESSION_EXPIRED", emptyList())
                            snap("ST")
                        } else {
                            when (pinGwOutcome203a(op.gw, false, 0)) {
                                0 -> {
                                    if (op.pinned) {
                                        if (op.id !in gwPins) gwPins += op.id
                                    } else {
                                        gwPins -= op.id
                                    }
                                    if (c.sessionActive) {
                                        toggling = false
                                        pins = gwPins.toList()
                                        warn = pinText203a(
                                            if (op.pinned) "PIN_SUCCESS" else "UNPIN_SUCCESS",
                                            emptyList(),
                                        )
                                        snap("ST")
                                    }
                                }
                                1 -> {
                                    toggling = false
                                    warn = "boom"
                                    snap("ST")
                                }
                                else -> {
                                    toggling = false
                                    warn = "boom"
                                    snap("ST")
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                if (!c.pinEnabled) {
                    warnMsg("FEATURE_DISABLED_BY_ADMIN")
                } else if (c.token.isBlank() || c.owner.isBlank() || c.chatId.isBlank()) {
                    warnMsg("SESSION_EXPIRED")
                } else if (toggling) {
                    // fall through to FIN
                } else {
                    val pinnedIds = pins.toSet()
                    val targetIds = op.ids.filter { (it in pinnedIds) != op.shouldPin }
                    if (targetIds.isEmpty()) {
                        // fall through to FIN
                    } else {
                        val targets = msgs.filter { it.id in targetIds }
                        if (targets.size != targetIds.size) {
                            warnMsg("PIN_FAILED")
                        } else {
                            val pinnable = targets.filter { pinCanPin203a(c.isGroup, c.myRole, it.type) }
                            if (pinnable.isEmpty()) {
                                warnMsg("PIN_FORBIDDEN")
                            } else {
                                val effectiveIds = pinnable.map { it.id }
                                val overLimit = op.shouldPin &&
                                    pinnedIds.size + effectiveIds.count { it !in pinnedIds } >
                                    MessagePinPolicy.MAX_PINS
                                if (overLimit) {
                                    warnMsg("PIN_LIMIT", MessagePinPolicy.MAX_PINS)
                                } else {
                                    toggling = true
                                    snap("ST")
                                    pinBatch203a(
                                        c, op, effectiveIds, msgs, pins,
                                        gwPins, gwStars,
                                        { m, p, t, w -> msgs = m; pins = p; toggling = t; warn = w; snap("ST") },
                                        effects,
                                    ).also { r ->
                                        msgs = r.first
                                        pins = r.second
                                        gwPins = r.third
                                    }
                                }
                            }
                        }
                    }
                }
            }
            3 -> {
                if (!canStar()) {
                    // warned inside canStar
                } else {
                    val original = msgs.find { it.id == op.id }
                    if (original == null) {
                        // fall through to FIN
                    } else if (c.token.isBlank() || c.owner.isBlank()) {
                        warnMsg("SESSION_EXPIRED")
                    } else {
                        replaceMessage(original.copy(starred = !original.starred))
                        if (!c.sessionActive) {
                            replaceMessage(original, pinText203a("SESSION_EXPIRED", emptyList()))
                        } else {
                            when (pinGwOutcome203a(op.gw, false, 0)) {
                                0 -> {
                                    if (c.sessionActive) {
                                        val cur = gwStars.getOrDefault(op.id, false)
                                        gwStars[op.id] = !cur
                                        val updated = original.copy(starred = !cur)
                                        replaceMessage(
                                            updated,
                                            pinText203a(if (!cur) "STARRED" else "UNSTARRED", emptyList()),
                                        )
                                        effects += "PERSIST:" + encStr203a(updated.id) + ":" + bit203a(updated.starred)
                                    }
                                }
                                1 -> replaceMessage(original, "boom")
                                else -> effects += "THROW:RuntimeException"
                            }
                        }
                    }
                }
            }
            4 -> {
                if (!canStar()) {
                    // warned inside canStar
                } else if (c.token.isBlank() || c.owner.isBlank()) {
                    warnMsg("SESSION_EXPIRED")
                } else {
                    val idSet = op.ids.toSet()
                    val targets = msgs.filter { it.id in idSet && it.starred != op.shouldStar }
                    if (targets.isEmpty()) {
                        // fall through to FIN
                    } else {
                        val targetIds = targets.map { it.id }.toSet()
                        msgs = msgs.map { if (it.id in targetIds) it.copy(starred = op.shouldStar) else it }
                        snap("ST")
                        var failed = 0
                        var aborted = false
                        for ((idx, message) in targets.withIndex()) {
                            if (!c.sessionActive) {
                                warnMsg("SESSION_EXPIRED")
                                aborted = true
                                break
                            }
                            when (pinGwOutcome203a(op.gw, true, idx)) {
                                0 -> {
                                    val cur = gwStars.getOrDefault(message.id, false)
                                    gwStars[message.id] = !cur
                                    val updated = message.copy(starred = !cur)
                                    replaceMessage(updated)
                                    effects += "PERSIST:" + encStr203a(updated.id) + ":" + bit203a(updated.starred)
                                }
                                1 -> {
                                    failed++
                                    replaceMessage(message)
                                }
                                else -> {
                                    effects += "THROW:RuntimeException"
                                    aborted = true
                                    break
                                }
                            }
                        }
                        if (!aborted) {
                            warnMsg(if (failed == 0) {
                                if (op.shouldStar) "STARRED" else "UNSTARRED"
                            } else {
                                "STAR_FAILED"
                            })
                        }
                    }
                }
            }
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

// 批量置顶的 launch 体（oracle 侧）：返回更新后的 msgs/pins/gwPins。
private fun pinBatch203a(
    c: PinCase203a,
    op: PinOp203a,
    effectiveIds: List<String>,
    msgsIn: List<PinMsg203a>,
    pinsIn: List<String>,
    gwPinsIn: MutableList<String>,
    gwStars: MutableMap<String, Boolean>,
    apply: (List<PinMsg203a>, List<String>, Boolean, String?) -> Unit,
    effects: MutableList<String>,
): Triple<List<PinMsg203a>, List<String>, MutableList<String>> {
    var currentPins = pinsIn
    var successCount = 0
    var sessionExpired = false
    var thrown = false
    var gwPins = gwPinsIn
    for ((idx, targetId) in effectiveIds.withIndex()) {
        if (!c.sessionActive) {
            sessionExpired = true
            break
        }
        when (pinGwOutcome203a(op.gw, true, idx)) {
            0 -> {
                successCount++
                if (op.shouldPin) {
                    if (targetId !in gwPins) gwPins += targetId
                } else {
                    gwPins -= targetId
                }
                currentPins = gwPins.toList()
            }
            1 -> Unit
            else -> {
                apply(msgsIn, pinsIn, false, "boom")
                thrown = true
                break
            }
        }
    }
    if (thrown) return Triple(msgsIn, pinsIn, gwPins)
    val failedCount = effectiveIds.size - successCount
    val warning = when {
        sessionExpired -> pinText203a("SESSION_EXPIRED", emptyList())
        failedCount > 0 && successCount > 0 -> pinText203a(
            if (op.shouldPin) "BATCH_PIN_PARTIAL" else "BATCH_UNPIN_PARTIAL",
            listOf(successCount, failedCount),
        )
        successCount > 0 -> pinText203a(
            if (op.shouldPin) "BATCH_PIN_SUCCESS" else "BATCH_UNPIN_SUCCESS",
            listOf(successCount),
        )
        else -> pinText203a("PIN_FAILED", emptyList())
    }
    apply(msgsIn, currentPins, false, warning)
    return Triple(msgsIn, currentPins, gwPins)
}

private fun pinProd203a(c: PinCase203a): String {
    val effects = mutableListOf<String>()
    var live = ChatDetailUiState(
        messages = c.messages.map {
            Message(id = it.id, chatId = c.chatId, senderId = "me", content = "hi", type = it.type, starred = it.starred)
        },
        pinnedMessages = c.initPins.map {
            PinnedMessageDto(chatId = c.chatId, messageId = it, pinnedBy = c.owner, pinnedAt = 0L)
        },
        isTogglingPin = c.initToggling,
        chatIsGroup = c.isGroup,
        myMemberRole = c.myRole,
        isSecretChat = c.secret,
    )
    fun snap(prefix: String) {
        val msgs = live.messages.map { PinMsg203a(it.id, it.type, it.starred) }
        effects += prefix + ":" + encPinState203a(
            msgs,
            live.pinnedMessages.map { it.messageId },
            live.isTogglingPin,
            live.groupEncryptionWarning,
        )
    }
    var gwPins = c.initPins.toMutableList()
    val gwStars = c.messages.associate { it.id to it.starred }.toMutableMap()
    var curGw = 0
    var curPinned = false
    var curBatch = false
    var callIdx = 0
    val handler = CoroutineExceptionHandler { _, e ->
        effects += "THROW:" + e.javaClass.simpleName
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)
    fun drain() {
        runBlocking {
            for (child in scope.coroutineContext[Job]?.children ?: emptySequence()) {
                runCatching { child.join() }
            }
        }
    }
    fun pinDto(id: String) = PinnedMessageDto(chatId = c.chatId, messageId = id, pinnedBy = c.owner, pinnedAt = 0L)
    val gateway = object : PinStarGateway {
        override suspend fun pinned(token: String, chatId: String): Result<PinnedMessagesListResponse> {
            return when (pinGwOutcome203a(curGw, false, 0)) {
                0 -> Result.success(PinnedMessagesListResponse(chatId = c.chatId, pins = gwPins.map { pinDto(it) }))
                1 -> Result.failure(RuntimeException("boom"))
                else -> throw RuntimeException("boom")
            }
        }

        override suspend fun togglePin(token: String, chatId: String, messageId: String): Result<TogglePinResponse> {
            val idx = callIdx++
            return when (pinGwOutcome203a(curGw, curBatch, idx)) {
                0 -> {
                    if (curPinned) {
                        if (messageId !in gwPins) gwPins += messageId
                    } else {
                        gwPins -= messageId
                    }
                    Result.success(TogglePinResponse(pinned = curPinned, pins = gwPins.map { pinDto(it) }))
                }
                1 -> Result.failure(RuntimeException("boom"))
                else -> throw RuntimeException("boom")
            }
        }

        override suspend fun toggleStar(token: String, messageId: String): Result<StarMessageResponse> {
            val idx = callIdx++
            return when (pinGwOutcome203a(curGw, curBatch, idx)) {
                0 -> {
                    val cur = gwStars.getOrDefault(messageId, false)
                    gwStars[messageId] = !cur
                    Result.success(StarMessageResponse(starred = !cur))
                }
                1 -> Result.failure(RuntimeException("boom"))
                else -> throw RuntimeException("boom")
            }
        }
    }
    val controller = ChatPinStarController(
        messagePinEnabled = { c.pinEnabled },
        messageStarringEnabled = { c.starEnabled },
        secretStarBlockEnabled = { c.secretBlock },
        pinStarGateway = gateway,
        scope = scope,
        chatId = { c.chatId },
        ownerUserId = { c.owner },
        token = { c.token },
        sessionActive = { c.sessionActive },
        currentState = { live },
        updateState = { transform ->
            live = transform(live)
            snap("ST")
        },
        persistMessage = { msg ->
            effects += "PERSIST:" + encStr203a(msg.id) + ":" + bit203a(msg.starred)
        },
        text = { message, args -> pinText203a(message.name, args.toList()) },
    )
    for (op in c.ops) {
        curGw = op.gw
        curBatch = op.kind == 2 || op.kind == 4
        callIdx = 0
        curPinned = when (op.kind) {
            1 -> op.pinned
            2 -> op.shouldPin
            else -> false
        }
        try {
            when (op.kind) {
                0 -> runBlocking { controller.refreshPinnedMessages(op.expectedUserId) }
                1 -> controller.togglePinMessage(op.id)
                2 -> controller.togglePinMessages(op.ids, op.shouldPin)
                3 -> controller.toggleStarMessage(op.id)
                4 -> controller.toggleStarMessagesBatch(op.ids, op.shouldStar)
                else -> throw IllegalArgumentException("unknown op ${op.kind}")
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val PIN_IDS203a = listOf(
    "", " ", "m1", "m2", "m3", "p1", "x\ty", "ünïcödé", "10", "2",
)

private val PIN_TYPES203a = listOf(
    MessageType.TEXT, MessageType.MARKDOWN, MessageType.IMAGE, MessageType.GIF,
    MessageType.STICKER, MessageType.LOCATION, MessageType.VOICE, MessageType.VIDEO,
    MessageType.FILE, MessageType.NUDGE, MessageType.SK_DIST, MessageType.SYSTEM,
    MessageType.REVOKED,
)

private val PIN_ROLES203a: List<String?> = listOf(null, "", "OWNER", "ADMIN", "MEMBER", "owner", "admin", "Member")

private fun randomPinMsg203a(r: Random, id: String): PinMsg203a =
    PinMsg203a(id, PIN_TYPES203a[r.nextInt(PIN_TYPES203a.size)], r.nextBoolean())

private fun randomPinOp203a(r: Random): PinOp203a {
    val id = PIN_IDS203a[r.nextInt(PIN_IDS203a.size)]
    return when (r.nextInt(5)) {
        0 -> PinOp203a(0, expectedUserId = listOf("me", "u1", "", " ")[r.nextInt(4)], gw = r.nextInt(3))
        1 -> PinOp203a(1, id = id, gw = r.nextInt(3), pinned = r.nextBoolean())
        2 -> PinOp203a(
            2,
            ids = PIN_IDS203a.shuffled(r).take(r.nextInt(0, 4)),
            shouldPin = r.nextBoolean(),
            gw = r.nextInt(5),
        )
        3 -> PinOp203a(3, id = id, gw = r.nextInt(3))
        else -> PinOp203a(
            4,
            ids = PIN_IDS203a.shuffled(r).take(r.nextInt(0, 4)),
            shouldStar = r.nextBoolean(),
            gw = r.nextInt(5),
        )
    }
}

private fun randomPinCase203a(r: Random): PinCase203a {
    val msgIds = PIN_IDS203a.shuffled(r).take(r.nextInt(0, 5))
    val messages = msgIds.map { randomPinMsg203a(r, it) }
    val pinPool = (msgIds + listOf("ghost", "m1")).shuffled(r)
    return PinCase203a(
        pinEnabled = r.nextBoolean(),
        starEnabled = r.nextBoolean(),
        secretBlock = r.nextBoolean(),
        owner = listOf("", " ", "me", "u1")[r.nextInt(4)],
        token = listOf("", " ", "token")[r.nextInt(3)],
        chatId = listOf("", " ", "c1")[r.nextInt(3)],
        sessionActive = r.nextBoolean(),
        isGroup = r.nextBoolean(),
        myRole = PIN_ROLES203a[r.nextInt(PIN_ROLES203a.size)],
        secret = listOf<Boolean?>(null, false, true)[r.nextInt(3)],
        initToggling = r.nextInt(4) == 0,
        messages = messages,
        initPins = pinPool.take(r.nextInt(0, 4)),
        ops = List(r.nextInt(0, 9)) { randomPinOp203a(r) },
    )
}

private fun boundaryPinCase203a(r: Random): PinCase203a {
    val m1 = PinMsg203a("m1")
    val m2 = PinMsg203a("m2", MessageType.IMAGE)
    val key = PinMsg203a("key", MessageType.SK_DIST)
    val base = PinCase203a(messages = listOf(m1, m2))
    val cases = listOf(
        base.copy(ops = listOf(PinOp203a(1, id = "m1", gw = 0, pinned = true))),
        base.copy(ops = listOf(PinOp203a(1, id = "m1", gw = 0, pinned = false))),
        base.copy(ops = listOf(PinOp203a(1, id = "m1", gw = 1))),
        base.copy(ops = listOf(PinOp203a(1, id = "m1", gw = 2))),
        base.copy(ops = listOf(PinOp203a(1, id = "ghost", gw = 0, pinned = true))),
        base.copy(ops = listOf(PinOp203a(1, id = "m1", gw = 0, pinned = true)), pinEnabled = false),
        base.copy(ops = listOf(PinOp203a(1, id = "m1", gw = 0, pinned = true)), token = ""),
        base.copy(ops = listOf(PinOp203a(1, id = "m1", gw = 0, pinned = true)), isGroup = true, myRole = "MEMBER"),
        base.copy(ops = listOf(PinOp203a(1, id = "m1", gw = 0, pinned = true)), isGroup = true, myRole = "admin"),
        base.copy(messages = listOf(m1, key), ops = listOf(PinOp203a(1, id = "key", gw = 0, pinned = true))),
        base.copy(
            messages = listOf(m1),
            initPins = (1..20).map { "p$it" },
            ops = listOf(PinOp203a(1, id = "m1", gw = 0, pinned = true)),
        ),
        base.copy(
            messages = listOf(m1),
            initPins = (1..20).map { "p$it" },
            ops = listOf(PinOp203a(1, id = "p1", gw = 0, pinned = false)),
        ),
        base.copy(ops = listOf(PinOp203a(1, id = "m1", gw = 0, pinned = true)), initToggling = true),
        base.copy(ops = listOf(PinOp203a(1, id = "m1", gw = 0, pinned = true)), sessionActive = false),
        base.copy(
            initPins = listOf("m1"),
            ops = listOf(PinOp203a(2, ids = listOf("m1", "m2"), shouldPin = true, gw = 0)),
        ),
        base.copy(
            ops = listOf(PinOp203a(2, ids = listOf("m1", "m2"), shouldPin = true, gw = 2)),
        ),
        base.copy(
            ops = listOf(PinOp203a(2, ids = listOf("m1", "m2"), shouldPin = true, gw = 3)),
        ),
        base.copy(
            ops = listOf(PinOp203a(2, ids = listOf("m1", "m2"), shouldPin = true, gw = 4)),
        ),
        base.copy(
            ops = listOf(PinOp203a(2, ids = listOf("m1", "m1"), shouldPin = true, gw = 0)),
        ),
        base.copy(
            messages = listOf(m1, key),
            ops = listOf(PinOp203a(2, ids = listOf("m1", "key"), shouldPin = true, gw = 0)),
        ),
        base.copy(
            messages = listOf(m1, key),
            ops = listOf(PinOp203a(2, ids = listOf("key"), shouldPin = true, gw = 0)),
        ),
        base.copy(
            messages = listOf(m1),
            ops = listOf(PinOp203a(2, ids = listOf("ghost"), shouldPin = true, gw = 0)),
        ),
        base.copy(ops = listOf(PinOp203a(3, id = "m1", gw = 0))),
        base.copy(ops = listOf(PinOp203a(3, id = "m1", gw = 1))),
        base.copy(ops = listOf(PinOp203a(3, id = "m1", gw = 2))),
        base.copy(ops = listOf(PinOp203a(3, id = "m1", gw = 0)), starEnabled = false),
        base.copy(ops = listOf(PinOp203a(3, id = "m1", gw = 0)), secret = true, secretBlock = true),
        base.copy(ops = listOf(PinOp203a(3, id = "m1", gw = 0)), secret = true, secretBlock = false),
        base.copy(ops = listOf(PinOp203a(3, id = "m1", gw = 0)), sessionActive = false),
        base.copy(ops = listOf(PinOp203a(3, id = "ghost", gw = 0))),
        base.copy(
            ops = listOf(PinOp203a(4, ids = listOf("m1", "m2"), shouldStar = true, gw = 0)),
        ),
        base.copy(
            ops = listOf(PinOp203a(4, ids = listOf("m1", "m2"), shouldStar = true, gw = 2)),
        ),
        base.copy(
            ops = listOf(PinOp203a(4, ids = listOf("m1", "m2"), shouldStar = true, gw = 4)),
        ),
        base.copy(
            messages = listOf(m1.copy(starred = true), m2),
            ops = listOf(PinOp203a(4, ids = listOf("m1", "m2"), shouldStar = true, gw = 0)),
        ),
        base.copy(
            messages = listOf(m1.copy(starred = true), m2),
            ops = listOf(PinOp203a(4, ids = listOf("m1"), shouldStar = false, gw = 0)),
        ),
        base.copy(ops = listOf(PinOp203a(0, expectedUserId = "me", gw = 0))),
        base.copy(ops = listOf(PinOp203a(0, expectedUserId = "me", gw = 1))),
        base.copy(ops = listOf(PinOp203a(0, expectedUserId = "me", gw = 2))),
        base.copy(ops = listOf(PinOp203a(0, expectedUserId = "", gw = 0))),
        base.copy(ops = listOf(PinOp203a(0, expectedUserId = "me", gw = 0)), sessionActive = false),
        base.copy(ops = listOf(PinOp203a(0, expectedUserId = "me", gw = 0)), token = ""),
        base.copy(
            messages = listOf(m1, m1.copy(type = MessageType.IMAGE)),
            ops = listOf(PinOp203a(3, id = "m1", gw = 0)),
        ),
        base.copy(ops = emptyList()),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class ChatPinStarControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(203101)
        repeat(300) {
            val c = randomPinCase203a(r)
            assertEquals(pinOracle203a(c), pinProd203a(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(203102)
        repeat(200) {
            val c = boundaryPinCase203a(r)
            assertEquals(pinOracle203a(c), pinProd203a(c))
        }
    }

    @Test
    fun pinnedCases() {
        val m1 = PinMsg203a("m1")
        val m2 = PinMsg203a("m2", MessageType.IMAGE)
        val cases = listOf(
            PinCase203a(messages = listOf(m1, m2), ops = listOf(PinOp203a(1, id = "m1", gw = 0, pinned = true))),
            PinCase203a(messages = listOf(m1, m2), ops = listOf(PinOp203a(3, id = "m1", gw = 0))),
            PinCase203a(
                messages = listOf(m1, m2),
                ops = listOf(PinOp203a(2, ids = listOf("m1", "m2"), shouldPin = true, gw = 0)),
            ),
            PinCase203a(
                messages = listOf(m1, m2),
                ops = listOf(PinOp203a(4, ids = listOf("m1", "m2"), shouldStar = true, gw = 0)),
            ),
            PinCase203a(messages = listOf(m1, m2), ops = listOf(PinOp203a(0, expectedUserId = "me", gw = 0))),
            PinCase203a(
                messages = listOf(m1),
                initPins = (1..20).map { "p$it" },
                ops = listOf(PinOp203a(1, id = "m1", gw = 0, pinned = true)),
            ),
            PinCase203a(
                messages = listOf(m1, m2),
                ops = listOf(
                    PinOp203a(1, id = "m1", gw = 0, pinned = true),
                    PinOp203a(3, id = "m2", gw = 1),
                    PinOp203a(0, expectedUserId = "me", gw = 0),
                ),
            ),
            PinCase203a(messages = listOf(m1, m2), ops = emptyList()),
        )
        for (c in cases) assertEquals(pinOracle203a(c), pinProd203a(c))
    }
}
