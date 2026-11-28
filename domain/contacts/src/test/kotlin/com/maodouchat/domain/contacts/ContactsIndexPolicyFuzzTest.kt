package com.maodouchat.domain.contacts

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [ContactsIndexPolicy] (checklist line 931, 36th block).
 * Test-only, zero production changes.
 *
 * The oracle re-derives the initial letter, leading rows, letter index and
 * grouping from the documented rules. Note the two asymmetries: only a count
 * strictly greater than zero adds a header row, and a letter missing from the
 * sizes map counts as zero rather than missing. A Python mini-model
 * differentially checks both oracles on 5000 random inputs each (0 diffs)
 * before this file was written.
 */
class ContactsIndexPolicyFuzzTest {

    private fun oracleInitial(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "#"
        val first = trimmed[0]
        if (first.code in 'A'.code..'Z'.code || first.code in 'a'.code..'z'.code) {
            return first.uppercase()
        }
        if (first.code in 0x4e00..0x9fff) return first.toString()
        return "#"
    }

    private fun oracleLeading(incoming: Int, outgoing: Int, groupInvites: Int, trailing: Int = 3): Int {
        var n = if (trailing < 0) 0 else trailing
        if (incoming > 0) n += incoming + 1
        if (outgoing > 0) n += outgoing + 1
        if (groupInvites > 0) n += groupInvites + 1
        return n
    }

    private fun oracleLetterIndex(
        letters: List<String>,
        sizes: Map<String, Int>,
        target: String,
        leading: Int = 1
    ): Int {
        var index = leading
        for (letter in letters) {
            if (letter == target) return index
            index += 1 + (sizes[letter] ?: 0)
        }
        return -1
    }

    private fun oracleGroupBy(names: List<Pair<String, String>>): Map<String, List<String>> {
        val grouped = mutableMapOf<String, MutableList<String>>()
        for ((id, name) in names) {
            grouped.getOrPut(oracleInitial(name)) { mutableListOf() }.add(id)
        }
        return grouped.toSortedMap()
    }

    private val nameFirsts = listOf("a", "Z", "b", "c", "张", "李", "王", "1", "_", "É", "Ω", " ", "")

    private fun randomName(rnd: Random): String {
        return when (rnd.nextInt(8)) {
            0 -> ""
            1 -> "   "
            2 -> nameFirsts.random(rnd)
            3 -> "  " + nameFirsts.random(rnd) + "x  "
            4 -> "😀x"
            5 -> nameFirsts.random(rnd) + nameFirsts.random(rnd)
            6 -> listOf("alice", "Alice", "Bob", "张三", "123", "Émile", "Ωmega", "_x").random(rnd)
            else -> nameFirsts.random(rnd) + "abc"
        }
    }

    private fun randomCount(rnd: Random): Int {
        return when (rnd.nextInt(8)) {
            0 -> 0
            1 -> -1
            2 -> -5
            3 -> 1
            4 -> 2
            5 -> Int.MAX_VALUE
            6 -> Int.MIN_VALUE
            else -> rnd.nextInt(0, 50)
        }
    }

    private val letterPool = listOf("A", "B", "C", "D", "E", "张", "李", "#")

    private fun randomLetterList(rnd: Random): Pair<List<String>, Map<String, Int>> {
        val letters = letterPool.shuffled(rnd).take(rnd.nextInt(0, letterPool.size + 1))
        val sizes = mutableMapOf<String, Int>()
        for (letter in letters) {
            if (rnd.nextInt(10) < 7) sizes[letter] = rnd.nextInt(0, 6)
        }
        return letters to sizes
    }

    @Test
    fun initialForRandomOracle() {
        val rnd = Random(36)
        repeat(300) {
            val name = randomName(rnd)
            assertEquals(oracleInitial(name), ContactsIndexPolicy.initialFor(name), "name=<$name>")
        }
    }

    @Test
    fun leadingFixedItemCountRandomOracle() {
        val rnd = Random(36)
        repeat(300) {
            val incoming = randomCount(rnd)
            val outgoing = randomCount(rnd)
            val groupInvites = randomCount(rnd)
            val trailing = listOf(-2, 0, 1, 3, 5, 100).random(rnd)
            assertEquals(
                oracleLeading(incoming, outgoing, groupInvites, trailing),
                ContactsIndexPolicy.leadingFixedItemCount(incoming, outgoing, groupInvites, trailing),
                "incoming=$incoming outgoing=$outgoing groupInvites=$groupInvites trailing=$trailing"
            )
        }
    }

    @Test
    fun letterListIndexRandomOracle() {
        val rnd = Random(36)
        repeat(300) {
            val (letters, sizes) = randomLetterList(rnd)
            val target = if (rnd.nextInt(10) < 6 && letters.isNotEmpty()) letters.random(rnd)
                else listOf("Z", "王", "#", "Q").random(rnd)
            val leading = listOf(0, 1, 3).random(rnd)
            assertEquals(
                oracleLetterIndex(letters, sizes, target, leading),
                ContactsIndexPolicy.letterListIndex(letters, sizes, target, leading),
                "letters=$letters sizes=$sizes target=$target leading=$leading"
            )
        }
    }

    @Test
    fun groupByInitialRandomOracle() {
        val rnd = Random(36)
        val ids = listOf("u1", "u2", "u3", "a", "b", "x1")
        repeat(300) {
            val names = List(rnd.nextInt(0, 9)) { ids.random(rnd) to randomName(rnd) }
            val expected = oracleGroupBy(names)
            val actual = ContactsIndexPolicy.groupByInitial(names)
            assertEquals(expected, actual, "names=$names")
            // Invariants: keys are exactly the initials, ids keep input order, nothing lost.
            assertEquals(names.map { oracleInitial(it.second) }.toSet(), actual.keys, "keys names=$names")
            assertEquals(names.size, actual.values.sumOf { it.size }, "total names=$names")
            for ((letter, groupIds) in actual) {
                val inOrder = names.filter { oracleInitial(it.second) == letter }.map { it.first }
                assertEquals(inOrder, groupIds, "order letter=$letter names=$names")
            }
        }
    }

    @Test
    fun letterListIndexMonotonicity() {
        val rnd = Random(36)
        repeat(200) {
            val (letters, sizes) = randomLetterList(rnd)
            val leading = listOf(0, 1, 3).random(rnd)
            var previous = Int.MIN_VALUE
            for (letter in letters) {
                val index = ContactsIndexPolicy.letterListIndex(letters, sizes, letter, leading)
                assertTrue(index > previous, "not increasing: letters=$letters sizes=$sizes leading=$leading")
                previous = index
            }
            assertEquals(-1, ContactsIndexPolicy.letterListIndex(letters, sizes, "ZZZ", leading), "absent letter")
            assertEquals(-1, ContactsIndexPolicy.letterListIndex(emptyList(), emptyMap(), "A", leading), "empty list")
        }
    }

    @Test
    fun pins() {
        assertEquals("#", ContactsIndexPolicy.initialFor(""), "empty name")
        assertEquals("#", ContactsIndexPolicy.initialFor("   "), "blank name")
        assertEquals("A", ContactsIndexPolicy.initialFor("alice"), "lowercase")
        assertEquals("A", ContactsIndexPolicy.initialFor("Alice"), "uppercase")
        assertEquals("张", ContactsIndexPolicy.initialFor("张三"), "CJK")
        assertEquals("#", ContactsIndexPolicy.initialFor("1abc"), "digit first")
        assertEquals("#", ContactsIndexPolicy.initialFor("😀x"), "emoji first")
        assertEquals("B", ContactsIndexPolicy.initialFor("  bob"), "leading spaces")
        assertEquals("#", ContactsIndexPolicy.initialFor("Émile"), "non-ASCII latin")
        assertEquals("Z", ContactsIndexPolicy.initialFor("z"), "z")
        assertEquals(3, ContactsIndexPolicy.leadingFixedItemCount(0, 0, 0), "no groups")
        assertEquals(0, ContactsIndexPolicy.leadingFixedItemCount(0, 0, 0, 0), "zero trailing")
        assertEquals(0, ContactsIndexPolicy.leadingFixedItemCount(0, 0, 0, -2), "negative trailing clamped")
        assertEquals(8, ContactsIndexPolicy.leadingFixedItemCount(2, 0, 1), "two groups")
        assertEquals(7, ContactsIndexPolicy.leadingFixedItemCount(-1, 3, 0), "negative count adds nothing")
        assertEquals(11, ContactsIndexPolicy.leadingFixedItemCount(1, 1, 1, 5), "three groups")
        val letters = listOf("A", "B", "C")
        val sizes = mapOf("A" to 2, "B" to 0)
        assertEquals(4, ContactsIndexPolicy.letterListIndex(letters, sizes, "B"), "B index")
        assertEquals(5, ContactsIndexPolicy.letterListIndex(letters, sizes, "C"), "C index with missing size")
        assertEquals(-1, ContactsIndexPolicy.letterListIndex(letters, sizes, "D"), "absent letter")
        assertEquals(-1, ContactsIndexPolicy.letterListIndex(emptyList(), emptyMap(), "A"), "empty letters")
        assertEquals(0, ContactsIndexPolicy.letterListIndex(listOf("A"), emptyMap(), "A", 0), "zero leading")
        assertEquals(
            mapOf("#" to listOf("4"), "A" to listOf("1"), "B" to listOf("2"), "张" to listOf("3")),
            ContactsIndexPolicy.groupByInitial(
                listOf("1" to "alice", "2" to "Bob", "3" to "张三", "4" to "123")
            ),
            "groupByInitial"
        )
    }
}
