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

    @Test
    fun modePrefix() {
        assertEquals("总结 ", groupAiModePrefix("summary"))
        assertEquals("决策 ", groupAiModePrefix("decisions"))
        assertEquals("待办 ", groupAiModePrefix("tasks"))
        assertEquals("时间线 ", groupAiModePrefix("timeline"))
        assertEquals("风险 ", groupAiModePrefix("risks"))
        assertEquals("", groupAiModePrefix("answer"))
        assertEquals("", groupAiModePrefix("unknown"))
    }

    @Test
    fun stripSummaryKeywords() {
        assertEquals("一下", stripGroupAiModeKeyword("总结一下"))
        assertEquals("要点", stripGroupAiModeKeyword("概括要点"))
        assertEquals("of x", stripGroupAiModeKeyword("summary of x"))
        assertEquals("this", stripGroupAiModeKeyword("SUMMARIZE this"))
        assertEquals(": x", stripGroupAiModeKeyword("Summary: x"))
    }

    @Test
    fun stripDecisionsAndTasksKeywords() {
        assertEquals("：上线", stripGroupAiModeKeyword("决策：上线"))
        assertEquals("一下方案", stripGroupAiModeKeyword("决定一下方案"))
        assertEquals("made", stripGroupAiModeKeyword("decisions made"))
        assertEquals("买菜", stripGroupAiModeKeyword("待办买菜"))
        assertEquals("列表", stripGroupAiModeKeyword("任务列表"))
        assertEquals("for today", stripGroupAiModeKeyword("tasks for today"))
        assertEquals("buy milk", stripGroupAiModeKeyword("TODO buy milk"))
    }

    @Test
    fun stripLeavesTimelineRisksAndPlainUntouched() {
        // 时间线/风险/无模式分支原样返回，这是 app 侧旧行为，逐字保留。
        assertEquals("时间线梳理", stripGroupAiModeKeyword("时间线梳理"))
        assertEquals("timeline of events", stripGroupAiModeKeyword("timeline of events"))
        assertEquals("风险点有哪些", stripGroupAiModeKeyword("风险点有哪些"))
        assertEquals("blocker: deploy", stripGroupAiModeKeyword("blocker: deploy"))
        assertEquals("你好", stripGroupAiModeKeyword("你好"))
        assertEquals("", stripGroupAiModeKeyword(""))
    }
}
