package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr206a(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit206a(b: Boolean): String = if (b) "1" else "0"

private fun opt206a(s: String?): String = s?.let { encStr206a(it) } ?: "~"

private data class CapChat206a(
    val id: String,
    val isGroup: Boolean,
    val disappearing: Int,
)

private data class CapOp206a(
    val message: String,
    val nowMs: Long,
)

private data class CapCase206a(
    val initChat: CapChat206a? = null,
    // 0 null，1 false，2 true。
    val initIsSecret: Int = 0,
    val initWarning: String? = null,
    val initInfo: String? = null,
    val initLastNotify: Long = 0L,
    val activeChatId: String = "c1",
    val captureEnabled: Boolean = true,
    val label: String = "Me",
    val ops: List<CapOp206a> = emptyList(),
)

private fun capInitialState206a(c: CapCase206a): ChatDetailUiState = ChatDetailUiState(
    contact = User("u0", "Nobody"),
    chat = c.initChat?.let {
        Chat(
            id = it.id,
            isGroup = it.isGroup,
            disappearingMessageSeconds = it.disappearing,
        )
    },
    isSecretChat = when (c.initIsSecret) {
        1 -> false
        2 -> true
        else -> null
    },
    groupEncryptionWarning = c.initWarning,
    secretChatInfoMessage = c.initInfo,
)

private fun capState206a(state: ChatDetailUiState, lastNotify: Long, sends: List<String>): String {
    val chat = state.chat?.let {
        encStr206a(it.id) + ":" + bit206a(it.isGroup) + ":" + it.disappearingMessageSeconds
    } ?: "~"
    val secret = when (state.isSecretChat) {
        null -> "~"
        true -> "1"
        else -> "0"
    }
    return listOf(
        chat,
        secret,
        opt206a(state.groupEncryptionWarning),
        opt206a(state.secretChatInfoMessage),
        lastNotify.toString(),
        sends.joinToString(",") { encStr206a(it) },
    ).joinToString("|")
}

// CaptureAlertPolicy.format 的逐分支镜像（kind 恒为 screenshot）。
private fun capFormat206a(label: String): String {
    val who = label.trim().ifBlank { "peer" }.take(32)
    return "CAPTURE_ALERT:screenshot|$who took a screenshot (privacy alert)"
}

private fun capOracle206a(c: CapCase206a): String {
    val effects = mutableListOf<String>()
    var state = capInitialState206a(c)
    var lastNotify = c.initLastNotify
    val sends = mutableListOf<String>()
    fun snap(prefix: String) {
        effects += prefix + ":" + capState206a(state, lastNotify, sends)
    }
    for (op in c.ops) {
        val msg = op.message.trim()
        if (!msg.isBlank()) {
            val disappearOn = (state.chat?.disappearingMessageSeconds ?: 0) > 0
            val shouldWarnPeer = (state.isSecretChat == true) || disappearOn
            state = state.copy(
                groupEncryptionWarning = msg,
                secretChatInfoMessage = if (state.isSecretChat == true) msg else state.secretChatInfoMessage,
            )
            snap("ST")
            if (shouldWarnPeer && state.chat?.isGroup != true) {
                if (c.captureEnabled) {
                    val now = op.nowMs
                    if (now - lastNotify >= 8_000L) {
                        lastNotify = now
                        if (c.activeChatId.isNotBlank()) {
                            if (state.chat?.isGroup != true) {
                                sends += capFormat206a(c.label)
                            }
                        }
                    }
                }
            }
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun capProd206a(c: CapCase206a): String {
    val effects = mutableListOf<String>()
    var state = capInitialState206a(c)
    var lastNotify = c.initLastNotify
    val sends = mutableListOf<String>()
    fun snap(prefix: String) {
        effects += prefix + ":" + capState206a(state, lastNotify, sends)
    }
    var curNow = 0L
    val controller = ChatCaptureAlertController(
        activeChatId = { c.activeChatId },
        currentState = { state },
        updateState = { transform ->
            state = transform(state)
            snap("ST")
        },
        sendInlineContent = { content, _, _ ->
            sends += content
            "ok"
        },
        getLastPeerNotifyAt = { lastNotify },
        setLastPeerNotifyAt = { lastNotify = it },
        captureAlertEnabled = { c.captureEnabled },
        currentUserLabel = { c.label },
        nowMillis = { curNow },
    )
    for (op in c.ops) {
        curNow = op.nowMs
        try {
            controller.notifyLocalCaptureDetected(op.message)
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val CAP_MSGS206a = listOf(
    "", " ", "  shot  ", "screenshot", "screen recording", "x\ty", "ünïcödé", "a".repeat(40),
)

private val CAP_NOWS206a = listOf(0L, 7_999L, 8_000L, 8_001L, 16_000L, -5L, 1_000_000L)

private val CAP_LASTS206a = listOf(0L, 1L, 7_999L, 8_000L, 100_000L, -8_000L)

private fun randomCapOp206a(r: Random): CapOp206a = CapOp206a(
    message = CAP_MSGS206a[r.nextInt(CAP_MSGS206a.size)],
    nowMs = CAP_NOWS206a[r.nextInt(CAP_NOWS206a.size)],
)

private fun randomCapCase206a(r: Random): CapCase206a {
    val hasChat = r.nextInt(6) != 0
    return CapCase206a(
        initChat = if (hasChat) CapChat206a(
            id = listOf("c1", "c2", "", "x\ty")[r.nextInt(4)],
            isGroup = r.nextBoolean(),
            disappearing = listOf(0, 1, 60, -5, 86400)[r.nextInt(5)],
        ) else null,
        initIsSecret = r.nextInt(3),
        initWarning = if (r.nextInt(4) == 0) CAP_MSGS206a[r.nextInt(CAP_MSGS206a.size)] else null,
        initInfo = if (r.nextInt(4) == 0) CAP_MSGS206a[r.nextInt(CAP_MSGS206a.size)] else null,
        initLastNotify = CAP_LASTS206a[r.nextInt(CAP_LASTS206a.size)],
        activeChatId = listOf("c1", "", " ", "other")[r.nextInt(4)],
        captureEnabled = r.nextInt(4) != 0,
        label = listOf("Me", "", "  ", "Al", "x".repeat(40), "ünïcödé")[r.nextInt(6)],
        ops = List(r.nextInt(0, 9)) { randomCapOp206a(r) },
    )
}

private fun boundaryCapCase206a(r: Random): CapCase206a {
    val base = CapCase206a()
    return when (r.nextInt(24)) {
        0 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2)
        1 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 1)
        2 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 0)
        3 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 60), initIsSecret = 1)
        4 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", true, 60), initIsSecret = 2)
        5 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", true, 0), initIsSecret = 2)
        6 -> base.copy(ops = listOf(CapOp206a("   ", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2)
        7 -> base.copy(ops = listOf(CapOp206a("", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2)
        8 -> base.copy(ops = listOf(CapOp206a("  shot  ", 8_000L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2)
        9 -> base.copy(ops = listOf(CapOp206a("shot", 7_999L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, initLastNotify = 0L)
        10 -> base.copy(ops = listOf(CapOp206a("shot", 8_000L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, initLastNotify = 0L)
        11 -> base.copy(ops = listOf(CapOp206a("shot", 8_001L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, initLastNotify = 0L)
        12 -> base.copy(ops = listOf(CapOp206a("shot", 0L), CapOp206a("shot2", 8_000L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2)
        13 -> base.copy(ops = listOf(CapOp206a("shot", 0L), CapOp206a("shot2", 7_999L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2)
        14 -> base.copy(ops = listOf(CapOp206a("shot", -5L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, initLastNotify = 0L)
        15 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, captureEnabled = false)
        16 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, activeChatId = "")
        17 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, activeChatId = "   ")
        18 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, label = "")
        19 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, label = "x".repeat(40))
        20 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, initWarning = "old", initInfo = "old-info")
        21 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = null, initIsSecret = 2)
        22 -> base.copy(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, -5), initIsSecret = 1)
        else -> base.copy(ops = emptyList(), initChat = CapChat206a("c1", false, 0), initIsSecret = 2)
    }
}

// ---------- tests ----------

class ChatCaptureAlertControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(206301)
        repeat(300) {
            val c = randomCapCase206a(r)
            assertEquals(capOracle206a(c), capProd206a(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(206302)
        repeat(200) {
            val c = boundaryCapCase206a(r)
            assertEquals(capOracle206a(c), capProd206a(c))
        }
    }

    @Test
    fun pinnedCases() {
        val cases = listOf(
            CapCase206a(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2),
            CapCase206a(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 60), initIsSecret = 1),
            CapCase206a(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", true, 60), initIsSecret = 2),
            CapCase206a(ops = listOf(CapOp206a("   ", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2),
            CapCase206a(ops = listOf(CapOp206a("shot", 7_999L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, initLastNotify = 0L),
            CapCase206a(ops = listOf(CapOp206a("shot", 8_000L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, initLastNotify = 0L),
            CapCase206a(ops = listOf(CapOp206a("shot", 0L), CapOp206a("shot2", 8_000L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2),
            CapCase206a(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, captureEnabled = false),
            CapCase206a(ops = listOf(CapOp206a("shot", 0L)), initChat = CapChat206a("c1", false, 0), initIsSecret = 2, label = "x".repeat(40)),
            CapCase206a(ops = emptyList(), initChat = CapChat206a("c1", false, 0), initIsSecret = 2),
        )
        for (c in cases) {
            assertEquals(capOracle206a(c), capProd206a(c))
        }
    }
}
