package com.maodouchat.slim

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
}
