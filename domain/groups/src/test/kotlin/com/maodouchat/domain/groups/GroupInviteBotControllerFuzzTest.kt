package com.maodouchat.domain.groups

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr208b(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit208b(b: Boolean): String = if (b) "1" else "0"

private fun opt208b(s: String?): String = s?.let { encStr208b(it) } ?: "~"

// op kind：只有 0 inviteOwnedBot。
// mode：0 成功、1 失败 Result 带消息、2 失败 Result 空消息、
// 3 抛 RuntimeException 带消息、4 抛 RuntimeException 空消息、5 抛 CancellationException。
private data class BotOp208b(
    val kind: Int = 0,
    val botId: String = "b1",
    val mode: Int = 0,
)

private data class BotCase208b(
    val chatId: String = "g1",
    val owner: String = "u1",
    // sessionActive 在第 flipAfter 次调用起返回 false（调用计数跨 op 累计）。
    val sessFlipAfter: Int = 999,
    val busy: Boolean = false,
    val ops: List<BotOp208b> = emptyList(),
)

private fun botText208b(msg: GroupInviteBotMessage): String = when (msg) {
    GroupInviteBotMessage.INVITED -> "bot-invited"
    GroupInviteBotMessage.INVITE_FAILED -> "bot-invite-failed"
}

private fun botState208b(state: GroupDetailUiState): String = listOf(
    bit208b(state.isInvitingBot),
    opt208b(state.message),
).joinToString("|")

private fun botOracle208b(c: BotCase208b): String {
    val effects = mutableListOf<String>()
    var state = GroupDetailUiState(isInvitingBot = c.busy)
    var sessCalls = 0
    fun snap(prefix: String) {
        effects += prefix + ":" + botState208b(state)
    }
    fun sessionActive(): Boolean {
        val idx = sessCalls++
        return idx < c.sessFlipAfter
    }
    fun inviteBot(op: BotOp208b): Result<String> {
        effects += "CALL:invite:" + encStr208b(c.chatId) + ":" + encStr208b(op.botId) + ":" + op.mode
        return when (op.mode) {
            0 -> Result.success("ok")
            1 -> Result.failure(RuntimeException("boom"))
            2 -> Result.failure(RuntimeException())
            3 -> throw RuntimeException("boom")
            4 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun inviteOwnedBot(op: BotOp208b) {
        if (op.botId.isBlank() || c.chatId.isBlank() || state.isInvitingBot) return
        state = state.copy(isInvitingBot = true, message = null)
        snap("ST")
        try {
            if (!sessionActive()) return
            val result = inviteBot(op)
            if (!sessionActive()) return
            result.fold(
                onSuccess = {
                    state = state.copy(isInvitingBot = false)
                    snap("ST")
                    effects += "CALLBACK:reload:" + encStr208b(botText208b(GroupInviteBotMessage.INVITED))
                },
                onFailure = {
                    state = state.copy(
                        isInvitingBot = false,
                        message = botText208b(GroupInviteBotMessage.INVITE_FAILED),
                    )
                    snap("ST")
                },
            )
        } catch (e: CancellationException) {
            state = state.copy(isInvitingBot = false)
            snap("ST")
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
    }
    for (op in c.ops) {
        inviteOwnedBot(op)
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun botProd208b(c: BotCase208b): String {
    val effects = mutableListOf<String>()
    var state = GroupDetailUiState(isInvitingBot = c.busy)
    var sessCalls = 0
    fun snap(prefix: String) {
        effects += prefix + ":" + botState208b(state)
    }
    val handler = CoroutineExceptionHandler { _, e ->
        effects += "THROW:" + e.javaClass.simpleName
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)
    fun drain() {
        runBlocking {
            repeat(100) {
                val kids = scope.coroutineContext[Job]?.children?.toList() ?: emptyList()
                if (kids.isEmpty()) return@runBlocking
                for (child in kids) runCatching { child.join() }
            }
        }
    }
    var curOp: BotOp208b? = null
    val controller = GroupInviteBotController(
        scope = scope,
        currentState = { state },
        updateState = { transform ->
            state = transform(state)
            snap("ST")
        },
        chatId = { c.chatId },
        ownerUserId = { c.owner },
        sessionActive = {
            val idx = sessCalls++
            idx < c.sessFlipAfter
        },
        inviteBot = { chatId, botId ->
            val op = curOp!!
            effects += "CALL:invite:" + encStr208b(chatId) + ":" + encStr208b(botId) + ":" + op.mode
            when (op.mode) {
                0 -> Result.success("ok")
                1 -> Result.failure(RuntimeException("boom"))
                2 -> Result.failure(RuntimeException())
                3 -> throw RuntimeException("boom")
                4 -> throw RuntimeException()
                else -> throw CancellationException("cancel-boom")
            }
        },
        text = { msg -> botText208b(msg) },
        reload = { message -> effects += "CALLBACK:reload:" + encStr208b(message ?: "") },
    )
    for (op in c.ops) {
        curOp = op
        try {
            controller.inviteOwnedBot(op.botId)
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val BOT_IDS208b = listOf("", " ", "b1", "bot-1", "x\ty", "ünïcödé")

private fun randomBotOp208b(r: Random): BotOp208b = BotOp208b(
    kind = 0,
    botId = BOT_IDS208b[r.nextInt(BOT_IDS208b.size)],
    mode = r.nextInt(6),
)

private fun randomBotCase208b(r: Random): BotCase208b = BotCase208b(
    chatId = listOf("g1", "", " ", "x\ty")[r.nextInt(4)],
    owner = listOf("u1", "", " ", "me", "other")[r.nextInt(5)],
    sessFlipAfter = listOf(0, 1, 2, 3, 5, 999)[r.nextInt(6)],
    busy = r.nextInt(5) == 0,
    ops = List(r.nextInt(0, 9)) { randomBotOp208b(r) },
)

private fun boundaryBotCase208b(r: Random): BotCase208b {
    val base = BotCase208b()
    return when (r.nextInt(16)) {
        0 -> base.copy(ops = listOf(BotOp208b(botId = "b1")))
        1 -> base.copy(ops = listOf(BotOp208b(botId = "b1", mode = 1)))
        2 -> base.copy(ops = listOf(BotOp208b(botId = "b1", mode = 2)))
        3 -> base.copy(ops = listOf(BotOp208b(botId = "b1", mode = 3)))
        4 -> base.copy(ops = listOf(BotOp208b(botId = "b1", mode = 4)))
        5 -> base.copy(ops = listOf(BotOp208b(botId = "b1", mode = 5)))
        6 -> base.copy(ops = listOf(BotOp208b(botId = "")))
        7 -> base.copy(ops = listOf(BotOp208b(botId = "  ")))
        8 -> base.copy(ops = listOf(BotOp208b(botId = "b1")), chatId = "")
        9 -> base.copy(ops = listOf(BotOp208b(botId = "b1")), busy = true)
        10 -> base.copy(ops = listOf(BotOp208b(botId = "b1")), sessFlipAfter = 0)
        11 -> base.copy(ops = listOf(BotOp208b(botId = "b1")), sessFlipAfter = 1)
        12 -> base.copy(
            ops = listOf(
                BotOp208b(botId = "b1"),
                BotOp208b(botId = "b1"),
            ),
        )
        13 -> base.copy(
            ops = listOf(
                BotOp208b(botId = "b1", mode = 3),
                BotOp208b(botId = "b1"),
            ),
        )
        14 -> base.copy(ops = listOf(BotOp208b(botId = "b1", mode = 5)), sessFlipAfter = 2)
        else -> base.copy(ops = emptyList())
    }
}

// ---------- tests ----------

class GroupInviteBotControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(208205)
        repeat(300) {
            val c = randomBotCase208b(r)
            assertEquals(botOracle208b(c), botProd208b(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(208206)
        repeat(200) {
            val c = boundaryBotCase208b(r)
            assertEquals(botOracle208b(c), botProd208b(c))
        }
    }

    @Test
    fun pinnedCases() {
        val cases = listOf(
            BotCase208b(ops = listOf(BotOp208b(botId = "b1"))),
            BotCase208b(ops = listOf(BotOp208b(botId = "b1", mode = 1))),
            BotCase208b(ops = listOf(BotOp208b(botId = "b1", mode = 3))),
            BotCase208b(ops = listOf(BotOp208b(botId = "b1", mode = 5))),
            BotCase208b(ops = listOf(BotOp208b(botId = ""))),
            BotCase208b(ops = listOf(BotOp208b(botId = "b1")), busy = true),
            BotCase208b(ops = listOf(BotOp208b(botId = "b1")), sessFlipAfter = 0),
            BotCase208b(ops = listOf(BotOp208b(botId = "b1")), sessFlipAfter = 1),
            BotCase208b(
                ops = listOf(
                    BotOp208b(botId = "b1"),
                    BotOp208b(botId = "b1"),
                ),
            ),
            BotCase208b(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(botOracle208b(c), botProd208b(c))
        }
    }
}
