package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallPureModelsTest {

    @Test
    fun joinGroupInviteResultVariants() {
        val joined = JoinGroupInviteResult.Joined(ChatDto(id = "c1", groupName = "g"))
        assertEquals("c1", (joined as JoinGroupInviteResult.Joined).chat.id)
        assertTrue(JoinGroupInviteResult.InvalidCode is JoinGroupInviteResult)
        val failed = JoinGroupInviteResult.Failed(Feedback(Kind.NETWORK))
        assertEquals(Kind.NETWORK, (failed as JoinGroupInviteResult.Failed).feedback.kind)
        assertTrue(JoinGroupInviteResult.Aborted is JoinGroupInviteResult)
    }

    @Test
    fun ownSentMediaRestoreSecretExcluded() {
        assertFalse(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = true, type = MessageType.IMAGE,
                attachmentId = "a1", localUriReadable = false
            )
        )
        assertTrue(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false, type = MessageType.IMAGE,
                attachmentId = "a1", localUriReadable = false
            )
        )
        assertFalse(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false, type = MessageType.IMAGE,
                attachmentId = "a1", localUriReadable = true
            )
        )
    }

    @Test
    fun ownSentMediaRestoreHydrateNeedsAutoDownloadOrOwnSent() {
        assertTrue(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false, type = MessageType.VIDEO,
                attachmentId = "a1", localUriReadable = false,
                senderIsCurrentUser = false, autoDownload = true
            )
        )
        assertFalse(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false, type = MessageType.VIDEO,
                attachmentId = "a1", localUriReadable = false,
                senderIsCurrentUser = false, autoDownload = false
            )
        )
        assertTrue(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false, type = MessageType.VIDEO,
                attachmentId = "a1", localUriReadable = false,
                senderIsCurrentUser = true, autoDownload = false
            )
        )
    }

    @Test
    fun normalizeAttachmentMetadataImageToJpg() {
        val out = normalizeAttachmentMetadata(
            MessageType.IMAGE,
            LocalFileMetadata("pic.PNG", "image/png", 100L)
        )
        assertEquals("pic.jpg", out.fileName)
        assertEquals("image/jpeg", out.mimeType)
        assertEquals(100L, out.sizeBytes)
    }

    @Test
    fun normalizeVideoMetadataDeclaredMimeWins() {
        val out = normalizeVideoMetadata("clip.mov", "video/quicktime", 2048L)
        assertEquals("clip.mov", out.fileName)
        assertEquals("video/quicktime", out.mimeType)
        assertEquals(2048L, out.sizeBytes)
    }
}
