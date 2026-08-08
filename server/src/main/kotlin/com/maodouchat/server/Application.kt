package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureCORS
import com.maodouchat.server.plugins.configureDeveloperRouting
import com.maodouchat.server.plugins.configureRateLimit
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureSockets
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.plugins.configurePollRouting
import com.maodouchat.server.plugins.configureAiEnhanceRouting
import com.maodouchat.server.plugins.configureAdminEnhanceRouting
import com.maodouchat.server.plugins.configureSecretSurfaceRouting
import com.maodouchat.server.plugins.startRateLimitStatsSampler
import com.maodouchat.server.plugins.BoundedRateLimiter
import com.maodouchat.server.plugins.CachingPlugin
import com.maodouchat.server.plugins.SecurityHeaders
import com.maodouchat.server.repository.AiRepository
import com.maodouchat.server.repository.ChatRepository
import com.maodouchat.server.repository.MessageRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.NotificationPreferenceRepository
import com.maodouchat.server.repository.PushTokenRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.RateLimitStatsRepository
import com.maodouchat.server.repository.UserTagRepository
import com.maodouchat.server.service.AiGatewayService
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import org.jetbrains.exposed.sql.Database
import java.nio.file.Files
import java.nio.file.Paths

fun main() {
    ServerConfig.validate()

    val storagePath = Paths.get(ServerConfig.storageDir).toAbsolutePath().normalize()
    Files.createDirectories(storagePath)
    require(Files.isDirectory(storagePath) && Files.isWritable(storagePath)) {
        "STORAGE_DIR must be a writable directory: $storagePath"
    }

    // 连接数据库（ServerConfig 优先读环境变量，其次读系统属性，便于测试覆盖默认值）。
    // 8.31 运维修复 CRITICAL：显式使用 HikariCP 连接池——Exposed 0.46 的
    // Database.connect(url, driver) 不再自动建池，每次事务裸连 DB（高并发打爆
    // Postgres max_connections）；池化提供连接复用、获取超时与泄漏检测。
    val dataSource = com.zaxxer.hikari.HikariDataSource(
        com.zaxxer.hikari.HikariConfig().apply {
            jdbcUrl = ServerConfig.databaseUrl
            driverClassName = ServerConfig.databaseDriver
            username = ServerConfig.databaseUser
            password = ServerConfig.databasePassword
            maximumPoolSize = ServerConfig.databasePoolSize
            minimumIdle = 2
            connectionTimeout = 5_000
            validationTimeout = 2_000
            maxLifetime = 1_800_000
            leakDetectionThreshold = 30_000
            poolName = "maodouchat-db"
        }
    )
    Database.connect(dataSource)

    // 初始化数据库表
    initDatabase()

    // 创建仓库
    val userRepo = UserRepository()
    val chatRepo = ChatRepository()
    val messageRepo = MessageRepository()
    val postRepo = PostRepository()
    val notificationPreferenceRepo = NotificationPreferenceRepository()
    val pushTokenRepo = PushTokenRepository()
    val pushService = FcmPushService(pushTokenRepo, notificationPreferenceRepo)
    val signalingRepo = SignalingRepository()
    val callInviteRateLimiter = CallInviteRateLimiter()
    // B6 运维增强：公告 / 用户标签 / 限流统计仓库
    val announcementRepo = AnnouncementRepository()
    val userTagRepo = UserTagRepository()
    val rateLimitStatsRepo = RateLimitStatsRepository()
    // 8.52 修复 AI-5：AI 网关单例——configureRouting（/api/ai/*）与 configureAiEnhanceRouting
    //（/api/ai/enhance/*）共享同一实例，否则预算预留/幂等缓存/并发信号量互相不可见（TOCTOU 绕开）
    val aiGateway = AiGatewayService()

    // 开发/测试模式可创建演示用户
    if (ServerConfig.seedDemoUsers) {
        userRepo.createDefaultUsers()
    }

    // B6 限流统计采样器：每 60s 写分钟桶 + 清理过期快照（8.31：返回执行器注册关闭）
    val rateLimitSampler = startRateLimitStatsSampler(rateLimitStatsRepo)

    embeddedServer(Netty, port = ServerConfig.port, host = ServerConfig.host, configure = {
        // 8.31 运维修复 HIGH：请求读/写超时（防慢速客户端无限占连接）+ 队列上限。
        // 上传端点内部有流式读取的独立限长，不受全局超时影响业务正确性。
        requestReadTimeoutSeconds = 60
        responseWriteTimeoutSeconds = 60
        requestQueueLimit = 256
    }) {
        configureAuthentication()
        configureCORS()
        install(SecurityHeaders)
        configureSerialization()
        // gzip 压缩 JSON 响应（配合 Serialization 的 prettyPrint=false 省一半流量）
        install(Compression) {
            gzip()
        }
        configureStatusPages()
        configureRateLimit()
        install(CachingPlugin)
        configureSockets(
            userRepo,
            messageRepo,
            chatRepo,
            signalingRepo = signalingRepo,
            pushService = pushService,
            callInviteRateLimiter = callInviteRateLimiter
        )
        configureRouting(
            userRepo,
            chatRepo,
            messageRepo,
            postRepo,
            aiGateway = aiGateway,
            notificationPreferenceRepo = notificationPreferenceRepo,
            pushTokenRepo = pushTokenRepo,
            pushService = pushService,
            signalingRepo = signalingRepo,
            callInviteRateLimiter = callInviteRateLimiter
        )
        // 群玩法 B3：群签到+排行 / 群接龙 / 群 PK / 投票同步（REST + WS 推送）
        configurePollRouting()
        configureDeveloperRouting()
        // B4 · AI 增强能力（会话画像 / 群周报 / 情绪感知回复 / 跨聊天问答 / 消息分类）
        // 服务端编排复用 AiGateway，端点统一挂载 /api/ai/enhance，实现见 plugins/AiEnhanceRouting.kt。
        configureAiEnhanceRouting(
            aiGateway = aiGateway,
            chatRepo = chatRepo,
            aiRepo = AiRepository(),
            aiRateLimiter = BoundedRateLimiter()
        )
        // B6 运维增强：公告广播 / 用户标签+风控联动 / 审计时间范围导出 / 限流仪表盘 / 设备一致性
        configureAdminEnhanceRouting(
            announcementRepo = announcementRepo,
            userTagRepo = userTagRepo,
            rateLimitStatsRepo = rateLimitStatsRepo,
            fcmPushService = pushService,
            pushTokenRepo = pushTokenRepo
        )
        // B2 密聊防泄漏扩展（Surface #71–#78）：burnz/ttlz/fwlz/simz/2faz/ndz/dvz/sntz + hints
        configureSecretSurfaceRouting(
            chatRepo = chatRepo,
            messageRepo = messageRepo
        )
        // 8.31 运维修复：限流采样器注册优雅关闭（退出瞬间不再执行 DB 写）；
        // Hikari 连接池在 start 返回后显式关闭。
        environment.monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
            rateLimitSampler.shutdownNow()
            runCatching { dataSource.close() }
                .onFailure { e -> java.util.logging.Logger.getLogger("Maodouchat").warning("Hikari close failed: ${e.message}") }
        }
    }.start(wait = true)
}
