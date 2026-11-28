package com.maodouchat.domain.conversation

import com.maodouchat.core.model.AppLinkDestination
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Independent re-implementation of NotificationTarget.toDestination:
// same five branches, written out explicitly so a slip in either
// side shows up. sessionGeneration / ownerUserId never reach the
// destination, so they are ignored here too.
private fun destOracle192(target: NotificationTarget): AppLinkDestination =
    when (target) {
        is NotificationTarget.Chat ->
            AppLinkDestination.ChatDetail(target.id, target.messageId)
        is NotificationTarget.AiTasks ->
            AppLinkDestination.AiTasksChat(target.chatId)
        is NotificationTarget.Post ->
            AppLinkDestination.PostDetail(target.id)
        is NotificationTarget.PublicProfile ->
            AppLinkDestination.PublicProfile(target.username)
        is NotificationTarget.GroupInvite ->
            AppLinkDestination.GroupInvite(target.code)
    }

private val ID_POOL192: List<String> = listOf(
    "", " ", "c1", "chat-1", "a/b", "a?b", "a#b", "a%b", "a+b",
    "a b", "a~b_c-d.e", "é", "中文", "😀", "x".repeat(80),
    "a\"b", "a'b", "a\nb", "a\tb", "u_1", "0", "-1"
)

private const val ID_ALPHABET192 =
    "abcxyz019/-?#%+ ~_.'\"é😀\n\t"

private fun randomId192(r: Random): String {
    if (r.nextInt(3) == 0) return ID_POOL192.random(r)
    val n = r.nextInt(0, 16)
    return buildString { repeat(n) { append(ID_ALPHABET192.random(r)) } }
}

private fun randomMessageId192(r: Random): String? = when (r.nextInt(4)) {
    0 -> null
    1 -> ""
    2 -> " "
    else -> randomId192(r)
}

private fun randomTarget192(r: Random): NotificationTarget {
    val gen = r.nextLong()
    val owner = randomId192(r)
    return when (r.nextInt(5)) {
        0 -> NotificationTarget.Chat(randomId192(r), gen, owner, randomMessageId192(r))
        1 -> NotificationTarget.AiTasks(randomId192(r), gen, owner)
        2 -> NotificationTarget.Post(randomId192(r), gen, owner)
        3 -> NotificationTarget.PublicProfile(randomId192(r), gen, owner)
        else -> NotificationTarget.GroupInvite(randomId192(r), gen, owner)
    }
}

// ---------- tests ----------

class NavTargetsFuzzTest {

    @Test
    fun navTargetDifferential192() {
        val r = Random(192005)
        repeat(300) {
            val target = randomTarget192(r)
            assertEquals(destOracle192(target), target.toDestination())
        }
    }

    @Test
    fun navTargetFieldsDifferential192() {
        val r = Random(192006)
        repeat(300) {
            // Same payload, different session/owner: destination is identical.
            val id = randomId192(r)
            val msg = randomMessageId192(r)
            val a = NotificationTarget.Chat(id, 1L, "owner-a", msg)
            val b = NotificationTarget.Chat(id, Long.MAX_VALUE, "owner-b", msg)
            assertEquals(a.toDestination(), b.toDestination())
            assertEquals(destOracle192(a), a.toDestination())
            // Null vs blank messageId give different destinations but the
            // same route: both omit the query parameter.
            val nullMsg = NotificationTarget.Chat(id, 1L, "o", null)
            val blankMsg = NotificationTarget.Chat(id, 1L, "o", "  ")
            assertEquals(
                (nullMsg.toDestination() as AppLinkDestination).toRoute(),
                (blankMsg.toDestination() as AppLinkDestination).toRoute()
            )
        }
    }

    @Test
    fun navTargetPinned192() {
        assertEquals(
            AppLinkDestination.ChatDetail("c1", "m1"),
            NotificationTarget.Chat("c1", 7L, "u1", "m1").toDestination()
        )
        assertEquals(
            AppLinkDestination.ChatDetail("c1", null),
            NotificationTarget.Chat("c1", 7L, "u1", null).toDestination()
        )
        assertEquals(
            AppLinkDestination.AiTasksChat("c9"),
            NotificationTarget.AiTasks("c9", 0L, "").toDestination()
        )
        assertEquals(
            AppLinkDestination.PostDetail("p1"),
            NotificationTarget.Post("p1", -1L, "u2").toDestination()
        )
        assertEquals(
            AppLinkDestination.PublicProfile("alice"),
            NotificationTarget.PublicProfile("alice", 3L, "u3").toDestination()
        )
        assertEquals(
            AppLinkDestination.GroupInvite("code-1"),
            NotificationTarget.GroupInvite("code-1", 3L, "u3").toDestination()
        )
        // Routes: reserved characters are percent-encoded, unreserved
        // and Unicode letters pass through untouched.
        assertEquals("chat_detail/a%2Fb", AppLinkDestination.ChatDetail("a/b").toRoute())
        assertEquals("chat_detail/a%20b", AppLinkDestination.ChatDetail("a b").toRoute())
        assertEquals("chat_detail/a%3Fb", AppLinkDestination.ChatDetail("a?b").toRoute())
        assertEquals("chat_detail/a%23b", AppLinkDestination.ChatDetail("a#b").toRoute())
        assertEquals("chat_detail/a%25b", AppLinkDestination.ChatDetail("a%b").toRoute())
        assertEquals("chat_detail/a%2Bb", AppLinkDestination.ChatDetail("a+b").toRoute())
        assertEquals("chat_detail/a%22b", AppLinkDestination.ChatDetail("a\"b").toRoute())
        assertEquals("chat_detail/a%27b", AppLinkDestination.ChatDetail("a'b").toRoute())
        assertEquals(
            "chat_detail/a~b_c-d.e",
            AppLinkDestination.ChatDetail("a~b_c-d.e").toRoute()
        )
        assertEquals("chat_detail/", AppLinkDestination.ChatDetail("").toRoute())
        assertEquals(
            "chat_detail/c1?messageId=m%2F1",
            AppLinkDestination.ChatDetail("c1", "m/1").toRoute()
        )
        assertEquals("chat_detail/c1", AppLinkDestination.ChatDetail("c1", "  ").toRoute())
        // A surrogate pair is encoded unit by unit: each lone surrogate
        // falls back to '?' in UTF-8, so the emoji becomes %3F%3F.
        assertEquals(
            "chat_detail/%3F%3F",
            AppLinkDestination.ChatDetail("😀").toRoute()
        )
        assertEquals("post/p1?comment=", AppLinkDestination.PostDetail("p1").toRoute())
        assertEquals(
            "post/p1?comment=c2",
            AppLinkDestination.PostDetail("p1", "c2").toRoute()
        )
        assertEquals("ai_tasks/x", AppLinkDestination.AiTasksChat("x").toRoute())
        assertEquals(
            "public_profile/u1",
            AppLinkDestination.PublicProfile("u1").toRoute()
        )
        assertEquals(
            "join_group_invite/k",
            AppLinkDestination.GroupInvite("k").toRoute()
        )
    }
}
