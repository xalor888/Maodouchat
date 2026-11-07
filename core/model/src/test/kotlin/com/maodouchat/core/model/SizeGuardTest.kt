package com.maodouchat.core.model

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeGuardTest {

    private fun tempDir(): File = Files.createTempDirectory("sizeguard-test").toFile()

    private fun writeApk(dir: File, name: String, entries: Map<String, ByteArray>): File {
        val apk = File(dir, name)
        ZipOutputStream(FileOutputStream(apk)).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return apk
    }

    @Test
    fun `apk candidates prefers known release name then newest apk`() {
        val dir = tempDir()
        val old = writeApk(dir, "other.apk", mapOf("classes.dex" to ByteArray(10)))
        old.setLastModified(1_000L)
        val release = writeApk(dir, "app-release.apk", mapOf("classes.dex" to ByteArray(20)))
        release.setLastModified(500L)
        File(dir, "notes.txt").writeText("not an apk")
        assertEquals(listOf(release, old), SizeGuard.apkCandidates(dir))
    }

    @Test
    fun `apk candidates empty without apk`() {
        assertTrue(SizeGuard.apkCandidates(tempDir()).isEmpty())
    }

    @Test
    fun `analyze apk buckets dex so res assets meta other`() {
        val dir = tempDir()
        val apk = writeApk(
            dir, "app-release.apk", mapOf(
                "classes.dex" to ByteArray(100),
                "classes2.dex" to ByteArray(50),
                "lib/arm64-v8a/libx.so" to ByteArray(40),
                "res/layout/main.xml" to ByteArray(30),
                "assets/data.txt" to ByteArray(20),
                "META-INF/MANIFEST.MF" to ByteArray(10),
                "other.bin" to ByteArray(5),
            )
        )
        val report = SizeGuard.analyzeApk(apk)
        assertEquals(apk.length(), report.totalBytes)
        val byLabel = report.buckets.associate { it.label to it.bytes }
        assertEquals(150L, byLabel["dex"])
        assertEquals(40L, byLabel["native(.so)"])
        assertEquals(30L, byLabel["resources(res/)"])
        assertEquals(20L, byLabel["assets"])
        assertEquals(10L, byLabel["META-INF"])
        assertEquals(5L, byLabel["other"])
        assertEquals(listOf("lib/arm64-v8a/libx.so" to 40L), report.largestSo)
    }

    @Test
    fun `baseline check and report text`() {
        val report = ApkReport(File("a.apk"), 1000L, listOf(SizeBucket("dex", 250L)), emptyList())
        assertTrue(SizeGuard.isWithinBaseline(report, 1000L))
        assertFalse(SizeGuard.isWithinBaseline(report, 999L))
        val text = SizeGuard.formatReport(report)
        assertTrue(text.contains("dex"))
        assertTrue(text.contains("1000"))
    }
}
