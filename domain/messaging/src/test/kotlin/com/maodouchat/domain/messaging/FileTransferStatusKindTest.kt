package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FileTransferStatusKindTest {

    @Test
    fun `plain states map one to one`() {
        assertEquals(FileTransferStatusKind.PREPARING, fileTransferStatusKind("PREPARING", null))
        assertEquals(FileTransferStatusKind.QUEUED, fileTransferStatusKind("QUEUED", null))
        assertEquals(FileTransferStatusKind.UPLOADING, fileTransferStatusKind("UPLOADING", null))
        assertEquals(FileTransferStatusKind.READY, fileTransferStatusKind("READY", null))
        assertEquals(FileTransferStatusKind.SENDING, fileTransferStatusKind("SENDING", null))
        assertEquals(FileTransferStatusKind.PAUSED, fileTransferStatusKind("PAUSED", null))
    }

    @Test
    fun `failed with source missing error`() {
        assertEquals(FileTransferStatusKind.SOURCE_MISSING,
            fileTransferStatusKind("FAILED", "SOURCE_MISSING"))
    }

    @Test
    fun `failed with network prefix`() {
        assertEquals(FileTransferStatusKind.NETWORK_FAILED,
            fileTransferStatusKind("FAILED", "NETWORK_UNREACHABLE"))
    }

    @Test
    fun `failed with timeout prefix`() {
        assertEquals(FileTransferStatusKind.TIMEOUT,
            fileTransferStatusKind("FAILED", "TIMEOUT_CONNECT"))
    }

    @Test
    fun `failed with send prefix`() {
        assertEquals(FileTransferStatusKind.SEND_FAILED,
            fileTransferStatusKind("FAILED", "SEND_REJECTED"))
    }

    @Test
    fun `failed with other error falls back to failed`() {
        assertEquals(FileTransferStatusKind.FAILED,
            fileTransferStatusKind("FAILED", "UNKNOWN"))
    }

    @Test
    fun `failed with null error falls back to failed`() {
        assertEquals(FileTransferStatusKind.FAILED,
            fileTransferStatusKind("FAILED", null))
    }

    @Test
    fun `unknown state falls back to queued like the old else branch`() {
        assertEquals(FileTransferStatusKind.QUEUED,
            fileTransferStatusKind("SOMETHING_ELSE", null))
    }
}
