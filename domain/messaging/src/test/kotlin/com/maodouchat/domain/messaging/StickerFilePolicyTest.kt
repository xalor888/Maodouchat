package com.maodouchat.domain.messaging

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StickerFilePolicyTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun sha256_matchesKnownDigest() {
        val file = File(tempDir, "known.bin").apply { writeText("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            StickerFilePolicy.sha256(file),
        )
    }

    @Test
    fun isCurrent_acceptsMatchingHashRegardlessOfCase() {
        val file = File(tempDir, "current.bin").apply { writeText("abc") }
        val digest = StickerFilePolicy.sha256(file)
        assertTrue(StickerFilePolicy.isCurrent(file, digest))
        assertTrue(StickerFilePolicy.isCurrent(file, digest.uppercase()))
    }

    @Test
    fun isCurrent_rejectsWrongHashEmptyFileMissingFileAndDirectory() {
        val wrong = File(tempDir, "wrong.bin").apply { writeText("abc") }
        assertFalse(StickerFilePolicy.isCurrent(wrong, "0".repeat(64)))

        val empty = File(tempDir, "empty.bin").apply { createNewFile() }
        assertFalse(StickerFilePolicy.isCurrent(empty, StickerFilePolicy.sha256(empty)))

        val missing = File(tempDir, "missing.bin")
        assertFalse(StickerFilePolicy.isCurrent(missing, "0".repeat(64)))

        assertFalse(StickerFilePolicy.isCurrent(tempDir, "0".repeat(64)))
    }
}
