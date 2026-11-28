// Fuzz / random-oracle tests for LocalAiProviderSelectionPolicy
// (checklist line 931, 132nd block). Test-only, zero production changes.
// The oracle is hand-written from the production source: resolve picks
// the provider whose id matches the active id, else the first; upsert
// replaces by id and appends; delete drops by id; after-delete keeps
// the active id unless it was the deleted one, then takes the first
// remaining id (or "" when none remain). No selection-policy fuzz test
// existed before this block (content-grep dedup 0 hits on all 138
// remote branches, main's 127 FuzzTest files, and all 135 open PR
// titles/bodies; LocalAiProviderFuzzTest in fuzz-102 covers the
// LocalAiProvider model members only, not this policy object).
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- shared random-text helpers ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

// ---------- oracle ----------

private fun lapsResolveOracle(providers: List<LocalAiProvider>, activeId: String?): LocalAiProvider? {
    if (providers.isEmpty()) return null
    return providers.firstOrNull { it.id == activeId } ?: providers.first()
}

private fun lapsUpsertOracle(providers: List<LocalAiProvider>, provider: LocalAiProvider): List<LocalAiProvider> =
    providers.filterNot { it.id == provider.id } + provider

private fun lapsDeleteOracle(providers: List<LocalAiProvider>, id: String): List<LocalAiProvider> =
    providers.filterNot { it.id == id }

private fun lapsAfterDeleteOracle(next: List<LocalAiProvider>, deletedId: String, activeId: String?): String? {
    if (activeId != deletedId) return activeId
    return next.firstOrNull()?.id.orEmpty()
}

// ---------- generators (mirrored by Oracle132 GEN, seed 132002) ----------

private val LAPS_ID_POOL = arrayOf(
    "", " ", "p1", "p2", "a", "b", "a\uD800b", "\uDC00", "\uE000",
    "\uD83D\uDE00", "p10", "p2", "  ", "\u00A0", "P1", "p1 ", " p1",
    "p1\u2003", "p1\u200B", "p1\u0000", "\uD800", "p-1", "p_1"
)

private val LAPS_NAME_POOL = arrayOf("", " ", "Main", "main", "Backup", "  x  ", "\u00A0")

private val LAPS_URL_POOL = arrayOf(
    "", "https://api.example.com", "https://api.example.com/",
    "http://localhost:8080", "  https://x.test  ", "not-a-url"
)

private val LAPS_MODEL_POOL = arrayOf(
    "", "gpt-4o", "gpt-4o-mini", "claude-3-5-sonnet", "qwen-vl-max",
    "llama3", "GEMINI-1.5-PRO", "  gpt-4o  ", "vision-model"
)

private fun genLapsId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) LAPS_ID_POOL[rnd.nextInt(LAPS_ID_POOL.size)]
    else randomText(rnd, 6)

private fun genLapsName(rnd: Random): String =
    if (rnd.nextInt(2) == 0) LAPS_NAME_POOL[rnd.nextInt(LAPS_NAME_POOL.size)]
    else randomText(rnd, 6)

private fun genLapsUrl(rnd: Random): String =
    if (rnd.nextInt(2) == 0) LAPS_URL_POOL[rnd.nextInt(LAPS_URL_POOL.size)]
    else randomText(rnd, 12)

private fun genLapsModel(rnd: Random): String =
    if (rnd.nextInt(2) == 0) LAPS_MODEL_POOL[rnd.nextInt(LAPS_MODEL_POOL.size)]
    else randomText(rnd, 8)

private fun genLapsProvider(rnd: Random): LocalAiProvider = LocalAiProvider(
    id = genLapsId(rnd),
    name = genLapsName(rnd),
    baseUrl = genLapsUrl(rnd),
    apiKey = randomText(rnd, 6),
    model = genLapsModel(rnd)
)

private fun genLapsList(rnd: Random): List<LocalAiProvider> {
    val n = rnd.nextInt(0, 6)
    return List(n) { genLapsProvider(rnd) }
}

private fun genLapsIdNullable(rnd: Random): String? =
    if (rnd.nextInt(6) == 0) null else genLapsId(rnd)

class LocalAiProviderSelectionPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val p1 = LocalAiProvider("p1", "Main", "https://a.test", "k1", "gpt-4o")
        val p2 = LocalAiProvider("p2", "Backup", "https://b.test", "k2", "claude-3-5-sonnet")
        val p1b = LocalAiProvider("p1", "Renamed", "https://c.test", "k3", "llama3")
        // Resolve: exact id wins, otherwise the first provider; empty list gives null.
        assertEquals(p2, LocalAiProviderSelectionPolicy.resolveActive(listOf(p1, p2), "p2"))
        assertEquals(p1, LocalAiProviderSelectionPolicy.resolveActive(listOf(p1, p2), "zz"))
        assertEquals(p1, LocalAiProviderSelectionPolicy.resolveActive(listOf(p1, p2), null))
        assertEquals(null, LocalAiProviderSelectionPolicy.resolveActive(emptyList(), "p1"))
        assertEquals(null, LocalAiProviderSelectionPolicy.resolveActive(emptyList(), null))
        // Ids compare exactly: case and surrounding space matter.
        assertEquals(p1, LocalAiProviderSelectionPolicy.resolveActive(listOf(p1, p2), "P1"))
        assertEquals(p1, LocalAiProviderSelectionPolicy.resolveActive(listOf(p1, p2), " p1"))
        // Upsert drops every same-id entry, then appends the new one at the end.
        assertEquals(listOf(p2, p1b), LocalAiProviderSelectionPolicy.upsert(listOf(p1, p2), p1b))
        assertEquals(listOf(p1, p2, LocalAiProvider("p3", "N", "https://d.test", "k4", "m")), LocalAiProviderSelectionPolicy.upsert(listOf(p1, p2), LocalAiProvider("p3", "N", "https://d.test", "k4", "m")))
        assertEquals(listOf(p1), LocalAiProviderSelectionPolicy.upsert(emptyList(), p1))
        // Delete drops every provider with that id; unknown ids change nothing.
        assertEquals(listOf(p2), LocalAiProviderSelectionPolicy.delete(listOf(p1, p2), "p1"))
        assertEquals(listOf(p1, p2), LocalAiProviderSelectionPolicy.delete(listOf(p1, p2), "zz"))
        assertEquals(emptyList(), LocalAiProviderSelectionPolicy.delete(listOf(p1), "p1"))
        assertEquals(listOf(p1, p1b), LocalAiProviderSelectionPolicy.delete(listOf(p1, p1b), "p2"))
        // After delete: untouched active id stays; deleted active id falls to the first remaining, or "".
        // A null active id is untouched (null != deleted id), so it stays null.
        assertEquals("p2", LocalAiProviderSelectionPolicy.activeIdAfterDelete(listOf(p2), "p1", "p2"))
        assertEquals(null, LocalAiProviderSelectionPolicy.activeIdAfterDelete(listOf(p2), "p1", null))
        assertEquals("", LocalAiProviderSelectionPolicy.activeIdAfterDelete(emptyList(), "p1", "p1"))
        assertEquals("p2", LocalAiProviderSelectionPolicy.activeIdAfterDelete(listOf(p2), "p1", "p1"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle132.genLaps consumption order exactly, so seed
        // 132002 replays the same 1500 GEN lines drive132.py ran.
        val rnd = Random(132002)
        repeat(1500) {
            when (rnd.nextInt(4)) {
                0 -> {
                    val providers = genLapsList(rnd)
                    val activeId = genLapsIdNullable(rnd)
                    assertEquals(lapsResolveOracle(providers, activeId), LocalAiProviderSelectionPolicy.resolveActive(providers, activeId))
                }
                1 -> {
                    val providers = genLapsList(rnd)
                    val provider = genLapsProvider(rnd)
                    assertEquals(lapsUpsertOracle(providers, provider), LocalAiProviderSelectionPolicy.upsert(providers, provider))
                }
                2 -> {
                    val providers = genLapsList(rnd)
                    val id = genLapsId(rnd)
                    assertEquals(lapsDeleteOracle(providers, id), LocalAiProviderSelectionPolicy.delete(providers, id))
                }
                else -> {
                    val next = genLapsList(rnd)
                    val deletedId = genLapsId(rnd)
                    val activeId = genLapsIdNullable(rnd)
                    assertEquals(
                        lapsAfterDeleteOracle(next, deletedId, activeId),
                        LocalAiProviderSelectionPolicy.activeIdAfterDelete(next, deletedId, activeId)
                    )
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool id as the active id over small fixed lists.
        val fixed = listOf(
            LocalAiProvider("p1", "Main", "https://a.test", "k1", "gpt-4o"),
            LocalAiProvider("p2", "Backup", "https://b.test", "k2", "claude-3-5-sonnet"),
            LocalAiProvider("p1", "Dup", "https://c.test", "k3", "llama3")
        )
        for (id in LAPS_ID_POOL.toList() + listOf<String?>(null)) {
            assertEquals(lapsResolveOracle(fixed, id), LocalAiProviderSelectionPolicy.resolveActive(fixed, id))
            assertEquals(lapsResolveOracle(emptyList(), id), LocalAiProviderSelectionPolicy.resolveActive(emptyList(), id))
            assertEquals(lapsAfterDeleteOracle(fixed, "p1", id), LocalAiProviderSelectionPolicy.activeIdAfterDelete(fixed, "p1", id))
            assertEquals(lapsAfterDeleteOracle(emptyList(), "p1", id), LocalAiProviderSelectionPolicy.activeIdAfterDelete(emptyList(), "p1", id))
        }
        // Every pool id as the deleted / upserted id.
        for (id in LAPS_ID_POOL) {
            assertEquals(lapsDeleteOracle(fixed, id), LocalAiProviderSelectionPolicy.delete(fixed, id))
            val prov = LocalAiProvider(id, "N", "https://d.test", "k", "m")
            assertEquals(lapsUpsertOracle(fixed, prov), LocalAiProviderSelectionPolicy.upsert(fixed, prov))
            assertEquals(lapsUpsertOracle(emptyList(), prov), LocalAiProviderSelectionPolicy.upsert(emptyList(), prov))
        }
        // Single whitespace/control units as ids.
        for (u in WS_CHARS) {
            val s = u.toString()
            assertEquals(lapsResolveOracle(fixed, s), LocalAiProviderSelectionPolicy.resolveActive(fixed, s))
            assertEquals(lapsDeleteOracle(fixed, s), LocalAiProviderSelectionPolicy.delete(fixed, s))
        }
        for (code in 0x00 until 0x20) {
            val s = code.toChar().toString()
            assertEquals(lapsResolveOracle(fixed, s), LocalAiProviderSelectionPolicy.resolveActive(fixed, s))
            assertEquals(lapsDeleteOracle(fixed, s), LocalAiProviderSelectionPolicy.delete(fixed, s))
        }
    }
}
