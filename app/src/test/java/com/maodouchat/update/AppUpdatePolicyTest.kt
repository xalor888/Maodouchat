package com.maodouchat.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {

    private val apkSha256 = "a".repeat(64)

    @Test
    fun newerHttpsApkIsOffered() {
        assertTrue(
            AppUpdatePolicy.shouldOfferUpdate(
                currentVersionCode = 17,
                remoteVersionCode = 18,
                apkUrl = "https://chat.mdou.me/maodou.apk",
                apkSha256 = apkSha256,
            )
        )
    }

    @Test
    fun sameOrOlderIsIgnored() {
        assertFalse(
            AppUpdatePolicy.shouldOfferUpdate(18, 18, "https://chat.mdou.me/a.apk", apkSha256)
        )
        assertFalse(
            AppUpdatePolicy.shouldOfferUpdate(19, 18, "https://chat.mdou.me/a.apk", apkSha256)
        )
    }

    @Test
    fun blankOrNonHttpsApkIsRejected() {
        assertFalse(AppUpdatePolicy.shouldOfferUpdate(1, 2, "", apkSha256))
        assertFalse(AppUpdatePolicy.shouldOfferUpdate(1, 2, "http://insecure.example/a.apk", apkSha256))
        assertFalse(AppUpdatePolicy.shouldOfferUpdate(1, 2, "github.com/x/y", apkSha256))
        assertFalse(AppUpdatePolicy.shouldOfferUpdate(1, 2, "https://chat.mdou.me/a.apk"))
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
                apkSha256 = apkSha256,
            )
        )
    }

    @Test
    fun expectedApkSha256MustBeCanonicalSha256() {
        val expected = "a".repeat(64)
        assertTrue(AppUpdatePolicy.hasExpectedApkSha256(expected))
        assertTrue(AppUpdatePolicy.matchesExpectedApkSha256(expected.uppercase(), expected))
        assertFalse(AppUpdatePolicy.hasExpectedApkSha256("a".repeat(63)))
        assertFalse(AppUpdatePolicy.hasExpectedApkSha256("g".repeat(64)))
        assertFalse(AppUpdatePolicy.matchesExpectedApkSha256("b".repeat(64), expected))
    }

    @Test
    fun redirectHostMustStayOfficial() {
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://github.com/xalor888/maodouchat/releases/download/v1/a.apk"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://cdn.example.com/maodou.apk"))
        assertTrue(AppUpdatePolicy.isOfficialApkUrl("https://files.mdou.me/maodou.apk"))
    }
}
