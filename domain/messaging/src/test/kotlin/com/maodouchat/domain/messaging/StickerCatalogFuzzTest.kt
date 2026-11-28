// StickerCatalog fuzz (147th block). Oracle reimplements the three
// catalog lookups from production text by hand-looping the same
// BUILT_IN_PACKS data (distinct() order for allStickers, first match
// for packById, id projection for defaultEnabledPackIds); the
// differential drive parses the catalog out of the production source
// text independently. fuzz-68/fuzz-78 only touch the PACK_* constants
// and pin defaultEnabledPackIds once; packById/allStickers have no
// fuzz coverage anywhere.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

private fun packByIdOracle147(id: String): StickerPack? {
    for (pack in StickerCatalog.BUILT_IN_PACKS) {
        if (pack.id == id) return pack
    }
    return null
}

private fun allStickersOracle147(): List<String> {
    val seen = LinkedHashSet<String>()
    for (pack in StickerCatalog.BUILT_IN_PACKS) {
        for (sticker in pack.stickers) {
            seen.add(sticker)
        }
    }
    return seen.toList()
}

private fun defaultEnabledPackIdsOracle147(): List<String> {
    val out = ArrayList<String>()
    for (pack in StickerCatalog.BUILT_IN_PACKS) {
        out.add(pack.id)
    }
    return out
}

// ---------- generators (mirrored by Oracle147 GEN, seed 147002) ----------

private val PACK_ID_POOL147 = arrayOf(
    "recent", "mood", "gesture", "party", "", "MOOD", "Mood",
    "mood ", " mood", "moo", "moods", "recent ", "unknown", "x",
    "gesture ", "part", "party1",
)
private val ID_WORD_POOL147 = arrayOf(
    "mood", "gesture", "party", "recent", "m", "g", "p", "r", "",
)

private fun randomPackId147(rnd: Random): String {
    return when (rnd.nextInt(5)) {
        0 -> PACK_ID_POOL147[rnd.nextInt(PACK_ID_POOL147.size)]
        1 -> ID_WORD_POOL147[rnd.nextInt(ID_WORD_POOL147.size)] +
            ID_WORD_POOL147[rnd.nextInt(ID_WORD_POOL147.size)]
        2 -> {
            val n = rnd.nextInt(0, 8)
            val sb = StringBuilder()
            repeat(n) { sb.append(('a'.code + rnd.nextInt(26)).toChar()) }
            sb.toString()
        }
        3 -> {
            val base = PACK_ID_POOL147[rnd.nextInt(PACK_ID_POOL147.size)]
            val pad = arrayOf(" ", "  ", "\t", "x", "1", "_", "-")
            pad[rnd.nextInt(pad.size)] + base + pad[rnd.nextInt(pad.size)]
        }
        else -> {
            val ch = ('a'.code + rnd.nextInt(26)).toChar()
            ch.toString().repeat(rnd.nextInt(0, 10))
        }
    }
}

class StickerCatalogFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // packById: exact ids resolve, near misses do not.
        assertEquals("mood", packByIdOracle147("mood")?.id)
        assertEquals("gesture", packByIdOracle147("gesture")?.id)
        assertEquals("party", packByIdOracle147("party")?.id)
        assertEquals(null, packByIdOracle147("recent"))
        assertEquals(null, packByIdOracle147("MOOD"))
        assertEquals(null, packByIdOracle147("mood "))
        assertEquals(null, packByIdOracle147(" mood"))
        assertEquals(null, packByIdOracle147(""))
        assertEquals(null, packByIdOracle147("moo"))
        assertEquals(null, packByIdOracle147("moods"))
        assertEquals(packByIdOracle147("mood"), StickerCatalog.packById("mood"))
        assertEquals(packByIdOracle147("recent"), StickerCatalog.packById("recent"))
        assertEquals(packByIdOracle147("MOOD"), StickerCatalog.packById("MOOD"))
        // packById returns the whole pack, tags included.
        val mood = packByIdOracle147("mood")!!
        assertEquals("mood", mood.nameKey)
        assertEquals(28, mood.stickers.size)
        assertEquals(listOf("🥳", "🤩", "😊", "😍", "😜", "😏"), mood.emojiTags["happy"])
        assertEquals(listOf("🥳", "🤩", "😊", "😍", "😜", "😏"), mood.emojiTags["开心"])
        assertEquals(mood, StickerCatalog.packById("mood"))
        val gesture = packByIdOracle147("gesture")!!
        assertEquals(28, gesture.stickers.size)
        assertEquals(gesture, StickerCatalog.packById("gesture"))
        val party = packByIdOracle147("party")!!
        assertEquals(28, party.stickers.size)
        assertEquals(party, StickerCatalog.packById("party"))
        // allStickers: flatMap order, first occurrence wins on duplicates.
        val all = allStickersOracle147()
        assertEquals(3 * 28 - 1, all.size)
        assertEquals("🥳", all.first())
        assertEquals("🏆", all.last())
        assertEquals(1, all.count { it == "🫡" })
        assertEquals(all.indexOf("🥳"), all.indexOf("🤗") - 1)
        assertEquals(all, StickerCatalog.allStickers())
        // defaultEnabledPackIds: pack order, recent is not built in.
        assertEquals(listOf("mood", "gesture", "party"), defaultEnabledPackIdsOracle147())
        assertEquals(defaultEnabledPackIdsOracle147(), StickerCatalog.defaultEnabledPackIds())
        assertEquals(3, StickerCatalog.BUILT_IN_PACKS.size)
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(147002)
        repeat(1500) {
            val id = randomPackId147(rnd)
            assertEquals(packByIdOracle147(id), StickerCatalog.packById(id))
            assertEquals(allStickersOracle147(), StickerCatalog.allStickers())
            assertEquals(defaultEnabledPackIdsOracle147(), StickerCatalog.defaultEnabledPackIds())
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool id, plus case / pad / truncation variants.
        for (id in PACK_ID_POOL147) {
            assertEquals(packByIdOracle147(id), StickerCatalog.packById(id))
        }
        for (base in listOf("mood", "gesture", "party", "recent")) {
            for (variant in listOf(base, base.uppercase(), base.replaceFirstChar { it.uppercaseChar() }, " $base", "$base ", base.drop(1), base.dropLast(1), base + "s", base + "1")) {
                assertEquals(packByIdOracle147(variant), StickerCatalog.packById(variant))
            }
        }
        // Single-unit ids.
        val units = (0..0x7F).toList() +
            listOf(0xA0, 0x1680, 0x2000, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000) +
            listOf(0x80, 0xFF, 0x100, 0x7FF, 0x800, 0xFFF, 0xD800, 0xDFFF, 0xE000, 0xFFFE, 0xFFFF)
        for (u in units) {
            val ch = String(charArrayOf(u.toChar()))
            assertEquals(packByIdOracle147(ch), StickerCatalog.packById(ch))
            assertEquals(packByIdOracle147("mood$ch"), StickerCatalog.packById("mood$ch"))
            assertEquals(packByIdOracle147("$ch" + "mood"), StickerCatalog.packById("$ch" + "mood"))
        }
        // Length sweep on a near-miss prefix.
        for (n in 0..10) {
            val id = "m".repeat(n)
            assertEquals(packByIdOracle147(id), StickerCatalog.packById(id))
        }
        assertEquals(allStickersOracle147(), StickerCatalog.allStickers())
        assertEquals(defaultEnabledPackIdsOracle147(), StickerCatalog.defaultEnabledPackIds())
    }
}
