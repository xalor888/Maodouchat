package com.maodouchat.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 服务器身份（第三方服务器模式）。
 *
 * 通过公开端点 `GET {base}/api/server/info` 获取当前连接服务器的名称、简介、公告等品牌信息，
 * 供设置页与主界面展示「第三方服务器」身份。跨域切换服务器时旧身份必须清空，
 * 避免把 A 服务器的名称展示在 B 服务器的会话上。
 */
object ServerIdentity {
    data class Info(
        val name: String,
        val description: String,
        val announcement: String,
        val contactUrl: String,
        val version: String,
        val registrationOpen: Boolean,
        val baseUrl: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _current = MutableStateFlow<Info?>(null)
    val current: StateFlow<Info?> = _current.asStateFlow()

    /** 当前是否第三方服务器模式（运行时自定义地址，区别于编译期官方默认）。 */
    val isThirdPartyServer: Boolean get() = ApiConfig.isUsingRuntimeServer

    /** 异步刷新；App 启动与服务器切换后调用。 */
    fun refreshAsync() {
        scope.launch { refresh() }
    }

    suspend fun refresh(baseUrl: String = ApiConfig.BASE_URL): Info? = withContext(Dispatchers.IO) {
        val normalized = baseUrl.trim().trimEnd('/')
        // 域名已变化：先丢弃旧身份，避免跨服务器串显
        if (_current.value?.baseUrl != normalized) _current.value = null
        fetch(normalized)?.also { info -> _current.value = info }
    }

    fun clear() {
        _current.value = null
    }

    /** 拉取指定地址的服务器身份；网络失败/非 Maodouchat 服务返回 null。 */
    fun fetch(baseUrl: String): Info? = runCatching {
        val normalized = baseUrl.trim().trimEnd('/')
        val connection = URL("$normalized/api/server/info").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            Info(
                name = json.optString("name", "").take(60).ifBlank { "Maodouchat Server" },
                description = json.optString("description", "").take(500),
                announcement = json.optString("announcement", "").take(1000),
                contactUrl = json.optString("contactUrl", "").take(300),
                version = json.optString("version", "").take(40),
                registrationOpen = json.optBoolean("registrationOpen", true),
                baseUrl = normalized
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
