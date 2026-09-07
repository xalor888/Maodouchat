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
}
