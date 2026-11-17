package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentFileTaskMessagePolicyTest {

    @Test
    fun `unsupported file type message`() {
        assertEquals("不支持该文件类型", AgentFileTaskMessagePolicy.unsupportedFileType())
    }

    @Test
    fun `vision unsupported message names the model`() {
        val message = AgentFileTaskMessagePolicy.visionUnsupported("gpt-4o-mini")
        assertTrue(message.startsWith("当前模型 (gpt-4o-mini) 不支持视觉多模态输入"))
        assertTrue(message.contains("「AI 与隐私」设置"))
    }
}
