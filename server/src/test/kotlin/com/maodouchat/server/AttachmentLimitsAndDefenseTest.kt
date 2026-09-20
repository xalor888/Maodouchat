package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.plugins.ATTACHMENT_HASH_HEADER
import com.maodouchat.server.plugins.ATTACHMENT_CHUNK_HASH_HEADER
import com.maodouchat.server.plugins.ATTACHMENT_TOO_LARGE_STATUS
import com.maodouchat.server.plugins.MAX_ATTACHMENT_CIPHER_BYTES
import com.maodouchat.server.plugins.*
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 附件管道的**极限与异常边界**防御。
 *
 * **本轮修复说明**（重要，别把这份文件当成"一直是绿的"）：
 * 这个文件此前是从未编译过的（装配用了不存在的 `configureSecurity`），而且它的请求形状
 * 与真实契约**系统性不符**——注册体缺 `name`（所以 5 个用例全部卡在注册 400）、建会话用
 * `{"targetUserId":"u2"}`（真实是 `{"participantIds":[...]}`）、会话创建用 `sha256`/`totalChunks`
 * （真实是 `messageId`/`cipherSha256`，没有 `totalChunks`）、直传用 `X-Sha256`（真实是
 * `X-Content-SHA256`）、上传会话返回里读 `uploadId`（真实字段是 `id`）。
 *
 * 这会带来一种最坏的绿：**请求因为"参数不合法"被 400 拒掉，而断言恰好也在期望 400**，
 * 测试于是通过，却什么都没证明。所以下面每个用例都刻意让**被断言的那条规则成为唯一被违反的规则**
 * ——其他入参全部合法（真实 messageId、真实 64 位十六进制哈希、正确的 Content-Type、
 * 正确的分块哈希）。
 */
class AttachmentLimitsAndDefenseTest {

    private val validSha = "0".repeat(64)

    /** 与 `MinimalRouteTest.moduleUnderTest` 同形状；测试装的东西要和生产 `Application.module` 一致。 */
    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:attachment_limit_test_${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1",
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("STORAGE_DIR", java.nio.file.Files.createTempDirectory("maodouchat-att-").toString())
        Database.connect(ServerConfig.databaseUrl, driver = ServerConfig.databaseDriver)
        initDatabase()
        val userRepo = UserRepository()
        val postRepo = PostRepository()
        configureAuthentication()
        configureSerialization()
        configureStatusPages()
        val signalingRepo = SignalingRepository()
        val callInviteRateLimiter = CallInviteRateLimiter()
        configureSockets(userRepo, signalingRepo = signalingRepo, callInviteRateLimiter = callInviteRateLimiter)
        configureRouting(
            userRepo,
            postRepo,
            FakeAiGateway(),
            signalingRepo = signalingRepo,
            callInviteRateLimiter = callInviteRateLimiter,
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun parseJson(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    /** 注册一个真实账号，返回 (token, userId)。注册体必须带 `name`；成功状态码是 **200**（不是 201）。 */
    private suspend fun ApplicationTestBuilder.register(name: String, email: String): Pair<String, String> {
        val res = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","email":"$email","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, "注册失败：${res.bodyAsText()}")
        val body = parseJson(res.bodyAsText())
        return body["token"]!!.jsonPrimitive.content to body["userId"]!!.jsonPrimitive.content
    }

    /** 建一个 1:1 会话，返回 chatId。真实契约是 `participantIds` + 真实对方 userId。 */
    private suspend fun ApplicationTestBuilder.createDirectChat(token: String, peerUserId: String): String {
        val res = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"participantIds":["$peerUserId"],"isGroup":false}""")
        }
        assertEquals(HttpStatusCode.Created, res.status, "建会话失败：${res.bodyAsText()}")
        return parseJson(res.bodyAsText())["id"]!!.jsonPrimitive.content
    }

    /** 准备一个已登录、且有一个 1:1 会话的账号。 */
    private suspend fun ApplicationTestBuilder.prepareSender(tag: String): Triple<String, String, String> {
        val (token, userId) = register("Sender$tag", "att_$tag@example.com")
        val (_, peerId) = register("Peer$tag", "att_peer_$tag@example.com")
        val chatId = createDirectChat(token, peerId)
        return Triple(token, userId, chatId)
    }

    // ------------------------------------------------------------------
    // 直传（POST /api/attachments）——声明长度走 Content-Length
    // ------------------------------------------------------------------

    /**
     * 直传路径的**长度欺骗**防御：声明的 Content-Length 与实际送达的字节数不符时必须拒收。
     *
     * **诚实标注证明边界**：这个用例**不是**「>100 MiB 上界」的证明。上界分支
     * （`declaredLength !in 17L..MAX_ATTACHMENT_CIPHER_BYTES` → 413）在代码里是真实的，
     * 而且发生在读 body **之前**；但要通过 Ktor 测试客户端触发它，就得让客户端发出一个
     * 与实际 body 不符的 `Content-Length`——实测做不到：引擎会用真实 body 长度覆盖我们显式设置的
     * header，于是请求反而落到了「长度/哈希校验失败」这条更靠后的防线上（就是本用例钉住的那条）。
     * 因此 >100 MiB 上界由下面的**分块会话**用例覆盖（`cipherSize` 走 JSON，不受此限制），
     * 那条是可信的。
     */
    @Test
    fun `direct upload rejects a declared length that does not match the delivered body`() = testApplication {
        application { moduleUnderTest() }
        val (token, _, chatId) = prepareSender("mismatch")
        val declared = MAX_ATTACHMENT_CIPHER_BYTES + 1L

        val res = client.post("/api/attachments?chatId=$chatId&messageId=msg-att-big") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentLength, declared.toString())
            header(ATTACHMENT_HASH_HEADER, validSha)
            contentType(ContentType.Application.OctetStream)
            setBody(ByteArray(17))
        }
        assertEquals(
            HttpStatusCode.BadRequest,
            res.status,
            "声明的 Content-Length 与实际送达字节数不符时必须拒收；实际 ${res.status} ${res.bodyAsText()}",
        )
        assertTrue(
            !res.bodyAsText().contains("413"),
            "这里期望的是长度/哈希一致性防线，不是 413 上界防线：${res.bodyAsText()}",
        )
    }

    @Test
    fun `direct upload rejects payload smaller than 17B with 413`() = testApplication {
        application { moduleUnderTest() }
        val (token, _, chatId) = prepareSender("small")

        val res = client.post("/api/attachments?chatId=$chatId&messageId=msg-att-small") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(ATTACHMENT_HASH_HEADER, validSha)
            contentType(ContentType.Application.OctetStream)
            setBody(ByteArray(16)) // 16 B < 17 B 下界
        }
        assertEquals(
            ATTACHMENT_TOO_LARGE_STATUS,
            res.status,
            "声明长度低于 17 B 必须 413；实际 ${res.status} ${res.bodyAsText()}",
        )
    }

    // ------------------------------------------------------------------
    // 分块会话（POST /api/attachment-uploads）——声明长度走 JSON，最可信
    // ------------------------------------------------------------------

    private suspend fun ApplicationTestBuilder.createSession(
        token: String,
        chatId: String,
        messageId: String,
        cipherSize: Long,
    ): HttpResponse = client.post("/api/attachment-uploads") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(
            """{"chatId":"$chatId","messageId":"$messageId","cipherSha256":"$validSha","cipherSize":$cipherSize}"""
        )
    }

    @Test
    fun `chunked upload session creation rejects cipherSize over limit with 400`() = testApplication {
        application { moduleUnderTest() }
        val (token, _, chatId) = prepareSender("sessbig")

        val res = createSession(token, chatId, "msg-sess-big", MAX_ATTACHMENT_CIPHER_BYTES + 1L)
        assertEquals(
            HttpStatusCode.BadRequest,
            res.status,
            "cipherSize 超过上界必须 400；实际 ${res.status} ${res.bodyAsText()}",
        )
    }

    @Test
    fun `chunked upload session creation rejects cipherSize under 17B with 400`() = testApplication {
        application { moduleUnderTest() }
        val (token, _, chatId) = prepareSender("sesssmall")

        val res = createSession(token, chatId, "msg-sess-small", 16L)
        assertEquals(
            HttpStatusCode.BadRequest,
            res.status,
            "cipherSize 低于 17 B 下界必须 400；实际 ${res.status} ${res.bodyAsText()}",
        )
    }

    @Test
    fun `a legal cipherSize is accepted`() = testApplication {
        application { moduleUnderTest() }
        val (token, _, chatId) = prepareSender("sessok")

        // 对照用例：证明上面两个 400 不是「这个接口总是 400」。
        val res = createSession(token, chatId, "msg-sess-ok", 100L)
        assertEquals(
            HttpStatusCode.Created,
            res.status,
            "合法 cipherSize 应当建会话成功；实际 ${res.status} ${res.bodyAsText()}",
        )
        assertTrue(parseJson(res.bodyAsText())["id"]!!.jsonPrimitive.content.isNotBlank())
    }

    // ------------------------------------------------------------------
    // 分块写入（PUT /api/attachment-uploads/{id}?offset=）——越界写
    // ------------------------------------------------------------------

    @Test
    fun `chunked upload put chunk rejects offset plus chunk size exceeding declared cipherSize with 400`() =
        testApplication {
            application { moduleUnderTest() }
            val (token, _, chatId) = prepareSender("chunk")

            val cipherSize = 100L
            val init = createSession(token, chatId, "msg-chunk", cipherSize)
            assertEquals(HttpStatusCode.Created, init.status, "建会话失败：${init.bodyAsText()}")
            val uploadId = parseJson(init.bodyAsText())["id"]!!.jsonPrimitive.content

            // offset=50 + 60 B = 110 > 100：**唯一被违反的规则就是越界**
            // （Content-Type 正确、分块哈希是这 60 字节的真实 sha256、长度 ≤ 4 MiB 上界）
            val chunk = ByteArray(60) { it.toByte() }
            val res = client.put("/api/attachment-uploads/$uploadId?offset=50") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(ATTACHMENT_CHUNK_HASH_HEADER, sha256Hex(chunk))
                contentType(ContentType.Application.OctetStream)
                setBody(chunk)
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                res.status,
                "offset + chunkSize 超出已声明 cipherSize 必须 400；实际 ${res.status} ${res.bodyAsText()}",
            )
        }
}
