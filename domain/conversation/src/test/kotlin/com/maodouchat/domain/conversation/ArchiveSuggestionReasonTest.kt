package com.maodouchat.domain.conversation

import com.maodouchat.domain.conversation.ArchiveSuggestionReason.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveSuggestionReasonTest {

    @Test
    fun selectKind_idle60AtBoundary() {
        assertEquals(Kind.Idle60, ArchiveSuggestionReason.selectKind(60, 0))
        assertEquals(Kind.Idle60, ArchiveSuggestionReason.selectKind(100, 30))
    }

    @Test
    fun selectKind_idle21Below60() {
        assertEquals(Kind.Idle21, ArchiveSuggestionReason.selectKind(59, 0))
        assertEquals(Kind.Idle21, ArchiveSuggestionReason.selectKind(21, 30))
    }

    @Test
    fun selectKind_lowActivityBelow21() {
        assertEquals(Kind.LowActivity, ArchiveSuggestionReason.selectKind(20, 2))
        assertEquals(Kind.LowActivity, ArchiveSuggestionReason.selectKind(0, 0))
    }

    @Test
    fun selectKind_decliningOtherwise() {
        assertEquals(Kind.Declining, ArchiveSuggestionReason.selectKind(20, 3))
        assertEquals(Kind.Declining, ArchiveSuggestionReason.selectKind(0, 30))
    }

    @Test
    fun selectKind_idleWinsOverLowActivity() {
        // 静置分支优先于低活跃分支，与原 when 顺序一致。
        assertEquals(Kind.Idle60, ArchiveSuggestionReason.selectKind(90, 0))
        assertEquals(Kind.Idle21, ArchiveSuggestionReason.selectKind(30, 1))
    }
}
