package com.maodouchat.domain.groups

import kotlin.test.Test
import kotlin.test.assertEquals

class SenderKeyTargetFilterTest {

    @Test
    fun `sender key target filters cover all four buckets`() {
        assertEquals(
            listOf(
                SenderKeyTargetFilter.ALL,
                SenderKeyTargetFilter.FAILED,
                SenderKeyTargetFilter.PENDING,
                SenderKeyTargetFilter.SENT,
            ),
            SenderKeyTargetFilter.entries,
        )
    }

}
