package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
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
private fun encStr204a(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun encIds204a(ids: Collection<String>): String =
    ids.joinToString(";") { encStr204a(it) }

private fun bit204a(b: Boolean): String = if (b) "1" else "0"

private fun warn204a(w: String?): String = w?.let { encStr204a(it) } ?: "~"

// op kind: 0 refresh，1 block，2 unblock，3 reportContact，4 reportMessage。
// gw：0 成功 1 失败 2 抛错。contactId 为 null 表示用默认参数（当前 contact.id）。
private data class ModOp204a(
    val kind: Int,
    val contactId: String? = null,
    val messageId: String = "",
    val reason: String = "",
    val description: String? = null,
    val gw: Int = 0,
)

private data class ModReport204a(
    val targetType: String,
    val targetId: String,
    val chatId: String?,
    val messageId: String?,
    val reason: String,
    val description: String?,
)

private fun encReport204a(r: ModReport204a): String = listOf(
    r.targetType,
    encStr204a(r.targetId),
    r.chatId?.let { encStr204a(it) } ?: "~",
    r.messageId?.let { encStr204a(it) } ?: "~",
    encStr204a(r.reason),
    r.description?.let { encStr204a(it) } ?: "~",
).joinToString(":")

private data class ModCase204a(
    val enabled: Boolean = true,
    val owner: String = "me",
    val token: String = "token",
    val activeChat: String = "c1",
    val contactId: String = "u2",
    val displayName: String = "Bob",
    val sessionActive: Boolean = true,
    val initBlocked: Boolean = false,
    val initBlocking: Boolean = false,
    val initReporting: Boolean = false,
    val initWarn: String? = null,
    val initBlockedSet: List<String> = emptyList(),
    val ops: List<ModOp204a> = emptyList(),
)

private fun encModState204a(
    blocked: Boolean,
    blocking: Boolean,
    reporting: Boolean,
    warn: String?,
    blockedSet: List<String>,
    reports: List<ModReport204a>,
): String = bit204a(blocked) + ":" + bit204a(blocking) + ":" + bit204a(reporting) + ":" +
    warn204a(warn) + ":" + encIds204a(blockedSet) + ":" +
    reports.joinToString(",") { encReport204a(it) }

private fun modText204a(name: String, args: List<Any>): String =
    "[" + name + (if (args.isEmpty()) "" else ":" + args.joinToString(",")) + "]"

private fun modOracle204a(c: ModCase204a): String {
    val effects = mutableListOf<String>()
    var blocked = c.initBlocked
    var blocking = c.initBlocking
    var reporting = c.initReporting
    var warn: String? = c.initWarn
    val blockedSet = c.initBlockedSet.toMutableList()
    val reports = mutableListOf<ModReport204a>()
    // 生产读的是 contact.displayName（User 的派生属性：nickname → 非空 name → id），不是原样 name。
    val dispName = c.displayName.takeIf { it.isNotBlank() } ?: c.contactId
    fun snap(prefix: String) {
        effects += prefix + ":" + encModState204a(blocked, blocking, reporting, warn, blockedSet, reports)
    }
    fun warnMsg(name: String, vararg args: Any) {
        warn = modText204a(name, args.toList())
        snap("ST")
    }
    fun warnRaw(message: String) {
        warn = message
        snap("ST")
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                val contactId = op.contactId ?: c.contactId
                if (contactId.isBlank() || c.token.isBlank() || c.owner.isBlank()) {
                    // fall through to FIN
                } else if (!c.sessionActive) {
                    // fall through to FIN
                } else {
                    when (op.gw) {
                        0 -> {
                            if (c.sessionActive) {
                                blocked = contactId in blockedSet
                                snap("ST")
                            }
                        }
                        1 -> Unit
                        else -> effects += "THROW:RuntimeException"
                    }
                }
            }
            1 -> {
                if (c.contactId.isBlank() || c.contactId == c.owner) {
                    // fall through to FIN
                } else if (!c.enabled) {
                    warnMsg("FEATURE_DISABLED_BY_ADMIN")
                } else if (c.token.isBlank() || c.owner.isBlank()) {
                    warnMsg("SESSION_EXPIRED")
                } else if (blocking) {
                    // fall through to FIN
                } else {
                    blocking = true
                    warn = null
                    snap("ST")
                    if (!c.sessionActive) {
                        blocking = false
                        snap("ST")
                    } else {
                        when (op.gw) {
                            0 -> {
                                if (c.contactId !in blockedSet) blockedSet += c.contactId
                                if (c.sessionActive) {
                                    blocked = true
                                    blocking = false
                                    warn = modText204a("CHAT_BLOCKED_USER_STATUS", listOf(dispName))
                                    snap("ST")
                                }
                            }
                            1 -> {
                                blocking = false
                                warn = "boom"
                                snap("ST")
                            }
                            else -> {
                                effects += "THROW:RuntimeException"
                            }
                        }
                    }
                }
            }
            2 -> {
                if (c.contactId.isBlank()) {
                    // fall through to FIN
                } else if (c.token.isBlank() || c.owner.isBlank()) {
                    warnMsg("SESSION_EXPIRED")
                } else if (blocking) {
                    // fall through to FIN
                } else {
                    blocking = true
                    warn = null
                    snap("ST")
                    if (!c.sessionActive) {
                        blocking = false
                        snap("ST")
                    } else {
                        when (op.gw) {
                            0 -> {
                                blockedSet -= c.contactId
                                if (c.sessionActive) {
                                    blocked = false
                                    blocking = false
                                    warn = modText204a("CHAT_UNBLOCKED_USER_STATUS", listOf(dispName))
                                    snap("ST")
                                }
                            }
                            1 -> {
                                blocking = false
                                warn = "boom"
                                snap("ST")
                            }
                            else -> {
                                effects += "THROW:RuntimeException"
                            }
                        }
                    }
                }
            }
            3 -> {
                if (c.contactId.isBlank() || c.contactId == c.owner) {
                    // fall through to FIN
                } else if (!c.enabled) {
                    warnMsg("FEATURE_DISABLED_BY_ADMIN")
                } else if (c.token.isBlank() || c.owner.isBlank()) {
                    warnMsg("SESSION_EXPIRED")
                } else if (!c.sessionActive) {
                    // fall through to FIN
                } else {
                    when (op.gw) {
                        0 -> {
                            reports += ModReport204a(
                                "USER", c.contactId,
                                c.activeChat.takeIf { it.isNotBlank() }, null,
                                op.reason, op.description,
                            )
                            if (c.sessionActive) warnMsg("CHAT_REPORT_SUBMITTED")
                        }
                        1 -> warnRaw("boom")
                        else -> effects += "THROW:RuntimeException"
                    }
                }
            }
            4 -> {
                if (!c.enabled) {
                    warnMsg("FEATURE_DISABLED_BY_ADMIN")
                } else if (op.messageId.isBlank()) {
                    // fall through to FIN
                } else if (c.token.isBlank() || c.owner.isBlank()) {
                    warnMsg("SESSION_EXPIRED")
                } else if (reporting) {
                    // fall through to FIN
                } else {
                    reporting = true
                    snap("ST")
                    if (!c.sessionActive) {
                        // fall through to FIN（isReporting 保持 true）
                    } else {
                        when (op.gw) {
                            0 -> {
                                reports += ModReport204a(
                                    "MESSAGE", op.messageId,
                                    c.activeChat.takeIf { it.isNotBlank() }, op.messageId,
                                    op.reason, op.description,
                                )
                                reporting = false
                                snap("ST")
                                if (c.sessionActive) warnMsg("CHAT_REPORT_SUBMITTED")
                            }
                            1 -> {
                                warn = "boom"
                                reporting = false
                                snap("ST")
                            }
                            else -> {
                                effects += "THROW:RuntimeException"
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

private fun modProd204a(c: ModCase204a): String {
    val effects = mutableListOf<String>()
    var live = ChatDetailUiState(
        contact = User(c.contactId, c.displayName),
        isContactBlocked = c.initBlocked,
        isBlockingContact = c.initBlocking,
        isReporting = c.initReporting,
        groupEncryptionWarning = c.initWarn,
    )
    val gwBlocked = c.initBlockedSet.toMutableList()
    val gwReports = mutableListOf<ModReport204a>()
    fun snap(prefix: String) {
        effects += prefix + ":" + encModState204a(
            live.isContactBlocked, live.isBlockingContact, live.isReporting,
            live.groupEncryptionWarning, gwBlocked, gwReports,
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
    val gateway = object : ChatModerationGateway {
        override suspend fun blockedUserIds(token: String): Result<List<String>> {
            return when (curGw) {
                0 -> Result.success(gwBlocked.toList())
                1 -> Result.failure(RuntimeException("boom"))
                else -> throw RuntimeException("boom")
            }
        }

        override suspend fun blockUser(token: String, userId: String): Result<Unit> {
            return when (curGw) {
                0 -> {
                    if (userId !in gwBlocked) gwBlocked += userId
                    Result.success(Unit)
                }
                1 -> Result.failure(RuntimeException("boom"))
                else -> throw RuntimeException("boom")
            }
        }

        override suspend fun unblockUser(token: String, userId: String): Result<Unit> {
            return when (curGw) {
                0 -> {
                    gwBlocked -= userId
                    Result.success(Unit)
                }
                1 -> Result.failure(RuntimeException("boom"))
                else -> throw RuntimeException("boom")
            }
        }

        override suspend fun createReport(token: String, targetType: String, targetId: String, chatId: String?, messageId: String?, reason: String, description: String?): Result<Unit> {
            return when (curGw) {
                0 -> {
                    gwReports += ModReport204a(targetType, targetId, chatId, messageId, reason, description)
                    Result.success(Unit)
                }
                1 -> Result.failure(RuntimeException("boom"))
                else -> throw RuntimeException("boom")
            }
        }
    }
    val controller = ChatModerationController(
        moderationGateway = gateway,
        moderationEnabled = { c.enabled },
        scope = scope,
        ownerUserId = { c.owner },
        token = { c.token },
        activeChatId = { c.activeChat },
        sessionActive = { c.sessionActive },
        currentState = { live },
        updateState = { transform ->
            live = transform(live)
            snap("ST")
        },
        text = { message, args -> modText204a(message.name, args.toList()) },
    )
    for (op in c.ops) {
        curGw = op.gw
        try {
            when (op.kind) {
                0 -> {
                    if (op.contactId == null) controller.refreshBlockState()
                    else controller.refreshBlockState(op.contactId)
                }
                1 -> controller.blockContact()
                2 -> controller.unblockContact()
                3 -> controller.reportContact(op.reason, op.description)
                4 -> controller.reportMessage(op.messageId, op.reason, op.description)
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

private val MOD_IDS204a = listOf(
    "", " ", "u1", "u2", "u3", "me", "c1", "x\ty", "ünïcödé", "10",
)

private val MOD_REASONS204a = listOf(
    "", "spam", "abuse", "  spaced  ", "x\ty", "ünïcödé",
)

private fun randomModOp204a(r: Random): ModOp204a {
    val id = MOD_IDS204a[r.nextInt(MOD_IDS204a.size)]
    return when (r.nextInt(5)) {
        0 -> ModOp204a(
            0,
            contactId = if (r.nextInt(4) == 0) null else id,
            gw = r.nextInt(3),
        )
        1 -> ModOp204a(1, gw = r.nextInt(3))
        2 -> ModOp204a(2, gw = r.nextInt(3))
        3 -> ModOp204a(
            3,
            reason = MOD_REASONS204a[r.nextInt(MOD_REASONS204a.size)],
            description = if (r.nextBoolean()) null else MOD_REASONS204a[r.nextInt(MOD_REASONS204a.size)],
            gw = r.nextInt(3),
        )
        else -> ModOp204a(
            4,
            messageId = id,
            reason = MOD_REASONS204a[r.nextInt(MOD_REASONS204a.size)],
            description = if (r.nextBoolean()) null else MOD_REASONS204a[r.nextInt(MOD_REASONS204a.size)],
            gw = r.nextInt(3),
        )
    }
}

private fun randomModCase204a(r: Random): ModCase204a {
    val blockedPool = (MOD_IDS204a + listOf("ghost")).shuffled(r)
    return ModCase204a(
        enabled = r.nextBoolean(),
        owner = listOf("", " ", "me", "u1")[r.nextInt(4)],
        token = listOf("", " ", "token")[r.nextInt(3)],
        activeChat = listOf("", " ", "c1", "c2")[r.nextInt(4)],
        contactId = listOf("", " ", "u2", "me", "u1")[r.nextInt(5)],
        displayName = listOf("", "Bob", "x\ty", "ünïcödé")[r.nextInt(4)],
        sessionActive = r.nextBoolean(),
        initBlocked = r.nextBoolean(),
        initBlocking = r.nextInt(4) == 0,
        initReporting = r.nextInt(4) == 0,
        initWarn = if (r.nextInt(4) == 0) "old" else null,
        initBlockedSet = blockedPool.take(r.nextInt(0, 4)),
        ops = List(r.nextInt(0, 9)) { randomModOp204a(r) },
    )
}

private fun boundaryModCase204a(r: Random): ModCase204a {
    val base = ModCase204a()
    val cases = listOf(
        base.copy(ops = listOf(ModOp204a(1, gw = 0))),
        base.copy(ops = listOf(ModOp204a(1, gw = 1))),
        base.copy(ops = listOf(ModOp204a(1, gw = 2))),
        base.copy(ops = listOf(ModOp204a(1, gw = 0)), enabled = false),
        base.copy(ops = listOf(ModOp204a(1, gw = 0)), token = ""),
        base.copy(ops = listOf(ModOp204a(1, gw = 0)), owner = ""),
        base.copy(ops = listOf(ModOp204a(1, gw = 0)), contactId = ""),
        base.copy(ops = listOf(ModOp204a(1, gw = 0)), contactId = "me", owner = "me"),
        base.copy(ops = listOf(ModOp204a(1, gw = 0)), sessionActive = false),
        base.copy(ops = listOf(ModOp204a(1, gw = 0)), initBlocking = true),
        base.copy(ops = listOf(ModOp204a(1, gw = 0)), initBlocked = true),
        base.copy(ops = listOf(ModOp204a(1, gw = 0)), initBlockedSet = listOf("u2")),
        base.copy(ops = listOf(ModOp204a(2, gw = 0))),
        base.copy(ops = listOf(ModOp204a(2, gw = 1))),
        base.copy(ops = listOf(ModOp204a(2, gw = 2))),
        base.copy(ops = listOf(ModOp204a(2, gw = 0)), enabled = false),
        base.copy(ops = listOf(ModOp204a(2, gw = 0)), initBlocked = true, initBlockedSet = listOf("u2")),
        base.copy(ops = listOf(ModOp204a(2, gw = 0)), sessionActive = false),
        base.copy(ops = listOf(ModOp204a(2, gw = 0)), contactId = ""),
        base.copy(ops = listOf(ModOp204a(2, gw = 0)), initBlocking = true),
        base.copy(ops = listOf(ModOp204a(0, contactId = null, gw = 0))),
        base.copy(ops = listOf(ModOp204a(0, contactId = null, gw = 1))),
        base.copy(ops = listOf(ModOp204a(0, contactId = null, gw = 2))),
        base.copy(ops = listOf(ModOp204a(0, contactId = "u3", gw = 0)), initBlockedSet = listOf("u3")),
        base.copy(ops = listOf(ModOp204a(0, contactId = "", gw = 0))),
        base.copy(ops = listOf(ModOp204a(0, contactId = null, gw = 0)), sessionActive = false),
        base.copy(ops = listOf(ModOp204a(0, contactId = null, gw = 0)), token = ""),
        base.copy(ops = listOf(ModOp204a(0, contactId = null, gw = 0)), initBlocked = true),
        base.copy(ops = listOf(ModOp204a(3, reason = "spam", gw = 0))),
        base.copy(ops = listOf(ModOp204a(3, reason = "spam", gw = 1))),
        base.copy(ops = listOf(ModOp204a(3, reason = "spam", gw = 2))),
        base.copy(ops = listOf(ModOp204a(3, reason = "spam", gw = 0)), enabled = false),
        base.copy(ops = listOf(ModOp204a(3, reason = "spam", gw = 0)), sessionActive = false),
        base.copy(ops = listOf(ModOp204a(3, reason = "spam", description = "d1", gw = 0)), activeChat = ""),
        base.copy(ops = listOf(ModOp204a(3, reason = "", gw = 0))),
        base.copy(ops = listOf(ModOp204a(4, messageId = "m1", reason = "spam", gw = 0))),
        base.copy(ops = listOf(ModOp204a(4, messageId = "m1", reason = "spam", gw = 1))),
        base.copy(ops = listOf(ModOp204a(4, messageId = "m1", reason = "spam", gw = 2))),
        base.copy(ops = listOf(ModOp204a(4, messageId = "m1", reason = "spam", gw = 0)), enabled = false),
        base.copy(ops = listOf(ModOp204a(4, messageId = "", reason = "spam", gw = 0))),
        base.copy(ops = listOf(ModOp204a(4, messageId = "m1", reason = "spam", gw = 0)), sessionActive = false),
        base.copy(ops = listOf(ModOp204a(4, messageId = "m1", reason = "spam", gw = 0)), initReporting = true),
        base.copy(
            ops = listOf(
                ModOp204a(1, gw = 0),
                ModOp204a(3, reason = "spam", gw = 0),
                ModOp204a(4, messageId = "m1", reason = "spam", gw = 0),
                ModOp204a(0, contactId = null, gw = 0),
                ModOp204a(2, gw = 0),
            ),
        ),
        base.copy(ops = listOf(ModOp204a(1, gw = 2), ModOp204a(1, gw = 0))),
        base.copy(ops = emptyList()),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class ChatModerationControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(204101)
        repeat(300) {
            val c = randomModCase204a(r)
            assertEquals(modOracle204a(c), modProd204a(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(204102)
        repeat(200) {
            val c = boundaryModCase204a(r)
            assertEquals(modOracle204a(c), modProd204a(c))
        }
    }

    @Test
    fun pinnedCases() {
        val cases = listOf(
            ModCase204a(ops = listOf(ModOp204a(1, gw = 0))),
            ModCase204a(ops = listOf(ModOp204a(1, gw = 2))),
            ModCase204a(ops = listOf(ModOp204a(2, gw = 0)), initBlocked = true, initBlockedSet = listOf("u2")),
            ModCase204a(ops = listOf(ModOp204a(0, contactId = null, gw = 0))),
            ModCase204a(ops = listOf(ModOp204a(0, contactId = null, gw = 2))),
            ModCase204a(ops = listOf(ModOp204a(3, reason = "spam", gw = 0))),
            ModCase204a(ops = listOf(ModOp204a(4, messageId = "m1", reason = "spam", gw = 0))),
            ModCase204a(ops = listOf(ModOp204a(4, messageId = "m1", reason = "spam", gw = 2))),
            ModCase204a(
                ops = listOf(
                    ModOp204a(1, gw = 0),
                    ModOp204a(4, messageId = "m1", reason = "spam", gw = 0),
                    ModOp204a(2, gw = 1),
                    ModOp204a(0, contactId = null, gw = 0),
                ),
            ),
            ModCase204a(ops = emptyList()),
        )
        for (c in cases) assertEquals(modOracle204a(c), modProd204a(c))
    }
}
