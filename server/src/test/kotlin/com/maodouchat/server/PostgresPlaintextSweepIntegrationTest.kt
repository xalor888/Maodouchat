package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.messaging.v2.MessagingV2Repository
import com.maodouchat.server.plugins.*
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 服务端「不得保存人类聊天明文」这条命门在**真实 PostgreSQL** 上的证据。
 *
 * **本轮修复说明**：这个文件此前从未编译过，而且它的请求形状与真实契约系统性不符——
 * 注册体缺 `name`（5 处断言直接卡在 400）、建会话用 `{"targetUserId":…}`（真实是
 * `{"participantIds":[…],"isGroup":…}`）、V2 发送打到 `/api/messaging/v2/send`（真实是
 * `/api/v2/messages`，且信封字段是 `recipientUserId/recipientDeviceId/ciphertextType/ciphertext`，
 * 不是 `recipientDeviceId/encryptedPayload`）、`/api/admin/session` 用邮箱密码当 body
 * （真实契约是**带 access token** 换发，不读 body）、设备与密钥想靠 `/api/keys/upload` 一次搞定
 * （真实是 `registrationId/identityKey/signedPreKeyId/signedPreKey/signedPreKeySignature/preKeys`，
 * 而且 V2 发送要求会话已绑定**已确认设备**——最可靠的造法是像
 * `MinimalRouteTest` 里那条已通过的 `/api/v2/messages` 用例一样直接种 `signal_devices`/`signal_keys`
 * 行并给 `auth_sessions.signal_device_id` 赋值）。
 *
 * 因此本文件整体按**仓库里已验证过的装配方式**重写；扫描逻辑与断言强度原样保留。
 */
@Tag("postgres")
class PostgresPlaintextSweepIntegrationTest {

    private val baseUrl = System.getenv("POSTGRES_TEST_DATABASE_URL")
        ?: "jdbc:postgresql://127.0.0.1:5432/maodouchat_test?user=maodouchat_test&password=maodouchat_test_password"

    private fun isPostgresAvailable(): Boolean = runCatching {
        DriverManager.getConnection(baseUrl).use { it.isValid(2) }
    }.getOrDefault(false)

    /** 每个用例一个独立 schema，跑完即删；互不串台。 */
    private fun <T> withPostgresSchema(testBlock: (String, String) -> T): T {
        assumeTrue(isPostgresAvailable(), "PostgreSQL unavailable at $baseUrl")
        val schema = "maodou_sw_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        DriverManager.getConnection(baseUrl).use { conn ->
            conn.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") }
        }
        val scopedUrl = baseUrl + if ('?' in baseUrl) "&currentSchema=$schema" else "?currentSchema=$schema"
        return try {
            testBlock(schema, scopedUrl)
        } finally {
            runCatching {
                DriverManager.getConnection(baseUrl).use { conn ->
                    conn.createStatement().use { it.execute("DROP SCHEMA IF EXISTS \"$schema\" CASCADE") }
                }
            }
        }
    }

    /**
     * 与 `MinimalRouteTest.moduleUnderTest` + 它的 `/api/v2/messages` 用例同形状：
     * 种子用户 + 显式注册 V2 路由（`configureRouting` 不含它）。
     */
    private fun Application.postgresModuleUnderTest(scopedUrl: String) {
        System.setProperty("DATABASE_URL", scopedUrl)
        System.setProperty("DATABASE_DRIVER", "org.postgresql.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("MASTER_ADMINS", "u1")
        System.setProperty("SEED_DEMO_USERS", "true")
        System.setProperty(
            "STORAGE_DIR",
            java.nio.file.Files.createTempDirectory("maodouchat-pg-sweep-").toString(),
        )
        Database.connect(scopedUrl, driver = "org.postgresql.Driver")
        initDatabase()
        val userRepo = UserRepository()
        userRepo.createDefaultUsers()
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
        configureMessagingV2Routing(MessagingV2Repository())
    }

    /** 种出「已确认设备 + 四类密钥」，并让 u1 的登录会话绑定设备 1（否则发消息会被 409 DEVICE_NOT_READY 拦下）。 */
    private fun seedConfirmedDevices(devices: List<Pair<String, Int>>) {
        transaction {
            devices.forEach { (userId, deviceId) ->
                SignalDevices.insert {
                    it[SignalDevices.userId] = userId
                    it[SignalDevices.deviceId] = deviceId
                    it[deviceName] = "$userId-$deviceId"
                    it[status] = "CONFIRMED"
                    it[confirmedAt] = 1L
                    it[confirmedByDeviceId] = deviceId
                    it[createdAt] = 1L
                    it[lastSeenAt] = 1L
                }
                listOf("identity_key", "registration_id", "signed_pre_key", "signed_pre_key_signature")
                    .forEach { keyType ->
                        SignalKeys.insert {
                            it[id] = "$userId-$deviceId-$keyType"
                            it[SignalKeys.userId] = userId
                            it[SignalKeys.deviceId] = deviceId
                            it[SignalKeys.keyType] = keyType
                            it[keyData] = "x"
                            it[createdAt] = 1L
                        }
                    }
            }
            if (devices.any { it.first == "u1" && it.second == 1 }) {
                AuthSessions.update({ AuthSessions.userId eq "u1" }) {
                    it[AuthSessions.signalDeviceId] = 1
                }
            }
        }
    }

    /** 用**独立 JDBC 连接**枚举该 schema 下全部表、全部列，找出含有标记的 (表, 列)。 */
    private fun sweepForMarkers(schema: String, markers: List<String>): List<Triple<String, String, String>> {
        val hits = mutableListOf<Triple<String, String, String>>()
        transaction {
            val jdbc = ((TransactionManager.current().connection as JdbcConnectionImpl).connection)
            val tableNames = mutableListOf<String>()
            jdbc.metaData.getTables(null, schema, "%", arrayOf("TABLE")).use { rs ->
                while (rs.next()) tableNames += rs.getString("TABLE_NAME")
            }
            assertTrue(tableNames.isNotEmpty(), "Postgres schema $schema 必须包含表")
            assertTrue("messaging_v2_envelopes" in tableNames, "messaging_v2_envelopes 必须存在")

            for (table in tableNames) {
                val colNames = mutableListOf<String>()
                jdbc.metaData.getColumns(null, schema, table, "%").use { rs ->
                    while (rs.next()) colNames += rs.getString("COLUMN_NAME")
                }
                for (col in colNames) {
                    jdbc.createStatement().use { stmt ->
                        stmt.executeQuery(
                            "SELECT \"$col\"::text AS c FROM \"$schema\".\"$table\" WHERE \"$col\" IS NOT NULL"
                        ).use { rs ->
                            while (rs.next()) {
                                val v = rs.getString("c") ?: continue
                                markers.forEach { m -> if (v.contains(m)) hits += Triple(table, col, m) }
                            }
                        }
                    }
                }
            }
        }
        return hits
    }

    @Test
    fun `a human v2 payload sent over real postgres lives in exactly one column and is not searchable`() {
        withPostgresSchema { schema, scopedUrl ->
            testApplication {
                application { postgresModuleUnderTest(scopedUrl) }

                suspend fun login(email: String): String {
                    val res = client.post("/api/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"$email","password":"password123"}""")
                    }
                    assertEquals(HttpStatusCode.OK, res.status, "登录失败：${res.bodyAsText()}")
                    return Json.parseToJsonElement(res.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
                }

                val alexToken = login("alex@example.com")

                // 1:1 会话：发送者 u1、收件人 u2。u2 造两台设备 → 两个不同的标记各落一行信封，
                // 「每个标记恰好出现 1 次」才是精确断言。
                val created = client.post("/api/chats") {
                    header(HttpHeaders.Authorization, "Bearer $alexToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"participantIds":["u2"],"isGroup":false}""")
                }
                assertEquals(HttpStatusCode.Created, created.status, "建会话失败：${created.bodyAsText()}")
                val chatId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

                seedConfirmedDevices(listOf("u1" to 1, "u2" to 1, "u2" to 2))

                val markerA = "PG-HUMAN-OPAQUE-U2D1-" + UUID.randomUUID()
                val markerB = "PG-HUMAN-OPAQUE-U2D2-" + UUID.randomUUID()

                val sent = client.post("/api/v2/messages") {
                    header(HttpHeaders.Authorization, "Bearer $alexToken")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"id":"pg_sweep_1","conversationId":"$chatId","kind":"DATA",
                            "clientTimestamp":1000,"attachmentIds":[],
                            "envelopes":[
                              {"recipientUserId":"u2","recipientDeviceId":1,"ciphertextType":"TEXT","ciphertext":"$markerA"},
                              {"recipientUserId":"u2","recipientDeviceId":2,"ciphertextType":"TEXT","ciphertext":"$markerB"}
                            ]}""",
                    )
                }
                assertTrue(
                    sent.status.value in 200..299,
                    "HTTP 发送必须成功，否则后面的负结论毫无意义：${sent.status} ${sent.bodyAsText()}",
                )

                // 管理端检索面：带 access token **并且**带二次确认密码换发 admin token，
                // 再以 admin token 调管理接口。正文标记不得出现在检索结果里。
                val adminSession = client.post("/api/admin/session") {
                    header(HttpHeaders.Authorization, "Bearer $alexToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"password":"password123"}""")
                }
                assertEquals(HttpStatusCode.OK, adminSession.status, "换发 admin session 失败：${adminSession.bodyAsText()}")
                val adminToken = Json.parseToJsonElement(adminSession.bodyAsText())
                    .jsonObject["token"]!!.jsonPrimitive.content

                val search = client.get("/api/admin/messages/search?chatId=$chatId") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                }
                assertEquals(HttpStatusCode.OK, search.status, "管理检索失败：${search.bodyAsText()}")
                assertFalse(search.bodyAsText().contains(markerA), "管理检索不得回显密文 A")
                assertFalse(search.bodyAsText().contains(markerB), "管理检索不得回显密文 B")

                // 真实 PostgreSQL：全 schema、全表、全列扫描。
                // 列名就是 `messaging_v2_envelopes.ciphertext`——此前这里写的是 `encrypted_payload`，
                // 是一个不存在的列名（同一个「照着想象的 schema 写断言」的毛病）。
                val hits = sweepForMarkers(schema, listOf(markerA, markerB))
                assertEquals(
                    listOf(Triple("messaging_v2_envelopes", "ciphertext", markerA)),
                    hits.filter { it.third == markerA },
                    "标记 A 只能落在 messaging_v2_envelopes.ciphertext；实际命中 $hits",
                )
                assertEquals(
                    listOf(Triple("messaging_v2_envelopes", "ciphertext", markerB)),
                    hits.filter { it.third == markerB },
                    "标记 B 只能落在 messaging_v2_envelopes.ciphertext；实际命中 $hits",
                )
            }
        }
    }

    /**
     * **正对照**：同一套扫描器必须能发现服务端**故意**保存的明文。
     *
     * 没有这一条，上面那个「只命中一列」的负结论是自证的——扫描器恒空也能过。
     * 这里用真实的 `ServiceMessageRepository.publish` 写入 bot/service 明文
     * （服务端可见明文的唯一合法路径），并断言扫描器**确实**在
     * `service_messages.content` 与 `chats.last_message` 找到它。
     */
    @Test
    fun `the sweep on postgres really can find plaintext that the server intentionally stores`() {
        withPostgresSchema { schema, scopedUrl ->
            testApplication {
                application { postgresModuleUnderTest(scopedUrl) }

                suspend fun login(email: String): String {
                    val res = client.post("/api/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"$email","password":"password123"}""")
                    }
                    assertEquals(HttpStatusCode.OK, res.status, "登录失败：${res.bodyAsText()}")
                    return Json.parseToJsonElement(res.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
                }

                val alexToken = login("alex@example.com")
                val created = client.post("/api/chats") {
                    header(HttpHeaders.Authorization, "Bearer $alexToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"participantIds":["u2"],"isGroup":false}""")
                }
                assertEquals(HttpStatusCode.Created, created.status, "建会话失败：${created.bodyAsText()}")
                val chatId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

                // publish 的前置：bot 的 user 行（chat_participants 与 service_messages 都有 user 外键）、
                // 启用的 bot 应用、该 bot 必须是会话成员、owner 必须可投递。
                transaction {
                    Users.insert {
                        it[Users.id] = BOT_ID
                        it[name] = "Sweep Control Bot"
                        it[email] = "sweep_ctrl_bot@example.com"
                        it[passwordHash] = "not-a-real-hash"
                    }
                    BotApps.insert {
                        it[BotApps.id] = BOT_ID
                        it[ownerUserId] = "u1"
                        it[name] = "Sweep Control Bot"
                        it[username] = "sweep_ctrl_bot"
                        it[tokenHash] = "x".repeat(64)
                        it[tokenPrefix] = "sweepctrl"
                        it[enabled] = true
                        it[createdAt] = 1L
                        it[updatedAt] = 1L
                    }
                    ChatParticipants.insert {
                        it[ChatParticipants.chatId] = chatId
                        it[ChatParticipants.userId] = BOT_ID
                        it[role] = "MEMBER"
                        it[joinedAt] = 1L
                    }
                }

                val botPlaintextSentinel = "POSITIVE-CONTROL-PG-PLAINTEXT-987654"
                val publishResult = transaction {
                    ServiceMessageRepository().publish(
                        id = "pg-sweep-service-msg",
                        chatId = chatId,
                        botUserId = BOT_ID,
                        content = botPlaintextSentinel,
                        timestamp = 10_000L,
                        recipientUserIds = emptySet(),
                    )
                }
                assertTrue(
                    publishResult is ServiceMessageRepository.PublishResult.Published,
                    "ServiceMessageRepository.publish 必须成功，实际 $publishResult",
                )

                val hits = sweepForMarkers(schema, listOf(botPlaintextSentinel))
                assertTrue(
                    hits.any { it.first == "service_messages" && it.second == "content" },
                    "正对照必须命中 service_messages.content，实际命中 $hits",
                )
                assertTrue(
                    hits.any { it.first == "chats" && it.second == "last_message" },
                    "正对照还必须命中 chats.last_message（服务端缓存的预览），实际命中 $hits",
                )
            }
        }
    }

    private companion object {
        const val BOT_ID = "bot_sweep_ctrl"
    }
}
