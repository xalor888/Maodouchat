package com.maodouchat.ui.screen.groupplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupPlayJsonTest {

    @Test
    fun `array parses bare json array`() {
        val arr = GroupPlayJson.array("""[{"id":"p1","question":"Q"}]""")
        assertEquals(1, arr.size)
        assertEquals("p1", arr[0].jsonObj()["id"]!!.toString().trim('"'))
    }

    @Test
    fun `array unwraps polls wrapper that used to blank the vote page`() {
        val arr = GroupPlayJson.array("""{"polls":[{"id":"p1","question":"Q"}]}""")
        assertEquals(1, arr.size)
        assertEquals("Q", arr[0].jsonObj()["question"]!!.toString().trim('"'))
    }

    @Test
    fun `array treats empty payload as empty list`() {
        assertEquals(0, GroupPlayJson.array("").size)
        assertEquals(0, GroupPlayJson.array("[]").size)
    }

    @Test
    fun `array rejects error objects instead of swallowing them as empty`() {
        assertFails { GroupPlayJson.array("""{"error":"无权访问该群"}""") }
    }

    @Test
    fun `errorMessage reads server ErrorResponse`() {
        assertEquals(
            "PK 投票失败：仅限群成员且未结束",
            GroupPlayJson.errorMessage("""{"error":"PK 投票失败：仅限群成员且未结束"}""")
        )
        assertNull(GroupPlayJson.errorMessage(""))
        assertNull(GroupPlayJson.errorMessage("[]"))
        assertTrue(GroupPlayJson.errorMessage("""{"error":""}""") == null)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObj() =
        this as kotlinx.serialization.json.JsonObject
}
