package com.maodouchat.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全应用共享的连接池与 OkHttp 客户端工厂（G328c）。
 *
 * **为什么要它**：审计数到 app 里有 **9 处独立的 `OkHttpClient.Builder()`**。它们各自的
 * 超时是有意不同的（下面对每个用途都有说明），所以这不是「重复代码」——真正的问题是
 * 每个 `OkHttpClient` 都会**自建一份连接池与线程池**：9 个实例 = 9 份池子，
 * 同一个主机（尤其是自己的 API 主机）的连接无法复用，空闲连接也各占各的。
 *
 * 做法：一个共享 [ConnectionPool] + 每用途一个 builder（profile 保留、语义显式）。
 * OkHttp 官方的建议正是「共享连接池，dispatch 各自独立」——本工厂照此实现：
 * 池子共享，**没有**共享 Dispatcher，避免一个慢请求（比如 AI 推理）占住其它请求的线程额度。
 *
 * 新增用途时请在这里加一个带注释的命名 profile，而不是就地 `OkHttpClient.Builder()`——
 * 后者会让第 10 份池子悄悄出现。
 */
object HttpClients {

    /** 共享连接池。空闲上限与保活时长取 OkHttp 的常规推荐值。 */
    private val sharedPool = ConnectionPool(
        maxIdleConnections = 8,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES,
    )

    /** 所有 profile 的起点：共享池。 */
    private fun base(): OkHttpClient.Builder = OkHttpClient.Builder().connectionPool(sharedPool)

    /** 主 API（`ApiService`）：交互式请求，读 30s、写 60s 覆盖附件分片。 */
    fun api(): OkHttpClient = base()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 长连接 WebSocket（`WebSocketClient`）：readTimeout=0 表示读不超时，靠应用层心跳判死。 */
    fun webSocket(): OkHttpClient = base()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    /** 群玩法接口（`GroupPlayHttp`）：轻量交互式，超时压得比主 API 更短。 */
    fun groupPlay(): OkHttpClient = base()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 大文件下载（官方 APK / WebRTC 原生库）：读超时放到 120s，允许重定向。 */
    fun largeDownload(): OkHttpClient = base()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 贴纸包等媒体下载：中等体积，15s/30s。 */
    fun mediaDownload(): OkHttpClient = base()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 链接预览：**故意不跟随重定向**——由应用层显式校验每一跳的目标 URL；
     * 超时也压到 4s/5s（预览失败不该拖慢输入框）。
     */
    fun linkPreview(): OkHttpClient = base()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 用户自配的 OpenAI 兼容端点（`OpenAiCompatClient`）：[timeoutSeconds] 由调用方按模型配置给。 */
    fun chatModel(timeoutSeconds: Long): OkHttpClient = base()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 图片加载（Coil）：调用方补自己的 DNS 与鉴权拦截器。 */
    fun imageLoader(): OkHttpClient.Builder = base()
}
