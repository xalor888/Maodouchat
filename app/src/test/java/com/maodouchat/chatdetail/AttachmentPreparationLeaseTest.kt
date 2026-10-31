package com.maodouchat.chatdetail

import com.maodouchat.attachment.AttachmentPreparationLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPreparationLeaseTest {
    @Test
    fun `owned resources and saf permission are cleaned exactly once`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordPreparedSource("file://prepared.jpg")
        lease.recordEncryptedPath("/private/encrypted.bin")

        assertTrue(lease.cleanupIfOwned())
        assertFalse(lease.cleanupIfOwned())
        assertEquals(
            listOf(
                "encrypted:/private/encrypted.bin",
                "prepared:file://prepared.jpg",
                "permission:content://original"
            ),
            effects
        )
    }

    @Test
    fun `durable handoff transfers all cleanup responsibility`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += it },
            deletePreparedSource = { effects += it },
            releasePersistablePermission = { effects += it }
        )
        lease.recordEncryptedPath("encrypted")
        lease.recordPreparedSource("prepared")
        lease.handOff()

        assertTrue(lease.isHandedOff())
        assertFalse(lease.cleanupIfOwned())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `blank records are ignored and only the permission is released`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("   ")
        lease.recordPreparedSource("")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("permission:content://original"), effects)
    }

    @Test
    fun `partial recording deletes only what was recorded`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("/private/encrypted.bin")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(
            listOf("encrypted:/private/encrypted.bin", "permission:content://original"),
            effects
        )
    }

    @Test
    fun `blank source uri releases no permission`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = " ",
            deleteEncryptedFile = { effects += it },
            deletePreparedSource = { effects += it },
            releasePersistablePermission = { effects += it }
        )
        lease.recordEncryptedPath("encrypted")
        lease.recordPreparedSource("prepared")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("encrypted", "prepared"), effects)
    }

    @Test
    fun `paths recorded after handoff are never cleaned`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += it },
            deletePreparedSource = { effects += it },
            releasePersistablePermission = { effects += it }
        )
        lease.handOff()
        lease.recordEncryptedPath("encrypted")
        lease.recordPreparedSource("prepared")

        assertFalse(lease.cleanupIfOwned())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `cleanup runs once even when new paths arrive between cleanups`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("first")
        assertTrue(lease.cleanupIfOwned())

        lease.recordEncryptedPath("second")
        assertFalse(lease.cleanupIfOwned())
        assertEquals(listOf("encrypted:first", "permission:content://original"), effects)
    }

    @Test
    fun `recording a path twice keeps only the latest`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("first")
        lease.recordEncryptedPath("second")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("encrypted:second", "permission:content://original"), effects)
    }

    @Test
    fun `blank overwrite clears the earlier record`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("/private/encrypted.bin")
        lease.recordEncryptedPath("   ")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("permission:content://original"), effects)
    }

    @Test
    fun `exception in encrypted deletion propagates and skips remaining cleanup`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = {
                effects += "encrypted:$it"
                throw IllegalStateException("boom")
            },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("/private/encrypted.bin")
        lease.recordPreparedSource("file://prepared.jpg")

        val thrown = assertThrows(IllegalStateException::class.java) {
            lease.cleanupIfOwned()
        }
        assertEquals("boom", thrown.message)
        assertEquals(listOf("encrypted:/private/encrypted.bin"), effects)
        assertFalse(lease.cleanupIfOwned())
    }

    @Test
    fun `concurrent cleanup runs exactly once`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("/private/encrypted.bin")
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val threads = (1..8).map { Thread { results += lease.cleanupIfOwned() } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(1, results.count { it })
        assertEquals(7, results.count { !it })
        assertEquals(listOf("encrypted:/private/encrypted.bin", "permission:content://original"), effects)
    }

    @Test
    fun `handoff is idempotent`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += it },
            deletePreparedSource = { effects += it },
            releasePersistablePermission = { effects += it }
        )
        lease.recordEncryptedPath("encrypted")
        lease.handOff()
        lease.handOff()

        assertTrue(lease.isHandedOff())
        assertFalse(lease.cleanupIfOwned())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `prepared source recorded twice keeps only the latest`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordPreparedSource("first")
        lease.recordPreparedSource("second")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("prepared:second", "permission:content://original"), effects)
    }

    @Test
    fun `blank prepared source overwrite clears the earlier record`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordPreparedSource("file://prepared.jpg")
        lease.recordPreparedSource("   ")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("permission:content://original"), effects)
    }

    @Test
    fun `permission release failure propagates and the lease stays cleaned`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { throw IllegalStateException("perm") }
        )
        lease.recordEncryptedPath("encrypted")

        val thrown = assertThrows(IllegalStateException::class.java) {
            lease.cleanupIfOwned()
        }
        assertEquals("perm", thrown.message)
        assertEquals(listOf("encrypted:encrypted"), effects)
        assertFalse(lease.cleanupIfOwned())
    }

    @Test
    fun `handoff after cleanup changes nothing`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += it },
            deletePreparedSource = { effects += it },
            releasePersistablePermission = { effects += it }
        )
        lease.recordEncryptedPath("encrypted")

        assertTrue(lease.cleanupIfOwned())
        lease.handOff()

        assertTrue(lease.isHandedOff())
        assertFalse(lease.cleanupIfOwned())
        assertEquals(listOf("encrypted", "content://original"), effects)
    }

    @Test
    fun `prepared deletion failure propagates and skips permission release`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = {
                effects += "prepared:$it"
                throw IllegalStateException("prep")
            },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("/private/encrypted.bin")
        lease.recordPreparedSource("file://prepared.jpg")

        val thrown = assertThrows(IllegalStateException::class.java) {
            lease.cleanupIfOwned()
        }
        assertEquals("prep", thrown.message)
        assertEquals(
            listOf("encrypted:/private/encrypted.bin", "prepared:file://prepared.jpg"),
            effects
        )
        assertFalse(lease.cleanupIfOwned())
    }

    @Test
    fun `blank encrypted record then value deletes the value`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("   ")
        lease.recordEncryptedPath("second")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("encrypted:second", "permission:content://original"), effects)
    }

    @Test
    fun `blank prepared record then value deletes the value`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordPreparedSource("")
        lease.recordPreparedSource("file://prepared.jpg")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("prepared:file://prepared.jpg", "permission:content://original"), effects)
    }

    @Test
    fun `fresh lease is not handed off and cleans only the permission`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )

        assertFalse(lease.isHandedOff())
        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("permission:content://original"), effects)
        assertFalse(lease.cleanupIfOwned())
    }

    @Test
    fun `empty lease with blank uri cleans nothing but still marks cleaned`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = " ",
            deleteEncryptedFile = { effects += it },
            deletePreparedSource = { effects += it },
            releasePersistablePermission = { effects += it }
        )

        assertTrue(lease.cleanupIfOwned())
        assertTrue(effects.isEmpty())
        assertFalse(lease.cleanupIfOwned())
    }

    @Test
    fun `encrypted deletion runs before prepared source regardless of record order`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordPreparedSource("file://prepared.jpg")
        lease.recordEncryptedPath("/private/encrypted.bin")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(
            listOf(
                "encrypted:/private/encrypted.bin",
                "prepared:file://prepared.jpg",
                "permission:content://original"
            ),
            effects
        )
    }

    @Test
    fun `tab newline blank encrypted record is ignored`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("\t\n")
        lease.recordPreparedSource("file://prepared.jpg")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("prepared:file://prepared.jpg", "permission:content://original"), effects)
    }

    @Test
    fun `prepared only cleanup releases permission after prepared source`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordPreparedSource("file://prepared.jpg")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("prepared:file://prepared.jpg", "permission:content://original"), effects)
    }

    @Test
    fun `empty source uri releases no permission`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("/private/encrypted.bin")
        lease.recordPreparedSource("file://prepared.jpg")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(
            listOf("encrypted:/private/encrypted.bin", "prepared:file://prepared.jpg"),
            effects
        )
    }

    @Test
    fun `same encrypted value recorded twice deletes once`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("same")
        lease.recordEncryptedPath("same")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("encrypted:same", "permission:content://original"), effects)
    }

    @Test
    fun `prepared deletion failure without encrypted record skips permission release`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = {
                effects += "prepared:$it"
                throw IllegalStateException("prep")
            },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordPreparedSource("file://prepared.jpg")

        val thrown = assertThrows(IllegalStateException::class.java) {
            lease.cleanupIfOwned()
        }
        assertEquals("prep", thrown.message)
        assertEquals(listOf("prepared:file://prepared.jpg"), effects)
        assertFalse(lease.cleanupIfOwned())
    }

    @Test
    fun `both slots replaced by newer values delete the latest pair in order`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("old-encrypted")
        lease.recordPreparedSource("old-prepared")
        lease.recordEncryptedPath("new-encrypted")
        lease.recordPreparedSource("new-prepared")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(
            listOf(
                "encrypted:new-encrypted",
                "prepared:new-prepared",
                "permission:content://original"
            ),
            effects
        )
    }

    @Test
    fun `permission release failure with no records propagates and marks cleaned`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { throw IllegalStateException("perm") }
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            lease.cleanupIfOwned()
        }
        assertEquals("perm", thrown.message)
        assertTrue(effects.isEmpty())
        assertFalse(lease.cleanupIfOwned())
    }

    @Test
    fun `carriage return newline tab uri is blank so only prepared source is deleted`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "\r\n\t",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordPreparedSource("file://prepared.jpg")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("prepared:file://prepared.jpg"), effects)
    }

    @Test
    fun `prepared record arriving after cleanup is never deleted`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        assertTrue(lease.cleanupIfOwned())

        lease.recordPreparedSource("file://prepared.jpg")
        assertFalse(lease.cleanupIfOwned())
        assertEquals(listOf("permission:content://original"), effects)
    }

    @Test
    fun `same value in both slots runs both deletions`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("shared")
        lease.recordPreparedSource("shared")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(
            listOf("encrypted:shared", "prepared:shared", "permission:content://original"),
            effects
        )
    }

    @Test
    fun `encrypted path with surrounding whitespace is kept verbatim`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("  /private/x  ")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(
            listOf(
                "encrypted:  /private/x  ",
                "permission:content://original"
            ),
            effects
        )
    }

    @Test
    fun `prepared source with surrounding whitespace is kept verbatim`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordPreparedSource("  file://p  ")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(
            listOf(
                "prepared:  file://p  ",
                "permission:content://original"
            ),
            effects
        )
    }

    @Test
    fun `original uri with surrounding whitespace is released verbatim`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "  content://original  ",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("permission:  content://original  "), effects)
    }

    @Test
    fun `two leases clean independently`() {
        val effectsA = mutableListOf<String>()
        val effectsB = mutableListOf<String>()
        val leaseA = AttachmentPreparationLease(
            originalSourceUri = "content://a",
            deleteEncryptedFile = { effectsA += "encrypted:$it" },
            deletePreparedSource = { effectsA += "prepared:$it" },
            releasePersistablePermission = { effectsA += "permission:$it" }
        )
        val leaseB = AttachmentPreparationLease(
            originalSourceUri = "content://b",
            deleteEncryptedFile = { effectsB += "encrypted:$it" },
            deletePreparedSource = { effectsB += "prepared:$it" },
            releasePersistablePermission = { effectsB += "permission:$it" }
        )
        leaseA.recordEncryptedPath("a-enc")
        leaseB.recordEncryptedPath("b-enc")

        assertTrue(leaseA.cleanupIfOwned())
        assertEquals(listOf("encrypted:a-enc", "permission:content://a"), effectsA)
        assertTrue(effectsB.isEmpty())

        assertTrue(leaseB.cleanupIfOwned())
        assertEquals(listOf("encrypted:b-enc", "permission:content://b"), effectsB)
    }

    @Test
    fun `cleanup without handoff leaves handed-off flag false`() {
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { _ -> },
            deletePreparedSource = { _ -> },
            releasePersistablePermission = { _ -> }
        )
        lease.recordEncryptedPath("encrypted")

        assertTrue(lease.cleanupIfOwned())
        assertFalse(lease.isHandedOff())
    }

    @Test
    fun `handoff with no records refuses cleanup and keeps the flag`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += it },
            deletePreparedSource = { effects += it },
            releasePersistablePermission = { effects += it }
        )
        lease.handOff()

        assertTrue(lease.isHandedOff())
        assertFalse(lease.cleanupIfOwned())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `encrypted only cleanup with blank uri deletes just the encrypted file`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("/private/encrypted.bin")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("encrypted:/private/encrypted.bin"), effects)
    }

    @Test
    fun `prepared value blanked then re-recorded deletes the latest value`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordPreparedSource("file://old.jpg")
        lease.recordPreparedSource("   ")
        lease.recordPreparedSource("file://new.jpg")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("prepared:file://new.jpg", "permission:content://original"), effects)
    }

    @Test
    fun `encrypted value blanked then re-recorded deletes the latest value`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("old")
        lease.recordEncryptedPath("")
        lease.recordEncryptedPath("new")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("encrypted:new", "permission:content://original"), effects)
    }

    @Test
    fun `record arriving after failed cleanup is never deleted`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = {
                effects += "encrypted:$it"
                throw IllegalStateException("boom")
            },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("first")

        val thrown = assertThrows(IllegalStateException::class.java) {
            lease.cleanupIfOwned()
        }
        assertEquals("boom", thrown.message)

        lease.recordPreparedSource("file://late.jpg")
        assertFalse(lease.cleanupIfOwned())
        assertEquals(listOf("encrypted:first"), effects)
    }


    @Test
    fun `encrypted record arriving after cleanup is never deleted`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        assertTrue(lease.cleanupIfOwned())

        lease.recordEncryptedPath("/private/encrypted.bin")
        assertFalse(lease.cleanupIfOwned())
        assertEquals(listOf("permission:content://original"), effects)
    }

    @Test
    fun `handoff before any record then cleanup returns false and releases nothing`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.handOff()

        assertTrue(lease.isHandedOff())
        assertFalse(lease.cleanupIfOwned())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `cleanup after a throwing encrypted deletion then handoff still returns false`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = {
                effects += "encrypted:$it"
                throw IllegalStateException("boom")
            },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordEncryptedPath("/private/encrypted.bin")

        val thrown = assertThrows(IllegalStateException::class.java) {
            lease.cleanupIfOwned()
        }
        assertEquals("boom", thrown.message)

        lease.handOff()
        assertTrue(lease.isHandedOff())
        assertFalse(lease.cleanupIfOwned())
        assertEquals(listOf("encrypted:/private/encrypted.bin"), effects)
    }

    @Test
    fun `prepared only with blank original uri deletes prepared and releases nothing`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = " ",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.recordPreparedSource("file://prepared.jpg")

        assertTrue(lease.cleanupIfOwned())
        assertEquals(listOf("prepared:file://prepared.jpg"), effects)
    }

    @Test
    fun `two cleanups after handoff both return false`() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "encrypted:$it" },
            deletePreparedSource = { effects += "prepared:$it" },
            releasePersistablePermission = { effects += "permission:$it" }
        )
        lease.handOff()

        assertFalse(lease.cleanupIfOwned())
        assertFalse(lease.cleanupIfOwned())
        assertTrue(effects.isEmpty())
    }
}
