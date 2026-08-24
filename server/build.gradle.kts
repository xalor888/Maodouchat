import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("application")
}

application {
    mainClass.set("com.maodouchat.server.ApplicationKt")
}

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.7"
val exposedVersion = "0.46.0"

// 9.303：仅用于提取 WebRTC .so 的独立配置（不进运行时 classpath）
val webrtcNative by configurations.creating { isTransitive = false }

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    // Ktor Client for AI Gateway
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    // H2 Database
    implementation("com.h2database:h2:2.2.224")
    // PostgreSQL driver for Docker/production deployments
    implementation("org.postgresql:postgresql:42.7.4")

    // 8.31 运维修复 CRITICAL：数据库连接池（Exposed 0.46 的 Database.connect(url, driver)
    // 不再自动建池，每次事务裸连 DB；HikariCP 提供复用、超时与泄漏检测）
    implementation("com.zaxxer:HikariCP:5.1.0")

    // JWT
    implementation("com.auth0:java-jwt:4.4.0")

    // Verify device-approval signatures made by Signal identity keys.
    implementation("org.signal:libsignal-client:0.41.0")

    // 9.303：WebRTC 原生库仅用于构建期提取 .so（GET /api/webrtc/lib/{abi} 自服下载）。
    // 此前 .so 靠 scripts/webrtc-sync-native.sh 手工同步到 resources 且被 gitignore/dockerignore
    // 双重排除 → 生产镜像永远缺库 → 通话首次拉库 503，语音/视频通话全挂。
    // 现改为构建期从 Maven 依赖自动提取，Docker 构建自足。
    "webrtcNative"("io.getstream:stream-webrtc-android:1.1.1")

    // BCrypt
    implementation("at.favre.lib:bcrypt:0.10.2")

    // Linear-time regular expressions for user-configurable moderation rules.
    implementation("com.google.re2j:re2j:1.8")

    // JavaMail
    implementation("com.sun.mail:jakarta.mail:2.0.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Test
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("io.ktor:ktor-client-core:$ktorVersion")
}

// 9.303：构建期从 stream-webrtc-android AAR 提取 arm64-v8a 的 libjingle_peerconnection_so.so
// 到资源目录（服务端 WebRtcBinaryService 从 classpath /webrtc/... 惰性解压提供下载）。
// 手工同步的 server/src/main/resources/webrtc/ 被 gitignore，Docker 构建拿不到，
// 只能靠此任务在 installDist 前落盘。本地已存在的同步文件不重复覆盖。
val extractWebRtcNativeLib = tasks.register("extractWebRtcNativeLib") {
    val destDir = layout.projectDirectory.dir("src/main/resources/webrtc")
    val abis = listOf("arm64-v8a", "x86_64")
    outputs.files(abis.map { destDir.file("$it/libjingle_peerconnection_so.so") })
    doLast {
        val aar = webrtcNative.singleFile
        ZipFile(aar).use { zip ->
            for (abi in abis) {
                val dest = destDir.file("$abi/libjingle_peerconnection_so.so").asFile
                if (dest.isFile) continue
                dest.parentFile.mkdirs()
                val entry = zip.getEntry("jni/$abi/libjingle_peerconnection_so.so")
                    ?: error("webrtc aar missing jni/$abi/libjingle_peerconnection_so.so")
                zip.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
                logger.lifecycle("extracted WebRTC native lib ($abi) to $dest")
            }
        }
    }
}

tasks.named("processResources") { dependsOn(extractWebRtcNativeLib) }

tasks.withType<Test> {
    useJUnitPlatform()
    // Ktor 2.3.7 + H2 in-memory + Exposed TransactionManager 在多个 testApplication
    // 同进程下互相串台；强制每个测试方法跑在独立 JVM 进程 → 消除 flake。
    forkEvery = 1
    maxParallelForks = 1
    // 9.218：测试环境隔离——开发者 shell 中导出的服务器 env（如 E2E 起服务时 export 的
    // JWT_SECRET/MASTER_ADMINS/DATABASE_URL）会泄漏进测试 JVM：ServerConfig.env() 优先取真实
    // 环境变量，压过测试的 System.setProperty 覆盖，导致 secret 切换/角色断言等用例失败。
    // 这里统一剥离已知干扰键（2026-08-20 实测复现并验证修复）。
    listOf(
        "JWT_SECRET", "MASTER_ADMINS", "MODERATOR_EMAILS", "DATABASE_URL", "DATABASE_DRIVER",
        "APP_ENV", "BASE_URL", "SEED_DEMO_USERS", "HOST", "PORT", "SMTP_HOST",
        "ADMIN_E2E_BASE_URL", "SERVER_NAME", "PUBLIC_SITE", "ALLOW_REGISTRATION"
    ).forEach { environment.remove(it) }
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("postgres") }
}

tasks.register<Test>("postgresIntegrationTest") {
    description = "Runs isolated integration tests against POSTGRES_TEST_DATABASE_URL."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("postgres") }
    maxParallelForks = 1
    forkEvery = 0
    shouldRunAfter(tasks.named("test"))
}
