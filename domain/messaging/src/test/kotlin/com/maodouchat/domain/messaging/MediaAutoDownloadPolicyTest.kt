package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaAutoDownloadPolicyTest {

    @Test
    fun knownModesPassThrough() {
        assertEquals("always", MediaAutoDownloadPolicy.normalizeMode("always"))
        assertEquals("off", MediaAutoDownloadPolicy.normalizeMode("off"))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("wifi_only"))
    }

    @Test
    fun unknownModeFallsBackToWifiOnly() {
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode("all"))
        assertEquals("wifi_only", MediaAutoDownloadPolicy.normalizeMode(""))
    }
}
