package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.AttachmentPreparationLease
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
}
