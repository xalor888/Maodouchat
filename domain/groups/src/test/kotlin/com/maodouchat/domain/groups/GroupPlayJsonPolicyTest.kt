package com.maodouchat.domain.groups

import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupPlayJsonPolicyTest {

    @Test
    fun `text array maps each object`() {
        val out = GroupPlayJsonPolicy.parseObjectList(
            "[{\"userId\":\"a\",\"streak\":3},{\"userId\":\"b\",\"streak\":5}]"
        ) { it.optString("userId") + ":" + it.optInt("streak") }
        assertEquals(listOf("a:3", "b:5"), out)
    }

    @Test
    fun `wrapper object unwraps to its array`() {
        val out = GroupPlayJsonPolicy.parseObjectList(
            "{\"items\":[{\"userId\":\"a\"}]}"
        ) { it.optString("userId") }
        assertEquals(listOf("a"), out)
    }

    @Test
    fun `single object wraps to one item`() {
        val out = GroupPlayJsonPolicy.parseObjectList(
            "{\"userId\":\"a\",\"streak\":1}"
        ) { it.optString("userId") }
        assertEquals(listOf("a"), out)
    }

    @Test
    fun `empty text yields empty list`() {
        assertEquals(emptyList(), GroupPlayJsonPolicy.parseObjectList("") { it.optString("id") })
        assertEquals(emptyList(), GroupPlayJsonPolicy.parseObjectList("  ") { it.optString("id") })
    }

    @Test
    fun `non object entries are skipped`() {
        val out = GroupPlayJsonPolicy.parseObjectList(
            "[1,{\"userId\":\"a\"},{\"userId\":\"b\"},\"x\",null]"
        ) { it.optString("userId") }
        assertEquals(listOf("a", "b"), out)
    }

    @Test
    fun `null array yields empty list`() {
        assertEquals(
            emptyList(),
            GroupPlayJsonPolicy.parseObjectList(null) { it.optString("id") },
        )
    }

    @Test
    fun `array overload parses and skips non objects`() {
        val out = GroupPlayJsonPolicy.parseObjectList(
            JSONArray("[{\"id\":\"1\"},{\"id\":\"2\"},42]")
        ) { it.optString("id") }
        assertEquals(listOf("1", "2"), out)
    }

    @Test
    fun `stringList maps values`() {
        assertEquals(
            listOf("a", "b"),
            GroupPlayJsonPolicy.stringList(JSONArray("[\"a\",\"b\"]")),
        )
    }

    @Test
    fun `stringList null yields empty list`() {
        assertEquals(emptyList(), GroupPlayJsonPolicy.stringList(null))
    }

    @Test
    fun `intList maps values`() {
        assertEquals(
            listOf(1, 2, 3),
            GroupPlayJsonPolicy.intList(JSONArray("[1,2,3]")),
        )
    }

    @Test
    fun `intList null yields empty list`() {
        assertEquals(emptyList(), GroupPlayJsonPolicy.intList(null))
    }

    @Test
    fun `stringList and intList skip nothing but coerce missing slots`() {
        assertEquals(listOf("", "x"), GroupPlayJsonPolicy.stringList(JSONArray("[\"\",\"x\"]")))
        assertEquals(listOf(0, 7), GroupPlayJsonPolicy.intList(JSONArray("[0,7]")))
    }
}
