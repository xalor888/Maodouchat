package com.maodouchat.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateInstallPolicyTest {

    @Test
    fun acceptsMatchingUpgrade() {
        assertTrue(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 42,
                archiveVersionCode = 42L,
                installedVersionCode = 41L,
            )
        )
    }

    @Test
    fun rejectsDowngradeOrEqual() {
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 40,
                archiveVersionCode = 40L,
                installedVersionCode = 41L,
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 41,
                archiveVersionCode = 41L,
                installedVersionCode = 41L,
            )
        )
    }

    @Test
    fun rejectsArchiveMismatchingOffer() {
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 42,
                archiveVersionCode = 43L,
                installedVersionCode = 41L,
            )
        )
    }

    @Test
    fun rejectsInvalidCodes() {
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 0,
                archiveVersionCode = 1L,
                installedVersionCode = 0L,
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 2,
                archiveVersionCode = 0L,
                installedVersionCode = 1L,
            )
        )
    }

    @Test
    fun packageAndSignerMustMatchExactly() {
        assertTrue(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = "com.maodouchat",
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = setOf("aa"),
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = "com.evil",
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = setOf("aa"),
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = "com.maodouchat",
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = setOf("bb"),
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = "com.maodouchat",
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = emptySet(),
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = null,
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = setOf("aa"),
            )
        )
    }

    @Test
    fun negativeExpectedOrArchiveAreRejected() {
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = -1,
                archiveVersionCode = 1L,
                installedVersionCode = 0L,
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 0,
                archiveVersionCode = 0L,
                installedVersionCode = 0L,
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 42,
                archiveVersionCode = -1L,
                installedVersionCode = 41L,
            )
        )
    }

    @Test
    fun archiveAboveInstalledButNotMatchingOfferIsRejected() {
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 50,
                archiveVersionCode = 42L,
                installedVersionCode = 41L,
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 50,
                archiveVersionCode = 49L,
                installedVersionCode = 41L,
            )
        )
        // 与 offer 一致但没高过已装版：照样是降级/同版，不放行。
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 42,
                archiveVersionCode = 42L,
                installedVersionCode = 50L,
            )
        )
    }

    @Test
    fun intMaxBoundaryUpgradeIsAccepted() {
        assertTrue(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = Int.MAX_VALUE,
                archiveVersionCode = Int.MAX_VALUE.toLong(),
                installedVersionCode = (Int.MAX_VALUE - 1).toLong(),
            )
        )
        assertTrue(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 1,
                archiveVersionCode = 1L,
                installedVersionCode = 0L,
            )
        )
    }

    @Test
    fun blankPackageNamesAreRejected() {
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "",
                archivePackageName = "com.maodouchat",
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = setOf("aa"),
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "   ",
                archivePackageName = "com.maodouchat",
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = setOf("aa"),
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = "",
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = setOf("aa"),
            )
        )
    }

    @Test
    fun signerSetsMustMatchExactlyRegardlessOfOrder() {
        // 集合相等不看顺序。
        assertTrue(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = "com.maodouchat",
                installedSignerDigests = setOf("aa", "bb"),
                archiveSignerDigests = setOf("bb", "aa"),
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = "com.maodouchat",
                installedSignerDigests = setOf("aa", "bb"),
                archiveSignerDigests = setOf("aa"),
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = "com.maodouchat",
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = setOf("aa", "bb"),
            )
        )
        // 包名与签名比对都区分大小写。
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = "COM.MAODOUCHAT",
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = setOf("aa"),
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = "com.maodouchat",
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = setOf("AA"),
            )
        )
    }
    @Test
    fun archiveMismatchBelowOfferIsRejected() {
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 50,
                archiveVersionCode = 42L,
                installedVersionCode = 42L,
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 42,
                archiveVersionCode = 41L,
                installedVersionCode = 40L,
            )
        )
    }

    @Test
    fun negativeInstalledIsNotChecked() {
        // 门闩只看 expected/archive/两两关系，installed 为负也不拦升级。
        assertTrue(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 42,
                archiveVersionCode = 42L,
                installedVersionCode = -1L,
            )
        )
        assertTrue(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 42,
                archiveVersionCode = 42L,
                installedVersionCode = Long.MIN_VALUE,
            )
        )
    }

    @Test
    fun archiveBeyondIntMaxIsRejected() {
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = Int.MAX_VALUE,
                archiveVersionCode = Int.MAX_VALUE.toLong() + 1L,
                installedVersionCode = 0L,
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.acceptsArchiveVersion(
                expectedRemoteVersionCode = 42,
                archiveVersionCode = 4294967296L,
                installedVersionCode = 41L,
            )
        )
    }

    @Test
    fun emptySignerSetsAreRejected() {
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = "com.maodouchat",
                installedSignerDigests = emptySet(),
                archiveSignerDigests = emptySet(),
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = "com.maodouchat",
                installedSignerDigests = emptySet(),
                archiveSignerDigests = setOf("aa"),
            )
        )
    }

    @Test
    fun packageNameIsNotTrimmedOrCaseFolded() {
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat ",
                archivePackageName = "com.maodouchat",
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = setOf("aa"),
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = " com.maodouchat",
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = setOf("aa"),
            )
        )
        assertFalse(
            AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = "com.maodouchat",
                archivePackageName = "   ",
                installedSignerDigests = setOf("aa"),
                archiveSignerDigests = setOf("aa"),
            )
        )
    }

}
