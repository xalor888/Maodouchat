package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.ChatSearchScope
import com.maodouchat.ui.screen.chatdetail.ChatSearchWindow
import com.maodouchat.ui.screen.chatdetail.MentionPolicy
import com.maodouchat.ui.screen.chatdetail.buildChatSearchDocuments
import com.maodouchat.ui.screen.chatdetail.searchChatDocuments
import com.maodouchat.ui.screen.chatdetail.semanticSearchCandidates
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchModelTest {
    @Test
    fun `search reuses text voice and translation documents including Chinese compatibility key`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("text", MessageType.TEXT, "Release plan"),
                message("voice", MessageType.VOICE, "voice://1", MessageMeta(voiceTranscript = "周六发布")),
                message(
                    "translation",
                    MessageType.TEXT,
                    "hello",
                    MessageMeta(translations = mapOf("中文" to "你好世界", "English" to "hello world"))
                )
            )
        )

        assertEquals("text", searchChatDocuments(documents, "release", ChatSearchScope.TEXT, ChatSearchWindow.ALL).single().id)
        assertEquals("voice", searchChatDocuments(documents, "周六", ChatSearchScope.VOICE, ChatSearchWindow.ALL).single().id)
        assertEquals("translation", searchChatDocuments(documents, "你好", ChatSearchScope.TRANSLATION, ChatSearchWindow.ALL).single().id)
        assertEquals("你好世界", documents.single { it.message.id == "translation" }.translations.first())
    }

    @Test
    fun `starred blank query and time windows are deterministic`() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val documents = buildChatSearchDocuments(
            listOf(
                message("old", MessageType.TEXT, "old", timestamp = now - 8L * 24L * 60L * 60L * 1_000L, starred = true),
                message("today", MessageType.TEXT, "today", timestamp = now - 1_000L, starred = true),
                message("plain", MessageType.TEXT, "plain", timestamp = now, starred = false)
            )
        )

        assertEquals(listOf("today", "old"), searchChatDocuments(documents, "", ChatSearchScope.STARRED, ChatSearchWindow.ALL, now = now).map { it.id })
        assertEquals(listOf("today"), searchChatDocuments(documents, "", ChatSearchScope.STARRED, ChatSearchWindow.TODAY, now = now).map { it.id })
        assertFalse(searchChatDocuments(documents, "old", ChatSearchScope.ALL, ChatSearchWindow.SEVEN_DAYS, now = now).any())
    }

    @Test
    fun `semantic candidates exclude protocol messages and keep latest sixty chronologically`() {
        val messages = (0 until 65).map { index -> message("m$index", MessageType.TEXT, "text $index", timestamp = index.toLong()) } +
            message("hidden", MessageType.SK_DIST, "secret", timestamp = 100L)
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            now = 1_000L
        )

        assertEquals(65, candidates.size)
        assertEquals("m0", candidates.first().id)
        assertEquals("m64", candidates.last().id)
        assertTrue(candidates.none { it.id == "hidden" })
    }

    @Test
    fun `media scope with blank query lists every media message`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("pic", MessageType.IMAGE, "file://pic", timestamp = 2L),
                message("note", MessageType.TEXT, "note", timestamp = 3L)
            )
        )

        assertEquals(listOf("pic"), searchChatDocuments(documents, "", ChatSearchScope.MEDIA, ChatSearchWindow.ALL).map { it.id })
    }

    @Test
    fun `media scope matches Chinese type keyword`() {
        val documents = buildChatSearchDocuments(
            listOf(message("pic", MessageType.IMAGE, "file://pic"))
        )

        assertEquals("pic", searchChatDocuments(documents, "图片", ChatSearchScope.MEDIA, ChatSearchWindow.ALL).single().id)
        assertEquals("pic", searchChatDocuments(documents, "image", ChatSearchScope.MEDIA, ChatSearchWindow.ALL).single().id)
    }

    @Test
    fun `mentions scope keeps direct mention and at-everyone only`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("direct", MessageType.TEXT, "hi", meta = MessageMeta(mentions = listOf("me")), timestamp = 1L),
                message("everyone", MessageType.TEXT, "all", meta = MessageMeta(mentions = listOf(MentionPolicy.EVERYONE_ID)), timestamp = 2L),
                message("other", MessageType.TEXT, "them", meta = MessageMeta(mentions = listOf("other")), timestamp = 3L)
            )
        )

        val ids = searchChatDocuments(documents, "", ChatSearchScope.MENTIONS, ChatSearchWindow.ALL, currentUserId = "me").map { it.id }
        assertEquals(listOf("everyone", "direct"), ids)
    }

    @Test
    fun `seven day window boundary is inclusive`() {
        val now = 1_000_000L
        val windowStart = now - 7L * 24L * 60L * 60L * 1_000L
        val documents = buildChatSearchDocuments(
            listOf(
                message("edge", MessageType.TEXT, "keep me", timestamp = windowStart),
                message("outside", MessageType.TEXT, "keep me", timestamp = windowStart - 1L)
            )
        )

        assertEquals(listOf("edge"), searchChatDocuments(documents, "keep", ChatSearchScope.ALL, ChatSearchWindow.SEVEN_DAYS, now = now).map { it.id })
    }

    @Test
    fun `system and nudge messages never become search documents`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("sys", MessageType.SYSTEM, "joined", timestamp = 1L),
                message("nudge", MessageType.NUDGE, "buzz", timestamp = 2L),
                message("plain", MessageType.TEXT, "hello", timestamp = 3L)
            )
        )

        assertEquals(listOf("plain"), documents.map { it.message.id })
    }

    @Test
    fun `blank query on ALL scope returns empty even with matching text`() {
        val documents = buildChatSearchDocuments(
            listOf(message("hit", MessageType.TEXT, "hello world"))
        )

        assertTrue(searchChatDocuments(documents, "", ChatSearchScope.ALL, ChatSearchWindow.ALL).isEmpty())
    }

    @Test
    fun `full phrase outranks token matches and timestamp breaks score ties`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("full", MessageType.TEXT, "release plan", timestamp = 1L),
                message("notes", MessageType.TEXT, "release notes", timestamp = 3L),
                message("planonly", MessageType.TEXT, "plan only", timestamp = 2L),
                message("unrelated", MessageType.TEXT, "zzz", timestamp = 9L)
            )
        )

        val ids = searchChatDocuments(documents, "release plan", ChatSearchScope.ALL, ChatSearchWindow.ALL).map { it.id }
        assertEquals(listOf("full", "notes", "planonly"), ids)
    }

    @Test
    fun `voice scope matches transcripts only`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("voice", MessageType.VOICE, "voice://1", MessageMeta(voiceTranscript = "morning brief")),
                message("text", MessageType.TEXT, "evening", MessageMeta(translations = mapOf("English" to "morning light")))
            )
        )

        assertEquals(listOf("voice"), searchChatDocuments(documents, "morning", ChatSearchScope.VOICE, ChatSearchWindow.ALL).map { it.id })
    }

    @Test
    fun `thirty day window boundary is inclusive`() {
        val now = 1_000_000_000L
        val windowStart = now - 30L * 24L * 60L * 60L * 1_000L
        val documents = buildChatSearchDocuments(
            listOf(
                message("edge", MessageType.TEXT, "keep me", timestamp = windowStart),
                message("outside", MessageType.TEXT, "keep me", timestamp = windowStart - 1L)
            )
        )

        assertEquals(listOf("edge"), searchChatDocuments(documents, "keep", ChatSearchScope.ALL, ChatSearchWindow.THIRTY_DAYS, now = now).map { it.id })
    }

    @Test
    fun `sk_dist excluded from documents while media types kept`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("secret", MessageType.SK_DIST, "secret", timestamp = 1L),
                message("pic", MessageType.IMAGE, "file://pic", timestamp = 2L),
                message("sticker", MessageType.STICKER, "sticker://1", timestamp = 3L)
            )
        )

        assertEquals(listOf("pic", "sticker"), documents.map { it.message.id })
    }


    @Test
    fun `padded mixed-case query matches case-insensitively`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("doc", MessageType.TEXT, "Release Plan", timestamp = 1L),
                message("other", MessageType.TEXT, "unrelated notes", timestamp = 2L)
            )
        )

        assertEquals(listOf("doc"), searchChatDocuments(documents, "  RELEASE plan ", ChatSearchScope.ALL, ChatSearchWindow.ALL).map { it.id })
    }

    @Test
    fun `today window keeps midnight message and drops previous late night message`() {
        val noon = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = noon.timeInMillis
        val midnight = (noon.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val documents = buildChatSearchDocuments(
            listOf(
                message("midnight", MessageType.TEXT, "keep me", timestamp = midnight),
                message("before", MessageType.TEXT, "keep me", timestamp = midnight - 1_000L)
            )
        )

        assertEquals(listOf("midnight"), searchChatDocuments(documents, "keep", ChatSearchScope.ALL, ChatSearchWindow.TODAY, now = now).map { it.id })
    }

    @Test
    fun `starred scope filters out unstarred text match`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("starred", MessageType.TEXT, "alpha", timestamp = 1L, starred = true),
                message("plain", MessageType.TEXT, "alpha", timestamp = 2L, starred = false)
            )
        )

        assertEquals(listOf("starred"), searchChatDocuments(documents, "alpha", ChatSearchScope.STARRED, ChatSearchWindow.ALL).map { it.id })
    }

    @Test
    fun `semantic single-character query token is ignored`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("a", MessageType.TEXT, "apple", timestamp = 1L),
                message("b", MessageType.TEXT, "banana", timestamp = 2L),
                message("c", MessageType.TEXT, "cherry", timestamp = 3L)
            )
        )

        assertEquals(listOf("a", "b", "c"), semanticSearchCandidates(documents, ChatSearchScope.ALL, ChatSearchWindow.ALL, query = "a").map { it.id })
    }

    @Test
    fun `semantic candidates cap keeps earliest one hundred for single sender`() {
        val messages = (0 until 101).map { index -> message("m$index", MessageType.TEXT, "text $index", timestamp = index.toLong()) }
        val candidates = semanticSearchCandidates(buildChatSearchDocuments(messages), ChatSearchScope.ALL, ChatSearchWindow.ALL)

        assertEquals(100, candidates.size)
        assertEquals("m0", candidates.first().id)
        assertEquals("m99", candidates.last().id)
    }

@Test
    fun `media scope matches video by Chinese keyword`() {
        val documents = buildChatSearchDocuments(
            listOf(message("clip", MessageType.VIDEO, "file://clip"))
        )

        assertEquals(listOf("clip"), searchChatDocuments(documents, "视频", ChatSearchScope.MEDIA, ChatSearchWindow.ALL).map { it.id })
        assertEquals(listOf("clip"), searchChatDocuments(documents, "video", ChatSearchScope.MEDIA, ChatSearchWindow.ALL).map { it.id })
    }

    @Test
    fun `translation scope ignores original text without translations`() {
        val documents = buildChatSearchDocuments(
            listOf(message("doc", MessageType.TEXT, "alpha"))
        )

        assertTrue(searchChatDocuments(documents, "alpha", ChatSearchScope.TRANSLATION, ChatSearchWindow.ALL).isEmpty())
        assertEquals(listOf("doc"), searchChatDocuments(documents, "alpha", ChatSearchScope.TEXT, ChatSearchWindow.ALL).map { it.id })
    }

    @Test
    fun `revoked message becomes a document but is not searchable`() {
        val documents = buildChatSearchDocuments(
            listOf(message("rev", MessageType.REVOKED, "bye"))
        )

        assertEquals(listOf("rev"), documents.map { it.message.id })
        assertTrue(searchChatDocuments(documents, "bye", ChatSearchScope.ALL, ChatSearchWindow.ALL).isEmpty())
    }

    @Test
    fun `semantic candidates sort chronologically regardless of input order`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("late", MessageType.TEXT, "c", timestamp = 3L),
                message("early", MessageType.TEXT, "a", timestamp = 1L),
                message("mid", MessageType.TEXT, "b", timestamp = 2L)
            )
        )

        assertEquals(listOf("early", "mid", "late"), semanticSearchCandidates(documents, ChatSearchScope.ALL, ChatSearchWindow.ALL).map { it.id })
    }

    @Test
    fun `semantic media scope includes image without searchable parts`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("pic", MessageType.IMAGE, "file://pic"),
                message("note", MessageType.TEXT, "note")
            )
        )

        assertEquals(listOf("pic"), semanticSearchCandidates(documents, ChatSearchScope.MEDIA, ChatSearchWindow.ALL).map { it.id })
    }

    @Test
    fun `media scope matches file by Chinese keyword`() {
        val documents = buildChatSearchDocuments(
            listOf(message("doc", MessageType.FILE, "file://doc"))
        )

        assertEquals(listOf("doc"), searchChatDocuments(documents, "文件", ChatSearchScope.MEDIA, ChatSearchWindow.ALL).map { it.id })
    }

    @Test
    fun `voice scope blank query returns empty`() {
        val documents = buildChatSearchDocuments(
            listOf(message("voice", MessageType.VOICE, "voice://1", MessageMeta(voiceTranscript = "morning brief")))
        )

        assertTrue(searchChatDocuments(documents, "", ChatSearchScope.VOICE, ChatSearchWindow.ALL).isEmpty())
    }

    @Test
    fun `mentions scope blank query ignores unmatched mentions`() {
        val documents = buildChatSearchDocuments(
            listOf(message("other", MessageType.TEXT, "them", meta = MessageMeta(mentions = listOf("other")), timestamp = 1L))
        )

        assertTrue(searchChatDocuments(documents, "", ChatSearchScope.MENTIONS, ChatSearchWindow.ALL, currentUserId = "me").isEmpty())
    }

    @Test
    fun `equal scores order by timestamp descending`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("early", MessageType.TEXT, "alpha", timestamp = 1L),
                message("late", MessageType.TEXT, "alpha", timestamp = 5L)
            )
        )

        assertEquals(listOf("late", "early"), searchChatDocuments(documents, "alpha", ChatSearchScope.ALL, ChatSearchWindow.ALL).map { it.id })
    }

    @Test
    fun `translation scope token matches one translation value only`() {
        val documents = buildChatSearchDocuments(
            listOf(message("doc", MessageType.TEXT, "hello", MessageMeta(translations = mapOf("English" to "hello world"))))
        )

        assertEquals(listOf("doc"), searchChatDocuments(documents, "world", ChatSearchScope.TRANSLATION, ChatSearchWindow.ALL).map { it.id })
        assertTrue(searchChatDocuments(documents, "world", ChatSearchScope.TEXT, ChatSearchWindow.ALL).isEmpty())
    }


    @Test
    fun `media scope matches sticker and location by Chinese keyword`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("sticker", MessageType.STICKER, "sticker://1"),
                message("loc", MessageType.LOCATION, "loc://1"),
                message("gif", MessageType.GIF, "gif://1")
            )
        )

        assertEquals(listOf("sticker"), searchChatDocuments(documents, "贴纸", ChatSearchScope.MEDIA, ChatSearchWindow.ALL).map { it.id })
        assertEquals(listOf("loc"), searchChatDocuments(documents, "位置", ChatSearchScope.MEDIA, ChatSearchWindow.ALL).map { it.id })
        assertEquals(listOf("gif"), searchChatDocuments(documents, "gif", ChatSearchScope.MEDIA, ChatSearchWindow.ALL).map { it.id })
    }

    @Test
    fun `media scope matches image by its translation text`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("pic", MessageType.IMAGE, "file://pic", MessageMeta(translations = mapOf("English" to "sunset view"))),
                message("plain", MessageType.IMAGE, "file://plain")
            )
        )

        assertEquals(listOf("pic"), searchChatDocuments(documents, "sunset", ChatSearchScope.MEDIA, ChatSearchWindow.ALL).map { it.id })
    }

    @Test
    fun `mentions scope with query filters by mention first then text`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("direct", MessageType.TEXT, "alpha", meta = MessageMeta(mentions = listOf("me")), timestamp = 1L),
                message("everyone", MessageType.TEXT, "beta", meta = MessageMeta(mentions = listOf(MentionPolicy.EVERYONE_ID)), timestamp = 2L),
                message("other", MessageType.TEXT, "alpha", meta = MessageMeta(mentions = listOf("other")), timestamp = 3L)
            )
        )

        assertEquals(listOf("direct"), searchChatDocuments(documents, "alpha", ChatSearchScope.MENTIONS, ChatSearchWindow.ALL, currentUserId = "me").map { it.id })
    }

    @Test
    fun `starred seven day window drops old starred message`() {
        val now = 1_000_000L
        val windowStart = now - 7L * 24L * 60L * 60L * 1_000L
        val documents = buildChatSearchDocuments(
            listOf(
                message("old", MessageType.TEXT, "alpha", timestamp = windowStart - 1L, starred = true),
                message("recent", MessageType.TEXT, "alpha", timestamp = now - 1_000L, starred = true)
            )
        )

        assertEquals(listOf("recent"), searchChatDocuments(documents, "alpha", ChatSearchScope.STARRED, ChatSearchWindow.SEVEN_DAYS, now = now).map { it.id })
        assertEquals(listOf("recent"), searchChatDocuments(documents, "", ChatSearchScope.STARRED, ChatSearchWindow.SEVEN_DAYS, now = now).map { it.id })
    }

    @Test
    fun `semantic mentions scope returns only mentioned documents chronologically`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("direct", MessageType.TEXT, "a", meta = MessageMeta(mentions = listOf("me")), timestamp = 1L),
                message("everyone", MessageType.TEXT, "b", meta = MessageMeta(mentions = listOf(MentionPolicy.EVERYONE_ID)), timestamp = 2L),
                message("other", MessageType.TEXT, "c", meta = MessageMeta(mentions = listOf("other")), timestamp = 3L)
            )
        )

        assertEquals(listOf("direct", "everyone"), semanticSearchCandidates(documents, ChatSearchScope.MENTIONS, ChatSearchWindow.ALL, currentUserId = "me").map { it.id })
    }

    private fun message(
        id: String,
        type: MessageType,
        content: String,
        meta: MessageMeta = MessageMeta(),
        timestamp: Long = 1L,
        starred: Boolean = false
    ) = Message(id, "chat", "user", content, type, timestamp, MessageStatus.SENT, starred = starred, meta = meta)
}
