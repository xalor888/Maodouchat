package com.maodouchat.server.plugins

import com.maodouchat.server.model.CreateReportRequest
import com.maodouchat.server.model.PreKeyData
import com.maodouchat.server.model.RegisterPushTokenRequest
import com.maodouchat.server.model.SendFriendRequestBody
import com.maodouchat.server.model.SendSignalRequest
import com.maodouchat.server.model.UpdateProfileRequest
import com.maodouchat.server.model.UploadKeysRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoutingModelsFuzzTest {

    private companion object {
        /** 被 fuzz 的六个 DTO 的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "deviceId", "token", "platform", "timezoneOffsetMinutes",
            "name", "status",
            "toUserId", "message",
            "registrationId", "deviceName", "identityKey", "signedPreKeyId",
            "signedPreKey", "signedPreKeySignature", "preKeys", "keyId", "publicKey",
            "type", "payload", "callId", "groupId", "groupMemberIds", "groupInvite",
            "epoch", "sequence", "idempotencyKey",
            "targetType", "targetId", "chatId", "messageId", "reason", "description",
        )
        private const val PUSH_ITERATIONS = 150
        private const val PROFILE_ITERATIONS = 100
        private const val FRIEND_ITERATIONS = 100
        private const val KEYS_ITERATIONS = 100
        private const val SIGNAL_ITERATIONS = 150
        private const val REPORT_ITERATIONS = 100
    }

    private fun randomFieldName(random: Random): String {
        val stems = listOf(
            "future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp",
            "clientExt", "appExt", "exp", "flag",
        )
        var name = "${stems.random(random)}_${random.nextInt(10000)}"
        while (name in KNOWN_FIELD_NAMES) name = "z$name"
        return name
    }

    private fun randomJsonValue(random: Random, depth: Int): JsonElement {
        val leafKinds = 5 // bool / long / double / string / null
        return when (random.nextInt(if (depth <= 0) leafKinds else leafKinds + 2)) {
            0 -> JsonPrimitive(random.nextBoolean())
            1 -> JsonPrimitive(random.nextLong())
            2 -> JsonPrimitive(random.nextDouble())
            3 -> JsonPrimitive("str_${random.nextInt(100000)}_${random.nextLong()}")
            4 -> JsonNull
            5 -> JsonArray(List(random.nextInt(1, 4)) { randomJsonValue(random, depth - 1) })
            else -> JsonObject(
                (0 until random.nextInt(1, 4))
                    .associate { randomFieldName(random) to randomJsonValue(random, depth - 1) }
            )
        }
    }

    /** 顶层注入 1–5 个未知字段，返回 fuzz 后的 JSON 字符串。 */
    private fun injectUnknownFields(base: JsonObject, random: Random): String {
        val fields = base.toMutableMap()
        repeat(random.nextInt(1, 6)) {
            fields[randomFieldName(random)] = randomJsonValue(random, 2)
        }
        return routingJson.encodeToString(JsonElement.serializer(), JsonObject(fields))
    }

    private inline fun <reified T> fuzzedPayload(expected: T, random: Random): Pair<String, T> {
        val base = routingJson.parseToJsonElement(routingJson.encodeToString(expected)).jsonObject
        return injectUnknownFields(base, random) to expected
    }

    @Test
    fun `register push token request survives seeded unknown-field fuzz`() {
        val random = Random(0xB001_2026)
        repeat(PUSH_ITERATIONS) { i ->
            val expected = RegisterPushTokenRequest(
                deviceId = "dev-$i",
                token = "fcm-token-$i",
                platform = if (i % 2 == 0) "ANDROID" else "IOS",
                timezoneOffsetMinutes = if (i % 3 == 0) 480 else 0,
            )
            val (payload, _) = fuzzedPayload(expected, random)
            val decoded = routingJson.decodeFromString<RegisterPushTokenRequest>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：已知字段必须与注入前完全一致")
        }
    }

    @Test
    fun `update profile request survives seeded unknown-field fuzz`() {
        val random = Random(0xB002_2026)
        repeat(PROFILE_ITERATIONS) { i ->
            val expected = UpdateProfileRequest(
                name = if (i % 2 == 0) "名字 $i" else null,
                status = if (i % 3 == 0) "签名 $i" else null,
            )
            val (payload, _) = fuzzedPayload(expected, random)
            val decoded = routingJson.decodeFromString<UpdateProfileRequest>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：已知字段必须与注入前完全一致")
        }
        // 全缺省：空对象必须解出全 null 默认值
        val decoded = routingJson.decodeFromString<UpdateProfileRequest>("{}")
        assertEquals(UpdateProfileRequest(name = null, status = null), decoded, "空对象应解出全 null 默认值")
    }

    @Test
    fun `send friend request body survives seeded unknown-field fuzz`() {
        val random = Random(0xB003_2026)
        repeat(FRIEND_ITERATIONS) { i ->
            val expected = SendFriendRequestBody(
                toUserId = "u-$i",
                message = if (i % 2 == 0) "你好 $i" else "",
            )
            val (payload, _) = fuzzedPayload(expected, random)
            val decoded = routingJson.decodeFromString<SendFriendRequestBody>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：已知字段必须与注入前完全一致")
        }
    }

    @Test
    fun `upload keys request survives seeded unknown-field fuzz`() {
        val random = Random(0xB004_2026)
        repeat(KEYS_ITERATIONS) { i ->
            val expected = UploadKeysRequest(
                registrationId = 1000 + i,
                deviceId = if (i % 2 == 0) 1 else 2,
                deviceName = if (i % 3 == 0) "Pixel $i" else null,
                identityKey = "ikey-$i",
                signedPreKeyId = 10 + i,
                signedPreKey = "spk-$i",
                signedPreKeySignature = "sig-$i",
                preKeys = List(1 + i % 3) { k -> PreKeyData(keyId = k, publicKey = "opk-$i-$k") },
            )
            val (payload, _) = fuzzedPayload(expected, random)
            val decoded = routingJson.decodeFromString<UploadKeysRequest>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：密钥材料与嵌套 preKeys 必须与注入前完全一致")
        }
    }

    @Test
    fun `send signal request survives seeded unknown-field fuzz`() {
        val random = Random(0xB005_2026)
        repeat(SIGNAL_ITERATIONS) { i ->
            val expected = SendSignalRequest(
                toUserId = "u-$i",
                type = if (i % 2 == 0) "offer" else "answer",
                payload = "sdp-$i",
                callId = "call-$i",
                groupId = if (i % 3 == 0) "g-$i" else "",
                groupMemberIds = if (i % 4 == 0) listOf("m1-$i", "m2-$i") else emptyList(),
                groupInvite = i % 5 == 0,
                epoch = 1727000000000L + i,
                sequence = i.toLong(),
                idempotencyKey = if (i % 2 == 0) "idem-$i" else "",
            )
            val (payload, _) = fuzzedPayload(expected, random)
            val decoded = routingJson.decodeFromString<SendSignalRequest>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：信令字段必须与注入前完全一致")
        }
        // 最小四字段：其余必须回默认值
        val decoded = routingJson.decodeFromString<SendSignalRequest>(
            """{"toUserId":"u","type":"offer","payload":"p","callId":"c"}"""
        )
        assertEquals(
            SendSignalRequest(toUserId = "u", type = "offer", payload = "p", callId = "c"),
            decoded,
            "缺省可选字段应回默认值",
        )
    }

    @Test
    fun `create report request survives seeded unknown-field fuzz`() {
        val random = Random(0xB006_2026)
        repeat(REPORT_ITERATIONS) { i ->
            val expected = CreateReportRequest(
                targetType = if (i % 2 == 0) "USER" else "MESSAGE",
                targetId = "t-$i",
                chatId = if (i % 3 == 0) "c-$i" else null,
                messageId = if (i % 4 == 0) "m-$i" else null,
                reason = "spam",
                description = if (i % 5 == 0) "描述 $i" else null,
            )
            val (payload, _) = fuzzedPayload(expected, random)
            val decoded = routingJson.decodeFromString<CreateReportRequest>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：已知字段必须与注入前完全一致")
        }
    }

    @Test
    fun `wrong-typed known fields still fail loudly`() {
        // registrationId 给字符串：密钥上传入口 Int 字段收字符串必须抛
        assertFailsWith<SerializationException>("registrationId 类型错必须大声失败") {
            routingJson.decodeFromString<UploadKeysRequest>(
                """{"registrationId":"abc","identityKey":"k","signedPreKeyId":1,"signedPreKey":"s","signedPreKeySignature":"g","preKeys":[]}"""
            )
        }
        // preKeys 给对象：List<PreKeyData> 字段收对象必须抛
        assertFailsWith<SerializationException>("preKeys 类型错必须大声失败") {
            routingJson.decodeFromString<UploadKeysRequest>(
                """{"registrationId":1,"identityKey":"k","signedPreKeyId":1,"signedPreKey":"s","signedPreKeySignature":"g","preKeys":{"keyId":1}}"""
            )
        }
        // groupMemberIds 给字符串：List<String> 字段收字符串必须抛
        assertFailsWith<SerializationException>("groupMemberIds 类型错必须大声失败") {
            routingJson.decodeFromString<SendSignalRequest>(
                """{"toUserId":"u","type":"offer","payload":"p","callId":"c","groupMemberIds":"m1,m2"}"""
            )
        }
        // timezoneOffsetMinutes 给对象：Int 字段收对象必须抛
        assertFailsWith<SerializationException>("timezoneOffsetMinutes 类型错必须大声失败") {
            routingJson.decodeFromString<RegisterPushTokenRequest>(
                """{"deviceId":"d","token":"t","timezoneOffsetMinutes":{"v":480}}"""
            )
        }
    }

    @Test
    fun `near-miss field names are treated as unknown keys`() {
        // 大小写/前后缀只差一点的字段名必须按未知键忽略，不能误读进已知字段
        val decoded = routingJson.decodeFromString<RegisterPushTokenRequest>(
            """{"DeviceId":"WRONG","deviceId":"d1","token":"t1","platform2":"x","platform":"IOS"}"""
        )
        assertEquals(
            RegisterPushTokenRequest(deviceId = "d1", token = "t1", platform = "IOS"),
            decoded,
            "近似字段名必须被忽略，只有精确命中的字段生效",
        )
    }
}
