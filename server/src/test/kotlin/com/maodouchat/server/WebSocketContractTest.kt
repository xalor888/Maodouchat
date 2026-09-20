package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.plugins.*
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * WebSocket 双向契约。
 *
 * **本轮修复**：这个文件此前是从未编译过的（装配用了不存在的 `configureSecurity`），
 * 而且其中两个用例即使编译也证明不了任何东西——一个正文是 `assertTrue(true)`，
 * 另一个对自己**硬编码在测试里的本地列表**做断言（列表里的 `TYPING` 在服务端根本不存在，
 * 真名是 `USER_TYPING`），却让台账把 G41「WebSocket 双端全量枚举」记成了 `[x]`。
 * 现在：
 * - 空断言那个删掉了（服务端侧的对应断言就是下面的上游白名单用例）；
 * - 下游枚举改成**源码扫描棘轮**——扫真正的 `WsMessage("…")` 构造点，集合精确相等，
 *   新增一个下游类型就会红，必须有人显式把基线改大（即做一次有意识的决定）。
 */
class WebSocketContractTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /** 与 `MinimalRouteTest.moduleUnderTest` 同形状；测试装的东西要和生产 `Application.module` 一致。 */
    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:ws_contract_test_${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
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

    private fun ApplicationTestBuilder.createAuthedWsClient(): io.ktor.client.HttpClient = createClient {
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(json)
        }
    }

    @Test
    fun `upstream command whitelist strictly rejects all chat and message mutations`() = testApplication {
        application { moduleUnderTest() }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }
        val reg = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"WsGuard","email":"ws_guard@example.com","password":"password123"}""")
        }
        // 注册成功返回 200（不是 201），且必须带 name
        assertEquals(HttpStatusCode.OK, reg.status, "注册失败：${reg.bodyAsText()}")
        val token = Json.parseToJsonElement(reg.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content

        val wsClient = createAuthedWsClient()
        wsClient.webSocket(request = {
            url("/ws")
            header(HttpHeaders.Authorization, "Bearer $token")
        }) {
            val forbiddenTypes = listOf(
                "SEND_MESSAGE", "SEND_TEXT", "SEND_AUDIO", "SEND_IMAGE", "SEND_FILE",
                "MESSAGE", "CHAT_MESSAGE", "DELETE_MESSAGE", "REVOKE_MESSAGE", "REACTION",
                "EDIT_MESSAGE", "SEND_DATA", "ENVELOPE", "SYNC",
            )
            for (type in forbiddenTypes) {
                send(Frame.Text(json.encodeToString(WsMessage(type, """{"content":"hello world"}"""))))
                val frame = incoming.receive() as Frame.Text
                val resp = json.decodeFromString<WsMessage>(frame.readText())
                assertEquals("ERROR", resp.type, "Type $type must return ERROR envelope")
                val error = json.decodeFromString<ErrorResponse>(resp.payload)
                assertEquals(
                    "UNSUPPORTED_WS_COMMAND",
                    error.code,
                    "Type $type must be rejected with UNSUPPORTED_WS_COMMAND",
                )
            }
        }
    }

    /**
     * 下游（服务端→客户端）能发出的**全部** WS 类型，从源码里扫出来的真实集合。
     *
     * 冻结策略与 `ServerArchitectureTest` 一致：精确相等，只许有意识地改动。
     */
    private val frozenDownstreamTypes: Set<String> = setOf(
        "ADMIN_BROADCAST",              // AdminManagementRouting
        "DISAPPEARING_MESSAGES_UPDATED", // ConversationSettingsRouting（设置变更通知）
        "ERROR",                        // Sockets
        "FRIEND_REQUEST",               // FriendRouting
        "GROUP_INVITE",                 // Routing
        "GROUP_PLAY_UPDATE",            // PollRouting（群玩法状态，非聊天正文）
        "GROUP_REVISION_CHANGED",       // Routing
        "INBOX_AVAILABLE_V2",           // 唯一的 V2 唤醒信号——正文从不上 WS
        "PINNED_MESSAGES_UPDATED",      // BotChatActionRouting（置顶变更通知）
        "PONG",                         // Sockets
        "POST_DELETED",                 // RealtimePublisher（动态删除）
        "SIGNALING",                    // Sockets（通话信令）
        "USER_STATUS",                  // RealtimePublisher（presence）
        "USER_TYPING",                  // TypingService
    )

    /** 明确禁止出现在下游类型名里的「人肉正文投递」形状。 */
    private val forbiddenPayloadTypeNames = setOf(
        "NEW_MESSAGE", "MESSAGE", "CHAT_MESSAGE", "MESSAGE_DATA", "MESSAGE_CONTENT",
        "MESSAGE_RECEIVED", "SEND_MESSAGE", "RECEIVE_MESSAGE",
    )

    private val serverSourceRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (marker in listOf("src/main/kotlin/com/maodouchat/server", "server/src/main/kotlin/com/maodouchat/server")) {
                val candidate = File(dir, marker)
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = dir.parentFile
        }
        fail("找不到服务端源码根目录（从 user.dir=${System.getProperty("user.dir")} 向上查找失败）")
    }

    private fun scannedDownstreamTypes(): Set<String> {
        val files = serverSourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue(files.isNotEmpty(), "没扫到任何 .kt 文件——扫描型门禁拒绝在空集合上通过")
        val pattern = Regex("\\bWsMessage\\(\\s*(?:type\\s*=\\s*)?\"([A-Za-z0-9_]+)\"")
        return files.flatMap { file ->
            val cleaned = file.readText()
                .replace(Regex("(?s)/\\*.*?\\*/"), "")
                .lines()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
            pattern.findAll(cleaned).map { it.groupValues[1] }.toList()
        }.toSet()
    }

    @Test
    fun `downstream ws types are exactly the frozen set and none of them delivers a human payload`() {
        val actual = scannedDownstreamTypes()
        println("[ws] downstream types = ${actual.sorted()}")

        assertEquals(
            frozenDownstreamTypes.sorted(),
            actual.sorted(),
            "下游 WS 类型集合变了。新增一个类型意味着它会被推给真实客户端——请先确认它不承载人类消息正文，" +
                "再把基线改大；减少是好消息，请把基线改小。",
        )

        val offenders = actual.intersect(forbiddenPayloadTypeNames)
        assertEquals(
            emptySet(),
            offenders,
            "下游类型里出现了「人肉正文投递」形状的名字：$offenders。" +
                "Messaging V2 的契约是 WS 只承载唤醒 / presence / typing / 通话信令 / 状态变更。",
        )
    }

    /**
     * 诚实标注本用例的**证明边界**：它只能证明「服务端没有构造出名字像正文投递的下游类型」。
     * 名字说不出的东西（例如把明文塞进某个 `_UPDATED` 的 payload）由载荷级扫描负责——
     * `ServerPlaintextSweepTest` / `PostgresPlaintextSweepIntegrationTest` 扫的是落库列，
     * `MessagingV2OutboxPlaintextBoundaryTest` 扫的是出站请求。名字扫描不能替代它们。
     */
    @Test
    fun `the type-name scan does not claim to prove payload contents`() {
        val declared = frozenDownstreamTypes
        assertTrue(
            declared.contains("INBOX_AVAILABLE_V2"),
            "V2 唤醒信号必须仍在集合里——它是「WS 不再承载正文」这条不变量得以成立的关键",
        )
        assertTrue(
            declared.none { it.contains("PLAINTEXT") || it.contains("DECRYPTED") },
            "类型名里不该出现 PLAINTEXT / DECRYPTED 这类字样",
        )
    }
}
