package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ChatLoadEffect
import com.maodouchat.core.model.ChatLoadInput
import com.maodouchat.core.model.ChatLoadPlan
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// planChatLoad restated by hand: the sender key is invalidated only
// when the group revision strictly rises over a known baseline;
// groups build a synthetic contact and three effects, direct chats
// with a peer get cipher + block + scheduled effects plus bot or
// identity-safety, direct chats with no peer get nothing at all.
// isSecret is restated as chatType == "SECRET" and the bot check as
// a startsWith("bot_") so the oracle never calls production.
internal fun planChatLoadOracle173(
    input: ChatLoadInput,
    currentState: ChatDetailUiState,
    formatGroupName: () -> String,
    formatMemberCount: (Int) -> String,
    formatRevisionWarning: () -> String,
): ChatLoadPlan {
    val chat = input.chat
    val previousRevision = input.previousRevision
    val shouldInvalidateSenderKey = chat.isGroup &&
        previousRevision != null &&
        chat.memberRevision > previousRevision
    val revisionWarning = if (shouldInvalidateSenderKey) formatRevisionWarning() else null
    val base = currentState.copy(isLoading = false, initialLoadError = null)
    return if (chat.isGroup) {
        val groupContact = User(
            id = chat.id,
            name = chat.groupName ?: formatGroupName(),
            avatar = chat.groupAvatar,
            status = formatMemberCount(chat.participants.size),
        )
        val nextState = base.copy(
            chat = chat,
            chatIsGroup = true,
            isSecretChat = chat.chatType == "SECRET",
            contact = groupContact,
            disappearingMessageSeconds = chat.disappearingMessageSeconds,
        )
        ChatLoadPlan(
            nextState = nextState,
            effects = listOf(
                ChatLoadEffect.OccupySessionCipher(chat.id, peerUserId = null),
                ChatLoadEffect.LoadGroupCandidates,
                ChatLoadEffect.RefreshBotCommands(chat.id),
            ),
            shouldInvalidateSenderKey = shouldInvalidateSenderKey,
            revisionWarning = revisionWarning,
            fromCache = input.fromCache,
        )
    } else {
        val peer = chat.participants.firstOrNull { it.id != input.currentUserId }
        if (peer == null) {
            ChatLoadPlan(
                nextState = base.copy(chat = chat, chatIsGroup = false),
                effects = emptyList(),
                shouldInvalidateSenderKey = false,
                revisionWarning = null,
                fromCache = input.fromCache,
            )
        } else {
            val nextState = base.copy(
                chat = chat,
                chatIsGroup = false,
                isSecretChat = chat.chatType == "SECRET",
                contact = peer,
                disappearingMessageSeconds = chat.disappearingMessageSeconds,
            )
            val effects = buildList {
                add(ChatLoadEffect.OccupySessionCipher(chat.id, peer.id))
                add(ChatLoadEffect.RefreshBlockState(peer.id))
                add(ChatLoadEffect.RefreshScheduledMessages)
                if (peer.id.startsWith("bot_")) {
                    add(ChatLoadEffect.RefreshBotCommands(chat.id))
                } else {
                    add(ChatLoadEffect.RefreshIdentitySafety(peer.id))
                }
            }
            ChatLoadPlan(
                nextState = nextState,
                effects = effects,
                shouldInvalidateSenderKey = false,
                revisionWarning = null,
                fromCache = input.fromCache,
            )
        }
    }
}

// planLoadFailure restated by hand: loading off, error text in.
internal fun planLoadFailureOracle173(
    currentState: ChatDetailUiState,
    formatError: () -> String,
): ChatDetailUiState = currentState.copy(isLoading = false, initialLoadError = formatError())

// Production planChatLoad returns the load plan.
internal fun planChatLoadProduction173(
    input: ChatLoadInput,
    currentState: ChatDetailUiState,
    formatGroupName: () -> String,
    formatMemberCount: (Int) -> String,
    formatRevisionWarning: () -> String,
): ChatLoadPlan = ChatDetailLoadCoordinator.planChatLoad(
    input, currentState, formatGroupName, formatMemberCount, formatRevisionWarning
)

// Production planLoadFailure returns the failure state.
internal fun planLoadFailureProduction173(
    currentState: ChatDetailUiState,
    formatError: () -> String,
): ChatDetailUiState = ChatDetailLoadCoordinator.planLoadFailure(currentState, formatError)

// ---------- generators ----------

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJsonLoad173(s: String): String {
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
                if (ch.code < 0x20) {
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

internal fun escJsonOrNullLoad173(s: String?): String =
    if (s == null) "null" else escJsonLoad173(s)

internal fun escJsonOrNullBoolLoad173(b: Boolean?): String =
    if (b == null) "null" else b.toString()

internal fun userLoad173(id: String, name: String, avatar: String?, status: String): User =
    User(id = id, name = name, avatar = avatar, status = status)

internal fun chatLoad173(
    id: String,
    isGroup: Boolean,
    chatType: String,
    groupName: String?,
    groupAvatar: String?,
    memberRevision: Long,
    disappearingMessageSeconds: Int,
    participants: List<User>
): Chat = Chat(
    id = id,
    participants = participants,
    lastMessageTime = 0L,
    isGroup = isGroup,
    chatType = chatType,
    groupName = groupName,
    groupAvatar = groupAvatar,
    memberRevision = memberRevision,
    disappearingMessageSeconds = disappearingMessageSeconds
)

// The sentinel state: non-default values on every field the plan
// does not write, so a stray overwrite shows up in the projection.
internal data class StateCase173(
    val inputText: String,
    val isRecording: Boolean,
    val currentUserId: String,
    val isLoading: Boolean,
    val initialLoadError: String?,
    val contact: User,
    val chatIsGroup: Boolean,
    val isSecretChat: Boolean?,
    val disappearingMessageSeconds: Int
)

internal fun sentinelState173(): StateCase173 = StateCase173(
    inputText = "SENTINEL-IN",
    isRecording = true,
    currentUserId = "sentinel-uid",
    isLoading = true,
    initialLoadError = "OLD-ERR",
    contact = userLoad173("sentinel-contact", "Sentinel", null, "sentinel-status"),
    chatIsGroup = true,
    isSecretChat = true,
    disappearingMessageSeconds = 999
)

internal fun buildState173(c: StateCase173): ChatDetailUiState = ChatDetailUiState(
    inputText = c.inputText,
    isRecording = c.isRecording,
    currentUserId = c.currentUserId,
    isLoading = c.isLoading,
    initialLoadError = c.initialLoadError,
    contact = c.contact,
    chatIsGroup = c.chatIsGroup,
    isSecretChat = c.isSecretChat,
    disappearingMessageSeconds = c.disappearingMessageSeconds
)

internal fun stateWireJson173(c: StateCase173): String =
    "{\"inputText\":${escJsonLoad173(c.inputText)}," +
        "\"isRecording\":${c.isRecording}," +
        "\"currentUserId\":${escJsonLoad173(c.currentUserId)}," +
        "\"isLoading\":${c.isLoading}," +
        "\"initialLoadError\":${escJsonOrNullLoad173(c.initialLoadError)}," +
        "\"contact\":${userWireJson173(c.contact)}," +
        "\"chatIsGroup\":${c.chatIsGroup}," +
        "\"isSecretChat\":${escJsonOrNullBoolLoad173(c.isSecretChat)}," +
        "\"disappearingMessageSeconds\":${c.disappearingMessageSeconds}}"

internal fun userWireJson173(u: User): String =
    "{\"id\":${escJsonLoad173(u.id)}," +
        "\"name\":${escJsonLoad173(u.name)}," +
        "\"avatar\":${escJsonOrNullLoad173(u.avatar)}," +
        "\"status\":${escJsonLoad173(u.status)}}"

internal data class LoadCase173(
    val id: String,
    val isGroup: Boolean,
    val chatType: String,
    val groupName: String?,
    val groupAvatar: String?,
    val memberRevision: Long,
    val disappearingMessageSeconds: Int,
    val participants: List<User>,
    val previousRevision: Long?,
    val currentUserId: String,
    val fromCache: Boolean,
    val state: StateCase173 = sentinelState173()
)

internal fun buildLoadInput173(c: LoadCase173): ChatLoadInput = ChatLoadInput(
    chat = chatLoad173(
        c.id, c.isGroup, c.chatType, c.groupName, c.groupAvatar,
        c.memberRevision, c.disappearingMessageSeconds, c.participants
    ),
    previousRevision = c.previousRevision,
    currentUserId = c.currentUserId,
    fromCache = c.fromCache
)

internal fun loadWireJson173(c: LoadCase173): String {
    val parts = c.participants.joinToString(",") { userWireJson173(it) }
    return "{\"chat\":{\"id\":${escJsonLoad173(c.id)}," +
        "\"isGroup\":${c.isGroup}," +
        "\"chatType\":${escJsonLoad173(c.chatType)}," +
        "\"groupName\":${escJsonOrNullLoad173(c.groupName)}," +
        "\"groupAvatar\":${escJsonOrNullLoad173(c.groupAvatar)}," +
        "\"memberRevision\":${c.memberRevision}," +
        "\"disappearingMessageSeconds\":${c.disappearingMessageSeconds}," +
        "\"participants\":[$parts]}," +
        "\"previousRevision\":${c.previousRevision?.toString() ?: "null"}," +
        "\"currentUserId\":${escJsonLoad173(c.currentUserId)}," +
        "\"fromCache\":${c.fromCache}," +
        "\"state\":${stateWireJson173(c.state)}}"
}

internal fun failureWireJson173(c: StateCase173): String =
    "{\"state\":${stateWireJson173(c)}}"

// The projection compared by the differential: every field the plan
// writes, the full effect list in order, and the sentinel fields
// the plan must leave alone. A null peer renders as "null", the
// way Kotlin string interpolation prints a null.
internal fun effectToken173(effect: ChatLoadEffect): String = when (effect) {
    is ChatLoadEffect.OccupySessionCipher ->
        "OccupySessionCipher:${effect.chatId}:${effect.peerUserId}"
    ChatLoadEffect.LoadGroupCandidates -> "LoadGroupCandidates"
    is ChatLoadEffect.RefreshBotCommands -> "RefreshBotCommands:${effect.chatId}"
    is ChatLoadEffect.RefreshBlockState -> "RefreshBlockState:${effect.peerUserId}"
    ChatLoadEffect.RefreshScheduledMessages -> "RefreshScheduledMessages"
    is ChatLoadEffect.RefreshIdentitySafety -> "RefreshIdentitySafety:${effect.peerUserId}"
}

internal fun planProjectionJson173(plan: ChatLoadPlan): String {
    val s = plan.nextState
    val chat = s.chat
    val effects = plan.effects.joinToString(",", "[", "]") { escJsonLoad173(effectToken173(it)) }
    return "{\"chatId\":${escJsonOrNullLoad173(chat?.id)}," +
        "\"chatIsGroup\":${chat?.isGroup}," +
        "\"chatType\":${escJsonOrNullLoad173(chat?.chatType)}," +
        "\"groupName\":${escJsonOrNullLoad173(chat?.groupName)}," +
        "\"memberRevision\":${chat?.memberRevision}," +
        "\"chatDisappearingMessageSeconds\":${chat?.disappearingMessageSeconds}," +
        "\"stateIsGroup\":${s.chatIsGroup}," +
        "\"isSecretChat\":${escJsonOrNullBoolLoad173(s.isSecretChat)}," +
        "\"contactId\":${escJsonLoad173(s.contact.id)}," +
        "\"contactName\":${escJsonLoad173(s.contact.name)}," +
        "\"contactAvatar\":${escJsonOrNullLoad173(s.contact.avatar)}," +
        "\"contactStatus\":${escJsonLoad173(s.contact.status)}," +
        "\"isLoading\":${s.isLoading}," +
        "\"initialLoadError\":${escJsonOrNullLoad173(s.initialLoadError)}," +
        "\"inputText\":${escJsonLoad173(s.inputText)}," +
        "\"isRecording\":${s.isRecording}," +
        "\"currentUserId\":${escJsonLoad173(s.currentUserId)}," +
        "\"effects\":$effects," +
        "\"shouldInvalidate\":${plan.shouldInvalidateSenderKey}," +
        "\"revisionWarning\":${escJsonOrNullLoad173(plan.revisionWarning)}," +
        "\"fromCache\":${plan.fromCache}}"
}

internal fun failureProjectionJson173(state: ChatDetailUiState): String =
    "{\"isLoading\":${state.isLoading}," +
        "\"initialLoadError\":${escJsonOrNullLoad173(state.initialLoadError)}," +
        "\"inputText\":${escJsonLoad173(state.inputText)}," +
        "\"isRecording\":${state.isRecording}," +
        "\"currentUserId\":${escJsonLoad173(state.currentUserId)}," +
        "\"contactId\":${escJsonLoad173(state.contact.id)}," +
        "\"chatIsGroup\":${state.chatIsGroup}," +
        "\"isSecretChat\":${escJsonOrNullBoolLoad173(state.isSecretChat)}}"

internal val LOAD_ID_POOL173: List<String> = listOf(
    "", "c1", "chat-9", "group-42", "x".repeat(20)
)

internal val LOAD_CHATTYPE_POOL173: List<String> = listOf(
    "DIRECT", "GROUP", "SECRET", "CHANNEL"
)

internal val LOAD_GROUP_NAME_POOL173: List<String?> = listOf(
    null, "", "  ", "Family", " 工作群 ", "g".repeat(20)
)

internal val LOAD_GROUP_AVATAR_POOL173: List<String?> = listOf(
    null, "", "av"
)

internal val LOAD_MEMBER_REV_POOL173: List<Long> = listOf(
    0L, 1L, 2L, 5L, Long.MAX_VALUE, Long.MIN_VALUE, -1L, 100L
)

internal val LOAD_PREV_REV_POOL173: List<Long?> = listOf(
    null, 0L, 1L, 2L, 5L, Long.MAX_VALUE, Long.MIN_VALUE, -1L, 100L
)

internal val LOAD_CURRENT_USER_POOL173: List<String> = listOf(
    "", "u1", "me", "bot_x"
)

internal val LOAD_PARTICIPANT_SHAPES173: List<List<User>> = listOf(
    emptyList(),
    listOf(userLoad173("me", "Me", null, "")),
    listOf(userLoad173("u2", "Bob", "av2", "online")),
    listOf(userLoad173("me", "Me", null, ""), userLoad173("u2", "Bob", "av2", "online")),
    listOf(userLoad173("u2", "Bob", "av2", "online"), userLoad173("me", "Me", null, "")),
    listOf(userLoad173("me", "Me", null, ""), userLoad173("bot_helper", "Helper", null, "")),
    listOf(userLoad173("bot_helper", "Helper", null, ""), userLoad173("me", "Me", null, "")),
    listOf(userLoad173("me", "Me", null, ""), userLoad173("bot", "Bot", null, "")),
    listOf(userLoad173("u2", "Bob", null, ""), userLoad173("u3", "Carol", null, ""))
)

internal fun randomLoadCase173(rnd: Random): LoadCase173 {
    val isGroup = rnd.nextBoolean()
    return LoadCase173(
        id = LOAD_ID_POOL173[rnd.nextInt(LOAD_ID_POOL173.size)],
        isGroup = isGroup,
        chatType = LOAD_CHATTYPE_POOL173[rnd.nextInt(LOAD_CHATTYPE_POOL173.size)],
        groupName = LOAD_GROUP_NAME_POOL173[rnd.nextInt(LOAD_GROUP_NAME_POOL173.size)],
        groupAvatar = LOAD_GROUP_AVATAR_POOL173[rnd.nextInt(LOAD_GROUP_AVATAR_POOL173.size)],
        memberRevision = LOAD_MEMBER_REV_POOL173[rnd.nextInt(LOAD_MEMBER_REV_POOL173.size)],
        disappearingMessageSeconds = rnd.nextInt(0, 1000),
        participants = LOAD_PARTICIPANT_SHAPES173[rnd.nextInt(LOAD_PARTICIPANT_SHAPES173.size)],
        previousRevision = LOAD_PREV_REV_POOL173[rnd.nextInt(LOAD_PREV_REV_POOL173.size)],
        currentUserId = LOAD_CURRENT_USER_POOL173[rnd.nextInt(LOAD_CURRENT_USER_POOL173.size)],
        fromCache = rnd.nextBoolean()
    )
}

internal fun randomFailureCase173(rnd: Random): StateCase173 {
    val base = sentinelState173()
    return base.copy(
        inputText = listOf("SENTINEL-IN", "", "draft", "a b")[rnd.nextInt(4)],
        currentUserId = LOAD_CURRENT_USER_POOL173[rnd.nextInt(LOAD_CURRENT_USER_POOL173.size)],
        isLoading = rnd.nextBoolean(),
        initialLoadError = listOf<String?>(null, "OLD-ERR", "")[rnd.nextInt(3)]
    )
}

internal val FORMAT_GROUP_NAME173: () -> String = { "[G]" }
internal val FORMAT_MEMBER_COUNT173: (Int) -> String = { "N:$it" }
internal val FORMAT_REVISION_WARNING173: () -> String = { "[W]" }
internal val FORMAT_ERROR173: () -> String = { "[E]" }

class ChatDetailLoadCoordinatorFuzzTest {

    private fun checkPlan173(c: LoadCase173) {
        val input = buildLoadInput173(c)
        val state = buildState173(c.state)
        val oracle = planChatLoadOracle173(
            input, state, FORMAT_GROUP_NAME173, FORMAT_MEMBER_COUNT173, FORMAT_REVISION_WARNING173
        )
        val prod = planChatLoadProduction173(
            input, state, FORMAT_GROUP_NAME173, FORMAT_MEMBER_COUNT173, FORMAT_REVISION_WARNING173
        )
        assertEquals(
            planProjectionJson173(oracle), planProjectionJson173(prod), "plan($c)"
        )
    }

    private fun checkFailure173(c: StateCase173) {
        val state = buildState173(c)
        val oracle = planLoadFailureOracle173(state, FORMAT_ERROR173)
        val prod = planLoadFailureProduction173(state, FORMAT_ERROR173)
        assertEquals(
            failureProjectionJson173(oracle), failureProjectionJson173(prod), "failure($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        val me = userLoad173("me", "Me", null, "")
        val bob = userLoad173("u2", "Bob", "av2", "online")
        val bot = userLoad173("bot_helper", "Helper", null, "")
        // Group revision strictly rises: invalidate + warning.
        val rising = LoadCase173(
            id = "g1", isGroup = true, chatType = "GROUP", groupName = "Family",
            groupAvatar = "av", memberRevision = 5L, disappearingMessageSeconds = 60,
            participants = listOf(me, bob), previousRevision = 4L,
            currentUserId = "me", fromCache = false
        )
        checkPlan173(rising)
        val risingPlan = planChatLoadProduction173(
            buildLoadInput173(rising), buildState173(rising.state),
            FORMAT_GROUP_NAME173, FORMAT_MEMBER_COUNT173, FORMAT_REVISION_WARNING173
        )
        assertEquals(true, risingPlan.shouldInvalidateSenderKey)
        assertEquals("[W]", risingPlan.revisionWarning)
        assertEquals(
            listOf(
                ChatLoadEffect.OccupySessionCipher("g1", null),
                ChatLoadEffect.LoadGroupCandidates,
                ChatLoadEffect.RefreshBotCommands("g1")
            ),
            risingPlan.effects
        )
        // Equal, falling and first-seen revisions never invalidate.
        for (prev in listOf(5L, 6L, null)) {
            checkPlan173(rising.copy(previousRevision = prev))
            val plan = planChatLoadProduction173(
                buildLoadInput173(rising.copy(previousRevision = prev)),
                buildState173(sentinelState173()),
                FORMAT_GROUP_NAME173, FORMAT_MEMBER_COUNT173, FORMAT_REVISION_WARNING173
            )
            assertEquals(false, plan.shouldInvalidateSenderKey, "prev=$prev")
            assertEquals(null, plan.revisionWarning, "prev=$prev")
        }
        // A blank group name stays blank: only a null name takes the
        // placeholder, and the member count is the participant count.
        checkPlan173(rising.copy(groupName = "", participants = listOf(me, bob, bot)))
        val blankNamePlan = planChatLoadProduction173(
            buildLoadInput173(rising.copy(groupName = "", participants = listOf(me, bob, bot))),
            buildState173(sentinelState173()),
            FORMAT_GROUP_NAME173, FORMAT_MEMBER_COUNT173, FORMAT_REVISION_WARNING173
        )
        assertEquals("", blankNamePlan.nextState.contact.name)
        assertEquals("N:3", blankNamePlan.nextState.contact.status)
        checkPlan173(rising.copy(groupName = null))
        val nullNamePlan = planChatLoadProduction173(
            buildLoadInput173(rising.copy(groupName = null)),
            buildState173(sentinelState173()),
            FORMAT_GROUP_NAME173, FORMAT_MEMBER_COUNT173, FORMAT_REVISION_WARNING173
        )
        assertEquals("[G]", nullNamePlan.nextState.contact.name)
        // Direct chat with a human peer: identity safety, not bot commands.
        val direct = LoadCase173(
            id = "c9", isGroup = false, chatType = "DIRECT", groupName = null,
            groupAvatar = null, memberRevision = 3L, disappearingMessageSeconds = 0,
            participants = listOf(me, bob), previousRevision = 2L,
            currentUserId = "me", fromCache = true
        )
        checkPlan173(direct)
        val directPlan = planChatLoadProduction173(
            buildLoadInput173(direct), buildState173(direct.state),
            FORMAT_GROUP_NAME173, FORMAT_MEMBER_COUNT173, FORMAT_REVISION_WARNING173
        )
        assertEquals(false, directPlan.shouldInvalidateSenderKey)
        assertEquals(
            listOf(
                ChatLoadEffect.OccupySessionCipher("c9", "u2"),
                ChatLoadEffect.RefreshBlockState("u2"),
                ChatLoadEffect.RefreshScheduledMessages,
                ChatLoadEffect.RefreshIdentitySafety("u2")
            ),
            directPlan.effects
        )
        // A bot peer swaps identity safety for bot commands.
        checkPlan173(direct.copy(participants = listOf(me, bot)))
        // Only "bot_" counts: an id of just "bot" is a human peer.
        checkPlan173(direct.copy(participants = listOf(me, userLoad173("bot", "Bot", null, ""))))
        // SECRET direct chat marks the secret flag.
        checkPlan173(direct.copy(chatType = "SECRET"))
        // No peer at all: no effects, and the secret flag, contact
        // and disappearing seconds keep their sentinel values.
        val noPeer = direct.copy(participants = listOf(me), chatType = "DIRECT")
        checkPlan173(noPeer)
        val noPeerPlan = planChatLoadProduction173(
            buildLoadInput173(noPeer), buildState173(noPeer.state),
            FORMAT_GROUP_NAME173, FORMAT_MEMBER_COUNT173, FORMAT_REVISION_WARNING173
        )
        assertEquals(emptyList(), noPeerPlan.effects)
        assertEquals(true, noPeerPlan.nextState.isSecretChat)
        assertEquals("sentinel-contact", noPeerPlan.nextState.contact.id)
        assertEquals(999, noPeerPlan.nextState.disappearingMessageSeconds)
        // planLoadFailure: loading off, error in, sentinels untouched.
        checkFailure173(sentinelState173())
        val failed = planLoadFailureProduction173(buildState173(sentinelState173()), FORMAT_ERROR173)
        assertEquals(false, failed.isLoading)
        assertEquals("[E]", failed.initialLoadError)
        assertEquals("SENTINEL-IN", failed.inputText)
        assertEquals(true, failed.isRecording)
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(173002)
        repeat(4000) { i ->
            checkPlan173(randomLoadCase173(rnd))
            if (i % 10 == 0) checkFailure173(randomFailureCase173(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        val me = userLoad173("me", "Me", null, "")
        val bob = userLoad173("u2", "Bob", "av2", "online")
        val peers = listOf(me, bob)
        // Revision boundaries crossed with group flag and chat type.
        for (isGroup in listOf(false, true)) {
            for (chatType in LOAD_CHATTYPE_POOL173) {
                for (prev in LOAD_PREV_REV_POOL173) {
                    for (member in LOAD_MEMBER_REV_POOL173) {
                        checkPlan173(
                            LoadCase173(
                                id = "c9", isGroup = isGroup, chatType = chatType,
                                groupName = "Family", groupAvatar = "av",
                                memberRevision = member, disappearingMessageSeconds = 60,
                                participants = peers, previousRevision = prev,
                                currentUserId = "me", fromCache = false
                            )
                        )
                    }
                }
            }
        }
        // Peer shapes crossed with cache flag and group name.
        for (participants in LOAD_PARTICIPANT_SHAPES173) {
            for (fromCache in listOf(false, true)) {
                for (groupName in LOAD_GROUP_NAME_POOL173) {
                    checkPlan173(
                        LoadCase173(
                            id = "c9", isGroup = false, chatType = "DIRECT",
                            groupName = groupName, groupAvatar = null,
                            memberRevision = 0L, disappearingMessageSeconds = 0,
                            participants = participants, previousRevision = null,
                            currentUserId = "me", fromCache = fromCache
                        )
                    )
                }
            }
        }
        // Failure sweep: every sentinel input text / user / error.
        for (inputText in listOf("SENTINEL-IN", "", "draft")) {
            for (currentUserId in LOAD_CURRENT_USER_POOL173) {
                for (initialLoadError in listOf<String?>(null, "OLD-ERR", "")) {
                    checkFailure173(
                        sentinelState173().copy(
                            inputText = inputText,
                            currentUserId = currentUserId,
                            initialLoadError = initialLoadError
                        )
                    )
                }
            }
        }
    }
}
