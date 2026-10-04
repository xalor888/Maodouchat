package com.maodouchat.server.plugins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatIdQueryParseTest {

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldWay(params: Parameters, name: String): String? =
        params[name]?.takeIf(String::isNotBlank)

    @Test
    fun `missing chatId responds 400 with message and stops the handler`() = testApplication {
        var reached = false
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/probe") {
                    val chatId = call.requireNonBlankParamOr400("chatId", "聊天 ID 无效") ?: return@get
                    reached = true
                    call.respond(chatId)
                }
            }
        }
        val res = client.get("/probe")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("聊天 ID 无效"), res.bodyAsText())
        assertFalse(reached)
    }

    @Test
    fun `blank chatId responds 400`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/probe") {
                    val chatId = call.requireNonBlankParamOr400("chatId", "聊天 ID 无效") ?: return@get
                    call.respond(chatId)
                }
            }
        }
        val res = client.get("/probe?chatId=%20%20%20")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("聊天 ID 无效"), res.bodyAsText())
    }

    @Test
    fun `present chatId passes through unchanged`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/probe") {
                    val chatId = call.requireNonBlankParamOr400("chatId", "聊天 ID 无效") ?: return@get
                    call.respond(chatId)
                }
            }
        }
        val res = client.get("/probe?chatId=c123")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("c123", res.bodyAsText())
    }

    @Test
    fun `shared requireNonBlankParamOr400 pure logic agrees with old inline on seeded random params`() {
        val random = Random(36117)
        val candidates = listOf("", "   ", "  abc  ", "c1", "0", "true", "@x", "中 文")
        repeat(150) { i ->
            val value = candidates[random.nextInt(candidates.size)]
            val params = if (random.nextBoolean()) parametersOf("chatId" to listOf(value)) else parametersOf()
            for (name in listOf("chatId", "other")) {
                assertEquals(
                    oldWay(params, name),
                    parseNonBlankOrNull(params, name),
                    "iteration " + i + ": " + name + " must match",
                )
            }
        }
    }
}
