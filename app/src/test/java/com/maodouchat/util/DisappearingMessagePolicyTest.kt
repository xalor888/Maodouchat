package com.maodouchat.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G194b：阅后即焚策略的覆盖。纯 Kotlin，不需要 Robolectric。
 */
class DisappearingMessagePolicyTest {

    // ---- effectiveSeconds ----

    @Test
    fun groupChatIsAlwaysOff() {
        // 群聊一律关闭，哪怕请求了一个合法时长
        assertEquals(
            DisappearingMessagePolicy.OFF_SECONDS,
            DisappearingMessagePolicy.effectiveSeconds(isGroup = true, requestedSeconds = 60 * 60),
            "群聊必须强制关闭阅后即焚",
        )
        // 即使「密聊」标记和群聊同时出现，也以群聊为准（先判断）
        assertEquals(
            DisappearingMessagePolicy.OFF_SECONDS,
            DisappearingMessagePolicy.effectiveSeconds(
                isGroup = true, requestedSeconds = 30, isSecret = true,
            ),
            "群聊 + 密聊标记同时出现时仍应关闭",
        )
    }

    @Test
    fun secretChatIsPinnedToTheFixedDefault() {
        // 密聊固定 30 秒，请求别的值也不该生效
        assertEquals(
            DisappearingMessagePolicy.SECRET_DEFAULT_SECONDS,
            DisappearingMessagePolicy.effectiveSeconds(
                isGroup = false, requestedSeconds = 7 * 24 * 60 * 60, isSecret = true,
            ),
            "密聊必须固定为 30 秒——请求更长不得生效",
        )
        assertEquals(30, DisappearingMessagePolicy.SECRET_DEFAULT_SECONDS)
    }

    @Test
    fun oneToOneUsesTheRequestedValueOrFallsBackToOff() {
        assertEquals(60, DisappearingMessagePolicy.effectiveSeconds(false, 60))
        // 不在白名单里的值一律关掉，而不是就近取整
        assertEquals(
            DisappearingMessagePolicy.OFF_SECONDS,
            DisappearingMessagePolicy.effectiveSeconds(false, 61),
            "非白名单时长必须关闭，不能就近取整",
        )
        // null = 没设置过 = 关
        assertEquals(DisappearingMessagePolicy.OFF_SECONDS, DisappearingMessagePolicy.effectiveSeconds(false, null))
    }

    // ---- resolveExpiresAt：不重写不变式 ----

    @Test
    fun existingExpiryIsNeverRewritten() {
        val firstRead = 1_000_000L
        val expiresAt = DisappearingMessagePolicy.resolveExpiresAt(
            existingExpiresAt = null, timerSeconds = 30, readAtMs = firstRead,
        )
        assertEquals(1_030_000L, expiresAt)

        // 对方再次已读（更晚的时间点）——必须保持原样
        val secondRead = 9_000_000L
        val after = DisappearingMessagePolicy.resolveExpiresAt(
            existingExpiresAt = expiresAt, timerSeconds = 30, readAtMs = secondRead,
        )
        assertEquals(
            expiresAt, after,
            "已有 expiresAt 的消息不得被再次 mark-read 改写——否则对方反复读就能让它永不过期",
        )
    }

    @Test
    fun resolveExpiresAtHandlesDegenerateInputs() {
        // 定时器关闭 → 不起算
        assertEquals(
            null,
            DisappearingMessagePolicy.resolveExpiresAt(null, timerSeconds = 0, readAtMs = 1_000L),
            "timerSeconds<=0 时不应新起算",
        )
        // readAt 非法 → 保留既有值（有则保留，无则 null）
        assertEquals(
            2_000L,
            DisappearingMessagePolicy.resolveExpiresAt(2_000L, timerSeconds = 30, readAtMs = 0L),
            "readAtMs<=0 时应保留既有 expiresAt",
        )
        assertEquals(
            null,
            DisappearingMessagePolicy.resolveExpiresAt(null, timerSeconds = 30, readAtMs = 0L),
        )
        // 既有值是 0（哨兵）→ 视为没有，重新起算
        assertEquals(
            1_030_000L,
            DisappearingMessagePolicy.resolveExpiresAt(0L, timerSeconds = 30, readAtMs = 1_000_000L),
            "既有 expiresAt=0 是哨兵值，应重新起算",
        )
    }

    // ---- isExpired / remainingMs ----

    @Test
    fun expiryBoundaryIsInclusive() {
        assertFalse(DisappearingMessagePolicy.isExpired(null, 1_000L), "没有 deadline 就不算过期")
        assertFalse(DisappearingMessagePolicy.isExpired(0L, 1_000L), "deadline=0 是哨兵，不算过期")
        assertFalse(DisappearingMessagePolicy.isExpired(1_000L, 999L), "还没到点不算过期")
        assertTrue(DisappearingMessagePolicy.isExpired(1_000L, 1_000L), "正好到点就算过期（>=）")
        assertTrue(DisappearingMessagePolicy.isExpired(1_000L, 1_001L), "过了点当然过期")
    }

    @Test
    fun remainingMsClampsAtZeroAndReportsMinusOneForNoDeadline() {
        assertEquals(-1L, DisappearingMessagePolicy.remainingMs(null, 1_000L))
        assertEquals(-1L, DisappearingMessagePolicy.remainingMs(0L, 1_000L))
        assertEquals(500L, DisappearingMessagePolicy.remainingMs(1_500L, 1_000L))
        assertEquals(0L, DisappearingMessagePolicy.remainingMs(1_000L, 2_000L), "已过期应钳到 0 而不是负数")
    }

    // ---- 白名单本身 ----

    @Test
    fun allowedSecondsAreSortedAndContainOff() {
        val list = DisappearingMessagePolicy.ALLOWED_SECONDS
        assertEquals(list.sorted(), list, "白名单必须升序（UI 直接按顺序渲染）")
        assertTrue(DisappearingMessagePolicy.OFF_SECONDS in list, "白名单必须包含「关」")
        assertTrue(list.all { it >= 0 }, "白名单不得有负值")
    }

    @Test
    fun normalizeSecondsAcceptsOnlyWhitelistedValues() {
        listOf(0, 30, 60, 2 * 60, 5 * 60, 15 * 60, 60 * 60, 24 * 60 * 60, 30 * 24 * 60 * 60)
            .forEach { assertEquals(it, DisappearingMessagePolicy.normalizeSeconds(it)) }
        listOf(-1, 1, 29, 31, 61, 999_999)
            .forEach {
                assertEquals(
                    DisappearingMessagePolicy.OFF_SECONDS,
                    DisappearingMessagePolicy.normalizeSeconds(it),
                    "不在白名单的 $it 必须归零",
                )
            }
        assertEquals(DisappearingMessagePolicy.OFF_SECONDS, DisappearingMessagePolicy.normalizeSeconds(null))
    }

    // ---- 两个「只看密聊」的判定 ----
    // ⚠️ 注意：这两个函数的第二个参数目前**没有被使用**（见下方说明）。

    @Test
    fun armingAndReadReceiptsAreSecretOnlyToday() {
        // 当前语义：只看 isSecretChat。
        assertTrue(DisappearingMessagePolicy.shouldArmOnVisible(isSecretChat = true, timerSeconds = 30))
        assertTrue(DisappearingMessagePolicy.shouldArmOnVisible(isSecretChat = true, timerSeconds = 0))
        assertFalse(DisappearingMessagePolicy.shouldArmOnVisible(isSecretChat = false, timerSeconds = 30))

        assertTrue(DisappearingMessagePolicy.shouldSkipReadReceipts(isSecretChat = true))
        assertFalse(DisappearingMessagePolicy.shouldSkipReadReceipts(isSecretChat = false))
    }

    @Test
    fun secretTimerIgnoresNullAndZeroRequests() {
        // 密聊固定 30 秒：null/0 请求也不退化为关闭
        assertEquals(
            DisappearingMessagePolicy.SECRET_DEFAULT_SECONDS,
            DisappearingMessagePolicy.effectiveSeconds(isGroup = false, requestedSeconds = null, isSecret = true),
        )
        assertEquals(
            DisappearingMessagePolicy.SECRET_DEFAULT_SECONDS,
            DisappearingMessagePolicy.effectiveSeconds(isGroup = false, requestedSeconds = 0, isSecret = true),
        )
    }

    @Test
    fun negativeExistingExpiryIsTreatedAsAbsent() {
        // 负数既有值不是合法 deadline，重新起算
        assertEquals(
            1_030_000L,
            DisappearingMessagePolicy.resolveExpiresAt(
                existingExpiresAt = -5L, timerSeconds = 30, readAtMs = 1_000_000L,
            ),
        )
    }

    @Test
    fun degenerateTimersKeepOrStartCorrectly() {
        assertEquals(30_001L, DisappearingMessagePolicy.resolveExpiresAt(null, timerSeconds = 30, readAtMs = 1L))
        // 负定时器等同于关闭：保留既有值
        assertEquals(
            2_000L,
            DisappearingMessagePolicy.resolveExpiresAt(2_000L, timerSeconds = -10, readAtMs = 5_000L),
        )
    }

    @Test
    fun skipReadReceiptsIgnoresBlockFlag() {
        // 第二个参数今天不用——只看 isSecretChat
        assertTrue(DisappearingMessagePolicy.shouldSkipReadReceipts(isSecretChat = true, blockReadReceipts = false))
        assertFalse(DisappearingMessagePolicy.shouldSkipReadReceipts(isSecretChat = false, blockReadReceipts = false))
    }

    @Test
    fun allowedListHasFourteenEntriesToppedByThirtyDays() {
        assertEquals(14, DisappearingMessagePolicy.ALLOWED_SECONDS.size)
        assertEquals(30 * 24 * 60 * 60, DisappearingMessagePolicy.ALLOWED_SECONDS.last())
    }

    @Test
    fun isAllowedSecondsCoversWhitelistAndRejectsNeighbors() {
        DisappearingMessagePolicy.ALLOWED_SECONDS.forEach {
            assertTrue(DisappearingMessagePolicy.isAllowedSeconds(it))
        }
        listOf(-30, 29, 31, 59, 61, 3599, 3601, 604799).forEach {
            assertFalse(DisappearingMessagePolicy.isAllowedSeconds(it))
        }
    }

    @Test
    fun effectiveSecondsPassesEveryWhitelistedValueThrough() {
        DisappearingMessagePolicy.ALLOWED_SECONDS.forEach {
            assertEquals(it, DisappearingMessagePolicy.effectiveSeconds(false, it))
        }
    }

    @Test
    fun resolveExpiresAtDegenerateTimerAndReadAt() {
        assertEquals(null, DisappearingMessagePolicy.resolveExpiresAt(null, timerSeconds = 0, readAtMs = 1_000L))
        assertEquals(null, DisappearingMessagePolicy.resolveExpiresAt(null, timerSeconds = -10, readAtMs = 5_000L))
        assertEquals(2_000L, DisappearingMessagePolicy.resolveExpiresAt(2_000L, timerSeconds = 0, readAtMs = 5_000L))
        assertEquals(2_000L, DisappearingMessagePolicy.resolveExpiresAt(2_000L, timerSeconds = 30, readAtMs = -1L))
        assertEquals(null, DisappearingMessagePolicy.resolveExpiresAt(null, timerSeconds = 30, readAtMs = -1L))
    }

    @Test
    fun negativeDeadlinesAreTreatedAsAbsent() {
        assertFalse(DisappearingMessagePolicy.isExpired(-5L, 1_000L))
        assertEquals(-1L, DisappearingMessagePolicy.remainingMs(-5L, 1_000L))
        assertEquals(0L, DisappearingMessagePolicy.remainingMs(1_000L, 1_000L))
    }

    @Test
    fun armOnVisibleIgnoresTimerMagnitude() {
        assertTrue(DisappearingMessagePolicy.shouldArmOnVisible(isSecretChat = true, timerSeconds = Int.MAX_VALUE))
        assertFalse(DisappearingMessagePolicy.shouldArmOnVisible(isSecretChat = false, timerSeconds = Int.MIN_VALUE))
        assertTrue(DisappearingMessagePolicy.shouldSkipReadReceipts(isSecretChat = true, blockReadReceipts = true))
    }
}
