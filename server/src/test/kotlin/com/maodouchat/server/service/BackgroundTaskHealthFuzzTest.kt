package com.maodouchat.server.service

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：驱动与测试共用，名字/错误串里出现分隔符也不怕。
private fun encStr193(s: String): String {
    val out = StringBuilder()
    for (ch in s) {
        val cp = ch.code
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            out.append("%04x".format(0xD800 + (v shr 10)))
            out.append("%04x".format(0xDC00 + (v and 0x3FF)))
        } else {
            out.append("%04x".format(cp))
        }
    }
    return out.toString()
}

private fun decStr193(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

private fun encOpt193(s: String?): String = if (s == null) "NULL" else "S:" + encStr193(s)
private fun decOpt193(h: String): String? = if (h == "NULL") null else decStr193(h.substring(2))

private fun makeErr193(cls: String, msg: String?): Throwable = when (cls) {
    "RuntimeException" -> RuntimeException(msg)
    "IllegalArgumentException" -> IllegalArgumentException(msg)
    "IllegalStateException" -> IllegalStateException(msg)
    "NullPointerException" -> NullPointerException(msg)
    "IOException" -> IOException(msg)
    "CancellationException" -> CancellationException(msg)
    else -> throw IllegalArgumentException("bad err class $cls")
}

private val ERR_CLASSES193 = listOf(
    "RuntimeException", "IllegalArgumentException", "IllegalStateException",
    "NullPointerException", "IOException", "CancellationException"
)

// 一条操作：hb 心跳 / rf 记失败。now 一律显式传入，不走系统时钟。
private data class HealthOp193(val kind: String, val name: String, val cls: String?, val msg: String?, val now: Long)

private class OracleStatus193(
    val name: String,
    var lastSuccessAt: Long? = null,
    var lastErrorAt: Long? = null,
    var lastError: String? = null,
    var consecutiveFailures: Int = 0,
) {
    fun known(): Boolean = lastSuccessAt != null || lastErrorAt != null
    // 降级阈值 3：与 BackgroundTaskHealth.MAX_CONSECUTIVE_FAILURES 同值，oracle 里写死，pinned 另断言两者相等。
    fun healthy(): Boolean = consecutiveFailures < 3
}

// 独立重实现的状态机：不用 ConcurrentHashMap，不走生产代码。
private class HealthOracle193 {
    private val tasks = LinkedHashMap<String, OracleStatus193>()

    fun heartbeat(name: String, now: Long) {
        val st = tasks.getOrPut(name) { OracleStatus193(name) }
        st.lastSuccessAt = now
        st.consecutiveFailures = 0
    }

    fun recordFailure(name: String, err: Throwable, now: Long) {
        val st = tasks.getOrPut(name) { OracleStatus193(name) }
        st.lastErrorAt = now
        st.lastError = "${err.javaClass.simpleName}: ${err.message}".take(500)
        st.consecutiveFailures += 1
    }

    fun apply(op: HealthOp193) {
        if (op.kind == "hb") heartbeat(op.name, op.now)
        else recordFailure(op.name, makeErr193(op.cls!!, op.msg), op.now)
    }

    // 全局健康位 | 降级名（已排序）| 快照（按名排序：成功时/失败时/错误串/连败数）。
    fun encode(): String {
        val healthy = if (tasks.values.all { !it.known() || it.healthy() }) "1" else "0"
        val degraded = tasks.values.filter { it.known() && !it.healthy() }.map { it.name }.sorted()
            .joinToString(",") { encStr193(it) }
        val snap = tasks.keys.sorted().joinToString(",") { name ->
            val st = tasks.getValue(name)
            encStr193(name) + ":" + (st.lastSuccessAt?.toString() ?: "N") + "," +
                (st.lastErrorAt?.toString() ?: "N") + "," +
                (st.lastError?.let { encStr193(it) } ?: "N") + "," + st.consecutiveFailures
        }
        return "$healthy|$degraded|$snap"
    }
}

private fun healthEncode193(h: BackgroundTaskHealth): String {
    val healthy = if (h.allHealthy()) "1" else "0"
    val degraded = h.degradedTasks().joinToString(",") { encStr193(it) }
    val snap = h.snapshot().keys.sorted().joinToString(",") { name ->
        val st = h.snapshot().getValue(name)
        encStr193(name) + ":" + (st.lastSuccessAt?.toString() ?: "N") + "," +
            (st.lastErrorAt?.toString() ?: "N") + "," +
            (st.lastError?.let { encStr193(it) } ?: "N") + "," + st.consecutiveFailures
    }
    return "$healthy|$degraded|$snap"
}

private val HEALTH_NAMES193 = listOf(
    "", "gc", "db", "sync", "a/b", "é", "😀", "x".repeat(60), "task-1", "  ", "a\nb", "a:b", "UPPER", "gc"
)
private val HEALTH_MSGS193: List<String?> = listOf(
    null, "", "boom", "x".repeat(600), "é😀".repeat(300), "a\nb", "err: detail",
    "  padded  ", "x".repeat(477), "x".repeat(478), "x".repeat(479), "tab\there"
)
private val HEALTH_NOWS193 = listOf(
    0L, 1L, -1L, 1000L, 9999999999999L, Long.MAX_VALUE, Long.MIN_VALUE, 86400000L
)

private fun randomHealthName193(r: Random): String =
    if (r.nextInt(4) == 0) HEALTH_NAMES193[r.nextInt(HEALTH_NAMES193.size)]
    else (0 until r.nextInt(8)).joinToString("") { "ab12-_/é😀\n\t"[r.nextInt(12)].toString() }

private fun randomHealthMsg193(r: Random): String? =
    if (r.nextInt(3) == 0) HEALTH_MSGS193[r.nextInt(HEALTH_MSGS193.size)]
    else (0 until r.nextInt(30)).joinToString("") { "abcxyz 019:é😀\n\t"[r.nextInt(16)].toString() }

private fun randomHealthOp193(r: Random): HealthOp193 {
    val name = randomHealthName193(r)
    val now = if (r.nextInt(3) == 0) HEALTH_NOWS193[r.nextInt(HEALTH_NOWS193.size)]
    else r.nextLong(-1000, 10_000_000)
    return if (r.nextBoolean()) {
        HealthOp193("hb", name, null, null, now)
    } else {
        HealthOp193("rf", name, ERR_CLASSES193[r.nextInt(ERR_CLASSES193.size)], randomHealthMsg193(r), now)
    }
}

private fun randomHealthScript193(r: Random): List<HealthOp193> =
    List(r.nextInt(1, 13)) { randomHealthOp193(r) }

// 阈值附近的脚本：同一名字连败 0..5 次，心跳穿插其间。
private fun boundaryHealthScript193(r: Random): List<HealthOp193> {
    val name = randomHealthName193(r)
    val ops = ArrayList<HealthOp193>()
    repeat(r.nextInt(0, 6)) {
        ops.add(HealthOp193("rf", name, ERR_CLASSES193[r.nextInt(ERR_CLASSES193.size)], randomHealthMsg193(r), r.nextLong(0, 10000)))
        if (r.nextInt(3) == 0) ops.add(HealthOp193("hb", name, null, null, r.nextLong(0, 10000)))
    }
    if (r.nextBoolean()) ops.add(HealthOp193("hb", name, null, null, r.nextLong(0, 10000)))
    if (ops.isEmpty()) ops.add(randomHealthOp193(r))
    return ops
}

// ---------- tests ----------

class BackgroundTaskHealthFuzzTest {

    @Test
    fun healthDifferential193() {
        val r = Random(193001)
        repeat(300) {
            val script = randomHealthScript193(r)
            val oracle = HealthOracle193()
            val prod = BackgroundTaskHealth(clock = { 0L })
            for (op in script) {
                oracle.apply(op)
                if (op.kind == "hb") prod.heartbeat(op.name, op.now)
                else prod.recordFailure(op.name, makeErr193(op.cls!!, op.msg), op.now)
                assertEquals(oracle.encode(), healthEncode193(prod))
            }
        }
    }

    @Test
    fun healthBoundaryDifferential193() {
        val r = Random(193002)
        repeat(300) {
            val script = boundaryHealthScript193(r)
            val oracle = HealthOracle193()
            val prod = BackgroundTaskHealth(clock = { 0L })
            for (op in script) {
                oracle.apply(op)
                if (op.kind == "hb") prod.heartbeat(op.name, op.now)
                else prod.recordFailure(op.name, makeErr193(op.cls!!, op.msg), op.now)
                assertEquals(oracle.encode(), healthEncode193(prod))
            }
        }
    }

    @Test
    fun healthPinned193() {
        assertEquals(3, BackgroundTaskHealth.MAX_CONSECUTIVE_FAILURES)
        val oracle = HealthOracle193()
        val prod = BackgroundTaskHealth(clock = { 0L })
        fun step(op: HealthOp193) {
            oracle.apply(op)
            if (op.kind == "hb") prod.heartbeat(op.name, op.now)
            else prod.recordFailure(op.name, makeErr193(op.cls!!, op.msg), op.now)
            assertEquals(oracle.encode(), healthEncode193(prod))
        }
        // 空实例健康。
        assertEquals("1||", oracle.encode())
        assertEquals("1||", healthEncode193(prod))
        // 两次失败仍健康，第三次降级；心跳清零连败。
        step(HealthOp193("rf", "gc", "RuntimeException", "boom", 1000L))
        assertEquals("1||" + encStr193("gc") + ":N,1000," + encStr193("RuntimeException: boom") + ",1", oracle.encode())
        step(HealthOp193("rf", "gc", "RuntimeException", "boom", 2000L))
        assertEquals("1||" + encStr193("gc") + ":N,2000," + encStr193("RuntimeException: boom") + ",2", oracle.encode())
        step(HealthOp193("rf", "gc", "RuntimeException", "boom", 3000L))
        assertEquals("0|" + encStr193("gc") + "|" + encStr193("gc") + ":N,3000," + encStr193("RuntimeException: boom") + ",3", oracle.encode())
        step(HealthOp193("hb", "gc", null, null, 4000L))
        assertEquals("1||" + encStr193("gc") + ":4000,3000," + encStr193("RuntimeException: boom") + ",0", oracle.encode())
        // null 消息拼成 "RuntimeException: null"；超长错误串截到 500 码元。
        step(HealthOp193("rf", "long", "IllegalStateException", "x".repeat(600), 5000L))
        val longErr = "IllegalStateException: " + "x".repeat(600)
        assertEquals(500, prod.snapshot().getValue("long").lastError!!.length)
        assertEquals(longErr.take(500), prod.snapshot().getValue("long").lastError)
        step(HealthOp193("rf", "nil", "RuntimeException", null, 6000L))
        assertEquals("RuntimeException: null", prod.snapshot().getValue("nil").lastError)
        // 降级名按字典序排列，与插入顺序无关。
        step(HealthOp193("rf", "zz", "RuntimeException", "e", 7000L))
        step(HealthOp193("rf", "zz", "RuntimeException", "e", 7000L))
        step(HealthOp193("rf", "zz", "RuntimeException", "e", 7000L))
        step(HealthOp193("rf", "aa", "RuntimeException", "e", 7000L))
        step(HealthOp193("rf", "aa", "RuntimeException", "e", 7000L))
        step(HealthOp193("rf", "aa", "RuntimeException", "e", 7000L))
        assertEquals(listOf("aa", "zz"), prod.degradedTasks())
    }
}
