// GroupPlayFieldEscape encode/decode (142nd block). Oracle
// reimplements the two one-line replaces from production text.
package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- escape oracles ----------

private fun escOracle(s: String): String = s.replace("|", "\u0001").replace("^", "\u0002")

private fun unescOracle(s: String): String = s.replace("\u0001", "|").replace("\u0002", "^")

// ---------- generators (mirrored by Oracle142 GEN, seed 142003) ----------

private val FIELD_POOL = arrayOf(
    "", "hello", "a|b^c", "|", "^", "\u0001", "\u0002", "||^^",
    "a\u0001b", "a\u0002b", "|^", "^|", "\u0001\u0002", "a|b|c^d^e",
    "x".repeat(40), "a b", "a/b", "é", "a\u0000b",
)
private val FIELD_ALPHABET = "ab|^ \u0001\u0002"

private fun randomFieldText142(rnd: Random, maxLen: Int = 16): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, maxLen + 1)
    repeat(len) {
        when (rnd.nextInt(6)) {
            0 -> sb.append(('a' + rnd.nextInt(26)))
            1 -> sb.append(FIELD_ALPHABET[rnd.nextInt(FIELD_ALPHABET.length)])
            2 -> sb.append(('0' + rnd.nextInt(10)))
            3 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            4 -> sb.appendCodePoint(rnd.nextInt(0x10000, 0x1FFFF))
            else -> sb.append(FIELD_POOL[rnd.nextInt(FIELD_POOL.size)])
        }
    }
    return sb.toString()
}

private fun genFieldText(rnd: Random): String =
    if (rnd.nextInt(3) == 0) FIELD_POOL[rnd.nextInt(FIELD_POOL.size)]
    else randomFieldText142(rnd, 16)

class GroupPlayFieldEscapeFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val inputs = listOf(
            "",
            "hello",
            "a|b^c",
            "|",
            "^",
            "\u0001",
            "\u0002",
            "||^^",
            "a\u0001b",
            "a\u0002b",
            "|^",
            "^|",
            "\u0001\u0002",
            "a|b|c^d^e",
            "x".repeat(40),
            "a b",
            "a/b",
            "é",
            "a\u0000b",
            "|\u0001^\u0002",
            "\u0001|\u0002^",
        )
        for (input in inputs) {
            assertEquals(escOracle(input), GroupPlayFieldEscape.esc(input), "esc input=$input")
            assertEquals(unescOracle(input), GroupPlayFieldEscape.unesc(input), "unesc input=$input")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(142003)
        repeat(1500) { i ->
            val input = genFieldText(rnd)
            assertEquals(escOracle(input), GroupPlayFieldEscape.esc(input), "esc iter=$i input=$input")
            assertEquals(unescOracle(input), GroupPlayFieldEscape.unesc(input), "unesc iter=$i input=$input")
        }
    }

    @Test
    fun sweepExhaustive() {
        fun editsOf(target: String): List<String> {
            val out = mutableListOf<String>()
            val edits = " _Xa0\u0000"
            for (pos in target.indices) {
                for (rep in edits) out.add(target.substring(0, pos) + rep + target.substring(pos + 1))
                out.add(target.substring(0, pos) + target.substring(pos + 1))
                val ch = target[pos]
                out.add(target.substring(0, pos) + (if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar()) + target.substring(pos + 1))
            }
            for (pos in 0..target.length) {
                for (ins in listOf("_", "X")) out.add(target.substring(0, pos) + ins + target.substring(pos))
            }
            return out
        }
        for (base in listOf("a|b^c", "|", "^", "\u0001\u0002", "hello", "a|b|c^d")) {
            for (input in editsOf(base)) {
                assertEquals(escOracle(input), GroupPlayFieldEscape.esc(input), "esc sweep input=$input")
                assertEquals(unescOracle(input), GroupPlayFieldEscape.unesc(input), "unesc sweep input=$input")
            }
        }
        for (code in 0x00..0x7F) {
            val input = String(Character.toChars(code))
            assertEquals(escOracle(input), GroupPlayFieldEscape.esc(input), "single-unit esc code=$code")
            assertEquals(unescOracle(input), GroupPlayFieldEscape.unesc(input), "single-unit unesc code=$code")
        }
    }
}
