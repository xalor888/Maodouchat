package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnSentMediaRestorePolicyTest {

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
}
