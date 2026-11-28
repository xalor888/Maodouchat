// PublicStatusBannerPolicy fuzz (146th block). Oracle reimplements
// buildPublicStatusBanner from production text: safeJsonString reads
// optString (missing key -> "", JSON null -> "", literal "null" ->
// ""), optBoolean is case-insensitive "true" only for strings;
// banner/e2ee/announcement fall back to their legacy keys when blank;
// maintenance wins over parts; minApp "0" shows no hint.
package com.maodouchat.domain.conversation

import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Java Character.isWhitespace set (28 units); Kotlin trim()/isBlank()
// use exactly this, not the <= 0x20 rule.
private val WS146 = setOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', '\u0020', '\u00A0', '\u1680', '\u2000', '\u2001',
    '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008',
    '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
)

private fun isBlank146(s: String): Boolean = s.all { it in WS146 }

private fun bannerOracle146(fields: List<Pair<String, Any?>>): String? {
    val m = linkedMapOf<String, Any?>()
    for ((k, v) in fields) m[k] = v
    fun safe(key: String): String {
        if (!m.containsKey(key)) return ""
        return when (val v = m[key]) {
            null -> ""
            is String -> if (v == "null") "" else v
            is Boolean -> v.toString()
            is Long -> v.toString()
            else -> error("bad value")
        }
    }
    fun optBool(key: String): Boolean {
        if (!m.containsKey(key)) return false
        return when (val v = m[key]) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> false
        }
    }
    val bannerRaw = safe("banner")
    val banner = if (isBlank146(bannerRaw)) safe("globalBanner") else bannerRaw
    val e2eeRaw = safe("forceE2eeBanner")
    val e2eeBanner = if (isBlank146(e2eeRaw)) safe("e2eeBanner") else e2eeRaw
    val annRaw = safe("publicAnnouncement")
    val announcement = if (isBlank146(annRaw)) safe("announcement") else annRaw
    val maintMsg = safe("maintenanceMessage")
    val publicMaintenance =
        if (m.containsKey("maintenance")) optBool("maintenance") else optBool("maintenanceMode")
    val minApp = safe("minAppVersion")
    val pqxdh = optBool("pqxdhPreview")
    val upgradeHint = if (!isBlank146(minApp) && minApp != "0") "Min version: $minApp" else null
    val pqxdhHint = if (pqxdh) "PQXDH preview on" else null
    val parts = listOfNotNull(
        banner.takeIf { !isBlank146(it) },
        e2eeBanner.takeIf { !isBlank146(it) },
        announcement.takeIf { !isBlank146(it) },
        upgradeHint,
        pqxdhHint,
    )
    return when {
        publicMaintenance && !isBlank146(maintMsg) -> maintMsg
        parts.isNotEmpty() -> parts.joinToString(" · ")
        else -> null
    }
}

private fun bannerProduction146(fields: List<Pair<String, Any?>>): String? {
    val o = JSONObject()
    for ((k, v) in fields) {
        when (v) {
            null -> o.put(k, JSONObject.NULL)
            is String -> o.put(k, v)
            is Long -> o.put(k, v)
            is Boolean -> o.put(k, v)
            else -> error("bad value")
        }
    }
    return buildPublicStatusBanner(o)
}

// ---------- generators (mirrored by Oracle146 GEN, seed 146001) ----------

private val KEY_POOL146 = arrayOf(
    "banner", "globalBanner", "forceE2eeBanner", "e2eeBanner",
    "publicAnnouncement", "announcement", "maintenanceMessage",
    "maintenance", "maintenanceMode", "minAppVersion", "pqxdhPreview",
    "other", "Banner", "BANNER", "", "banner ", " maintenance", "minAppVersion2", "x",
)
private val STR_POOL146 = arrayOf(
    "", " ", "hi", "null", "NULL", "Null", "  x  ", "0", "2.5.0",
    "true", "TRUE", "True", "false", "FALSE", "tRuE", "  ", "\t\n",
    "a".repeat(50), "é", "·", "Min version: 1.0", "yes", "no", "1",
    "maintenance", "down for maintenance", "global", "e2ee", " 2.5.0 ",
    "x|y", "a · b", "falſe", "TRUİ",
)
private val LONG_POOL146 = arrayOf<Long?>(
    0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 20L, 1_700_000_000_000L,
)

private fun randomPsbString146(rnd: Random): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, 12)
    repeat(len) {
        when (rnd.nextInt(5)) {
            0 -> sb.append('a' + rnd.nextInt(26))
            1 -> sb.append('0' + rnd.nextInt(10))
            2 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            3 -> sb.append(STR_POOL146[rnd.nextInt(STR_POOL146.size)])
            else -> sb.append(WS146.elementAt(rnd.nextInt(WS146.size)))
        }
    }
    return sb.toString()
}

private fun randomValue146(rnd: Random): Any? = when (rnd.nextInt(10)) {
    in 0..4 -> randomPsbString146(rnd)
    5, 6 -> if (rnd.nextBoolean()) LONG_POOL146[rnd.nextInt(LONG_POOL146.size)] else rnd.nextLong()
    7, 8 -> rnd.nextBoolean()
    else -> null
}

private fun randomFields146(rnd: Random): List<Pair<String, Any?>> {
    val n = rnd.nextInt(0, 7)
    return List(n) { KEY_POOL146[rnd.nextInt(KEY_POOL146.size)] to randomValue146(rnd) }
}

class PublicStatusBannerPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val cases: List<Pair<List<Pair<String, Any?>>, String?>> = listOf(
            emptyList<Pair<String, Any?>>() to null,
            listOf("banner" to "hi") to "hi",
            listOf("banner" to "  ") to null,
            listOf("banner" to "  ", "globalBanner" to "global") to "global",
            listOf("globalBanner" to "global", "e2eeBanner" to "e2ee") to "global · e2ee",
            listOf("banner" to "hi", "maintenance" to true, "maintenanceMessage" to "down") to "down",
            listOf("maintenanceMode" to true, "maintenanceMessage" to "legacy") to "legacy",
            listOf("banner" to "hi", "maintenance" to true) to "hi",
            listOf("banner" to null) to null,
            listOf("banner" to "null") to null,
            listOf("banner" to "null", "globalBanner" to "g") to "g",
            listOf("minAppVersion" to "0") to null,
            listOf("minAppVersion" to "2.5.0") to "Min version: 2.5.0",
            listOf("minAppVersion" to 0L) to null,
            listOf("minAppVersion" to 25L) to "Min version: 25",
            listOf("banner" to "hi", "pqxdhPreview" to true) to "hi · PQXDH preview on",
            listOf("banner" to "b", "forceE2eeBanner" to "e", "publicAnnouncement" to "a") to "b · e · a",
            listOf("maintenance" to "TRUE", "maintenanceMessage" to "m") to "m",
            listOf("maintenance" to "TrUe", "maintenanceMessage" to "m") to "m",
            listOf("maintenance" to "1", "maintenanceMessage" to "m") to null,
            listOf("maintenance" to 1L, "maintenanceMessage" to "m") to null,
            listOf("maintenance" to true, "maintenanceMessage" to "  ") to null,
            listOf("banner" to "first", "banner" to "second") to "second",
            listOf("zzz" to 1L, "banner" to "hi") to "hi",
            listOf("banner" to "a · b") to "a · b",
            listOf("banner" to 5L, "e2eeBanner" to true) to "5 · true",
            listOf("banner" to "", "globalBanner" to "") to null,
            listOf("maintenanceMode" to "false", "maintenance" to true, "maintenanceMessage" to "m") to "m",
            listOf("maintenanceMode" to true, "maintenance" to false, "maintenanceMessage" to "m") to null,
            listOf("pqxdhPreview" to "true") to "PQXDH preview on",
            listOf("pqxdhPreview" to "yes") to null,
            listOf("minAppVersion" to " 2.5.0 ") to "Min version:  2.5.0 ",
            listOf("minAppVersion" to "  ") to null,
            listOf("banner" to " x ", "globalBanner" to "g") to " x ",
            listOf("banner" to "falſe") to "falſe",
            listOf("maintenance" to "falſe", "maintenanceMessage" to "m") to null,
            listOf("banner" to "TRUİ") to "TRUİ",
        )
        for ((fields, want) in cases) {
            assertEquals(want, bannerOracle146(fields))
            assertEquals(bannerOracle146(fields), bannerProduction146(fields))
        }
        assertNull(bannerProduction146(emptyList()))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(146001)
        repeat(1500) {
            val fields = randomFields146(rnd)
            assertEquals(bannerOracle146(fields), bannerProduction146(fields))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Single-unit string values for every key across ASCII + whitespace + boundary units.
        val units = (0..0x7F).toList() + WS146.map { it.code } +
            listOf(0x80, 0xFF, 0x100, 0x7FF, 0x800, 0xFFF, 0xD800, 0xDFFF, 0xE000, 0xFFFE, 0xFFFF)
        for (key in KEY_POOL146) {
            for (u in units) {
                val fields = listOf(key to String(charArrayOf(u.toChar())) as Any?)
                assertEquals(bannerOracle146(fields), bannerProduction146(fields))
            }
            for (v in listOf<Long?>(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE)) {
                val fields = listOf(key to v as Any?)
                assertEquals(bannerOracle146(fields), bannerProduction146(fields))
            }
            for (v in listOf(true, false)) {
                val fields = listOf(key to v as Any?)
                assertEquals(bannerOracle146(fields), bannerProduction146(fields))
            }
            val fields = listOf(key to null as Any?)
            assertEquals(bannerOracle146(fields), bannerProduction146(fields))
        }
        // Word sweeps around the load-bearing literals.
        for (w in listOf("null", "NULL", "true", "TRUE", "false", "FALSE", "0", "2.5.0", "yes", "no")) {
            for (pad in listOf("", " ", "  ", "\t", "\u00A0")) {
                val fields = listOf("banner" to (pad + w + pad) as Any?)
                assertEquals(bannerOracle146(fields), bannerProduction146(fields))
                val mfields = listOf("maintenance" to (pad + w + pad) as Any?, "maintenanceMessage" to "m" as Any?)
                assertEquals(bannerOracle146(mfields), bannerProduction146(mfields))
            }
        }
        // Duplicate-key sweep: last wins for every key.
        for (key in KEY_POOL146) {
            val fields = listOf(key to "first" as Any?, key to "second" as Any?)
            assertEquals(bannerOracle146(fields), bannerProduction146(fields))
        }
    }
}
