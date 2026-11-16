package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiFileAnalysisMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiFileAnalysisModeKindTest {

    @Test
    fun `both modes map to their kinds`() {
        assertEquals(AiFileAnalysisModeKind.SUMMARIZE, aiFileAnalysisModeKind(AiFileAnalysisMode.SUMMARIZE))
        assertEquals(AiFileAnalysisModeKind.QUESTION, aiFileAnalysisModeKind(AiFileAnalysisMode.QUESTION))
    }
}
