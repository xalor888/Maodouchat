package com.maodouchat.domain.conversation

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr205b(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit205b(b: Boolean): String = if (b) "1" else "0"

private fun opt205b(s: String?): String = s?.let { encStr205b(it) } ?: "~"

private data class AnnItem205b(
    val id: String,
    val title: String,
    val content: String,
    val level: String,
    val startsAt: Long,
    val expiresAt: Long,
    val status: String,
    val acked: Boolean,
)

// op kind: 0 refreshAnnouncements，1 ackAnnouncement，2 fetchPushVerifyKey。
// gw：0 成功 1 失败 2 抛错。
private data class AnnOp205b(
    val kind: Int,
    val gw: Int = 0,
    val ackId: String = "a1",
)

private data class AnnCase205b(
    val hasSession: Boolean = true,
    val owner: String = "me",
    val ownerFlip: Boolean = false,
    // 0 恒真，1 恒假，2 首 call 真、之后假。
    val sessMode: Int = 0,
    val nowMs: Long = 1_700_000_000_000L,
    val initAnnouncements: List<AnnItem205b> = emptyList(),
    val initAppliedKey: String? = null,
    // 0 正常 JSON，1 空白，2 非法 JSON，3 缺 announcements 键，
    // 4 announcements 非数组，5 异形字段字面量。
    val rawMode: Int = 0,
    val items: List<AnnItem205b> = emptyList(),
    val keyPool: String = "key-raw",
    val ops: List<AnnOp205b> = emptyList(),
)

private const val ANN_WEIRD_RAW205b = """{"announcements":[{"id":"w1","title":["x"],"content":123,"level":"INFO","startsAt":"1700000000000","expiresAt":9999999999999,"status":"ACTIVE","acked":"true"},{"id":"","title":"T","content":"C","level":"INFO","startsAt":0,"expiresAt":9999999999999,"status":"ACTIVE","acked":false},{"id":"w2","title":"T","content":"C","level":"INFO","startsAt":0,"expiresAt":9999999999999,"status":"ACTIVE","acked":false},42,{"id":"w3","title":"T","content":"C","level":"EMERGENCY","startsAt":1700000000000,"expiresAt":1700000000000,"status":"ACTIVE","acked":false}]}"""

private fun jsonEscape205b(s: String): String {
    val out = StringBuilder()
    for (ch in s) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (ch.code < 0x20) out.append("\\u%04x".format(ch.code)) else out.append(ch)
        }
    }
    return out.toString()
}

private fun annJson205b(items: List<AnnItem205b>): String {
    val sb = StringBuilder()
    sb.append("{\"announcements\":[")
    items.forEachIndexed { idx, a ->
        if (idx > 0) sb.append(",")
        sb.append("{\"id\":\"").append(jsonEscape205b(a.id)).append("\",")
        sb.append("\"title\":\"").append(jsonEscape205b(a.title)).append("\",")
        sb.append("\"content\":\"").append(jsonEscape205b(a.content)).append("\",")
        sb.append("\"level\":\"").append(jsonEscape205b(a.level)).append("\",")
        sb.append("\"startsAt\":").append(a.startsAt).append(",")
        sb.append("\"expiresAt\":").append(a.expiresAt).append(",")
        sb.append("\"status\":\"").append(jsonEscape205b(a.status)).append("\",")
        sb.append("\"acked\":").append(a.acked).append("}")
    }
    sb.append("]}")
    return sb.toString()
}

private fun buildAnnRaw205b(c: AnnCase205b): String = when (c.rawMode) {
    0 -> annJson205b(c.items)
    1 -> "   "
    2 -> "{not json"
    3 -> """{"other":[]}"""
    4 -> """{"announcements":{}}"""
    5 -> ANN_WEIRD_RAW205b
    else -> throw IllegalArgumentException("unknown rawMode ${c.rawMode}")
}

private val ANN_LEVEL_PRIORITY205b: Map<String, Int> = mapOf(
    "EMERGENCY" to 0,
    "MAINTENANCE" to 1,
    "WARNING" to 2,
    "INFO" to 3,
)

private fun JsonObject.annSafeString205b(key: String): String {
    val element = this[key] ?: return ""
    if (element is JsonNull) return ""
    if (element is JsonPrimitive) return element.content.takeIf { it != "null" }.orEmpty()
    return element.toString()
}

private fun JsonObject.annSafeLong205b(key: String): Long {
    val primitive = this[key] as? JsonPrimitive ?: return 0L
    return primitive.longOrNull ?: 0L
}

private fun JsonObject.annSafeBoolean205b(key: String): Boolean {
    val primitive = this[key] as? JsonPrimitive ?: return false
    return primitive.booleanOrNull ?: false
}

private fun annFilter205b(items: List<AnnouncementData>, nowMs: Long): List<AnnouncementData> {
    return items
        .filter { a ->
            !a.acked &&
                a.startsAt <= nowMs &&
                a.expiresAt >= nowMs &&
                a.status == "ACTIVE" &&
                a.level in ANN_LEVEL_PRIORITY205b
        }
        .sortedWith(compareBy({ ANN_LEVEL_PRIORITY205b[it.level] ?: 99 }, { it.startsAt }))
}

private fun annParseFilter205b(raw: String, nowMs: Long): List<AnnouncementData> {
    if (raw.isBlank()) return emptyList()
    val json = Json { ignoreUnknownKeys = true }
    return runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val arr = root["announcements"] as? JsonArray ?: return@runCatching emptyList()
        val items = arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o.annSafeString205b("id")
            if (id.isBlank()) return@mapNotNull null
            AnnouncementData(
                id = id,
                title = o.annSafeString205b("title"),
                content = o.annSafeString205b("content"),
                level = o.annSafeString205b("level"),
                startsAt = o.annSafeLong205b("startsAt"),
                expiresAt = o.annSafeLong205b("expiresAt"),
                status = o.annSafeString205b("status"),
                acked = o.annSafeBoolean205b("acked"),
            )
        }
        annFilter205b(items, nowMs)
    }.getOrNull().orEmpty()
}

private fun encAnnItem205b(a: AnnouncementData): String = listOf(
    encStr205b(a.id),
    encStr205b(a.title),
    encStr205b(a.content),
    encStr205b(a.level),
    a.startsAt.toString(),
    a.expiresAt.toString(),
    encStr205b(a.status),
    bit205b(a.acked),
).joinToString(":")

private fun encAnnState205b(announcements: List<AnnouncementData>, appliedKey: String?): String =
    announcements.joinToString(",") { encAnnItem205b(it) } + "|" + opt205b(appliedKey)

private fun annOracle205b(c: AnnCase205b): String {
    val effects = mutableListOf<String>()
    var live: List<AnnouncementData> = c.initAnnouncements.map {
        AnnouncementData(it.id, it.title, it.content, it.level, it.startsAt, it.expiresAt, it.status, it.acked)
    }
    var appliedKey: String? = c.initAppliedKey
    var ownerCalls = 0
    var sessCalls = 0
    fun ownerId(): String {
        val idx = ownerCalls++
        return if (idx == 0) c.owner else if (c.ownerFlip) "other" else c.owner
    }
    fun mayContinue(@Suppress("UNUSED_PARAMETER") owner: String): Boolean {
        val idx = sessCalls++
        return when (c.sessMode) {
            0 -> true
            1 -> false
            else -> idx == 0
        }
    }
    fun snap(prefix: String) {
        effects += prefix + ":" + encAnnState205b(live, appliedKey)
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                val owner = ownerId()
                if (!c.hasSession) {
                    // fall through to FIN
                } else if (!mayContinue(owner)) {
                    // fall through to FIN
                } else {
                    effects += "CALL:fetchAnn:" + op.gw
                    if (op.gw == 2) {
                        effects += "THROW:RuntimeException"
                    } else {
                        val raw = if (op.gw == 0) buildAnnRaw205b(c) else ""
                        if (!raw.isBlank()) {
                            if (mayContinue(owner)) {
                                val list = annParseFilter205b(raw, c.nowMs)
                                if (ownerId() == owner) {
                                    live = list
                                    snap("ST")
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                val owner = ownerId()
                if (!c.hasSession) {
                    // fall through to FIN
                } else {
                    effects += "CALL:ack:" + op.gw
                    if (op.gw == 2) {
                        effects += "THROW:RuntimeException"
                    } else if (ownerId() == owner) {
                        live = live.filterNot { it.id == op.ackId }
                        snap("ST")
                    }
                }
            }
            2 -> {
                val owner = ownerId()
                if (!c.hasSession) {
                    // fall through to FIN
                } else if (!mayContinue(owner)) {
                    // fall through to FIN
                } else {
                    effects += "CALL:key:" + op.gw
                    if (op.gw == 2) {
                        effects += "THROW:RuntimeException"
                    } else {
                        val raw = if (op.gw == 0) c.keyPool else ""
                        if (!raw.isBlank()) {
                            appliedKey = raw
                            snap("ST")
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

private fun annProd205b(c: AnnCase205b): String {
    val effects = mutableListOf<String>()
    var live: List<AnnouncementData> = c.initAnnouncements.map {
        AnnouncementData(it.id, it.title, it.content, it.level, it.startsAt, it.expiresAt, it.status, it.acked)
    }
    var appliedKey: String? = c.initAppliedKey
    var ownerCalls = 0
    var sessCalls = 0
    fun snap(prefix: String) {
        effects += prefix + ":" + encAnnState205b(live, appliedKey)
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
    val coordinator = ChatListAnnouncementCoordinator(
        scope = scope,
        hasSession = { c.hasSession },
        ownerUserId = {
            val idx = ownerCalls++
            if (idx == 0) c.owner else if (c.ownerFlip) "other" else c.owner
        },
        sessionMayContinue = {
            val idx = sessCalls++
            when (c.sessMode) {
                0 -> true
                1 -> false
                else -> idx == 0
            }
        },
        getActiveAnnouncements = { live },
        updateActiveAnnouncements = { transform ->
            live = transform(live)
            snap("ST")
        },
        fetchActiveAnnouncements = {
            effects += "CALL:fetchAnn:" + curGw
            when (curGw) {
                0 -> Result.success(buildAnnRaw205b(c))
                1 -> Result.failure(RuntimeException("boom"))
                else -> throw RuntimeException("boom")
            }
        },
        ackAnnouncementRemote = {
            effects += "CALL:ack:" + curGw
            when (curGw) {
                0 -> Result.success("ok")
                1 -> Result.failure<Any?>(RuntimeException("boom"))
                else -> throw RuntimeException("boom")
            }
        },
        fetchPushVerifyKeyRaw = {
            effects += "CALL:key:" + curGw
            when (curGw) {
                0 -> Result.success(c.keyPool)
                1 -> Result.failure(RuntimeException("boom"))
                else -> throw RuntimeException("boom")
            }
        },
        applyPushVerifyKey = { raw ->
            appliedKey = raw
            snap("ST")
        },
        nowMs = { c.nowMs },
    )
    for (op in c.ops) {
        curGw = op.gw
        try {
            when (op.kind) {
                0 -> coordinator.refreshAnnouncements()
                1 -> coordinator.ackAnnouncement(op.ackId)
                2 -> coordinator.fetchPushVerifyKey()
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

private val ANN_IDS205b = listOf("", " ", "a1", "a2", "a3", "x\ty", "ünïcödé")
private val ANN_LEVELS205b = listOf("EMERGENCY", "MAINTENANCE", "WARNING", "INFO", "info", "", "UNKNOWN", " EMERGENCY")
private val ANN_STATUS205b = listOf("ACTIVE", "INACTIVE", "", "active")
private val ANN_NOWS205b = listOf(1_700_000_000_000L, 1_000_000L, 0L, -1L, 999L)
private val ANN_KEYS205b = listOf("key-raw", "  ", "", "k1", "x\ty")

private fun randomAnnItem205b(r: Random, nowMs: Long): AnnItem205b {
    val delta = listOf(-2_000_000L, -1L, 0L, 1L, 2_000_000L)
    return AnnItem205b(
        id = ANN_IDS205b[r.nextInt(ANN_IDS205b.size)],
        title = listOf("", "T", "x\ty", "ünïcödé")[r.nextInt(4)],
        content = listOf("", "C", "x\ty")[r.nextInt(3)],
        level = ANN_LEVELS205b[r.nextInt(ANN_LEVELS205b.size)],
        startsAt = nowMs + delta[r.nextInt(delta.size)],
        expiresAt = nowMs + delta[r.nextInt(delta.size)],
        status = ANN_STATUS205b[r.nextInt(ANN_STATUS205b.size)],
        acked = r.nextBoolean(),
    )
}

private fun randomAnnOp205b(r: Random): AnnOp205b {
    return when (r.nextInt(3)) {
        0 -> AnnOp205b(kind = 0, gw = r.nextInt(3))
        1 -> AnnOp205b(kind = 1, gw = r.nextInt(3), ackId = ANN_IDS205b[r.nextInt(ANN_IDS205b.size)])
        else -> AnnOp205b(kind = 2, gw = r.nextInt(3))
    }
}

private fun randomAnnCase205b(r: Random): AnnCase205b {
    val nowMs = ANN_NOWS205b[r.nextInt(ANN_NOWS205b.size)]
    return AnnCase205b(
        hasSession = r.nextInt(6) != 0,
        owner = listOf("", " ", "me", "u1")[r.nextInt(4)],
        ownerFlip = r.nextInt(4) == 0,
        sessMode = listOf(0, 0, 0, 1, 2)[r.nextInt(5)],
        nowMs = nowMs,
        initAnnouncements = List(r.nextInt(0, 4)) { randomAnnItem205b(r, nowMs) },
        initAppliedKey = if (r.nextInt(4) == 0) ANN_KEYS205b[r.nextInt(ANN_KEYS205b.size)] else null,
        rawMode = if (r.nextInt(6) == 0) r.nextInt(1, 6) else 0,
        items = List(r.nextInt(0, 5)) { randomAnnItem205b(r, nowMs) },
        keyPool = ANN_KEYS205b[r.nextInt(ANN_KEYS205b.size)],
        ops = List(r.nextInt(0, 9)) { randomAnnOp205b(r) },
    )
}

private fun boundaryAnnCase205b(r: Random): AnnCase205b {
    val base = AnnCase205b()
    val item = AnnItem205b("a1", "T", "C", "INFO", 0L, 2_000_000_000_000L, "ACTIVE", false)
    val expired = AnnItem205b("a2", "Old", "C", "INFO", 0L, 10L, "ACTIVE", false)
    val cases = listOf(
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item, expired)),
        base.copy(ops = listOf(AnnOp205b(0, gw = 1)), items = listOf(item)),
        base.copy(ops = listOf(AnnOp205b(0, gw = 2)), items = listOf(item)),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item), sessMode = 1),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item), sessMode = 2),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item), hasSession = false),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item), ownerFlip = true),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item), rawMode = 1),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item), rawMode = 2),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item), rawMode = 3),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item), rawMode = 4),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), rawMode = 5),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item), nowMs = 0L),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item.copy(startsAt = 1_700_000_000_000L, expiresAt = 1_700_000_000_000L))),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item.copy(level = "EMERGENCY"), item.copy(level = "INFO", id = "a2"), item.copy(level = "MAINTENANCE", id = "a3"))),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item.copy(acked = true))),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item.copy(status = "INACTIVE"))),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0)), items = emptyList()),
        base.copy(ops = listOf(AnnOp205b(1, gw = 0, ackId = "a1")), initAnnouncements = listOf(item)),
        base.copy(ops = listOf(AnnOp205b(1, gw = 1, ackId = "a1")), initAnnouncements = listOf(item)),
        base.copy(ops = listOf(AnnOp205b(1, gw = 2, ackId = "a1")), initAnnouncements = listOf(item)),
        base.copy(ops = listOf(AnnOp205b(1, gw = 0, ackId = "ghost")), initAnnouncements = listOf(item)),
        base.copy(ops = listOf(AnnOp205b(1, gw = 0, ackId = "a1")), initAnnouncements = listOf(item), ownerFlip = true),
        base.copy(ops = listOf(AnnOp205b(1, gw = 0, ackId = "a1")), initAnnouncements = listOf(item), hasSession = false),
        base.copy(ops = listOf(AnnOp205b(2, gw = 0))),
        base.copy(ops = listOf(AnnOp205b(2, gw = 1))),
        base.copy(ops = listOf(AnnOp205b(2, gw = 2))),
        base.copy(ops = listOf(AnnOp205b(2, gw = 0)), keyPool = "  "),
        base.copy(ops = listOf(AnnOp205b(2, gw = 0)), keyPool = ""),
        base.copy(ops = listOf(AnnOp205b(2, gw = 0)), sessMode = 1),
        base.copy(ops = listOf(AnnOp205b(2, gw = 0)), hasSession = false),
        base.copy(ops = listOf(AnnOp205b(0, gw = 0), AnnOp205b(1, gw = 0, ackId = "a1"), AnnOp205b(2, gw = 0)), items = listOf(item, expired)),
        base.copy(ops = listOf(AnnOp205b(1, gw = 2, ackId = "a1"), AnnOp205b(1, gw = 0, ackId = "a1")), initAnnouncements = listOf(item)),
        base.copy(ops = emptyList()),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class ChatListAnnouncementCoordinatorFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(205401)
        repeat(300) {
            val c = randomAnnCase205b(r)
            assertEquals(annOracle205b(c), annProd205b(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(205402)
        repeat(200) {
            val c = boundaryAnnCase205b(r)
            assertEquals(annOracle205b(c), annProd205b(c))
        }
    }

    @Test
    fun pinnedCases() {
        val item = AnnItem205b("a1", "T", "C", "INFO", 0L, 2_000_000_000_000L, "ACTIVE", false)
        val cases = listOf(
            AnnCase205b(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item)),
            AnnCase205b(ops = listOf(AnnOp205b(0, gw = 2)), items = listOf(item)),
            AnnCase205b(ops = listOf(AnnOp205b(0, gw = 0)), rawMode = 5),
            AnnCase205b(ops = listOf(AnnOp205b(0, gw = 0)), items = listOf(item), sessMode = 2),
            AnnCase205b(ops = listOf(AnnOp205b(1, gw = 0, ackId = "a1")), initAnnouncements = listOf(item)),
            AnnCase205b(ops = listOf(AnnOp205b(1, gw = 2, ackId = "a1")), initAnnouncements = listOf(item)),
            AnnCase205b(ops = listOf(AnnOp205b(2, gw = 0))),
            AnnCase205b(ops = listOf(AnnOp205b(2, gw = 2))),
            AnnCase205b(ops = listOf(AnnOp205b(0, gw = 0), AnnOp205b(1, gw = 0, ackId = "a1"), AnnOp205b(2, gw = 0)), items = listOf(item)),
            AnnCase205b(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(annOracle205b(c), annProd205b(c))
        }
    }
}
