// Fuzz / random-oracle tests for ExploreLikeTogglePolicy
// (checklist line 931, 154th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: toggling flips
// the liked flag and moves the count by +1 / -1, clamped at 0; the
// addition is plain Int arithmetic, so Int.MAX_VALUE + 1 wraps to
// Int.MIN_VALUE (then clamps to 0) and Int.MIN_VALUE - 1 wraps to
// Int.MAX_VALUE.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun toggleOracle154(likeCount: Int, likedByMe: Boolean): ExploreLikeTogglePolicy.Toggled {
    val newLiked = !likedByMe
    val newCount = (likeCount + if (newLiked) 1 else -1).coerceAtLeast(0)
    return ExploreLikeTogglePolicy.Toggled(newLiked, newCount)
}

// ---------- generators ----------

internal data class LikeInput154(
    val likeCount: Int,
    val likedByMe: Boolean,
)

internal val LIKE_COUNT_POOL154 = listOf(
    -2, -1, 0, 1, 2, 3, 5, 100, Int.MAX_VALUE - 1, Int.MAX_VALUE,
    Int.MIN_VALUE, Int.MIN_VALUE + 1,
)

internal fun randomLikeCount154(rnd: Random): Int = when (rnd.nextInt(10)) {
    0 -> rnd.nextInt(-10, 11)
    1 -> LIKE_COUNT_POOL154[rnd.nextInt(LIKE_COUNT_POOL154.size)]
    2 -> rnd.nextInt(Int.MIN_VALUE, Int.MIN_VALUE + 100)
    3 -> rnd.nextInt(Int.MAX_VALUE - 100, Int.MAX_VALUE)
    else -> rnd.nextInt(0, 1000)
}

internal fun randomLikeInput154(rnd: Random): LikeInput154 =
    LikeInput154(randomLikeCount154(rnd), rnd.nextBoolean())

internal fun likeWire154(input: LikeInput154): String =
    "${input.likeCount},${if (input.likedByMe) 1 else 0}"

class ExploreLikeToggleFuzzTest {

    private fun check(input: LikeInput154) {
        assertEquals(
            toggleOracle154(input.likeCount, input.likedByMe),
            ExploreLikeTogglePolicy.toggle(input.likeCount, input.likedByMe),
        )
    }

    @Test
    fun pinnedBoundaries() {
        val cases = listOf(
            // likeCount, likedByMe -> expected (likedByMe, likeCount)
            Triple(0, false, ExploreLikeTogglePolicy.Toggled(true, 1)),
            Triple(4, false, ExploreLikeTogglePolicy.Toggled(true, 5)),
            Triple(5, true, ExploreLikeTogglePolicy.Toggled(false, 4)),
            Triple(0, true, ExploreLikeTogglePolicy.Toggled(false, 0)),
            Triple(1, true, ExploreLikeTogglePolicy.Toggled(false, 0)),
            Triple(-1, false, ExploreLikeTogglePolicy.Toggled(true, 0)),
            Triple(-1, true, ExploreLikeTogglePolicy.Toggled(false, 0)),
            Triple(-5, true, ExploreLikeTogglePolicy.Toggled(false, 0)),
            Triple(3, false, ExploreLikeTogglePolicy.Toggled(true, 4)),
            // Int overflow edges: plain Int addition wraps.
            Triple(Int.MAX_VALUE, false, ExploreLikeTogglePolicy.Toggled(true, 0)),
            Triple(Int.MAX_VALUE, true, ExploreLikeTogglePolicy.Toggled(false, Int.MAX_VALUE - 1)),
            Triple(Int.MIN_VALUE, false, ExploreLikeTogglePolicy.Toggled(true, 0)),
            Triple(Int.MIN_VALUE, true, ExploreLikeTogglePolicy.Toggled(false, Int.MAX_VALUE)),
            Triple(Int.MAX_VALUE - 1, false, ExploreLikeTogglePolicy.Toggled(true, Int.MAX_VALUE)),
            Triple(Int.MIN_VALUE + 1, true, ExploreLikeTogglePolicy.Toggled(false, 0)),
        )
        for ((count, liked, expected) in cases) {
            assertEquals(expected, toggleOracle154(count, liked))
            assertEquals(toggleOracle154(count, liked), ExploreLikeTogglePolicy.toggle(count, liked))
            check(LikeInput154(count, liked))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(154001)
        repeat(2000) {
            check(randomLikeInput154(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        for (count in listOf(
            Int.MIN_VALUE, Int.MIN_VALUE + 1, -2, -1, 0, 1, 2, 3, 100,
            Int.MAX_VALUE - 1, Int.MAX_VALUE,
        )) {
            for (liked in listOf(false, true)) {
                check(LikeInput154(count, liked))
            }
        }
        // Toggle twice returns to the original state where no clamp or
        // overflow interferes (count > 0, or count == 0 liking fresh).
        for (count in listOf(0, 1, 2, 3, 100)) {
            val first = ExploreLikeTogglePolicy.toggle(count, false)
            val second = ExploreLikeTogglePolicy.toggle(first.likeCount, first.likedByMe)
            assertEquals(false, second.likedByMe)
            assertEquals(count, second.likeCount)
        }
        for (count in listOf(1, 2, 3, 100)) {
            val first = ExploreLikeTogglePolicy.toggle(count, true)
            val second = ExploreLikeTogglePolicy.toggle(first.likeCount, first.likedByMe)
            assertEquals(true, second.likedByMe)
            assertEquals(count, second.likeCount)
        }
    }
}
