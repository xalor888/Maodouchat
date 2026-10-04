package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminTokenRevokeParseFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private class RevokeErr(message: String) : IllegalStateException(message)

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~\t\n"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomScalar(random: Random, allowNull: Boolean = true): JsonElement = when (random.nextInt(6)) {
        0 -> JsonPrimitive(random.nextBoolean())
        1 -> JsonPrimitive(random.nextLong(-1000, 1000))
        2 -> JsonPrimitive(random.nextDouble(-1000.0, 1000.0))
        3 -> JsonPrimitive(randomString(random, random.nextInt(0, 40)))
        4 -> if (allowNull) JsonNull else JsonPrimitive(randomString(random, 5))
        else -> JsonArray(List(random.nextInt(0, 5)) { randomScalar(random, allowNull = false) })
    }

    // 旧内联写法的逐字复刻（原先直接在路由里 respond 400，复刻里用异常携带文案比对）。
    private fun oldPrefixWay(element: JsonElement?): String {
        if (element == null) return ""
        return (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?: throw RevokeErr("tokenHashPrefix must be a string")
    }

    private fun oldAllWay(element: JsonElement?): Boolean {
        if (element == null) return false
        return (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw RevokeErr("all must be a boolean")
    }

    private fun newPrefixAsThrowing(element: JsonElement?): String =
        when (val parsed = parseAdminRevokePrefix(element)) {
            is AdminRevokePrefixResult.Ok -> parsed.prefix
            is AdminRevokePrefixResult.Invalid -> throw RevokeErr(parsed.message)
        }

    private fun newAllAsThrowing(element: JsonElement?): Boolean =
        when (val parsed = parseAdminRevokeAll(element)) {
            is AdminRevokeAllResult.Ok -> parsed.revokeAll
            is AdminRevokeAllResult.Invalid -> throw RevokeErr(parsed.message)
        }

    @Test
    fun `prefix old and new agree on seeded random elements`() {
        val random = Random(20002)
        repeat(ITERATIONS) { i ->
            val element: JsonElement? = if (random.nextInt(10) == 0) null else randomScalar(random)
            val old = runCatching { oldPrefixWay(element) }
            val new = runCatching { newPrefixAsThrowing(element) }
            assertEquals(old.isSuccess, new.isSuccess, "iteration " + i + ": throw behavior must match")
            if (old.isSuccess) {
                assertEquals(old.getOrNull(), new.getOrNull(), "iteration " + i + ": prefix must match old inline way")
            } else {
                val oldEx = old.exceptionOrNull()!!
                val newEx = new.exceptionOrNull()!!
                assertEquals(oldEx::class, newEx::class, "iteration " + i + ": exception class must match")
                assertEquals(oldEx.message, newEx.message, "iteration " + i + ": 400 message must match")
            }
        }
    }

    @Test
    fun `all old and new agree on seeded random elements`() {
        val random = Random(20003)
        repeat(ITERATIONS) { i ->
            val element: JsonElement? = if (random.nextInt(10) == 0) null else randomScalar(random)
            val old = runCatching { oldAllWay(element) }
            val new = runCatching { newAllAsThrowing(element) }
            assertEquals(old.isSuccess, new.isSuccess, "iteration " + i + ": throw behavior must match")
            if (old.isSuccess) {
                assertEquals(old.getOrNull(), new.getOrNull(), "iteration " + i + ": revokeAll must match old inline way")
            } else {
                val oldEx = old.exceptionOrNull()!!
                val newEx = new.exceptionOrNull()!!
                assertEquals(oldEx::class, newEx::class, "iteration " + i + ": exception class must match")
                assertEquals(oldEx.message, newEx.message, "iteration " + i + ": 400 message must match")
            }
        }
    }

    @Test
    fun `prefix strict string semantics are pinned`() {
        assertEquals("", (parseAdminRevokePrefix(null) as AdminRevokePrefixResult.Ok).prefix)
        assertEquals("abc", (parseAdminRevokePrefix(JsonPrimitive("  abc  ")) as AdminRevokePrefixResult.Ok).prefix)
        assertEquals("", (parseAdminRevokePrefix(JsonPrimitive("   ")) as AdminRevokePrefixResult.Ok).prefix)
        assertTrue(parseAdminRevokePrefix(JsonPrimitive(123)) is AdminRevokePrefixResult.Invalid)
        assertTrue(parseAdminRevokePrefix(JsonPrimitive(true)) is AdminRevokePrefixResult.Invalid)
        assertTrue(parseAdminRevokePrefix(JsonNull) is AdminRevokePrefixResult.Invalid)
        assertTrue(parseAdminRevokePrefix(JsonArray(emptyList())) is AdminRevokePrefixResult.Invalid)
        assertTrue(parseAdminRevokePrefix(JsonObject(emptyMap())) is AdminRevokePrefixResult.Invalid)
        val invalid = parseAdminRevokePrefix(JsonPrimitive(1)) as AdminRevokePrefixResult.Invalid
        assertEquals("tokenHashPrefix must be a string", invalid.message)
    }

    @Test
    fun `all strict boolean semantics are pinned`() {
        assertEquals(false, (parseAdminRevokeAll(null) as AdminRevokeAllResult.Ok).revokeAll)
        assertEquals(true, (parseAdminRevokeAll(JsonPrimitive(true)) as AdminRevokeAllResult.Ok).revokeAll)
        assertEquals(false, (parseAdminRevokeAll(JsonPrimitive(false)) as AdminRevokeAllResult.Ok).revokeAll)
        // 字符串 "true" 必须拒绝：booleanOrNull 会宽松解析它。
        assertTrue(parseAdminRevokeAll(JsonPrimitive("true")) is AdminRevokeAllResult.Invalid)
        assertTrue(parseAdminRevokeAll(JsonPrimitive("false")) is AdminRevokeAllResult.Invalid)
        assertTrue(parseAdminRevokeAll(JsonPrimitive(1)) is AdminRevokeAllResult.Invalid)
        assertTrue(parseAdminRevokeAll(JsonNull) is AdminRevokeAllResult.Invalid)
        assertTrue(parseAdminRevokeAll(JsonArray(emptyList())) is AdminRevokeAllResult.Invalid)
        assertTrue(parseAdminRevokeAll(JsonObject(emptyMap())) is AdminRevokeAllResult.Invalid)
        val invalid = parseAdminRevokeAll(JsonPrimitive("true")) as AdminRevokeAllResult.Invalid
        assertEquals("all must be a boolean", invalid.message)
    }
}
