package com.maodouchat.domain.conversation

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublicStatusBannerPolicyTest {

    @Test
    fun `empty object shows nothing`() {
        assertNull(buildPublicStatusBanner(JSONObject()))
    }

    @Test
    fun `banner only`() {
        assertEquals("hi", buildPublicStatusBanner(JSONObject().put("banner", "hi")))
    }

    @Test
    fun `fallback chain uses global banner and e2ee banner`() {
        val o = JSONObject()
            .put("globalBanner", "global")
            .put("e2eeBanner", "e2ee")
        assertEquals("global · e2ee", buildPublicStatusBanner(o))
    }

    @Test
    fun `maintenance message wins over parts`() {
        val o = JSONObject()
            .put("banner", "hi")
            .put("maintenance", true)
            .put("maintenanceMessage", "down for maintenance")
        assertEquals("down for maintenance", buildPublicStatusBanner(o))
    }

    @Test
    fun `legacy maintenanceMode key works`() {
        val o = JSONObject()
            .put("maintenanceMode", true)
            .put("maintenanceMessage", "legacy maintenance")
        assertEquals("legacy maintenance", buildPublicStatusBanner(o))
    }

    @Test
    fun `maintenance without message falls back to parts`() {
        val o = JSONObject()
            .put("banner", "hi")
            .put("maintenance", true)
        assertEquals("hi", buildPublicStatusBanner(o))
    }

    @Test
    fun `literal null string is treated as blank`() {
        val o = JSONObject().put("banner", JSONObject.NULL)
        assertNull(buildPublicStatusBanner(o))
    }

    @Test
    fun `minApp zero has no hint and non-zero shows hint`() {
        val zero = JSONObject().put("minAppVersion", "0")
        assertNull(buildPublicStatusBanner(zero))
        val v = JSONObject().put("minAppVersion", "2.5.0")
        assertEquals("Min version: 2.5.0", buildPublicStatusBanner(v))
    }

    @Test
    fun `pqxdh hint appended after banner`() {
        val o = JSONObject().put("banner", "hi").put("pqxdhPreview", true)
        assertEquals("hi · PQXDH preview on", buildPublicStatusBanner(o))
    }

    @Test
    fun `parts joined with middle dot in order`() {
        val o = JSONObject()
            .put("banner", "b")
            .put("forceE2eeBanner", "e")
            .put("publicAnnouncement", "a")
        assertEquals("b · e · a", buildPublicStatusBanner(o))
    }
}
