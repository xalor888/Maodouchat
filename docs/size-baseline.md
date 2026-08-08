# 包体基线

**用途**：跟踪 Release APK 体积，防止发版膨胀。
**关联**：`app/build.gradle.kts`（R8 + 资源收缩 + `verifyReleaseSize` 护栏任务）、`docs/release-checklist.md`、B1 区块（`docs/feature-vision.md` §2 B1）。

---

## 1. 构建配置

- Release 仅打包 **arm64-v8a**（`ndk { abiFilters += "arm64-v8a" }`）
- R8 全模式（`minifyEnabled = true`、`shrinkResources = true`、`android.enableR8.fullMode=true`）
- 资源收缩：`android.enableNewResourceShrinking=true` + 语言资源仅保留 `zh` / `en`
- `proguard-rules.pro` 保留 libsignal / WebRTC / Room / Coil / Kotlinx Serialization（详见该文件 B1 审计注释）
- WebRTC 原生库 `libjingle_peerconnection_so.so`（~9.86MB）**不进入 APK**：运行时从自服下载（`call/WebRtcNativeLibraryLoader.kt`）
- 剔除各 AAR 的 LICENSE/NOTICE/acknowledgments 文本（~450KB）

## 2. 基线（B1，2026-08-01）

> 旧包 ~19.7MB（含已废弃的 9.86MB WebRTC `.so`）；用当前排除规则重建 ≈ 9~10MB，目标 **≤ 10MB**。

| 版本 | APK 大小 | AAB 大小 | 日期 | 备注 |
|------|---------|---------|------|------|
| 1.0 (B1 实测) | **12.3MB**（12,328,015 B） | 待测量 | 2026-08-01 | 从 19.7MB（旧包含 9.86MB WebRTC .so）降至 12.3MB；**未达 ≤10MB 严格目标**（差 2.3MB），主因：8 个功能区块新增代码使 dex 翻倍（4.7→8.5MB）。构成：dex 8.5MB + libsignal 3.7MB + sqlcipher 3.5MB + 其他 ~0.5MB；acknowledgments 排除规则已加宽待下次构建验证 |

**护栏命令**（重建后由主控执行并回填实测值）：

```bash
./gradlew.bat :app:verifyReleaseSize \
  -PMAODOU_RELEASE_API_BASE_URL=https://api.example.com \
  -PMAODOU_RELEASE_WS_URL=wss://api.example.com/ws
```

- 任务会先 `assembleRelease`，再由 `com.maodouchat.slim.SizeGuard` 打印 APK 体积分解（dex / .so / 资源 / assets / META-INF 各占字节与百分比、最大的 .so 清单）；
- APK ≤ 基线（默认 10MB，`gradle.properties` 的 `maodou.sizeBaselineBytes`，可 `-PMAODOU_SIZE_BASELINE_BYTES=` 覆盖）→ 通过；超限 → 任务失败（阻断发版）。

## 3. 依赖审计结论（B1，2026-08-01）

逐条核对后，**本轮无安全可删依赖**：所有 `implementation` 均被代码直接引用，删除会导致编译/运行不可用。结论与说明：

| 依赖 | 结论 | 说明 |
|------|------|------|
| Coil `coil-compose` / `coil-gif` / `coil-video` | 保留 | GIF/视频帧解码在 `MaodouchatApp` 直接构造 Factory 使用；无反射/JNI，R8 无需 keep |
| Room 2.8.4 | 保留 | 数据库核心；已补 `AppDatabase` keep（KSP 生成 `*_Impl`） |
| `com.google.zxing:core` | 保留 | `QrCodeGenerator` 用于安全码 / 身份指纹 / 群邀请 QR 的**生成**（E2EE 核验核心） |
| `zxing-android-embedded` | 保留（**最大可删候选**） | 仅用于 `ContactSubScreens` 扫码 UI（`ScanContract`/`ScanOptions`，CaptureActivity）。R8 后仍约贡献 300KB；替换为 `com.maodouchat.slim` 自定义 `QRCodeReader` 解码器后可移除，但需同步改 `ContactSubScreens` 调用点（不在 B1 允许改动清单，列为后续任务） |
| `stream-webrtc-android` | 保留 | 通话核心；原生 `.so` 已排除，Java 类被 `WebRTCManager` 引用，R8 裁剪未用路径 |
| `firebase-messaging` | 保留（不可改 compileOnly） | Manifest 无条件注册 `MaodouFirebaseMessagingService`（extends `FirebaseMessagingService`），缺库将导致 GMS 绑定服务时 `NoClassDefFoundError` |
| `security-crypto` | 保留 | `TokenManager` / `DatabasePassphraseProvider` 加密存储 |
| `android-database-sqlcipher` | 保留 | 本地加密库（E2EE 安全基线），arm64 `.so` 仅单 ABI |
| `material-icons-core` | 保留 | 基础图标（`Icons.Filled.Check` 等）应用内广泛使用；`material-icons-extended` 早已被本地 `ExtendedIcons.kt` 副本替代 |
| `biometric` / `fragment-ktx` / `work-runtime-ktx` / OkHttp / coroutines | 保留 | App 锁、后台任务、网络均直接使用 |

## 4. 按需贴纸（B1，2026-08-01）

新增 `com.maodouchat.slim.OnDemandStickerStore`（构建期不含贴纸二进制，全部改为运行时按需下载）：

- **清单**：`GET {ApiConfig.BASE_URL}/api/stickers/manifest.json`（10 分钟 TTL 内存缓存，失败静默回退内置表情）
- **下载**：贴纸文件到 `filesDir/stickers/<packId>/`，`.part` 临时文件 + SHA-256 校验 + 原子重命名
- **LRU 淘汰**：包数 > 6 或总字节 > 24MB 时按最近访问淘汰
- **取用**：`getSticker(name)` 本地命中直接返回，未命中查清单下载
- **内置裁剪为最小集合**：仅保留 1 包「基础表情」（24 个 emoji 文本，不占二进制体积）
- 服务端清单约定见该文件 KDoc；既有 `StickerCatalog`（emoji 文本贴纸）为业务功能文件，B1 未改动

## 5. 膨胀阈值

- APK > 基线 + 10% -> 阻断发版，需排查（`verifyReleaseSize` 任务失败）
- APK > 基线 + 5% -> 警告，需记录原因
- 新增 .so -> 评估是否必须（WebRTC 已按需下载模式）
- 新增依赖 -> 先走 §3 审计流程，确认无法用现有实现替代

## 6. 测量方法

```bash
./gradlew.bat :app:assembleRelease \
  -PMAODOU_RELEASE_API_BASE_URL=https://api.example.com \
  -PMAODOU_RELEASE_WS_URL=wss://api.example.com/ws

ls -lh app/build/outputs/apk/release/app-release.apk
```

或直接使用护栏任务（含体积分解报告）：

```bash
./gradlew.bat :app:verifyReleaseSize \
  -PMAODOU_RELEASE_API_BASE_URL=https://api.example.com \
  -PMAODOU_RELEASE_WS_URL=wss://api.example.com/ws
```

AAB：
```bash
./gradlew.bat :app:bundleRelease \
  -PMAODOU_RELEASE_API_BASE_URL=https://api.example.com \
  -PMAODOU_RELEASE_WS_URL=wss://api.example.com/ws
```

## 7. 减包手段（执行状态）

- [x] WebRTC `.so` 排除，运行时自服下载（`call/WebRtcNativeLibraryLoader.kt`）
- [x] 剔除 LICENSE / NOTICE / acknowledgments 资源（`packaging` excludes）
- [x] 语言资源只保留 zh / en（`localeFilters`）
- [x] `material-icons-extended` → 本地图标副本（`ExtendedIcons.kt`）
- [x] 贴纸包按需下载（`OnDemandStickerStore`，不内置二进制）
- [x] R8 全模式 + 资源收缩 + 体积护栏任务（`verifyReleaseSize`）
- [ ] 移除 `zxing-android-embedded`（待自定义解码器落地 `ContactSubScreens` 调用点后移除，预计 -300KB）
- [ ] 生成并启用 Baseline Profile（冷启动优化，属 B1 后续项）
- [ ] 实测回填 §2 基线表
