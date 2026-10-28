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

    @Test
    fun negativeCurrentAndNonPositiveRemoteAreRejected() {
        assertFalse(
            AppUpdatePolicy.shouldOfferUpdate(-1, 2, "https://chat.mdou.me/a.apk", apkSha256)
        )
        assertFalse(
            AppUpdatePolicy.shouldOfferUpdate(1, 0, "https://chat.mdou.me/a.apk", apkSha256)
        )
        assertFalse(
            AppUpdatePolicy.shouldOfferUpdate(1, -5, "https://chat.mdou.me/a.apk", apkSha256)
        )
        // current=0 不在拒绝条件里（只拒 <0），remote 更大就照常给。
        assertTrue(
            AppUpdatePolicy.shouldOfferUpdate(0, 1, "https://chat.mdou.me/a.apk", apkSha256)
        )
    }

    @Test
    fun bareDomainAndSubdomainsAreOfficial() {
        assertTrue(AppUpdatePolicy.isOfficialApkUrl("https://mdou.me/a.apk"))
        assertTrue(AppUpdatePolicy.isOfficialApkUrl("https://deep.sub.mdou.me/a.apk"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://mdou.me.evil.com/a.apk"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://fakemdou.me/a.apk"))
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://chat.mdou.me.evil.com/a.apk"))
    }

    @Test
    fun uppercaseSchemeAndPaddedUrlAreOfficial() {
        assertTrue(AppUpdatePolicy.isOfficialApkUrl("HTTPS://chat.mdou.me/a.apk"))
        assertTrue(AppUpdatePolicy.isOfficialApkUrl("  https://chat.mdou.me/a.apk  "))
        // trim 后尾部换行也没了，照样官方。
        assertTrue(AppUpdatePolicy.isOfficialApkUrl("https://chat.mdou.me/a.apk\n"))
        // 无路径也算官方（hostOf 不要求斜杠）。
        assertTrue(AppUpdatePolicy.isOfficialApkUrl("https://chat.mdou.me"))
        // userinfo 花招：host 取到的是 @ 后的 evil.com。
        assertFalse(AppUpdatePolicy.isOfficialApkUrl("https://chat.mdou.me@evil.com/a.apk"))
    }

    @Test
    fun formatNotesHandlesBlankTrimAndPercent() {
        assertTrue(AppUpdatePolicy.formatNotes("").isEmpty())
        assertTrue(AppUpdatePolicy.formatNotes("   ").isEmpty())
        assertTrue(AppUpdatePolicy.formatNotes("hello world") == "hello world")
        assertTrue(AppUpdatePolicy.formatNotes("  hello world  ") == "hello world")
        assertTrue(AppUpdatePolicy.formatNotes("hello%20world") == "hello world")
        // 没有 % 就不走 URL 解码，加号原样保留。
        assertTrue(AppUpdatePolicy.formatNotes("a+b") == "a+b")
        // % 后没跟两位十六进制：解码抛异常，回落原文。
        assertTrue(AppUpdatePolicy.formatNotes("100%") == "100%")
    }

    @Test
    fun hostOfStripsPortQueryAndRejectsSchemeLess() {
        assertTrue(AppUpdatePolicy.hostOf("https://chat.mdou.me:8443/a.apk?x=1") == "chat.mdou.me")
        assertTrue(AppUpdatePolicy.hostOf("https://chat.mdou.me/a.apk?x=1") == "chat.mdou.me")
        assertTrue(AppUpdatePolicy.hostOf("no-scheme-here") == null)
        assertTrue(AppUpdatePolicy.hostOf("") == null)
        assertTrue(AppUpdatePolicy.hostOf("https://") == null)
    }
    @Test
    fun nonOfficialUrlFailsEvenWithNewerVersionAndValidSha() {
        assertFalse(
            AppUpdatePolicy.shouldOfferUpdate(
                currentVersionCode = 17,
                remoteVersionCode = 18,
                apkUrl = "https://github.com/xalor888/maodouchat/releases/download/v1/a.apk",
                apkSha256 = apkSha256,
            )
        )
        assertFalse(
            AppUpdatePolicy.shouldOfferUpdate(
                currentVersionCode = 17,
                remoteVersionCode = 18,
                apkUrl = "https://cdn.example.com/a.apk",
                apkSha256 = apkSha256,
            )
        )
    }

    @Test
    fun sha256ComparisonTrimsAndIgnoresCaseButRejectsInnerSpace() {
        val expected = "a".repeat(64)
        assertTrue(AppUpdatePolicy.hasExpectedApkSha256("  $expected  "))
        assertTrue(AppUpdatePolicy.matchesExpectedApkSha256(expected.uppercase(), "  $expected  "))
        assertFalse(AppUpdatePolicy.hasExpectedApkSha256("a".repeat(32) + " " + "a".repeat(32)))
        assertFalse(AppUpdatePolicy.matchesExpectedApkSha256(expected, "a".repeat(32) + " " + "a".repeat(32)))
        assertFalse(AppUpdatePolicy.hasExpectedApkSha256(""))
    }

    @Test
    fun formatNotesRecoversLatin1Mojibake() {
        // UTF-8 字节被误当 Latin-1 读出的乱码：含 0x80..0xFF 字符且不在 CJK 区，
        // 走 ISO-8859-1 取字节再按 UTF-8 解码，正好还原原文。
        val mojibake = String("更新说明".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertTrue(mojibake != "更新说明")
        assertTrue(AppUpdatePolicy.formatNotes(mojibake) == "更新说明")
    }

    @Test
    fun formatNotesDecodesPercentEncodedUtf8() {
        assertTrue(AppUpdatePolicy.formatNotes("%E6%9B%B4%E6%96%B0") == "更新")
        assertTrue(AppUpdatePolicy.formatNotes("%25") == "%")
    }

    @Test
    fun hostOfKeepsUserinfoAndStripsTrailingSlashes() {
        // hostOf 不拆 @：userinfo 花招的 host 原样是 chat.mdou.me@evil.com。
        assertTrue(AppUpdatePolicy.hostOf("https://chat.mdou.me@evil.com/a.apk") == "chat.mdou.me@evil.com")
        assertTrue(AppUpdatePolicy.hostOf("https://chat.mdou.me///a.apk") == "chat.mdou.me")
        assertTrue(AppUpdatePolicy.hostOf("https://   ") == null)
    }

}
