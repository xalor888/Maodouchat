package com.maodouchat.server.plugins

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequiredPathParamTest {

    @Test
    fun `missing param responds 400 with message and stops the handler`() = testApplication {
        var reached = false
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/probe") {
                    val id = call.requirePathParamOr400("id", "缺少 ID") ?: return@get
                    reached = true
                    call.respond(id)
                }
            }
        }
        val res = client.get("/probe")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("缺少 ID"), res.bodyAsText())
        assertFalse(reached)
    }

    @Test
    fun `present param passes through unchanged`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/probe") {
                    val id = call.requirePathParamOr400("id", "缺少 ID") ?: return@get
                    call.respond(id)
                }
            }
        }
        val res = client.get("/probe?id=abc123")
        assertEquals(HttpStatusCode.OK, res.status)
        // Ktor 的 ContentNegotiation 不会对 String 走 JSON 编码：respond(String) 按 text/plain 原样透出。
        assertEquals("abc123", res.bodyAsText())
    }

    @Test
    fun `missing param on post keeps early-return label and message`() = testApplication {
        var reached = false
        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/probe") {
                    val id = call.requirePathParamOr400("id", "missing user id") ?: return@post
                    reached = true
                    call.respond(id)
                }
            }
        }
        val res = client.post("/probe")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("missing user id"), res.bodyAsText())
        assertFalse(reached)
    }

    @Test
    fun `empty string value is not treated as missing`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/probe") {
                    val id = call.requirePathParamOr400("id", "缺少 ID") ?: return@get
                    call.respond(id)
                }
            }
        }
        // 与原内联写法一致：parameters["id"] 为 "" 时非 null，原样透出，不 400。
        val res = client.get("/probe?id=")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("", res.bodyAsText())
    }
}
