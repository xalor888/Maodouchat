package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// replace restated by hand: every message whose id matches the target
// is swapped for the updated message; all other rows keep their place
// and their content, duplicates included.
internal fun replaceOracle170(
    messages: List<Message>,
    messageId: String,
    updated: Message
): List<Message> =
    messages.map { if (it.id == messageId) updated else it }

// Production replace returns the list with matches swapped.
internal fun replaceProduction170(
    messages: List<Message>,
    messageId: String,
    updated: Message
): List<Message> =
    TimelineMessageReplacePolicy.replace(messages, messageId, updated)

// ---------- generators ----------

// Messages are built with a fixed timestamp so equality stays
// deterministic; the body marks the position (body0, body1, ...)
// so a result list shows exactly which rows were swapped.
internal fun message170(id: String, content: String): Message =
    Message(id = id, chatId = "c1", senderId = "u1", content = content, timestamp = 0L)

internal fun messages170(ids: List<String>): List<Message> =
    ids.mapIndexed { index, id -> message170(id, "body$index") }

internal data class ReplaceCase170(
    val ids: List<String>,
    val target: String,
    val updatedId: String,
    val updatedContent: String
)

internal val REPLACE_ID_POOL170: List<String> = listOf(
    "", "m1", "m2", "m3", " ", "m1 ", "M1"
)

internal val REPLACE_CONTENT_POOL170: List<String> = listOf(
    "", "hello", "new", "x".repeat(30), "a b", "  "
)

internal fun randomReplaceCase170(rnd: Random): ReplaceCase170 {
    val n = rnd.nextInt(6)
    val ids = List(n) { REPLACE_ID_POOL170[rnd.nextInt(REPLACE_ID_POOL170.size)] }
    return ReplaceCase170(
        ids = ids,
        target = REPLACE_ID_POOL170[rnd.nextInt(REPLACE_ID_POOL170.size)],
        updatedId = REPLACE_ID_POOL170[rnd.nextInt(REPLACE_ID_POOL170.size)],
        updatedContent = REPLACE_CONTENT_POOL170[rnd.nextInt(REPLACE_CONTENT_POOL170.size)]
    )
}

internal fun replaceWireJson170(c: ReplaceCase170): String {
    // escJson170 lives in ReadReceiptQueryPolicyFuzzTest (same package).
    val ids = c.ids.joinToString(",") { escJson170(it) }
    return "{\"ids\":[$ids],\"target\":${escJson170(c.target)}," +
        "\"updatedId\":${escJson170(c.updatedId)}," +
        "\"updatedContent\":${escJson170(c.updatedContent)}}"
}

class TimelineMessageReplacePolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val updated = message170("m9", "new")
        val cases = listOf(
            // Empty list stays empty.
            Triple(emptyList<String>(), "m1", updated) to emptyList<Message>(),
            // Target absent: every row keeps its place and content.
            Triple(listOf("m1", "m2"), "m3", updated) to messages170(listOf("m1", "m2")),
            // Single match is swapped, the rest untouched.
            Triple(listOf("m1", "m2"), "m1", updated) to
                listOf(updated, message170("m2", "body1")),
            // Duplicate ids: every matching row is swapped, not just the first.
            Triple(listOf("m1", "m1", "m2"), "m1", updated) to
                listOf(updated, updated, message170("m2", "body2")),
            // Empty id matches empty ids.
            Triple(listOf("", "m1"), "", updated) to
                listOf(updated, message170("m1", "body1")),
            // Same id, new content: the row is still replaced.
            Triple(listOf("m1"), "m1", message170("m1", "fresh")) to
                listOf(message170("m1", "fresh")),
            // Id match is case-sensitive.
            Triple(listOf("m1"), "M1", updated) to messages170(listOf("m1"))
        )
        for ((input, expected) in cases) {
            val (ids, target, upd) = input
            val messages = messages170(ids)
            assertEquals(
                expected,
                replaceProduction170(messages, target, upd),
                "replace(ids=$ids, target=$target)"
            )
            assertEquals(
                expected,
                replaceOracle170(messages, target, upd),
                "oracle(ids=$ids, target=$target)"
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(170003)
        repeat(4000) {
            val c = randomReplaceCase170(rnd)
            val messages = messages170(c.ids)
            val updated = message170(c.updatedId, c.updatedContent)
            assertEquals(
                replaceOracle170(messages, c.target, updated),
                replaceProduction170(messages, c.target, updated),
                "replace(random)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every target against the full id pool (with a duplicate m1).
        val ids = listOf("m1", "m1", "m2", "m3", "", " ", "M1")
        for (target in REPLACE_ID_POOL170) {
            val messages = messages170(ids)
            val updated = message170("m9", "new")
            assertEquals(
                replaceOracle170(messages, target, updated),
                replaceProduction170(messages, target, updated),
                "sweep-target(target=$target)"
            )
        }
        // Every list length 0..6, ids cycling through the pool.
        for (n in 0..6) {
            val listIds = List(n) { REPLACE_ID_POOL170[it % REPLACE_ID_POOL170.size] }
            for (target in listOf("m1", "m2", "", "zzz")) {
                val messages = messages170(listIds)
                val updated = message170("m9", "new")
                assertEquals(
                    replaceOracle170(messages, target, updated),
                    replaceProduction170(messages, target, updated),
                    "sweep-len(n=$n, target=$target)"
                )
            }
        }
        // Every pool id as the updated id, target fixed on m1.
        for (updatedId in REPLACE_ID_POOL170) {
            val messages = messages170(listOf("m1", "m2"))
            val updated = message170(updatedId, "new")
            assertEquals(
                replaceOracle170(messages, "m1", updated),
                replaceProduction170(messages, "m1", updated),
                "sweep-updated(updatedId=$updatedId)"
            )
        }
    }
}
