package com.maodouchat.server.plugins

import com.maodouchat.server.repository.GroupMemberMutationResult
import com.maodouchat.server.service.IdentitySecurityEventPolicy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// mismatchDetail restated by hand: a fixed template over the device id
// and the auth session id.
internal fun mismatchDetailOracle178(deviceId: Int, authSessionId: String): String =
    "deviceId=$deviceId session=$authSessionId"

// publishedDetail restated by hand: a fixed template over the device id.
internal fun publishedDetailOracle178(deviceId: Int): String =
    "deviceId=$deviceId"

// shouldRevokeAuthSessionOnMismatch restated by hand: a mismatch always
// revokes the current auth session.
internal fun shouldRevokeAuthSessionOnMismatchOracle178(): Boolean = true

// GroupMutationHttpPolicy.response restated by hand: the domain outcome
// maps to an HTTP status plus the error body, or null when the mutation
// already succeeded. The triple is (status value, error text, code).
internal fun groupMutationResponseOracle178(
    result: GroupMemberMutationResult,
): Triple<Int, String, String>? = when (result) {
    GroupMemberMutationResult.UPDATED -> null
    GroupMemberMutationResult.CHAT_NOT_FOUND,
    GroupMemberMutationResult.NOT_GROUP ->
        Triple(404, "群聊不存在", "GROUP_NOT_FOUND")
    GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT ->
        Triple(403, "操作者已不是群成员", "GROUP_ACTOR_NOT_MEMBER")
    GroupMemberMutationResult.TARGET_NOT_PARTICIPANT ->
        Triple(404, "该用户不是群成员", "GROUP_TARGET_NOT_MEMBER")
    GroupMemberMutationResult.FORBIDDEN ->
        Triple(403, "当前角色无权执行该操作", "GROUP_PERMISSION_DENIED")
    GroupMemberMutationResult.SELF_NOT_ALLOWED ->
        Triple(400, "不能对自己执行该操作", "GROUP_SELF_ACTION_NOT_ALLOWED")
    GroupMemberMutationResult.OWNER_PROTECTED ->
        Triple(403, "不能对群主执行该操作", "GROUP_OWNER_PROTECTED")
    GroupMemberMutationResult.PEER_ADMIN_PROTECTED ->
        Triple(403, "管理员不能操作其他管理员", "GROUP_ADMIN_PEER_PROTECTED")
    GroupMemberMutationResult.MEMBER_LIMIT_EXCEEDED ->
        Triple(409, "群成员数量已达上限", "GROUP_MEMBER_LIMIT_EXCEEDED")
    GroupMemberMutationResult.USER_NOT_FOUND ->
        Triple(404, "用户不存在", "GROUP_USER_NOT_FOUND")
    GroupMemberMutationResult.BLOCKED ->
        Triple(403, "无法添加已屏蔽的用户", "GROUP_MEMBER_BLOCKED")
}

// Production mismatchDetail formats the mismatch audit detail.
internal fun mismatchDetailProduction178(deviceId: Int, authSessionId: String): String =
    IdentitySecurityEventPolicy.mismatchDetail(deviceId, authSessionId)

// Production publishedDetail formats the publish audit detail.
internal fun publishedDetailProduction178(deviceId: Int): String =
    IdentitySecurityEventPolicy.publishedDetail(deviceId)

// Production shouldRevokeAuthSessionOnMismatch decides the revoke.
internal fun shouldRevokeAuthSessionOnMismatchProduction178(): Boolean =
    IdentitySecurityEventPolicy.shouldRevokeAuthSessionOnMismatch()

// Production GroupMutationHttpPolicy.response maps the domain outcome.
internal fun groupMutationResponseProduction178(
    result: GroupMemberMutationResult,
): Triple<Int, String, String>? =
    GroupMutationHttpPolicy.response(result)?.let {
        Triple(it.first.value, it.second.error, it.second.code ?: "")
    }

// ---------- generators ----------

internal data class ServerResponseCase178(
    val deviceId: Int,
    val authSessionId: String,
    val result: String,
)

internal val RESPONSE_DEVICE_POOL178: List<Int> = listOf(
    Int.MIN_VALUE, Int.MIN_VALUE + 1, -100, -1, 0, 1, 2, 100,
    Int.MAX_VALUE - 1, Int.MAX_VALUE,
)

internal val RESPONSE_SESSION_POOL178: List<String> = listOf(
    "", " ", "sess-1", "auth-session-abcdef", "a".repeat(100),
    "sess with spaces", "s\u00e9ss", "x\ny", "  sess  ", "\u00a0sess\u00a0",
    "sess=1&x=2", "a\uD83D\uDE00b",
)

internal val RESPONSE_RESULT_POOL178: List<String> = listOf(
    "UPDATED", "CHAT_NOT_FOUND", "NOT_GROUP", "ACTOR_NOT_PARTICIPANT",
    "TARGET_NOT_PARTICIPANT", "FORBIDDEN", "SELF_NOT_ALLOWED",
    "OWNER_PROTECTED", "PEER_ADMIN_PROTECTED", "MEMBER_LIMIT_EXCEEDED",
    "USER_NOT_FOUND", "BLOCKED",
)

internal fun randomServerResponseCase178(rnd: Random): ServerResponseCase178 {
    fun randomSession(): String {
        if (rnd.nextInt(2) == 0) {
            return RESPONSE_SESSION_POOL178[rnd.nextInt(RESPONSE_SESSION_POOL178.size)]
        }
        val alphabet = "abcXYZ019 -_= \t\n\u00a0\u00e9"
        val len = rnd.nextInt(0, 40)
        val sb = StringBuilder()
        repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        return sb.toString()
    }
    return ServerResponseCase178(
        deviceId = if (rnd.nextInt(2) == 0) {
            RESPONSE_DEVICE_POOL178[rnd.nextInt(RESPONSE_DEVICE_POOL178.size)]
        } else {
            rnd.nextInt()
        },
        authSessionId = randomSession(),
        result = RESPONSE_RESULT_POOL178[rnd.nextInt(RESPONSE_RESULT_POOL178.size)],
    )
}

internal fun escJsonResponse178(s: String): String {
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

internal fun serverResponseWireJson178(c: ServerResponseCase178): String =
    "{\"deviceId\":${c.deviceId}," +
        "\"authSessionId\":${escJsonResponse178(c.authSessionId)}," +
        "\"result\":${escJsonResponse178(c.result)}}"

internal fun serverResponseMutationJson178(
    mutation: Triple<Int, String, String>?,
): String =
    if (mutation == null) {
        "null"
    } else {
        "{\"status\":${mutation.first}," +
            "\"error\":${escJsonResponse178(mutation.second)}," +
            "\"code\":${escJsonResponse178(mutation.third)}}"
    }

internal fun serverResponseProjectionJson178(
    mismatch: String,
    published: String,
    revoke: Boolean,
    mutation: Triple<Int, String, String>?,
): String =
    "{\"mismatch\":${escJsonResponse178(mismatch)}," +
        "\"published\":${escJsonResponse178(published)}," +
        "\"revoke\":$revoke," +
        "\"mutation\":${serverResponseMutationJson178(mutation)}}"

class ServerResponseDetailFuzzTest {

    private fun checkServerResponse178(c: ServerResponseCase178) {
        val result = GroupMemberMutationResult.valueOf(c.result)
        assertEquals(
            serverResponseProjectionJson178(
                mismatchDetailOracle178(c.deviceId, c.authSessionId),
                publishedDetailOracle178(c.deviceId),
                shouldRevokeAuthSessionOnMismatchOracle178(),
                groupMutationResponseOracle178(result),
            ),
            serverResponseProjectionJson178(
                mismatchDetailProduction178(c.deviceId, c.authSessionId),
                publishedDetailProduction178(c.deviceId),
                shouldRevokeAuthSessionOnMismatchProduction178(),
                groupMutationResponseProduction178(result),
            ),
            "response($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Every device id against every session shape and every one of
        // the twelve mutation outcomes.
        for (deviceId in RESPONSE_DEVICE_POOL178) {
            for (session in RESPONSE_SESSION_POOL178) {
                for (result in RESPONSE_RESULT_POOL178) {
                    checkServerResponse178(
                        ServerResponseCase178(deviceId, session, result)
                    )
                }
            }
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(178003)
        repeat(4000) { checkServerResponse178(randomServerResponseCase178(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Device ids -100..100 plus the Int edges, session lengths
        // 0..200, every outcome at each point.
        for (deviceId in -100..100) {
            checkServerResponse178(
                ServerResponseCase178(deviceId, "sess-1", "FORBIDDEN")
            )
        }
        for (len in 0..200) {
            checkServerResponse178(
                ServerResponseCase178(7, "s".repeat(len), "MEMBER_LIMIT_EXCEEDED")
            )
        }
        for (deviceId in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
            for (result in RESPONSE_RESULT_POOL178) {
                checkServerResponse178(
                    ServerResponseCase178(deviceId, "sess-1", result)
                )
            }
        }
    }
}
