package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [AppUpdateInstallPolicy] (checklist line 931, 35th block).
 * Test-only, zero production changes.
 *
 * Both gates are plain boolean formulas, restated in the oracles: the archive
 * version must be positive, equal to the offered remote versionCode, and
 * strictly greater than the installed one; package/signer trust needs
 * non-blank equal package names and a non-empty archive digest set exactly
 * equal to the installed set (order-insensitive). A Python mini-model
 * differentially checks both oracles on 5000 random inputs each (0 diffs)
 * before this file was written.
 */
class AppUpdateInstallPolicyFuzzTest {

    private val packages = listOf(
        "com.maodouchat", "com.maodouchat", "com.other", "", "   ", "com.maodouchat2", "COM.MAODOUCHAT"
    )
    private val digests = listOf("aa", "bb", "cc", "dd", "AA", "aa ", " aabbcc")

    private fun oracleAccepts(expected: Int, archive: Long, installed: Long): Boolean {
        if (expected <= 0) return false
        if (archive <= 0L) return false
        if (archive != expected.toLong()) return false
        return archive > installed
    }

    private fun oracleTrusted(
        installedPkg: String,
        archivePkg: String?,
        installedDigests: Set<String>,
        archiveDigests: Set<String>,
    ): Boolean {
        if (installedPkg.isBlank()) return false
        if (archivePkg.isNullOrBlank()) return false
        if (archivePkg != installedPkg) return false
        if (archiveDigests.isEmpty()) return false
        return archiveDigests == installedDigests
    }

    private fun randomVersion(rnd: Random): Long {
        return when (rnd.nextInt(8)) {
            0 -> 0L
            1 -> -1L
            2 -> 1L
            3 -> rnd.nextInt(-5, 40).toLong()
            4 -> Int.MAX_VALUE.toLong()
            5 -> Long.MAX_VALUE
            6 -> Long.MIN_VALUE
            else -> rnd.nextInt(0, 100).toLong()
        }
    }

    private fun randomDigestSet(rnd: Random): Set<String> {
        val n = rnd.nextInt(0, 5)
        return (1..n).map { digests.random(rnd) }.toSet()
    }

    @Test
    fun acceptsArchiveVersionRandomOracle() {
        val rnd = Random(35)
        repeat(500) {
            val expected = randomVersion(rnd).toInt()
            val archive = randomVersion(rnd)
            val installed = randomVersion(rnd)
            assertEquals(
                oracleAccepts(expected, archive, installed),
                AppUpdateInstallPolicy.acceptsArchiveVersion(expected, archive, installed),
                "expected=$expected archive=$archive installed=$installed"
            )
        }
    }

    @Test
    fun isPackageAndSignerTrustedRandomOracle() {
        val rnd = Random(35)
        repeat(300) {
            val installedPkg = packages.random(rnd)
            val archivePkg = when (rnd.nextInt(5)) {
                0 -> null
                1 -> installedPkg
                else -> packages.random(rnd)
            }
            val installedDigests = randomDigestSet(rnd)
            val archiveDigests = when (rnd.nextInt(4)) {
                0 -> installedDigests
                1 -> installedDigests.shuffled(rnd).toSet()
                else -> randomDigestSet(rnd)
            }
            assertEquals(
                oracleTrusted(installedPkg, archivePkg, installedDigests, archiveDigests),
                AppUpdateInstallPolicy.isPackageAndSignerTrusted(installedPkg, archivePkg, installedDigests, archiveDigests),
                "installedPkg=<$installedPkg> archivePkg=<$archivePkg> installed=$installedDigests archive=$archiveDigests"
            )
        }
    }

    @Test
    fun installGatePins() {
        assertFalse(AppUpdateInstallPolicy.acceptsArchiveVersion(10, 10, 10))
        assertFalse(AppUpdateInstallPolicy.acceptsArchiveVersion(10, 10, 11))
        assertTrue(AppUpdateInstallPolicy.acceptsArchiveVersion(10, 10, 9))
        assertFalse(AppUpdateInstallPolicy.acceptsArchiveVersion(0, 10, 9))
        assertFalse(AppUpdateInstallPolicy.acceptsArchiveVersion(-3, 10, 9))
        assertFalse(AppUpdateInstallPolicy.acceptsArchiveVersion(10, 0, 9))
        assertFalse(AppUpdateInstallPolicy.acceptsArchiveVersion(10, 9, 9))
        assertTrue(AppUpdateInstallPolicy.acceptsArchiveVersion(Int.MAX_VALUE, Int.MAX_VALUE.toLong(), 0))
        assertFalse(AppUpdateInstallPolicy.acceptsArchiveVersion(Int.MAX_VALUE, Long.MAX_VALUE, 0))
        assertTrue(AppUpdateInstallPolicy.isPackageAndSignerTrusted("com.maodouchat", "com.maodouchat", setOf("aa", "bb"), setOf("bb", "aa")))
        assertFalse(AppUpdateInstallPolicy.isPackageAndSignerTrusted("com.maodouchat", "com.maodouchat", setOf("aa", "bb"), emptySet()))
        assertFalse(AppUpdateInstallPolicy.isPackageAndSignerTrusted("com.maodouchat", null, setOf("aa"), setOf("aa")))
        assertFalse(AppUpdateInstallPolicy.isPackageAndSignerTrusted("com.maodouchat", "  ", setOf("aa"), setOf("aa")))
        assertFalse(AppUpdateInstallPolicy.isPackageAndSignerTrusted("   ", "   ", setOf("aa"), setOf("aa")))
        assertFalse(AppUpdateInstallPolicy.isPackageAndSignerTrusted("com.maodouchat", "com.other", setOf("aa"), setOf("aa")))
        assertFalse(AppUpdateInstallPolicy.isPackageAndSignerTrusted("com.maodouchat", "com.maodouchat", setOf("aa"), setOf("AA")))
    }
}
