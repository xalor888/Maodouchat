package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.util.UUID

/** Header names never written to logs in plaintext (compared case-insensitively). */
private val SENSITIVE_HEADERS = setOf(
    "authorization",
    "cookie",
    "set-cookie",
    "x-bot-token",
    "x-maodouchat-signature"
)

/**
 * Build a log-safe copy of the request headers: sensitive headers are replaced with
 * `<redacted>` so stack traces / error logs can never leak credentials, JWTs, or signatures.
 */
private fun ApplicationCall.redactedHeaders(): Map<String, String> =
    request.headers.entries().associate { (name, values) ->
        val safe = if (name.lowercase() in SENSITIVE_HEADERS) "<redacted>" else values.joinToString(",")
        name to safe
    }

/** Short correlation id included in 500 responses so ops can match a client report to a log line. */
private fun newRequestId(): String = UUID.randomUUID().toString().take(8)

fun Application.configureStatusPages() {
    val logger = LoggerFactory.getLogger("StatusPages")
    val isProduction = ServerConfig.isProduction

    install(StatusPages) {
        // JSON 解析失败应返回 400，而不是落到全局 Throwable 处理器
        exception<SerializationException> { call, cause ->
            logger.warn("Bad request body: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求体格式无效"))
        }
        exception<BadRequestException> { call, cause ->
            logger.warn("Bad request: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        }
        // 数据库唯一约束/检查约束冲突 -> 409；其他数据库错误保留为 500，避免把连接/语法/超时伪装成业务冲突
        exception<org.jetbrains.exposed.exceptions.ExposedSQLException> { call, cause ->
            val sqlState = cause.sqlState.orEmpty()
            if (sqlState.startsWith("23")) {
                logger.warn("Database constraint violation: {}", cause.message)
                call.respond(HttpStatusCode.Conflict, ErrorResponse("数据冲突"))
            } else {
                val requestId = newRequestId()
                logger.error("Database error [reqId={}] {} {} headers={}", requestId, call.request.httpMethod.value, call.request.path(), call.redactedHeaders(), cause)
                val error = if (isProduction) "服务器内部错误" else "服务器内部错误: ${cause.message?.take(200)}"
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(error, code = "internal_error:$requestId")
                )
            }
        }
        // 非法参数（参数校验失败）-> 400
        exception<IllegalArgumentException> { call, cause ->
            logger.warn("Invalid argument: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
        }
        exception<Throwable> { call, cause ->
            val requestId = newRequestId()
            logger.error(
                "Unhandled server error [reqId={}] {} {} headers={}",
                requestId,
                call.request.httpMethod.value,
                call.request.path(),
                call.redactedHeaders(),
                cause
            )
            // 生产环境只返回通用错误；开发环境附带简短 detail 便于本地调试。
            // 响应体永不包含 stackTrace/cause；堆栈仅写入服务端日志。
            val error = if (isProduction) {
                "服务器内部错误"
            } else {
                "服务器内部错误: ${cause.message?.take(200)}"
            }
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(error, code = "internal_error:$requestId")
            )
        }
    }
}
