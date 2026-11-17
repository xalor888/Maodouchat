package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentTranscriptionPolicyTest {

    @Test
    fun `extractText reads text field`() {
        assertEquals("hello", AgentTranscriptionPolicy.extractText("""{"text":"hello"}"""))
    }

    @Test
    fun `extractText returns empty for missing or malformed payload`() {
        assertEquals("", AgentTranscriptionPolicy.extractText("""{"nope":1}"""))
        // 空白原文照原样返回，调用方用 isBlank 判空（与 app 侧一致）。
        assertEquals("  ", AgentTranscriptionPolicy.extractText("""{"text":"  "}"""))
        assertEquals("", AgentTranscriptionPolicy.extractText("not json"))
        assertEquals("", AgentTranscriptionPolicy.extractText(""))
    }
}
