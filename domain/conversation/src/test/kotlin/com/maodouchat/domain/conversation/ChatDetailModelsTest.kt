package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDetailModelsTest {



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
        assertEquals(emptyList<String>(), doc.parts(ChatSearchScope.MEDIA))
        assertFalse(doc.matchesMediaScope())
        val imageDoc = doc.copy(
            message = Message("m2", "chat", "u", "img", MessageType.IMAGE, 2))
        assertTrue(imageDoc.matchesMediaScope())
    }

}
