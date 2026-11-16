package com.maodouchat.domain.groups

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupAnnouncementPolicyTest {

    @Test
    fun `filterInput truncates overlong input to 1200 chars`() {
        assertEquals("a".repeat(1200), GroupAnnouncementPolicy.filterInput("a".repeat(1201)))
        assertEquals("a".repeat(1200), GroupAnnouncementPolicy.filterInput("a".repeat(2000)))
    }

    @Test
    fun `filterInput keeps short input unchanged`() {
        assertEquals("abc", GroupAnnouncementPolicy.filterInput("abc"))
        assertEquals("", GroupAnnouncementPolicy.filterInput(""))
        assertEquals("a".repeat(1200), GroupAnnouncementPolicy.filterInput("a".repeat(1200)))
    }

    @Test
    fun `max length is 1200`() {
        assertEquals(1200, GroupAnnouncementPolicy.MAX_LENGTH)
    }
}
