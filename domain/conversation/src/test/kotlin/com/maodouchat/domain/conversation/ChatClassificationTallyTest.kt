package com.maodouchat.domain.conversation

import com.maodouchat.core.model.AiMessageClassifierCategory
import com.maodouchat.core.model.Classification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClassificationTallyTest {

    private fun result(category: AiMessageClassifierCategory, confidence: Double) =
        Classification(category, confidence)

    @Test
    fun tally_emptyWhenNoResults() {
        assertTrue(ChatClassificationTally.tally(emptyList()).isEmpty())
    }

    @Test
    fun tally_singleCategoryMeanConfidence() {
        val tallied = ChatClassificationTally.tally(
            listOf(
                result(AiMessageClassifierCategory.TODO, 0.6),
                result(AiMessageClassifierCategory.TODO, 0.8),
                result(AiMessageClassifierCategory.TODO, 1.0),
            )
        )
        assertEquals(1, tallied.size)
        assertEquals("todo", tallied[0].category)
        assertEquals(3, tallied[0].count)
        assertEquals(0.8, tallied[0].confidence, 1e-9)
    }

    @Test
    fun tally_sortedByCountDescending() {
        val tallied = ChatClassificationTally.tally(
            listOf(
                result(AiMessageClassifierCategory.FINANCE, 0.9),
                result(AiMessageClassifierCategory.NOTICE, 0.5),
                result(AiMessageClassifierCategory.NOTICE, 0.5),
                result(AiMessageClassifierCategory.NOTICE, 0.5),
            )
        )
        assertEquals(2, tallied.size)
        assertEquals("notice", tallied[0].category)
        assertEquals(3, tallied[0].count)
        assertEquals("finance", tallied[1].category)
        assertEquals(1, tallied[1].count)
    }

    @Test
    fun tally_confidenceIsPerCategoryMean() {
        val tallied = ChatClassificationTally.tally(
            listOf(
                result(AiMessageClassifierCategory.TODO, 1.0),
                result(AiMessageClassifierCategory.NOTICE, 0.0),
                result(AiMessageClassifierCategory.NOTICE, 0.0),
            )
        )
        val byCategory = tallied.associateBy { it.category }
        assertEquals(1.0, byCategory.getValue("todo").confidence, 1e-9)
        assertEquals(0.0, byCategory.getValue("notice").confidence, 1e-9)
    }

    @Test
    fun tally_wireNames() {
        val tallied = ChatClassificationTally.tally(
            listOf(result(AiMessageClassifierCategory.SOCIAL, 0.7))
        )
        assertEquals("social", tallied[0].category)
    }
}
