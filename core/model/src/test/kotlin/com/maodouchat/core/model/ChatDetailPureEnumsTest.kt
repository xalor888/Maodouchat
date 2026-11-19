package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDetailPureEnumsTest {

    @Test
    fun `media center categories cover all five buckets`() {
        assertEquals(
            listOf(
                MediaCenterCategory.MEDIA,
                MediaCenterCategory.FILES,
                MediaCenterCategory.VOICE,
                MediaCenterCategory.LINKS,
                MediaCenterCategory.LOCATION,
            ),
            MediaCenterCategory.entries,
        )
    }
}
