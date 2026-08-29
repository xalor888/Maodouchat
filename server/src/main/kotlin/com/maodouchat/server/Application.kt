package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.db.migration.runDatabaseMigrations
import com.maodouchat.server.plugins.configureAuthentication
import com.maodouchat.server.plugins.configureCORS
import com.maodouchat.server.plugins.configureDeveloperRouting
import com.maodouchat.server.plugins.configureRateLimit
import com.maodouchat.server.plugins.configureRouting
import com.maodouchat.server.plugins.configureSerialization
import com.maodouchat.server.plugins.configureSockets
import com.maodouchat.server.plugins.configureStatusPages
import com.maodouchat.server.plugins.configurePollRouting
import com.maodouchat.server.plugins.configureAdminEnhanceRouting
import com.maodouchat.server.plugins.configureSecretSurfaceRouting
import com.maodouchat.server.plugins.configureMessagingV2Routing
import com.maodouchat.server.plugins.startRateLimitStatsSampler
import com.maodouchat.server.plugins.CachingPlugin
import com.maodouchat.server.plugins.SecurityHeaders
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.NotificationPreferenceRepository
import com.maodouchat.server.repository.PushTokenRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.RateLimitStatsRepository
import com.maodouchat.server.repository.UserTagRepository
import com.maodouchat.server.messaging.v2.MessagingV2Repository
import com.maodouchat.server.service.AiGatewayService
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    // Bootstrap table definitions once, then apply ordered, locked migrations.
    initDatabase()
    runDatabaseMigrations()

    // B06：mailbox retention——进程内定时批处理（单实例部署；多实例 lease 属迁移框架后续）。
    // 每小时触发一次，单轮最多 20 批 × 500 行，失败只记日志不影响服务。
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
        val retentionService = com.maodouchat.server.messaging.retention.MailboxRetentionService()
        val retentionLogger = org.slf4j.LoggerFactory.getLogger("MailboxRetention")
        while (true) {
            kotlinx.coroutines.delay(3_600_000L)
            runCatching {
                var more = true
                var batches = 0
                while (more && batches < 20) {
                    more = retentionService.purgeBatch().hasMore
                    batches++
                }
            }.onFailure { e -> retentionLogger.warn("mailbox retention purge failed", e) }
        }
    }

    // 创建仓库
    val userRepo = UserRepository()
    val messagingV2Repository = MessagingV2Repository()
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
    // 聊天明文推理已下放到客户端自配模型。服务端 AiGateway 仅给动态/评论审核（默认关）。
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
            signalingRepo = signalingRepo,
            pushService = pushService,
            callInviteRateLimiter = callInviteRateLimiter
        )
        configureRouting(
            userRepo,
            postRepo,
            aiGateway = aiGateway,
            notificationPreferenceRepo = notificationPreferenceRepo,
            pushTokenRepo = pushTokenRepo,
            pushService = pushService,
            signalingRepo = signalingRepo,
            callInviteRateLimiter = callInviteRateLimiter
        )
        configureMessagingV2Routing(messagingV2Repository)
        // 群玩法 B3：群签到+排行 / 群接龙 / 群 PK / 投票同步（REST + WS 推送）
        configurePollRouting()
        configureDeveloperRouting()
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
            userRepo = userRepo
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
