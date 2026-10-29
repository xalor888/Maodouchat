package com.maodouchat.slim

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StickerFilePolicyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sha256_matchesKnownDigest() {
        val file = temporaryFolder.newFile("known.bin").apply { writeText("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            StickerFilePolicy.sha256(file),
        )
    }

    @Test
    fun isCurrent_acceptsMatchingHash() {
        val file = temporaryFolder.newFile("current.bin").apply { writeText("abc") }
        assertTrue(StickerFilePolicy.isCurrent(file, StickerFilePolicy.sha256(file)))
        assertTrue(
            StickerFilePolicy.isCurrent(
                file,
                "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            ),
        )
    }

    @Test
    fun isCurrent_rejectsWrongHashOrEmptyFile() {
        val wrong = temporaryFolder.newFile("wrong.bin").apply { writeText("abc") }
        assertFalse(StickerFilePolicy.isCurrent(wrong, "0".repeat(64)))

        val empty = temporaryFolder.newFile("empty.bin")
        assertFalse(StickerFilePolicy.isCurrent(empty, StickerFilePolicy.sha256(empty)))
    }

    @Test
    fun isCurrent_rejectsMissingFile() {
        val missing = File(temporaryFolder.root, "missing.bin")
        assertFalse(StickerFilePolicy.isCurrent(missing, "0".repeat(64)))
    }

    @Test
    fun isCurrent_rejectsDirectory() {
        assertFalse(StickerFilePolicy.isCurrent(temporaryFolder.root, "0".repeat(64)))
    }

    @Test
    fun sha256_isStableAndResetAcrossFiles() {
        val first = temporaryFolder.newFile("a.bin").apply { writeText("abc") }
        val second = temporaryFolder.newFile("b.bin").apply { writeText("abd") }
        val firstDigest = StickerFilePolicy.sha256(first)
        val secondDigest = StickerFilePolicy.sha256(second)
        assertEquals(firstDigest, StickerFilePolicy.sha256(first))
        assertTrue(firstDigest != secondDigest)
    }

    @Test
    fun sha256_emptyFileMatchesKnownDigest() {
        val empty = temporaryFolder.newFile("empty2.bin")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            StickerFilePolicy.sha256(empty),
        )
    }

    @Test
    fun isCurrent_rejectsExpectedHashWithWhitespace() {
        val file = temporaryFolder.newFile("ws.bin").apply { writeText("abc") }
        val digest = StickerFilePolicy.sha256(file)
        assertFalse(StickerFilePolicy.isCurrent(file, " $digest"))
        assertFalse(StickerFilePolicy.isCurrent(file, "$digest "))
    }

    @Test
    fun sha256_isLowercaseHex64() {
        val file = temporaryFolder.newFile("hex.bin").apply { writeText("abc") }
        val digest = StickerFilePolicy.sha256(file)
        assertEquals(64, digest.length)
        assertEquals(digest, digest.lowercase())
    }

    @Test
    fun sha256_multiBlockContentIsStableAndDistinct() {
        // 100KB 超过 64KB 读缓冲，走多轮 update 循环
        val big = temporaryFolder.newFile("big.bin").apply { writeText("a".repeat(100_000)) }
        val digest = StickerFilePolicy.sha256(big)
        assertEquals(digest, StickerFilePolicy.sha256(big))
        val small = temporaryFolder.newFile("small.bin").apply { writeText("abc") }
        assertTrue(digest != StickerFilePolicy.sha256(small))
        assertTrue(digest != StickerFilePolicy.sha256(temporaryFolder.newFile("empty3.bin")))
    }

    @Test
    fun isCurrent_acceptsUppercaseDigestForLargeFile() {
        val big = temporaryFolder.newFile("big2.bin").apply { writeText("a".repeat(100_000)) }
        assertTrue(StickerFilePolicy.isCurrent(big, StickerFilePolicy.sha256(big).uppercase()))
    }

    @Test
    fun isCurrent_rejectsShortAndNonHexExpected() {
        val file = temporaryFolder.newFile("short.bin").apply { writeText("abc") }
        assertFalse(StickerFilePolicy.isCurrent(file, "abc"))
        assertFalse(StickerFilePolicy.isCurrent(file, "g".repeat(64)))
        assertFalse(StickerFilePolicy.isCurrent(file, "0".repeat(63)))
    }

    @Test
    fun isCurrent_rejectsEmbeddedWhitespaceAndOtherFileDigest() {
        val file = temporaryFolder.newFile("emb.bin").apply { writeText("abc") }
        val digest = StickerFilePolicy.sha256(file)
        val spaced = digest.substring(0, 32) + " " + digest.substring(32)
        assertFalse(StickerFilePolicy.isCurrent(file, spaced))
        val other = temporaryFolder.newFile("other.bin").apply { writeText("abd") }
        assertFalse(StickerFilePolicy.isCurrent(file, StickerFilePolicy.sha256(other)))
    }


    @Test
    fun sha256_singleByteMatchesKnownDigest() {
        val file = temporaryFolder.newFile("one.bin").apply { writeText("a") }
        assertEquals(
            "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
            StickerFilePolicy.sha256(file),
        )
    }

    @Test
    fun isCurrent_acceptsMixedCaseDigest() {
        val file = temporaryFolder.newFile("mixed.bin").apply { writeText("abc") }
        val digest = StickerFilePolicy.sha256(file)
        val mixed = digest.mapIndexed { i, c ->
            if (i % 2 == 0) c.uppercaseChar() else c
        }.joinToString("")
        assertTrue(StickerFilePolicy.isCurrent(file, mixed))
    }

    @Test
    fun sha256_twoFilesSameContentShareDigest() {
        val first = temporaryFolder.newFile("same1.bin").apply { writeText("abc") }
        val second = temporaryFolder.newFile("same2.bin").apply { writeText("abc") }
        assertEquals(StickerFilePolicy.sha256(first), StickerFilePolicy.sha256(second))
    }

    @Test
    fun isCurrent_rejectsEmptyAndTrailingNewlineExpected() {
        val file = temporaryFolder.newFile("nl.bin").apply { writeText("abc") }
        val digest = StickerFilePolicy.sha256(file)
        assertFalse(StickerFilePolicy.isCurrent(file, ""))
        assertFalse(StickerFilePolicy.isCurrent(file, "$digest\n"))
    }

    @Test
    fun sha256_contentWithTrailingNewlineMatchesKnownDigest() {
        val file = temporaryFolder.newFile("nl2.bin").apply { writeText("abc\n") }
        assertEquals(
            "edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb",
            StickerFilePolicy.sha256(file),
        )
    }
}
