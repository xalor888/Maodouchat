package com.maodouchat.domain.messaging

import com.maodouchat.core.model.User
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// resolve restated by hand: a non-blank group name wins outright,
// otherwise the first non-owner id in the comma list is looked up
// through the peer-name callback; anything missing yields "".
internal fun resolveChatNameOracle179(
    groupName: String?,
    participantIds: String,
    ownerUserId: String,
    peers: Map<String, String?>,
): String {
    groupName?.takeIf { it.isNotBlank() }?.let { return it }
    val peerId = participantIds.split(",").map { it.trim() }
        .filter { it.isNotBlank() && it != ownerUserId }
        .firstOrNull() ?: return ""
    return peers[peerId] ?: ""
}

// displayName restated by hand: a non-blank nickname wins, otherwise
// the name as-is (even when blank — unlike User.displayName, which
// would fall back to the id).
internal fun displayNameOracle179(user: User): String =
    user.nickname?.takeIf { it.isNotBlank() } ?: user.name

// Production resolve runs the real policy with a lookup-backed
// peer-name callback.
internal fun resolveChatNameProduction179(
    groupName: String?,
    participantIds: String,
    ownerUserId: String,
    peers: Map<String, String?>,
): String = runBlocking {
    ChatNameResolvePolicy.resolve(groupName, participantIds, ownerUserId) { peers[it] }
}

// Production displayName runs the real policy on the same user.
internal fun displayNameProduction179(user: User): String =
    ChatNameResolvePolicy.displayName(user)

// ---------- generators ----------

internal data class ChatNameCase179(
    val groupName: String?,
    val participantIds: String,
    val ownerUserId: String,
    val peers: Map<String, String?>,
    val userId: String,
    val userName: String,
    val userNickname: String?,
)

internal val CHATNAME_GROUP_POOL179: List<String?> = listOf(
    null, "", " ", "  ", "Family", "  Family  ", "a".repeat(100),
    "x\ny", "u\u00e9", "  \u00a0  ", "All Hands", "a\uD83D\uDE00b",
)

internal val CHATNAME_IDS_POOL179: List<String> = listOf(
    "", " ", ",", ",,", "u1", "u1,u2", "u1, u2", "u1 ,u2",
    "u1,u1", "owner", "owner,u1", "u1,owner", "u1,owner,u2",
    " , u1 , ", "u1,,u2", "u1,u2,u3", "  ", "u1\n,u2",
    "u\u00e9,u2", "a".repeat(80) + ",u2",
)

internal val CHATNAME_OWNER_POOL179: List<String> = listOf(
    "", " ", "owner", "u1", "  owner  ", "u\u00e9", "nobody",
)

internal val CHATNAME_NAME_POOL179: List<String?> = listOf(
    null, "", " ", "  ", "Alice", "  Alice  ", "Bob", "a".repeat(60),
    "x\ny", "u\u00e9", "\u00a0", "a\uD83D\uDE00b",
)

internal fun randomChatNameCase179(rnd: Random): ChatNameCase179 {
    fun randomId(): String {
        if (rnd.nextInt(2) == 0) {
            return listOf("u1", "u2", "u3", "owner", "", " ", "x").random(rnd)
        }
        val alphabet = "abc019 _,.-\t\n\u00a0\u00e9"
        val len = rnd.nextInt(0, 10)
        val sb = StringBuilder()
        repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        return sb.toString()
    }
    fun randomText(pool: List<String?>): String? {
        if (rnd.nextInt(2) == 0) return pool[rnd.nextInt(pool.size)]
        return randomId()
    }
    val ids = List(rnd.nextInt(0, 5)) { randomId() }
    val peers = HashMap<String, String?>()
    for (id in ids) {
        if (rnd.nextInt(3) == 0) continue
        peers[id] = if (rnd.nextInt(4) == 0) null else randomText(CHATNAME_NAME_POOL179)
    }
    return ChatNameCase179(
        groupName = randomText(CHATNAME_GROUP_POOL179),
        participantIds = ids.joinToString(","),
        ownerUserId = if (rnd.nextInt(2) == 0) {
            CHATNAME_OWNER_POOL179[rnd.nextInt(CHATNAME_OWNER_POOL179.size)]
        } else {
            randomId()
        },
        peers = peers,
        userId = randomId(),
        userName = randomText(CHATNAME_NAME_POOL179) ?: "",
        userNickname = randomText(CHATNAME_NAME_POOL179),
    )
}

internal fun escJsonChatName179(s: String): String {
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

internal fun escJsonOrNullChatName179(s: String?): String =
    if (s == null) "null" else escJsonChatName179(s)

internal fun chatNameWireJson179(c: ChatNameCase179): String {
    val peers = c.peers.entries.joinToString(",") { (k, v) ->
        escJsonChatName179(k) + ":" + escJsonOrNullChatName179(v)
    }
    return "{\"groupName\":${escJsonOrNullChatName179(c.groupName)}," +
        "\"participantIds\":${escJsonChatName179(c.participantIds)}," +
        "\"ownerUserId\":${escJsonChatName179(c.ownerUserId)}," +
        "\"peers\":{$peers}," +
        "\"userId\":${escJsonChatName179(c.userId)}," +
        "\"userName\":${escJsonChatName179(c.userName)}," +
        "\"userNickname\":${escJsonOrNullChatName179(c.userNickname)}}"
}

internal fun chatNameProjectionJson179(resolve: String, display: String): String =
    "{\"resolve\":${escJsonChatName179(resolve)}," +
        "\"display\":${escJsonChatName179(display)}}"

class ChatNameResolvePolicyFuzzTest {

    private fun checkChatName179(c: ChatNameCase179) {
        val user = User(id = c.userId, name = c.userName, nickname = c.userNickname)
        assertEquals(
            chatNameProjectionJson179(
                resolveChatNameOracle179(c.groupName, c.participantIds, c.ownerUserId, c.peers),
                displayNameOracle179(user),
            ),
            chatNameProjectionJson179(
                resolveChatNameProduction179(c.groupName, c.participantIds, c.ownerUserId, c.peers),
                displayNameProduction179(user),
            ),
            "chatName($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Every group name against every id list and every owner.
        for (group in CHATNAME_GROUP_POOL179) {
            for (ids in CHATNAME_IDS_POOL179) {
                for (owner in CHATNAME_OWNER_POOL179) {
                    checkChatName179(
                        ChatNameCase179(
                            groupName = group, participantIds = ids,
                            ownerUserId = owner,
                            peers = mapOf("u1" to "Alice", "u2" to null, "u3" to "  "),
                            userId = "u1", userName = "Bob", userNickname = "Bobby",
                        )
                    )
                }
            }
        }
        // Every nickname shape against every name shape.
        for (nickname in CHATNAME_NAME_POOL179) {
            for (name in CHATNAME_NAME_POOL179) {
                checkChatName179(
                    ChatNameCase179(
                        groupName = null, participantIds = "u1",
                        ownerUserId = "owner",
                        peers = mapOf("u1" to "Alice"),
                        userId = "u1", userName = name ?: "",
                        userNickname = nickname,
                    )
                )
            }
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(179002)
        repeat(4000) { checkChatName179(randomChatNameCase179(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Id-list lengths 0..40 with a fixed owner and a peer map that
        // names every id.
        for (n in 0..40) {
            val ids = List(n) { "u$it" }.joinToString(",")
            val peers = (0 until n).associate { "u$it" to "Name$it" }
            checkChatName179(
                ChatNameCase179(
                    groupName = null, participantIds = ids,
                    ownerUserId = "u0", peers = peers,
                    userId = "u1", userName = "Bob", userNickname = null,
                )
            )
        }
        // Group-name lengths 0..120 (blank, short, long).
        for (len in 0..120) {
            checkChatName179(
                ChatNameCase179(
                    groupName = "g".repeat(len), participantIds = "u1",
                    ownerUserId = "owner", peers = mapOf("u1" to "Alice"),
                    userId = "u1", userName = "", userNickname = "  ",
                )
            )
        }
        // Owner in every slot of a three-id list.
        for (slot in 0..3) {
            val parts = mutableListOf("u1", "u2", "u3")
            if (slot < 3) parts[slot] = "owner"
            checkChatName179(
                ChatNameCase179(
                    groupName = null, participantIds = parts.joinToString(","),
                    ownerUserId = "owner",
                    peers = mapOf("u1" to "Alice", "u2" to "Bob", "u3" to "Carol"),
                    userId = "u1", userName = "Bob", userNickname = "",
                )
            )
        }
    }
}
