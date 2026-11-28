package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// ---------- oracle ----------

// formatKey restated by hand: blank data has no key at all; otherwise
// the owner id and the data are joined with a colon, no trimming.
internal fun formatKeyOracle170(ownerUserId: String, data: String): String? =
    if (data.isBlank()) null else "$ownerUserId:$data"

// Production formatKey returns the formatted key or null.
internal fun formatKeyProduction170(ownerUserId: String, data: String): String? =
    OwnerScopedImageKeyPolicy.formatKey(ownerUserId, data)

// ---------- generators ----------

internal data class KeyCase170(val ownerUserId: String, val data: String)

internal val KEY_OWNER_POOL170: List<String> = listOf(
    "", "u1", "u2", " ", "a b", "U1", "u1 ", ":", "u:1"
)

internal val KEY_DATA_POOL170: List<String> = listOf(
    "", " ", "  ", "\u00A0", "\u200B", "\t", "\n",
    "img1", "a/b", "x:y", "  x  ", "a".repeat(40), "0", "-"
)

internal fun randomKeyCase170(rnd: Random): KeyCase170 {
    val owner = KEY_OWNER_POOL170[rnd.nextInt(KEY_OWNER_POOL170.size)]
    val n = rnd.nextInt(3)
    val parts = List(n) { KEY_DATA_POOL170[rnd.nextInt(KEY_DATA_POOL170.size)] }
    val data = if (n == 0) KEY_DATA_POOL170[rnd.nextInt(KEY_DATA_POOL170.size)]
        else parts.joinToString("")
    return KeyCase170(owner, data)
}

internal fun keyWireJson170(c: KeyCase170): String =
    // escJson170 lives in ReadReceiptQueryPolicyFuzzTest (same package).
    "{\"owner\":${escJson170(c.ownerUserId)},\"data\":${escJson170(c.data)}}"

class OwnerScopedImageKeyPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Blank data never yields a key, whatever the owner is.
        assertNull(formatKeyProduction170("u1", ""))
        assertNull(formatKeyProduction170("u1", " "))
        assertNull(formatKeyProduction170("u1", "   "))
        assertNull(formatKeyProduction170("u1", "\u00A0"))
        assertNull(formatKeyProduction170("u1", "\t"))
        assertNull(formatKeyProduction170("u1", "\n"))
        assertNull(formatKeyProduction170("", "  "))
        // Zero-width space is not whitespace, so it still forms a key.
        assertEquals(
            "u1:\u200B",
            formatKeyProduction170("u1", "\u200B")
        )
        // The owner id is not checked: blank owner still joins.
        assertEquals(":img1", formatKeyProduction170("", "img1"))
        assertEquals(" :img1", formatKeyProduction170(" ", "img1"))
        // No trimming on either side; colons inside pass through.
        assertEquals("u1:  x  ", formatKeyProduction170("u1", "  x  "))
        assertEquals("u:1:a/b", formatKeyProduction170("u:1", "a/b"))
        assertEquals("u1:img1", formatKeyProduction170("u1", "img1"))
        for ((owner, data) in listOf(
            "u1" to "", "u1" to " ", "u1" to "\u00A0",
            "u1" to "\u200B", "" to "img1", "u1" to "  x  "
        )) {
            assertEquals(
                formatKeyOracle170(owner, data),
                formatKeyProduction170(owner, data),
                "formatKey(owner=$owner, data=$data)"
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(170001)
        repeat(4000) {
            val c = randomKeyCase170(rnd)
            assertEquals(
                formatKeyOracle170(c.ownerUserId, c.data),
                formatKeyProduction170(c.ownerUserId, c.data),
                "formatKey(random)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every owner x every data in the pools.
        for (owner in KEY_OWNER_POOL170) {
            for (data in KEY_DATA_POOL170) {
                assertEquals(
                    formatKeyOracle170(owner, data),
                    formatKeyProduction170(owner, data),
                    "sweep-pool(owner=$owner, data=$data)"
                )
            }
        }
        // Every whitespace unit as the whole data string is blank.
        for (u in listOf(9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 160, 5760, 8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288)) {
            val data = u.toChar().toString()
            assertNull(formatKeyProduction170("u1", data), "sweep-ws-blank(u=$u)")
            assertEquals(
                formatKeyOracle170("u1", data),
                formatKeyProduction170("u1", data),
                "sweep-ws(u=$u)"
            )
        }
        // Zero-width space is not blank, so it still forms a key.
        assertEquals(
            "u1:\u200B",
            formatKeyProduction170("u1", "\u200B")
        )
    }
}
