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
}
