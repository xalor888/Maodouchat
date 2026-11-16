package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiClassifyCategoryKindTest {

    @Test
    fun `all seven categories map to their kinds`() {
        assertEquals(AiClassifyCategoryKind.NOTICE, aiClassifyCategoryKind("notice"))
        assertEquals(AiClassifyCategoryKind.TODO, aiClassifyCategoryKind("todo"))
        assertEquals(AiClassifyCategoryKind.FINANCE, aiClassifyCategoryKind("finance"))
        assertEquals(AiClassifyCategoryKind.STUDY, aiClassifyCategoryKind("study"))
        assertEquals(AiClassifyCategoryKind.TECH, aiClassifyCategoryKind("tech"))
        assertEquals(AiClassifyCategoryKind.SOCIAL, aiClassifyCategoryKind("social"))
    }

    @Test
    fun `unknown category falls back to other`() {
        assertEquals(AiClassifyCategoryKind.OTHER, aiClassifyCategoryKind("spam"))
        assertEquals(AiClassifyCategoryKind.OTHER, aiClassifyCategoryKind(""))
    }

    @Test
    fun `matching is case sensitive like the original when`() {
        assertEquals(AiClassifyCategoryKind.OTHER, aiClassifyCategoryKind("Notice"))
    }
}
