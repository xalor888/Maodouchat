package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnnouncementLevelKindTest {

    @Test
    fun `known levels map one to one`() {
        assertEquals(AnnouncementLevelKind.EMERGENCY, announcementLevelKind("EMERGENCY"))
        assertEquals(AnnouncementLevelKind.MAINTENANCE, announcementLevelKind("MAINTENANCE"))
    }

    @Test
    fun `info and unknown levels fall back to info like the old else branch`() {
        assertEquals(AnnouncementLevelKind.INFO, announcementLevelKind("INFO"))
        assertEquals(AnnouncementLevelKind.INFO, announcementLevelKind("URGENT"))
        assertEquals(AnnouncementLevelKind.INFO, announcementLevelKind(""))
    }
}
