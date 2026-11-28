package com.maodouchat.domain.calls

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.IceConfigDto
import com.maodouchat.core.model.IceServerDto
import com.maodouchat.core.model.SendSignalRequest
import com.maodouchat.core.model.SignalMessageDto
import com.maodouchat.core.model.WsMessage
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebRTCSignalingMappingFuzzTest {

    private val random = Random(0xCA11_2026L)
    private val json = Json { ignoreUnknownKeys = true }

    private fun randomString(): String {
        val pool = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(
            ' ', '_', '-', '.', '/', ':', '"', '\\', '\n', '\t', 'é', '中', '文',
        )
        return (0 until random.nextInt(0, 16)).map { pool.random(random) }.joinToString("")
    }

    private fun randomLong(): Long =
        when (random.nextInt(5)) {
            0 -> 0L
            1 -> Long.MAX_VALUE
            2 -> Long.MIN_VALUE
            3 -> random.nextLong(-1000, 1000)
            else -> random.nextLong()
        }

    private fun randomSignalRequest(): SendSignalRequest =
        SendSignalRequest(
            toUserId = randomString(),
            type = randomString(),
            payload = randomString(),
            callId = randomString(),
            groupId = randomString(),
            groupMemberIds = List(random.nextInt(0, 4)) { randomString() },
            groupInvite = random.nextBoolean(),
            epoch = randomLong(),
            sequence = randomLong(),
            idempotencyKey = randomString(),
        )

    private fun randomDto(): SignalMessageDto =
        SignalMessageDto(
            id = randomString(),
            fromUserId = randomString(),
            type = randomString(),
            payload = randomString(),
            timestamp = randomLong(),
            callId = randomString(),
            groupId = randomString(),
            groupMemberIds = List(random.nextInt(0, 4)) { randomString() },
            groupInvite = random.nextBoolean(),
            epoch = randomLong(),
            sequence = randomLong(),
            idempotencyKey = randomString(),
        )

    private fun randomUrl(): String {
        val scheme = listOf(
            "stun:", "turn:", "turns:", "http:", "https:", "ws:", "wss:",
            "STUN:", "Turn:", "TURNS:", "stun :", "stun", "",
        ).random(random)
        return scheme + randomString()
    }

    private fun randomIceConfig(): IceConfigDto =
        IceConfigDto(
            iceServers = List(random.nextInt(0, 4)) {
                IceServerDto(
                    urls = List(random.nextInt(0, 5)) { randomUrl() },
                    username = randomString(),
                    credential = randomString(),
                )
            },
        )

    // mapIceServers 的逐字复述：只留 stun:/turn:/turns: 前缀（大小写敏感），
    // 滤空的服务器整条丢弃；无可用服务器时报 FETCH 失败。
    private fun oracleIceServers(config: IceConfigDto): Result<List<CallIceServer>> {
        val servers = config.iceServers.mapNotNull { server ->
            val urls = server.urls.filter {
                it.startsWith("stun:") || it.startsWith("turn:") || it.startsWith("turns:")
            }
            if (urls.isEmpty()) null else CallIceServer(urls, server.username, server.credential)
        }
        return if (servers.isEmpty()) Result.failure(SignalingException(FailureOperation.FETCH))
        else Result.success(servers)
    }

    @Test
    fun `buildSignalPayload round-trips random fields`() {
        repeat(200) {
            val request = randomSignalRequest()
            val raw = buildSignalPayload(
                request.toUserId, request.type, request.payload, request.callId,
                request.groupId, request.groupMemberIds, request.groupInvite,
                request.epoch, request.sequence, request.idempotencyKey,
            )
            val envelope = json.decodeFromString(WsMessage.serializer(), raw)
            assertEquals("SIGNALING", envelope.type)
            val inner = json.decodeFromString(SendSignalRequest.serializer(), envelope.payload)
            assertEquals(request, inner, "request=$request")
        }
    }

    @Test
    fun `mapPendingSignals copies every field over random dtos`() {
        repeat(200) {
            val dtos = List(random.nextInt(0, 4)) { randomDto() }
            val expected = dtos.map {
                SignalMessage(
                    id = it.id, fromUserId = it.fromUserId, type = it.type,
                    payload = it.payload, timestamp = it.timestamp, callId = it.callId,
                    groupId = it.groupId, groupMemberIds = it.groupMemberIds,
                    groupInvite = it.groupInvite, epoch = it.epoch, sequence = it.sequence,
                    idempotencyKey = it.idempotencyKey,
                )
            }
            assertEquals(expected, mapPendingSignals(dtos))
        }
    }

    @Test
    fun `mapIceServers matches the filter oracle over random configs`() {
        repeat(300) {
            val config = randomIceConfig()
            val expected = oracleIceServers(config)
            val actual = mapIceServers(config)
            assertEquals(expected.isSuccess, actual.isSuccess, "config=$config")
            if (expected.isSuccess) {
                assertEquals(expected.getOrThrow(), actual.getOrThrow(), "config=$config")
            } else {
                val err = assertFailsWith<SignalingException> { actual.getOrThrow() }
                assertEquals(FailureOperation.FETCH, err.operation)
            }
        }
    }

    @Test
    fun `ice url scheme match is case sensitive`() {
        val kept = mapIceServers(
            IceConfigDto(
                iceServers = listOf(
                    IceServerDto(urls = listOf("stun:a", "turn:b", "turns:c")),
                ),
            ),
        )
        assertTrue(kept.isSuccess)
        assertEquals(
            listOf(CallIceServer(listOf("stun:a", "turn:b", "turns:c"))),
            kept.getOrThrow(),
        )
        val dropped = mapIceServers(
            IceConfigDto(
                iceServers = listOf(
                    IceServerDto(urls = listOf("STUN:a", "Turn:b", "TURNS:c", "stun :d")),
                ),
            ),
        )
        assertTrue(dropped.isFailure)
    }

    @Test
    fun `mapFetchFailure maps api errors and passes others through`() {
        repeat(200) {
            val kind = ApiFailureKind.entries.random(random)
            val status = if (random.nextBoolean()) null else random.nextInt(-1, 600)
            val code = if (random.nextBoolean()) null else randomString()
            val apiErr = ApiException(kind, statusCode = status, serverCode = code)
            val mapped = mapFetchFailure(apiErr)
            assertTrue(mapped is SignalingException, "kind=$kind")
            mapped as SignalingException
            assertEquals(FailureOperation.FETCH, mapped.operation)
            assertEquals(status, mapped.statusCode)
            assertEquals(code, mapped.code)

            val plain = when (random.nextInt(3)) {
                0 -> RuntimeException(randomString())
                1 -> IllegalStateException(randomString())
                else -> SignalingException(FailureOperation.SEND)
            }
            assertSame(plain, mapFetchFailure(plain))
        }
    }

    @Test
    fun `mapSignalingResult follows the mapping table over random failures`() {
        repeat(200) {
            val operation = FailureOperation.entries.random(random)
            val ok = Result.success(Unit)
            assertEquals(ok, mapSignalingResult(ok, operation))

            val signaling = SignalingException(FailureOperation.entries.random(random))
            val kept = mapSignalingResult(Result.failure(signaling), operation)
            assertSame(signaling, kept.exceptionOrNull())

            val kind = ApiFailureKind.entries.random(random)
            val status = if (random.nextBoolean()) null else random.nextInt(-1, 600)
            val code = if (random.nextBoolean()) null else randomString()
            val apiErr = ApiException(kind, statusCode = status, serverCode = code)
            val mapped = mapSignalingResult(Result.failure(apiErr), operation)
            if (kind == ApiFailureKind.HTTP) {
                val err = assertFailsWith<SignalingException> { mapped.getOrThrow() }
                assertEquals(operation, err.operation)
                assertEquals(status, err.statusCode)
                assertEquals(code, err.code)
            } else {
                assertSame(apiErr, mapped.exceptionOrNull())
            }

            val plain = RuntimeException(randomString())
            assertSame(plain, mapSignalingResult(Result.failure(plain), operation).exceptionOrNull())
        }
    }
}
