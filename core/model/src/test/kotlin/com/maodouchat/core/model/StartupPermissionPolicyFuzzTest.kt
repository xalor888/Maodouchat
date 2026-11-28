package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fuzz / random-oracle tests for [StartupPermissionPolicy] (checklist line
 * 931, 54th block). Test-only, zero production changes.
 *
 * The oracle transcribes the sdkInt < 33 gate and the single
 * POST_NOTIFICATIONS grant check; the test lambda also records which
 * permission string production actually asks about. Random inputs use
 * Random(42), fresh per @Test.
 */
class StartupPermissionPolicyFuzzTest {

// ---------- B3 StartupPermissionPolicy oracle ----------

private fun permissionsToRequestOracle(sdkInt: Int, isGranted: Boolean): List<String> {
    if (sdkInt < 33) return emptyList()
    return if (isGranted) emptyList() else listOf("android.permission.POST_NOTIFICATIONS")
}

// ---------- B3 generators (Random(42) in the CI tests) ----------

private fun randomSdkInt(rnd: Random): Int = when (rnd.nextInt(10)) {
    0 -> Int.MIN_VALUE; 1 -> -1; 2 -> 0; 3 -> 31; 4 -> 32; 5 -> 33; 6 -> 34; 7 -> 100
    8 -> Int.MAX_VALUE
    else -> rnd.nextInt()
}

    @Test
    fun permissionsRandom() {
        val rnd = Random(42)
        repeat(300) {
            val sdk = randomSdkInt(rnd)
            val granted = rnd.nextBoolean()
            var seen: String? = null
            val prod = StartupPermissionPolicy.permissionsToRequest(sdk) {
                seen = it
                granted
            }
            assertEquals(permissionsToRequestOracle(sdk, granted), prod)
            if (sdk < StartupPermissionPolicy.TIRAMISU_SDK) {
                assertNull(seen, "sdk $sdk must not query isGranted")
            } else {
                assertEquals(StartupPermissionPolicy.POST_NOTIFICATIONS, seen)
            }
        }
    }

    @Test
    fun pinnedEdgeCases() {
        assertEquals("android.permission.POST_NOTIFICATIONS", StartupPermissionPolicy.POST_NOTIFICATIONS)
        assertEquals(33, StartupPermissionPolicy.TIRAMISU_SDK)
        assertEquals(emptyList(), StartupPermissionPolicy.permissionsToRequest(32) { true })
        assertEquals(emptyList(), StartupPermissionPolicy.permissionsToRequest(32) { false })
        assertEquals(emptyList(), StartupPermissionPolicy.permissionsToRequest(33) { true })
        assertEquals(
            listOf(StartupPermissionPolicy.POST_NOTIFICATIONS),
            StartupPermissionPolicy.permissionsToRequest(33) { false }
        )
        assertEquals(emptyList(), StartupPermissionPolicy.permissionsToRequest(Int.MIN_VALUE) { false })
        assertEquals(
            listOf(StartupPermissionPolicy.POST_NOTIFICATIONS),
            StartupPermissionPolicy.permissionsToRequest(Int.MAX_VALUE) { false }
        )
        assertEquals(emptyList(), StartupPermissionPolicy.permissionsToRequest(100) { true })
    }
}
