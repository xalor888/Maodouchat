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
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
private data class ReceiveProbeRequest(val name: String = "")

class ReceiveJsonOr400Test {

    @Test
    fun `broken json responds 400 default message and stops the handler`() = testApplication {
        var reached = false
        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/probe") {
                    val req = call.receiveJsonOr400<ReceiveProbeRequest>() ?: return@post
                    reached = true
                    call.respond(req)
                }
            }
        }
        val res = client.post("/probe") {
            contentType(ContentType.Application.Json)
            setBody("{bad")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("参数无效"), res.bodyAsText())
        assertFalse(reached)
    }

    @Test
    fun `empty body responds 400 like the old inline run block`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/probe") {
                    val req = call.receiveJsonOr400<ReceiveProbeRequest>() ?: return@post
                    call.respond(req)
                }
            }
        }
        // 与原内联写法一致：空体 parse 出 null，同样 400。
        val res = client.post("/probe")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("参数无效"), res.bodyAsText())
    }

    @Test
    fun `custom message passes through verbatim`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/probe") {
                    val req = call.receiveJsonOr400<ReceiveProbeRequest>(message = "位置参数无效") ?: return@post
                    call.respond(req)
                }
            }
        }
        val res = client.post("/probe") {
            contentType(ContentType.Application.Json)
            setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("位置参数无效"), res.bodyAsText())
    }

    @Test
    fun `valid body passes through and the handler continues`() = testApplication {
        var reached = false
        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/probe") {
                    val req = call.receiveJsonOr400<ReceiveProbeRequest>() ?: return@post
                    reached = true
                    call.respond(req.name)
                }
            }
        }
        val res = client.post("/probe") {
            contentType(ContentType.Application.Json)
            setBody("{\"name\":\"maodou\"}")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(reached)
        assertEquals("maodou", res.bodyAsText())
    }
}
