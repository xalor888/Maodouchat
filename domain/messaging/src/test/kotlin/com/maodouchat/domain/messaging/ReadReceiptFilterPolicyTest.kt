package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ReadReceiptUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadReceiptFilterPolicyTest {

    private fun receipt(userId: String, name: String, readAt: Long? = null) =
        ReadReceiptUi(userId = userId, name = name, readAt = readAt)

    private val receipts = listOf(
        receipt("u1", "Alice", readAt = 100L),
        receipt("u2", "Bob"),
        receipt("u3", "alice-zhang"),
    )

    @Test
    fun `blank query keeps all with unread first`() {
        assertEquals(
            listOf(receipts[1], receipts[2], receipts[0]),
            ReadReceiptFilterPolicy.filter(receipts, ""),
        )
    }

    @Test
    fun `query matches name case-insensitively and keeps unread first`() {
        assertEquals(
            listOf(receipts[2], receipts[0]),
            ReadReceiptFilterPolicy.filter(receipts, "ALICE"),
        )
    }

    @Test
    fun `matches by user id`() {
        assertTrue(ReadReceiptFilterPolicy.matches(receipts[1], "u2"))
        assertFalse(ReadReceiptFilterPolicy.matches(receipts[1], "u1"))
    }

    @Test
    fun `filter trims query`() {
        assertEquals(listOf(receipts[2], receipts[0]), ReadReceiptFilterPolicy.filter(receipts, "  alice "))
    }

    @Test
    fun `filter with no match returns empty`() {
        assertEquals(emptyList<ReadReceiptUi>(), ReadReceiptFilterPolicy.filter(receipts, "zzz"))
    }
}
