[S1] Android build graph contains 9 new modules: core/model, core/util, core/serialization, core/database, core/network, core/crypto, core/session, domain/messaging, feature/chat.
[S2] Messaging runtime rebuild must cancel 2 long-lived collectors (WebSocket event and polling loop) before closing SQLCipher.
[S3] Public update payload requires a 64-character SHA-256 digest in addition to versionCode, versionName, and apkUrl.
[S4] Update installation accepts an APK only when SHA-256, package name, and signer set match the installed application.
[S5] External telecom actions require a non-empty active or pending in-process call id before changing lock-screen state.
[S6] New Android library modules compile with Java and Kotlin JVM target 17 while Gradle runs on JDK 21.
