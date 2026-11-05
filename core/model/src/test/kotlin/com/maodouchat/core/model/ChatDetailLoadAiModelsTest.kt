package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDetailLoadAiModelsTest {

    @Test
    fun `classified error defaults persisted code to code`() {
        val error = AiClassifiedError(code = "RATE_LIMITED")
        assertEquals(null, error.retryAfterSeconds)
        assertEquals("RATE_LIMITED", error.persistedCode)
    }

    @Test
    fun `classified error carries retry timing`() {
        val error = AiClassifiedError(code = "QUOTA_EXCEEDED", retryAfterSeconds = 30L, persistedCode = "QUOTA_EXCEEDED:30")
        assertEquals(30L, error.retryAfterSeconds)
        assertEquals("QUOTA_EXCEEDED:30", error.persistedCode)
    }

    @Test
    fun `load effects cover every subtype`() {
        val effects: List<ChatLoadEffect> = listOf(
            ChatLoadEffect.OccupySessionCipher("c1", "u2"),
            ChatLoadEffect.LoadGroupCandidates,
            ChatLoadEffect.RefreshBotCommands("c1"),
            ChatLoadEffect.RefreshBlockState("u2"),
            ChatLoadEffect.RefreshScheduledMessages,
            ChatLoadEffect.RefreshIdentitySafety("u2")
        )
        val names = effects.map {
            when (it) {
                is ChatLoadEffect.OccupySessionCipher -> "cipher"
                ChatLoadEffect.LoadGroupCandidates -> "candidates"
                is ChatLoadEffect.RefreshBotCommands -> "bots"
                is ChatLoadEffect.RefreshBlockState -> "block"
                ChatLoadEffect.RefreshScheduledMessages -> "scheduled"
                is ChatLoadEffect.RefreshIdentitySafety -> "identity"
            }
        }
        assertEquals(listOf("cipher", "candidates", "bots", "block", "scheduled", "identity"), names)
    }

    @Test
    fun `message labels carry all twenty strings`() {
        val labels = ChatDetailMessageLabels(
            sensitiveAuthTitle = "auth",
            sensitiveAuthExport = "export",
            sensitiveAuthClearHistory = "clear",
            sensitiveAuthFailed = "failed",
            chatPermissionRecordMsg = "record",
            chatPermissionVoiceCallMsg = "voice",
            chatPermissionVideoCallMsg = "video",
            chatPermissionLocationMsg = "location",
            chatCopiedMsg = "copied",
            chatAiImageResultTitle = "image",
            chatAiFileResultTitle = "file",
            chatGroupAiTitle = "groupAi",
            chatGroupAiCopiedMsg = "groupCopied",
            chatClipboardMessageLabel = "msg",
            chatClipboardTranslationLabel = "trans",
            chatTranslationCopiedMsg = "transCopied",
            chatClipboardTranscriptLabel = "transcript",
            chatTranscriptCopiedMsg = "transcriptCopied",
            unknownSenderLabel = "unknown",
            groupMemberLabel = "member"
        )
        assertEquals("copied", labels.chatCopiedMsg)
        assertEquals("unknown", labels.unknownSenderLabel)
        assertEquals("member", labels.groupMemberLabel)
    }
}
