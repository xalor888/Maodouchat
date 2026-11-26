package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// isCurrentOwner restated by hand: the ids must match and the
// expected id must be non-blank, so two empty ids never count as
// the current owner (logged-out state must not match).
internal fun isCurrentOwnerOracle169(currentUserId: String?, expectedUserId: String): Boolean =
    currentUserId == expectedUserId && expectedUserId.isNotBlank()

// Production isCurrentOwner returns the same boolean directly.
internal fun isCurrentOwnerProduction169(currentUserId: String?, expectedUserId: String): Boolean =
    ExploreOwnerPolicy.isCurrentOwner(currentUserId, expectedUserId)

// ---------- generators ----------

internal data class OwnerCase169(
    val currentUserId: String?,
    val expectedUserId: String
)

internal val OWNER_ID_POOL169: List<String> = listOf(
    "", "u1", "u2", " ", "  ", "\u00A0", "\u200B", "a b", "U1", "u1 "
)

internal val OWNER_CURRENT_POOL169: List<String?> = listOf(
    null, "", "u1", "u2", " ", "  ", "\u00A0", "\u200B", "a b", "U1", "u1 "
)

internal fun randomOwnerCase169(rnd: Random): OwnerCase169 = OwnerCase169(
    currentUserId = OWNER_CURRENT_POOL169[rnd.nextInt(OWNER_CURRENT_POOL169.size)],
    expectedUserId = OWNER_ID_POOL169[rnd.nextInt(OWNER_ID_POOL169.size)]
)

internal fun ownerWireJson169(c: OwnerCase169): String {
    val current = if (c.currentUserId == null) "null" else "\"${c.currentUserId}\""
    return "{\"current\":$current,\"expected\":\"${c.expectedUserId}\"}"
}

class ExploreOwnerPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Matching non-blank ids count; anything else does not.
        val cases = listOf(
            Triple("u1", "u1", true),
            Triple("u1", "u2", false),
            Triple(null, "u1", false),
            Triple(null, "", false),
            Triple("", "", false),
            Triple(" ", " ", false),
            Triple("\u00A0", "\u00A0", false),
            Triple("\u200B", "\u200B", true),
            Triple("u1", "", false),
            Triple("", "u1", false),
            Triple("U1", "u1", false),
            Triple("u1 ", "u1", false),
            Triple("a b", "a b", true)
        )
        for ((current, expected, want) in cases) {
            assertEquals(
                want,
                isCurrentOwnerProduction169(current, expected),
                "isCurrentOwner(current=$current,expected=$expected)"
            )
            assertEquals(
                want,
                isCurrentOwnerOracle169(current, expected),
                "oracle(current=$current,expected=$expected)"
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(169002)
        repeat(4000) {
            val c = randomOwnerCase169(rnd)
            assertEquals(
                isCurrentOwnerOracle169(c.currentUserId, c.expectedUserId),
                isCurrentOwnerProduction169(c.currentUserId, c.expectedUserId),
                "isCurrentOwner(random)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool id against itself and against every other pool
        // id, with a null current id as well.
        for (expected in OWNER_ID_POOL169) {
            for (current in OWNER_CURRENT_POOL169) {
                assertEquals(
                    isCurrentOwnerOracle169(current, expected),
                    isCurrentOwnerProduction169(current, expected),
                    "sweep(current=$current,expected=$expected)"
                )
            }
        }
        // Every whitespace unit as the whole expected id: blank
        // expected ids never match, even with an equal current id.
        for (u in listOf(9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 160, 5760, 8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288)) {
            val id = u.toChar().toString()
            assertEquals(
                false,
                isCurrentOwnerProduction169(id, id),
                "sweep-expected-ws(u=$u)"
            )
            assertEquals(
                isCurrentOwnerOracle169(id, id),
                isCurrentOwnerProduction169(id, id),
                "sweep-expected-ws-oracle(u=$u)"
            )
        }
    }
}
