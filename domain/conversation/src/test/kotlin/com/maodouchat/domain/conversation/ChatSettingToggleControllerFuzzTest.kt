package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ChatSettingsResponse
import com.maodouchat.core.model.UpdateChatSettingsRequest
import com.maodouchat.core.model.User
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
private fun encStr204b(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit204b(b: Boolean): String = if (b) "1" else "0"

private fun warn204b(w: String?): String = w?.let { encStr204b(it) } ?: "~"

// op kind: 0 toggleMarkedUnread，1 togglePinned。gw：0 成功 1 失败 2 抛错
// （生产在 launch 里 catch 住 Exception 回滚，抛错与失败同效果、无 THROW）。
private data class SetOp204b(
    val kind: Int,
    val gw: Int = 0,
)

private data class SetCase204b(
    val pinEnabled: Boolean = true,
    val owner: String = "me",
    val token: String = "token",
    val chatId: String = "c1",
    val liveToken: String = "token",
    val sessionActive: Boolean = true,
    val hasChat: Boolean = true,
    val initMarkedUnread: Boolean = false,
    val initPinnedAt: Long = 0L,
    val initWarn: String? = null,
    val featureDisabledText: String = "PIN_DISABLED",
    val ops: List<SetOp204b> = emptyList(),
)

private fun encSetState204b(
    markedUnread: Boolean?,
    pinnedAt: Long?,
    warn: String?,
): String = (markedUnread?.let { bit204b(it) } ?: "~") + ":" +
    (pinnedAt?.toString() ?: "~") + ":" + warn204b(warn)

// fresh：生产 toggleChatPinned 实测取到的 System.currentTimeMillis()，
// 由 prod 侧采到 harvested、oracle 与 Python 共用，不各自取 now。
private fun setOracle204b(c: SetCase204b, fresh: List<Long>): String {
    val effects = mutableListOf<String>()
    var markedUnread: Boolean? = if (c.hasChat) c.initMarkedUnread else null
    var pinnedAt: Long? = if (c.hasChat) c.initPinnedAt else null
    var warn: String? = c.initWarn
    var freshIdx = 0
    fun snap(prefix: String) {
        effects += prefix + ":" + encSetState204b(markedUnread, pinnedAt, warn)
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                if (markedUnread == null) {
                    // fall through to FIN
                } else if (c.token.isBlank() || c.owner.isBlank() || c.chatId.isBlank()) {
                    // fall through to FIN
                } else {
                    val orig = markedUnread!!
                    val next = !orig
                    markedUnread = next
                    snap("ST")
                    if (!c.sessionActive) {
                        // 乐观值保留，不回滚
                    } else {
                        when (op.gw) {
                            0 -> Unit
                            else -> {
                                markedUnread = orig
                                snap("ST")
                            }
                        }
                    }
                }
            }
            1 -> {
                if (pinnedAt == null) {
                    // fall through to FIN
                } else if (!c.pinEnabled) {
                    warn = c.featureDisabledText
                    snap("ST")
                } else if (c.token.isBlank() || c.owner.isBlank() || c.chatId.isBlank()) {
                    // fall through to FIN
                } else {
                    val orig = pinnedAt!!
                    val wasPinned = orig > 0L
                    val nextPinnedAt = if (wasPinned) 0L else fresh[freshIdx++]
                    pinnedAt = nextPinnedAt
                    snap("ST")
                    if (!c.sessionActive) {
                        // 乐观值保留，不回滚
                    } else {
                        when (op.gw) {
                            0 -> Unit
                            else -> {
                                pinnedAt = orig
                                snap("ST")
                            }
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

private fun setProd204b(c: SetCase204b, harvested: MutableList<Long>): String {
    val effects = mutableListOf<String>()
    var live = ChatDetailUiState(
        contact = User("u2", "Bob"),
        chat = if (c.hasChat) {
            Chat(
                id = c.chatId,
                participants = listOf(User("me", "Me"), User("u2", "Bob")),
                lastMessageTime = 0L,
                pinnedAt = c.initPinnedAt,
                markedUnread = c.initMarkedUnread,
            )
        } else {
            null
        },
        groupEncryptionWarning = c.initWarn,
    )
    fun snap(prefix: String) {
        effects += prefix + ":" + encSetState204b(
            live.chat?.markedUnread, live.chat?.pinnedAt, live.groupEncryptionWarning,
        )
    }
    var curGw = 0
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
    val gateway = object : ChatSettingsGateway {
        override suspend fun updateChatSettings(token: String, chatId: String, request: UpdateChatSettingsRequest): Result<ChatSettingsResponse> {
            return when (curGw) {
                0 -> Result.success(
                    ChatSettingsResponse(chatId, 0L, false, false, false, 0L),
                )
                1 -> Result.failure(RuntimeException("boom"))
                else -> throw RuntimeException("boom")
            }
        }
    }
    val controller = ChatSettingToggleController(
        scope = scope,
        chatPinEnabled = { c.pinEnabled },
        sessionActive = { c.sessionActive },
        liveToken = { c.liveToken },
        chatSettingsGateway = gateway,
        ownerUserId = { c.owner },
        token = { c.token },
        chatId = { c.chatId },
        currentState = { live },
        updateState = { transform ->
            val before = live.chat?.pinnedAt
            live = transform(live)
            val after = live.chat?.pinnedAt
            if (after != null && after != before && after > 1_000_000_000_000L) {
                harvested += after
            }
            snap("ST")
        },
        featureDisabledText = { c.featureDisabledText },
    )
    for (op in c.ops) {
        curGw = op.gw
        try {
            when (op.kind) {
                0 -> controller.toggleChatMarkedUnread()
                1 -> controller.toggleChatPinned()
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

private fun randomSetOp204b(r: Random): SetOp204b =
    SetOp204b(kind = r.nextInt(2), gw = r.nextInt(3))

private fun randomSetCase204b(r: Random): SetCase204b {
    return SetCase204b(
        pinEnabled = r.nextBoolean(),
        owner = listOf("", " ", "me", "u1")[r.nextInt(4)],
        token = listOf("", " ", "token")[r.nextInt(3)],
        chatId = listOf("", " ", "c1")[r.nextInt(3)],
        liveToken = listOf("", " ", "token", "live")[r.nextInt(4)],
        sessionActive = r.nextBoolean(),
        hasChat = r.nextInt(6) != 0,
        initMarkedUnread = r.nextBoolean(),
        initPinnedAt = listOf(0L, 1000L, 2000L, -5L)[r.nextInt(4)],
        initWarn = if (r.nextInt(4) == 0) "old" else null,
        featureDisabledText = listOf("PIN_DISABLED", "off", "")[r.nextInt(3)],
        ops = List(r.nextInt(0, 9)) { randomSetOp204b(r) },
    )
}

private fun boundarySetCase204b(r: Random): SetCase204b {
    val base = SetCase204b()
    val cases = listOf(
        base.copy(ops = listOf(SetOp204b(0, gw = 0))),
        base.copy(ops = listOf(SetOp204b(0, gw = 1))),
        base.copy(ops = listOf(SetOp204b(0, gw = 2))),
        base.copy(ops = listOf(SetOp204b(0, gw = 0)), initMarkedUnread = true),
        base.copy(ops = listOf(SetOp204b(0, gw = 0)), sessionActive = false),
        base.copy(ops = listOf(SetOp204b(0, gw = 0)), token = ""),
        base.copy(ops = listOf(SetOp204b(0, gw = 0)), owner = ""),
        base.copy(ops = listOf(SetOp204b(0, gw = 0)), chatId = ""),
        base.copy(ops = listOf(SetOp204b(0, gw = 0)), hasChat = false),
        base.copy(ops = listOf(SetOp204b(0, gw = 0)), initWarn = "old"),
        base.copy(ops = listOf(SetOp204b(1, gw = 0))),
        base.copy(ops = listOf(SetOp204b(1, gw = 1))),
        base.copy(ops = listOf(SetOp204b(1, gw = 2))),
        base.copy(ops = listOf(SetOp204b(1, gw = 0)), initPinnedAt = 1000L),
        base.copy(ops = listOf(SetOp204b(1, gw = 1)), initPinnedAt = 1000L),
        base.copy(ops = listOf(SetOp204b(1, gw = 2)), initPinnedAt = 1000L),
        base.copy(ops = listOf(SetOp204b(1, gw = 0)), initPinnedAt = -5L),
        base.copy(ops = listOf(SetOp204b(1, gw = 0)), pinEnabled = false),
        base.copy(ops = listOf(SetOp204b(1, gw = 0)), pinEnabled = false, featureDisabledText = ""),
        base.copy(ops = listOf(SetOp204b(1, gw = 0)), sessionActive = false),
        base.copy(ops = listOf(SetOp204b(1, gw = 0)), sessionActive = false, initPinnedAt = 1000L),
        base.copy(ops = listOf(SetOp204b(1, gw = 0)), token = ""),
        base.copy(ops = listOf(SetOp204b(1, gw = 0)), hasChat = false),
        base.copy(
            ops = listOf(
                SetOp204b(1, gw = 0),
                SetOp204b(0, gw = 1),
                SetOp204b(1, gw = 0),
                SetOp204b(0, gw = 0),
            ),
        ),
        base.copy(
            ops = listOf(
                SetOp204b(1, gw = 2),
                SetOp204b(1, gw = 0),
                SetOp204b(1, gw = 1),
            ),
            initPinnedAt = 1000L,
        ),
        base.copy(ops = emptyList()),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class ChatSettingToggleControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(204201)
        repeat(300) {
            val c = randomSetCase204b(r)
            val harvested = mutableListOf<Long>()
            val prod = setProd204b(c, harvested)
            assertEquals(setOracle204b(c, harvested), prod)
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(204202)
        repeat(200) {
            val c = boundarySetCase204b(r)
            val harvested = mutableListOf<Long>()
            val prod = setProd204b(c, harvested)
            assertEquals(setOracle204b(c, harvested), prod)
        }
    }

    @Test
    fun pinnedCases() {
        val cases = listOf(
            SetCase204b(ops = listOf(SetOp204b(0, gw = 0))),
            SetCase204b(ops = listOf(SetOp204b(0, gw = 2))),
            SetCase204b(ops = listOf(SetOp204b(1, gw = 0))),
            SetCase204b(ops = listOf(SetOp204b(1, gw = 1)), initPinnedAt = 1000L),
            SetCase204b(ops = listOf(SetOp204b(1, gw = 0)), pinEnabled = false),
            SetCase204b(ops = listOf(SetOp204b(1, gw = 0)), sessionActive = false),
            SetCase204b(ops = listOf(SetOp204b(0, gw = 0)), hasChat = false),
            SetCase204b(
                ops = listOf(
                    SetOp204b(1, gw = 0),
                    SetOp204b(0, gw = 1),
                    SetOp204b(1, gw = 0),
                ),
            ),
            SetCase204b(ops = emptyList()),
        )
        for (c in cases) {
            val harvested = mutableListOf<Long>()
            val prod = setProd204b(c, harvested)
            assertEquals(setOracle204b(c, harvested), prod)
        }
    }
}
