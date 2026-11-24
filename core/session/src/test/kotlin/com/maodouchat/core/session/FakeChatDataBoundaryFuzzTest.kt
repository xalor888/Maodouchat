// Fuzz / random-oracle tests for FakeChatDataBoundary
// (checklist line 931, 130th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: the fake-mode
// data gate, notification sanitising, launcher-icon guard, and the
// mock-id prefix are pure boolean/string logic. No FakeChatDataBoundary
// fuzz test existed before this block.
package com.maodouchat.core.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

private fun fcbAccessOracle(isFakeModeActive: Boolean): Boolean = !isFakeModeActive

private fun fcbSanitizeOracle(
    isFakeModeActive: Boolean,
    realTitle: String,
    realBody: String,
    mockTitle: String,
    mockBody: String
): Pair<String, String> =
    if (isFakeModeActive) mockTitle to mockBody else realTitle to realBody

private fun fcbHideIconOracle(
    hasPin: Boolean,
    isSecretCodeReceiverRegistered: Boolean,
    allowDangerousHiding: Boolean
): Boolean {
    if (!hasPin) return false
    if (!isSecretCodeReceiverRegistered && !allowDangerousHiding) return false
    return true
}

private fun fcbIsMockOracle(chatId: String): Boolean = chatId.startsWith("fake_mock_")

private fun fcbMkIdOracle(identifier: String): String = "fake_mock_$identifier"

// ---------- generators (mirrored by Oracle130 GEN, seed 130001) ----------

private val FCB_ID_POOL = arrayOf(
    "", " ", "fake_mock_", "fake_mock_x", "fake_mock", "fake_Mock_x", "x",
    "fake_mock_fake_mock_y", "a\uD800b", "\uDC00", "fake_mock_\u00A0", "fake_mock"
)

private val FCB_TITLE_POOL = arrayOf(
    "", " ", "Hi", "系统更新", "您有一条新的系统服务提醒", "a b", "x\ty"
)

private fun genFcbId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) FCB_ID_POOL[rnd.nextInt(FCB_ID_POOL.size)]
    else randomText(rnd, 12)

private fun genFcbTitle(rnd: Random): String =
    if (rnd.nextInt(2) == 0) FCB_TITLE_POOL[rnd.nextInt(FCB_TITLE_POOL.size)]
    else randomText(rnd, 16)

class FakeChatDataBoundaryFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Real-data gate is a plain negation.
        assertTrue(FakeChatDataBoundary.mayAccessRealData(false))
        assertFalse(FakeChatDataBoundary.mayAccessRealData(true))
        // Sanitising swaps the whole pair in fake mode, keeps it otherwise.
        assertEquals(
            "系统更新" to "您有一条新的系统服务提醒",
            FakeChatDataBoundary.sanitizeNotification(true, "Alice", "hello")
        )
        assertEquals(
            "Alice" to "hello",
            FakeChatDataBoundary.sanitizeNotification(false, "Alice", "hello")
        )
        assertEquals(
            "M" to "N",
            FakeChatDataBoundary.sanitizeNotification(true, "R", "B", "M", "N")
        )
        assertEquals(
            "R" to "B",
            FakeChatDataBoundary.sanitizeNotification(false, "R", "B", "M", "N")
        )
        // Launcher icon: no PIN never hides; without a receiver only the
        // explicit dangerous flag opens it.
        assertFalse(FakeChatDataBoundary.mayHideLauncherIcon(false, true, true))
        assertFalse(FakeChatDataBoundary.mayHideLauncherIcon(false, false, false))
        assertFalse(FakeChatDataBoundary.mayHideLauncherIcon(true, false, false))
        assertTrue(FakeChatDataBoundary.mayHideLauncherIcon(true, false, true))
        assertTrue(FakeChatDataBoundary.mayHideLauncherIcon(true, true, false))
        assertTrue(FakeChatDataBoundary.mayHideLauncherIcon(true, true, true))
        // Mock-id prefix is an exact, case-sensitive match.
        assertTrue(FakeChatDataBoundary.isMockChatId("fake_mock_"))
        assertTrue(FakeChatDataBoundary.isMockChatId("fake_mock_x"))
        assertFalse(FakeChatDataBoundary.isMockChatId("fake_mock"))
        assertFalse(FakeChatDataBoundary.isMockChatId("fake_Mock_x"))
        assertFalse(FakeChatDataBoundary.isMockChatId(""))
        assertFalse(FakeChatDataBoundary.isMockChatId(" fake_mock_x"))
        assertEquals("fake_mock_x", FakeChatDataBoundary.createMockChatId("x"))
        assertEquals("fake_mock_", FakeChatDataBoundary.createMockChatId(""))
        // Anything created is recognised as mock.
        assertTrue(FakeChatDataBoundary.isMockChatId(FakeChatDataBoundary.createMockChatId("u1")))
        assertTrue(FakeChatDataBoundary.isMockChatId(FakeChatDataBoundary.createMockChatId("")))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle130.genFcb consumption order exactly, so seed
        // 130001 replays the same 1500 GEN lines drive130.py ran.
        val rnd = Random(130001)
        repeat(1500) {
            when (rnd.nextInt(5)) {
                0 -> {
                    val fake = rnd.nextBoolean()
                    assertEquals(fcbAccessOracle(fake), FakeChatDataBoundary.mayAccessRealData(fake))
                }
                1 -> {
                    val fake = rnd.nextBoolean()
                    val rt = genFcbTitle(rnd)
                    val rb = genFcbTitle(rnd)
                    val mt = genFcbTitle(rnd)
                    val mb = genFcbTitle(rnd)
                    assertEquals(
                        fcbSanitizeOracle(fake, rt, rb, mt, mb),
                        FakeChatDataBoundary.sanitizeNotification(fake, rt, rb, mt, mb)
                    )
                }
                2 -> {
                    val pin = rnd.nextBoolean()
                    val reg = rnd.nextBoolean()
                    val allow = rnd.nextBoolean()
                    assertEquals(
                        fcbHideIconOracle(pin, reg, allow),
                        FakeChatDataBoundary.mayHideLauncherIcon(pin, reg, allow)
                    )
                }
                3 -> {
                    val id = genFcbId(rnd)
                    assertEquals(fcbIsMockOracle(id), FakeChatDataBoundary.isMockChatId(id))
                }
                else -> {
                    val id = genFcbId(rnd)
                    assertEquals(fcbMkIdOracle(id), FakeChatDataBoundary.createMockChatId(id))
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Access gate over both booleans; hide-icon over all 8 combos.
        for (fake in listOf(false, true)) {
            assertEquals(fcbAccessOracle(fake), FakeChatDataBoundary.mayAccessRealData(fake))
        }
        for (pin in listOf(false, true)) {
            for (reg in listOf(false, true)) {
                for (allow in listOf(false, true)) {
                    assertEquals(
                        fcbHideIconOracle(pin, reg, allow),
                        FakeChatDataBoundary.mayHideLauncherIcon(pin, reg, allow)
                    )
                }
            }
        }
        // Sanitise over fake x a small title universe (defaults included).
        for (fake in listOf(false, true)) {
            for (rt in FCB_TITLE_POOL) {
                for (mt in FCB_TITLE_POOL) {
                    assertEquals(
                        fcbSanitizeOracle(fake, rt, "body", mt, "mock"),
                        FakeChatDataBoundary.sanitizeNotification(fake, rt, "body", mt, "mock")
                    )
                }
            }
        }
        // Mock-id checks over the pool, every whitespace/control single,
        // and every C0 unit as an id.
        for (id in FCB_ID_POOL) {
            assertEquals(fcbIsMockOracle(id), FakeChatDataBoundary.isMockChatId(id))
            assertEquals(fcbMkIdOracle(id), FakeChatDataBoundary.createMockChatId(id))
            assertTrue(FakeChatDataBoundary.isMockChatId(FakeChatDataBoundary.createMockChatId(id)))
        }
        for (u in WS_CHARS) {
            val s = u.toString()
            assertEquals(fcbIsMockOracle(s), FakeChatDataBoundary.isMockChatId(s))
        }
        for (u in 0x00..0x1F) {
            val s = u.toChar().toString()
            assertEquals(fcbIsMockOracle(s), FakeChatDataBoundary.isMockChatId(s))
        }
        // Prefix-boundary strings: the prefix minus/plus one char.
        for (s in arrayOf("fake_mock", "fake_mock_", "fake_mock__", "fake_mock_a", "fake_moc", "fake_mock_ ")) {
            assertEquals(fcbIsMockOracle(s), FakeChatDataBoundary.isMockChatId(s))
        }
    }
}
