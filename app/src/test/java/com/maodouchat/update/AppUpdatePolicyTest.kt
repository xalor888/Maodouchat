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
                apkUrl = "https://chat.mdou.me/maodou.apk",
            )
        )
    }

    @Test
    fun sameOrOlderIsIgnored() {
        assertFalse(
            AppUpdatePolicy.shouldOfferUpdate(18, 18, "https://chat.mdou.me/a.apk")
        )
        assertFalse(
            AppUpdatePolicy.shouldOfferUpdate(19, 18, "https://chat.mdou.me/a.apk")
        )
    }

    @Test
    fun blankOrNonHttpsApkIsRejected() {
        assertFalse(AppUpdatePolicy.shouldOfferUpdate(1, 2, ""))
        assertFalse(AppUpdatePolicy.shouldOfferUpdate(1, 2, "http://insecure.example/a.apk"))
        assertFalse(AppUpdatePolicy.shouldOfferUpdate(1, 2, "github.com/x/y"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("http://insecure.example/a.apk"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://official.example/a.apk"))
        assertTrue(AppUpdatePolicy.isOfficialApkUrl("https://chat.mdou.me/a.apk"))
    }

    @Test
    fun hostedLatestApkIsOffered() {
        assertTrue(
            AppUpdatePolicy.shouldOfferUpdate(
                currentVersionCode = 10,
                remoteVersionCode = 11,
                apkUrl = "https://chat.mdou.me/api/public/app-update/latest.apk",
            )
        )
    }

    @Test
    fun redirectHostMustStayOfficial() {
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://github.com/xalor888/maodouchat/releases/download/v1/a.apk"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://cdn.example.com/maodou.apk"))
        assertTrue(AppUpdatePolicy.isOfficialApkUrl("https://files.mdou.me/maodou.apk"))
    }
}
