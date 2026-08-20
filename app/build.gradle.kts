import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun readGradleProperty(vararg names: String): String? {
    return names.firstNotNullOfOrNull { providers.gradleProperty(it).orNull?.takeIf(String::isNotBlank) }
}

val releaseBuildRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("Release", ignoreCase = true) || taskName.contains("bundle", ignoreCase = true)
}

// Release 版本可在构建时覆盖：-PMAODOU_VERSION_NAME=1.2.3 -PMAODOU_VERSION_CODE=123
// （GitHub Release workflow 从 tag/输入传入；不传则回退默认 1.0 / 1）
val releaseVersionName: String = readGradleProperty("MAODOU_VERSION_NAME") ?: "1.0"
val releaseVersionCode: Int = readGradleProperty("MAODOU_VERSION_CODE")?.toIntOrNull() ?: 1

val firebaseProjectId = readGradleProperty("MAODOU_FIREBASE_PROJECT_ID").orEmpty()
val firebaseApplicationId = readGradleProperty("MAODOU_FIREBASE_APPLICATION_ID").orEmpty()
val firebaseApiKey = readGradleProperty("MAODOU_FIREBASE_API_KEY").orEmpty()
val firebaseSenderId = readGradleProperty("MAODOU_FIREBASE_SENDER_ID").orEmpty()

// B1 体积护栏基线（字节）：默认 14MB，:app:verifyReleaseSize 超限即失败（可 -PMAODOU_SIZE_BASELINE_BYTES 覆盖）
// 2026-08 实测 release APK ≈ 12.0MB（Compose + Signal + WebRTC + AI），10MB 基线过紧。
val slimBaselineBytes: Long =
    (readGradleProperty("MAODOU_SIZE_BASELINE_BYTES")?.toLongOrNull()?.takeIf { it > 0L } ?: 14L * 1024L * 1024L)

// B1: libsignal AAR 内置致谢文档 assets/acknowledgments（~327KB，应用未引用）。
// 实测 packaging.resources.excludes 对 AAR 的 assets 不生效（AGP 限制，只处理 java resources），
// 改为在 merge*Assets 输出后直接删除——构建期纯增量，运行时不读取该文档。
// 用 configureEach 惰性匹配（onVariants 回调时机早于 merge 任务注册，tasks.named 会找不到）。
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        doLast {
            outputs.files.forEach { out ->
                val ack = File(out, "acknowledgments")
                if (ack.exists()) ack.deleteRecursively()
            }
        }
    }
}

android {
    namespace = "com.maodouchat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maodouchat"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        buildConfigField("String", "API_BASE_URL", "http://10.0.2.2:8080".asBuildConfigString())
        buildConfigField("String", "WS_URL", "ws://10.0.2.2:8080/ws".asBuildConfigString())
        buildConfigField("String", "FIREBASE_PROJECT_ID", firebaseProjectId.asBuildConfigString())
        buildConfigField("String", "FIREBASE_APPLICATION_ID", firebaseApplicationId.asBuildConfigString())
        buildConfigField("String", "FIREBASE_API_KEY", firebaseApiKey.asBuildConfigString())
        buildConfigField("String", "FIREBASE_SENDER_ID", firebaseSenderId.asBuildConfigString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // 只保留中英文资源，删除其他语言资源（~100KB 节省）
        androidResources {
            localeFilters += setOf("zh", "en")
        }
    }

    // WebRTC 原生库（~9.86MB）不进基础 APK：侧载/非 Play 渠道由运行时从自服下载
    // （见 call/WebRtcNativeLibraryLoader.kt），Play 渠道由特性模块下发。动态特性模块
    // 已移除（:feature_call），fusing=false 下侧载渠道本就无法获得该库。

    // Release 签名：从环境变量读取（CI 用 GitHub Secrets：KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD）。
    // 未配置时 release 构建为未签名（可构建、不可安装），本地开发不受影响。
    signingConfigs {
        val ksFile = System.getenv("KEYSTORE_FILE")
        val ksPass = System.getenv("KEYSTORE_PASSWORD")
        val kAlias = System.getenv("KEY_ALIAS")
        val kPass = System.getenv("KEY_PASSWORD")
        if (!ksFile.isNullOrBlank() && !ksPass.isNullOrBlank() && !kAlias.isNullOrBlank() && !kPass.isNullOrBlank()) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = ksPass
                keyAlias = kAlias
                keyPassword = kPass
            }
        }
    }

    buildTypes {
        debug {
            val debugApiBaseUrl = readGradleProperty("MAODOU_API_BASE_URL") ?: "http://10.0.2.2:8080"
            val debugWsUrl = readGradleProperty("MAODOU_WS_URL") ?: "ws://10.0.2.2:8080/ws"
            buildConfigField("String", "API_BASE_URL", debugApiBaseUrl.asBuildConfigString())
            buildConfigField("String", "WS_URL", debugWsUrl.asBuildConfigString())
        }

        release {
            val releaseApiBaseUrl = readGradleProperty("MAODOU_RELEASE_API_BASE_URL", "MAODOU_API_BASE_URL")
            val releaseWsUrl = readGradleProperty("MAODOU_RELEASE_WS_URL", "MAODOU_WS_URL")

            if (releaseBuildRequested) {
                require(!releaseApiBaseUrl.isNullOrBlank()) {
                    "Release API_BASE_URL must be set with -PMAODOU_RELEASE_API_BASE_URL=https://..."
                }
                require(!releaseWsUrl.isNullOrBlank()) {
                    "Release WS_URL must be set with -PMAODOU_RELEASE_WS_URL=wss://..."
                }
            }

            val checkedReleaseApiBaseUrl = releaseApiBaseUrl ?: "https://invalid.maodouchat.local"
            val checkedReleaseWsUrl = releaseWsUrl ?: "wss://invalid.maodouchat.local/ws"

            require(checkedReleaseApiBaseUrl.startsWith("https://")) {
                "Release API_BASE_URL must use https://"
            }
            require(checkedReleaseWsUrl.startsWith("wss://")) {
                "Release WS_URL must use wss://"
            }

            buildConfigField("String", "API_BASE_URL", checkedReleaseApiBaseUrl.asBuildConfigString())
            buildConfigField("String", "WS_URL", checkedReleaseWsUrl.asBuildConfigString())
            // 环境变量配置了签名时对 release APK 签名（GitHub Release 构建必填）
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += setOf(
            "ObsoleteLintCustomCheck", // Compose runtime lint bundled with current toolchain is API-incompatible.
            "ObsoleteSdkInt" // Adaptive launcher icons must remain in mipmap-anydpi-v26 for AAPT compatibility.
        )
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "**/*.dll",
                "**/*.dylib",
                "signal_jni.dll",
                "libsignal_jni.dylib",
                // 各 AAR 的 LICENSE/NOTICE 文本（应用不需要，节省 ~120KB）
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE",
                "META-INF/**/LICENSE.txt",
                "META-INF/**/LICENSE",
                "META-INF/**/NOTICE.txt",
                "META-INF/**/NOTICE",
                // libsignal AAR 内置的致谢文档（~327KB，应用未引用）
                "assets/acknowledgments/**",
                "**/acknowledgments/**",
                "**/acknowledgments/libsignal.md",
            )
        }
        jniLibs {
            excludes += setOf(
                "**/*.dll",
                "**/*.dylib",
                // WebRTC .so 不进基础 APK：运行时从自服下载后 System.load 预加载，
                // 基础 APK 减 ~9.86MB（见 call/WebRtcNativeLibraryLoader.kt）
                "**/libjingle_peerconnection_so.so",
            )
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir(file("schemas"))
    }
}

// B1 体积护栏：重建 Release APK 后统计体积分解并校验 ≤ 基线（默认 14MB），超限任务失败（阻断发版）。
// 用法：./gradlew.bat :app:verifyReleaseSize -PMAODOU_RELEASE_API_BASE_URL=https://... -PMAODOU_RELEASE_WS_URL=wss://...
// 统计逻辑见 com.maodouchat.slim.SizeGuard（纯 JVM，读 APK 分解 dex/.so/资源占比）。
tasks.register<JavaExec>("verifyReleaseSize") {
    group = "verification"
    description = "体积护栏：检查 Release APK 体积 ≤ 基线（默认 14MB），超限即失败"
    dependsOn("assembleRelease")
    classpath = files(
        layout.buildDirectory.dir("intermediates/javac/release/classes"),
        layout.buildDirectory.dir("tmp/kotlin-classes/release"),
        configurations.named("releaseRuntimeClasspath")
    )
    mainClass.set("com.maodouchat.slim.SizeGuard")
    args(
        layout.buildDirectory.dir("outputs/apk/release").get().asFile.absolutePath,
        slimBaselineBytes.toString()
    )
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", file("schemas").path)
    arg("room.incremental", "true")
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.05.00")
    implementation(composeBom)
    // 9.294：Liquid Glass 真实实现（Murexide 同款）——backdrop 实时采样模糊 + lens +
    // highlight + innerShadow，用于主界面玻璃悬浮底栏。
    // 用 alpha03：正式版 2.0.0 要求 compileSdk 37，项目锁定 36（AGP 8.13 最高支持）
    implementation("io.github.kyant0:backdrop:2.0.0-alpha03")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // material-icons-extended 已替换为本地图标副本 (ExtendedIcons.kt)，减少 ~1-2MB debug APK
    // 注：本地副本从未落地，缺失的扩展图标（EditNote/ContentCopy/ContactPage 等）导致编译失败；
    // 恢复 material-icons-extended（release 构建 R8 会裁掉未用图标，体积影响仅在 debug APK）。
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Coil (Image Loading) + Video frame decoder
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-gif:2.5.0")
    implementation("io.coil-kt:coil-video:2.5.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Encrypted local database
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    // sqlite-ktx 已移除：Room 和 SQLCipher 已传递提供 androidx.sqlite:sqlite，
    // 且代码中未使用任何 sqlite-ktx Kotlin 扩展函数

    // Signal Protocol (libsignal Android AAR includes JNI libraries)
    implementation("org.signal:libsignal-android:0.41.0")

    // WebRTC (Google's prebuilt) -- 原生库在 :feature_call 动态特性模块中打包
    implementation("io.getstream:stream-webrtc-android:1.1.1")

    // 二维码（ZXing core + 集成 CaptureActivity）
    // B1 依赖审计（2026-08-01）：
    // - com.google.zxing:core 必须保留：QrCodeGenerator 用于安全码/身份指纹/群邀请等 E2EE 核验 QR 的「生成」。
    // - zxing-android-embedded 仅用于 ContactSubScreens 的「扫码」UI（ScanContract/ScanOptions，CaptureActivity）。
    //   其 AAR（布局/主题/解码管线）R8 收缩后仍贡献约 300KB；可替换为 com.maodouchat.slim 自定义 QRCodeReader
    //   解码器后移除（需同步修改 ContactSubScreens 扫码调用点——该文件不在 B1 允许改动清单内，故本轮保留，
    //   见 docs/size-baseline.md §3 依赖审计结论）。维持实现以保证扫码功能可用。
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Lifecycle ViewModel Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Background retry jobs
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Optional data-only FCM. Firebase is initialized manually when Gradle properties are present.
    // B1 审计（2026-08-01）：不可改 compileOnly——AndroidManifest 无条件注册了
    // MaodouFirebaseMessagingService（extends FirebaseMessagingService，action com.google.firebase.MESSAGING_EVENT），
    // 缺库会导致 GMS 绑定服务时 NoClassDefFoundError。R8 已裁剪未用 Firebase 路径。
    implementation("com.google.firebase:firebase-messaging:24.1.2")

    // Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Unit test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.8")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // 9.291：显式声明 runner——testInstrumentationRunner 指向 AndroidJUnitRunner，但 ext:junit 1.2.1
    // 不再传递引入 androidx.test:runner，导致仪器测试启动即 ClassNotFoundException 崩溃（0 tests）
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    // Robolectric 需要从互联网下载 Android SDK 镜像；在受限网络环境下无法运行。
    // 需要接入内网 mirror 后取消注释以下两行即可启用：
    // testImplementation("org.robolectric:robolectric:4.11.1")
    // testImplementation("androidx.test:core-ktx:1.5.0")
}
