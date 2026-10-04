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
        assertEquals("\"abc123\"", res.bodyAsText())
    }
}
