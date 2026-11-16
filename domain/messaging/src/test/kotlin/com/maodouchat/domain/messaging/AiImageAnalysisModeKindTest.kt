package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiImageAnalysisMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiImageAnalysisModeKindTest {

    @Test
    fun `all three modes map to their kinds`() {
        assertEquals(AiImageAnalysisModeKind.DESCRIBE, aiImageAnalysisModeKind(AiImageAnalysisMode.DESCRIBE))
        assertEquals(AiImageAnalysisModeKind.OCR, aiImageAnalysisModeKind(AiImageAnalysisMode.OCR))
        assertEquals(AiImageAnalysisModeKind.SAFETY, aiImageAnalysisModeKind(AiImageAnalysisMode.SAFETY))
    }
}
