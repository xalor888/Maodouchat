package com.maodouchat.domain.messaging

import com.maodouchat.core.model.CategoryCount
import kotlin.test.Test
import kotlin.test.assertEquals

class ClassifyResultTextTest {

    private val labelText: (AiClassifyCategoryKind) -> String = { kind -> kind.name }
    private val countText: (Int) -> String = { count -> "$count items" }

    @Test
    fun rowsThenDisclaimer() {
        val text = classifyResultText(
            listOf(
                CategoryCount("notice", 3, 0.9),
                CategoryCount("todo", 1, 0.8)
            ),
            labelText, countText, "disclaimer"
        )
        assertEquals("NOTICE: 3 items\nTODO: 1 items\ndisclaimer", text)
    }

    @Test
    fun emptyListYieldsOnlyDisclaimer() {
        val text = classifyResultText(emptyList(), labelText, countText, "disclaimer")
        assertEquals("disclaimer", text)
    }

    @Test
    fun unknownCategoryFallsBackToOther() {
        val text = classifyResultText(
            listOf(CategoryCount("mystery", 2, 0.5)),
            labelText, countText, "disclaimer"
        )
        assertEquals("OTHER: 2 items\ndisclaimer", text)
    }
}
