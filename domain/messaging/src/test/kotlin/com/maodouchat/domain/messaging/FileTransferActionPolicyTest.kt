package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileTransferActionPolicyTest {

    @Test
    fun `queued and uploading offer pause`() {
        assertEquals(FileTransferPrimaryAction.PAUSE, fileTransferPrimaryAction("QUEUED"))
        assertEquals(FileTransferPrimaryAction.PAUSE, fileTransferPrimaryAction("UPLOADING"))
    }

    @Test
    fun `paused offers resume and failed offers retry`() {
        assertEquals(FileTransferPrimaryAction.RESUME, fileTransferPrimaryAction("PAUSED"))
        assertEquals(FileTransferPrimaryAction.RETRY, fileTransferPrimaryAction("FAILED"))
    }

    @Test
    fun `preparing ready sending unknown and null show progress`() {
        assertEquals(FileTransferPrimaryAction.PROGRESS, fileTransferPrimaryAction("PREPARING"))
        assertEquals(FileTransferPrimaryAction.PROGRESS, fileTransferPrimaryAction("READY"))
        assertEquals(FileTransferPrimaryAction.PROGRESS, fileTransferPrimaryAction("SENDING"))
        assertEquals(FileTransferPrimaryAction.PROGRESS, fileTransferPrimaryAction("DONE"))
        assertEquals(FileTransferPrimaryAction.PROGRESS, fileTransferPrimaryAction(null))
    }

    @Test
    fun `cancel is available for the five active states`() {
        for (state in listOf("PREPARING", "QUEUED", "UPLOADING", "PAUSED", "FAILED")) {
            assertTrue(fileTransferCanCancel(state), state)
        }
    }

    @Test
    fun `cancel is unavailable for settled and unknown states`() {
        assertFalse(fileTransferCanCancel("READY"))
        assertFalse(fileTransferCanCancel("SENDING"))
        assertFalse(fileTransferCanCancel("DONE"))
        assertFalse(fileTransferCanCancel(null))
    }
}
