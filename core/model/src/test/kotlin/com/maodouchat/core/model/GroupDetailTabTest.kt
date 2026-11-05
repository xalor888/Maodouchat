package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupDetailTabTest {

    @Test
    fun `entries keep declaration order`() {
        assertEquals(
            listOf(
                GroupDetailTab.MEMBERS,
                GroupDetailTab.AUDIT,
                GroupDetailTab.MEDIA,
                GroupDetailTab.FILES,
                GroupDetailTab.VOICE,
                GroupDetailTab.LOCATION,
                GroupDetailTab.LINKS,
            ),
            GroupDetailTab.entries.toList(),
        )
    }

    @Test
    fun `valueOf round-trips every entry`() {
        GroupDetailTab.entries.forEach { assertEquals(it, GroupDetailTab.valueOf(it.name)) }
    }

    @Test
    fun `mediaCategory maps each media tab to its category`() {
        assertNull(GroupDetailTab.MEMBERS.mediaCategory)
        assertNull(GroupDetailTab.AUDIT.mediaCategory)
        assertEquals(MediaCenterCategory.MEDIA, GroupDetailTab.MEDIA.mediaCategory)
        assertEquals(MediaCenterCategory.FILES, GroupDetailTab.FILES.mediaCategory)
        assertEquals(MediaCenterCategory.VOICE, GroupDetailTab.VOICE.mediaCategory)
        assertEquals(MediaCenterCategory.LOCATION, GroupDetailTab.LOCATION.mediaCategory)
        assertEquals(MediaCenterCategory.LINKS, GroupDetailTab.LINKS.mediaCategory)
    }
}
