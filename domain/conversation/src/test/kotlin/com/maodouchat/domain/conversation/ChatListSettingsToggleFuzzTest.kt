package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatSettingsResponse
import com.maodouchat.core.model.UpdateChatSettingsRequest
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Settings-toggle helpers restated by hand. Ground truth, read from
// ChatListSettingsToggle.kt:
//   - bump: settingsUpdatedAt becomes max(nowMs, old + 1); the +1 wraps
//     at Long.MAX_VALUE, exactly like production.
//   - confirm: server snapshot overwrites the four setting bits and the
//     clock; every other local field keeps its optimistic value.
//   - toggle: PIN pins at nowMs when unpinned (pinnedAt <= 0) and clears
//     to 0 when pinned; MUTE / MARKED_UNREAD flip their bit; ARCHIVE
//     flips archived, clears pinnedAt while archiving and sends
//     pinned=false, unarchiving keeps pinnedAt and sends pinned=null.
internal fun bumpOptimisticSettingsClockOracle184(chat: Chat, nowMs: Long): Chat =
    chat.copy(settingsUpdatedAt = maxOf(nowMs, chat.settingsUpdatedAt + 1L))

internal fun applyConfirmedSettingsOracle184(optimistic: Chat, settings: ChatSettingsResponse): Chat =
    optimistic.copy(
        pinnedAt = settings.pinnedAt,
        notificationsMuted = settings.notificationsMuted,
        archived = settings.archived,
        markedUnread = settings.markedUnread,
        settingsUpdatedAt = settings.updatedAt,
    )

internal fun buildSettingsToggleOracle184(chat: Chat, toggle: ChatSettingsToggle, nowMs: Long): SettingsToggleMutation =
    when (toggle) {
        ChatSettingsToggle.PIN -> {
            val pinning = chat.pinnedAt <= 0
            SettingsToggleMutation(
                optimistic = bumpOptimisticSettingsClockOracle184(
                    chat.copy(pinnedAt = if (chat.pinnedAt > 0) 0 else nowMs),
                    nowMs,
                ),
                request = UpdateChatSettingsRequest(pinned = pinning),
            )
        }
        ChatSettingsToggle.MUTE -> SettingsToggleMutation(
            optimistic = bumpOptimisticSettingsClockOracle184(
                chat.copy(notificationsMuted = !chat.notificationsMuted),
                nowMs,
            ),
            request = UpdateChatSettingsRequest(notificationsMuted = !chat.notificationsMuted),
        )
        ChatSettingsToggle.ARCHIVE -> {
            val archiving = !chat.archived
            SettingsToggleMutation(
                optimistic = bumpOptimisticSettingsClockOracle184(
                    chat.copy(
                        archived = archiving,
                        pinnedAt = if (archiving) 0 else chat.pinnedAt,
                    ),
                    nowMs,
                ),
                request = UpdateChatSettingsRequest(
                    archived = archiving,
                    pinned = if (archiving) false else null,
                ),
            )
        }
        ChatSettingsToggle.MARKED_UNREAD -> SettingsToggleMutation(
            optimistic = bumpOptimisticSettingsClockOracle184(
                chat.copy(markedUnread = !chat.markedUnread),
                nowMs,
            ),
            request = UpdateChatSettingsRequest(markedUnread = !chat.markedUnread),
        )
    }

internal fun escJsonToggle184(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun userWireJson184(u: User): String =
    "{\"id\":${escJsonToggle184(u.id)},\"name\":${escJsonToggle184(u.name)}," +
        "\"avatar\":${u.avatar?.let { escJsonToggle184(it) } ?: "null"}," +
        "\"email\":${escJsonToggle184(u.email)},\"isOnline\":${u.isOnline}," +
        "\"status\":${escJsonToggle184(u.status)}," +
        "\"nickname\":${u.nickname?.let { escJsonToggle184(it) } ?: "null"}," +
        "\"lastSeen\":${u.lastSeen}}"

internal fun chatWireJson184(c: Chat): String {
    val participants = c.participants.joinToString(",") { userWireJson184(it) }
    return "{\"id\":${escJsonToggle184(c.id)},\"participants\":[$participants]," +
        "\"lastMessage\":${escJsonToggle184(c.lastMessage)}," +
        "\"lastMessageType\":${escJsonToggle184(c.lastMessageType.name)}," +
        "\"lastMessageTime\":${c.lastMessageTime},\"unreadCount\":${c.unreadCount}," +
        "\"isGroup\":${c.isGroup},\"chatType\":${escJsonToggle184(c.chatType)}," +
        "\"groupName\":${c.groupName?.let { escJsonToggle184(it) } ?: "null"}," +
        "\"groupAnnouncement\":${c.groupAnnouncement?.let { escJsonToggle184(it) } ?: "null"}," +
        "\"groupAvatar\":${c.groupAvatar?.let { escJsonToggle184(it) } ?: "null"}," +
        "\"memberRevision\":${c.memberRevision},\"pinnedAt\":${c.pinnedAt}," +
        "\"notificationsMuted\":${c.notificationsMuted},\"archived\":${c.archived}," +
        "\"markedUnread\":${c.markedUnread},\"settingsUpdatedAt\":${c.settingsUpdatedAt}," +
        "\"disappearingMessageSeconds\":${c.disappearingMessageSeconds}}"
}

internal fun settingsResponseWireJson184(s: ChatSettingsResponse): String =
    "{\"chatId\":${escJsonToggle184(s.chatId)},\"pinnedAt\":${s.pinnedAt}," +
        "\"notificationsMuted\":${s.notificationsMuted},\"archived\":${s.archived}," +
        "\"markedUnread\":${s.markedUnread},\"updatedAt\":${s.updatedAt}}"

internal fun updateRequestWireJson184(r: UpdateChatSettingsRequest): String =
    "{\"pinned\":${r.pinned?.toString() ?: "null"}," +
        "\"notificationsMuted\":${r.notificationsMuted?.toString() ?: "null"}," +
        "\"archived\":${r.archived?.toString() ?: "null"}," +
        "\"markedUnread\":${r.markedUnread?.toString() ?: "null"}}"

internal fun mutationWireJson184(m: SettingsToggleMutation): String =
    "{\"optimistic\":${chatWireJson184(m.optimistic)}," +
        "\"request\":${updateRequestWireJson184(m.request)}}"

// ---------- production ----------

internal fun bumpProduction184(chat: Chat, nowMs: Long): Chat =
    bumpOptimisticSettingsClock(chat, nowMs)

internal fun applyConfirmedProduction184(optimistic: Chat, settings: ChatSettingsResponse): Chat =
    applyConfirmedSettings(optimistic, settings)

internal fun buildToggleProduction184(chat: Chat, toggle: ChatSettingsToggle, nowMs: Long): SettingsToggleMutation =
    buildSettingsToggle(chat, toggle, nowMs)

// ---------- generators ----------

internal fun chat184(
    pinnedAt: Long = 0L,
    notificationsMuted: Boolean = false,
    archived: Boolean = false,
    markedUnread: Boolean = false,
    settingsUpdatedAt: Long = 0L,
    participants: List<User> = emptyList(),
    isGroup: Boolean = false,
    chatType: String = "DIRECT",
    groupName: String? = null,
): Chat = Chat(
    id = "c1",
    participants = participants,
    lastMessage = "hi",
    lastMessageTime = 1000L,
    unreadCount = 3,
    isGroup = isGroup,
    chatType = chatType,
    groupName = groupName,
    memberRevision = 7L,
    pinnedAt = pinnedAt,
    notificationsMuted = notificationsMuted,
    archived = archived,
    markedUnread = markedUnread,
    settingsUpdatedAt = settingsUpdatedAt,
    disappearingMessageSeconds = 0,
)

internal fun settingsResponse184(
    pinnedAt: Long = 0L,
    notificationsMuted: Boolean = false,
    archived: Boolean = false,
    markedUnread: Boolean = false,
    updatedAt: Long = 0L,
): ChatSettingsResponse = ChatSettingsResponse(
    chatId = "c1",
    pinnedAt = pinnedAt,
    notificationsMuted = notificationsMuted,
    archived = archived,
    markedUnread = markedUnread,
    updatedAt = updatedAt,
)

internal val TOGGLE_VALUES184: List<ChatSettingsToggle> = ChatSettingsToggle.entries

internal fun randomUser184(rnd: Random): User = User(
    id = listOf("u1", "u2", "alice").random(rnd),
    name = listOf("Alice", "Bob", "", "  ").random(rnd),
    avatar = if (rnd.nextBoolean()) null else "https://x/y.png",
    email = listOf("", "a@b.c").random(rnd),
    isOnline = rnd.nextBoolean(),
    status = listOf("", "busy").random(rnd),
    nickname = if (rnd.nextBoolean()) null else "nick",
    lastSeen = rnd.nextLong(-1000L, 100000L),
)

internal val CHAT_CLOCKS184: List<Long> = listOf(
    Long.MIN_VALUE, -1L, 0L, 1L, 999L, 1000L, 1001L, Long.MAX_VALUE - 1L, Long.MAX_VALUE,
)

internal fun randomChat184(rnd: Random): Chat = Chat(
    id = listOf("c1", "c2").random(rnd),
    participants = List(rnd.nextInt(0, 3)) { randomUser184(rnd) },
    lastMessage = listOf("", "hi", "hello world").random(rnd),
    lastMessageTime = rnd.nextLong(-1000L, 100000L),
    unreadCount = rnd.nextInt(0, 100),
    isGroup = rnd.nextBoolean(),
    chatType = listOf("DIRECT", "GROUP", "CHANNEL", "SECRET").random(rnd),
    groupName = if (rnd.nextBoolean()) null else "g",
    groupAnnouncement = if (rnd.nextBoolean()) null else "ann",
    groupAvatar = if (rnd.nextBoolean()) null else "av",
    memberRevision = rnd.nextLong(-1000L, 100000L),
    pinnedAt = CHAT_CLOCKS184.random(rnd),
    notificationsMuted = rnd.nextBoolean(),
    archived = rnd.nextBoolean(),
    markedUnread = rnd.nextBoolean(),
    settingsUpdatedAt = CHAT_CLOCKS184.random(rnd),
    disappearingMessageSeconds = rnd.nextInt(0, 100000),
)

internal fun randomSettingsResponse184(rnd: Random): ChatSettingsResponse =
    ChatSettingsResponse(
        chatId = listOf("c1", "c2").random(rnd),
        pinnedAt = CHAT_CLOCKS184.random(rnd),
        notificationsMuted = rnd.nextBoolean(),
        archived = rnd.nextBoolean(),
        markedUnread = rnd.nextBoolean(),
        updatedAt = CHAT_CLOCKS184.random(rnd),
    )

internal data class ToggleCase184(val chat: Chat, val toggle: ChatSettingsToggle, val nowMs: Long)

internal fun randomToggleCase184(rnd: Random): ToggleCase184 =
    ToggleCase184(randomChat184(rnd), TOGGLE_VALUES184.random(rnd), CHAT_CLOCKS184.random(rnd))

internal fun toggleCaseWireJson184(c: ToggleCase184): String =
    "{\"chat\":${chatWireJson184(c.chat)},\"toggle\":${escJsonToggle184(c.toggle.name)}," +
        "\"nowMs\":${c.nowMs}}"

internal data class BumpCase184(val chat: Chat, val nowMs: Long)

internal fun bumpCaseWireJson184(c: BumpCase184): String =
    "{\"chat\":${chatWireJson184(c.chat)},\"nowMs\":${c.nowMs}}"

internal data class ConfirmCase184(val chat: Chat, val settings: ChatSettingsResponse)

internal fun confirmCaseWireJson184(c: ConfirmCase184): String =
    "{\"chat\":${chatWireJson184(c.chat)},\"settings\":${settingsResponseWireJson184(c.settings)}}"

class ChatListSettingsToggleFuzzTest {

    private fun checkBump184(c: BumpCase184) {
        assertEquals(
            bumpOptimisticSettingsClockOracle184(c.chat, c.nowMs),
            bumpProduction184(c.chat, c.nowMs),
            "bump($c)",
        )
    }

    private fun checkConfirm184(c: ConfirmCase184) {
        assertEquals(
            applyConfirmedSettingsOracle184(c.chat, c.settings),
            applyConfirmedProduction184(c.chat, c.settings),
            "confirm($c)",
        )
    }

    private fun checkToggle184(c: ToggleCase184) {
        assertEquals(
            buildSettingsToggleOracle184(c.chat, c.toggle, c.nowMs),
            buildToggleProduction184(c.chat, c.toggle, c.nowMs),
            "toggle($c)",
        )
    }

    @Test
    fun pinnedSettingsToggle() {
        // bump: nowMs wins, old clock + 1 wins, wrap at MAX_VALUE.
        checkBump184(BumpCase184(chat184(settingsUpdatedAt = 100L), 50L))
        checkBump184(BumpCase184(chat184(settingsUpdatedAt = 100L), 101L))
        checkBump184(BumpCase184(chat184(settingsUpdatedAt = 100L), 100L))
        checkBump184(BumpCase184(chat184(settingsUpdatedAt = Long.MAX_VALUE), 0L))
        checkBump184(BumpCase184(chat184(settingsUpdatedAt = Long.MAX_VALUE), Long.MAX_VALUE))
        checkBump184(BumpCase184(chat184(settingsUpdatedAt = Long.MIN_VALUE), Long.MIN_VALUE))
        checkBump184(BumpCase184(chat184(settingsUpdatedAt = -5L), -5L))
        // confirm: server snapshot overwrites the four bits and the clock.
        checkConfirm184(
            ConfirmCase184(
                chat184(pinnedAt = 9L, notificationsMuted = true, archived = true, markedUnread = true, settingsUpdatedAt = 5L),
                settingsResponse184(pinnedAt = 0L, updatedAt = 42L),
            )
        )
        checkConfirm184(
            ConfirmCase184(
                chat184(),
                settingsResponse184(pinnedAt = 123L, notificationsMuted = true, archived = true, markedUnread = true, updatedAt = Long.MAX_VALUE),
            )
        )
        // PIN: unpinned pins at nowMs, pinned clears to 0, negative pins.
        checkToggle184(ToggleCase184(chat184(pinnedAt = 0L), ChatSettingsToggle.PIN, 500L))
        checkToggle184(ToggleCase184(chat184(pinnedAt = 500L), ChatSettingsToggle.PIN, 500L))
        checkToggle184(ToggleCase184(chat184(pinnedAt = -3L), ChatSettingsToggle.PIN, 500L))
        checkToggle184(ToggleCase184(chat184(pinnedAt = Long.MAX_VALUE), ChatSettingsToggle.PIN, 500L))
        // MUTE / MARKED_UNREAD flip.
        checkToggle184(ToggleCase184(chat184(notificationsMuted = false), ChatSettingsToggle.MUTE, 500L))
        checkToggle184(ToggleCase184(chat184(notificationsMuted = true), ChatSettingsToggle.MUTE, 500L))
        checkToggle184(ToggleCase184(chat184(markedUnread = false), ChatSettingsToggle.MARKED_UNREAD, 500L))
        checkToggle184(ToggleCase184(chat184(markedUnread = true), ChatSettingsToggle.MARKED_UNREAD, 500L))
        // ARCHIVE: archiving clears pinnedAt and sends pinned=false;
        // unarchiving keeps pinnedAt and sends pinned=null.
        checkToggle184(ToggleCase184(chat184(archived = false, pinnedAt = 700L), ChatSettingsToggle.ARCHIVE, 500L))
        checkToggle184(ToggleCase184(chat184(archived = true, pinnedAt = 700L), ChatSettingsToggle.ARCHIVE, 500L))
        checkToggle184(ToggleCase184(chat184(archived = true, pinnedAt = 0L), ChatSettingsToggle.ARCHIVE, 500L))
    }

    @Test
    fun randomSettingsToggleMatchProduction() {
        val rnd = Random(184002)
        repeat(1500) { checkBump184(BumpCase184(randomChat184(rnd), CHAT_CLOCKS184.random(rnd))) }
        repeat(1500) { checkConfirm184(ConfirmCase184(randomChat184(rnd), randomSettingsResponse184(rnd))) }
        repeat(1500) { checkToggle184(randomToggleCase184(rnd)) }
    }

    @Test
    fun sweepSettingsToggleBoundaries() {
        // Every toggle x pinnedAt edge x clock edge.
        val pins = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 500L, Long.MAX_VALUE)
        val clocks = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 500L, Long.MAX_VALUE)
        for (toggle in TOGGLE_VALUES184) {
            for (pin in pins) {
                for (now in clocks) {
                    checkToggle184(ToggleCase184(chat184(pinnedAt = pin, settingsUpdatedAt = now), toggle, now))
                }
            }
        }
        // Every bit combination through confirm.
        for (muted in listOf(false, true)) {
            for (archived in listOf(false, true)) {
                for (unread in listOf(false, true)) {
                    checkConfirm184(
                        ConfirmCase184(
                            chat184(notificationsMuted = !muted, archived = !archived, markedUnread = !unread),
                            settingsResponse184(notificationsMuted = muted, archived = archived, markedUnread = unread),
                        )
                    )
                }
            }
        }
        // bump sweep: clock edges x nowMs edges.
        for (clock in clocks) {
            for (now in clocks) {
                checkBump184(BumpCase184(chat184(settingsUpdatedAt = clock), now))
            }
        }
    }
}
