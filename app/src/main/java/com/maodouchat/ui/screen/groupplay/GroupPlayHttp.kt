package com.maodouchat.ui.screen.groupplay

import com.maodouchat.network.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 群玩法 B3 的轻量 HTTP 客户端。
 *
 * 独立于 ApiService（不侵入既有文件），仅覆盖本功能所需的 GET/POST JSON 调用，
 * 统一挂 Authorization Bearer。非 2xx 仍带回响应体，供调用方展示服务端 `error`。
 */
internal data class GroupPlayResponse(
    val ok: Boolean,
    val body: String,
    val code: Int
) {
    val errorText: String?
        get() = if (ok) null else GroupPlayJson.errorMessage(body)
}

internal object GroupPlayHttp {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun get(token: String, path: String): GroupPlayResponse =
        execute(token, path, jsonBody = null)

    suspend fun post(token: String, path: String, jsonBody: String): GroupPlayResponse =
        execute(token, path, jsonBody)

    private suspend fun execute(token: String, path: String, jsonBody: String?): GroupPlayResponse =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder()
                    .url(ApiConfig.BASE_URL + path)
                    .header("Authorization", "Bearer $token")
                val req = if (jsonBody == null) {
                    builder.get().build()
                } else {
                    builder.post(jsonBody.toRequestBody(jsonMedia)).build()
                }
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    GroupPlayResponse(ok = resp.isSuccessful, body = body, code = resp.code)
                }
            }.getOrElse {
                GroupPlayResponse(ok = false, body = "", code = -1)
            }
        }
}
