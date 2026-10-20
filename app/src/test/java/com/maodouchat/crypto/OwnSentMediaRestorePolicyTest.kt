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

    @Test
    fun secretChatHydrateNeverEvenWhenOwnSent() {
        assertFalse(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = true,
                type = MessageType.IMAGE,
                attachmentId = "att_secret_img",
                localUriReadable = false,
                senderIsCurrentUser = true,
                autoDownload = true
            )
        )
    }

    @Test
    fun incomingImageWithoutAutoDownloadNeverHydrates() {
        assertFalse(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.IMAGE,
                attachmentId = "att_peer_img",
                localUriReadable = false,
                senderIsCurrentUser = false,
                autoDownload = false
            )
        )
    }

    @Test
    fun blankAttachmentIdNeverRestores() {
        assertFalse(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false,
                type = MessageType.IMAGE,
                attachmentId = "   ",
                localUriReadable = false
            )
        )
    }

    @Test
    fun nonMediaTypesNeverRestore() {
        for (type in listOf(
            MessageType.TEXT, MessageType.MARKDOWN, MessageType.STICKER,
            MessageType.LOCATION, MessageType.NUDGE, MessageType.SK_DIST,
            MessageType.SYSTEM, MessageType.REVOKED
        )) {
            assertFalse(
                OwnSentMediaRestorePolicy.shouldRestore(
                    isSecretChat = false,
                    type = type,
                    attachmentId = "att_x",
                    localUriReadable = false
                )
            )
        }
    }

    @Test
    fun videoIncomingWithAutoDownloadHydratesButStickerNever() {
        assertTrue(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.VIDEO,
                attachmentId = "att_peer_vid",
                localUriReadable = false,
                senderIsCurrentUser = false,
                autoDownload = true
            )
        )
        assertFalse(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.STICKER,
                attachmentId = "att_peer_sticker",
                localUriReadable = false,
                senderIsCurrentUser = true,
                autoDownload = true
            )
        )
    }

    @Test
    fun voiceOwnSentHydratesWithoutAutoDownload() {
        assertTrue(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.VOICE,
                attachmentId = "att_own_voice",
                localUriReadable = false,
                senderIsCurrentUser = true,
                autoDownload = false
            )
        )
        assertFalse(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.VOICE,
                attachmentId = "att_peer_voice",
                localUriReadable = false,
                senderIsCurrentUser = false,
                autoDownload = false
            )
        )
    }

    @Test
    fun gifHydrateFollowsOwnSentRule() {
        assertTrue(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.GIF,
                attachmentId = "att_own_gif",
                localUriReadable = false,
                senderIsCurrentUser = true,
                autoDownload = false
            )
        )
        assertTrue(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.GIF,
                attachmentId = "att_peer_gif",
                localUriReadable = false,
                senderIsCurrentUser = false,
                autoDownload = true
            )
        )
        assertFalse(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.GIF,
                attachmentId = "att_peer_gif",
                localUriReadable = false,
                senderIsCurrentUser = false,
                autoDownload = false
            )
        )
    }

    @Test
    fun whitespaceAttachmentIdNeverHydrates() {
        assertFalse(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.IMAGE,
                attachmentId = "   ",
                localUriReadable = false,
                senderIsCurrentUser = true,
                autoDownload = true
            )
        )
    }

    @Test
    fun wipeAndMetaDecisionsAgreeOnLogout() {
        assertFalse(OwnSentMediaRestorePolicy.wipeOrdinaryMediaOnLogout(destroyEncryptedDatabase = false))
        assertTrue(OwnSentMediaRestorePolicy.keepAttachmentMetaOnLogout(destroyEncryptedDatabase = false))
        assertTrue(OwnSentMediaRestorePolicy.wipeOrdinaryMediaOnLogout(destroyEncryptedDatabase = true))
        assertFalse(OwnSentMediaRestorePolicy.keepAttachmentMetaOnLogout(destroyEncryptedDatabase = true))
    }

    @Test
    fun nonRestorableTypesNeverHydrateEvenWithAutoDownload() {
        for (type in listOf(
            MessageType.MARKDOWN, MessageType.LOCATION, MessageType.NUDGE,
            MessageType.SK_DIST, MessageType.SYSTEM, MessageType.REVOKED
        )) {
            assertFalse(
                OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                    isSecretChat = false,
                    type = type,
                    attachmentId = "att_x",
                    localUriReadable = false,
                    senderIsCurrentUser = true,
                    autoDownload = true
                )
            )
        }
    }

    @Test
    fun textOwnSentNeverHydratesEvenWithAutoDownload() {
        assertFalse(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.TEXT,
                attachmentId = "att_own_text",
                localUriReadable = false,
                senderIsCurrentUser = true,
                autoDownload = true
            )
        )
    }

    @Test
    fun incomingFileWithAutoDownloadHydrates() {
        assertTrue(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.FILE,
                attachmentId = "att_peer_file",
                localUriReadable = false,
                senderIsCurrentUser = false,
                autoDownload = true
            )
        )
    }

    @Test
    fun ownVideoHydratesWithoutAutoDownload() {
        assertTrue(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.VIDEO,
                attachmentId = "att_own_video",
                localUriReadable = false,
                senderIsCurrentUser = true,
                autoDownload = false
            )
        )
    }

    @Test
    fun attachmentIdPaddingVariants() {
        // 前后填充普通空格仍是非空 id：可恢复。
        assertTrue(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false,
                type = MessageType.IMAGE,
                attachmentId = "  att_padded  ",
                localUriReadable = false
            )
        )
        // Kotlin 的 isBlank 按 Char.isWhitespace 判定，不间断空格算空白：
        // 纯不间断空格的 id 视为空，不可恢复。
        assertFalse(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false,
                type = MessageType.IMAGE,
                attachmentId = "\u00a0",
                localUriReadable = false
            )
        )
    }

    @Test
    fun restoreDecisionIsDeterministic() {
        repeat(3) {
            assertTrue(
                OwnSentMediaRestorePolicy.shouldRestore(
                    isSecretChat = false,
                    type = MessageType.IMAGE,
                    attachmentId = "att_own",
                    localUriReadable = false
                )
            )
            assertFalse(
                OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                    isSecretChat = false,
                    type = MessageType.TEXT,
                    attachmentId = "att_x",
                    localUriReadable = false,
                    senderIsCurrentUser = true,
                    autoDownload = true
                )
            )
        }
    }

    @Test
    fun wipeAndKeepMetaAreExactInverses() {
        assertFalse(OwnSentMediaRestorePolicy.wipeOrdinaryMediaOnLogout(destroyEncryptedDatabase = false))
        assertTrue(OwnSentMediaRestorePolicy.keepAttachmentMetaOnLogout(destroyEncryptedDatabase = false))
        assertTrue(OwnSentMediaRestorePolicy.wipeOrdinaryMediaOnLogout(destroyEncryptedDatabase = true))
        assertFalse(OwnSentMediaRestorePolicy.keepAttachmentMetaOnLogout(destroyEncryptedDatabase = true))
    }

    @Test
    fun hydrateIncomingVoiceFollowsAutoDownloadFlag() {
        assertTrue(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.VOICE,
                attachmentId = "att_peer_voice",
                localUriReadable = false,
                senderIsCurrentUser = false,
                autoDownload = true
            )
        )
        assertFalse(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = false,
                type = MessageType.VOICE,
                attachmentId = "att_peer_voice",
                localUriReadable = false,
                senderIsCurrentUser = false,
                autoDownload = false
            )
        )
    }

    @Test
    fun hydrateSecretChatIncomingWithAutoDownloadStillNever() {
        assertFalse(
            OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                isSecretChat = true,
                type = MessageType.VIDEO,
                attachmentId = "att_secret_vid",
                localUriReadable = false,
                senderIsCurrentUser = false,
                autoDownload = true
            )
        )
    }

    @Test
    fun richNonMediaTypesNeverRestore() {
        for (type in listOf(
            MessageType.MARKDOWN, MessageType.LOCATION, MessageType.NUDGE,
            MessageType.SYSTEM, MessageType.REVOKED, MessageType.SK_DIST
        )) {
            assertFalse(
                OwnSentMediaRestorePolicy.shouldRestore(
                    isSecretChat = false,
                    type = type,
                    attachmentId = "att_meta",
                    localUriReadable = false
                )
            )
        }
    }

    @Test
    fun paddedAttachmentIdStillRestores() {
        assertTrue(
            OwnSentMediaRestorePolicy.shouldRestore(
                isSecretChat = false,
                type = MessageType.IMAGE,
                attachmentId = " att_own ",
                localUriReadable = false
            )
        )
    }
}
