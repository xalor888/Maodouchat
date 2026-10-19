package com.maodouchat.chatdetail

import com.maodouchat.attachment.AttachmentPreparationLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
