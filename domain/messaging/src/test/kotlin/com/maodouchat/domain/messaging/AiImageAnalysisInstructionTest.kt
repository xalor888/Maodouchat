package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiImageAnalysisInstructionTest {

    @Test
    fun `ocr mode extracts text only`() {
        assertEquals(
            "只提取图中文字，按阅读顺序输出。没有文字就输出空。",
            aiImageAnalysisInstruction("ocr")
        )
    }

    @Test
    fun `risk mode asks for visible risks`() {
        assertEquals(
            "简述图中可见风险（钓鱼、二维码、证件）。不要声称已执行任何操作。",
            aiImageAnalysisInstruction("risk")
        )
    }

    @Test
    fun `unknown modes fall back to describe and input is normalized`() {
        val describe = "用一两段话描述这张图。不要声称已发送或已删除消息。"
        assertEquals(describe, aiImageAnalysisInstruction("describe"))
        assertEquals(describe, aiImageAnalysisInstruction(""))
        assertEquals(describe, aiImageAnalysisInstruction("foo"))
        assertEquals(
            "只提取图中文字，按阅读顺序输出。没有文字就输出空。",
            aiImageAnalysisInstruction(" OCR ")
        )
        assertEquals(
            "简述图中可见风险（钓鱼、二维码、证件）。不要声称已执行任何操作。",
            aiImageAnalysisInstruction("Risk")
        )
    }
}
