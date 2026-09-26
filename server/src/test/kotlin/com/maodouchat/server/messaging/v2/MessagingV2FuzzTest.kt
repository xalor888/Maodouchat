package com.maodouchat.server.messaging.v2

import com.maodouchat.server.plugins.messagingV2Json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Messaging V2 线圈协议模型的变异 fuzz（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的第二块）。
 *
 * 第一块（`MessagingV2ForwardCompatTest`）钉住兼容契约：未知字段容忍、缺省回默认值、往返稳定。
 * 这一块钉住**解码全性**：三个线圈请求模型（`SendMessageV2Request` /
 * `AcknowledgeEnvelopesV2Request` / `ServiceMessagingV2Content`）的解码器对**任意**
 * 字节输入，要么解码成功，要么只以 `SerializationException` 失败——`Error`
 *（`StackOverflowError` / `AssertionError` 之类；G19 证明过第三方库会把意外包成
 * `Error` 逃逸，第一方代码更不该）或其它任何异常类型逃逸出来都是红。
 * 这与 G20 `SignalDecryptInputMatrixTest` 的「畸形输入矩阵」是同款哲学，
 * 只是对象从解密入口换成 JSON 解码入口。
 *
 * 实现是种子化的字符串级变异（[JsonMutator]），不依赖外部 fuzz 语料：
 * seed 固定 → 每次跑同一批输入，CI 可复跑；想加量就调各用例的 `repeat` 次数。
 */
class MessagingV2FuzzTest {

    // 有效基准：与 ForwardCompatTest 同源，所有变异都从这里出发。
    private val validSendRequest = """
        {
          "id": "m-1", "conversationId": "c-1", "kind": "DATA",
          "clientTimestamp": 1727000000000,
          "envelopes": [
            {"recipientUserId": "u2", "recipientDeviceId": 3,
             "ciphertextType": "SIGNAL", "ciphertext": "aGVsbG8="}
          ]
        }
    """.trimIndent()

    private val validAckRequest = """{"envelopeIds": ["e1", "e2"]}"""

    private val validServiceContent = """{"version": 1, "type": "TEXT", "body": "hello"}"""

    /**
     * 解码并断言失败通道：成功则返回解码值；失败只允许 `SerializationException`
     *（畸形输入的契约内失败）。其它任何 `Throwable`（含 `Error`）都直接判红，
     * 并把输入截断附在失败信息里，方便复现。
     */
    private fun decodeSafely(label: String, input: String, decode: (String) -> Any?): Any? =
        try {
            decode(input)
        } catch (e: SerializationException) {
            null
        } catch (t: Throwable) {
            fail(
                "fuzz 输入让解码器逃逸出契约外异常：${t::class.simpleName}：${t.message}。" +
                    "输入($label，前 300 字符)：${input.take(300)}"
            )
        }

    @Test
    fun `send request decoder is total over mutated inputs`() {
        val mutator = JsonMutator(seed = 0x5EED_2026L)
        repeat(400) { i ->
            decodeSafely("send-$i", mutator.mutate(validSendRequest)) {
                messagingV2Json.decodeFromString<SendMessageV2Request>(it)
            }
        }
    }

    @Test
    fun `ack request decoder is total over mutated inputs`() {
        val mutator = JsonMutator(seed = 0xAC1_2026L)
        repeat(300) { i ->
            decodeSafely("ack-$i", mutator.mutate(validAckRequest)) {
                messagingV2Json.decodeFromString<AcknowledgeEnvelopesV2Request>(it)
            }
        }
    }

    @Test
    fun `service content decoder is total over mutated inputs`() {
        val mutator = JsonMutator(seed = 0x5E9C_2026L)
        repeat(300) { i ->
            decodeSafely("svc-$i", mutator.mutate(validServiceContent)) {
                messagingV2Json.decodeFromString<ServiceMessagingV2Content>(it)
            }
        }
    }

    @Test
    fun `every truncation prefix of a valid request fails cleanly`() {
        // 确定性矩阵：合法请求的所有截断前缀（步长 7 防用例爆炸）+ 空/空白/标量输入。
        // 任一前缀都只允许成功或 SerializationException。
        val prefixes = (validSendRequest.indices step 7).map { validSendRequest.take(it) } +
            listOf("", " ", "\n", "null", "[]", "{}")
        for ((i, prefix) in prefixes.withIndex()) {
            decodeSafely("trunc-$i", prefix) {
                messagingV2Json.decodeFromString<SendMessageV2Request>(it)
            }
        }
    }

    @Test
    fun `extreme numbers do not escape the decoder`() {
        // 手工边界：超 Long 范围整数、科学计数无穷大、负小数、字符串冒充数字、
        // 数字冒充字符串、对象冒充数组。全部只允许成功或 SerializationException。
        val huge = "9".repeat(40)
        val cases = listOf(
            """{"id":"m-1","conversationId":"c-1","kind":"DATA","clientTimestamp":$huge,"envelopes":[]}""",
            """{"id":"m-1","conversationId":"c-1","kind":"DATA","clientTimestamp":1e999,"envelopes":[]}""",
            """{"id":"m-1","conversationId":"c-1","kind":"DATA","clientTimestamp":-0.5,"envelopes":[]}""",
            """{"id":"m-1","conversationId":"c-1","kind":"DATA","clientTimestamp":"1727000000000","envelopes":[]}""",
            """{"id":"m-1","conversationId":"c-1","kind":"DATA","clientTimestamp":1727000000000,"envelopes":[{"recipientUserId":"u2","recipientDeviceId":$huge,"ciphertextType":"SIGNAL","ciphertext":"eA=="}]}""",
            """{"id":1e999,"conversationId":"c-1","kind":"DATA","clientTimestamp":1727000000000,"envelopes":[]}""",
            """{"id":"m-1","conversationId":"c-1","kind":"DATA","clientTimestamp":1727000000000,"envelopes":{"recipientUserId":"u2"}}""",
        )
        for ((i, input) in cases.withIndex()) {
            decodeSafely("extreme-$i", input) {
                messagingV2Json.decodeFromString<SendMessageV2Request>(it)
            }
        }
    }

    @Test
    fun `deeply nested unknown structures are skipped safely`() {
        // 未知字段里套 64 层对象：解码必须成功且已知字段不受影响（ignoreUnknownKeys=true）。
        // 层数取 64：足够覆盖递归跳过路径，又远低于任何合理的嵌套深度上限。
        var deep = "\"v\""
        repeat(64) { i -> deep = "{\"z$i\":$deep}" }
        val base = validSendRequest.trim().dropLast(1)
        val decoded = decodeSafely("deep-nest", "$base,\"deep\":$deep}") {
            messagingV2Json.decodeFromString<SendMessageV2Request>(it)
        } as SendMessageV2Request?
            ?: fail("64 层未知嵌套让合法请求解码失败")
        assertEquals("m-1", decoded.id)
        assertEquals(1, decoded.envelopes.size)

        // 64 层数组包裹：顶层形状不对→只允许 SerializationException，不许 Error。
        var wrapped = validSendRequest.trim()
        repeat(64) { wrapped = "[$wrapped]" }
        decodeSafely("deep-array", wrapped) {
            messagingV2Json.decodeFromString<SendMessageV2Request>(it)
        }
    }

    @Test
    fun `successful fuzz decodes are round-trip stable`() {
        // 未知字段注入的合法请求必须解码成功，且成功的结果往返稳定（无静默数据损坏）。
        // 与 ForwardCompatTest 的角度不同：这里是 fuzz 视角的"解码成功子集"必须自洽。
        val sendBase = validSendRequest.trim().dropLast(1)
        val sendVariants = listOf(
            validSendRequest,
            "$sendBase,\"futureFlag\":true}",
            "$sendBase,\"futureNested\":{\"a\":[1,2,{\"b\":null}],\"c\":\"x\"}}",
            "$sendBase,\"futureDeep\":[[[[\"deep\"]]]]}",
        )
        for ((i, input) in sendVariants.withIndex()) {
            val decoded = decodeSafely("send-rt-$i", input) {
                messagingV2Json.decodeFromString<SendMessageV2Request>(it)
            } as SendMessageV2Request?
                ?: fail("注入未知字段的合法请求竟解码失败（send-rt-$i），向前兼容契约已破")
            val once = messagingV2Json.decodeFromString<SendMessageV2Request>(
                messagingV2Json.encodeToString(decoded)
            )
            assertEquals(decoded, once)
            assertEquals(messagingV2Json.encodeToString(decoded), messagingV2Json.encodeToString(once))
        }

        val ackBase = validAckRequest.trim().dropLast(1)
        val ackDecoded = decodeSafely("ack-rt", "$ackBase,\"futureBatchToken\":\"zzz\"}") {
            messagingV2Json.decodeFromString<AcknowledgeEnvelopesV2Request>(it)
        } as AcknowledgeEnvelopesV2Request?
            ?: fail("注入未知字段的 ack 请求竟解码失败，向前兼容契约已破")
        assertEquals(listOf("e1", "e2"), ackDecoded.envelopeIds)
    }

    /**
     * 种子化字符串级 JSON 变异器。只做词法层面的破坏（截断/字符翻转/词法注入/
     * 删段/跨度复制/未知嵌套），不保证语法合法——要的就是把解码器逼到各种
     * 半残输入上。同一 seed 永远产生同一序列，CI 可复跑。
     */
    private class JsonMutator(seed: Long) {
        private val random = Random(seed)

        // 专挑 JSON 敏感字符 + 控制字符 + 多字节字符（含单个半边代理项这种脏输入）
        private val alphabet = "\"{}[]:,\\/-+0123456789.eEtruefalsn \t\n\r\u0000\u001f中🔥"
        private val wordTokens = listOf(
            "\"x\"", "\"\"", "123", "-0", "1e999", "true", "false", "null",
            "{}", "[]", "\"k\":1", ",", ":", "\"", "\"\\u0000\"",
        )

        fun mutate(input: String): String {
            var s = input
            repeat(1 + random.nextInt(3)) {
                s = when (random.nextInt(6)) {
                    0 -> truncate(s)
                    1 -> flipChars(s)
                    2 -> insertToken(s)
                    3 -> deleteChars(s)
                    4 -> duplicateSpan(s)
                    else -> nestUnknown(s)
                }
            }
            return s
        }

        private fun cutPoint(s: String): Int = if (s.isEmpty()) 0 else random.nextInt(s.length + 1)

        private fun truncate(s: String): String = s.take(cutPoint(s))

        private fun flipChars(s: String): String {
            if (s.isEmpty()) return s
            val chars = s.toCharArray()
            repeat(1 + random.nextInt(4)) {
                chars[random.nextInt(chars.size)] = alphabet[random.nextInt(alphabet.length)]
            }
            return chars.concatToString()
        }

        private fun insertToken(s: String): String {
            val token = wordTokens[random.nextInt(wordTokens.size)]
            val at = cutPoint(s)
            return s.take(at) + token + s.drop(at)
        }

        private fun deleteChars(s: String): String {
            if (s.isEmpty()) return s
            val at = random.nextInt(s.length)
            val len = 1 + random.nextInt(4)
            return s.take(at) + s.drop((at + len).coerceAtMost(s.length))
        }

        private fun duplicateSpan(s: String): String {
            if (s.length < 4) return s
            val a = random.nextInt(s.length)
            val b = (a + 1 + random.nextInt(8)).coerceAtMost(s.length)
            val span = s.substring(a, b)
            val at = cutPoint(s)
            return s.take(at) + span + s.drop(at)
        }

        private fun nestUnknown(s: String): String {
            var w = s
            repeat(1 + random.nextInt(6)) {
                w = if (random.nextBoolean()) "{\"z$it\":$w}" else "[$w]"
            }
            return w
        }
    }
}
