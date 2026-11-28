package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallSignalingIdempotencyStoreFuzzTest {

    private val random = Random(0xCA11_2026L)

    private val callIds = listOf("c1", "c2", "", "call-9")
    private val users = listOf("u1", "u2", "", "u-9")
    private val types = listOf("offer", "OFFER", " Offer ", "ice-candidate", "ICE-CANDIDATE", "answer", "hang-up", "")
    private val payloads = listOf("p1", "p2", "", "mid|0|cand-a", "{}", "p1")
    private val keys = listOf("", "   ", "k1", "k2", "key-9")

    private data class Op(
        val callId: String,
        val from: String,
        val type: String,
        val payload: String,
        val key: String,
    )

    private fun randomOp() = Op(
        callIds.random(random),
        users.random(random),
        types.random(random),
        payloads.random(random),
        keys.random(random),
    )

    // 生产键推导的逐字复述：非空 idempotencyKey 优先（"id|<key>"），
    // 否则回退复合键 "<callId>|<from>|<type.trim().lowercase()>|<payload>"。
    private fun deriveKey(op: Op): String =
        if (op.key.isNotBlank()) "id|${op.key}" else "${op.callId}|${op.from}|${op.type.trim().lowercase()}|${op.payload}"

    // 有界 FIFO 集合模型的逐字复述：首次出现记 true 并入队、超 maxSize 淘汰最旧；
    // 已存在记 false，不淘汰。
    private fun modelRemember(model: LinkedHashSet<String>, key: String, maxSize: Int): Boolean {
        if (!model.add(key)) return false
        while (model.size > maxSize) model.remove(model.first())
        return true
    }

    @Test
    fun `remember matches linked-set model over random sequences`() {
        repeat(20) {
            val maxSize = random.nextInt(1, 5)
            val store = CallSignalingIdempotencyStore(maxSize = maxSize)
            val model = linkedSetOf<String>()
            repeat(300) {
                val op = randomOp()
                assertEquals(
                    modelRemember(model, deriveKey(op), maxSize),
                    store.remember(op.callId, op.from, op.type, op.payload, op.key),
                    "op=$op maxSize=$maxSize",
                )
            }
        }
    }

    @Test
    fun `blank idempotency key falls back to composite key`() {
        val store = CallSignalingIdempotencyStore()
        assertTrue(store.remember("c", "u", "OFFER", "p1", idempotencyKey = ""))
        assertFalse(store.remember("c", "u", "offer", "p1", idempotencyKey = "   "))
        assertTrue(store.remember("c", "u", "offer", "p1", idempotencyKey = "k1"))
        assertTrue(store.remember("c", "u", "offer", "p2", idempotencyKey = ""))
    }

    @Test
    fun `idempotency key shadows differing composite fields`() {
        val store = CallSignalingIdempotencyStore()
        assertTrue(store.remember("c1", "u1", "offer", "p1", idempotencyKey = "k"))
        assertFalse(store.remember("c2", "u2", "answer", "p9", idempotencyKey = "k"))
        assertTrue(store.remember("c2", "u2", "answer", "p9", idempotencyKey = "k2"))
    }

    @Test
    fun `eviction boundary is exactly max size`() {
        val store = CallSignalingIdempotencyStore(maxSize = 3)
        assertTrue(store.remember("c", "u", "offer", "p1", idempotencyKey = "k1"))
        assertTrue(store.remember("c", "u", "offer", "p2", idempotencyKey = "k2"))
        assertTrue(store.remember("c", "u", "offer", "p3", idempotencyKey = "k3"))
        assertFalse(store.remember("c", "u", "offer", "p1", idempotencyKey = "k1"))
        assertTrue(store.remember("c", "u", "offer", "p4", idempotencyKey = "k4"))
        assertTrue(store.remember("c", "u", "offer", "p1", idempotencyKey = "k1"))
        assertFalse(store.remember("c", "u", "offer", "p4", idempotencyKey = "k4"))
    }

    @Test
    fun `clear resets the store`() {
        repeat(30) {
            val store = CallSignalingIdempotencyStore(maxSize = 3)
            val ops = List(40) { randomOp() }
            ops.forEach { store.remember(it.callId, it.from, it.type, it.payload, it.key) }
            store.clear()
            val model = linkedSetOf<String>()
            ops.forEach { op ->
                assertEquals(
                    modelRemember(model, deriveKey(op), 3),
                    store.remember(op.callId, op.from, op.type, op.payload, op.key),
                    "op=$op",
                )
            }
        }
    }
}
