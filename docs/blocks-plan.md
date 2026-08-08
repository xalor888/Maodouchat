# Maodouchat 区块开发计划（多 Agent 并行）

**更新日期**：2026-08-01
**配套**：功能清单见 `docs/feature-vision.md`；每个区块的完整 Agent 提示词见 `docs/agent-prompts/`。

---

## 0. 并行策略总则

1. **8 个区块互不重叠**：每个区块拥有专属文件集合，可安全并行、互不阻塞。
2. **共享大文件红线**（所有区块适用）：
   - ❌ 禁止修改：`app/.../ui/screen/chatdetail/ChatDetailViewModel.kt`、`ChatDetailScreen.kt`、`app/.../ui/screen/chatlist/ChatListScreen.kt`、`server/.../plugins/Routing.kt`、`app/.../util/GroupPlayPolicy.kt`
   - ✅ 服务端新路由：新建 `server/src/main/kotlin/com/maodouchat/server/plugins/<区块>Routing.kt`，并在 `server/.../Application.kt` 的 `embeddedServer {}` 块**末尾追加一行**注册（只追加，不改现有行）。
   - ✅ 新字符串：在 `app/src/main/res/values/strings.xml` 与 `values-en/strings.xml` **成对追加**（保持中英对称，B8 会校验）。
3. **验收口径**：只写代码，不做测试与编译（用户指定）；全部区块完成后由主控统一执行 `run_verify.bat` / `gradlew :app:assembleRelease`。
4. **代码质量**：新代码必须可编译风格（不引入未用 import、不引入新错误）；新增文件写清楚 KDoc 头部。

---

## B1 — 包体瘦身与构建基线（目标 APK ≤ 10MB）

**目标**：Release APK ≤ 10MB，建立体积护栏。
**专属文件**：
- `app/build.gradle.kts`（依赖/资源/打包配置）
- `app/proguard-rules.pro`
- `gradle.properties`
- `app/src/main/res/values/`（仅追加字符串，配合按需贴纸）
- 新文件：`app/src/main/java/com/maodouchat/slim/OnDemandStickerStore.kt`、`app/src/main/java/com/maodouchat/slim/SizeGuard.kt`
- `docs/size-baseline.md`（更新基线）

**步骤**：
1. 审计 `app/build.gradle.kts` 依赖（Coil/Room/ZXing/stream-webrtc 等），移除未用传递依赖；确认 `jniLibs.excludes` 排除 WebRTC `.so`、`packaging.resources.excludes` 排除 ack/LICENSE。
2. 贴纸/GIF 改为按需下载：新增 `OnDemandStickerStore`（从服务端拉取贴纸包、缓存到 `filesDir`，LRU 淘汰），内置贴纸裁剪到最小必要集合。
3. 精调 R8 规则与 `gradle.properties`（已有 `fullMode`/`resourceShrinking`），避免误删反射类。
4. 更新 `docs/size-baseline.md` 基线表与膨胀阈值。
5. 不触碰 `ChatDetail*`、`ChatListScreen.kt`。

**提示词**：`docs/agent-prompts/B1-size-slash.md`

---

## B2 — 密聊防泄漏栈扩展（Surface #71–#78）

**目标**：在既有「RuntimeConfig → /api/public/status → Prefs → UI 门控 → Bot → Admin」surface 模式上新增 8 个密聊隐私 surface。
**专属文件**：
- 新：`app/.../util/SecretAutoDestroyPrefs.kt`、`SecretScreenshotBurnPrefs.kt`、`SecretForwardWhitelistPrefs.kt`、`SecretSimChangePrefs.kt`、`Secret2faGatePrefs.kt`、`SecretNewDeviceRiskPrefs.kt`、`SecretDeviceVerifyPrefs.kt`、`SecretSessionNoticePrefs.kt`
- 新：`app/.../security/ScreenshotBurnDetector.kt`、`SimChangeWatcher.kt`、`SecretSessionTtl.kt`
- 新：`server/.../plugins/SecretSurfaceRouting.kt`（新增 `/api/public/status` 扩展字段 + hint 路由）
- 追加：`server/.../service/RuntimeConfigService.kt`（末尾追加新 keys）
- 追加：`server/src/main/resources/admin/admin.js`（末尾追加开关行）、`server/src/main/resources/public/index.html`
- 追加：`app/.../res/values/strings.xml` + `values-en/strings.xml`（成对）

**步骤**：
1. 先读 `server/.../service/RuntimeConfigService.kt` 了解 knownKeys 模式，末尾追加 8 个 key（带默认值）。
2. 按现有 `SecretXxxPrefs` 模式新建 8 个 Prefs 文件（EncryptedSharedPreferences）。
3. 实现行为门控（新文件实现，如 `ScreenshotBurnDetector` 基于现有 `ScreenshotDetector` 模式扩展）。
4. 新增 hint 路由 + Bot flags + Admin 开关行 + 群玩法/Markdown 快捷符（如 `~sd` 自毁、`~sb` 截屏即焚）。
5. strings 成对追加；不触碰巨型文件。

**提示词**：`docs/agent-prompts/B2-secret-surfaces.md`

---

## B3 — 群聊玩法扩展

**目标**：群投票、群签到、群接龙、PK 互动 + 3~5 个新 Markdown 快捷玩法。
**专属文件**：
- 新：`server/.../plugins/PollRouting.kt`（群投票 API）
- 新：`server/.../repository/PollRepository.kt`、`server/.../repository/GroupCheckinRepository.kt`
- 新：`server/.../db/PollTables.kt`（表定义，并在 `Database.kt` 的 initDatabase 列表**末尾追加**）
- 新：`app/.../ui/screen/groupplay/GroupPollScreen.kt`、`GroupCheckinScreen.kt`
- 新：`app/.../util/GroupPollPolicy.kt`、`GroupCheckinPolicy.kt`、`GroupChainPolicy.kt`
- 追加：`app/.../res/values/strings.xml` + `values-en/strings.xml`

**步骤**：
1. 服务端：新表（投票/选项/记录、签到记录）+ 新路由文件（REST：创建投票、投票、结果、签到、排行）。
2. 客户端：群详情/聊天内入口（新 Screen + Policy），列表/结果 UI。
3. 群玩法：新增 Markdown 快捷符玩法（`~vote`、`~checkin`、`~chain`、`~pk` 等），在 `GroupPlayData.kt` 以新文件方式扩展（不修改 `GroupPlayPolicy.kt`）。
4. strings 成对追加。

**提示词**：`docs/agent-prompts/B3-group-play.md`

---

## B4 — AI 智能增强

**目标**：会话画像、智能归档建议、群周报、情绪感知回复、跨聊天问答、消息分类。
**专属文件**：
- 新：`app/.../ai/AiConversationProfile.kt`、`AiArchiveSuggestion.kt`、`AiWeeklyReport.kt`、`AiEmotionReply.kt`、`AiCrossChatQa.kt`、`AiMessageClassifier.kt`
- 新：`app/.../data/repository/AiProfileRepository.kt`
- 新：`server/.../plugins/AiEnhanceRouting.kt`、`server/.../service/AiEnhanceService.kt`
- 追加：`app/.../network/AiApiModels.kt`（末尾追加 model 类）

**步骤**：
1. 读现有 `server/.../service/AiGatewayService.kt` 与 `AiApiModels.kt`，复用统一 Gateway。
2. 客户端：会话画像/归档建议/分类为**纯本地**（SQLCipher + 规则），不依赖服务端明文。
3. 群周报/情绪回复/跨聊天问答走服务端 AI Gateway（严格复用现有授权、聊天开关、白名单机制）。
4. 新路由文件注册进 `Application.kt` 末尾；不触碰 `Routing.kt`。

**提示词**：`docs/agent-prompts/B4-ai-enhance.md`

---

## B5 — 系统集成与体验

**目标**：主屏幕小组件、通知快捷回复、悬浮球、平板双栏、手势优化。
**专属文件**：
- 新：`app/src/main/java/com/maodouchat/widget/ConversationWidgetProvider.kt`、`ConversationWidgetService.kt`、`widget_info.xml`
- 新：`app/src/main/java/com/maodouchat/quickreply/QuickReplyPolicy.kt`
- 新：`app/src/main/java/com/maodouchat/floating/FloatingBubble.kt`、`FloatingBubbleService.kt`
- 新：`app/src/main/java/com/maodouchat/ui/layout/AdaptiveLayout.kt`（双栏）
- 修改（**仅本区块可动**）：`app/src/main/AndroidManifest.xml`（追加组件声明）、`app/.../ui/navigation/NavGraph.kt`（末尾追加路由）、`app/.../MainActivity.kt`（末尾追加入口）
- 追加：strings 成对

**步骤**：
1. 小组件：AppWidgetProvider 展示未读会话/最近消息，点击打开对应会话。
2. 快捷回复：通知 Action + RemoteInput 直接回复文本（复用现有发信路径）。
3. 悬浮球：前台服务 + 悬浮窗（需要权限，设置页引导）。
4. 平板双栏：`AdaptiveLayout` 按窗口宽度切换单/双栏。
5. 手势：滑动切换会话。

**提示词**：`docs/agent-prompts/B5-system-integration.md`

---

## B6 — 服务端运维与管理后台增强

**目标**：系统公告广播、用户标签、审计增强、限流仪表盘、多设备一致性加固。
**专属文件**：
- 新：`server/.../plugins/AdminEnhanceRouting.kt`、`server/.../repository/AnnouncementRepository.kt`、`UserTagRepository.kt`、`RateLimitStatsRepository.kt`
- 新：`server/.../db/AdminTables.kt`（追加表）
- 追加：`server/.../plugins/AdminRouting.kt`（末尾追加，不删行）
- 追加：`server/src/main/resources/admin/admin.html|css|js`（末尾追加区块）
- 追加：`app/.../notification/NotificationCenterRepository.kt`（仅追加广播消息类型处理，不重构）

**步骤**：
1. 公告：管理员创建/编辑/撤回广播；App 通知中心新类型展示（复用 `NotificationCenterRepository` 追加类型，不重构）。
2. 用户标签：后台 CRUD + 风控规则按标签匹配（复用 `ModerationRuleRepository` 追加字段，不删行）。
3. 审计：导出支持时间范围与批量。
4. 限流统计：`RateLimit.kt` 已有命中计数则暴露给后台，缺则加轻量统计。
5. 多设备：完善设备变更事件推送确认。

**提示词**：`docs/agent-prompts/B6-admin-ops.md`

---

## B7 — 性能与流畅度

**目标**：冷启动、列表流畅度、数据库索引、图片内存、动效预算。
**专属文件**：
- 新：`app/.../perf/StartupTracer.kt`、`ListScroller.kt`、`ImageMemoryPolicy.kt`
- 修改（**仅本区块可动 Room schema**）：`app/.../data/local/AppDatabase.kt`（追加 migration 版本）、`app/.../data/local/dao/*Dao.kt`（仅追加索引注解）
- 更新：`docs/ui-motion-performance-budget.md`
- 追加：strings 成对

**步骤**：
1. 冷启动：检查首帧路径，懒加载非关键初始化（现有已延迟 DB/Signal，补充剩余热点）。
2. 列表：`ChatListScreen.kt` **不可修改**——通过新 `ListScroller`/缓存组件间接优化或仅在明确边界内追加；优先优化图片加载与占位。
3. 索引：为 `MessageDao`/`ChatDao` 高频查询追加索引（Room migration 追加版本）。
4. 图片：采样率、LRU 缓存策略。
5. 动效预算文档更新。

**提示词**：`docs/agent-prompts/B7-performance.md`

---

## B8 — 中英文全面性与质量

**目标**：strings 对称、无障碍、术语一致、深色模式细节。
**专属文件**：
- 修改：`app/src/main/res/values/strings.xml`、`values-en/strings.xml`（只修不对称，不删已有 name）
- 修改：`app/.../ui/theme/`（对比度/动态色）
- 追加：无障碍属性（contentDescription 等，只追加不改现有）
- 运行（仅静态脚本，不编译）：`scripts/check-string-parity.py`、`scripts/check-brand-terminology.py`，输出修复报告

**步骤**：
1. 跑 parity 脚本，修复所有不对称条目（只补缺、不改已有文案含义）。
2. 扫无障碍缺口（无 description 的 IconButton 等），追加描述。
3. 术语统一：`Maodouchat / 毛豆聊天 / 密聊 / E2EE` 等用词在 UI 文案与文档一致。
4. 深色模式高对比检查。

**提示词**：`docs/agent-prompts/B8-i18n-quality.md`

---

## 5. 收尾（全部区块完成后，由主控执行）

1. `source scripts/use-jdk21.sh`（或按 README 设 JAVA_HOME）
2. `./gradlew.bat :app:compileDebugKotlin` 全量编译
3. `./gradlew.bat :app:assembleRelease -PMAODOU_RELEASE_API_BASE_URL=... -PMAODOU_RELEASE_WS_URL=...` 验证 APK ≤ 10MB
4. `cd server && ../gradlew.bat compileKotlin`
5. 跑 `scripts/check-string-parity.py` 确认对称
6. 更新 `docs/progress-report.md` 与 `docs/feature-inventory.md`
