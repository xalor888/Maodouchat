package com.maodouchat.server.config

import org.slf4j.LoggerFactory

internal fun normalizeHttpScheme(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return trimmed
    return trimmed.substring(0, schemeEnd).lowercase() + trimmed.substring(schemeEnd)
}

/**
 * 服务端运行配置。
 *
 * 生产环境请通过环境变量覆盖默认值，尤其是 JWT_SECRET、BASE_URL、DATABASE_URL。
 */
object ServerConfig {
    private val logger = LoggerFactory.getLogger(ServerConfig::class.java)

    val appEnv: String = env("APP_ENV", "development").lowercase()
    val isProduction: Boolean = appEnv == "production" || appEnv == "prod"

    /** 0.74：注册邮箱域名黑名单（一次性/垃圾邮箱域名，逗号分隔；空 = 不拦截）。 */
    val emailDomainBlocklist: Set<String> get() =
        env("EMAIL_DOMAIN_BLOCKLIST", "")
            .split(',')
            .map { it.trim().lowercase().removePrefix("@") }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * 宽松校验模式（仅生产环境有意义）：适用于自托管 / 小规模私人部署。
     * 开启后 SMTP 与 TURN 不再强制——无 SMTP 时验证码走日志（需 DEV_LOG_CODES=true），
     * 无 TURN 时通话降级为仅 STUN。JWT_SECRET / HTTPS BASE_URL / 持久化数据库等
     * 核心安全校验仍然强制，不会因此弱化。
     */
    val relaxedVerification: Boolean get() =
        env("RELAXED_VERIFICATION", "false").toBooleanStrictOrNull() ?: false

    val host: String = env("HOST", "0.0.0.0")
    val port: Int = env("PORT", "8080").toIntOrNull() ?: 8080

    val baseUrl: String = normalizeHttpScheme(env("BASE_URL", "http://localhost:$port"))

    val databaseUrl: String get() = env("DATABASE_URL", "jdbc:h2:mem:maodouchat;DB_CLOSE_DELAY=-1")
    val databaseDriver: String get() = env("DATABASE_DRIVER", "org.h2.Driver")
    val databaseUser: String get() = env("DATABASE_USER", "sa")
    val databasePassword: String get() = env("DATABASE_PASSWORD", "")
    /** HikariCP 连接池大小（默认 CPU*2+1，上限 64）。 */
    val databasePoolSize: Int get() =
        env("DATABASE_POOL_SIZE", "${(Runtime.getRuntime().availableProcessors() * 2 + 1).coerceAtMost(64)}")
            .toIntOrNull()?.coerceIn(2, 128) ?: (Runtime.getRuntime().availableProcessors() * 2 + 1).coerceAtMost(64)

    val storageDir: String = env("STORAGE_DIR", "./uploads")
    /** Trust proxy-supplied client IP headers only when the Ktor port is isolated behind a known proxy. */
    val trustProxyHeaders: Boolean get() = env("TRUST_PROXY_HEADERS", "false").toBooleanStrictOrNull() ?: false

    /** Maximum allowed size for Base64 avatar/post image uploads (in characters). ~2 MB decoded. */
    val maxBase64ImageChars: Int get() = env("MAX_BASE64_IMAGE_CHARS", "2800000").toIntOrNull()?.coerceIn(100_000, 10_000_000) ?: 2_800_000
    /** Maximum allowed decoded image bytes. */
    val maxImageBytes: Int get() = env("MAX_IMAGE_BYTES", "2097152").toIntOrNull()?.coerceIn(100_000, 10_485_760) ?: 2 * 1024 * 1024
    /** Maximum image dimension in pixels (width or height). */
    val maxImageDimension: Int get() = env("MAX_IMAGE_DIMENSION", "4096").toIntOrNull()?.coerceIn(256, 8192) ?: 4096
    /** Maximum encrypted attachment size per object (100 MB default). */
    val maxAttachmentBytes: Long get() = env("MAX_ATTACHMENT_BYTES", "104857600").toLongOrNull()?.coerceIn(1_048_576L, 524_288_000L) ?: 104_857_600L
    /** Per-user storage quota in bytes (20 GB default, env-tunable between 1 GB and 1 TB). */
    val userStorageQuotaBytes: Long get() = env("USER_STORAGE_QUOTA_BYTES", "21474836480").toLongOrNull()?.coerceIn(1_073_741_824L, 1_099_511_627_776L) ?: 21_474_836_480L

    /**
     * Global API rate limit: requests per minute per IP for UNAUTHENTICATED requests only.
     * Authenticated requests use [authenticatedRateLimitPerMinute] keyed by user id instead,
     * so several real users behind one NAT IP can never exhaust each other's budget.
     */
    val globalRateLimitPerMinute: Int get() = env("GLOBAL_RATE_LIMIT_PER_MINUTE", "600").toIntOrNull()?.coerceIn(30, 60_000) ?: 600
    /**
     * Per-user rate limit: requests per minute for authenticated requests.
     * Normal client bursts (chat list + per-chat sync + prekey bundles for every group member)
     * easily reach several hundred requests in a minute, so the budget must be generous.
     */
    val authenticatedRateLimitPerMinute: Int get() = env("USER_RATE_LIMIT_PER_MINUTE", "2000").toIntOrNull()?.coerceIn(120, 120_000) ?: 2000
    /** Login/register rate limit: attempts per minute per IP. */
    val authRateLimitPerMinute: Int get() = env("AUTH_RATE_LIMIT_PER_MINUTE", "10").toIntOrNull()?.coerceIn(3, 100) ?: 10
    /** AI endpoint rate limit: requests per minute per user. */
    val aiRateLimitPerMinute: Int get() = env("AI_RATE_LIMIT_PER_MINUTE", "20").toIntOrNull()?.coerceIn(5, 200) ?: 20

    /** CORS allowed origins (comma-separated). Empty means same-origin only. */
    val corsOrigins: List<String> get() = env("CORS_ORIGINS", "")
        .split(',')
        .map(::normalizeHttpScheme)
        .filter { it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://")) }
        .distinct()

    /** Whether to allow new user registration. Set to false for invite-only deployments. */
    val allowRegistration: Boolean get() = env("ALLOW_REGISTRATION", "true").toBooleanStrictOrNull() ?: true

    /**
     * 9.206：第三方部署可关闭官网首页（PUBLIC_SITE=false）——“/” 改为展示极简服务器名片，
     * 自建服务器无需也不应替运营方展示毛豆官网。
     */
    val publicSiteEnabled: Boolean get() = env("PUBLIC_SITE", "true").toBooleanStrictOrNull() ?: true

    val openAiApiKey: String get() = env("OPENAI_API_KEY", "")
    val openAiBaseUrl: String get() = normalizeHttpScheme(env("OPENAI_BASE_URL", "https://api.openai.com/v1"))
    /**
     * 默认/轻量模型别名，向后兼容。新代码应使用 [openAiModelLight] / [openAiModelStrong] / [openAiModelFallback]。
     * 默认值必须是 OpenAI 公开 API 上真实可用的模型，避免全新部署时 404。
     */
    val openAiModel: String get() = env("OPENAI_MODEL", openAiModelLight)
    /** 轻量模型：translate / suggestReplies / rewrite 等低延迟任务。 */
    val openAiModelLight: String get() = env("OPENAI_MODEL_LIGHT", "gpt-4o-mini")
    /** 强力模型：summarize / groupAssistant / analyzeFile / analyzeImage / semanticSearch 等需要推理的任务。 */
    val openAiModelStrong: String get() = env("OPENAI_MODEL_STRONG", "gpt-4o")
    /** 兜底模型：主模型上游错误时的单次回退尝试。 */
    val openAiModelFallback: String get() = env("OPENAI_MODEL_FALLBACK", "gpt-4o-mini")
    val openAiTranscriptionModel: String get() = env("OPENAI_TRANSCRIPTION_MODEL", "whisper-1")

    val fcmProjectId: String get() = env("FCM_PROJECT_ID", "")
    val fcmServiceAccountFile: String get() = env("FCM_SERVICE_ACCOUNT_FILE", "")

    /**
     * Push 负载 HMAC 密钥：服务端对每条 FCM data 签名（sig + ts），客户端本地校验以拒绝伪造推送。
     * 与 SealedSender 授权 token 同理，密钥经 /api/public/status 下发给客户端用于本地校验。
     * 生产环境务必通过 PUSH_HMAC_SECRET 覆盖；默认值仅用于开发。
     */
    val pushHmacSecret: String get() = env("PUSH_HMAC_SECRET", "dev-only-push-hmac-secret-not-for-prod").also {
        // 8.31 运维修复 HIGH：生产环境使用默认密钥 = 推送签名可被任何人伪造（FCM 负载
        // 的 sig 用该密钥签名、客户端按签名信任推送）。
        // 宽松模式（RELAXED_VERIFICATION=true）允许不配置：推送校验降级为 fail-open。
        if (it == "dev-only-push-hmac-secret-not-for-prod" && isProduction && !relaxedVerification) {
            error("PUSH_HMAC_SECRET environment variable is required — refusing to start with the dev default in production")
        }
    }

    /** Coturn REST-auth configuration. Credentials are minted per user and never embedded in the APK. */
    val turnUrls: List<String> get() = env("TURN_URLS", "")
        .split(',')
        .map(String::trim)
        .filter { it.startsWith("turn:") || it.startsWith("turns:") }
        .distinct()
    val turnSharedSecret: String get() = env("TURN_SHARED_SECRET", "")
    val turnCredentialTtlSeconds: Long get() = env("TURN_CREDENTIAL_TTL_SECONDS", "3600")
        .toLongOrNull()
        ?.coerceIn(300, 86_400)
        ?: 3600

    // JWT_SECRET 必须显式设置，不允许默认值 — 防止开发期的弱 secret 泄漏到生产构建
    val jwtSecret: String get() = env("JWT_SECRET", "").also {
        if (it.isBlank() && isProduction) error("JWT_SECRET environment variable is required — refusing to start without a real secret")
    }

    val seedDemoUsers: Boolean get() = env("SEED_DEMO_USERS", (!isProduction).toString()).toBooleanStrictOrNull() ?: false
    val moderatorEmails: Set<String>
        get() {
            val fallback = if (isProduction) "" else "alex@example.com"
            return env("MODERATOR_EMAILS", fallback)
                .split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .toSet()
        }

    /**
     * 超级管理员 userId 列表（配置期静态，适合小规模私人部署）。
     * 格式：逗号分隔的用户 ID，环境变量 MASTER_ADMINS。
     */
    val adminUserIds: Set<String>
        get() = env("MASTER_ADMINS", "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * 开发者账号 userId 允许列表（配置期静态，适合小规模私人部署）。
     * 格式：逗号分隔的用户 ID，环境变量 DEVELOPER_USER_IDS。
     * 失败闭合：未配置时拒绝所有开发者账号登录，防止任意已登录账号获取开发者会话。
     */
    val developerUserIds: Set<String>
        get() = env("DEVELOPER_USER_IDS", "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * 首次注册用户自动成为主管理员（一次性引导）。
     * 解决 MASTER_ADMINS 需要「先注册拿 ID、再配置、再重启」的鸡生蛋问题：
     * 自托管部署设置 true 后，第一个注册的账号自动获得完整后台权限。
     * 引导完成后应设回 false 并改用 MASTER_ADMINS（或依赖运行时集合）。
     */
    val bootstrapFirstUserAsAdmin: Boolean get() =
        env("BOOTSTRAP_FIRST_USER_AS_ADMIN", "false").toBooleanStrictOrNull() ?: false
    // SMTP 开发模式判断：必须与 env() 保持一致，同时读环境变量和系统属性
    val smtpDevMode: Boolean = env("SMTP_HOST", "").isBlank()

    fun validate() {
        // 无论生产/开发都强制校验 JWT_SECRET — 不再有弱默认值可以"漏过"
        require(jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters." }

        // 校验存储目录可写（如不存在则创建）
        val storagePath = java.io.File(storageDir).apply { mkdirs() }
        require(storagePath.isDirectory && storagePath.canWrite()) {
            "STORAGE_DIR must be a writable directory: ${storagePath.absolutePath}"
        }

        if (!isProduction) return

        require(baseUrl.startsWith("https://")) { "Production BASE_URL must use https://." }
        require(!databaseUrl.startsWith("jdbc:h2:mem:")) { "Production DATABASE_URL must be persistent; in-memory H2 is not allowed." }
        require(!databaseUrl.startsWith("jdbc:h2:file:")) { "Production DATABASE_URL should use PostgreSQL, not file-based H2." }
        require(!seedDemoUsers) { "Production must not seed demo users." }
        require(globalRateLimitPerMinute in 30..60_000) { "Production GLOBAL_RATE_LIMIT_PER_MINUTE must be between 30 and 60000." }

        if (relaxedVerification) {
            // 宽松模式：允许自托管环境缺 SMTP / TURN / PUSH_HMAC。
            if (smtpDevMode) {
                logger.warn("RELAXED_VERIFICATION: SMTP not configured — verification codes will only be printed to logs (set DEV_LOG_CODES=true).")
            }
            if (turnUrls.isEmpty()) {
                logger.warn("RELAXED_VERIFICATION: TURN not configured — calls will use STUN only, NAT-traversal reliability is reduced.")
            }
            if (pushHmacSecret == "dev-only-push-hmac-secret-not-for-prod") {
                logger.warn("RELAXED_VERIFICATION: PUSH_HMAC_SECRET not configured — FCM push signatures will not be verified (fail-open).")
            }
            return
        }

        require(!smtpDevMode) { "Production requires SMTP_HOST; email verification dev mode is not allowed. For self-hosted deployments without SMTP set RELAXED_VERIFICATION=true." }
        require(turnUrls.isNotEmpty()) { "Production requires TURN_URLS for reliable WebRTC connectivity. For self-hosted deployments set RELAXED_VERIFICATION=true." }
        require(turnSharedSecret.length >= 32) { "Production TURN_SHARED_SECRET must be at least 32 characters." }
        // OPENAI_API_KEY is optional: empty disables /api/ai/* with 503; chat still works.
    }

    private fun env(name: String, defaultValue: String): String {
        // 优先取真实环境变量（生产场景），其次取系统属性（便于测试覆盖）。
        return System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(name)?.takeIf(String::isNotBlank)
            ?: defaultValue
    }
}
