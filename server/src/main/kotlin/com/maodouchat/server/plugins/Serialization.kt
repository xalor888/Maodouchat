package com.maodouchat.server.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            // prettyPrint = false → JSON 体积减半（生产环境不需要缩进）
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
}
