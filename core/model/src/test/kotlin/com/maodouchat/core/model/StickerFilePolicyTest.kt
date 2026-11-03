package com.maodouchat.core.model

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
    fun isCurrent_acceptsMatchingHashRegardlessOfCase() {
        val file = temporaryFolder.newFile("current.bin").apply { writeText("abc") }
        val digest = StickerFilePolicy.sha256(file)
        assertTrue(StickerFilePolicy.isCurrent(file, digest))
        assertTrue(StickerFilePolicy.isCurrent(file, digest.uppercase()))
    }

    @Test
    fun isCurrent_rejectsWrongHashEmptyFileMissingFileAndDirectory() {
        val wrong = temporaryFolder.newFile("wrong.bin").apply { writeText("abc") }
        assertFalse(StickerFilePolicy.isCurrent(wrong, "0".repeat(64)))

        val empty = temporaryFolder.newFile("empty.bin")
        assertFalse(StickerFilePolicy.isCurrent(empty, StickerFilePolicy.sha256(empty)))

        val missing = File(temporaryFolder.root, "missing.bin")
        assertFalse(StickerFilePolicy.isCurrent(missing, "0".repeat(64)))

        assertFalse(StickerFilePolicy.isCurrent(temporaryFolder.root, "0".repeat(64)))
    }
}
