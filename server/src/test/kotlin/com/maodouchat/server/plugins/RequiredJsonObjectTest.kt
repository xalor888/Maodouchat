package com.maodouchat.server.plugins

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequiredJsonObjectTest {

    @Test
    fun `broken json responds 400 invalid json and stops the handler`() = testApplication {
        var reached = false
        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/probe") {
                    val obj = call.requireJsonObjectOr400("{bad") ?: return@post
                    reached = true
                    call.respond(obj)
                }
            }
        }
        val res = client.post("/probe")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("invalid json"), res.bodyAsText())
        assertFalse(reached)
    }

    @Test
    fun `empty body responds 400 invalid json`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/probe") {
                    val obj = call.requireJsonObjectOr400("") ?: return@post
                    call.respond(obj)
                }
            }
        }
        // 与原内联写法一致：空串 parse 失败，同样 400。
        val res = client.post("/probe")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("invalid json"), res.bodyAsText())
    }

    @Test
    fun `non-object top level responds 400 invalid json`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/probe") {
                    val obj = call.requireJsonObjectOr400("[1, 2]") ?: return@post
                    call.respond(obj)
                }
            }
        }
        // 顶层是数组：jsonObject 转换失败，原写法同样判空回 400。
        val res = client.post("/probe")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("invalid json"), res.bodyAsText())
    }

    @Test
    fun `valid object passes through unchanged`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/probe") {
                    val obj = call.requireJsonObjectOr400("""{"userIds":["u1"]}""") ?: return@post
                    call.respond(obj)
                }
            }
        }
        val res = client.post("/probe") {
            contentType(ContentType.Text.Plain)
            setBody("""{"userIds":["u1"]}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("u1"), res.bodyAsText())
    }
}
