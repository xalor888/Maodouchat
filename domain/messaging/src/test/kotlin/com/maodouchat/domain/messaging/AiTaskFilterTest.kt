package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AiTaskFilterTest {

    @Test
    fun `ai task filters cover all three buckets`() {
        assertEquals(
            listOf(AiTaskFilter.ALL, AiTaskFilter.PENDING, AiTaskFilter.COMPLETED),
            AiTaskFilter.entries,
        )
    }
}
