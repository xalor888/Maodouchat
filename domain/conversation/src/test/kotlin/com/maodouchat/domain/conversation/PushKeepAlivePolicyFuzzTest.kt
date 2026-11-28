// Fuzz / random-oracle tests for PushKeepAlivePolicy (checklist line 931,
// 124th block). Test-only, zero production changes. The oracle restates the
// mode latch by hand: only the four known mode strings are enabled, the two
// legacy modes normalize to foreground, anything else is off, and the two
// retired disguises (media session / fake call) are always false. This is a
// different cluster from PushNotificationPolicy (fuzz-106): that one classifies
// notification payloads, this one gates the keep-alive service lifecycle.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushKeepAlivePolicyFuzzTest {

// ---------- oracle ----------

// Hand-written membership. Never `in` on the production lists.
private fun isEnabledOracle(mode: String): Boolean =
    mode == "foreground" || mode == "media" || mode == "call"

private fun effectiveModeOracle(storedMode: String): String =
    if (storedMode == "off") "off"
    else if (isEnabledOracle(storedMode)) "foreground"
    else "off"

private fun shouldStartServiceOracle(mode: String, hasToken: Boolean): Boolean =
    isEnabledOracle(mode) && hasToken

private fun shouldResurrectDaemonOracle(mode: String, hasToken: Boolean): Boolean =
    shouldStartServiceOracle(mode, hasToken)

// ---------- GEN ----------

private val PKA_MODE_POOL = arrayOf(
    "off", "foreground", "media", "call",
    "OFF", "Off", "oFF", "FOREGROUND", "Foreground", "MEDIA", "Media",
    "CALL", "Call",
    " foreground", "foreground ", " media", "media ", " call", "call ",
    "off ", " off", "", " ", "  ",
    "dataSync", "on", "true", "null", "disabled", "background",
    "foreground\n", "media\t", "foregroun", "foregrounD", "calls", "medias"
)

private fun genPkaMode(rnd: Random, poolOnly: Boolean): String {
    if (poolOnly || rnd.nextInt(4) != 0) {
        return PKA_MODE_POOL[rnd.nextInt(PKA_MODE_POOL.size)]
    }
    // Random perturbation of a known mode: flip case, pad, or truncate.
    val base = arrayOf("off", "foreground", "media", "call")[rnd.nextInt(4)]
    val sb = StringBuilder()
    if (rnd.nextBoolean()) sb.append(' ')
    for (c in base) {
        sb.append(if (rnd.nextBoolean()) c.uppercaseChar() else c)
    }
    if (rnd.nextBoolean()) sb.append(' ')
    if (rnd.nextInt(6) == 0 && sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
    return sb.toString()
}

private fun checkKeepAlive(mode: String, hasToken: Boolean) {
    assertEquals(
        isEnabledOracle(mode),
        PushKeepAlivePolicy.isEnabled(mode),
        "isEnabled mode='$mode'"
    )
    assertEquals(
        effectiveModeOracle(mode),
        PushKeepAlivePolicy.effectiveMode(mode),
        "effectiveMode mode='$mode'"
    )
    assertEquals(
        shouldStartServiceOracle(mode, hasToken),
        PushKeepAlivePolicy.shouldStartService(mode, hasToken),
        "shouldStartService mode='$mode' hasToken=$hasToken"
    )
    assertEquals(
        shouldResurrectDaemonOracle(mode, hasToken),
        PushKeepAlivePolicy.shouldResurrectDaemon(mode, hasToken),
        "shouldResurrectDaemon mode='$mode' hasToken=$hasToken"
    )
    assertFalse(
        PushKeepAlivePolicy.wantsMediaSession(mode),
        "wantsMediaSession mode='$mode'"
    )
    assertFalse(
        PushKeepAlivePolicy.wantsFakeCall(mode),
        "wantsFakeCall mode='$mode'"
    )
}

    @Test
    fun pushKeepAliveRandom() {
        val rnd = Random(42)
        repeat(300) {
            checkKeepAlive(
                mode = genPkaMode(rnd, poolOnly = false),
                hasToken = rnd.nextBoolean()
            )
        }
    }

    @Test
    fun pushKeepAliveEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            checkKeepAlive(
                mode = genPkaMode(rnd, poolOnly = true),
                hasToken = rnd.nextBoolean()
            )
        }
    }

    @Test
    fun pushKeepAlivePinned() {
        // The four known modes, with and without a token.
        for (mode in arrayOf("off", "foreground", "media", "call")) {
            for (hasToken in arrayOf(true, false)) {
                val enabled = mode != "off"
                assertEquals(
                    enabled,
                    PushKeepAlivePolicy.isEnabled(mode),
                    "isEnabled $mode"
                )
                assertEquals(
                    if (mode == "off") "off" else "foreground",
                    PushKeepAlivePolicy.effectiveMode(mode),
                    "effectiveMode $mode"
                )
                assertEquals(
                    enabled && hasToken,
                    PushKeepAlivePolicy.shouldStartService(mode, hasToken),
                    "shouldStartService $mode $hasToken"
                )
                assertEquals(
                    enabled && hasToken,
                    PushKeepAlivePolicy.shouldResurrectDaemon(mode, hasToken),
                    "shouldResurrectDaemon $mode $hasToken"
                )
                assertFalse(PushKeepAlivePolicy.wantsMediaSession(mode))
                assertFalse(PushKeepAlivePolicy.wantsFakeCall(mode))
            }
        }
        // Case is significant and padding is not stripped: near-misses are off.
        for (mode in arrayOf(
            "OFF", "Off", "FOREGROUND", "Foreground", "MEDIA", "CALL",
            " foreground", "foreground ", " media", "call ",
            "", " ", "dataSync", "on", "foregroun", "calls"
        )) {
            assertFalse(PushKeepAlivePolicy.isEnabled(mode), "isEnabled '$mode'")
            assertEquals("off", PushKeepAlivePolicy.effectiveMode(mode), "effectiveMode '$mode'")
            assertFalse(
                PushKeepAlivePolicy.shouldStartService(mode, true),
                "shouldStartService '$mode'"
            )
            assertFalse(
                PushKeepAlivePolicy.shouldResurrectDaemon(mode, true),
                "shouldResurrectDaemon '$mode'"
            )
            assertFalse(PushKeepAlivePolicy.wantsMediaSession(mode))
            assertFalse(PushKeepAlivePolicy.wantsFakeCall(mode))
        }
        // Oracle cross-checks on the same pinned inputs.
        assertEquals(
            isEnabledOracle("media"),
            PushKeepAlivePolicy.isEnabled("media")
        )
        assertEquals(
            effectiveModeOracle("call"),
            PushKeepAlivePolicy.effectiveMode("call")
        )
        assertEquals(
            shouldStartServiceOracle("foreground", true),
            PushKeepAlivePolicy.shouldStartService("foreground", true)
        )
        assertEquals(
            shouldResurrectDaemonOracle("off", true),
            PushKeepAlivePolicy.shouldResurrectDaemon("off", true)
        )
        assertTrue(PushKeepAlivePolicy.SUPPORTED_RUNTIME_MODES == listOf("off", "foreground"))
        assertTrue(PushKeepAlivePolicy.ALL_MODES == listOf("off", "foreground", "media", "call"))
    }
}
