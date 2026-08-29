package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureSockets
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.AiGatewayResult
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppUpdatePublishRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Application.moduleUnderTest() {
        val storage = Files.createTempDirectory("maodou-app-update").toFile()
        System.setProperty("STORAGE_DIR", storage.absolutePath)
        System.setProperty("UPDATE_DEPLOY_TOKEN", "sixteen-chars-ok")
        System.setProperty("BASE_URL", "https://chat.example.com")
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:app-update-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1",
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("SEED_DEMO_USERS", "false")
        Database.connect(ServerConfig.databaseUrl, driver = ServerConfig.databaseDriver)
        initDatabase()
        val userRepo = UserRepository()
        val postRepo = PostRepository()
        configureAuthentication()
        configureSerialization()
        configureStatusPages()
        val signalingRepo = SignalingRepository()
        configureSockets(
            userRepo,
            signalingRepo = signalingRepo,
            callInviteRateLimiter = CallInviteRateLimiter(),
        )
        configureRouting(
            userRepo,
            postRepo,
            FakeUpdateAiGateway(),
            signalingRepo = signalingRepo,
            callInviteRateLimiter = CallInviteRateLimiter(),
        )
    }

    @Test
    fun `github token uploads apk and public json offers https url`() = testApplication {
        application { moduleUnderTest() }
        val apk = ByteArray(128) { 0 }
        apk[0] = 0x50; apk[1] = 0x4B; apk[2] = 0x03; apk[3] = 0x04

        val denied = client.put("/api/internal/app-update") {
            header(HttpHeaders.Authorization, "Bearer wrong-token-value")
            header("X-Version-Code", "42")
            header("X-Version-Name", "1.4.2")
            setBody(apk)
        }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)

        val published = client.put("/api/internal/app-update") {
            header(HttpHeaders.Authorization, "Bearer sixteen-chars-ok")
            header("X-Version-Code", "42")
            header("X-Version-Name", "1.4.2")
            header("X-Update-Notes", "fix chat")
            setBody(apk)
        }
        assertEquals(HttpStatusCode.OK, published.status, published.bodyAsText())

        val updates = client.get("/api/public/updates")
        assertEquals(HttpStatusCode.OK, updates.status)
        val body = json.parseToJsonElement(updates.bodyAsText()).jsonObject
        assertEquals("42", body["versionCode"]!!.jsonPrimitive.content)
        assertEquals("1.4.2", body["versionName"]!!.jsonPrimitive.content)
        assertEquals(
            "https://chat.example.com/api/public/app-update/latest.apk",
            body["apkUrl"]!!.jsonPrimitive.content,
        )

        val download = client.get("/api/public/app-update/latest.apk")
        assertEquals(HttpStatusCode.OK, download.status)
        assertTrue(download.bodyAsText().isNotEmpty())
    }
}

private class FakeUpdateAiGateway : AiGateway {
    override val model: String = "test-model"
}
