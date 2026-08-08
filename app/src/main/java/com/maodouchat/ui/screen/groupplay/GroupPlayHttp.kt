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
 * 统一挂 Authorization Bearer，返回原始响应文本；非 2xx 返回 null，由调用方兜底。
 */
internal object GroupPlayHttp {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun get(token: String, path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(ApiConfig.BASE_URL + path)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) body else null
            }
        }.getOrNull()
    }

    suspend fun post(token: String, path: String, jsonBody: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(ApiConfig.BASE_URL + path)
                .header("Authorization", "Bearer $token")
                .post(jsonBody.toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) body else null
            }
        }.getOrNull()
    }
}
