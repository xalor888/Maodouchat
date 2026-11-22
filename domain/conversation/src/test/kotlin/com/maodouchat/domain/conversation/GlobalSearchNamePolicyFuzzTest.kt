package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [GlobalSearchNamePolicy] (checklist line 931, 43rd block).
 * Test-only, zero production changes.
 *
 * The oracle spells out the branch tables from scratch (group name, first-participant
 * display name, sender lookup, hit-text folding). A Python mini-model differentially
 * checks this oracle against an independent reimplementation on 5000 random inputs
 * (0 diffs).
 *
 * Rules pinned here: an empty direct chat falls back to the unknown label (not the
 * group label); a direct chat takes the first participant without excluding self;
 * hitText folds only '\n', never '\r'.
 */
class GlobalSearchNamePolicyFuzzTest {

    // User.displayName, reimplemented: nickname, then non-blank name, then id.
    private fun oracleDisplayName(user: User): String {
        val nick = user.nickname
        if (nick != null && nick.isNotBlank()) return nick
        if (user.name.isNotBlank()) return user.name
        return user.id
    }

    private fun oracleChatName(chat: Chat?, unknownLabel: String, groupLabel: String): String {
        if (chat == null) return unknownLabel
        if (chat.isGroup) {
            val groupName = chat.groupName
            return if (groupName != null && groupName.isNotBlank()) groupName else groupLabel
        }
        val first = chat.participants.firstOrNull() ?: return unknownLabel
        val display = oracleDisplayName(first)
        return if (display.isNotBlank()) display else unknownLabel
    }

    private fun oracleSenderName(
        senderId: String,
        currentUserId: String?,
        meLabel: String,
        participants: List<User>
    ): String {
        if (currentUserId != null && senderId == currentUserId) return meLabel
        val found = participants.firstOrNull { it.id == senderId }
        return if (found != null) oracleDisplayName(found) else senderId
    }

    private fun oracleHitText(searchableText: String): String {
        val folded = buildString {
            for (ch in searchableText) append(if (ch == '\n') ' ' else ch)
        }
        return folded.trim()
    }

    private val names = listOf(
        "Alice", "alice", "ALICE", "Bob", "bob", "", " ", "  ", "\t",
        "张三", "李四", "u1", "x_9", "hello"
    )
    private val ids = listOf("u1", "u2", "u3", "me", "", "u1")

    private fun randomUser(rnd: Random): User =
        User(
            id = ids.random(rnd),
            name = names.random(rnd),
            nickname = if (rnd.nextInt(10) < 4) null else names.random(rnd)
        )

    private fun randomChat(rnd: Random): Chat? {
        return when (rnd.nextInt(100)) {
            in 0..14 -> null
            in 15..54 -> Chat(
                id = "g1",
                isGroup = true,
                groupName = listOf<String?>(null, "", " ", "  ", "跑团", "跑团小队", "\t", "a b").random(rnd),
                participants = List(rnd.nextInt(0, 4)) { randomUser(rnd) }
            )
            else -> Chat(
                id = "d1",
                participants = List(rnd.nextInt(0, 4)) { randomUser(rnd) }
            )
        }
    }

    private fun randomHitText(rnd: Random): String {
        val alpha = listOf("a", "b", " ", "\t", "\r", "\n", "\u0085", "\u00A0", "\u2007", "\u202F", "\u200B", "\u000B", "\u000C", "\u001C")
        return (0 until rnd.nextInt(13)).joinToString("") { alpha.random(rnd) }
    }

    @Test
    fun chatNameRandomOracle() {
        val rnd = Random(45)
        repeat(300) {
            val chat = randomChat(rnd)
            assertEquals(
                oracleChatName(chat, "未知", "群聊"),
                GlobalSearchNamePolicy.chatName(chat, "未知", "群聊"),
                "chat=$chat"
            )
        }
    }

    @Test
    fun senderNameRandomOracle() {
        val rnd = Random(45)
        repeat(300) {
            val participants = List(rnd.nextInt(0, 4)) { randomUser(rnd) }
            val senderId = ids.random(rnd)
            val currentUserId = listOf<String?>(null, "u1", "u2", "me", "").random(rnd)
            assertEquals(
                oracleSenderName(senderId, currentUserId, "我", participants),
                GlobalSearchNamePolicy.senderName(senderId, currentUserId, "我", participants),
                "sender=<$senderId> current=<$currentUserId> participants=$participants"
            )
        }
    }

    @Test
    fun hitTextRandomOracle() {
        val rnd = Random(45)
        repeat(300) {
            val text = randomHitText(rnd)
            val got = GlobalSearchNamePolicy.hitText(text)
            assertEquals(oracleHitText(text), got, "text=<$text>")
            assertTrue('\n' !in got, "hitText must not contain a newline: <$got>")
            assertEquals(got.trim(), got, "hitText must have no leading/trailing trimmable chars: <$got>")
        }
    }

    @Test
    fun displayNameFallbackRandomOracle() {
        val rnd = Random(45)
        repeat(300) {
            val user = randomUser(rnd)
            // Direct chat with this user first: chatName is their display name, or
            // the unknown label when even the id fallback is blank.
            val chat = Chat(id = "d1", participants = listOf(user))
            val expected = oracleDisplayName(user).let { if (it.isNotBlank()) it else "未知" }
            assertEquals(expected, GlobalSearchNamePolicy.chatName(chat, "未知", "群聊"), "user=$user")
            // Sender lookup by id returns the same display name, blanks included.
            // The target user goes first so a duplicate id cannot shadow it.
            val participants = listOf(user, randomUser(rnd), randomUser(rnd))
            assertEquals(
                oracleDisplayName(user),
                GlobalSearchNamePolicy.senderName(user.id, "someone-else", "我", participants),
                "user=$user participants=$participants"
            )
        }
    }

    @Test
    fun pinnedNameAndHitTextBoundaries() {
        val user = { id: String, name: String, nick: String? -> User(id = id, name = name, nickname = nick) }
        // Blank group name falls back to the group label; null does too.
        assertEquals("群聊", GlobalSearchNamePolicy.chatName(Chat(id = "g1", isGroup = true, groupName = "  "), "未知", "群聊"))
        assertEquals("群聊", GlobalSearchNamePolicy.chatName(Chat(id = "g1", isGroup = true, groupName = null), "未知", "群聊"))
        // NBSP is not blank, so it counts as a group name.
        assertEquals("\u00A0", GlobalSearchNamePolicy.chatName(Chat(id = "g1", isGroup = true, groupName = "\u00A0"), "未知", "群聊"))
        // Direct chat takes the first participant; a fully blank display name falls back.
        assertEquals("Alice", GlobalSearchNamePolicy.chatName(Chat(id = "d1", participants = listOf(user("u1", "Alice", null), user("u2", "Bob", null))), "未知", "群聊"))
        assertEquals("未知", GlobalSearchNamePolicy.chatName(Chat(id = "d1", participants = listOf(user("", " ", null))), "未知", "群聊"))
        assertEquals("未知", GlobalSearchNamePolicy.chatName(Chat(id = "d1", participants = emptyList()), "未知", "群聊"))
        // Nickname wins over name, name over id.
        assertEquals("阿狸", GlobalSearchNamePolicy.chatName(Chat(id = "d1", participants = listOf(user("u1", "Alice", "阿狸"))), "未知", "群聊"))
        assertEquals("u1", GlobalSearchNamePolicy.chatName(Chat(id = "d1", participants = listOf(user("u1", " ", null))), "未知", "群聊"))
        // With a null current user id, nobody is self; lookup falls back to the id.
        val participants = listOf(user("u1", "Alice", null), user("u2", "Bob", null))
        assertEquals("Bob", GlobalSearchNamePolicy.senderName("u2", null, "我", participants))
        assertEquals("u9", GlobalSearchNamePolicy.senderName("u9", null, "我", participants))
        assertEquals("我", GlobalSearchNamePolicy.senderName("u1", "u1", "我", participants))
        // hitText folds only '\n': '\r' survives inside, and is trimmed at the edges.
        assertEquals("", GlobalSearchNamePolicy.hitText("\n\r \n"))
        assertEquals("a\rb", GlobalSearchNamePolicy.hitText("a\rb"))
        assertEquals("", GlobalSearchNamePolicy.hitText("\r"))
        assertEquals("a b c", GlobalSearchNamePolicy.hitText("  a\nb\nc  "))
        assertEquals("a  b", GlobalSearchNamePolicy.hitText("a  b"))
    }
}
