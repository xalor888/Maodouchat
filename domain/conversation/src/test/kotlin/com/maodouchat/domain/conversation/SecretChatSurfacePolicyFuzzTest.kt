package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Secret surface ids restated by hand: a plain insertion-ordered set;
// blank ids never enter, clearExcept keeps only a non-blank keep id
// (blank keep clears everything).
internal class SecretSurfaceOracle180 {
    private val ids = LinkedHashSet<String>()

    fun mark(chatId: String) {
        if (chatId.isBlank()) return
        ids.add(chatId)
    }

    fun clear() {
        ids.clear()
    }

    fun clearOne(chatId: String) {
        if (chatId.isBlank()) return
        ids.remove(chatId)
    }

    fun clearExcept(keepChatId: String?) {
        val keep = keepChatId?.takeIf { it.isNotBlank() }
        if (keep == null) {
            ids.clear()
            return
        }
        ids.retainAll(setOf(keep))
    }

    fun hasActive(): Boolean = ids.isNotEmpty()

    fun activeIds(): List<String> = ids.toList()
}

internal data class SecretSurfaceOp180(
    val k: String,
    val id: String,
)

internal fun runSecretSurfaceScriptOracle180(ops: List<SecretSurfaceOp180>): String {
    val model = SecretSurfaceOracle180()
    val out = ArrayList<String>()
    for (op in ops) {
        when (op.k) {
            "mark" -> { model.mark(op.id); out.add("null") }
            "clear" -> { model.clear(); out.add("null") }
            "clearOne" -> { model.clearOne(op.id); out.add("null") }
            "clearExcept" -> { model.clearExcept(op.id); out.add("null") }
            "has" -> out.add(model.hasActive().toString())
            "ids" -> out.add("[" + model.activeIds().joinToString(",") { escJsonSecretSurface180(it) } + "]")
            else -> error("unknown op ${op.k}")
        }
    }
    return "[" + out.joinToString(",") + "]"
}

// Production runs the same script against the real singleton; the
// singleton is process-global, so every script starts from a clear.
internal fun runSecretSurfaceScriptProduction180(ops: List<SecretSurfaceOp180>): String {
    SecretChatSurfacePolicy.clearSurfaceMarkers()
    val out = ArrayList<String>()
    for (op in ops) {
        when (op.k) {
            "mark" -> { SecretChatSurfacePolicy.markSurfaceActive(op.id); out.add("null") }
            "clear" -> { SecretChatSurfacePolicy.clearSurfaceMarkers(); out.add("null") }
            "clearOne" -> { SecretChatSurfacePolicy.clearSurfaceMarker(op.id); out.add("null") }
            "clearExcept" -> { SecretChatSurfacePolicy.clearSurfaceMarkersExcept(op.id); out.add("null") }
            "has" -> out.add(SecretChatSurfacePolicy.hasActiveSecretSurface().toString())
            "ids" -> out.add(
                "[" + SecretChatSurfacePolicy.activeSecretSurfaceChatIds()
                    .joinToString(",") { escJsonSecretSurface180(it) } + "]"
            )
            else -> error("unknown op ${op.k}")
        }
    }
    return "[" + out.joinToString(",") + "]"
}

// ---------- generators ----------

internal data class SecretSurfaceCase180(
    val ops: List<SecretSurfaceOp180>,
)

internal val SECRET_SURFACE_ID_POOL180: List<String> = listOf(
    "", " ", "  ", "a", "b", "alice", "bob", "  alice  ", "a\nb", "a\tb",
    "u\u00e9", "\u00a0", "a\uD83D\uDE00b", "chat-1", "chat-1", "CHAT-1",
    "x".repeat(80), "id:with:colons", "id with spaces",
)

internal val SECRET_SURFACE_OP_KINDS180: List<String> = listOf(
    "mark", "clear", "clearOne", "clearExcept", "has", "ids",
)

internal fun randomSecretSurfaceCase180(rnd: Random): SecretSurfaceCase180 {
    val ops = ArrayList<SecretSurfaceOp180>()
    repeat(rnd.nextInt(0, 13)) {
        val k = SECRET_SURFACE_OP_KINDS180[rnd.nextInt(SECRET_SURFACE_OP_KINDS180.size)]
        val id = if (rnd.nextInt(2) == 0) {
            SECRET_SURFACE_ID_POOL180[rnd.nextInt(SECRET_SURFACE_ID_POOL180.size)]
        } else {
            val alphabet = "abc019 _,.-\t\n\u00a0\u00e9"
            val len = rnd.nextInt(0, 10)
            val sb = StringBuilder()
            repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
            sb.toString()
        }
        ops.add(SecretSurfaceOp180(k, id))
    }
    return SecretSurfaceCase180(ops)
}

internal fun escJsonSecretSurface180(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun secretSurfaceWireJson180(c: SecretSurfaceCase180): String {
    val ops = c.ops.joinToString(",") { op ->
        "{\"k\":${escJsonSecretSurface180(op.k)}," +
            "\"id\":${escJsonSecretSurface180(op.id)}}"
    }
    return "{\"ops\":[$ops]}"
}

class SecretChatSurfacePolicyFuzzTest {

    private fun checkSecretSurface180(c: SecretSurfaceCase180) {
        assertEquals(
            runSecretSurfaceScriptOracle180(c.ops),
            runSecretSurfaceScriptProduction180(c.ops),
            "secretSurface($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Every id through mark / observe / clearExcept / clearOne.
        for (id in SECRET_SURFACE_ID_POOL180) {
            checkSecretSurface180(
                SecretSurfaceCase180(
                    listOf(
                        SecretSurfaceOp180("mark", id),
                        SecretSurfaceOp180("has", ""),
                        SecretSurfaceOp180("ids", ""),
                        SecretSurfaceOp180("clearExcept", id),
                        SecretSurfaceOp180("has", ""),
                        SecretSurfaceOp180("ids", ""),
                        SecretSurfaceOp180("clearOne", id),
                        SecretSurfaceOp180("has", ""),
                        SecretSurfaceOp180("ids", ""),
                    )
                )
            )
        }
        // Two ids: keep-one clears the other, keep-absent clears both.
        for (a in listOf("a", "b", " ", "")) {
            for (b in listOf("a", "b", " ", "")) {
                checkSecretSurface180(
                    SecretSurfaceCase180(
                        listOf(
                            SecretSurfaceOp180("mark", a),
                            SecretSurfaceOp180("mark", b),
                            SecretSurfaceOp180("ids", ""),
                            SecretSurfaceOp180("clearExcept", a),
                            SecretSurfaceOp180("ids", ""),
                            SecretSurfaceOp180("mark", a),
                            SecretSurfaceOp180("mark", b),
                            SecretSurfaceOp180("clearExcept", "nobody"),
                            SecretSurfaceOp180("ids", ""),
                        )
                    )
                )
            }
        }
        // Duplicate marks keep one entry, order is first insertion.
        checkSecretSurface180(
            SecretSurfaceCase180(
                listOf(
                    SecretSurfaceOp180("mark", "b"),
                    SecretSurfaceOp180("mark", "a"),
                    SecretSurfaceOp180("mark", "b"),
                    SecretSurfaceOp180("mark", "a"),
                    SecretSurfaceOp180("ids", ""),
                    SecretSurfaceOp180("clearOne", "b"),
                    SecretSurfaceOp180("mark", "b"),
                    SecretSurfaceOp180("ids", ""),
                )
            )
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(180003)
        repeat(4000) { checkSecretSurface180(randomSecretSurfaceCase180(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // All 3-op scripts over mark/has/ids with two ids.
        val kinds = listOf("mark", "has", "ids")
        val ids = listOf("a", "b")
        for (k1 in kinds) {
            for (k2 in kinds) {
                for (k3 in kinds) {
                    for (id1 in ids) {
                        for (id2 in ids) {
                            for (id3 in ids) {
                                checkSecretSurface180(
                                    SecretSurfaceCase180(
                                        listOf(
                                            SecretSurfaceOp180(k1, id1),
                                            SecretSurfaceOp180(k2, id2),
                                            SecretSurfaceOp180(k3, id3),
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        // Long mark runs with periodic clears (order + dedup).
        for (n in 0..40) {
            val ops = ArrayList<SecretSurfaceOp180>()
            for (i in 0 until n) {
                ops.add(SecretSurfaceOp180("mark", "u${i % 5}"))
                if (i % 7 == 3) ops.add(SecretSurfaceOp180("clearOne", "u${i % 5}"))
            }
            ops.add(SecretSurfaceOp180("ids", ""))
            checkSecretSurface180(SecretSurfaceCase180(ops))
        }
    }
}
