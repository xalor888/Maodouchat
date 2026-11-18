package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatMessageRowModelsTest {

    @Test
    fun chatMessageRowStateCarriesMessageAndSelection() {
        val message = Message("m", "chat", "u", "hi", MessageType.TEXT, 1)
        val state = ChatMessageRowState(
            message = message, isOwn = true, showAvatar = true,
            senderName = "me", replyToPreview = null, isSearchHit = false,
            animateEntry = false, isAnimatingRemoval = false, isSelected = true,
            selectionMode = true, currentUserId = "u", isVoiceTranscribing = false,
            isTranslating = false, fileTransferProgress = null, fileTransferState = null,
            fileTransferError = null, mediaDownloadFailed = false, safetyWarning = null,
        )
        assertEquals(message, state.message)
        assertTrue(state.isSelected)
        assertTrue(state.selectionMode)
        assertEquals(null, state.groupReadCount)
    }
}
