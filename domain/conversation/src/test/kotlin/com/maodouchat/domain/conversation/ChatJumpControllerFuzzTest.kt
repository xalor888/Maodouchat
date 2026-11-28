package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
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
private fun encStr203c(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun warn203c(w: String?): String = w?.let { encStr203c(it) } ?: "~"

// op kind: 0 jumpToDate，1 jumpToMessage，2 jumpToPinnedMessage。
private data class JmpOp203c(
    val kind: Int,
    val dayStart: Long = 0L,
    val id: String = "",
)

private data class JmpCase203c(
    val owner: String = "u1",
    val activeChat: String = "c1",
    val chatId: String = "c1",
    val sessionActive: Boolean = true,
    val anchor: String? = "m1",
    val anchorThrow: Boolean = false,
    val ops: List<JmpOp203c> = emptyList(),
)

private fun jmpOracle203c(c: JmpCase203c): String {
    val effects = mutableListOf<String>()
    var nav: String? = null
    var warn: String? = null
    fun snap(prefix: String) {
        effects += prefix + ":" + warn203c(nav) + ":" + warn203c(warn)
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                val targetChatId = c.activeChat.ifBlank { c.chatId }
                if (targetChatId.isBlank() || op.dayStart <= 0L) {
                    // fall through to FIN
                } else if (c.owner.isBlank() || c.owner == "me") {
                    // fall through to FIN
                } else if (!c.sessionActive) {
                    // fall through to FIN
                } else {
                    effects += "CALL:" + encStr203c(targetChatId) + ":" + op.dayStart
                    if (c.anchorThrow) {
                        effects += "LOG:jumpToDate failed:boom"
                    } else if (c.anchor.isNullOrBlank()) {
                        warn = "[DATE_EMPTY]"
                        snap("ST")
                    } else {
                        nav = c.anchor
                        snap("ST")
                    }
                }
            }
            1, 2 -> {
                if (op.id.isBlank()) {
                    // fall through to FIN
                } else {
                    nav = op.id
                    snap("ST")
                }
            }
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun jmpProd203c(c: JmpCase203c): String {
    val effects = mutableListOf<String>()
    var live = ChatDetailUiState()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    fun drain() {
        runBlocking {
            for (child in scope.coroutineContext[Job]?.children ?: emptySequence()) {
                runCatching { child.join() }
            }
        }
    }
    fun snap(prefix: String) {
        effects += prefix + ":" + warn203c(live.navigationTargetMessageId) + ":" +
            warn203c(live.groupEncryptionWarning)
    }
    val controller = ChatJumpController(
        scope = scope,
        ownerUserId = { c.owner },
        activeChatId = { c.activeChat },
        chatId = { c.chatId },
        updateState = { transform ->
            live = transform(live)
            snap("ST")
        },
        text = { message -> "[${message.name}]" },
        sessionActive = { c.sessionActive },
        getFirstMessageAtOrAfter = { targetChatId, dayStart ->
            effects += "CALL:" + encStr203c(targetChatId) + ":" + dayStart
            if (c.anchorThrow) throw IllegalStateException("boom")
            c.anchor
        },
        logWarning = { message, error ->
            effects += "LOG:" + message + ":" + (error?.message ?: "~")
        },
        ioDispatcher = Dispatchers.Unconfined,
        mainDispatcher = Dispatchers.Unconfined,
        searchSelectionStateController = ChatSearchSelectionStateController(),
    )
    for (op in c.ops) {
        when (op.kind) {
            0 -> controller.jumpToDate(op.dayStart)
            1 -> controller.jumpToMessage(op.id)
            2 -> controller.jumpToPinnedMessage(op.id)
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val JMP_IDS203c = listOf("", " ", "m1", "m8", "m9", "x\ty", "ünïcödé", "10")

private val JMP_DAYS203c = listOf(-1L, 0L, 1L, 1000L, 86_400_000L, 1_700_000_000_000L)

private fun randomJmpOp203c(r: Random): JmpOp203c {
    return when (r.nextInt(3)) {
        0 -> JmpOp203c(0, dayStart = JMP_DAYS203c[r.nextInt(JMP_DAYS203c.size)])
        1 -> JmpOp203c(1, id = JMP_IDS203c[r.nextInt(JMP_IDS203c.size)])
        else -> JmpOp203c(2, id = JMP_IDS203c[r.nextInt(JMP_IDS203c.size)])
    }
}

private fun randomJmpCase203c(r: Random): JmpCase203c {
    return JmpCase203c(
        owner = listOf("", " ", "me", "u1", "u2")[r.nextInt(5)],
        activeChat = listOf("", " ", "c1", "c2")[r.nextInt(4)],
        chatId = listOf("", " ", "c1", "c2")[r.nextInt(4)],
        sessionActive = r.nextBoolean(),
        anchor = listOf<String?>(null, "", " ", "m1", "m5")[r.nextInt(5)],
        anchorThrow = r.nextInt(6) == 0,
        ops = List(r.nextInt(0, 9)) { randomJmpOp203c(r) },
    )
}

private fun boundaryJmpCase203c(r: Random): JmpCase203c {
    val base = JmpCase203c()
    val cases = listOf(
        base.copy(ops = listOf(JmpOp203c(0, dayStart = 1000L))),
        base.copy(ops = listOf(JmpOp203c(0, dayStart = 0L))),
        base.copy(ops = listOf(JmpOp203c(0, dayStart = -1L))),
        base.copy(ops = listOf(JmpOp203c(0, dayStart = 1000L)), anchor = null),
        base.copy(ops = listOf(JmpOp203c(0, dayStart = 1000L)), anchor = ""),
        base.copy(ops = listOf(JmpOp203c(0, dayStart = 1000L)), anchor = " "),
        base.copy(ops = listOf(JmpOp203c(0, dayStart = 1000L)), anchorThrow = true),
        base.copy(ops = listOf(JmpOp203c(0, dayStart = 1000L)), sessionActive = false),
        base.copy(ops = listOf(JmpOp203c(0, dayStart = 1000L)), owner = "me"),
        base.copy(ops = listOf(JmpOp203c(0, dayStart = 1000L)), owner = ""),
        base.copy(ops = listOf(JmpOp203c(0, dayStart = 1000L)), owner = " "),
        base.copy(ops = listOf(JmpOp203c(0, dayStart = 1000L)), activeChat = "", chatId = ""),
        base.copy(ops = listOf(JmpOp203c(0, dayStart = 1000L)), activeChat = "", chatId = "c2"),
        base.copy(ops = listOf(JmpOp203c(0, dayStart = 86_400_000L))),
        base.copy(ops = listOf(JmpOp203c(1, id = "m8"))),
        base.copy(ops = listOf(JmpOp203c(1, id = ""))),
        base.copy(ops = listOf(JmpOp203c(1, id = " "))),
        base.copy(ops = listOf(JmpOp203c(2, id = "m9"))),
        base.copy(ops = listOf(JmpOp203c(2, id = ""))),
        base.copy(ops = listOf(JmpOp203c(2, id = " "))),
        base.copy(
            ops = listOf(
                JmpOp203c(0, dayStart = 1000L),
                JmpOp203c(1, id = "m8"),
                JmpOp203c(2, id = "m9"),
                JmpOp203c(0, dayStart = 2000L),
            ),
        ),
        base.copy(
            ops = listOf(JmpOp203c(1, id = "m8"), JmpOp203c(1, id = "m8")),
        ),
        base.copy(ops = emptyList()),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class ChatJumpControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(203301)
        repeat(300) {
            val c = randomJmpCase203c(r)
            assertEquals(jmpOracle203c(c), jmpProd203c(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(203302)
        repeat(200) {
            val c = boundaryJmpCase203c(r)
            assertEquals(jmpOracle203c(c), jmpProd203c(c))
        }
    }

    @Test
    fun pinnedCases() {
        val cases = listOf(
            JmpCase203c(ops = listOf(JmpOp203c(0, dayStart = 1000L))),
            JmpCase203c(ops = listOf(JmpOp203c(0, dayStart = 0L))),
            JmpCase203c(ops = listOf(JmpOp203c(0, dayStart = 1000L)), anchor = null),
            JmpCase203c(ops = listOf(JmpOp203c(0, dayStart = 1000L)), anchorThrow = true),
            JmpCase203c(ops = listOf(JmpOp203c(0, dayStart = 1000L)), sessionActive = false),
            JmpCase203c(ops = listOf(JmpOp203c(1, id = "m8"))),
            JmpCase203c(ops = listOf(JmpOp203c(2, id = "m9"))),
            JmpCase203c(
                ops = listOf(
                    JmpOp203c(0, dayStart = 1000L),
                    JmpOp203c(1, id = "m8"),
                    JmpOp203c(2, id = "m9"),
                ),
            ),
            JmpCase203c(ops = emptyList()),
        )
        for (c in cases) assertEquals(jmpOracle203c(c), jmpProd203c(c))
    }
}
