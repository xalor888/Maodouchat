package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatGroupAiCommandTest {

    @Test
    fun summaryPrefixes() {
        assertEquals("summary", inferGroupAiMode("总结一下今天的讨论"))
        assertEquals("summary", inferGroupAiMode("概括要点"))
        assertEquals("summary", inferGroupAiMode("Summary of the meeting"))
        assertEquals("summary", inferGroupAiMode("summarize this thread"))
        assertEquals("summary", inferGroupAiMode("  SUMMARY  "))
    }

    @Test
    fun decisionsPrefixes() {
        assertEquals("decisions", inferGroupAiMode("决策：是否上线"))
        assertEquals("decisions", inferGroupAiMode("决定一下方案"))
        assertEquals("decisions", inferGroupAiMode("decisions made so far"))
    }

    @Test
    fun tasksPrefixes() {
        assertEquals("tasks", inferGroupAiMode("待办事项"))
        assertEquals("tasks", inferGroupAiMode("任务列表"))
        assertEquals("tasks", inferGroupAiMode("tasks for today"))
        assertEquals("tasks", inferGroupAiMode("todo: buy milk"))
    }

    @Test
    fun timelinePrefixes() {
        assertEquals("timeline", inferGroupAiMode("时间线梳理"))
        assertEquals("timeline", inferGroupAiMode("时间轴"))
        assertEquals("timeline", inferGroupAiMode("timeline of events"))
        assertEquals("timeline", inferGroupAiMode("chronology"))
    }

    @Test
    fun risksPrefixes() {
        assertEquals("risks", inferGroupAiMode("风险点有哪些"))
        assertEquals("risks", inferGroupAiMode("隐患排查"))
        assertEquals("risks", inferGroupAiMode("risk assessment"))
        assertEquals("risks", inferGroupAiMode("blocker: deploy"))
    }

    @Test
    fun defaultAnswer() {
        assertEquals("answer", inferGroupAiMode("你好"))
        assertEquals("answer", inferGroupAiMode(""))
        assertEquals("answer", inferGroupAiMode("   "))
        // 只认前缀，句中出现关键词不触发。
        assertEquals("answer", inferGroupAiMode("请帮我总结一下"))
    }

    @Test
    fun parseCommandBasic() {
        assertEquals("总结一下", parseGroupAiCommand("@AI 总结一下"))
        assertEquals("hello", parseGroupAiCommand("@ai hello"))
        assertEquals("", parseGroupAiCommand("@AI"))
        assertEquals("hello", parseGroupAiCommand("@AI   hello  "))
        assertEquals("hi", parseGroupAiCommand("  @AI hi"))
    }

    @Test
    fun parseCommandNotACommand() {
        assertNull(parseGroupAiCommand("hello"))
        assertNull(parseGroupAiCommand("@AIBot hi"))
        assertNull(parseGroupAiCommand("@AIx"))
        assertNull(parseGroupAiCommand(""))
    }

    @Test
    fun parseCommandWhitespaceSeparator() {
        assertEquals("hello", parseGroupAiCommand("@AI	hello"))
        assertEquals("", parseGroupAiCommand("@ai"))
    }

    @Test
    fun modeCaseInsensitive() {
        assertEquals("summary", inferGroupAiMode("SUMMARY"))
        assertEquals("tasks", inferGroupAiMode("TODO"))
        assertEquals("risks", inferGroupAiMode("Risk: delay"))
    }
}
