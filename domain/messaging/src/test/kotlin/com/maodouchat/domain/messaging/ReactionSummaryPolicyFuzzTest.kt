package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReaction
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactionSummaryPolicyFuzzTest {

    private companion object {
        private const val ITERATIONS = 300
        private val EMOJIS = listOf("❤️", "😂", "👍", "🎉", "🔥", "😮", "😢", "🙏", "👀", "💯", "🤔", "👏")
        private val USERS = (0..9).map { "u$it" } + "me"
    }

    private fun randomReactions(random: Random, count: Int): List<MessageReaction> =
        List(count) {
            MessageReaction(
                userId = USERS[random.nextInt(USERS.size)],
                emoji = EMOJIS[random.nextInt(EMOJIS.size)],
                reactedAt = random.nextLong(0, 1_000_000),
            )
        }

    // 独立重算：按“分组→组内按时间排→组按最早反应排→取前 24 组”的规约另写一遍，只用作期望值。
    private fun expectedSummary(reactions: List<MessageReaction>, currentUserId: String?): ReactionSummary {
        val grouped = reactions.groupBy { it.emoji }
            .map { (emoji, group) ->
                ReactionSummaryGroup(
                    emoji = emoji,
                    reactions = group.sortedBy { it.reactedAt },
                    reactedByMe = currentUserId != null && group.any { it.userId == currentUserId },
                )
            }
            .sortedBy { it.reactions.firstOrNull()?.reactedAt ?: 0L }
        val visible = grouped.take(ReactionSummaryPolicy.MAX_VISIBLE_GROUPS)
        return ReactionSummary(
            visible = visible,
            overflow = (grouped.size - visible.size).coerceAtLeast(0),
        )
    }

    @Test
    fun `random summaries match independent recomputation`() {
        val random = Random(93101)
        repeat(ITERATIONS) {
            val reactions = randomReactions(random, random.nextInt(0, 81))
            val currentUserId = when (random.nextInt(3)) {
                0 -> null
                1 -> "me"
                else -> "u${random.nextInt(10)}"
            }
            val summary = ReactionSummaryPolicy.summarize(reactions, currentUserId)
            assertEquals(expectedSummary(reactions, currentUserId), summary)

            // 与推导无关的结构恒等式：可见组数 + 溢出数恒等于去重 emoji 数。
            val distinct = reactions.map { it.emoji }.distinct().size
            assertEquals(distinct, summary.visible.size + summary.overflow)
            assertEquals(maxOf(0, distinct - ReactionSummaryPolicy.MAX_VISIBLE_GROUPS), summary.overflow)
            assertEquals(minOf(ReactionSummaryPolicy.MAX_VISIBLE_GROUPS, distinct), summary.visible.size)
            // 组内按时间升序、组间按最早反应非降、reactedByMe 只看组内有无该用户。
            var prevEarliest = Long.MIN_VALUE
            summary.visible.forEach { group ->
                val times = group.reactions.map { it.reactedAt }
                assertEquals(times.sorted(), times)
                val earliest = times.firstOrNull() ?: 0L
                assertTrue(earliest >= prevEarliest)
                prevEarliest = earliest
                assertEquals(
                    currentUserId != null && group.reactions.any { it.userId == currentUserId },
                    group.reactedByMe,
                )
            }
        }
    }

    @Test
    fun `boundary group counts pin visible and overflow`() {
        fun reactionsFor(groups: Int) =
            (0 until groups).map { i ->
                MessageReaction(userId = "u$i", emoji = "e$i", reactedAt = i.toLong())
            }
        val empty = ReactionSummaryPolicy.summarize(emptyList(), "me")
        assertTrue(empty.visible.isEmpty())
        assertEquals(0, empty.overflow)
        val exact = ReactionSummaryPolicy.summarize(reactionsFor(24), null)
        assertEquals(24, exact.visible.size)
        assertEquals(0, exact.overflow)
        val oneOver = ReactionSummaryPolicy.summarize(reactionsFor(25), null)
        assertEquals(24, oneOver.visible.size)
        assertEquals(1, oneOver.overflow)
        val fifty = ReactionSummaryPolicy.summarize(reactionsFor(50), null)
        assertEquals(24, fifty.visible.size)
        assertEquals(26, fifty.overflow)
        assertEquals((0 until 24).map { "e$it" }, fifty.visible.map { it.emoji })
    }

    @Test
    fun `groups tied on earliest reaction keep first-seen order`() {
        // 两组最早反应同为 100：groupBy 首见序决定先后，不是字母序也不是输入序的另一面。
        val forward = ReactionSummaryPolicy.summarize(
            listOf(
                MessageReaction(userId = "a", emoji = "e1", reactedAt = 200L),
                MessageReaction(userId = "b", emoji = "e2", reactedAt = 100L),
                MessageReaction(userId = "c", emoji = "e1", reactedAt = 100L),
            ),
            null,
        )
        assertEquals(listOf("e1", "e2"), forward.visible.map { it.emoji })
        val reversed = ReactionSummaryPolicy.summarize(
            listOf(
                MessageReaction(userId = "b", emoji = "e2", reactedAt = 100L),
                MessageReaction(userId = "a", emoji = "e1", reactedAt = 200L),
                MessageReaction(userId = "c", emoji = "e1", reactedAt = 100L),
            ),
            null,
        )
        assertEquals(listOf("e2", "e1"), reversed.visible.map { it.emoji })
    }

    @Test
    fun `identical timestamps stay in one group with no overflow`() {
        val reactions = List(30) { i ->
            MessageReaction(userId = if (i == 7) "me" else "u$i", emoji = "❤️", reactedAt = 500L)
        }
        val summary = ReactionSummaryPolicy.summarize(reactions, "me")
        assertEquals(1, summary.visible.size)
        assertEquals(0, summary.overflow)
        assertEquals(30, summary.visible[0].reactions.size)
        assertTrue(summary.visible[0].reactedByMe)
    }

    @Test
    fun `null current user never marks reactedByMe under random input`() {
        val random = Random(93111)
        repeat(100) {
            val reactions = randomReactions(random, random.nextInt(0, 81))
            val summary = ReactionSummaryPolicy.summarize(reactions, null)
            assertTrue(summary.visible.all { !it.reactedByMe })
        }
    }

    @Test
    fun `reactedByMe follows group membership under random input`() {
        val random = Random(93112)
        repeat(100) {
            val reactions = randomReactions(random, random.nextInt(0, 81))
            val summary = ReactionSummaryPolicy.summarize(reactions, "me")
            summary.visible.forEach { group ->
                assertEquals(group.reactions.any { it.userId == "me" }, group.reactedByMe)
            }
            // 含 "me" 的组若被截断到 24 组之外，不在 visible 里出现，不再断言。
            val meEmojis = reactions.filter { it.userId == "me" }.map { it.emoji }.distinct().toSet()
            assertTrue(meEmojis.containsAll(summary.visible.filter { it.reactedByMe }.map { it.emoji }))
        }
    }
}
