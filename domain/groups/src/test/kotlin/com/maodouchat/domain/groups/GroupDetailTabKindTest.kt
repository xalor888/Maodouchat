package com.maodouchat.domain.groups

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupDetailTabKindTest {

    @Test
    fun `all seven tabs map one to one`() {
        assertEquals(GroupDetailTabKind.MEMBERS, groupDetailTabKind(GroupDetailTab.MEMBERS))
        assertEquals(GroupDetailTabKind.AUDIT, groupDetailTabKind(GroupDetailTab.AUDIT))
        assertEquals(GroupDetailTabKind.MEDIA, groupDetailTabKind(GroupDetailTab.MEDIA))
        assertEquals(GroupDetailTabKind.FILES, groupDetailTabKind(GroupDetailTab.FILES))
        assertEquals(GroupDetailTabKind.VOICE, groupDetailTabKind(GroupDetailTab.VOICE))
        assertEquals(GroupDetailTabKind.LOCATION, groupDetailTabKind(GroupDetailTab.LOCATION))
        assertEquals(GroupDetailTabKind.LINKS, groupDetailTabKind(GroupDetailTab.LINKS))
    }
}
