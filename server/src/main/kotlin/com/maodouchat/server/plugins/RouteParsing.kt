package com.maodouchat.server.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

private val routingJson = Json { ignoreUnknownKeys = true }
private val routingParseLogger = org.slf4j.LoggerFactory.getLogger("RoutingParse")

// 手动 JSON 解析 —— 绕过 Ktor ContentNegotiation 对 receiveNullable / ContentConversion 的歧义。
// 在 Ktor 2.3 + in-memory testApplication 同进程多次 mount 时行为最稳定。
// 用法：val req = call.receiveJson<SomeRequest>()
internal suspend inline fun <reified T> ApplicationCall.receiveJson(maxChars: Int = MAX_JSON_BODY_CHARS): T? =
    receiveBoundedText(maxChars)?.let { parseJson<T>(it) }

internal inline fun <reified T> parseJson(text: String): T? = try {
    if (text.isBlank()) null
    else routingJson.decodeFromString<T>(text)
} catch (e: Exception) {
    // 9.4xx：不再静默吞掉解析失败——记录类型与错误摘要（正文截断，避免日志膨胀/泄密）
    routingParseLogger.warn(
        "JSON parse failed for {}: {} (body head: {})",
        T::class.simpleName,
        e.message.orEmpty(),
        text.take(200).replace('\n', ' ')
    )
    null
}

internal suspend fun ApplicationCall.receiveBoundedText(maxChars: Int = MAX_JSON_BODY_CHARS): String? {
    // 9.135：字节预算 = 字符预算 × 4（UTF-8 单字符最多 4 字节）。此前按 maxChars 字节截断，
    // 中文等多字节正文在接近字符上限时被提前拒绝（字节数天然大于字符数）；字符数检查才是语义上限。
    val maxBytes = maxChars.toLong() * 4
    val declaredLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declaredLength != null && declaredLength > maxBytes) return null
    val channel = receiveChannel()
    val output = java.io.ByteArrayOutputStream(minOf(maxChars, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) {
            // 9.150：readAvailable 无数据时立即返回 0，直接 continue 会空转烧 CPU
            //（慢速/恶意客户端逐字节送包时尤其明显）；挂起等待有数据或 EOF 再继续
            channel.awaitContent()
            continue
        }
        if (total + read > maxBytes) return null
        output.write(buffer, 0, read)
        total += read
    }
    return String(output.toByteArray(), Charsets.UTF_8).takeIf { it.length <= maxChars }
}

internal suspend fun ApplicationCall.receiveBoundedTextOrEmpty(maxChars: Int = MAX_JSON_BODY_CHARS): String {
    return try {
        receiveBoundedText(maxChars) ?: ""
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 9.4xx：记录读取失败（此前静默返回空串，掩盖网络错误/超时）
        routingParseLogger.warn(
            "Body read failed on {} {}: {}",
            request.httpMethod.value,
            request.path(),
            e.message.orEmpty()
        )
        ""
    }
}
