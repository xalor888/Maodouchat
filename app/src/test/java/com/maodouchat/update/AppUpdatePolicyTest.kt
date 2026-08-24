package com.maodouchat.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {

    @Test
    fun newerHttpsApkIsOffered() {
        assertTrue(
            AppUpdatePolicy.shouldOfferUpdate(
                currentVersionCode = 17,
                remoteVersionCode = 18,
                apkUrl = "https://example.com/maodou.apk",
            )
        )
    }

    @Test
    fun sameOrOlderIsIgnored() {
        assertFalse(
            AppUpdatePolicy.shouldOfferUpdate(18, 18, "https://example.com/a.apk")
        )
        assertFalse(
            AppUpdatePolicy.shouldOfferUpdate(19, 18, "https://example.com/a.apk")
        )
    }

    @Test
    fun blankOrNonHttpsApkIsRejected() {
        assertFalse(AppUpdatePolicy.shouldOfferUpdate(1, 2, ""))
        assertFalse(AppUpdatePolicy.shouldOfferUpdate(1, 2, "http://insecure.example/a.apk"))
        assertFalse(AppUpdatePolicy.shouldOfferUpdate(1, 2, "github.com/x/y"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("http://insecure.example/a.apk"))
        assertTrue(AppUpdatePolicy.isOfficialApkUrl("https://official.example/a.apk"))
    }

    @Test
    fun hostedLatestApkIsOffered() {
        assertTrue(
            AppUpdatePolicy.shouldOfferUpdate(
                currentVersionCode = 10,
                remoteVersionCode = 11,
                apkUrl = "https://chat.example.com/api/public/app-update/latest.apk",
            )
        )
    }
}
