# Add project specific ProGuard rules here.

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.maodouchat.**$$serializer { *; }
-keepclassmembers class com.maodouchat.** {
    *** Companion;
}
-keepclasseswithmembers class com.maodouchat.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Room entities
-keep class com.maodouchat.data.local.entity.** { *; }
# B1 R8 精调（2026-08-01）：Room Database 类与 KSP 生成的 *_Impl 需保留（与 entity 规则同一风格的手工规则；
# room-runtime 自带 consumer 规则兜底，此处双保险）。不加宽 —— 不 keep 整个 data 包。
-keep class com.maodouchat.data.local.AppDatabase { *; }

# Keep Signal Protocol
-keep class org.signal.libsignal.protocol.** { *; }

# Keep WebRTC
# B1 审计：org.webrtc.** 的 JNI 方法按符号名被原生侧回调（nativeCreatePeerConnection 等），
# 且 PeerConnectionFactory 内部按反射装配，故保持整包 keep 是必要的，不可收窄到子集（避免原生崩溃）。
-keep class org.webrtc.** { *; }

# Compose / ViewModels do not need broad keep rules; keep only concrete reflection/native targets above.

# SQLCipher
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Tink / libsignal transitive (compile-only annotations)
-dontwarn com.google.errorprone.annotations.**
# B1 审计：OkHttp/Okio 自带的缺失类告警（conscrypt/bouncycastle/openjsse/animal-sniffer）已由
# 各库 consumer 规则处理；此处补充 animal_sniffer 注解依赖（与上面 errorprone 同类），不额外 keep。
-dontwarn org.codehaus.mojo.animal_sniffer.**

# B1 审计：Coil 2.x（coil-gif/coil-video）在 MaodouchatApp 中直接构造 GifDecoder.Factory /
# VideoFrameDecoder.Factory 传入 ImageLoader，无反射/JNI 需求，官方亦不要求 keep 规则，故不加。
# 新增的 com.maodouchat.slim.* 为 B1 基础设施：SizeGuard 由 Gradle verifyReleaseSize 在编译产物上直接
# 运行（不经 R8），OnDemandStickerStore 待主控接入后自然被引用保留 —— 不为其增加过度 keep。
