package com.maodouchat.server.plugins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequiredBodyFieldTest {

    @Test
    fun `blank body field responds 400 with message and stops the handler`() = testApplication {
        var reached = false
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/probe") {
                    val title = call.requireNonBlankValueOr400("   ", "标题不能为空") ?: return@get
                    reached = true
                    call.respond(title)
                }
            }
        }
        val res = client.get("/probe")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("标题不能为空"), res.bodyAsText())
        assertFalse(reached)
    }

    @Test
    fun `non-blank body field passes through unchanged`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/probe") {
                    val title = call.requireNonBlankValueOr400("hello", "标题不能为空") ?: return@get
                    // Ktor 的 ContentNegotiation 不会对 String 走 JSON 编码：respond(String) 按 text/plain 原样透出。
                    call.respond(title)
                }
            }
        }
        val res = client.get("/probe")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("hello", res.bodyAsText())
    }

    @Test
    fun `empty string is rejected the same as whitespace-only`() = testApplication {
        var reached = false
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/probe") {
                    val title = call.requireNonBlankValueOr400("", "标题不能为空") ?: return@get
                    reached = true
                    call.respond(title)
                }
            }
        }
        val res = client.get("/probe")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertFalse(reached)
    }
}
