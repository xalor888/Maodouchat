package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallLockScreenFlagPolicyFuzzTest {

    private val random = Random(2026_11_22L)

    private val blankIds = listOf(
        "", " ", "   ", "\t", "\n", "\r", "\t\n", "\u000B",
        "\u00A0", "\u2007", "\u3000", "\u202F",
    )

    private val nonBlankIds = listOf(
        "call-1", "  call-1 ", "a", "x y", "c".repeat(500),
        "\u200B", "\uFEFF", "\u0000", "📞",
    )

    private val idChars = "abcXYZ019 -_.".toList() +
        listOf(' ', '\t', '\n', '\u00A0', '\u200B', '\u3000')

    private fun randomId(): String? =
        when (random.nextInt(5)) {
            0 -> null
            1 -> blankIds[random.nextInt(blankIds.size)]
            2 -> nonBlankIds[random.nextInt(nonBlankIds.size)]
            else -> buildString {
                repeat(random.nextInt(13)) { append(idChars[random.nextInt(idChars.size)]) }
            }
        }

    // 判定式的逐字复述：非空白 id 或待接来电任一成立即点亮。
    private fun oracleShouldEnable(activeCallId: String?, hasPendingIncomingCall: Boolean): Boolean =
        !activeCallId.isNullOrBlank() || hasPendingIncomingCall

    @Test
    fun `shouldEnable matches oracle over random ids and pending flags`() {
        repeat(300) {
            val id = randomId()
            val pending = random.nextBoolean()
            assertEquals(
                oracleShouldEnable(id, pending),
                CallLockScreenFlagPolicy.shouldEnable(id, pending),
                "id=${id?.toList()} pending=$pending",
            )
        }
    }

    @Test
    fun `pending call always enables regardless of id`() {
        repeat(200) {
            val id = randomId()
            assertTrue(
                CallLockScreenFlagPolicy.shouldEnable(id, true),
                "id=${id?.toList()}",
            )
        }
    }

    @Test
    fun `without pending only non-blank ids enable`() {
        repeat(200) {
            val id = randomId()
            assertEquals(
                !id.isNullOrBlank(),
                CallLockScreenFlagPolicy.shouldEnable(id, false),
                "id=${id?.toList()}",
            )
        }
    }

    @Test
    fun `unicode blank boundary table is pinned`() {
        val cases = listOf(
            "\u00A0" to false,
            "\u2007" to false,
            "\u3000" to false,
            "\u202F" to false,
            "\u200B" to true,
            "\uFEFF" to true,
            "\u0000" to true,
            " " to false,
            "\t\n" to false,
            "  call-1 " to true,
        )
        for ((id, expected) in cases) {
            assertEquals(
                expected,
                CallLockScreenFlagPolicy.shouldEnable(id, false),
                "id=${id.toList()}",
            )
        }
    }

    @Test
    fun `same id and pending always yield the same decision`() {
        repeat(100) {
            val id = randomId()
            val pending = random.nextBoolean()
            assertEquals(
                CallLockScreenFlagPolicy.shouldEnable(id, pending),
                CallLockScreenFlagPolicy.shouldEnable(id, pending),
                "id=${id?.toList()} pending=$pending",
            )
        }
    }

    @Test
    fun `null id behaves exactly like an empty id`() {
        repeat(100) {
            val pending = random.nextBoolean()
            assertEquals(
                CallLockScreenFlagPolicy.shouldEnable("", pending),
                CallLockScreenFlagPolicy.shouldEnable(null, pending),
                "pending=$pending",
            )
        }
    }

    @Test
    fun `blank id with pending still enables`() {
        for (id in blankIds) {
            assertTrue(
                CallLockScreenFlagPolicy.shouldEnable(id, true),
                "id=${id.toList()}",
            )
            assertFalse(
                CallLockScreenFlagPolicy.shouldEnable(id, false),
                "id=${id.toList()}",
            )
        }
    }
}
