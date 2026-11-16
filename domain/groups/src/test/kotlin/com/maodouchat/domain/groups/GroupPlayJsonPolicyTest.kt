package com.maodouchat.domain.groups

import com.maodouchat.core.model.ChainEntryItem
import com.maodouchat.core.model.ChainListItem
import com.maodouchat.core.model.CheckinRankItem
import com.maodouchat.core.model.PkListItem
import com.maodouchat.core.model.PollListItem
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

    @Test
    fun `parseChainList maps all fields`() {
        val out = GroupPlayJsonPolicy.parseChainList(
            "[{\"id\":\"c1\",\"title\":\"接龙\",\"topic\":\"周末\",\"active\":true,\"entryCount\":3,\"maxEntries\":10,\"myJoined\":true}]"
        )
        assertEquals(
            listOf(ChainListItem("c1", "接龙", "周末", true, 3, 10, true)), out
        )
    }

    @Test
    fun `parseChainList missing fields fall back to defaults`() {
        val out = GroupPlayJsonPolicy.parseChainList("[{}]")
        assertEquals(listOf(ChainListItem("", "", "", false, 0, 0, false)), out)
    }

    @Test
    fun `parseChainEntries maps rows from array`() {
        val out = GroupPlayJsonPolicy.parseChainEntries(
            JSONArray("[{\"id\":\"e1\",\"userId\":\"u1\",\"sequence\":2,\"content\":\"hello\"}]")
        )
        assertEquals(listOf(ChainEntryItem("e1", "u1", 2, "hello")), out)
    }

    @Test
    fun `parseChainEntries null array yields empty list`() {
        assertEquals(emptyList(), GroupPlayJsonPolicy.parseChainEntries(null))
    }

    @Test
    fun `parseChainEntries skips non objects`() {
        val out = GroupPlayJsonPolicy.parseChainEntries(
            JSONArray("[42,{\"id\":\"e2\",\"userId\":\"u2\",\"sequence\":1,\"content\":\"x\"}]")
        )
        assertEquals(listOf(ChainEntryItem("e2", "u2", 1, "x")), out)
    }

    @Test
    fun `parseCheckinRanking maps fields`() {
        val out = GroupPlayJsonPolicy.parseCheckinRanking(
            "[{\"userId\":\"u1\",\"streak\":5,\"totalCount\":20}]"
        )
        assertEquals(listOf(CheckinRankItem("u1", 5, 20)), out)
    }

    @Test
    fun `parseCheckinRanking keeps server order`() {
        val out = GroupPlayJsonPolicy.parseCheckinRanking(
            "[{\"userId\":\"u1\",\"streak\":5,\"totalCount\":20},{\"userId\":\"u2\",\"streak\":9,\"totalCount\":3}]"
        )
        assertEquals(
            listOf(CheckinRankItem("u1", 5, 20), CheckinRankItem("u2", 9, 3)), out
        )
    }

    @Test
    fun `parseCheckinRanking missing fields fall back to defaults`() {
        val out = GroupPlayJsonPolicy.parseCheckinRanking("[{}]")
        assertEquals(listOf(CheckinRankItem("", 0, 0)), out)
    }

    @Test
    fun `parsePkList maps all fields`() {
        val out = GroupPlayJsonPolicy.parsePkList(
            "[{\"id\":\"p1\",\"leftTitle\":\"A\",\"rightTitle\":\"B\",\"active\":true,\"leftCount\":4,\"rightCount\":6,\"totalVoters\":10,\"myChoice\":\"left\"}]"
        )
        assertEquals(
            listOf(PkListItem("p1", "A", "B", true, 4, 6, 10, "left")), out
        )
    }

    @Test
    fun `parsePkList blank or missing myChoice becomes null`() {
        val blank = GroupPlayJsonPolicy.parsePkList("[{\"id\":\"p1\",\"myChoice\":\"  \"}]")
        assertEquals(listOf(PkListItem("p1", "", "", false, 0, 0, 0, null)), blank)
        val missing = GroupPlayJsonPolicy.parsePkList("[{\"id\":\"p2\"}]")
        assertEquals(listOf(PkListItem("p2", "", "", false, 0, 0, 0, null)), missing)
    }

    @Test
    fun `parsePolls maps fields and nested arrays`() {
        val out = GroupPlayJsonPolicy.parsePolls(
            "[{\"id\":\"poll1\",\"question\":\"q?\",\"options\":[\"a\",\"b\"],\"multi\":true,\"anonymous\":false,\"closed\":false,\"counts\":[3,5],\"totalVoters\":8,\"myVotes\":[1]}]"
        )
        assertEquals(
            listOf(
                PollListItem(
                    "poll1", "q?", listOf("a", "b"), true, false, false,
                    listOf(3, 5), 8, listOf(1)
                )
            ),
            out
        )
    }

    @Test
    fun `parsePolls missing nested arrays yield empty lists`() {
        val out = GroupPlayJsonPolicy.parsePolls("[{}]")
        assertEquals(
            listOf(PollListItem("", "", emptyList(), false, false, false, emptyList(), 0, emptyList())),
            out
        )
    }

    @Test
    fun `parsePolls keeps mismatched options and counts lengths`() {
        val out = GroupPlayJsonPolicy.parsePolls(
            "[{\"id\":\"poll1\",\"options\":[\"a\",\"b\",\"c\"],\"counts\":[7]}]"
        )
        assertEquals(
            listOf(
                PollListItem(
                    "poll1", "", listOf("a", "b", "c"), false, false, false,
                    listOf(7), 0, emptyList()
                )
            ),
            out
        )
    }
}
