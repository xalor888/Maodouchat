package com.maodouchat.server.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class PutJsonElementTest {

    @Serializable
    private data class PollView(val id: String, val options: List<String>, val closed: Boolean)

    private inline fun <reified T> oldWay(key: String, value: T) = buildJsonObject {
        put(key, Json.parseToJsonElement(Json.encodeToString(value)))
    }

    private inline fun <reified T> newWay(key: String, value: T) = buildJsonObject {
        putJsonElement(key, value)
    }

    @Test
    fun `string list matches old round-trip`() {
        val v = listOf("a1", "b2", "c3")
        assertEquals(oldWay("pins", v), newWay("pins", v))
    }

    @Test
    fun `data class matches old round-trip`() {
        val v = PollView("p1", listOf("x", "y"), closed = true)
        assertEquals(oldWay("poll", v), newWay("poll", v))
    }

    @Test
    fun `map of counts matches old round-trip`() {
        val v = mapOf("like" to 3, "dislike" to 0)
        assertEquals(oldWay("byCommand", v), newWay("byCommand", v))
    }

    @Test
    fun `empty list matches old round-trip`() {
        val v = emptyList<String>()
        assertEquals(oldWay("chatIds", v), newWay("chatIds", v))
    }
}
