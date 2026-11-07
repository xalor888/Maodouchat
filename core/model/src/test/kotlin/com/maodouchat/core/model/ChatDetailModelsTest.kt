package com.maodouchat.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatDetailModelsTest {

    @Test
    fun mediaCenterUiStateDefaultsToLoadingEmpty() {
        val state = MediaCenterUiState()
        assertTrue(state.isLoading)
        assertEquals(emptyList(), state.items)
        assertEquals(null, state.isChatLocked)
        assertEquals("", state.chatName)
        assertFalse(state.isSecretChat)
    }

    @Test
    fun starredMessagesUiStateDefaultsToLoadingEmpty() {
        val state = StarredMessagesUiState()
        assertTrue(state.isLoading)
        assertEquals(emptyList(), state.messages)
        assertEquals("", state.currentUserId)
        assertFalse(state.globalScope)
    }

    @Test
    fun chatSearchDocumentPartsFollowScope() {
        val doc = ChatSearchDocument(
            message = Message("m", "chat", "u", "hi", MessageType.TEXT, 1),
            text = "hello", voiceTranscript = "voice", translations = listOf("你好"),
        )
        assertEquals(listOf("hello", "voice", "你好"), doc.parts(ChatSearchScope.ALL))
        assertEquals(listOf("hello"), doc.parts(ChatSearchScope.TEXT))
        assertEquals(listOf("voice"), doc.parts(ChatSearchScope.VOICE))
        assertEquals(listOf("你好"), doc.parts(ChatSearchScope.TRANSLATION))
        assertEquals(emptyList(), doc.parts(ChatSearchScope.MEDIA))
        assertFalse(doc.matchesMediaScope())
        val imageDoc = doc.copy(
            message = Message("m2", "chat", "u", "img", MessageType.IMAGE, 2))
        assertTrue(imageDoc.matchesMediaScope())
    }

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
