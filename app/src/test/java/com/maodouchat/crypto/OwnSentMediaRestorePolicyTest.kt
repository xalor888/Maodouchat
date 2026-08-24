package com.maodouchat.crypto

import com.maodouchat.data.model.MessageType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnSentMediaRestorePolicyTest {

    @Test
    fun restoresOwnSentWhenLocalFileGoneButMetaRemains() {
        assertTrue(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false,
                type = MessageType.IMAGE,
                attachmentId = "att_own",
                localUriReadable = false
            )
        )
        assertTrue(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false,
                type = MessageType.FILE,
                attachmentId = "att_file",
                localUriReadable = false
            )
        )
        assertTrue(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false,
                type = MessageType.VOICE,
                attachmentId = "att_voice",
                localUriReadable = false
            )
        )
    }

    @Test
    fun skipsWhenAlreadyReadableOrMissingReference() {
        assertFalse(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false,
                type = MessageType.IMAGE,
                attachmentId = "att_own",
                localUriReadable = true
            )
        )
        assertFalse(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false,
                type = MessageType.IMAGE,
                attachmentId = null,
                localUriReadable = false
            )
        )
        assertFalse(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false,
                type = MessageType.TEXT,
                attachmentId = "att_own",
                localUriReadable = false
            )
        )
    }

    @Test
    fun secretChatNeverRestoresFromCloud() {
        assertFalse(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = true,
                type = MessageType.IMAGE,
                attachmentId = "att_secret",
                localUriReadable = false
            )
        )
    }

    @Test
    fun ordinaryMediaSurvivesSoftLogout() {
        assertFalse(OwnSentMediaRestorePolicy.wipeOrdinaryMediaOnLogout(destroyEncryptedDatabase = false))
        assertTrue(OwnSentMediaRestorePolicy.wipeOrdinaryMediaOnLogout(destroyEncryptedDatabase = true))
    }

    @Test
    fun gifAndVideoRestoreWhenLocalUriGone() {
        assertTrue(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false,
                type = MessageType.GIF,
                attachmentId = "att_gif",
                localUriReadable = false
            )
        )
        assertTrue(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false,
                type = MessageType.VIDEO,
                attachmentId = "att_vid",
                localUriReadable = false
            )
        )
    }

    @Test
    fun keepStoreLogoutKeepsOwnSentImageMetaAndMayRedownload() {
        assertFalse(OwnSentMediaRestorePolicy.wipeOrdinaryMediaOnLogout(destroyEncryptedDatabase = false))
        assertTrue(OwnSentMediaRestorePolicy.keepAttachmentMetaOnLogout(destroyEncryptedDatabase = false))
        assertFalse(OwnSentMediaRestorePolicy.keepAttachmentMetaOnLogout(destroyEncryptedDatabase = true))

        // Cache still on disk after keep-store: do not re-download.
        assertFalse(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.IMAGE,
                attachmentId = "att_own_img",
                localUriReadable = true,
                senderIsCurrentUser = true,
                autoDownload = true
            )
        )
        // Cache gone, Room still has attachmentId/keys: hydrate/re-download own-sent IMAGE.
        assertTrue(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.IMAGE,
                attachmentId = "att_own_img",
                localUriReadable = false,
                senderIsCurrentUser = true,
                autoDownload = true
            )
        )
        // Own-sent FILE is not auto-download but still hydrates because it is ours.
        assertTrue(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.FILE,
                attachmentId = "att_own_file",
                localUriReadable = false,
                senderIsCurrentUser = true,
                autoDownload = false
            )
        )
        // Incoming FILE without auto-download does not hydrate.
        assertFalse(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.FILE,
                attachmentId = "att_peer_file",
                localUriReadable = false,
                senderIsCurrentUser = false,
                autoDownload = false
            )
        )
        // Incoming IMAGE auto-downloads when meta remains.
        assertTrue(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.IMAGE,
                attachmentId = "att_peer_img",
                localUriReadable = false,
                senderIsCurrentUser = false,
                autoDownload = true
            )
        )
        // Meta keys gone (true wipe / incomplete row): cannot re-download.
        assertFalse(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.IMAGE,
                attachmentId = "",
                localUriReadable = false,
                senderIsCurrentUser = true,
                autoDownload = true
            )
        )
    }
}
