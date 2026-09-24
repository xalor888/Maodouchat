package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.AiGateway
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.Database
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `/health/metrics` 与 `/api/status` 的匿名暴露面（G328c）。
 *
 * 单独一个类：Exposed 的 `Database.connect` 是「先连上的成为默认库」，同 JVM 内多个
 * 用例各建一个内存库时，后建的不会接管默认——`ConnectionRegistry.onlineUserIds()`
 * 这类会查库的端点因此在同类混跑时命中前一个用例的库。`forkEvery = 1` 是按**类**分 JVM，
 * 所以把这个类独立出来即可隔离（AuthHardeningTest 里的纯鉴权用例不查库，不受影响）。
 */
class MetricsEndpointGateTest {

    private fun Application.moduleUnderTest() {
        System.setProperty(
            "DATABASE_URL",
            "jdbc:h2:mem:metrics-gate-${AtomicInteger().incrementAndGet()}-" +
                "${kotlin.random.Random.nextInt(1_000_000)};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        )
        System.setProperty("DATABASE_DRIVER", "org.h2.Driver")
        System.setProperty("JWT_SECRET", "test-secret-12345678901234567890")
        System.setProperty("SEED_DEMO_USERS", "true")
        System.setProperty("AUTH_RATE_LIMIT_PER_MINUTE", "1000")
        Database.connect(System.getProperty("DATABASE_URL"), driver = "org.h2.Driver")
        initDatabase()
        val userRepo = UserRepository()
        userRepo.createDefaultUsers()
        configureAuthentication()
        configureSerialization()
        configureStatusPages()
        configureRouting(
            userRepo,
            PostRepository(),
            object : AiGateway {
                override val model: String = "test-model"
            },
        )
    }

    // ─── 运维指标端点的门禁 ───

    @Test
    fun `metrics endpoint is closed until a token is configured`() = testApplication {
        application { moduleUnderTest() }
        System.clearProperty("METRICS_TOKEN")
        val response = client.get("/health/metrics")
        assertEquals(
            HttpStatusCode.NotFound,
            response.status,
            "未配置 METRICS_TOKEN 时该端点必须关闭（fail-closed）：${response.bodyAsText()}",
        )
        assertTrue(response.bodyAsText().contains("METRICS_TOKEN"), "文案应说明如何开启")
    }

    @Test
    fun `metrics endpoint requires the token and then returns the payload`() = testApplication {
        application { moduleUnderTest() }
        System.setProperty("METRICS_TOKEN", "metrics-token-1234567890")
        try {
            val anonymous = client.get("/health/metrics")
            assertEquals(HttpStatusCode.Unauthorized, anonymous.status, anonymous.bodyAsText())

            val wrong = client.get("/health/metrics") {
                header(HttpHeaders.Authorization, "Bearer not-the-token")
            }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status, wrong.bodyAsText())

            val ok = client.get("/health/metrics") {
                header(HttpHeaders.Authorization, "Bearer metrics-token-1234567890")
            }
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            assertTrue(ok.bodyAsText().contains("usedHeapBytes"), "带正确令牌时应返回完整指标")
        } finally {
            System.clearProperty("METRICS_TOKEN")
        }
    }

    @Test
    fun `public status does not leak the app environment`() = testApplication {
        application { moduleUnderTest() }
        val response = client.get("/api/status")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertFalse(body.contains("\"env\""), "匿名状态端点不得暴露 APP_ENV：$body")
    }
}
