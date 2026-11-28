package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageReactionJsonPolicyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private const val STRUCTURED_ITERATIONS = 150
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_- \"\\\n\t"
        private const val GARBAGE_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_:/?#[]{} \".\\,<>-_%+=&'\n\t@：中文"
        private val EMOJIS = listOf("👍", "❤️", "😀", "🎉", "🚀", "👏")
        private val KNOWN_FIELDS = setOf("userId", "emoji", "reactedAt")
        private val UNKNOWN_FIELD_NAMES = listOf("unknown", "zzz", "userId2", "Emoji", "reactedAt ", "USERID", "count")
    }

    private fun randomText(random: Random, alphabet: String, maxLength: Int, minLength: Int = 0): String {
        val length = random.nextInt(minLength, maxLength + 1)
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomReaction(random: Random): MessageReaction = MessageReaction(
        userId = randomText(random, ID_ALPHABET, 32, 1),
        emoji = EMOJIS[random.nextInt(EMOJIS.size)],
        reactedAt = random.nextLong()
    )

    private fun randomJsonScalar(random: Random) = when (random.nextInt(5)) {
        0 -> JsonPrimitive(random.nextBoolean())
        1 -> JsonPrimitive(random.nextInt(-1000, 1001))
        2 -> JsonPrimitive(random.nextDouble())
        3 -> JsonPrimitive(randomText(random, ID_ALPHABET, 12))
        else -> JsonNull
    }

    private fun randomJsonValue(random: Random, depth: Int): kotlinx.serialization.json.JsonElement {
        if (depth <= 0) return randomJsonScalar(random)
        return when (random.nextInt(6)) {
            0, 1 -> randomJsonScalar(random)
            2 -> JsonArray(List(random.nextInt(0, 4)) { randomJsonValue(random, depth - 1) })
            else -> JsonObject(
                (0 until random.nextInt(0, 4)).associate { "k$it" to randomJsonValue(random, depth - 1) }
            )
        }
    }

    @Test
    fun `random garbage never throws on decode`() {
        val random = Random(0x61_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val raw = randomText(random, GARBAGE_ALPHABET, 200)
            val result = MessageReactionJsonPolicy.decodeReactions(raw)
            assertTrue(result is List<MessageReaction>, "fuzz 迭代 #$i：解码垃圾串必须返回列表，raw=$raw")
            // 解码出的结果本身必须能再编码，不抛错。
            MessageReactionJsonPolicy.encodeReactions(result)
        }
    }

    @Test
    fun `random valid reactions round trip`() {
        val random = Random(0x62_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val reaction = randomReaction(random)
            val encoded = MessageReactionJsonPolicy.encodeReactions(listOf(reaction))
            assertEquals(
                listOf(reaction),
                MessageReactionJsonPolicy.decodeReactions(encoded),
                "fuzz 迭代 #$i：单条 reaction 往返必须恒等"
            )
        }
    }

    @Test
    fun `random reaction lists round trip`() {
        val random = Random(0x63_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val reactions = List(random.nextInt(0, 9)) { randomReaction(random) }
            val encoded = MessageReactionJsonPolicy.encodeReactions(reactions)
            assertEquals(
                reactions,
                MessageReactionJsonPolicy.decodeReactions(encoded),
                "fuzz 迭代 #$i：reaction 列表往返必须恒等"
            )
        }
    }

    @Test
    fun `unknown fields in reaction objects are ignored`() {
        val random = Random(0x64_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val reaction = randomReaction(random)
            val extras = (0 until random.nextInt(0, 4)).associate {
                var name: String
                do {
                    name = UNKNOWN_FIELD_NAMES[random.nextInt(UNKNOWN_FIELD_NAMES.size)]
                } while (name in KNOWN_FIELDS)
                name to randomJsonValue(random, 2)
            }
            val obj = JsonObject(
                mapOf(
                    "userId" to JsonPrimitive(reaction.userId),
                    "emoji" to JsonPrimitive(reaction.emoji),
                    "reactedAt" to JsonPrimitive(reaction.reactedAt)
                ) + extras
            )
            val payload = Json.encodeToString(JsonArray.serializer(), JsonArray(listOf(obj)))
            assertEquals(
                listOf(reaction),
                MessageReactionJsonPolicy.decodeReactions(payload),
                "fuzz 迭代 #$i：未知字段必须被忽略，已知字段恒等"
            )
        }
    }

    @Test
    fun `wrong-typed known fields fall back to empty list`() {
        val random = Random(0x65_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val badField = when (random.nextInt(4)) {
                0 -> "userId" to randomJsonScalar(random).let { if (it is JsonPrimitive && it.isString) JsonPrimitive(123) else it }
                1 -> "emoji" to JsonObject(mapOf("x" to JsonPrimitive(1)))
                // kotlinx.serialization 的 Long 反序列化接受 JSON 字符串里的数字（"42" → 42），
                // 所以「类型错」要选真正解不出的值：非数字文本。
                2 -> "reactedAt" to JsonPrimitive("not-a-number")
                else -> "userId" to JsonArray(listOf(JsonPrimitive("u1")))
            }
            val base = mapOf(
                "userId" to JsonPrimitive("u1"),
                "emoji" to JsonPrimitive("👍"),
                "reactedAt" to JsonPrimitive(42L)
            )
            val obj = JsonObject(base + mapOf(badField.first to badField.second))
            val payload = Json.encodeToString(JsonArray.serializer(), JsonArray(listOf(obj)))
            assertEquals(
                emptyList(),
                MessageReactionJsonPolicy.decodeReactions(payload),
                "fuzz 迭代 #$i：已知字段类型错必须整体回空列表，payload=$payload"
            )
        }
        // 缺失必填字段同样回空列表。
        assertEquals(emptyList(), MessageReactionJsonPolicy.decodeReactions("""[{"emoji":"👍"}]"""))
        assertEquals(emptyList(), MessageReactionJsonPolicy.decodeReactions("""[{"userId":"u1"}]"""))
    }
}
