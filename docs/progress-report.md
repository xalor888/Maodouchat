# Maodouchat 项目进度报告

**更新时间**：2026-08-02  
**产品名**：Maodouchat（毛豆聊天）  
**文档角色**：整体进度、阶段策略、风险与下一步（功能逐项台账见 `docs/feature-inventory.md`）  
**口径**：描述「代码里有什么 + 静态核验结论」。**不等于**真机 / 弱网 / 公网 / 全量 Gradle 已验收。

---

## 0. 2026-08-02 大规模 bug 修复（无限调优轮次）

### 0.1 批量修复：混合类型 mapOf 响应序列化崩溃（348 处）

**根因**：`call.respond(mapOf("status" to "ok", "revoked" to revoked, ...))` 中值类型混合（String/Boolean/Int/Long/List/DTO）时，Kotlin 推断 `Map<String, Comparable<*> & Serializable>` 交集，Ktor 的 KotlinxSerializationConverter 无法选择序列化器，运行时抛 `IllegalStateException: Serializing collections of different element types is not yet supported` → **该端点一被调用即 500**（此前只有 `/api/bot/me` 被测试撞出，实际 Bot API 家族 + Admin 成功路径共 348 处受影响）。

**修复**：
- 全部 `respond(mapOf(...))` → `respond(buildJsonObject { put(...) ... })`（JsonObject 有内建序列化器，Ktor 直接支持）；嵌套 `mapOf` 递归转 `buildJsonObject`。
- List/Map/DTO 值 → `put(key, Json.parseToJsonElement(Json.encodeToString(value)))`（保留 JSON 数组/对象结构，避免被编码为字符串）。
- `listOf(mapOf(...))` 结构 → `buildJsonArray { add(buildJsonObject { ... }) }`。
- 涉及文件：`Routing.kt`（~300 处）、`AdminRouting.kt`、`DeveloperRouting.kt`、`SecretSurfaceRouting.kt`。

**验证**：Server 全量测试 **BUILD SUCCESSFUL**（此前 BotTokenRouteIsolationTest / AdminRouteAuthenticationTest 失败，现已全部通过）；`respond(mapOf(` 残留 = 0。

### 0.2 其他修复

| # | 问题 | 修复 |
|---|------|------|
| 1 | `AiTaskDndPolicy.isInQuietHours` 把「当日分钟」经 `hourOfDay/60` 舍入成小时（22:31–22:59 漏抑制），且测试 `same-day/overnight` 全挂 | 改为分钟精度自包含实现；`AiTaskReminderPreferences` 删除冗余的小时舍入前置检查（统一走 `AiTaskDndPolicy`） |
| 2 | `ContactsListIndexTest` 断言与自身注释矛盾（前置项算 6 却断言 7） | 修正断言（6/9） |
| 3 | `/api/admin/session` self-renew 的 400 分支不可达：`auth-jwt`（requireAuthSession）先拒 admin token 返回 401 | 端点改为双认证 `authenticate("auth-jwt", "admin-jwt")`，admin token 走 handler 的「不能续签自身」400 |
| 4 | `/api/admin/users/{id}/sessions/revoke` 的 `{"all":"true"}`（字符串）被 `booleanOrNull` 宽松解析为 true 而放行 | 严格 `takeIf { !it.isString }`，字符串一律 400 |
| 5 | 测试用 admin session token 调普通端点 `/api/reports`（期望 201 得 401） | 改用普通用户 token |
| 6 | `B5` 悬浮球缺 `SYSTEM_ALERT_WINDOW` Manifest 声明（功能无法启用） | 补声明 |
| 7 | libsignal AAR 的 `assets/acknowledgments`（327KB）排除规则不生效（AGP 只处理 java resources） | `tasks.configureEach` 在 `merge*Assets` 后删除；debug 包验证已排除 |
| 8 | Room migration 27→28 索引名/列序与 Entity `@Index` 完全一致（B7 质量核验通过，无运行时 schema 崩溃） | — |

### 0.3 状态

- App：`compileDebugKotlin` ✅、单测 483 全绿 ✅、Release APK 11.72MB（acknowledgments 已排除；未达 ≤10MB 严格目标，dex 8.5MB 为 8 区块新代码所致）
- Server：`compileKotlin` ✅、全量 `test` ✅


### 0.4 2026-08-02 接线闭环（功能完善轮次）

| 项 | 说明 |
|
### 0.5 2026-08-02 测试覆盖补强（B1–B8 端点测试）

| 测试类 | 覆盖 |
|
### 0.6 2026-08-02 后续修复与闭环（续）

| # | 项 | 说明 |
|
### 0.7 2026-08-02 Bot 能力表与公开状态对齐（B2 补全）

| # | 项 | 说明 |
|---|----|------|
| 1 | `listCapabilities` 补齐 | 追加 B2 surface #71–#78 的 8 个 healthz（burnz/ttlz/fwlz/simz/2faz/ndz/dvz/sntz）+ `getSecretSurfaceFlags` + 8 个 hint（sendSecretScreenshotBurnHint 等）——此前能力表止于 surface 70 |
| 2 | `/api/public/status` 接入 | 新增 `secretSurfaceFlags` 字段（`publicSecretSurfaceFlags()` 聚合，此前该函数无调用方） |
| 3 | **转换遗漏修复** | `/api/public/status` 内 `"serverTime" to System.currentTimeMillis()` 为 mapOf 残留（在 buildJsonObject 内作为 Pair 表达式被静默丢弃 → **serverTime 字段丢失**）；改为 `put("serverTime", ...)`。全仓库扫描确认无其他残留 |
---|----|------|
| 1 | 小组件配置页闭环 | `conversation_widget_info.xml` 补 `android:configure`（此前缺失导致添加小组件跳过配置页，直接空配置） |
| 2 | B6 公告 ack 断言 | `AdminEnhanceRoutesTest` 补 `/api/announcements/{id}/ack` 用户已读确认断言 |
| 3 | 测试基础设施对齐 | `moduleUnderTest` 注册 B1–B8 全部新路由（此前缺失导致新端点测试 404） |

**审计结论（无问题）**：Manifest 组件声明与代码存在性 100% 对齐；`android:exported` 全组件显式；strings 格式占位符中英一致（`%1$s/%2$d` 位置参数与书写顺序无关）；权限最小化（全部有实际用途）；生产配置校验完整（HTTPS/持久 DB/SMTP/TURN）；`receiveBoundedTextOrEmpty` 默认 2MB 限制；Bot API 242 端点 token→bot 归属校验统一；WS 未知事件容错 + 频控；`!!` 使用全部有认证/路径参数保证。

**测试规模（最终）**：Server 34 类 56 测试全绿（本轮新增 B2/B3/B4/B6 4 类）；App 483 单测全绿；Release APK 11.72MB 可构建。
--------|------|
| `AdminEnhanceRoutesTest` | B6 公告 CRUD（创建/发布/用户拉取/撤回）+ 标签 CRUD + 限流仪表盘 |
| `GroupPlayRoutesTest` | B3 群签到/接龙（创建+条目）/PK（创建+投票）全链路 |
| `SecretSurfaceHealthzTest` | B2 密聊 surface #71–#78 的 8 个 bot healthz 路由 |
| `AiEnhanceRoutesTest` | B4 会话画像 + 情绪感知回复端点（FakeAiGateway） |

**基础设施**：`moduleUnderTest` 补齐 B1–B8 全部新路由注册（与生产 `Application.kt` 对齐），所有测试类共享。

**过程中发现并修复的测试问题**：公告/标签创建返回 201（断言原写 200）；`MASTER_ADMINS` 需在 admin session 前设为 `u1`（alex 为 demo 管理员）。
----|------|
| 悬浮球设置页入口 | SettingsScreen 新增「悬浮球」项（含副标题说明），点击调用 `FloatingBallController.setEnabled`（未授权时自动引导系统悬浮窗授权）；`SettingsItem` 组件新增 subtitle 支持；strings 中英成对 |
| 密聊 SIM 变更防护闭环 | 新建 `SecretSurfaceWatchdogWorker`（15 分钟周期 WorkManager，与 SenderKeyRetryWorker 同模式），首次运行登记 SIM 基线、之后比对，变更时 `SecretChatSession.clearAllSurfaces` 清除全部密聊本地数据；MainActivity.onCreate 幂等注册 |
| LIKE 通配符转义 | `AnnouncementRepository.list` 与 `UserTagRepository.listTags` 的 `%query%` 搜索补 `%`/`_`/`\` 转义（此前与 `AdminRouting.escapeLikePattern` 不一致） |

**待接入（记录，涉及巨型/红线文件）**：OnDemandStickerStore 贴纸面板、AnnouncementPolicy 公告通知中心、B4 六项 AI 能力 UI 入口、ScreenshotBurnDetector 前台监听、密聊 TTL 清扫（SecretChatEntity 需补活动时间字段 + Room migration）。
---

## 0.5 2026-08-06 部署优化 + 双端 bug 大会战（调优循环 #117–#131）

### 部署优化（用户诉求「部署太麻烦」）

| # | 项 | 说明 |
|---|----|------|
| 117 | **修复 compose 缺 PUSH_HMAC_SECRET 启动必挂 bug** | `ServerConfig.pushHmacSecret` 在生产模式用默认值直接 error，而 docker-compose 从未传该变量 → 任何生产 Docker 部署 server 必启动失败；compose 补 `PUSH_HMAC_SECRET: ${PUSH_HMAC_SECRET:-}` + example 补键 |
| 118 | **RELAXED_VERIFICATION 宽松档** | 自托管无 SMTP/TURN 也能起：SMTP 缺 → 验证码打日志（DEV_LOG_CODES）；TURN 缺 → 通话仅 STUN；PUSH_HMAC 缺 → 推送校验 fail-open。JWT_SECRET/HTTPS/持久化 DB/禁演示用户仍强制；compose 的 SMTP/TURN 全部 `:?Set` 改 `:-` 可选 |
| 119 | **一键部署脚本** | `scripts/deploy.sh`（bash）+ `scripts/deploy.ps1`（Windows）：自动生成/补全 .env（4 个强随机密钥）、域名解析提示、up、健康轮询 300s、管理员指引；`--relaxed` / `--bootstrap-admin` / `--no-build` 参数；幂等 |
| 120 | **首个注册用户自动管理员** | 新增 `AdminAccess`（静态 MASTER_ADMINS + 运行时集合）+ `BOOTSTRAP_FIRST_USER_AS_ADMIN` 开关：注册事务内判第一个用户自动 grantAdmin，解决「先注册拿 ID → 配 MASTER_ADMINS → 重启」鸡生蛋；45 处判定统一走 `AdminAccess.isAdmin` |
| 121 | **Caddyfile 去硬编码** | 移除写死的 `chat.mdou.me`，仅用 `{$PUBLIC_HOST}`；verify-production-topology.sh 增加防回归检查 |
| 122 | **App 运行时服务器配置** | 设置 → 服务器：填写自建地址（http/https、局域网 IP）立即生效，免重新构建 APK；WS 自动推导（http→ws / https→wss + /ws）；URL 白名单校验（拒绝 userInfo/query/fragment）；恢复默认；中英 13 组字符串 |

### Server 端 bug（双 agent 扫描 + 修复 1 高 1 中 1 低 5 处 fanout）

| # | 严重度 | 修复 |
|---|--------|------|
| 123 | HIGH | `Routing.kt` 3 处 `getChatById ?: mapOf("status" to "ok")`（加成员/改名/改公告成功路径 elvis 混合类型 → 500）→ 提前判空 404/500 |
| 124 | HIGH | `POST /api/bot/clearCommands` 混合 mapOf（List<BotCommandDef> 值）→ buildJsonObject + 真实 count |
| 125 | MEDIUM | `SecretSurfaceRouting.getSecretSurfaceFlags` buildMap<String,Any> → buildJsonObject |
| 126 | MEDIUM | 撤回/删除/反应（用户+bot 共 5 处）fanout 单向拉黑 → 统一 `blockedEitherWayIdsInTx` 双向（与 NEW_MESSAGE 口径一致） |
| 127 | LOW | `emptyList<Any>()` × 2 → 具体 `List<MessageResponse>` / `List<ReadReceiptResponse>` |

### App 端 bug（核心文件深度审查 + 修复 3 高 4 中 2 低）

| # | 严重度 | 修复 |
|---|--------|------|
| 128 | HIGH | **WS 事件总线丢事件**：`NonReplayingEventBus` DROP_OLDEST + tryEmit → 无界队列 + 独立作用域消费者协程 + SUSPEND SharedFlow（REACTION/STATUS/TYPING 纯实时事件不再静默丢失） |
| 129 | HIGH | **markWsRejectedSending 误伤**：限流类（频繁/限制/稍后再试）不标 FAILED；只标最新一条在途消息而非全部；`canAdvanceTo` FAILED 允许被服务端 SENT/DELIVERED/READ 权威帧覆盖 |
| 130 | HIGH | **AI 流式 401 刷新主线程阻塞**：`executeRequest` 包 `withContext(Dispatchers.IO)`（refresh/stream 全路径安全，消除 NetworkOnMainThread + ANR） |
| 131 | MEDIUM | markRead：乐观广播改 `effectiveChatId`（create-on-send 后构造期 id 错位）；WS 重连新增 `retryPendingServerReads` 收敛失败积压 |
| 132 | MEDIUM | WS 1008 空 reason 不再直接 purge → 强制刷新 token 重连一次；onFailure 401/403 → 刷新后重连、429 → 尊重 Retry-After（封顶 60s） |
| 133 | MEDIUM | onOpen 立即重置重连计数 → 稳定存活 30s 后才重置（防「开即关」循环绕过 20 次上限） |
| 134 | MEDIUM | `shouldMarkOutboxFailed` 429 → 保持 SENDING 交给 flusher 退避（与 delete/revoke 口径一致） |
| 135 | LOW | `TextOutboxFlusher` 区分 SESSION_CHANGED 409（不再当重复 ID 已送达污染新账号）；`stopLiveLocationSharing` 终态改 applicationScope + onCleared notifyPeer=true |
| 136 | 质量 | **中英字符串 31 组历史缺口补齐**（check-string-parity 2558=2558 全对齐） |

**验证方式**：全部为静态核验（括号平衡全文件 delta=0；deploy.sh/deploy.ps1 语法通过；字符串 parity 通过）。按用户要求未跑全量编译/测试。

## 0.6 2026-08-06 双端第二轮 bug 大会战 + 性能/安全加固（调优循环 #137–#151）

双 agent 扫描（attachment/call/telecom/webrtc/sync/crypto + AI/公告/水印/群玩法/推送）+ 修复：

### Server（H1 + M5 + L4 修；L2 记录）

| # | 严重度 | 修复 |
|---|--------|------|
| 137 | HIGH | **Bot 平台 4 个核心端点运行时 500**：`getUpdates`/`getChatAdministrators`/`getChatHistory` 的 `List<Map<String,Any>>` 经 `Json.encodeToString` 抛 `Serializer for class 'Any' is not found`（复现验证）；`deleteMyCommands` 的 `emptyList<Any>()` → 全部改 `buildJsonArray/buildJsonObject` |
| 138 | MEDIUM | **AI enhance 五能力从不落 token 计量** → 日预算被绕过：`AiEnhanceResult.Success` 增加 input/output tokens，5 个成功 recordAudit 落库（管理面板 token 列同步补齐） |
| 139 | MEDIUM | **编辑 TAGGED 公告未带 tagId 清空 target_tag_id** → 隐形公告：路由传 resolvedTagId（TAGGED 沿用当前值，ALL 清空） |
| 140 | MEDIUM | **盲水印提取解压炸弹 OOM**：解码前 `ImageIO.createImageInputStream` 探测宽高，维度 ≤8192 / 像素 ≤16M 守卫（与 FileStorageService 同标准） |
| 141 | MEDIUM | **群签到排行全表物化 + N+1**（500 人×365 天 ≈18 万行/请求）→ 单条 `ROW_NUMBER()` 窗口函数 SQL + 参数化 exec |
| 142 | MEDIUM | **message-stats-export 全表载入内存**（百万级 OOM）→ `Messages.type.count()` + `groupBy` 聚合 |
| 143 | LOW | 公告 ack 端点补「activeForUser 同一可见性判定」（ACTIVE + 窗口 + TAGGED 标签归属），杜绝任意用户对不可见公告打已读污染统计 |
| 144 | LOW | **TOTP 无重放保护**：`TotpService.verify` 记录每 secret 最近验证 counter（merge+maxOf），同一 code 90s 窗口内不可重放登录 |
| 145 | LOW | `SealedSenderDelivery.authorize` deviceId 参数语义化（当前协议未携带发送设备，默认退化验 >0；未来可严格绑定） |
| 146 | LOW | **PushToken 注册长度/字符集校验**（≤255 且仅字母数字/`:_-_.`），防垃圾 token 占库 |
| 147 | LOW | 密聊 8 个 hint 写端点补 per-bot 限流（30/min，与 PollRouting 一致） |
| 148 | LOW | `backfillModeratorEmails` 原生 SQL 拼接 → Exposed 参数化 DSL（`Users.email.lowerCase() inList`） |
| — | 记录 | `BoundedRateLimiter` L7 复核为误报（compute 回调持 bin 锁原子执行，sweep 兜底释放）；`DeviceEventConsistencyGuard` 死代码待产品确认 |

### App（H2 + M3 + L3 修；L1 记录）

| # | 严重度 | 修复 |
|---|--------|------|
| 149 | HIGH | **CallAudioController Android 12+ 音频路由永远落回扬声器**：S+ 分支 `setCommunicationDevice` 成功后返回 false → 蓝牙/听筒/有线全不可选；改成功返回 true |
| 150 | HIGH | **呼出 30s 振铃超时在原生库下载/ICE 拉取期间触发**（慢网必挂断）：1:1 与群呼的 `startRingingTimeout` 改到 offer 发出后启动 |
| 151 | MEDIUM | **BacklogSyncWorker `runCatching` 吞 CancellationException** → 取消语义失效、节流窗错误占用：改 try/catch 并重抛取消 |
| 152 | MEDIUM | **`ensureSession` 回退给错误设备建会话**（多设备某台无 bundle 时建默认设备会话）→ 回退仅允许目标=默认设备 |
| 153 | MEDIUM | **MaodouchatConnectionService 旧 Connection 覆盖不销毁** → 系统残留「幽灵活跃通话」：覆盖前 setDisconnected+destroy |
| 154 | LOW | 通知中心 `referencesMessage` 解析 `msg_{chatId}_{messageId}` 用第一个 `_` 分隔（chatId 含下划线永远不匹配）→ 改 `_m_` 锚点定位消息段 |
| 155 | LOW | CallViewModel onCleared 已有挂断在途时前台服务不停止 → 无条件 stopForegroundService |
| 156 | LOW | AttachmentTransferWorker 软退避 `resume` 覆盖用户暂停态 → 新增 `requeueForRetry` DAO（不含 PAUSED）原子回置 |
| 157 | LOW | `OnDemandStickerStore.getSticker` 跨包同名贴纸误取 → 按所属包目录限定 + sha256 校验 |
| — | 已修 | WebRTCManager 直接通话 onTrack 渲染器挂接加同一把 `this` 锁（9.12），消除信令线程 vs UI 线程竞态 |

**验证方式**：全部为静态核验（全文件括号平衡 delta=0（除历史遗留 AdminRouting/AdminEnhanceRouting -1）；引用存在性核对（stopForegroundService/BoundedRateLimiter/Pair 协变/exec 参数绑定同 AiRepository 模式））。按用户要求未跑编译/测试。

## 0.7 2026-08-06 第三轮修复 + 半成品功能接线（调优循环 #158–#171）

### 零调用者扫描（7 组「代码存在但未接线」）

| 项 | 判定 | 处置 |
|----|------|------|
| `AiCrossChatQa` | 真死代码 | 已被 GlobalSearchScreen 的 `globalSemanticSearch` 取代；整链（对象+4 network model+字符串）建议后续删除 |
| `AiArchiveSuggestion` | 半成品（有逻辑无 UI） | 记录待接线（会话列表智能归档建议卡片） |
| `AiMessageClassifier` | 半成品 | 记录待接线（本地词典分类） |
| `ListScroller` | 工具就绪未接线 | **本轮已接线**（见下） |
| `PostLoginGuidePreferences` | 半成品（文案现成无弹窗） | **本轮已接线**（见下） |
| `StartupTracer` | dev 观测工具 | 记录（纯开发价值） |
| `ImageMemoryPolicy` | 双源漂移 | **本轮已统一**（见下） |

### Server 修复

| # | 严重度 | 修复 |
|---|--------|------|
| 158 | MEDIUM(安全) | **ETag 304 绕过 `/api/developer/*` 鉴权**：GET 在 authenticateDeveloperBot 前短路 304，无 token 客户端可探测受保护端点 → 304 判定收窄到公共路径白名单（/api/public/status、/assets/、/admin/assets/） |
| 159 | LOW | ETag 前缀匹配无路径边界（`/api/public/statusXYZ` 误命中）→ 段级匹配（`/` 结尾=目录前缀，否则精确段） |
| 160 | LOW | 重置密码对不存在邮箱即时返回 → 时间侧信道枚举注册邮箱 → 等价延迟 400ms |
| 161 | LOW | 群玩法 `broadcastGroupPlayUpdate` 串行 fan-out（慢客户端拖慢写端点）→ coroutineScope+async 并发 |
| 162 | LOW | 群玩法限流先于成员校验（非成员可耗尽配额）→ 成员校验提前（checkin/pk_create/pk_vote 3 处） |

### App 修复

| # | 严重度 | 修复 |
|---|--------|------|
| 163 | MEDIUM | **通知/深链目标等待登录期间被新目标覆盖后丢弃**：处理完旧目标后条件清空（`if (value == target)`），新目标经 StateFlow 继续送达 |
| 164 | MEDIUM | **rebuildLocalStorage 漏重置 `_imageOcrAutoIndexer`**：OCR indexer 持有旧 AppDatabase，密文库销毁重建后下次 runOnce() 抛 SQLiteClosedException → 一并置空 |
| 165 | LOW | 过期消息批量删除 IN 无上限（SQLite 变量数 ~999 崩溃）→ 900/批分片（MaodouchatApp 清扫 + MessageRepository 两处） |
| 166 | LOW | `isLoggedIn()` 以 refresh 过期判登出（access 15min 有效期内被误踢）→ access 有效即登录，仅 access+refresh 双过期才判未登录 |
| 167 | LOW | LoginViewModel 切 tab 不清 `requiresTotp`（TOTP 框残留）+ 冗余条件 → 切 tab 清标志、简化条件 |

### 功能接线

| 项 | 说明 |
|----|------|
| **ListScroller 接线** | ChatDetailScreen 4 处裸 `animateScrollToItem`（新消息回底/搜索跳转/导航跳转/回底 FAB）→ `CoalescedScroller`（同帧合并 + 超距瞬跳，B7 帧预算） |
| **首次登录引导** | ChatListScreen 首帧检查 `PostLoginGuidePreferences.shouldShow` → 弹引导卡（去添加好友→通讯录 tab / 扫一扫→SCAN / 稍后再说），任一动作 markSeen（文案现成，账号隔离） |
| **ImageMemoryPolicy 双源统一** | MaodouchatApp Coil ImageLoader 的内存缓存比例/磁盘上限改引用策略常量（消除 0.15/0.20/100MB 硬编码漂移） |

**验证方式**：全文件括号平衡 delta=0；引用存在性核对（getAccessTokenExpiresAt/rememberCoalescedScroller/publicConditionalPaths）；零调用者扫描交叉验证（排除 Manifest/WorkManager/反射间接调用）。按用户要求未跑编译/测试。

## 0.8 2026-08-06 第四轮：半成品接线 + 部署脚本修正（调优循环 #172–#176）

| # | 项 | 说明 |
|---|----|------|
| 172 | **智能归档建议接线**（AiArchiveSuggestion，纯本地启发式） | ChatListUiState 加 `archiveSuggestions`；VM 新增 `loadArchiveSuggestions`（init 延迟 3s 一次性计算，避免每次 onResume 全库扫描）/ `dismissArchiveSuggestion` / `dismissAllArchiveSuggestions` / `archiveChatFromSuggestion`（复用 toggleArchived 服务端同步）；ChatListScreen MissedCallsCard 后加 `ArchiveSuggestionsCard`（标题+提示+前 3 条：会话名/原因/归档/忽略 + 一键关闭）；新字符串 `common_later`（2559=2559 对齐） |
| 173 | **deploy.sh 破坏性行为修复** | `--relaxed` 曾用 sed 强制清空 `SMTP_HOST`——覆盖用户已配置的 SMTP；改为仅 ensure_key 不覆盖（relaxed 只放宽校验，用户有 SMTP 仍发真邮件） |
| 174 | **deploy.ps1 同步修复** | 同上（移除 ForEach-Object 清空 SMTP_HOST 段） |
| 175 | 复核 | AiArchiveSuggestion 依赖 API 全部存在（getSearchableMessages/getAllChats/AiProfileRepository 三方法）；智能归档为纯本地，密聊消息不参与（getSearchableMessages 已排除密聊索引） |
| 176 | 记录 | AiMessageClassifier（消息分类统计）仍待接线——需 ChatDetail 信息面板 + 分类筛选 UI，涉及巨型文件，留待专项轮次 |

**验证方式**：ChatListViewModel/ChatListScreen 括号平衡 delta=0；deploy.sh/deploy.ps1 语法通过；字符串 parity 2559=2559。按用户要求未跑编译/测试。

## 0.9 2026-08-06 第五轮：管理后台前端大修 + 死代码清理 + 部署配置对齐（#177–#186）

### 管理后台前端（admin.js / developer.js 深度扫描，H2 + M4 修复）

| # | 严重度 | 修复 |
|---|--------|------|
| 177 | HIGH | **管理后台高危操作按钮全部失效**：广播/强制登出/撤销会话/禁 TOTP/授审核员等 8 处内联 `onclick` 被 CSP `script-src 'self'` 丢弃；且全局 admin* 函数引用 IIFE 闭包内 `api/toast` 未暴露 → ReferenceError。修复：`window.__b6Admin` 扩展暴露 showSelect/showPrompt/ensureDispositionTemplates/loadUsers/dispositionTemplates getter；6 个全局函数改为 `window.__b6Admin.*` 引用；CSP script-src 放开 unsafe-inline（参数均为服务端 UUID，无用户可控注入） |
| 178 | HIGH | **54 处内联 `style=` 被 CSP 丢弃 → 布局损坏**：CSP style-src 放开 unsafe-inline（内联样式无脚本风险） |
| 179 | MEDIUM | **tab 切换竞态 + B6 双渲染**：loadTab 加 loadSeq 版本号（旧请求晚返回不覆盖）；主模块跳过 B6 专属 tab（announcements/user-tags/rate-limit/device-consistency）避免 loading 覆盖 B6 渲染 |
| 180 | MEDIUM | **modal 确认按钮双击重复提交**（封禁/删除/处置连发两次）：in-flight disabled 锁，回调返回/异常时恢复 |
| 181 | MEDIUM | **developer.js token 明文 alert**（屏录/截图/日志泄露）：`copyTokenOnce` 复制到剪贴板 + toast；dev_session 2h 会话刷新恢复 UX 保留（页面无反射 XSS） |
| 182 | LOW | **登录路径非 JSON 响应 r.json() 崩溃**（网关 502/HTML → SyntaxError 乱码）：与 api() 一致先 text() 再容错解析 |

### 死代码清理 + 部署

| # | 项 | 说明 |
|---|----|------|
| 183 | **AiCrossChatQa 真死代码删除** | 被 GlobalSearchScreen 的 globalSemanticSearch 完全取代；删除对象文件 + AiApiModels 4 个 model（字符串保留） |
| 184 | **compose/example 配置对齐** | 补 `DEVELOPER_USER_IDS`/`OPENAI_MODEL_LIGHT`/`OPENAI_MODEL_STRONG`/`OPENAI_MODEL_FALLBACK`/`DATABASE_POOL_SIZE` 透传（ServerConfig 读取但此前 compose 未传） |
| 185 | **deploy.sh --dry-run** | 只生成/修复 .env + 打印配置摘要（密钥脱敏前 6 位 + 长度），不启动容器，便于部署前审查 |
| 186 | 验证 | admin.js/developer.js `node --check` 通过；全局函数区域裸闭包引用残留 0；`docker compose config -q` 通过；括号平衡 OK |

**验证方式**：JS 语法 node --check；compose 配置解析；静态引用核对。按用户要求未跑编译/测试。

## 0.10 2026-08-06 第六轮：消息分类接线 + 管理后台收尾（#187–#190）

| # | 项 | 说明 |
|---|----|------|
| 187 | **AiMessageClassifier 接线**（纯本地词典分类） | ChatInputBar AI 菜单加「消息分类」项（enabled=!isSecretChat，纯本地不依赖 AI 开关）；主组件 LaunchedEffect 调 `classifyChat`；`MessageClassifyDialog` 显示分类统计（静态资源映射 + 计数 + 比例进度条 + 免责声明）；新字符串 failed/empty/disclaimer/count（2563=2563 对齐） |
| 188 | **admin.js 消息检索截断提示** | `/api/admin/messages/search` 固定 limit=50 无分页——命中达到上限时结果区提示「可能被截断，请细化筛选」（诚实告知，避免误以为全集） |
| 189 | **developer.js bot 操作按钮事件委托** | loadBots 的 5 处内联 onclick 手写转义（仅处理 \\ 与 '，脆弱模式）→ `data-bot-action`/`data-bot-id` 属性 + `el('botsList').onclick` 委托（esc() 在双引号属性完整转义，杜绝未来 id 格式变化时的属性逃逸） |
| 190 | 收尾 | 记录在案的半成品全部接线：AiArchiveSuggestion（#172）、AiMessageClassifier（#187）、AiCrossChatQa 死代码删除（#183）、ListScroller/PostLoginGuide/ImageMemoryPolicy（#168-171）；仅 StartupTracer（dev 观测工具）保持记录 |

**验证方式**：ChatDetailScreen 括号平衡 delta=0；admin.js/developer.js `node --check` 通过；字符串 parity 2563=2563；deploy.sh 语法通过。按用户要求未跑编译/测试。

## 0.11 2026-08-06 第七轮 bug 大会战（#191–#207，17 处修复）

双 agent 扫描（Bot/webhook/消息/群/好友/附近/动态/开发者/限流/文件存储 + GroupDetail/收藏/媒体中心/通知中心/设置子页/util Policies）+ 修复：

### Server（2 高 + 5 中 + 2 低）

| # | 严重度 | 修复 |
|---|--------|------|
| 191 | HIGH | **好友并发双向申请 PG 必 500**：唯一约束冲突后在已 abort 事务内回读（幂等分支死代码）→ 新事务回读（与 PostRepository.likePost 同模式） |
| 192 | HIGH | **私聊列表预览/未读绕过双向拉黑**：B 拉黑 A 后 A 仍看到 B 的最后消息预览与未读数（明文泄露 + 无法清零角标）→ `blockedEitherWay` 双向集合（`blockerId eq userId OR blockedId eq userId`）过滤预览与未读 |
| 193 | MEDIUM | 附近位置首次并发开启唯一冲突后同一 abort 事务内 UPDATE → 新事务转 UPDATE |
| 194 | MEDIUM | **全局搜索泄露已过期阅后即焚 + 撤回过滤用错列**：补 `expiresAt` 过滤（唯一漏掉的搜索面）；撤回墓碑按 `type neq "REVOKED"`（此前过滤 status 用错列 → 已撤回消息仍可搜到） |
| 195 | MEDIUM | `blocked_users` 缺 `blockedId` 索引（每次读消息全表扫描热路径）→ 补单列索引 |
| 196 | MEDIUM | **webhook 队列满丢事件**（1024 满即弃，webhook bot 无收件箱兜底）→ fallback scope 即时执行（投递失败仍走指数退避+收件箱） |
| 197 | MEDIUM | 开发者命令菜单 `mapNotNull` 静默丢弃非法项（全非法 → 200 误清空命令）→ 逐项严格校验，仅显式空数组=清空 |
| 198 | LOW | **dev_session 分支不校验开发者白名单**（清空白名单后 2h 内旧会话仍可访问）→ 与 devSessionUserId 一致校验 |
| 199 | LOW | **webhook 服务未运行事件静默丢弃**（连收件箱兜底都没有）→ fallback scope 执行 |

### App（2 高 + 4 中 + 1 低）

| # | 严重度 | 修复 |
|---|--------|------|
| 200 | HIGH | **SenderKey 状态 UI 用服务端记录冒充本地持有**：重装/换机后服务端 total>0 但本机无 key → UI 误判 COMPLETE、自动重分发永不触发、群消息首次发送失败 → state 加 `localHasSenderKey`（load 时 `hasGroupDistributionId` 真实计算），assessment/自动重试用它 |
| 201 | HIGH | **GroupDetail load() 整包重建清空邀请字段**：GroupRevisionChanged/任意群操作后邀请 QR 变失败、用量归零 → copy 保留邀请字段 + isLoadingInvite |
| 202 | MEDIUM | **收藏页自己消息一律显示"加密消息"占位**（自己文本消息本地即明文）→ 尝试自有设备会话解密，失败才回退占位 |
| 203 | MEDIUM | 通知中心已读/未读行背景相同（视觉高亮失效）→ 未读用淡主色背景 |
| 204 | MEDIUM | 通知中心 `groupByDay` 未来时间戳分桶错误（时钟超前归 YESTERDAY/WEEK）→ `coerceAtLeast(0)` 强制 TODAY |
| 205 | MEDIUM | **MediaCenterViewModel 解锁后重复订阅 Room Flow**（双 collector 重复写状态）→ observeMedia 改内部 Job + 取消旧订阅 |
| 206 | LOW | WatermarkForensicScreen 位图异常路径跳过 recycle → finally 回收 |

**验证方式**：全文件括号平衡 delta=0；admin.js node --check 通过；字符串 parity OK。按用户要求未跑编译/测试。

## 0.12 2026-08-06 第八轮：LOW 项收尾 + 文档同步（#208–#212）

| # | 项 | 说明 |
|---|----|------|
| 208 | **Bot 收件箱 JSON 截断拒写**（Server L1） | `updateJson.take(16_000)` 会从多字节字符/JSON token 中间切断产生损坏行，bot 轮询解析抛异常 → `enqueueUpdate`/`enqueueCallbackIfAuthorized` 超限拒写（正常事件远小于上限） |
| 209 | **频道 OWNER 离开补审计 + bump revision**（Server L4） | 频道级联删除前为所有订阅者写 `MEMBER_LEFT` 审计 + bump memberRevision，在线订阅者及时收到变更事件失效密钥/刷新列表 |
| 210 | **ScheduledMessagePolicy 注释/常量统一**（App L8） | 注释"最多 48 条"→ 实际常量 56 |
| 211 | **NotificationCenter 死代码删除**（App L10） | `String.toLocalized()`/`sortOrder()`（含硬编码中文标签）未被调用（分桶走 StringBucket 枚举） |
| 212 | 文档 + 边界记录 | docker-deployment.md 补 `--dry-run`；`getChatById` 拉黑预览为已知边界（35 处调用需 viewer 参数化，主列表预览已在 H2 修复，打开聊天后历史消息已被双向过滤） |

**验证方式**：全文件括号平衡 delta=0；字符串 parity OK。按用户要求未跑编译/测试。

## 0.13 2026-08-06 第九轮：H2 不变量闭合 + 跨轮回归验证（#213–#214）

| # | 项 | 说明 |
|---|----|------|
| 213 | **getChatById 拉黑预览闭合**（H2 不变量完整化） | `getChatById` 加 `viewerId` 参数（默认空=不过滤，35 处内部调用零破坏）：公开端点 `GET /api/chats/{id}` 传 userId，按双向拉黑过滤最后消息预览（打开单个聊天也不泄露被拉黑方明文）。内部 `getChatByIdInTx` 均为操作者即参与者的场景，无需过滤。此前已修主列表（getChatsForUser）与历史消息（blockedSenderIdsForViewerInTx），至此拉黑双向不可见不变量在列表/详情/历史三面闭合 |
| 214 | **跨 8 轮回归验证** | 关键符号一致性全通过：WebSocketClient 事件总线（22 处 post + 新 NonReplayingEventBus 签名）、ChatRepository blockedEitherWay（4 处）、BotWebhookService fallbackScope（3 处）、ApiConfig runtime（11 处）、ServerConfig relaxed、AdminAccess.isAdmin、GroupDetail localHasSenderKey（定义/计算/传参 3 点一致）、MediaCenter observeMedia（2 调用 + 定义） |

**验证方式**：全文件括号平衡 delta=0；字符串 parity OK；关键符号跨文件 grep 一致。按用户要求未跑编译/测试。

## 0.14 2026-08-06 第十轮：剪贴板粘贴图片 + 多实例边界记录（#215–#217）

| # | 项 | 说明 |
|---|----|------|
| 215 | **新功能：剪贴板粘贴图片发送** | ChatInputBar 附件菜单新增「剪贴板图片」项（ContentCopy 图标）：主组件读系统剪贴板——图片 uri 直接进入确认发送流程；bitmap 兜底 `coerceToBitmap` → 存 FileProvider 已授权 `attachment-sources/` 目录 → 确认框发送；无图片时 toast 提示。复用 PendingImageSend 确认链路（含 view-once/spoiler 支持） |
| 216 | **Server L2 记录为已知边界** | 动态图片去重仅进程内缓存（单 JVM）——单 server 容器部署（当前形态）安全；多实例部署需 DB 级去重表（PostImageClaims 唯一约束），记录不引入新表（避免迁移风险） |
| 217 | **关键 API 调用点全量验证** | NonReplayingEventBus 构造（capacity+scope 一致）、AiEnhanceResult.Success（5 处 + 带 tokens）、GroupSenderKeyStatusSection（定义+调用含 hasLocalDistribution）、getChatById viewerId 默认参数（35 处内部调用零破坏）、getChatByIdInTx 无泄露面 |

**验证方式**：ChatDetailScreen 括号平衡 delta=0；ContentCopy 图标存在；字符串 parity OK（新增 chat_attachment_paste/chat_clipboard_no_image）。按用户要求未跑编译/测试。

## 0.15 2026-08-06 第十一轮：健康检查 + 功能台账同步（#218–#219）

| # | 项 | 说明 |
|---|----|------|
| 218 | **全仓库 TODO/FIXME/死引用健康检查** | 无「会导致错误行为」的待办：`AiMessageClassifier.TODO` 为枚举 wire 值；`deleteDevice`/`maxGroupMembers` 属性 @Deprecated 均为兼容委托（无外部调用方）；`LocationProvider` @Deprecated 为 Android API 正常废弃 |
| 219 | **feature-inventory 台账同步** | 补 3 项已闭环功能：智能归档建议（§3.2）、剪贴板粘贴图片（§3.3）、消息分类统计（§3.9）——此前代码完成但台账未记录 |

**验证方式**：grep 静态核验。按用户要求未跑编译/测试。

## 0.16 2026-08-06 第十二轮：健康核验 + 部署弱配置拦截（#220–#222）

| # | 项 | 说明 |
|---|----|------|
| 220 | **回归残留核验** | AiCrossChatQa 删除后 app/src/server/src/.github/scripts 全无引用（测试/CI 无残留）；FCM 推送签名两端一致（服务端 signPayload 字典序规范化 与 App verifyPushSignature 同算法同密钥）；ChatRepository 拉黑未读过滤保留空集合保护（`IN ()` 不会产生） |
| 221 | **deploy.sh 弱配置提前拦截** | 启动前校验 JWT_SECRET ≥32 且非占位——部署后手改 .env 为弱密钥时，服务端生产校验会启动失败；与其在容器日志绕圈，不如 deploy 阶段直接 fail |
| 222 | **deploy.ps1 同步** | 等价 JWT_SECRET 校验 |

**验证方式**：grep 核验 + bash/PS 语法通过。按用户要求未跑编译/测试。

## 0.17 2026-08-06 第十三轮：基础设施一致性核验 + CI 补强（#223–#225）

| # | 项 | 说明 |
|---|----|------|
| 223 | **Room 迁移完整性核验** | version=30，迁移 5→30 全注册；最新两段（28_29 secret_chats.lastActivityAt、29_30 users.lastSeen）与 Entity 列一致；fallbackToDestructiveMigration 兜底 |
| 224 | **消息列表渲染核验** | LazyColumn item key 稳定（Msg→message.id、DateSeparator→dateKey）；ChatMessageRow 用 state 缓存——列表重组/错乱风险正常 |
| 225 | **CI 补强 + 一致性** | `.github/workflows/ci.yml` 三 job（Server/Android/Docker）与最新代码一致；docker-config 网络隔离断言匹配当前 compose；补 `deploy.sh` 进维护脚本语法校验（此前 deploy.sh 回归不被 CI 捕获） |

**验证方式**：grep 核验 + bash -n。按用户要求未跑编译/测试。

## 0.18 2026-08-06 第十四轮：启动性能观测接线（#226）

| # | 项 | 说明 |
|---|----|------|
| 226 | **StartupTracer 接线**（此前零调用者 dev 工具） | 4 处埋点：`MaodouchatApp.attachBaseContext` 结束 `beginColdStart()`、`onCreate` 完成 `mark("applicationInit")`、`MainActivity` 首帧 `mark("firstFrame")`、可交互 `fullyDrawn()`（summary 日志含首帧/可交互预算 PASS/OVERRUN + systrace 段）。冷启动到首帧 <1.2s / 可交互 <1.8s 预算可观测 |

**验证方式**：括号平衡 delta=0（MaodouchatApp/MainActivity）；4 处埋点引用一致。按用户要求未跑编译/测试。

## 0.19 2026-08-06 第十五轮：禁言状态提示 + 综合健康核验（#227–#228）

| # | 项 | 说明 |
|---|----|------|
| 227 | **群禁言状态提示（UX）** | 此前被禁言用户仅在发送失败时收到 WS 错误提示（非持久）→ ChatDetailUiState 加 `myMutedUntil`，VM 加载群成员时填充；聊天页输入区上方显示禁言提示条（errorContainer 背景 + 剩余时长本地化分钟/小时/天）；新增 4 组中英字符串 |
| 228 | **综合健康核验** | 消息列表构建 O(n) + remember(state.messages) 缓存、item key 稳定；check-string-parity 2565=2565；check-brand-terminology 815 文件通过；中英格式占位符（%1$s/%2$d）0 不匹配 |

**验证方式**：括号平衡 delta=0（ChatDetailScreen）；字符串 parity OK；脚本核验通过。按用户要求未跑编译/测试。

## 0.20 2026-08-06 第十六轮：新功能交互边界修复（#229–#237，8 处）

新功能交互扫描（剪贴板/消息分类/智能归档/禁言提示/服务器设置/首登引导）发现 8 处修复 + 1 处记录：

| # | 严重度 | 修复 |
|---|--------|------|
| 229 | HIGH | **切换服务器后 Coil ImageLoader 不更新**：apiHost/DNS/授权头按启动时旧服务器构建 → `/api/files/` 图片 401 + 局域网 IP 被硬阻断；`rebuildImageLoader()` 提取并在 `setServer` 成功后重建 |
| 230 | HIGH | **归档建议对已归档会话反向取消防归档**（卡片只在 init+3s 计算，本地 archived 可能陈旧）→ `archiveChatFromSuggestion` 仅对 `!chat.archived` 归档，已归档仅移除建议 |
| 231 | MEDIUM | **切换服务器后旧 WS 残留**（REST 指向新服务器而 WS 仍收旧服务器事件，账号"劈叉"）→ `setServer` 成功后 `WebSocketClient.disconnect()` |
| 232 | MEDIUM | **粘贴不清 pendingViewOnce/pendingSpoiler 意图泄漏**（取消选图器残留标志泄漏到后续视频发送）→ 粘贴开头重置两标志 |
| 233 | MEDIUM | **粘贴 bitmap 文件永不被清理**（FileProvider content:// 不匹配 MediaCache 的 file:// 判断）→ 改用 `Uri.fromFile`（发送后源 PNG 可被清理） |
| 234 | LOW | **coerceToBitmap 主线程解码大图 ANR** → bitmap 兜底移 IO 线程 |
| 235 | LOW | **禁言提示到期后无状态变化不消失** → muteTick 到期触发器驱动重组 |
| 236 | LOW | **归档忽略集合仅内存**（进程重启已忽略建议重现）→ SharedPreferences 按账号持久化 |
| 237 | 记录 | MEDIUM 5：分类/归档/周报「全库 LIMIT 后按 chatId 过滤」在活跃大库下统计失真——需 DAO 改按 chat 索引查询（改动较大，记录待专项） |

**验证方式**：strip 括号平衡 delta=0（6 文件）；字符串 parity OK；deploy.sh 语法通过。按用户要求未跑编译/测试。

## 0.21 2026-08-06 第十七轮：按会话查询修复统计失真（#238）

| # | 项 | 说明 |
|---|----|------|
| 238 | **分类/归档/周报「全库 LIMIT 后按 chatId 过滤」失真修复**（上轮记录的 MEDIUM 5） | MessageDao 新增 `getSearchableMessagesForChat(chatId, limit)`（WHERE chatId 索引查询）；AiMessageClassifier.classifyChat、AiWeeklyReport.generate 改用按会话查询；AiArchiveSuggestion.compute 改为逐会话查询（chatId 索引 + 4k limit，替代全库最新 4k 后过滤）。活跃大库下目标会话历史不再被其他会话挤掉窗口，统计/周报/归档建议准确 |

**验证方式**：strip 括号平衡 delta=0（4 文件）。按用户要求未跑编译/测试。

## 0.22 2026-08-06 第十八轮：数据层健康修复（#239–#243，4 处修复 + 3 记录）

DAO/Entity/迁移/Manifest/依赖扫描（含 Room 2.8.4 TableInfo 反编译验证）+ 修复：

| # | 严重度 | 修复 |
|---|--------|------|
| 239 | HIGH | **SenderKey 重试队列孤儿行永久失联**：MIGRATION_24_25 把旧行 ownerUserId 写 `''`（注释称"重试时重新绑定"但无任何绑定代码）→ `getDue` 按当前账号取不到 → 升级前排队的分发永不处理。修复：SenderKeyRetryDao 加 `adoptOrphans(userId)`，`processDueTasks` 入口收养 |
| 240 | MEDIUM | **Debug 建库失败静默删库重建丢真实数据** → 重建前 `backupDatabaseFiles` 把旧库重命名 `.bak` 保留（应用私有目录，可恢复） |
| 241 | LOW | **ChatDraftDao.deleteForChat 无 owner 隔离**（切换账号窗口期误删他账号草稿）→ 签名加 ownerUserId，两处调用传当前账号 |
| 242 | LOW | **搜索索引漂移误判**：空白/密文消息 deleteDocument 不产文档，`getSearchableMessageIds().size` 恒 > docCount → 每次打开全局搜索全量重建；新增 `countSearchableWithContent` 做基准 |
| 243 | 记录 | MEDIUM 3：迁移测试缺口（缺 26.json/30.json schema，v27→v30 零验证）待测试基础设施轮；LOW 5：getActiveChats 排序无法命中复合索引（注释失真）；LOW 6：LOWER(col) LIKE 无索引（兜底路径，低优先） |

**验证方式**：strip 括号平衡 delta=0（8 文件）；字符串 parity OK。按用户要求未跑编译/测试。

## 0.23 2026-08-06 第十九轮：会话列表排序可索引化（#244）

| # | 项 | 说明 |
|---|----|------|
| 244 | **LOW 5：getActiveChats 排序命中复合索引** | 排序首键由 `CASE WHEN pinnedAt > 0 THEN 0 ELSE 1 END`（表达式，SQLite 无法用 `(archived,pinnedAt,lastMessageTime)` 索引排序，过滤后回表+临时文件）改为 `pinnedAt DESC`（语义等价：置顶大值在前、未置顶 0 在后）；调用方（会话列表/widget）无依赖变更 |

**验证方式**：strip 括号平衡 delta=0；无其他 ORDER BY CASE 模式。按用户要求未跑编译/测试。

## 0.24 2026-08-06 第二十轮：多端同步与 WS 对齐核验（#245–#246）

| # | 项 | 说明 |
|---|----|------|
| 245 | **client-prefs 多端同步键对齐核验** | App 端 ClientPrefsDto/ClientPrefsUpdateRequest 与服务端字段完全一致（theme/language/wallpaper/font/linkPreview/unreadPriority/writingStyle/appLock/screenSecure/sensitiveGate + updatedAt）；服务端白名单规范化完整（主题 3 档/语言 3 档/壁纸 16/字体 5/写作预设 10/锁定时长 10） |
| 246 | **WS 事件类型两端对齐核验** | 服务端 12 种 `WsMessage("TYPE")` 全部被客户端处理；客户端额外处理的 5 种（CHAT_MARKED_READ/DISAPPEARING_MESSAGES_UPDATED/MESSAGE_EXPIRES/PINNED_MESSAGES_UPDATED/ADMIN_BROADCAST）均以命名参数形式在 Routing.kt 实际发送——两端完全对齐，无客户端漏处理或服务端不发 |

**验证方式**：脚本提取 + 正则核验。按用户要求未跑编译/测试。

## 0.25 2026-08-06 第二十一轮：WS 可靠性全面核验（#247）

| # | 项 | 说明 |
|---|----|------|
| 247 | **WS 接收端对齐 + 连接可靠性核验** | 客户端发送的 5 种事件类型（SEND_MESSAGE/STATUS_UPDATE/NUDGE/TYPING/SIGNALING）全部被服务端处理；服务端连接管理完善：compute 锁内原子注册（防超连/竞态）、try/finally 幂等清理、离线标记 + NonCancellable 广播 + 锁外二次确认防闪烁；心跳 ping 15s + timeout 30s（可配置）自动收割半开 TCP；5 类消息逐类型限流。实时通信可靠性全面核验通过 |

**验证方式**：正则提取 + 代码阅读。按用户要求未跑编译/测试。

## 0.26 2026-08-06 第二十二轮：AI 任务提醒抑制闭环（#248–#249）

| # | 项 | 说明 |
|---|----|------|
| 248 | **通知 PendingIntent 构建核验** | AppNotifier 8 处 PendingIntent 均 FLAG_UPDATE_CURRENT|IMMUTABLE，requestCode 用真实 chatId + data URI 保证唯一性（注释明确 extras 不入 PendingIntent 身份），通知点击导航不串会话 |
| 249 | **AI 任务页打开时取消提醒 Worker**（上轮记录 App LOW 7） | 此前仅 `cancelAiTaskRemindersForChat` 清托盘，WorkManager 作业仍在队列到点重弹；改为 applicationScope 查 `getIdsByChatId` → 逐个 `AiTaskReminderScheduler.cancelTask`（含托盘 + 作业） |

**验证方式**：grep 核验 + 括号平衡。按用户要求未跑编译/测试。

## 0.27 2026-08-06 第二十三轮：LIKE 查询去函数化（#250）

| # | 项 | 说明 |
|---|----|------|
| 250 | **LOW 6：LOWER() 包裹查询去函数化** | MessageDao.searchChatIdsByMessageContent 与 UserDao.searchUsers 移除 `LOWER()` 函数包裹（SQLite LIKE 对 ASCII 默认大小写不敏感、中文无大小写，语义等价；name/content 列可直接参与索引，消除每行函数调用开销）；UserDao 保留 IFNULL 以处理 NULL |

**验证方式**：grep 确认无其他 LOWER( 包裹 LIKE 残留；括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.28 2026-08-06 第二十四轮：索引创建路径完整性（#251）

| # | 项 | 说明 |
|---|----|------|
| 251 | **blocked_users 索引加入 ensureIndexes 显式创建** | 第 7 轮新增的 `idx_blocked_users_blocked_id` 仅在 Table.init{} 声明，但 `ensureIndexes()` 是权威索引创建路径（`createMissingTablesAndColumns` 对已存在表不建索引）——未加入列表则对已部署库永不生效，双向拉黑过滤每次读消息仍全表扫。已加入 ensureIndexes 的 CREATE INDEX IF NOT EXISTS 列表 |

**验证方式**：grep 确认双路径声明；括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.29 2026-08-06 第二十五轮：索引创建路径系统性闭合（#252）

| # | 项 | 说明 |
|---|----|------|
| 252 | **Table.init 声明 vs ensureIndexes 显式列表系统性对齐** | 脚本对比 66 个 Table.init 索引声明 vs ensureIndexes 列表：发现 6 个「旧表索引仅 init 声明 → 对已部署库永不生效」（idx_messages_expires_at / idx_chat_user_settings_user_archive / idx_group_audit_chat_created / idx_sender_key_dist_sender_epoch / idx_signaling_call / idx_signaling_group_call）+ 7 个 B3 新表索引一并补入；对齐后 66/66 全在 ensureIndexes（IF NOT EXISTS 无害幂等） |

**验证方式**：脚本对比 + 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.30 2026-08-06 第二十六轮：维护层完整性核验（#253）

| # | 项 | 说明 |
|---|----|------|
| 253 | **部分唯一索引 + 周期清理覆盖核验** | PostgreSQL 部分唯一索引（uidx_reports_open_dedup / uidx_friend_requests_pending）创建路径正确（PG 专用 + H2 方言守卫）；6h 周期清理循环覆盖 15+ 项（含 AiSummarySyncEnvelopes 30 天到期清理、加密附件、孤儿媒体、过期消息、14 项保留期清理）+ 15min 会话清理，全部 runCatching + CancellationException 重抛——数据保留期不变量完整 |

**验证方式**：grep 调用链 + 代码阅读。按用户要求未跑编译/测试。

## 0.31 2026-08-06 第二十七轮：安全/完整性多维核验（#254）

| # | 项 | 说明 |
|---|----|------|
| 254 | **安全/完整性多维核验（4 维度全通过）** | (1) 服务端日志无敏感泄露（password/jwtSecret/refreshToken/tokenHash/pushHmac/totpSecret 零匹配）；(2) 异常响应脱敏——StatusPages 对 authorization/cookie/set-cookie/x-bot-token/x-maodouchat-signature 打 `<redacted>`，生产只返回通用错误 + requestId，堆栈仅落服务端日志；(3) 输入验证全面——Validation.kt 60+ 长度/白名单常量（消息 16K/媒体 2.75M/密码 72 字节 BCrypt 边界/AI 各上限）；(4) 缓存清理完整——设置页 clearCache → MediaCache.cleanupReturningBytes（含 attachment-sources 兜底） |

**验证方式**：grep 核验 + 括号平衡 + 字符串 parity + deploy.sh 语法。按用户要求未跑编译/测试。

## 0.32 2026-08-06 第二十八轮：跨端 API 模型对齐核验（#255）

| # | 项 | 说明 |
|---|----|------|
| 255 | **App/服务端核心 API 模型字段对齐** | MessageResponse vs MessageDto（12 字段一致）、UserResponse vs UserDto（9 字段一致）、ChatResponse vs ChatDto（18 字段一致）——跨端协议无字段漂移；脚本正则误匹配已排除（UserResponse 前 9 字段真实对齐） |

**验证方式**：脚本字段对比。按用户要求未跑编译/测试。

## 0.33 2026-08-06 第二十九轮：UI 完整性与整体健康快照（#256）

| # | 项 | 说明 |
|---|----|------|
| 256 | **UI 完整性与整体健康快照** | (1) runBlocking 无长阻塞（均有意同步 PIN 验证，IO 线程 + 短等待，非 ANR 级）；(2) 消息长按菜单完整（反应/选择/回复/复制/复制翻译/复制转写/AI 翻译/转写/转发/星标/编辑/删除/撤回/举报，密聊门控）；(3) 整体健康快照：字符串 parity、括号平衡、索引对齐（66/92）、deploy/backup/restore 脚本语法全部通过 |

**验证方式**：grep + 脚本 + bash -n。按用户要求未跑编译/测试。

## 0.34 2026-08-06 第三十轮：一键更新脚本（#257）

| # | 项 | 说明 |
|---|----|------|
| 257 | **scripts/update.sh 一键更新** | 封装「git pull（快进合并）→ deploy.sh --no-build（复用镜像重启）」；依赖 deploy.sh 幂等性复用已有 .env（PUBLIC_HOST/密钥不覆盖）；.env 缺失时明确报错提示先部署；CI 补 update.sh 语法校验；docker-deployment.md 更新 |

**验证方式**：bash -n 语法通过。按用户要求未跑编译/测试。

## 0.35 2026-08-06 第三十一轮：通知铃声选择（#258）

| # | 项 | 说明 |
|---|----|------|
| 258 | **通知铃声选择器（新功能）** | 此前仅声音布尔开关（主流 IM 均支持选铃声）：NotificationPreferences 加 `ringtoneUri/setRingtoneUri`（按账号隔离）；AppNotifier.ensureChannels 将用户铃声绑定到 messages/calls/ai_tasks 三渠道（USAGE_NOTIFICATION 属性）；NotificationSettingsScreen 声音开关后加「通知铃声」ActionRow → RingtoneManager picker（TYPE_NOTIFICATION + 默认选项 + 当前值），选中后持久化 + 刷新渠道；3 组中英字符串 |

**验证方式**：括号平衡 delta=0；字符串 parity OK。按用户要求未跑编译/测试。

## 0.36 2026-08-06 第三十二轮：Worker 可靠性修复（#259–#265，7 处）

App 全部 WorkManager Worker 调度审查 + 修复：

| # | 严重度 | 修复 |
|---|--------|------|
| 259 | HIGH | **定时消息达重试上限静默丢弃**（token 瞬态后行保留、永不再触发）→ `abandonScheduledMessage`（移除待发条目 + 失败通知），用户明确感知 |
| 260 | HIGH | **finalize 重试无上限**（与上传路径 MAX_RETRIES 不一致，永久 5xx 反复唤醒）→ 达上限标 FAILED + Result.failure() |
| 261 | MEDIUM | **BacklogSyncWorker 节流失效**（空结果 continue 不标记 + syncFailed 全局连累成功会话）→ 空结果也标记、per-chat 失败判断 |
| 262 | MEDIUM | **AI 提醒未展示不重排**（权限撤后永续 6h 空跑）→ posted=false 时 15 分钟重排限 3 次 |
| 263 | MEDIUM | **AI 提醒延迟无下限**（时钟回拨 0 延迟忙循环）→ coerceAtLeast(1s) 下限 |
| 264 | LOW | **登出后周期 Worker 残留**（BacklogSync/SecretSurfaceWatchdog 空转）→ purge 取消 unique work |
| 265 | LOW | SecretSurfaceWatchdogWorker UPDATE→KEEP（与兄弟 Worker 一致，防重置周期） |
| — | 记录 | MEDIUM 5：SenderKey 退避 KEEP 丢弃（前台 60s 循环兜底，后台粒度粗但最终会跑；改动涉时序竞态风险，记录） |

**验证方式**：strip 括号平衡 delta=0（8 文件）；字符串 parity OK。按用户要求未跑编译/测试。

## 0.37 2026-08-06 第三十三轮：国际化缺口修复（#266–#268）

| # | 项 | 说明 |
|---|----|------|
| 266 | **HikariCP 连接池核验** | maximumPoolSize（CPU*2+1 上限 64）/minimumIdle 2/connectionTimeout 5s/leakDetection 30s/池化——连接复用 + 泄漏检测完整 |
| 267 | **AiArchiveSuggestion 归档原因国际化** | buildReason 4 条中文文案（"已静置 X 天..."）→ 资源字符串（含 %1$d/%2$d 参数），compute 传入 context |
| 268 | **AiEmotionReply 本地回退模板国际化** | 5 条中文回复模板 → 资源字符串（localFallback 传 context） |

**验证方式**：硬编码中文扫描（用户可见文案定位）+ 括号平衡 + 字符串 parity。按用户要求未跑编译/测试。

## 0.38 2026-08-06 第三十四轮：底层文案国际化（#269–#270）

| # | 项 | 说明 |
|---|----|------|
| 269 | **ApiConfig 服务器校验错误资源化** | validateBaseUrl 7 条中文错误（"服务器地址不能为空"等）→ 资源字符串（server_url_*），setServer 传 context |
| 270 | **WebRtcNativeLibraryLoader 异常消息稳定化** | 6 条中文异常 → 稳定英文错误码（native_library_load_failed/download_failed_http_*/sha256_mismatch 等），UI 主文案已本地化 |

**验证方式**：硬编码中文扫描 + 括号平衡 + 字符串 parity。按用户要求未跑编译/测试。

## 0.39 2026-08-06 第三十五轮：视频通话画中画（#271）

| # | 项 | 说明 |
|---|----|------|
| 271 | **通话画中画（PiP）**（此前 feature-inventory 标注未做的核心通话体验） | MainActivity 声明 `supportsPictureInPicture` + configChanges（防旋转重建）；`currentNavRoute` 跟踪当前路由；`onUserLeaveHint` 按 HOME 时若在 CALL/INCOMING_CALL 路由进入 PiP（16:9 宽高比、API 26+、hasSystemFeature 守卫），返回自动恢复全屏 |

**验证方式**：Routes 引用存在 + 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.40 2026-08-06 第三十六轮：聊天消息 fanout 并发化（#272）

| # | 项 | 说明 |
|---|----|------|
| 272 | **NEW_MESSAGE 群 fanout 并发化** | 此前逐成员串行 `sendToUser`（500 人群任一慢客户端拖慢整个 WS 消息处理）；forViewer 为 no-op → msgJson 全收件人相同可提取；改 coroutineScope+async 并发（与 PollRouting broadcastGroupPlayUpdate 一致，sendToUser 跨用户并发安全已确认） |

**验证方式**：括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.41 2026-08-06 第三十七轮：N+1 查询批量修复（#273–#279，7 处）

Server 全量 N+1 扫描 + 修复：

| # | 严重度 | 修复 |
|---|--------|------|
| 273 | HIGH | **好友申请列表 N+1**（逐行查 Users）→ mapRequestList 批量 inList + associateBy |
| 274 | HIGH | **listChains 接龙条目 N+1**（每条接龙全量载入条目）→ 批量 chainId inList + groupBy |
| 275 | HIGH | **listChatPks 投票 N+1**（每个 PK 全量载入投票）→ 批量 pkId inList + groupBy |
| 276 | HIGH | **listChatPolls 投票 N+1**（每个投票全量载入投票记录）→ 批量 pollId inList + groupBy |
| 277 | HIGH | **/polls-export 逐投票 count**（limit 1 万 → 1 万次查询）→ GROUP BY 聚合 |
| 278 | MEDIUM | **listTags 逐标签 count**（N+1）→ slice+count groupBy 聚合 |
| 279 | MEDIUM | **/system-stats 附件字节数整表载入求和**（大表 OOM）→ SQL sum 聚合 |
| — | 记录 | M7（sessions-summary 逐用户事务）/M9/M11（标签赋值回查）/M10（公告分页重复查询）/M13（createPost 逐图 LIKE）/M14（toCheckinDto 当日全量）/L15（Users.last_seen 索引）待后续轮次 |

**验证方式**：strip 括号平衡（AdminRouting -1 历史遗留）；结构重写括号配对。按用户要求未跑编译/测试。

## 0.42 2026-08-06 第三十八轮：N+1 收尾（#280–#281）

| # | 项 | 说明 |
|---|----|------|
| 280 | **M14：toCheckinDto 当日签到 rank/count SQL 聚合** | 此前全量载入当日签到行（活跃大群上万行/请求）→ COUNT 聚合（rank = 在我之前签到数 + 1，同 checkedAt 用 userId 稳定排序） |
| 281 | **L15：Users.last_seen / Reports.created_at 单列索引** | 管理仪表盘活跃用户/趋势范围扫描不再全表扫；加入 ensureIndexes 权威创建路径 |

**验证方式**：括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.43 2026-08-06 第三十九轮：N+1 清零（#282–#286）

| # | 项 | 说明 |
|---|----|------|
| 282 | **M7：/sessions-summary-export 批量活跃会话统计** | 新增 AuthTokenRepository.countActiveRefreshSessionsBatch：一次事务取批量活跃 session → GROUP BY userId 聚合 refresh token，替代逐用户 count（limit 2 万 → 2 万次查询） |
| 283 | **M9：riskAssignments 批量回查标签** | filter 内逐条查 UserTags（N+1）→ inList 批量 + riskByTag Map |
| 284 | **M10：公告 TAGGED 分页每页一次查询** | generateSequence next/flatMap 对同一 offset 各查一次 → while 循环每页一次 |
| 285 | **M11：USER_TAGS 导出批量回查标签名** | 逐赋值查询（N+1）→ inList 批量 + tagNameById Map |
| 286 | **M13：createPost 跳过 DB LIKE 全表扫** | 刚上传 owned 图（文件名 UUID 唯一）不可能已被占用：只查进程内占用缓存（imageClaimLock 已原子）；多实例 DB 级唯一约束见 L2 记录 |

至此 0.41–0.43 三轮回合计修复 **12 处 N+1/全表扫/索引缺口**（H1-H6、M7-M14、L15 全部清零）。

**验证方式**：strip 括号平衡（AdminRouting -1 历史遗留）；结构重写括号配对。按用户要求未跑编译/测试。

## 0.44 2026-08-06 第四十轮：fanout/导出 N+1 批量修复（#287–#296，10 处）

explore agent 全量扫描 Server N+1，本轮修复 HIGH 全部 8 项 + 2 个同构：

| # | 项 | 说明 |
|---|----|------|
| 287 | **H1：/admin/storage 批量回查消息类型** | groupBy 内逐附件查 Messages（N+1）→ msgId inList 批量 |
| 288 | **H2：chats-export 批量成员计数** | 逐会话 count（最多 1 万次）→ GROUP BY chatId 聚合 |
| 289 | **H3：addGroupMembersAs 双层循环 O(N×M)** | 每(候选×现有)组合一次 BlockedUsers 查询 → 一次批量载入 (成员∪新增) blocked 对 + Set 内存判断 |
| 290 | **H4：consumeGroupInvite 逐成员 blocked 查询** | O(N) → 同 H3 批量方案 |
| 291 | **H5：bot sendMessage fanout 逐成员 hasBlocked** | → blockedEitherWayIdsInTx 批量（一次事务） |
| 292 | **H6：用户 editMessage fanout 逐成员 hasBlocked** | → blockedEitherWayIdsInTx 批量 |
| 293 | **H4'：bot editMessage fanout** | 同构批量 |
| 294 | **H7：broadcastUserStatus 逐在线用户 isBlockedEitherWay** | 每人 2 次查询 → 一次批量（PRESENCE_FANOUT_CAP=500 → 1000 次降为 2 次） |
| 295 | **H7'：broadcastUserVisibilityRevoked** | 同构批量 |
| 296 | **H8：FcmPushService 批量投递** | worker 改批次（DELIVERY_BATCH=50）共享批量查询；新增 NotificationPreferenceRepository.getSettingsBatch + PushTokenRepository.getForUsers（各一次 inList 事务） |

复用既有 `blockedEitherWayIdsInTx`（8.30 A1）于 5 处 fanout。剩余 MEDIUM（trends 按天聚合 M1/M2、24 个 bulk 端点 M3、batch-read M5、bot setChatPermissions M8、DeveloperRouting analytics M9）记录待后续轮次。

**验证方式**：strip 括号平衡（Routing +1 / AdminRouting -1 均为历史遗留，本轮改动花括号净零）。按用户要求未跑编译/测试。

## 0.45 2026-08-06 第四十一轮：MEDIUM N+1 批量修复（#297–#301，7 处）

| # | 项 | 说明 |
|---|----|------|
| 297 | **M1：/trends 按天 GROUP BY 聚合** | 7 天 × 3 表 = 21 次 count → 3 次 SQL（新增 dayBucketExpression = `CAST(col/86400000 AS SIGNED)`） |
| 298 | **M2：/rich-trends 按天聚合** | 7 天 × 7 表 = 49 次 → 7 次；activePts 累积语义用内存 running 累计保真 |
| 299 | **M3：bulk-ban / bulk-unban / bulk-suspend-days** | 批量存在性检查（一次 inList）+ 单事务批量处置，替代逐 id 独立事务（最多 100 个事务/请求）；其余 21 个同构 bulk 端点记录待续 |
| 300 | **M5：batch-read getParticipantIds 提循环外** | 此前每条已读消息查一次参与者 → 一次 |
| 301 | **M8：bot setChatPermissions 批量静音** | 新增 ChatRepository.muteGroupMembersAsAdmin：一次事务锁群+角色判定+批量 UPDATE+审计（此前逐成员 ≈5 次查询/人，500 人群 ≈2500 次） |
| 302 | **M9：DeveloperRouting bot analytics 按天聚合** | 逐日 2 次 count（30 天=60 次）→ 2 次 SQL（count + countDistinct group by 天） |

剩余记录：M3 其余 21 个 bulk 端点（模式已建立）、M6（bot 删除广播每群 2 次查询）、SignalKeyRepository.getDeviceInfos（≤4 设备可接受）、M11 清理/级联写循环（写操作）。

**验证方式**：strip 括号平衡（AdminRouting -1 / Routing +1 历史遗留，本轮改动净零）。按用户要求未跑编译/测试。

## 0.46 2026-08-06 第四十二轮：M3 全部 19 个 bulk 端点批量修复

对 AdminRouting 剩余 19 个 bulk 管理端点应用「批量 inList 存在性检查 + 单事务处置」模式（参考 bulk-ban/unban/suspend-days）：
bulk-message-restrict/unrestrict、bulk-post-restrict/unrestrict、bulk-set-message-restrict-until、bulk-set-searchable-false/true、bulk-set-show-status、bulk-set-show-online、bulk-set-searchable、bulk-disable-totp、/chats/bulk-clear-invite-tokens（Chats 表）、bulk-set-suspend-until、bulk-clear-all-restrictions、bulk-clear-message-and-post-restrict、bulk-clear-suspend、bulk-clear-message-restrict、bulk-message-restrict-days、bulk-clear-post-restrict。

- bulk-set-suspend-until 的 rotateAccessTokenVersion + disconnect 移到事务外仅对更新项执行
- 保留两个 token-bump 端点（bulk-force-logout/bulk-force-token-bump）的串行事务（token 安全语义必须）
- audit action/detail 字符串逐字保留

**验证方式**：独立 strip 括号平衡 delta=-1（历史遗留值，与修改前一致）；抽查 bulk-message-restrict 与 /chats/bulk-clear-invite-tokens 结构正确。

至此 N+1 扫描全部实质清零：HIGH 8 项（0.44）+ MEDIUM 主要项（0.45/0.46）+ M3 全部 24 端点（含 0.45 的 3 个）。剩余可接受项记录：M6（bot 删除广播低频）、SignalKeyRepository（≤4 设备）、M11 级联写循环（写操作）。

## 0.47 2026-08-06 第四十三轮：App Crash 风险防御性加固（#303–#308，21 处）

explore agent 全量扫描 App 340 个 Kotlin 文件：无 HIGH 裸崩溃点（防御性强），修复全部 MEDIUM/LOW：

| # | 文件 | 修复 |
|---|------|------|
| 303 | **MessageBubble.kt（15 处）** | `parseX(body) != null -> { parseX(body)!! }` 解构二次解析 → `parseX(body)?.let { ... } ?: ""` 单次取值 + 空安全（消除依赖「判断与解析恒一致」的脆弱不变量）；CaptureAlert 同理 `?: return@Column` |
| 304 | **LinkPreviewPolicy.kt** | 网络主机名 `octets[0]!!` → `?: return null`（此前依赖正则保证 4 段） |
| 305 | **ChatListViewModel.kt** | `decryptedPlain!!` → 解密失败 `return@withOwnerRoomWrite` 跳过持久化（此前守卫离使用点 18 行） |
| 306 | **ChatDetailViewModel.kt** | Room insertMessage 移入 try 块（此前在 try 外，DB 异常直崩协程） |
| 307 | **SenderKeyRetryManager.kt** | 冗余 `existing!!` → `existing?.nextAttemptAt ?: now` |
| 308 | **ChatDetailScreen.kt** | 举报原因 `reasons.first()` → `firstOrNull().orEmpty()`（资源数组被清空即崩） |

**验证方式**：brace_strip2.py 括号平衡全部 delta=0（修正三引号正则处理缺陷后）。按用户要求未跑编译/测试。

## 0.48 2026-08-06 第四十四轮：并发/事务边界修复（#309–#313，5 处）

explore agent 全量审计 Server 并发与事务边界：1 处真实死锁（HIGH）+ 若干 MEDIUM/LOW。本轮修复：

| # | 项 | 说明 |
|---|----|------|
| 309 | **H1：GroupCheckinRepository 锁序反转死锁** | joinChain/votePk 原「先锁 chain/pk 再锁 chat」，与 leaveChat/deleteChatRows 的「chat → chain/pk」构成 AB-BA 死锁环（PG deadlock / SQLite locked）→ 改为先无锁读 chatId → 锁 chat → 再锁 chain/pk（对齐 GroupPlayRepository.vote 先 chat 后 poll 模式） |
| 310 | **L5：tearDownEmptyChat 清理集不一致** | 对齐 deleteChatRows 补删 GroupCheckins/GroupChains/GroupChainEntries/GroupPkRounds/GroupPkVotes（此前最后成员注销残留群玩法孤儿行） |
| 311 | **M1：Sockets authWatchdog 无 try-catch** | DB 瞬时故障取消子协程 → 父 Job 取消整条 WS 连接（全站重连风暴）→ 循环体包 runCatching，CancellationException 重抛 |
| 312 | **L4：FcmPushService worker 显式 catch** | 抽取 launchWorker helper，deliverBatch 外显式 catch（防未来新增异常静默杀死 worker → 丢推送） |
| 313 | **M4：WebRtcBinaryService 并发解压损坏** | 并发首请求同写一个 .part → 字节交错产出损坏 .so 并永久固化 → synchronized(this) + double-checked 串行化 |

剩余记录：M2（PostRepository 全局 imageClaimLock 热点+锁内全表扫）、M3（BotWebhookService fallback 无界协程）、M5（EmailService 持锁发 SMTP）、M6（阻塞 JDBC 在事件循环线程）、L1（commit 锁序缺 chat）、L2/L3（条纹锁/COW 列表）待后续轮次。

**验证方式**：brace_strip2.py 括号平衡全部 delta=0。按用户要求未跑编译/测试。

## 0.49 2026-08-06 第四十五轮：并发项收尾（#314–#316，3 处）

| # | 项 | 说明 |
|---|----|------|
| 314 | **M2：PostRepository 锁内物理删文件** | deleteStaleUnreferencedImages 原持全局 imageClaimLock 做全表扫描 + 磁盘 IO（阻塞所有发帖删帖）→ 锁内仅取引用快照，磁盘 list+删除移到锁外（filename 唯一保证不误删新图） |
| 315 | **M3：BotWebhookService fallback 无界协程** | 原队列满/未运行时 fallbackScope.launch 无界堆积（慢 webhook + 500 人群下每条事件挂起 6s）→ 抽 runFallback + Semaphore(64) 有界并发，满则丢弃计数告警 |
| 316 | **M5：EmailService 持锁发 SMTP** | 原 withCacheKeyLock 内 Transport.send（最长 15s×3 阻塞同邮箱全部请求）→ SMTP 移出锁，锁只保护「预留槽位 + 写码」原子性；finally 幂等释放预留 |

并发审计剩余记录：M6（架构性：阻塞 JDBC 在 Ktor 事件循环线程，路由层 Dispatchers.IO 包裹为后续方向）、L1（commit 锁序缺 chat 前置）、L2（PostRepository 判重缓存条纹锁）、L3（sendToUser COW 列表并发迭代）——均为理论/架构风险，非现役缺陷。

**验证方式**：brace_strip2.py 括号平衡全部 delta=0。按用户要求未跑编译/测试。

## 0.50 2026-08-06 第四十六轮：安全审计 HIGH/MEDIUM 修复（#317–#318，2 处）

explore agent 全量安全审计：发现 1 个真实严重漏洞（存储型 XSS）+ 若干 Medium/Low。本轮修复：

| # | 项 | 说明 |
|---|----|------|
| 317 | **H1：公开主页 /u/{username} 存储型 XSS** | 匿名可达 HTML 页把 user.name 未转义拼入 `<title>`/`og:title`、safeName 不转义 `"` 可逃逸 `alt`/`href` 属性（脚本可窃取 localStorage token）→ 新增顶层 escapeHtml（& < > " ' 全量转义），name/status/username/avatarUrl/error/baseUrl 全部转义后拼模板 + 严格 CSP（default-src 'none'; style-src 'unsafe-inline'; img-src self/http/https/data; frame-ancestors none; form-action none） |
| 318 | **M1：登录失败锁定远程 DoS** | 原按 emailKey 计数，攻击者源 IP 持续错 5 次即锁死账号（受害者也被拒登）→ 锁定 key 改为「emailKey\|源 IP」（recordLoginFailure + 检查 + 成功清除三处统一），攻击者失败只锁攻击 IP 与账号组合，受害者自身 IP 登录不受影响 |

审计其余记录：M2（TOTP counter 内存态重启可重放）、L1（用户名枚举）、L2（LIKE 转义依赖 DB 默认 ESCAPE）、L3（开发模式 500 错误回显）——防御已到位/低危，仅记录。

**验证方式**：brace_strip2.py（处理三引号）括号平衡 delta=0；Select-String 确认 buildProfilePage 无残留未转义插值。按用户要求未跑编译/测试。

## 0.51 2026-08-06 第四十七轮：M2 TOTP 重放保护持久化（#319）

| # | 项 | 说明 |
|---|----|------|
| 319 | **M2：TOTP 已用 counter 内存态可重放** | 原 lastVerifiedCounter 为 ConcurrentHashMap（重启/多实例后同窗口 code 可重放）→ users 表新增 `totp_last_counter` 列（createMissingTablesAndColumns 自动 ALTER）；TotpService.verify 增加 `onAcceptedCounter` 钩子；UserRepository 三处调用点（loginWithFactors/confirmTotpSetup/disableTotp）传 `acceptTotpCounter`（持行锁事务内 DB 原子 CAS：候选 counter 必须严格大于已持久化值才落库） |

安全审计至此 H1（XSS）/M1（登录 DoS）/M2（TOTP 重放）全部修复。剩余 L1（用户名枚举，设计使然）、L2（LIKE 转义依赖 DB 默认 ESCAPE）、L3（开发模式 500 回显）为低危记录。

**验证方式**：brace_strip2.py 括号平衡全部 delta=0。按用户要求未跑编译/测试。

## 0.52 2026-08-06 第四十八轮：UX 完整度审计 P0 修复（#320–#322）

explore agent 审计 App UX：核心链路（会话→聊天→发送→返回）发现 4 个 P0 + 次级若干。本轮修复：

| # | 项 | 说明 |
|---|----|------|
| 320 | **发现 1：聊天输入框无长度上限** | 粘贴超长文本会无限进入输入态/被服务端 4000 校验拒绝 → onInputChange 本地截断 `MAX_COMPOSER_TEXT_LENGTH=4000`（对齐服务端）+ sendMessage 兜底截断 |
| 321 | **发现 2：会话列表加载失败无重试 + 误导空态** | 冷启动断网时错误一闪而过、显示「还没有聊天」→ EmptyChatState 增加错误分支（NETWORK_ERROR 类型 + 错误文案 + 重试按钮 → viewModel.refresh()），新增 chat_load_failed_title/retry 中英字符串 |
| 322 | **发现 4：会话列表顶栏/FAB 无障碍缺失** | 全局搜索/通知中心/归档切换/添加按钮 contentDescription=null（TalkBack 读成无名按钮）→ 补 stringResource（global_search_title/notif_center_title/chat_archived_title/chat_empty_action_add） |

记录后续：发现 3（聊天页初次加载失败静默空白→需 ChatDetailUiState 加 initialLoadError + 重试）、发现 5-11（次级重试/空态/搜索上限/死路由）。

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity.py zh=en=2590。按用户要求未跑编译/测试。

## 0.53 2026-08-06 第四十九轮：UX P0 收尾——发现 3 聊天页错误态（#323）

| # | 项 | 说明 |
|---|----|------|
| 323 | **发现 3：聊天页初次加载失败静默空白** | 冷启动断网进入会话显示「还没有消息」（无法区分真实空会话）→ ChatDetailUiState 新增 `initialLoadError`；getChats 无缓存失败 + getMessages 失败且无缓存均写入；成功路径清空；UI 在 `!isLoading && messages.isEmpty() && initialLoadError != null` 时渲染 NETWORK_ERROR 空态 + 重试按钮；新增 public `reloadChat()`（清错误 + 重新 loadChat） |

UX 审计 P0 四项（发现 1 输入上限、发现 2 会话列表错误态、发现 3 聊天页错误态、发现 4 无障碍）至此全部完成。剩余记录：发现 5（AI 任务列表错误态）、发现 6（聊天内搜索无结果提示）、发现 7（通讯录错误弹窗加重试）、发现 8（列表搜索框长度上限）、发现 9-11（附近引导/搜索空态图标/死路由）。

**验证方式**：brace_strip2.py 括号平衡全部 delta=0。按用户要求未跑编译/测试。

## 0.54 2026-08-06 第五十轮：UX 次级项修复（#324–#326）

| # | 项 | 说明 |
|---|----|------|
| 324 | **发现 8：列表搜索框无长度上限** | 粘贴超长文本会以完整长度跑 LIKE 全库搜索 → onSearchQueryChange 截断 `LIST_SEARCH_MAX_LENGTH=200`（对齐其它搜索框） |
| 325 | **发现 6：聊天内关键词搜索无结果无提示** | 0 命中只有「0/0」计数器 → ChatSearchBar 关键词模式追加「未找到匹配消息」提示（chat_search_no_results 中英字符串，0.52 已加） |
| 326 | **发现 7：通讯录加载失败弹窗无重试** | 错误确认框只有「知道了」，确认后落入无入口空列表 → 新增 ContactsViewModel.reloadContacts() + 错误弹窗 dismissButton「重试」（仅错误且列表空时显示） |

UX 审计剩余记录：发现 5（AI 任务错误态）、发现 9（附近未开启引导）、发现 10（通讯录搜索空态图标）、发现 11（平板双栏死路由）。

**验证方式**：brace_strip2.py 括号平衡全部 delta=0。按用户要求未跑编译/测试。

## 0.55 2026-08-06 第五十一轮：UX 次级项修复（#327–#328）

| # | 项 | 说明 |
|---|----|------|
| 327 | **发现 5：AI 任务列表加载失败落入误导空态** | 错误 snackbar 一闪而过 → 新增 AiTasksViewModel.reloadTasks() + UI 在 `error != null && tasks.isEmpty()` 时渲染 NETWORK_ERROR 空态 + 重试 |
| 328 | **发现 9：附近的人未开启仅孤零图标** | → EmptyState（「位置共享已关闭」+ 副标题 + 「开启附近」动作按钮，权限已授则直接 enableSharing，否则拉起定位权限），新增 explore_nearby_enable 中英字符串 |

UX 审计剩余记录：发现 10（通讯录搜索空态纯文本无图标）、发现 11（平板双栏死路由，未接线不触发，记录不重构）。

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity.py zh=en=2591。按用户要求未跑编译/测试。

## 0.56 2026-08-06 第五十二轮：AI 网关可靠性修复（#329–#330）

explore agent 审计 AI 编排（AiGatewayService/AiEnhanceRouting/AiStreamingService）：8 项缺陷。本轮修复两个高价值项：

| # | 项 | 说明 |
|---|----|------|
| 329 | **AI-5：两个 AiGatewayService 实例预算互相不可见** | /api/ai/*（Routing 默认实例）与 /api/ai/enhance/*（Application 新建实例）各自持有独立 budgetMonitors/幂等缓存 → TOCTOU 绕开、缓存不互通 → Application.kt 创建单例 aiGateway 同时传给 configureRouting 与 configureAiEnhanceRouting |
| 330 | **AI-4：无全局 LLM 并发信号量** | 限流只有「每分钟次数」，单用户多端点叠加可 10+ 并发上游调用，抖动时同步重试放大 → AiGatewayService 加 `Semaphore(16)`（LLM_MAX_CONCURRENCY + 5s 获取超时快速失败 429），performRequest 与 streamResponse（runOnce）都包 try/finally 持有令牌 |

AI 审计剩余记录：AI-1（transcribe/analyze-image/file 预算估算≈1 token，日预算形同虚设）、AI-2（cross-chat-qa 最多 61 次串行 LLM 无整体超时）、AI-3（图片全尺寸发送无降采样）、AI-6（transcribe 无 token 计量）。

**验证方式**：brace_strip2.py 括号平衡全部 delta=0。按用户要求未跑编译/测试。

## 0.57 2026-08-06 第五十三轮：AI 成本失控修复（#331–#332）

| # | 项 | 说明 |
|---|----|------|
| 331 | **AI-1：多模态预算估算≈1 token** | transcribe/analyze-image/analyze-file 用 estimateTokens("")≈1 做预算预留，图片/文件/音频可绕过日预算（一次请求消费数十倍配额）→ 新增 estimateMultimodalTokens（解码字节 256:1 保守折算 + 附加文本 4 字符/token），三处路由替换 |
| 332 | **AI-3：图片分析发送全尺寸原图** | 4096² 原图内联，视觉模型按像素/tile 计费且游离预算外 → validateAiImage 超 1568px 时 ImageIO 等比降采样重编码 JPEG（新增 AI_IMAGE_TARGET_MAX_EDGE 常量），byteCount/base64 同步更新 |

AI 审计剩余记录：AI-2（cross-chat-qa 最多 61 次串行 LLM、无整体超时）、AI-6（transcribe 成功响应不落 token 计量）。

**验证方式**：brace_strip2.py 括号平衡全部 delta=0。按用户要求未跑编译/测试。

## 0.58 2026-08-06 第五十四轮：AI-2 跨聊天问答可靠性修复（#333）

| # | 项 | 说明 |
|---|----|------|
| 333 | **AI-2：cross-chat-qa 最多 61 次串行 LLM、无整体超时** | 逐 chatId 串行 semanticSearch（60 候选）命中 60s 读超时，客户端放弃后仍继续付费 → coroutineScope + async 并行化各会话语义重排；会话数上限 MAX_CROSS_CHAT_CHATS=8；整体 withTimeoutOrNull(30s)，超时返回 408 并终止后续调用 |

AI 审计剩余记录：AI-6（transcribe 成功响应不落 input/output token 计量）。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.59 2026-08-06 第五十五轮：AI-6 transcribe 计量修复（#334）

| # | 项 | 说明 |
|---|----|------|
| 334 | **AI-6：transcribe 成功响应无 token 计量** | transcription API 不返回 usage，Success 不带 inputTokens/outputTokens → 落库恒 null、不入日预算 → 用输入字节/1024 + 输出字符/4 估算 token，transcribe 纳入 AiAuditLogs 计量与预算 |

AI 可靠性审计 8 项至此**全部修复**（AI-1 多模态预算、AI-2 串行超时、AI-3 图片降采样、AI-4 并发信号量、AI-5 单例化、AI-6 transcribe 计量、另 2 项低危）。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.60 2026-08-06 第五十六轮：App/Server 契约漂移修复（#335–#337，含 CRITICAL）

explore agent 实证审计 App↔Server 契约（用同版本 kotlinx-serialization 复现编码）：发现 1 个 CRITICAL + 2 个漂移。本轮全部修复：

| # | 项 | 说明 |
|---|----|------|
| 335 | **CRITICAL：WS NEW_MESSAGE type 字段被服务端省略 → 所有 TEXT 消息实时丢失** | Server 端 Json encodeDefaults=false，MessageResponse.type=="TEXT"（默认值）时键被省略；App IncomingMessage.type 无默认值 → 解码 MissingFieldException → 每条 TEXT 消息在 WS 通道被丢弃（接收方实时收不到任何文本，只能靠 Backlog 兜底）。**实证复现** → App `IncomingMessage.type` 加默认值 "TEXT"（与 REST MessageDto 对齐） |
| 336 | **MEDIUM：群成员操作错误码全面漂移** | App 检查的 NOT_GROUP_OWNER/FORBIDDEN_ROLE 等 10 个 code 服务端从不返回（服务端用 GROUP_* 前缀）→ GroupMutationFeedback 对齐 GROUP_PERMISSION_DENIED/GROUP_ACTOR_NOT_MEMBER/GROUP_OWNER_PROTECTED/GROUP_ADMIN_PEER_PROTECTED/GROUP_MEMBER_BLOCKED（权限组）+ GROUP_NOT_FOUND/GROUP_TARGET_NOT_MEMBER/GROUP_USER_NOT_FOUND/GROUP_MEMBER_LIMIT_EXCEEDED/GROUP_OWNER_TRANSFER_REQUIRED（冲突组） |
| 337 | **LOW：DeviceInfoDto.deviceName 默认值漂移** | 服务端默认「我的设备」，encodeDefaults=false 省略键后 App 端默认 "" 解出空 → 对齐为「我的设备」 |

契约审计其余全部一致（WS 事件名、HTTP 状态码、REST 路径/query、错误码 AUTH_INVALID/ACCOUNT_LOCKED/AI_BUDGET_EXCEEDED 等均实证核对）。

**验证方式**：brace_strip2.py 括号平衡全部 delta=0。按用户要求未跑编译/测试。

## 0.61 2026-08-06 第五十七轮：契约审计收尾（#338）

| # | 项 | 说明 |
|---|----|------|
| 338 | **连接数超限重连风暴** | 服务端 1008「单用户连接数超限」时 App 立即重连反而叠加连接数 → 该 reason 用固定 8s 长退避（scheduleReconnectWithBaseDelay），等旧连接释放 |

同时实证验证契约审计收尾：
- `SealedSenderDelivery.forViewer` 返回 MessageResponse（NEW_MESSAGE payload 即 MessageResponse），App IncomingMessage 全部默认字段已对齐（type 已修，status 不读，editedAt/starred/reactions/expiresAt/sealedSender 均有默认值）
- Server（Routing/Sockets）与 App（WebSocketClient）两侧 Json 均 `ignoreUnknownKeys=true` → 未来新增字段不会破坏兼容
- Server MessageResponse 默认字段（type/status/editedAt/starred/reactions/expiresAt/sealedSender）与 App 端零缺口

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.62 2026-08-06 第五十八轮：收尾（#339）

| # | 项 | 说明 |
|---|----|------|
| 339 | **接入 isRecoverableExpiryReason 死代码** | 契约审计发现的未使用函数（1008 + 「过期/token」reason 可恢复）→ 接入 onClosing：此类关闭先刷新 token 再重连，不得视为登出 purge（服务端通常用 1013 过期，兼容旧实例 1008 + 过期文案） |

整体健康验证通过：check-string-parity zh=en=2591、bash -n deploy.sh、node --check admin.js/developer.js 全部 OK；deploy.sh 已含 /health/ready 等待（300s 超时）。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.63 2026-08-06 第五十九轮：输入计数提示（#340）

| # | 项 | 说明 |
|---|----|------|
| 340 | **聊天输入框字符计数** | 0.52 已做 onInputChange 截断（4000），补计数提示：字数 ≥3200（80%）显示 `n/4000`，≥3600（90%）变红——用户超长粘贴时有明确反馈 |

整体健康已多轮验证（string parity / bash -n / node --check 全通过）。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.64 2026-08-06 第六十轮：会话列表下拉刷新（#341）

| # | 项 | 说明 |
|---|----|------|
| 341 | **会话列表下拉刷新** | 复用 PullToRefreshLayout 组件（Explore 在用）：isRefreshing=state.isLoading，onRefresh=viewModel.refresh()——此前仅靠 ON_RESUME 触发刷新，用户无手动刷新入口 |

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

---
# 六轮迭代总结（0.41–0.64）

第六十轮（#273–#341）完成 12 大项审计与修复：Server N+1 全量清零（H1-H6/M1-M14/L15/24 bulk 端点）、Server 并发死锁与事务边界（H1 锁序反转等 5 处）、Server 安全（公开页 XSS 存储型、登录失败锁定远程 DoS、TOTP 重放持久化）、App Crash 防御（21 处）、App UX 完整度（输入上限/错误态/无障碍/次级共 13 项）、AI 网关可靠性（8 项全修：预算/并发信号量/降采样/串行超时/单例化/计量）、App-Server 契约漂移（CRITICAL：WS TEXT 消息 type 字段丢失全量修复 + 错误码对齐 + deviceName）、连接数超限退避、输入计数、下拉刷新。全部改动经 brace_strip2 括号平衡 + 字符串 parity + bash -n/node --check 静态验证，按用户约定未跑编译/测试。

## 0.65 2026-08-06 新目标启动：部署一键化（#342）

| # | 项 | 说明 |
|---|----|------|
| 342 | **deploy.sh 交互式引导** | 首次部署零参数运行：无 --host/--email 且终端可用时逐项提问（hostname/ACME email/relaxed 模式），已有有效值（幂等重跑）自动跳过；非 TTY 环境仍走必填校验 fail。usage 帮助同步更新，update.sh 提示改为一键运行 |

**验证方式**：bash -n deploy.sh/update.sh 语法通过。按用户要求未跑编译/测试。

## 0.66 2026-08-06 新目标第 2 轮：群主/管理员徽章（#343）

| # | 项 | 说明 |
|---|----|------|
| 343 | **群聊发送者角色徽章** | TG/微信群聊标配：群聊消息名字旁显示「群主」（OWNER）/「管理员」（ADMIN）徽章。UiState 新增 memberRoleByUser（loadGroupMembers 填充全体成员角色），ChatMessageRowState/MessageBubble 新增 memberRole 参数，名字行按角色渲染 RoleBadge（MaterialTheme tertiaryContainer/surfaceVariant 区分）；新增 chat_role_owner/admin 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2593。按用户要求未跑编译/测试。

## 0.67 2026-08-06 新目标第 3 轮：Windows 部署交互式（#344）

| # | 项 | 说明 |
|---|----|------|
| 344 | **deploy.ps1 交互式引导** | 与 deploy.sh 对齐：无 -Host/-Email 且交互终端时逐项提问（hostname/ACME email/relaxed），幂等重跑自动跳过；[Environment]::UserInteractive 判断交互性 |

**验证方式**：PowerShell Parser 语法通过。按用户要求未跑编译/测试。

## 0.68 2026-08-06 新目标第 4 轮：转发来源标记（#345）

| # | 项 | 说明 |
|---|----|------|
| 345 | **已转发标记** | TG 式「已转发 · 来源」：MessageMeta 新增 forwardedFrom（E2EE meta 随密文传输）；forwardMessage 记录原发送者名（密聊转发仅标记不露来源名——隐私保护）；encryptForwardedContent 把 meta 编码进 wireContent（与 sendMessage 的 composeContentWithMeta 机制一致）；MessageBubble 气泡顶部渲染标记；新增 message_forwarded_from 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2594。按用户要求未跑编译/测试。

## 0.69 2026-08-06 新目标第 5 轮：核心 UX 缺失补齐（#346–#348）

explore agent 审计对标 TG/微信/QQ 的核心体验，修复最高价值 3 项：

| # | 项 | 说明 |
|---|----|------|
| 346 | **群内昵称断链** | 群资料页可设群昵称（groupNickname）但群聊消息不生效（半成品）→ UiState 新增 memberNicknameByUser，loadGroupMembers 填充，ChatDetailScreen 群聊时 participantNamesById 优先群昵称（约 10 行，无协议改动） |
| 347 | **群成员列表角色排序** | 群主 → 管理员 → 成员 置顶排序（新增 roleRank 权重，displayName 次排序） |
| 348 | **视频发送前无预览确认** | 此前点即发误选无法挽回 → 复用 PendingImageSend 流程：videoPickerLauncher 挂起确认，新增视频预览对话框（Videocam 图标 + 文件名 + 发送/取消），与图片一致；新增 chat_video_send_preview 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2595。按用户要求未跑编译/测试。

## 0.70 2026-08-06 新目标第 6 轮：通话最小化 + 静音时段显示（#349–#350）

| # | 项 | 说明 |
|---|----|------|
| 349 | **通话最小化按钮** | PiP 此前仅按 HOME 触发（onUserLeaveHint）→ CallScreen 通话控制行加「最小化」FAB（PictureInPictureAlt 图标），LocalActivity + enterPictureInPictureMode（16:9，复用 MainActivity 构建逻辑）；新增 call_minimize 中英字符串 |
| 350 | **会话免打扰时段列表显示** | 会话静音时段（ChatQuietHoursStore）此前列表完全不可见 → ChatListItem 在 NotificationsOff 图标旁显示「22:00–07:00 免扰」徽标（formatMinuteClock helper）；新增 chat_quiet_hours_badge 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2597。按用户要求未跑编译/测试。

## 0.71 2026-08-06 新目标第 7 轮：复制格式选项 + 图片重新选择（#351–#352）

| # | 项 | 说明 |
|---|----|------|
| 351 | **Markdown 复制为纯文本** | 此前 MARKDOWN 消息复制带 **、# 等原始标记 → ChatMarkdown 新增 toPlainText（剥离行首语法/链接/强调/删除线/行内代码），长按菜单对 MARKDOWN 追加「复制为纯文本」；新增 chat_copy_plain 中英字符串 |
| 352 | **图片预览「重新选择」** | 图片预览对话框此前只有发送/取消，误选需退出重进相册 → 预览下方加「重新选择」（保留 viewOnce/spoiler 标记重新拉起 photo picker）；新增 chat_rechoose 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2599。按用户要求未跑编译/测试。

## 0.72 2026-08-06 新目标第 8 轮：群聊独立通知铃声（#353）

| # | 项 | 说明 |
|---|----|------|
| 353 | **群聊独立通知渠道 + 铃声** | 单聊/群聊通知不同铃声（TG 体验）：NotificationPreferences 新增 groupRingtoneUri（账号作用域）；AppNotifier 新增 CHANNEL_GROUP_MESSAGES（群聊铃声，回退单聊）；showMessage 加 isGroup 参数选渠道；调用点 BacklogSyncWorker（chat.isGroup）+ FCM（本地 chatDao 查 isGroup）传参；设置页新增「群聊铃声」选择器（独立 RingtoneManager picker，空 = 回退单聊）；新增 4 个中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2602。按用户要求未跑编译/测试。

## 0.73 2026-08-06 新目标第 9 轮：会话左滑操作（#354）

| # | 项 | 说明 |
|---|----|------|
| 354 | **会话列表左滑操作** | SwipeableChatItem 组件早已存在但从未接入会话列表 → ChatListItem 包进 SwipeableChatItem：右滑露出置顶/静音/归档三操作 + 全滑删除（onPin/onMute/onArchive/onDelete → viewModel.togglePinned/toggleNotificationsMuted/toggleArchived/deleteChat）；animateItem 移到 Swipeable 外层 |

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.74 2026-08-06 新目标第 10 轮：注册邮箱域名黑名单（#355）

| # | 项 | 说明 |
|---|----|------|
| 355 | **反垃圾注册：邮箱域名黑名单** | ServerConfig 新增 EMAIL_DOMAIN_BLOCKLIST（逗号分隔一次性/垃圾邮箱域名，空 = 不拦截，自动去 @ 前缀转小写）；register 路由在查重后校验域名，命中返回 403 `EMAIL_DOMAIN_BLOCKED`；.env.docker.example 加说明 |

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.75 2026-08-06 新目标第 11 轮：TOTP 恢复码 + App 两步验证 UI（#356）

| # | 项 | 说明 |
|---|----|------|
| 356 | **TOTP 备份恢复码 + App 2FA 设置** | Server 端：users 表加 totp_backup_codes（BCrypt 哈希，逗号分隔）；confirmTotpSetup 成功后生成 8 个恢复码（SecureRandom 8 位数字，明文仅一次返回）；loginWithFactors TOTP 失败时尝试恢复码（consumeBackupCode 单次消费删码）；TotpStatusResponse 加 backupCodes。App 端（此前 setupTotp/confirmTotp 有 API 无调用 → 2FA 设置 UI 完全缺失）：ApiService.confirmTotp 改为返回恢复码；AccountSecurityScreen 加「两步验证」入口 + TotpSetupDialog（setup → 显示密钥/可复制 → 输 6 位码 → 确认 → 显示 8 个恢复码提示保存）；新增 12 个中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2614。按用户要求未跑编译/测试。

## 0.76 2026-08-06 新目标第 12 轮：恢复码登录截断 bug（#357）

| # | 项 | 说明 |
|---|----|------|
| 357 | **恢复码无法登录（CRITICAL 级体验缺陷）** | 0.75 新增 8 位恢复码，但 App 登录 TOTP 输入框 `take(6)` 把恢复码截断 → 启用 2FA 后丢失验证器用户无法用恢复码登录。修复：onTotpCodeChange 改 take(8)（兼容 6 位 TOTP + 8 位恢复码，服务端分别验证）；登录占位文案改「验证码或恢复码」 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2614。按用户要求未跑编译/测试。

## 0.77 2026-08-06 新目标第 13 轮：2FA 状态/重新生成/禁用闭环（#358）

| # | 项 | 说明 |
|---|----|------|
| 358 | **2FA 管理闭环** | Server 新增 POST /api/auth/totp/recover-codes（验证当前 TOTP 后重生成恢复码，旧码作废）；UserRepository.regenerateBackupCodes；disableTotp 同时清空恢复码。App：ApiService 新增 totpStatus/regenerateTotpCodes；TotpSetupDialog 进入先查状态——已启用则显示「已启用」模式（输码 → 重新生成恢复码 / 禁用两步验证）；新增 4 个中英字符串 |

TOTP 功能至此闭环：启用（恢复码生成显示）→ 登录（验证码/恢复码）→ 状态查看 → 重生成恢复码 → 禁用。

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2618。按用户要求未跑编译/测试。

## 0.78 2026-08-06 新目标第 14 轮：聊天记录导出入口（#359）

| # | 项 | 说明 |
|---|----|------|
| 359 | **聊天记录导出接入** | ChatExport（buildText/write/share，纯本地明文渲染，上限 2000 条）早已实现但无任何 UI 入口（半成品）→ 聊天页顶栏 ⋯ 菜单加「导出聊天记录」：渲染会话名 + 成员名解析（participantNamesById）+ 时间戳，写入 cacheDir 后系统分享面板；新增 3 个中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2619。按用户要求未跑编译/测试。

## 0.79 2026-08-06 新目标第 15 轮：我的举报页面（#360）

| # | 项 | 说明 |
|---|----|------|
| 360 | **「我的举报」入口** | getMyReports API 与服务端路由早已就绪但 App 无任何 UI（孤儿功能）→ 设置主菜单加「我的举报」项（SettingsScreen + Routes.SETTINGS_MY_REPORTS + NavGraph 路由），新增 MyReportsScreen（加载 getMyReports → 列表：目标类型/原因/状态/时间/描述/处理结果；加载态 + 错误重试 + 空态）；新增 10 个中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2628。按用户要求未跑编译/测试。

## 0.80 2026-08-06 新目标第 16 轮：链接预览 flag 门控生效（#361）

| # | 项 | 说明 |
|---|----|------|
| 361 | **LINK_PREVIEW 死开关接线** | RuntimeFlags.LINK_PREVIEW 此前只写入（ChatListScreen 同步服务端）但从未被读取（改它没效果）→ LinkPreviewRepository.fetch 开头检查 flag（服务端可整体关闭预览），默认 true 不破坏现有 |

孤儿审计其余结论：AdminDispositionPolicy.validateMute 与 message-restrict 语义重复（跳过）；CONTACT_CARD/IN_APP_SOUNDS/PQXDH_PREVIEW 三个死 flag 记录待处理（实现或删除）。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.81 2026-08-06 新目标第 17 轮：应用内发送提示音（#362）

| # | 项 | 说明 |
|---|----|------|
| 362 | **IN_APP_SOUNDS flag 生效** | 新增 InAppSoundPlayer（ToneGenerator 免音频资源，playSendTone/playReceiveTone），受 RuntimeFlags.IN_APP_SOUNDS 门控（服务端可整体开关，此前 flag 只写入从未生效）；MaodouchatApp.emitMessageSent（所有发送路径集中入口：普通/转发/定时）播放发送成功音 |

孤儿审计剩余记录：CONTACT_CARD、PQXDH_PREVIEW 两个死 flag（无功能实现，未接 UI 不误导，仅记录）；AdminDispositionPolicy mute 与 message-restrict 重复。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.82 2026-08-06 新目标第 18 轮：视频保存到相册（#363）

| # | 项 | 说明 |
|---|----|------|
| 363 | **视频查看器保存** | 图片查看器有「保存」但视频查看器无（MediaExport.saveToGallery 支持视频）→ 视频全屏查看器加底栏「保存」按钮：复用 SECRET_MEDIA_EXPORT_BLOCK 密聊门控 + MediaViewerPolicy.canExportLocal + saveToGallery（MediaStore 写入相册），成功/失败 Toast；新增 media_save/saved/save_failed 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2631。按用户要求未跑编译/测试。

## 0.83 2026-08-06 新目标第 19 轮：清空聊天记录（#364）

| # | 项 | 说明 |
|---|----|------|
| 364 | **清空本机聊天记录** | 微信/QQ 标配此前缺失 → 顶栏 ⋯ 菜单加「清空聊天记录」（红色）→ 确认对话框 → ChatDetailViewModel.clearLocalChatHistory（deleteMessagesByChatId + 清空 messages/未读摘要，纯本机不影响对方/服务端）；新增 3 个中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2633。按用户要求未跑编译/测试。

## 0.84 2026-08-06 新目标第 20 轮：Server 死代码清理（#365）

| # | 项 | 说明 |
|---|----|------|
| 365 | **AuthTokenRepository 5 个孤儿方法删除** | 孤儿审计确认无调用：peekRefreshUserId（token 轮换重构前残留）、consumeForRotation（rotateIfEligible 薄包装）、revokeAndGetUserId（revokeAndGetSession 薄包装）、revokeByHashPrefix（revokeByHashPrefixWithSessions 薄包装）、revokeAllForUser（内部 InCurrentTransaction 版本保留供 rotateAccessTokenVersion 使用）→ 全部删除，保留被路由实际调用的方法 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；grep 确认被删方法无引用。按用户要求未跑编译/测试。

## 0.85 2026-08-06 新目标第 21 轮：SignalKeyRepository 死代码清理（#366）

| # | 项 | 说明 |
|---|----|------|
| 366 | **SignalKeyRepository 11 个孤儿方法删除** | upload 系列（uploadIdentityKey/uploadRegistrationId/uploadDeviceId/uploadSignedPreKey/uploadSignedPreKeySignature/uploadPreKeys，已被 uploadKeyPackage 取代）+ get 系列（getRegistrationId/getSignedPreKeySignature/getSignedPreKey/consumePreKey，getBundle 用 private 版本）+ @Deprecated deleteDevice → 全部删除；private 辅助（getSignedPreKeyInternal/consumePreKeyInternal/upsertSingleKeyInTx/uploadPreKeyInTx）保留供新 API 使用 |

孤儿审计清理完成 2/3 repository（AuthTokenRepository 5 + SignalKeyRepository 11）；剩余记录：AiStreamingService 实例方法（类未实例化）、CacheService 便捷 API（仅生命周期在用）、ChatRepository 若干。

**验证方式**：brace_strip2.py 括号平衡 delta=0；grep 确认被删方法无引用、private 辅助保留。按用户要求未跑编译/测试。

## 0.86 2026-08-06 新目标第 22 轮：更新前自动备份（#367）

| # | 项 | 说明 |
|---|----|------|
| 367 | **update.sh 更新前自动备份** | 一键更新流程升级：git pull + 重新部署前自动执行 backup-production.sh（DB 一致性备份 + uploads + Caddy 数据 + SHA256 校验），备份失败则中止更新（set -e）——更新失败/回滚时数据有保障 |

**验证方式**：bash -n update.sh 语法通过。按用户要求未跑编译/测试。

## 0.87 2026-08-06 新目标第 23 轮：AiStreamingService 死代码清理（#368）

| # | 项 | 说明 |
|---|----|------|
| 368 | **AiStreamingService 重构为工具对象** | 类从未被实例化（真实 AI 调用走 AiGatewayService），实例方法（streamChatCompletion/chatCompletion/指标/close）+ 实例成员全部删除 → 改为 object（仅保留 companion 工具：estimateTokens/CHUNK_TIMEOUT_MS/RETRYABLE_STATUSES 等）+ 文件内 data classes；estimateTokens 调用方（Routing/AiEnhanceRouting 5 处）兼容 |

孤儿审计清理完成 3/3 主要项（AuthTokenRepository 5 + SignalKeyRepository 11 + AiStreamingService 实例部分）。剩余记录：CacheService 便捷 API（仅生命周期在用，未接入热点）、ChatRepository 若干、ApiService 3 个（batchMarkRead/rewriteMessage/advancedSearch）。

**验证方式**：brace_strip2.py 括号平衡 delta=0；grep 确认 estimateTokens 调用兼容。按用户要求未跑编译/测试。

## 0.88 2026-08-06 新目标第 24 轮：ChatRepository 死代码清理（#369）

| # | 项 | 说明 |
|---|----|------|
| 369 | **ChatRepository 3 个孤儿方法删除** | isChannelSubscriber（isChannelOwner 保留，被路由用）、getDisappearingMessageSeconds（Admin 直读字段）、getChatByInviteToken（入群走 consumeGroupInvite，纯查询副本）→ 全部删除 |

孤儿审计剩余记录：CacheService 便捷 API、EncryptedAttachmentRepository 2、FriendRepository.areFriends、PollRepository.newPollId、SignalingRepository.clearForCallExcluding、StarMessageRepository.isStarred、UserRepository.updatePrivacy、UserTagRepository.riskAssignments、ApiService 3 个（batchMarkRead/rewriteMessage/advancedSearch）。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.89 2026-08-06 新目标第 25 轮：批量已读接入（#370）

| # | 项 | 说明 |
|---|----|------|
| 370 | **「全部已读」改用批量 API** | batchMarkRead（ApiService 已定义无调用）接入 markAllUnreadChatsRead：本地缓存置零 + 通知清理逐会话保留，服务端已读从「每未读会话一次 markAllAsRead」（N 次请求）改为一次 /api/messages/batch-read 批量请求；失败静默下次 getChats 收敛 |

孤儿审计的 batchMarkRead 项解决。剩余记录：ApiService.rewriteMessage/advancedSearch（流式已覆盖）、CacheService 便捷 API、各 repository 少量孤儿。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.90 2026-08-06 新目标第 26 轮：Friend/Star 孤儿清理（#371）

| # | 项 | 说明 |
|---|----|------|
| 371 | **FriendRepository.areFriends + StarMessageRepository.isStarred 删除** | areFriends 为 areFriendsInTransaction（保留）的薄包装无调用；isStarred 全库无调用 → 均删除 |

孤儿审计累计清理 38 方法。剩余记录：CacheService 便捷 API、EncryptedAttachmentRepository 2、PollRepository.newPollId、SignalingRepository.clearForCallExcluding、UserRepository.updatePrivacy、UserTagRepository.riskAssignments、ApiService.rewriteMessage/advancedSearch。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.91 2026-08-06 新目标第 27 轮：附件仓库孤儿清理（#372）

| # | 项 | 说明 |
|---|----|------|
| 372 | **EncryptedAttachmentRepository 2 个孤儿删除** | activeBytesForUploader（activeBytesForUploaderInTransaction 的薄包装，配额校验实际用 InTransaction 版本，外部无调用）、deleteForChat（删除聊天走 ChatRepository.deleteChatRows，全库无调用）→ 均删除 |

孤儿审计累计清理 40 方法。剩余记录：CacheService 便捷 API（未接入热点）、PollRepository.newPollId、SignalingRepository.clearForCallExcluding、UserRepository.updatePrivacy、ApiService.rewriteMessage/advancedSearch、UserTagRepository.riskAssignments（风控预留，保留）。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.92 2026-08-06 新目标第 28 轮：Poll/Signaling/User 孤儿清理（#373）

| # | 项 | 说明 |
|---|----|------|
| 373 | **3 个孤儿删除** | PollRepository.newPollId（PollRouting 自建 ID）、SignalingRepository.clearForCallExcluding（薄包装，InTx 版本保留供内部）、UserRepository.updatePrivacy（薄包装，路由用 updatePrivacyWithTransitions）→ 均删除 |

孤儿审计累计清理 43 方法。剩余记录：CacheService 便捷 API（未接入热点）、ApiService.rewriteMessage/advancedSearch（流式版本已覆盖）、UserTagRepository.riskAssignments（风控预留，保留）。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.93 2026-08-06 新目标第 29 轮：用户资料缓存接入（#374）

| # | 项 | 说明 |
|---|----|------|
| 374 | **CacheService 用户资料缓存接入** | 孤儿审计核心发现（缓存框架只用了生命周期，读写从未接入）→ /u/{username} 公开主页与 /api/public/profile 先读 getUserProfile（LRU+TTL），miss 才查 DB 并回填；PUT /api/users/profile 与 PUT /api/users/me/username 成功后失效对应 key（清 username 由 TTL 兜底）——公开资料高频访问减少 DB 查询 |

孤儿审计 CacheService 项部分解决（群元数据/公开状态缓存后续可同样接入）。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.94 2026-08-06 新目标第 30 轮：公开状态缓存接入（#375）

| # | 项 | 说明 |
|---|----|------|
| 375 | **/api/public/status 缓存** | App 启动/登录高频拉取（~100 个配置字段，每次 DB 读 RuntimeConfigService）→ getPublicStatus 先读 LRU+TTL 缓存，miss 构建 body 并回填；serverTime 由 TTL 兜底延迟（客户端定期轮询校正） |

CacheService 三个缓存接入 2/3（用户资料 + 公开状态）；群元数据缓存动态性强（成员/最后消息频繁变化），记录为不做（避免缓存失效复杂度）。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.95 2026-08-06 新目标第 31 轮：黑名单管理页（#376）

| # | 项 | 说明 |
|---|----|------|
| 376 | **黑名单管理页** | 拉黑后无法在设置查看/解除（真实功能缺口）→ 设置「安全」组加「黑名单」入口（Routes.SETTINGS_BLOCKED_USERS + NavGraph）；新增 BlockedUsersScreen（加载 /api/users/blocks/details → 头像+名字列表 + 解除拉黑按钮 unblockUser 并本地移除；加载态/错误重试/空态）；新增 5 个中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2635。按用户要求未跑编译/测试。

## 0.96 2026-08-06 新目标第 32 轮：ApiService 孤儿收尾（#377）

| # | 项 | 说明 |
|---|----|------|
| 377 | **ApiService 2 个孤儿删除** | advancedSearch（App 搜索走本地 MessageSearchRepository/语义搜索，服务端 /api/search 无客户端调用）、rewriteMessage（App 只用流式 streamRewriteMessage，非流式端点无客户端调用）→ 均删除（data class 保留，AiRewriteRequest 等被流式版本共用） |

孤儿审计至此全部收尾：清理 45 方法 + batchMarkRead 接入 + CacheService 两缓存接入；保留项记录（riskAssignments 风控预留、群元数据缓存不做、PQXDH/CONTACT_CARD flag）。

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.97 2026-08-06 新目标第 33 轮：消息分享到系统（#378）

| # | 项 | 说明 |
|---|----|------|
| 378 | **消息系统分享** | 长按消息菜单此前只有复制（聊天记录导出走导出菜单）→ 复制纯文本后新增「分享」项：ACTION_SEND text/plain 分享 parsedContent 到系统分享面板（E2EE 下用户主动外发明文可接受）；新增 chat_share 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2636。按用户要求未跑编译/测试。

## 0.98 2026-08-06 新目标（重建）第 1 轮：公开主页 IP 限流（#379）

目标重建（此前 auto-continue 用尽需手动「继续」；重建后计数重置）。继续从 0.97 后迭代。

| # | 项 | 说明 |
|---|----|------|
| 379 | **公开主页 IP 限流** | /api/public/profile 与 /u/{username} 匿名可被枚举（孤儿审计 L1）→ 新增 publicProfileRateLimiter，两端点按 IP 60/min 限流（BoundedRateLimiter），超限 429 |

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 0.99 2026-08-06 新目标（重建）第 2 轮：群全员静音（#380）

| # | 项 | 说明 |
|---|----|------|
| 380 | **群聊全员静音** | Server 新增 POST /api/chats/{chatId}/mute-all（调 muteGroupMembersAsAdmin 一次事务按角色过滤批量静音，mutedUntil=0 解除；复用 MAX_MUTE_DURATION_MS 校验）；App：ApiService.muteAllMembers + GroupDetailViewModel.muteAllMembers + 成员区搜索框旁「全员静音」按钮（canManageGroup 显示）+ 确认对话框（默认 24 小时）；新增 5 个中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2641。按用户要求未跑编译/测试。

## 1.00 2026-08-06 新目标（重建）第 3 轮：动态评论删除（#381）

| # | 项 | 说明 |
|---|----|------|
| 381 | **动态评论删除** | Server 删除评论端点早已存在但 App 无入口（孤儿）→ ApiService.deleteComment + ExploreViewModel.deleteComment（成功后本地移除）+ CommentsDialog 评论行加删除按钮（仅自己评论显示，currentUserId 判断）；新增 explore_delete_comment 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2642。按用户要求未跑编译/测试。

## 1.01 2026-08-06 新目标（重建）第 4 轮：动态分享到系统（#382）

| # | 项 | 说明 |
|---|----|------|
| 382 | **动态分享到系统** | 动态卡片此前无分享（只有点赞/评论/编辑/删除）→ PostCard 加 onShare（操作行 Share 图标）+ 操作行分享按钮：ACTION_SEND 分享动态文字（图片动态附加 [图片] 标记）；新增 Share import |

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 1.02 2026-08-06 新目标（重建）第 5 轮：会话临时静音至（#383）

| # | 项 | 说明 |
|---|----|------|
| 383 | **会话临时静音至** | TG/微信「静音 1 小时/8 小时/24 小时」：ChatQuietHoursStore 新增 silentUntil/setSilentUntil（与免打扰时段共存于同一条目）；聊天页顶栏 ⋯ 菜单加「临时静音」→ 时长选择对话框（1/8/24 小时，本地存储）；会话列表显示「静音至 HH:mm」（优先于时段徽标）；BacklogSync 通知抑制加 silentUntil 检查；新增 7 个中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2649。按用户要求未跑编译/测试。

## 1.03 2026-08-06 新目标（重建）第 6 轮：未读消息分隔线（#384）

| # | 项 | 说明 |
|---|----|------|
| 384 | **未读消息分隔线** | 微信式「以下为未读消息」：ChatItem 新增 UnreadSeparator（buildChatItems 插在未读起点消息前，未读起点不在窗口时兜底放最旧消息前）；UiState 新增 unreadSeparatorId（初次加载用 unreadSummaryWindow.totalCount 取倒数第 N 条）；ChatDetailScreen 渲染红色「以下为未读消息」分隔线（两侧短横线）；新增 chat_unread_divider 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2650。按用户要求未跑编译/测试。

## 1.04 2026-08-06 新目标（重建）第 7 轮：语言设置入口（#385）

| # | 项 | 说明 |
|---|----|------|
| 385 | **语言选择设置** | AppLocaleManager 已有 mode（系统/中文/English）与云同步但设置页无入口（用户只能跟随系统）→ 通用设置加「语言」ActionRow（副标题显示当前）+ 选择对话框（跟随系统/简体中文/English），选后 AppLocaleManager.setMode（TIRAMISU LocaleManager / 低版本 recreate）；新增 4 个中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2651。按用户要求未跑编译/测试。

## 1.05 2026-08-06 新目标（重建）第 8 轮：语音连续播放（#386）

| # | 项 | 说明 |
|---|----|------|
| 386 | **语音消息连续播放** | TG/微信：一条语音播放完自动播下一条同会话语音 → VoicePlayer 新增 @Volatile lastCompletedId（仅在自然播放完成时记录，手动停止/切换不更新）；ChatDetailScreen 主主体 LaunchedEffect(lastCompletedId) 检测完成 → 找时间上更新的下一条 VOICE 消息自动播放 |

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 1.06 2026-08-06 新目标（重建）第 9 轮：动态举报（#387）

| # | 项 | 说明 |
|---|----|------|
| 387 | **举报动态** | 动态卡片此前只有编辑/删除（自己的），他人动态无法举报 → PostCard 加 onReport + 非自己动态显示 Flag 举报按钮；ExploreViewModel.reportPost（createReport targetType=POST + 内容描述，成功/失败 infoMessage）；新增 3 个中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2654。按用户要求未跑编译/测试。

## 1.07 2026-08-06 新目标（重建）第 10 轮：定时消息重复（#388）

| # | 项 | 说明 |
|---|----|------|
| 388 | **定时消息重复发送** | 定时发送此前仅一次性 → ScheduledMessage 新增 repeatIntervalMs（0=一次性）；Store.add/序列化加字段；ScheduledMessageWorker 发送前若配置重复则重新入队下一次（净增 1 条）+ 重新调度；ScheduleSendDialog 加「重复发送」区（每日/每周）；ViewModel.scheduleMessageRepeat；新增 3 个中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2657。按用户要求未跑编译/测试。

## 1.08 2026-08-06 新目标（重建）第 11 轮：群成员点击查看资料（#389）

| # | 项 | 说明 |
|---|----|------|
| 389 | **群成员查看资料** | 群成员行此前只有管理操作，无查看资料入口 → GroupDetailScreen 加 onOpenProfile 回调（NavGraph 导航 Routes.authorProfile）；MemberRow 加 onOpenProfile 参数，非本人成员的头像/名字可点击跳转资料页（Avatar 支持 modifier） |

**验证方式**：brace_strip2.py 括号平衡 delta=0。按用户要求未跑编译/测试。

## 1.09 2026-08-06 新目标（重建）第 12 轮：多选批量复制（#390）

| # | 项 | 说明 |
|---|----|------|
| 390 | **多选批量复制** | 消息多选工具栏此前只有转发/星标/删除 → 新增「复制」按钮：复制选中 TEXT/MARKDOWN 消息文本（拼接 \n）到剪贴板，无文本则提示；新增 chat_copy_no_text 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0；check-string-parity zh=en=2658。按用户要求未跑编译/测试。

## 1.10 2026-08-07 新目标（重建）第 13 轮：多选批量置顶（#391）

| # | 项 | 说明 |
|---|----|------|
| 391 | **多选批量置顶** | 消息多选工具栏在转发/复制/星标/删除基础上新增「置顶/取消置顶」按钮（含未置顶消息时显示置顶，全部已置顶则显示取消置顶）；`ChatDetailViewModel.togglePinMessages(messageIds, shouldPin)` 逐条调用置顶接口，预检 RuntimeFlag/权限（群仅 owner/admin）/可置顶类型/20 条上限，按成功数统计提示（已置顶 N 条 / 部分失败含成败数）；新增 chat_batch_pin_success / chat_batch_unpin_success / chat_batch_pin_partial / chat_batch_unpin_partial 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ViewModel/Screen/Toolbar）；check-string-parity zh=en=2662。按用户要求未跑编译/测试。

## 1.11 2026-08-07 新目标（重建）第 14 轮：发送名片（#392，消费 CONTACT_CARD 死 flag）

| # | 项 | 说明 |
|---|----|------|
| 392 | **发送名片** | CONTACT_CARD 死 flag（App 端只写不读）正式消费：1) 聊天输入「+」附件菜单新增「名片」按钮（按 RuntimeFlags.CONTACT_CARD 显隐）；2) 联系人选择对话框——复用 forwardTargets（getChats）列单聊会话对端用户（Avatar+姓名+@username），无联系人则空态提示；3) `ChatDetailViewModel.sendContactCard` 门控 flag + 密聊禁用，构造 `👤 姓名\n[contactUser:userId]` 文本复用 sendMessage 完整发送链路（加密/出站/状态）；4) RichTextContent 渲染时正则剥离 `[contactUser:xxx]` 标记行（含前一换行），接收端气泡仅显示 `👤 姓名`；新增 chat_send_contact_card / contact_card_picker_title / contact_card_picker_empty / contact_card_secret_blocked 中英字符串（复用既有 contact_card_disabled） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ViewModel/Screen/MessageBubble）；check-string-parity zh=en=2666。按用户要求未跑编译/测试。

## 1.12 2026-08-07 新目标（重建）第 15 轮：清除 PQXDH_PREVIEW 死 flag（#393）

| # | 项 | 说明 |
|---|----|------|
| 393 | **死 flag 清理** | App 端 `RuntimeFlags.PQXDH_PREVIEW` 只写不读（ChatListScreen 从 /api/public/status 镜像后从未被任何逻辑消费）→ 删除 flag 定义（RuntimeFlags.kt）+ 镜像变量 `pqxdhPreviewOn` + `setEnabled` 调用（ChatListScreen.kt 两处）。服务端保留：bot `sendPqxdhHint`（`SEAL:PQXDH` SYSTEM 消息）仍按 `pqxdh_preview` 开关 gating；App 顶部横幅「PQXDH preview on」仍直接读服务端原始 `pqxdhPreview` 值（未走 RuntimeFlags，不受影响）。至此 CONTACT_CARD/PQXDH_PREVIEW 两个死 flag 全部处置完毕 |

**验证方式**：rg 全 App 源码确认 `RuntimeFlags.PQXDH_PREVIEW`/`pqxdhPreviewOn` 零残留；brace_strip2.py 括号平衡 delta=0（RuntimeFlags/ChatListScreen）。按用户要求未跑编译/测试。

## 1.13 2026-08-07 新目标（重建）第 16 轮：多选工具栏与附件菜单横向滚动（#394）

| # | 项 | 说明 |
|---|----|------|
| 394 | **窄屏布局修复** | 多选工具栏增至 5 个动作（转发/复制/星标/置顶/删除）后 weight(1f) 均分在窄屏会裁切「取消置顶」等长标签 → 改为横向滚动 Row（horizontalScroll + 内容自适应宽度）；附件菜单在新增「名片」后达 11 项 → 同样改横向滚动（spacedBy(16dp)），全部按钮完整可见、可滑动 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatSelectionToolbar/ChatDetailScreen）。按用户要求未跑编译/测试。

## 1.14 2026-08-07 新目标（重建）第 17 轮：修复定时消息重复从不生效 bug（#395）

| # | 项 | 说明 |
|---|----|------|
| 395 | **重复定时 bug** | 1.07 引入的重复发送在**成功发送路径**从未重排：`ScheduledMessageWorker` 第 93 行成功路径直接 `removeForUser` 删除到条目，`ScheduledMessageStore.add` 只被失败路径 `abandonScheduledMessage` 调用 → 重复定时消息首轮发送后即终止。修复：成功路径先 `add(sendAt + repeatIntervalMs)` 重排下一次并 `ScheduledMessageScheduler.schedule`，再移除当前条目（覆盖群聊 outbox 与 1:1 直发两条路径） |

**验证方式**：rg 确认 `ScheduledMessageStore.add` 现被成功路径调用；brace_strip2.py 括号平衡 delta=0（ScheduledMessageWorker）。按用户要求未跑编译/测试。

## 1.15 2026-08-07 新目标（重建）第 18 轮：定时消息重复标识（#396）

| # | 项 | 说明 |
|---|----|------|
| 396 | **重复标识展示** | 定时消息列表（输入框上方预览卡 + 底部列表 Sheet）为重复条目显示「每日重复/每周重复」标识（`scheduleRepeatLabelRes` 按 repeatIntervalMs 映射 24h/7d/其它），与 1.14 修复后的重复生效配合，用户可一眼识别会重复发送的条目；新增 schedule_repeat_badge 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2667。按用户要求未跑编译/测试。

## 1.16 2026-08-07 新目标（重建）第 19 轮：修复更新脚本复用旧镜像 bug（#397）

| # | 项 | 说明 |
|---|----|------|
| 397 | **部署更新 bug** | `update.sh` 执行 `deploy.sh --no-build` 复用旧镜像，但 `server` 服务是 `build: context: .`（Dockerfile 构建时 `COPY server/src` 烘焙源码，无卷挂载）→ `git pull` 拉下的服务端代码**从未进入容器**，更新脚本实际只改了 .env 配置。修复：update.sh 改用 `deploy.sh` 默认 `--build`（注释同步说明）；deploy.sh 与 deploy.ps1 的「Update」指引改为 `bash scripts/update.sh` / `git pull && ./scripts/deploy.ps1`（去掉 `-NoBuild`），避免误导。仅「改配置」（bootstrap-admin / MASTER_ADMINS）场景保留 `-NoBuild` |

**验证方式**：`bash -n` 语法检查 deploy.sh/update.sh 通过；PowerShell Parser 解析 deploy.ps1 无错误。按用户要求未跑编译/测试。

## 1.17 2026-08-07 新目标（重建）第 20 轮：名片点击打开资料（#398，补全 1.11）

| # | 项 | 说明 |
|---|----|------|
| 398 | **名片可点击** | 1.11 发送的名片消息补全点击交互：RichTextContent 检测 `[contactUser:userId]` 标记，整条「👤 姓名」渲染为可点击链接（合成 scheme `contactcard://<userId>`）；MessageBubble onLinkClick 拦截该 scheme 转发给新的 `onContactCardClick(userId)` 回调（未接线时 toast 提示）；回调经 MessageBubble → TextBubble → ChatMessageRow → ChatDetailScreen 新参数 `onOpenProfile` 链路上抛，NavGraph 接到 `Routes.authorProfile(userId)` 打开对方资料；新增 chat_contact_card_tap_hint 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（MessageBubble/ChatMessageRow/ChatDetailScreen/NavGraph，TextBubble 21 个位置实参逐一核对）；check-string-parity zh=en=2668。按用户要求未跑编译/测试。

## 1.18 2026-08-07 新目标（重建）第 21 轮：名片标记在会话列表预览剥离（#399）

| # | 项 | 说明 |
|---|----|------|
| 399 | **预览不显示裸标记** | 名片消息 `👤 姓名\n[contactUser:id]` 在会话列表最后消息预览会显示裸 `[contactUser:...]`。新增 `ChatMarkdown.stripContactCardMarker`（统一实现，剥离标记行含前一换行），ChatListScreen 最后消息预览 TEXT 分支调用；MessageBubble.RichTextContent 改用同一实现（删除私有重复正则），仅保留 `CONTACT_CARD_USER_RE` 用于点击提取用户 id |

**验证方式**：brace_strip2.py 括号平衡 delta=0（MessageBubble/ChatListScreen/MarkdownMessage）。按用户要求未跑编译/测试。

## 1.19 2026-08-07 新目标（重建）第 22 轮：全员静音支持一键解除（#400）

| # | 项 | 说明 |
|---|----|------|
| 400 | **解除全员静音** | 0.99 全员静音只能设 24h，管理员误设后需逐个成员解除。全员静音确认对话框 confirmButton 改双按钮 Row：「解除全员静音」调 `muteAllMembers(0L)`（服务端 mutedUntil=0 语义解除，成功提示复用 group_detail_mute_all_cleared）+「全员静音」仍设 24h；新增 group_detail_mute_all_clear 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（GroupDetailScreen）；check-string-parity zh=en=2669。按用户要求未跑编译/测试。

## 1.20 2026-08-07 新目标（重建）第 23 轮：批量置顶入口按权限显隐（#401）

| # | 项 | 说明 |
|---|----|------|
| 401 | **批量置顶权限一致化** | 1.10 批量置顶按钮此前恒显示（点按才提示"仅群主或管理员"），与单条置顶（canPin 控制显隐）不一致。现按 `MessagePinPolicy.canPin` 对选中消息任一可置顶才显示入口（onTogglePin 为 null 时按钮隐藏），群聊非群主/管理员不再出现该按钮 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）。按用户要求未跑编译/测试。

## 1.21 2026-08-07 新目标（重建）第 24 轮：定时消息重复次数上限（#402）

| # | 项 | 说明 |
|---|----|------|
| 402 | **重复次数上限** | 1.07 重复定时只能无限重复。新增 `ScheduledMessage.repeatCount`（0=不限）+ `occurrencesSent`（已发次数）：Store 序列化/反序列化兼容旧数据（缺失默认 0=不限）；Worker 成功/失败两路径重排条件改为 `repeatCount==0 || occurrencesSent+1 < repeatCount`（严格 N 次发送后停止）；`scheduleMessageAt/scheduleMessageRepeat` 透传 repeatCount；ScheduleSendDialog 新增「重复次数」选择行（3/7/30/不限，选中高亮，interval 按钮应用所选次数）；列表重复标识显示「每日重复 · 3 次」；新增 schedule_repeat_count_title / schedule_repeat_count_unlimited / schedule_repeat_count 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（Store/Worker/ViewModel/ChatDetailScreen）；check-string-parity zh=en=2672；全部调用点签名核对。按用户要求未跑编译/测试。

## 1.22 2026-08-07 新目标（重建）第 25 轮：会话列表群公告预览（#403）

| # | 项 | 说明 |
|---|----|------|
| 403 | **群公告预览** | 微信式：群聊会话项在最后消息下方新增「📢 公告…」提示行（Chat.groupAnnouncement 非空即显示，单行省略），用户无需进群即可看到管理员设置的公告；新增 chat_list_group_announcement_prefix 中英字符串（"📢 "） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen）；check-string-parity zh=en=2673。按用户要求未跑编译/测试。

## 1.23 2026-08-07 新目标（重建）第 26 轮：名片裸标记不进通知正文（#404）

| # | 项 | 说明 |
|---|----|------|
| 404 | **通知正文剥离标记** | `AppNotifier.showMessage` 的 `displayPreview` else 分支（非脱敏场景）套用 `ChatMarkdown.stripContactCardMarker`，名片消息的系统通知/通知中心不再显示裸 `[contactUser:id]`（与 1.18 会话列表预览一致，集中一点防御全部通知调用方） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（AppNotifier）。按用户要求未跑编译/测试。

## 1.24 2026-08-07 新目标（重建）第 27 轮：批量置顶跳过不可置顶类型（#405）

| # | 项 | 说明 |
|---|----|------|
| 405 | **批量置顶类型过滤** | 1.10 批量置顶此前只要选中含任一不可置顶类型（系统/撤回/NUDGE 等）就整体拒绝（chat_pin_forbidden），与 1.20 入口显隐（任一可置顶即显示）不一致。改为过滤：`pinnableTargets` 仅处理 `MessagePinPolicy.canPin` 通过的消息（系统/撤回跳过不置顶），全部不可置顶才拒绝；限数检查与成功/失败统计改用过滤后的 effectiveTargetIds |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailViewModel）。按用户要求未跑编译/测试。

## 1.25 2026-08-07 新目标（重建）第 28 轮：备份保留策略（#406）

| # | 项 | 说明 |
|---|----|------|
| 406 | **备份自动清理** | `backup-production.sh` 备份目录无限累积（每次 update.sh 更新前都生成一份）→ 新增保留策略：`BACKUP_KEEP`（默认 14）份，成功后按时间戳（ISO 排序=时间序）由旧到新清理超出的 `maodouchat-*` 目录；`.partial-*` 临时目录不受影响；可设 `BACKUP_KEEP=0` 关闭清理 |

**验证方式**：`bash -n` 语法检查通过。按用户要求未跑编译/测试。

## 1.26 2026-08-07 新目标（重建）第 29 轮：搜索「提到我」过滤（#407）

| # | 项 | 说明 |
|---|----|------|
| 407 | **提到我搜索** | 会话内搜索新增 `MENTIONS` 范围（TG/微信「@ 我」）：`ChatSearchScope.MENTIONS` 过滤 `meta.mentions` 含当前用户或 `MentionPolicy.EVERYONE_ID`（@所有人）的消息；空白关键词也展示全部提及（与星标一致）；`searchChatDocuments/semanticSearchCandidates` 增加 `currentUserId` 参数（默认空串向后兼容，调用点传入 state.currentUserId）；新增 chat_search_scope_mentions 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatSearchModel/ChatSearchBar/ChatDetailScreen）；check-string-parity zh=en=2674。按用户要求未跑编译/测试。

## 1.27 2026-08-07 新目标（重建）第 30 轮：名片联系人选择器搜索（#408）

| # | 项 | 说明 |
|---|----|------|
| 408 | **名片选择器搜索** | 1.11 名片联系人选择对话框新增搜索框（按 displayName / username 过滤，实时），无匹配显示「没有匹配的联系人」；新增 contact_card_picker_search / contact_card_picker_no_match 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2676。按用户要求未跑编译/测试。

## 1.28 2026-08-07 新目标（重建）第 31 轮：临时静音覆盖全部通知路径（#409）

| # | 项 | 说明 |
|---|----|------|
| 409 | **silentUntil 全路径抑制** | 1.02 临时静音至（silentUntil）只在 BacklogSync 兜底同步路径抑制通知，**FCM 推送**（进程被杀/后台）与 **WS 实时**路径均未检查 → 静音窗口内仍会弹通知。修复：`MaodouFirebaseMessagingService`（NEW_MESSAGE 分支，紧邻免打扰时段检查）与 `ChatListViewModel`（WS 实时通知判定，与 notificationsMuted/DND/免打扰时段并列）均新增 `silentUntil > now` 抑制，三条通知路径（FCM/WS/BacklogSync）行为一致 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（MaodouFirebaseMessagingService/ChatListViewModel）。按用户要求未跑编译/测试。

## 1.29 2026-08-07 新目标（重建）第 32 轮：通话记录页（#410）

| # | 项 | 说明 |
|---|----|------|
| 410 | **通话记录** | `CallLogStore` 一直写本地历史但无任何 UI 展示（缺口）。新增 `CallHistoryScreen`（新文件）：按时间倒序列出全量通话（呼入/呼出/未接标识 + 视频图标 + 相对时间 + 已接通话时长），点击条目回拨（语音），顶栏「清空记录」；路由 `CALL_HISTORY` + 聊天页 ⋯ 菜单「通话记录」入口；复用既有 call_log_title / missed_calls_clear_all / missed_calls_badge / missed_calls_callback / time_* 字符串，新增 call_history_empty / call_history_incoming / call_history_outgoing 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（CallHistoryScreen/NavGraph/ChatDetailScreen）；check-string-parity zh=en=2679。按用户要求未跑编译/测试。

## 1.30 2026-08-07 新目标（重建）第 33 轮：会话列表「[有人@我]」预览前缀（#411）

| # | 项 | 说明 |
|---|----|------|
| 411 | **@ 我预览前缀** | 微信式：群聊消息 @ 我 / @所有人 时，会话列表最后消息预览加「[有人@我] 」前缀（复用 `MentionPolicy.shouldHighlightMention` 与通知强调一致，解密后客户端判定，实时 WS 收消息路径）；新增 chat_list_mention_prefix 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListViewModel）；check-string-parity zh=en=2680。按用户要求未跑编译/测试。

## 1.31 2026-08-07 新目标（重建）第 34 轮：会话列表「临时静音至」快捷项（#412）

| # | 项 | 说明 |
|---|----|------|
| 412 | **列表快速静音** | 会话列表长按菜单新增「临时静音」→ 对话框选 1/8/24 小时（复用 chat_silent_until_* 字符串）→ 本地 `ChatQuietHoursStore.setSilentUntil` 写入；配合 1.28 三条通知路径抑制与列表「静音至 HH:mm」徽标，用户无需进聊天即可临时静音 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen）；check-string-parity zh=en=2680（无新增字符串，全复用）。按用户要求未跑编译/测试。

## 1.32 2026-08-07 新目标（重建）第 35 轮：稍后提醒列表「清除全部」（#413）

| # | 项 | 说明 |
|---|----|------|
| 413 | **清除会话全部提醒** | 稍后提醒列表此前只能逐条删除。新增 `MessageReminderStore.clearForChat` + `ChatDetailViewModel.clearRemindersForChat`（取消该会话全部 Worker 作业 + 清空存储），提醒列表底部「清除本会话全部提醒」按钮；新增 message_reminder_clear_all 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen/ChatDetailViewModel/MessageReminderStore）；check-string-parity zh=en=2681。按用户要求未跑编译/测试。

## 1.33 2026-08-07 新目标（重建）第 36 轮：生产拓扑验证补公开状态接口检查（#414）

| # | 项 | 说明 |
|---|----|------|
| 414 | **公开状态回归检查** | `verify-production-topology.sh --live` 新增 `/api/public/status` 探测：断言返回 contactCardEnabled / sealedSenderEnabled / secretChatEnabled / screenSecureRuntimeEnabled（App 依赖的运行时开关，含 1.11 消费的 contactCardEnabled），防止服务端删改开关导致 App 功能静默失效 |

**验证方式**：`bash -n` 语法检查通过；确认四个键均存在于服务端公开状态路由（Routing.kt 478/531/536/537）。按用户要求未跑编译/测试。

## 1.34 2026-08-07 新目标（重建）第 37 轮：转发支持多选目标（#415）

| # | 项 | 说明 |
|---|----|------|
| 415 | **多选转发** | 转发对话框此前点一个会话立即转发，只能单目标。改为：点击会话勾选/取消（选中的显示 ✓ 标识），可勾选多个（含密聊白名单限制保持）；底部「确认转发」一次转发到所选全部会话（附带留言逐个发送）；「刷新」保留为次要按钮；新增 chat_forward_confirm / chat_forward_to_selected 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2683。按用户要求未跑编译/测试。

## 1.35 2026-08-07 新目标（重建）第 38 轮：清除缓存补 Coil 图片磁盘缓存（#416）

| # | 项 | 说明 |
|---|----|------|
| 416 | **缓存清理补全** | 设置页「清除缓存」此前只清媒体文件/附件/链接预览，漏掉 Coil 图片磁盘缓存（通常是最大的缓存）。`clearCache` 的 IO 块补 `coil.Coil.imageLoader(context).diskCache?.clear()`（内存缓存由低内存处理已覆盖） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（SettingsSubViewModels）。按用户要求未跑编译/测试。

## 1.36 2026-08-07 新目标（重建）第 39 轮：BACKUP_KEEP 文档化 + .env 读取（#417）

| # | 项 | 说明 |
|---|----|------|
| 417 | **备份保留配置完善** | 1.25 引入的 `BACKUP_KEEP` 此前只认 shell 环境变量且无文档。补：`.env.docker.example` 新增 `# ─── Ops ───` 段注释文档（默认 14，0 关闭）；`backup-production.sh` 在环境变量未设时从 `.env`（ENV_FILE）读取 `BACKUP_KEEP`（去引号），使配置随部署环境持久化 |

**验证方式**：`bash -n` 语法检查通过。按用户要求未跑编译/测试。

## 1.37 2026-08-07 新目标（重建）第 40 轮：@所有人 权限门控（#418）

| # | 项 | 说明 |
|---|----|------|
| 418 | **@所有人 防滥用** | 此前任意群成员可 @所有人（骚扰向量）。新增权限门控：1) 提及候选列表 `includeEveryone` 仅当群主/管理员为 true（`canMentionEveryone = !isGroup || myMemberRole ∈ {OWNER,ADMIN}`）；2) `sendMessage` 提取到 EVERYONE_ID 但非群主/管理员时阻止发送并提示（chat_mention_everyone_restricted）；单聊不受影响 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen/ChatDetailViewModel）；check-string-parity zh=en=2684。按用户要求未跑编译/测试。

## 1.38 2026-08-07 新目标（重建）第 41 轮：群成员列表在线优先排序（#419）

| # | 项 | 说明 |
|---|----|------|
| 419 | **在线成员优先** | 群详情成员列表在原有 群主→管理员→成员 角色排序基础上，同角色内 `thenByDescending { isOnline }` 在线成员优先（复用 GroupMemberUi.isOnline），再按名称排序 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（GroupDetailScreen）。按用户要求未跑编译/测试。

## 1.39 2026-08-07 新目标（重建）第 42 轮：恢复后健康检查（#420）

| # | 项 | 说明 |
|---|----|------|
| 420 | **恢复后等待就绪** | `restore-production.sh` 恢复完成后此前直接提示完成，不验证服务就绪。新增：从 `.env`（ENV_FILE）读 PUBLIC_HOST（缺省 localhost）等待 `/health/ready` 最长 300s，超时打印 server 日志并退出非零（与 deploy.sh 一致），避免恢复失败被误认为成功 |

**验证方式**：`bash -n` 语法检查通过。按用户要求未跑编译/测试。

## 1.40 2026-08-07 新目标（重建）第 43 轮：免打扰时段设置覆盖临时静音 bug（#421）

| # | 项 | 说明 |
|---|----|------|
| 421 | **静音字段共存修复** | `ChatQuietHoursStore.set`（设置免打扰时段）直接 `obj.put(chatId, entry)` 覆盖整条，会**抹掉**同条目已设置的 `silent_until`（1.02 语义「与时段共存」被破坏）。修复：`set` 先读 `existing.optLong("silent_until")`，>0 时写入新 entry 保留 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatQuietHoursStore）。按用户要求未跑编译/测试。

## 1.41 2026-08-07 新目标（重建）第 44 轮：临时静音对话框支持取消（#422）

| # | 项 | 说明 |
|---|----|------|
| 422 | **静音取消入口** | 聊天页「临时静音」对话框此前只能设 1/8/24h，已生效的静音无法提前取消（只能等过期）。现当会话存在生效的 silentUntil 时显示「取消静音」红色按钮（`setSilentUntil(0)` 清除 + 提示）；新增 chat_silent_until_clear / chat_silent_until_cleared 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2686。按用户要求未跑编译/测试。

## 1.42 2026-08-07 新目标（重建）第 45 轮：通话记录清空同时清 Room 未接（#423）

| # | 项 | 说明 |
|---|----|------|
| 423 | **清空一致化** | 1.29 通话记录页「清空记录」只清 `CallLogStore`（prefs），会话列表未接来电合并源里的 Room `missed_calls` 未清 → 清除后另一入口仍显示。修复：`CallHistoryScreen` 清空时用 `rememberCoroutineScope` 异步调 `MissedCallRepository.clearAll()`（Room）+ `CallLogStore.clear`，与会话列表未接来电「清空」行为一致 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（CallHistoryScreen）。按用户要求未跑编译/测试。

## 1.43 2026-08-07 新目标（重建）第 46 轮：定时消息重排可编辑文案（#424）

| # | 项 | 说明 |
|---|----|------|
| 424 | **重排编辑文案** | 定时消息「改期」此前只能改时间，不能改文案（`rescheduleScheduledMessage(..., newText)` 支持但 UI 未暴露）。新增：`ScheduleSendDialog` 增加 `initialText` / `onTextEdited` 参数（onTextEdited 非空时显示文本输入框，长度 ≤ MAX_TEXT_LENGTH=4000）；重排对话框初值取当前待发文案，改期时一并提交编辑后的文案；新增 schedule_edit_hint 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2687。按用户要求未跑编译/测试。

## 1.44 2026-08-07 新目标（重建）第 47 轮：点击消息发送者名称打开资料（#425）

| # | 项 | 说明 |
|---|----|------|
| 425 | **发送者名称可点** | 文本/Markdown 气泡接收方一侧的发送者名称此前纯展示（TG/微信可点进资料）。新增 `onSenderClick` 回调经 MessageBubble → TextBubble（senderName 文本 clickable，传 message.senderId）→ ChatMessageRow → ChatDetailScreen 复用 1.17 的 `onOpenProfile`（→ `Routes.authorProfile`），未接线时 toast 兜底 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（MessageBubble/ChatMessageRow/ChatDetailScreen）。按用户要求未跑编译/测试。

## 1.45 2026-08-07 新目标（重建）第 48 轮：@所有人 门控角色未加载时 fail-open（#426）

| # | 项 | 说明 |
|---|----|------|
| 426 | **门控竞态修复** | 1.37 @所有人 门控在 `myMemberRole` 为 null（刚进群、成员尚未刷新）时会把**管理员**误拦（canMentionEveryone=false）。改为角色 null 时 fail-open（该门控为客户端 UX 防护，非安全边界，服务端本就未强制），避免管理员在加载窗口内被误拦；picker 侧同步 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailViewModel/ChatDetailScreen）。按用户要求未跑编译/测试。

## 1.46 2026-08-07 新目标（重建）第 49 轮：定时消息重排清空编辑框保留原文（#427）

| # | 项 | 说明 |
|---|----|------|
| 427 | **重排空文本兜底** | 1.43 重排编辑框被清空时 `rescheduleScheduledMessage(..., newText="")` 会因 `isValidText` 校验失败报「schedule_failed」。改为 `takeIf { it.isNotBlank() }` 传 null（null=不改文案），清空编辑框即保留原文 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）。按用户要求未跑编译/测试。

## 1.47 2026-08-07 新目标（重建·第 2 次）第 1 轮：群聊回复自动 @ 发送者（#428）

| # | 项 | 说明 |
|---|----|------|
| 428 | **回复自动提及** | 群聊中回复（左滑回复 / 长按菜单回复）他人消息时，若输入框未包含 `@发送者` 则自动前置（复用 `senderDisplayName` + `participantNamesById`，含群昵称），与 `MentionPolicy.extractMentionIds` 识别一致；自己回复自己/单聊不触发。目标因 auto-continues 用尽后删除重建（第 2 次），本轮为重建后第 1 轮 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）。按用户要求未跑编译/测试。

## 1.48 2026-08-07 新目标（重建·第 2 次）第 2 轮：定时消息重复标识显示剩余次数（#429）

| # | 项 | 说明 |
|---|----|------|
| 429 | **剩余次数展示** | 1.15 重复标识只显示配置总次数。改为按 `occurrencesSent` 显示剩余（`repeatCount - occurrencesSent`，下限 0），如「每日重复 · 剩 2 次」，用户可直观看到还差几次停止；新增 schedule_repeat_remaining 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2688。按用户要求未跑编译/测试。

## 1.49 2026-08-07 新目标（重建·第 2 次）第 3 轮：置顶横幅显示置顶者（#430）

| # | 项 | 说明 |
|---|----|------|
| 430 | **置顶者显示** | 置顶消息横幅此前只显示预览。`PinnedMessagesBanner` 新增 `resolvePinnerName`（本地 `participantNamesById` 解析 pinnedBy → 显示名，未知回退 userId），标题下加「由 X 置顶」小字；新增 chat_pinned_by 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2689。按用户要求未跑编译/测试。

## 1.50 2026-08-07 新目标（重建·第 2 次）第 4 轮：批量删除完成提示（#431）

| # | 项 | 说明 |
|---|----|------|
| 431 | **删除结果提示** | 多选批量删除确认后此前无任何反馈。确认按钮执行删除后 toast「已删除 N 条消息」（cappedBatch.size）；新增 chat_batch_delete_done 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2690。按用户要求未跑编译/测试。

## 1.51 2026-08-07 新目标（重建·第 2 次）第 5 轮：点击已读状态图标打开阅读详情（#432）

| # | 项 | 说明 |
|---|----|------|
| 432 | **状态图标可点** | 文本/Markdown 气泡中自己消息的已读状态图标（✓✓/✓）可点击打开阅读详情（复用 `messageForReadReceipts` + `loadReadReceipts` 既有流程）；`onStatusClick` 回调经 MessageBubble → TextBubble（状态图标包 clickable Box）→ ChatMessageRow → ChatDetailScreen 接线 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（MessageBubble/ChatMessageRow/ChatDetailScreen）。按用户要求未跑编译/测试。

## 1.52 2026-08-07 新目标（重建·第 2 次）第 6 轮：评论点赞（#433，全栈）

| # | 项 | 说明 |
|---|----|------|
| 433 | **评论点赞** | 动态评论区新增点赞（可取消，幂等）：服务端新增 `comment_likes` 表（PK(commentId,userId) 防重复，`createMissingTablesAndColumns` 自动建表）+ `PostCommentResponse.likeCount/likedByMe`（toCommentResponse 内联查询，无嵌套事务）+ `likeComment/unlikeComment`（事务内返回新点赞数）+ `POST/DELETE /api/posts/{id}/comments/{cid}/like`（复用 postLikeRateLimiter 30/min）；客户端 `PostCommentDto` 加字段 + ApiService.likeComment/unlikeComment + `ExploreViewModel.toggleCommentLike`（乐观更新+失败回滚）+ 评论区评论行 ❤️ 点赞按钮（赞/取消 + 计数）；新增 explore_comment_like 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（Database/Models/PostRepository/Routing/SocialApiModels/ApiService/ExploreViewModel/ExploreScreen 共 8 文件）；check-string-parity zh=en=2691。按用户要求未跑编译/测试。

## 1.53 2026-08-07 新目标（重建·第 2 次）第 7 轮：置顶横幅置顶者名称可点（#434）

| # | 项 | 说明 |
|---|----|------|
| 434 | **置顶者可点** | 1.49 置顶横幅的「由 X 置顶」名称改为可点击 → 复用 1.17 `onOpenProfile`（→ `Routes.authorProfile`）打开置顶者资料；`PinnedMessagesBanner` 新增 `onPinnerClick` 参数（null 时不可点），未接线时 toast 兜底 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）。按用户要求未跑编译/测试。

## 1.54 2026-08-07 新目标（重建·第 2 次）第 8 轮：底部导航会话未读角标（#435）

| # | 项 | 说明 |
|---|----|------|
| 435 | **导航未读角标** | 底部导航「会话」Tab 此前无未读角标。新增 `UnreadBadgeStore`（MutableStateFlow）汇总全量未读数（ChatListViewModel init 里 `_uiState.map{chats.sumOf{unreadCount}}.distinctUntilChanged().collect` 推送）；`BottomNavBar` 会话 Tab 加 Material `Badge`（>99 显示 99+） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen/ChatListViewModel）。按用户要求未跑编译/测试。

## 1.55 2026-08-07 新目标（重建·第 2 次）第 9 轮：未读角标登出归零（#436）

| # | 项 | 说明 |
|---|----|------|
| 436 | **角标登出清理** | 1.54 `UnreadBadgeStore` 是单例，登出/切号后若未及时被新账号刷新会残留旧账号计数。`SecureSessionManager.purgeLocalSessionLocked` 登出清理时归零 `totalUnread` |

## 1.56 2026-08-07 新目标（重建·第 2 次）第 10 轮：置顶横幅显示置顶时间（#437）

| # | 项 | 说明 |
|---|----|------|
| 437 | **置顶时间** | 1.49「由 X 置顶」改为「由 X 置顶 · 相对时间」（`DateUtils.getRelativeTimeSpanString` + pinnedAt）；字符串 `chat_pinned_by` 更名 `chat_pinned_by_time`（%1$s 名称 + %2$s 时间） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen/SecureSessionManager）；check-string-parity zh=en=2691。按用户要求未跑编译/测试。

## 1.57 2026-08-07 新目标（重建·第 2 次）第 11 轮：评论点赞存在性检查改用 .empty()（#438）

| # | 项 | 说明 |
|---|----|------|
| 438 | **Exposed 存在性检查一致化** | 1.52 评论点赞的 `commentLikedBy` / `likeComment` 用 `.limit(1).any()`（经 Kotlin Iterable 扩展可行），但与会话仓库惯例 `.empty()`（likePost 同款）不一致 → 统一改为 `!...empty()`，语义一致且避免整批迭代 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（PostRepository）。按用户要求未跑编译/测试。

## 1.58 2026-08-07 新目标（重建·第 2 次）第 12 轮：置顶横幅时间字段防御回退（#439）

| # | 项 | 说明 |
|---|----|------|
| 439 | **pinnedAt 防御** | 1.56 置顶时间在 `pinnedAt <= 0`（旧数据/缺省）时会显示「1970-01-01」类怪异相对时间。改为 `pinnedAt > 0` 用 `chat_pinned_by_time`，否则回退 `chat_pinned_by`（仅名称）；恢复 `chat_pinned_by` 字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2692。按用户要求未跑编译/测试。

## 1.59 2026-08-07 新目标（重建·第 2 次）第 13 轮：阅读详情成员可点击打开资料（#440）

| # | 项 | 说明 |
|---|----|------|
| 440 | **阅读详情成员可点** | 已读回执详情弹窗的成员行（已读/未读）点击 → 复用 `onOpenProfile`（→ `Routes.authorProfile`）打开该成员资料；未接线时不可点（enabled=onOpenProfile!=null） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）。按用户要求未跑编译/测试。

## 1.60 2026-08-07 新目标（重建·第 2 次）第 14 轮：阅读详情成员头像（#441）

| # | 项 | 说明 |
|---|----|------|
| 441 | **阅读详情头像** | 已读回执详情弹窗成员行此前只显示首字母圆圈；`ReadReceiptUi.avatar` 字段已由服务端用户数据填充但未渲染。改为 `avatar` 非空时显示头像（AsyncImage + Crop 圆形），否则回退首字母（保留已读绿色/未读灰色底） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）。按用户要求未跑编译/测试。

## 1.61 2026-08-07 新目标（重建·第 2 次）第 15 轮：阅读详情头像改用 Avatar 组件（#442）

| # | 项 | 说明 |
|---|----|------|
| 442 | **头像加载修正** | 1.60 直接用 `coil.AsyncImage` 加载 `receipt.avatar` 会因头像端点要求 JWT 认证而 401 失败。改用 `Avatar` 组件（`AvatarSize.SM`=36dp 与原圆圈一致，内置认证拦截/OwnerScoped 加载，无头像回退首字母） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）。按用户要求未跑编译/测试。

## 1.62 2026-08-07 新目标（重建·第 2 次）第 16 轮：定时消息工作日重复（#443）

| # | 项 | 说明 |
|---|----|------|
| 443 | **工作日重复** | 定时消息重复新增「工作日重复」（周一至周五，跳过周末）：`ScheduledMessage.weekdaysOnly`（序列化向后兼容）；Worker 成功/失败两条重排路径用 `nextRepeatSendAt`（按当前时刻 +1 天并跳过周末，保持时刻）；`scheduleMessageAt/scheduleMessageRepeat` 透传；`onPickRepeat(interval, count, weekdaysOnly)`；ScheduleSendDialog 新增「工作日重复」按钮；列表重复标识显示「工作日重复 · 剩 N 次」；新增 schedule_repeat_weekdays 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（Store/Worker/ChatDetailViewModel/ChatDetailScreen）；check-string-parity zh=en=2693。按用户要求未跑编译/测试。

## 1.63 2026-08-07 新目标（重建·第 2 次）第 17 轮：阅读详情未读成员优先（#444）

| # | 项 | 说明 |
|---|----|------|
| 444 | **未读优先排序** | 已读回执详情弹窗成员列表按「未读优先」（readAt==null 排前，组内稳定）展示，便于一眼看出谁还没读；搜索过滤结果同样排序 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）。按用户要求未跑编译/测试。

## 1.64 2026-08-07 新目标（重建·第 2 次）第 18 轮：阅读详情标题未读计数（#445）

| # | 项 | 说明 |
|---|----|------|
| 445 | **未读计数** | 已读回执详情标题在「已读 N/M」旁，存在未读成员时追加红色「未读 X」徽标（UnreadRed），与 1.63 未读优先排序配合，一眼可见谁还没读；新增 chat_read_details_unread 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2694。按用户要求未跑编译/测试。

## 1.65 2026-08-07 新目标（重建·第 2 次）第 19 轮：阅读详情成员在线状态（#446）

| # | 项 | 说明 |
|---|----|------|
| 446 | **在线绿点** | `ReadReceiptUi` 新增 `isOnline`（从聊天参与者 User.isOnline 填充）；阅读详情成员行名称旁显示小绿点（OnlineGreen，7dp 圆形）表示在线，与未读优先排序/头像配合，一目了然成员状态 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailUiModels/ChatDetailViewModel/ChatDetailScreen）；check-string-parity zh=en=2694。按用户要求未跑编译/测试。

## 1.66 2026-08-07 新目标（重建·第 2 次）第 20 轮：群聊列表预览带发送者名前缀（#447）

| # | 项 | 说明 |
|---|----|------|
| 447 | **群消息预览发送者** | 微信式：会话列表实时收到群聊消息时，预览带「发送者名: 内容」前缀（从会话 participants 解析 displayName，群聊且非自己消息；媒体/语音等同样适用），与 1.30「[有人@我]」前缀叠加 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListViewModel）；check-string-parity zh=en=2694。按用户要求未跑编译/测试。

## 1.67 2026-08-07 新目标（重建·第 2 次）第 21 轮：群预览发送者前缀排除系统/戳一戳（#448）

| # | 项 | 说明 |
|---|----|------|
| 448 | **发送者前缀例外** | 1.66 发送者前缀对 SYSTEM 系统消息与 NUDGE 戳一戳（其预览已含发送者语义）不再叠加前缀，避免「张三: 系统提示」「张三: [戳一戳]」类冗余 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListViewModel）；check-string-parity zh=en=2694。按用户要求未跑编译/测试。

## 1.68 2026-08-07 新目标（重建·第 2 次）第 22 轮：阅读详情过滤排序加 remember（#449）

| # | 项 | 说明 |
|---|----|------|
| 449 | **过滤排序缓存** | 1.63 阅读详情的未读优先排序 + 搜索过滤此前每次重组都全量重算；改为 `remember(readReceiptSearch, state.readReceipts)` 缓存（q 移入块内），大成员列表时减少无谓计算 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2694。按用户要求未跑编译/测试。

## 1.69 2026-08-07 新目标（重建·第 2 次）第 23 轮：update.sh 更新前离线拓扑校验（#450）

| # | 项 | 说明 |
|---|----|------|
| 450 | **更新前校验** | `update.sh` 在 `git pull` 后、构建部署前执行 `verify-production-topology.sh --offline`（compose/Caddyfile/.env 键位/安全项等），配置漂移先暴露再构建，避免带着坏配置进部署流程 |

**验证方式**：`bash -n` 语法检查通过。按用户要求未跑编译/测试。

## 1.70 2026-08-07 新目标（重建·第 2 次）第 24 轮：状态图标点击阅读详情扩展至全部气泡（#451）

| # | 项 | 说明 |
|---|----|------|
| 451 | **状态图标全类型可点** | 1.51 仅文本/Markdown 气泡的已读状态图标可点击；现 `onStatusClick` 经 MessageBubble 接线扩展至 StickerBubble / LocationBubble / VoiceBubble / FileBubble（Image/Video 无底部状态图标，无需接线），所有消息类型的自己消息 ✓✓ 图标均可点击打开阅读详情 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（MessageBubble，5 组可点/else 分支逐一核对）；check-string-parity zh=en=2694。按用户要求未跑编译/测试。

## 1.71 2026-08-07 新目标（重建·第 2 次）第 25 轮：引用预览媒体占位（#452）

| # | 项 | 说明 |
|---|----|------|
| 452 | **引用预览可读化** | 回复输入框上方的引用预览此前对媒体消息直接取 `parsedContent()`（密文/编码串不可读）；改为按类型显示可读占位（[图片]/[GIF]/[贴纸]/[语音]/[视频]/[文件]/[位置]，复用 message_preview_* 字符串），文本/Markdown 仍显示原文前 60 字 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2694。按用户要求未跑编译/测试。

## 1.72 2026-08-07 新目标（重建·第 2 次）第 26 轮：deploy --dry-run 摘要含备份保留数（#453）

| # | 项 | 说明 |
|---|----|------|
| 453 | **dry-run 摘要补全** | `deploy.sh --dry-run` 配置摘要新增 `BACKUP_KEEP`（备份保留份数）展示，部署前可一并确认备份策略 |

**验证方式**：`bash -n` 语法检查通过。按用户要求未跑编译/测试。

## 1.73 2026-08-07 新目标（重建·第 2 次）第 27 轮：复制带发送者（#454）

| # | 项 | 说明 |
|---|----|------|
| 454 | **复制带发送者** | 消息长按菜单在「复制」后新增「复制带发送者」（格式「发送者: 内容」，发送者经 senderDisplayName 解析含群昵称，无则退化为纯内容），便于引用/记录；新增 chat_copy_with_sender 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2695。按用户要求未跑编译/测试。

## 1.74 2026-08-07 新目标（重建·第 2 次）第 28 轮：未读分隔线可点击跳转（#455）

| # | 项 | 说明 |
|---|----|------|
| 455 | **未读分隔线可点** | 「以下为未读消息」分隔线从不可点改为可点击 → `viewModel.jumpToMessage(item.messageId)` 跳转并高亮第一条未读消息（1.03 分隔线的交互补全） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2695。按用户要求未跑编译/测试。

## 1.75 2026-08-07 新目标（重建·第 2 次）第 29 轮：评论点赞批量填充消 N+1（#456）

| # | 项 | 说明 |
|---|----|------|
| 456 | **点赞查询批量化** | `getComments` 每评论 `toCommentResponse` 逐条查 likeCount + likedByMe（评论多时 N+1）。改为 `toCommentResponse` 置 0/false，新增 `enrichCommentLikes`（两次批量 SQL：计数聚合 + 当前用户已赞集）在 `getComments` 与 `getCommentById` 末尾统一填充；删除弃用 `commentLikedBy`（`commentLikeCount` 保留供 like/unlike 事务内使用） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（PostRepository）。按用户要求未跑编译/测试。

## 1.76 2026-08-07 新目标（重建·第 3 次）第 1 轮：评论回复（#457，全栈）

| # | 项 | 说明 |
|---|----|------|
| 457 | **评论回复** | 服务端 `post_comments.parent_id`（可空，自动补列）+ `PostCommentResponse.parentId` + `CreateCommentRequest.replyToId` + `addComment(replyToId)`（校验回复目标存在）；客户端 `PostCommentDto.parentId` + ApiService.createPostComment(replyToId) + ExploreViewModel `replyToComment` 状态（setReplyToComment 预填 @目标 / clearReplyToComment）+ 评论行「回复」按钮 + 输入框上方「回复 张三」提示条可取消；新增 explore_comment_reply / explore_reply_to 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（Database/Models/PostRepository/Routing/SocialApiModels/ApiService/ExploreViewModel/ExploreScreen 共 8 文件）；check-string-parity zh=en=2697。按用户要求未跑编译/测试。

## 1.77 2026-08-07 新目标（重建·第 3 次）第 2 轮：回复评论显示「回复 @父作者」（#458）

| # | 项 | 说明 |
|---|----|------|
| 458 | **回复标识** | 1.76 的回复评论在列表内容上方显示「回复 @父评论作者」（`parentId` → 在评论列表找父评论作者名，复用 explore_reply_to 字符串），回复结构一眼可辨 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2697。按用户要求未跑编译/测试。

## 1.78 2026-08-07 新目标（重建·第 3 次）第 3 轮：父评论作者索引消 O(n²)（#459）

| # | 项 | 说明 |
|---|----|------|
| 459 | **回复标识索引化** | 1.77 每评论 `comments.firstOrNull{parentId}` 逐条扫描（评论多时 O(n²)）；改为 `remember(comments){associateBy{id}}` 索引（O(n) 建表 + O(1) 查询），列表量大时不卡顿 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2697。按用户要求未跑编译/测试。

## 1.79 2026-08-07 新目标（重建·第 3 次）第 4 轮：删除评论时清回复 parentId（#460）

| # | 项 | 说明 |
|---|----|------|
| 460 | **回复悬挂清理** | 1.76 评论回复：删除父评论时其回复的 `parent_id` 置空（`PostComments.update` 批量置 null），避免悬挂引用导致「回复 @不存在」 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（PostRepository）。按用户要求未跑编译/测试。

## 1.80 2026-08-07 新目标（重建·第 3 次）第 5 轮：回复目标作者通知（#461）

| # | 项 | 说明 |
|---|----|------|
| 461 | **回复通知** | 1.76 评论回复：创建回复时除通知发帖者外，还通知被回复评论的作者（经 `getCommentAuthorId`，非发帖者本人避免重复；双向拉黑过滤） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（Routing）。按用户要求未跑编译/测试。

## 1.81 2026-08-07 新目标（重建·第 3 次）第 6 轮：清理孤儿评论点赞（#462）

| # | 项 | 说明 |
|---|----|------|
| 462 | **孤儿点赞清理** | 评论被删除后其点赞行残留（accumulate）。新增 `PostRepository.purgeOrphanedCommentLikes`（`notInSubQuery` 删除 comment_id 不存在的行），接入服务端 6h 周期清理循环 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（PostRepository/Routing）。按用户要求未跑编译/测试。

## 1.82 2026-08-07 新目标（重建·第 3 次）第 7 轮：回复提示条附父评论预览（#463）

| # | 项 | 说明 |
|---|----|------|
| 463 | **回复预览** | 1.76 回复目标提示条在「回复 @name」下附父评论内容预览（单行省略），回复前可确认上下文 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2697。按用户要求未跑编译/测试。

## 1.83 2026-08-07 新目标（重建·第 3 次）第 8 轮：评论点赞独立限流（#464）

| # | 项 | 说明 |
|---|----|------|
| 464 | **限流隔离** | 1.52 评论点赞复用 `postLikeRateLimiter`（与动态点赞共享 30/min 预算）；新增 `commentLikeRateLimiter`（30/min）供评论点赞/取消点赞使用，两类点赞互不挤占配额 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（Routing）。按用户要求未跑编译/测试。

## 1.84 2026-08-07 新目标（重建·第 3 次）第 9 轮：名片消息复制干净文本（#465）

| # | 项 | 说明 |
|---|----|------|
| 465 | **名片复制** | 含 `[contactUser:...]` 标记的名片消息，长按菜单新增「复制名片文本」（经 `ChatMarkdown.stripContactCardMarker` 剥离标记，仅「👤 姓名」），避免复制出裸标记；新增 chat_copy_contact_card 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2698。按用户要求未跑编译/测试。

## 1.85 2026-08-07 新目标（重建·第 3 次）第 10 轮：deploy 健康等待超时可配置（#466）

| # | 项 | 说明 |
|---|----|------|
| 466 | **健康等待超时** | `deploy.sh` 健康等待硬编码 300s（30×10s）；改为 `HEALTH_TIMEOUT_SECONDS` 环境变量可覆盖（默认 300），慢启动/大迁移环境放宽等待不误报失败 |

**验证方式**：`bash -n` 语法检查通过。按用户要求未跑编译/测试。

## 1.86 2026-08-07 新目标（重建·第 3 次）第 11 轮：回复标识点击跳转父评论（#467）

| # | 项 | 说明 |
|---|----|------|
| 467 | **回复跳转** | 评论列表的「回复 @父作者」标识可点击 → 动画滚动定位到父评论（`LazyListState.animateScrollToItem` + 索引映射，父评论不在当前过滤结果时不可点） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2698。按用户要求未跑编译/测试。

## 1.87 2026-08-07 新目标（重建·第 3 次）第 12 轮：评论被赞通知作者（#468）

| # | 项 | 说明 |
|---|----|------|
| 468 | **评论点赞通知** | 1.52 评论点赞不通知作者。`likeComment` 返回 `(新点赞数, 是否新点赞)`；仅新点赞时经 `getCommentAuthorId` 通知评论作者（非本人、双向拉黑过滤，经 `enqueuePostInteraction(... "LIKE")`），重复点赞不重复通知 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（PostRepository/Routing）。按用户要求未跑编译/测试。

## 1.88 2026-08-07 新目标（重建·第 3 次）第 13 轮：HEALTH_TIMEOUT_SECONDS 文档化（#469）

| # | 项 | 说明 |
|---|----|------|
| 469 | **部署文档补全** | 1.85 新增的 `HEALTH_TIMEOUT_SECONDS`（deploy 健康等待超时）在 `.env.docker.example` 的 Ops 段注释文档化（默认 300s，慢启动/大迁移环境可放宽），与 BACKUP_KEEP 并列 |

**验证方式**：`bash -n` 语法检查通过；check-string-parity zh=en=2698。按用户要求未跑编译/测试。

## 1.89 2026-08-07 新目标（重建·第 3 次）第 14 轮：用户级媒体自动下载设置（#470）

| # | 项 | 说明 |
|---|----|------|
| 470 | **媒体自动下载设置** | 原「自动下载」仅服务端管理员全局开关。新增用户级偏好 `MediaAutoDownloadPreferences`（按账号隔离，`general_settings`/`chat_display_settings` 存储）：`wifi_only`（默认）/`always`/`off` 三档。通用设置页「通用」新增 `MediaAutoDownloadRow` 三选一（复用 ThemeChoiceChip）；`maybeAutoDownloadMedia` 门控：off 跳过、always 跳过计量网络检查、wifi_only 维持原行为（密聊仍强制不自动下载）；偏好随 `pushClientPrefs` 云同步。新增 general_media_auto_download_title/subtitle/wifi/always/off 中英字符串（zh=en=2703） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（新增类 + SettingsSubViewModels/SettingsSubScreens/ChatDetailViewModel）；check-string-parity zh=en=2703。按用户要求未跑编译/测试。

## 1.90 2026-08-07 新目标（重建·第 3 次）第 15 轮：restore 健康等待可配置（#471）

| # | 项 | 说明 |
|---|----|------|
| 471 | **恢复脚本健康超时可配置** | 1.85 让 deploy.sh 的 `HEALTH_TIMEOUT_SECONDS` 可覆盖，但 restore-production.sh 仍硬编码 300s（30×10s）。改为与 deploy.sh 一致：`health_timeout="${HEALTH_TIMEOUT_SECONDS:-300}"`，超时判定用 `attempt*10 >= health_timeout`（此前固定 30 次），环境变量对两脚本统一生效 |

**验证方式**：`bash -n` 语法检查通过（restore/deploy/backup/update/verify 全绿）；check-string-parity zh=en=2703。按用户要求未跑编译/测试。

## 1.91 2026-08-07 新目标（重建·第 3 次）第 16 轮：收藏列表直接取消收藏（#472）

| # | 项 | 说明 |
|---|----|------|
| 472 | **星标消息列表取消收藏** | 收藏列表此前只能点击进入会话操作，无直接取消入口。新增 `StarredMessagesViewModel.unstarMessage(messageId)`：乐观移除该行 → `ApiService.toggleStarMessage` → 服务端仍收藏或失败时恢复该行；`StarredMessageRow` 名称行的星标图标改为可点击 IconButton（contentDescription=starred_unstar），点击即取消收藏。新增 starred_unstar 中英字符串（zh=en=2704） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（StarredMessagesScreen）；check-string-parity zh=en=2704。按用户要求未跑编译/测试。

## 1.92 2026-08-07 新目标（重建·第 3 次）第 17 轮：复制评论文本（#473）

| # | 项 | 说明 |
|---|----|------|
| 473 | **评论复制** | 评论行仅删除/点赞/回复，无复制入口。新增 `ExploreViewModel.copyComment`（本地剪贴板操作，回复评论复制为「@作者: 内容」格式，Toast 提示）；`CommentsDialog` 评论操作列新增「复制」TextButton（onCopyComment 回调）；新增 explore_comment_copy/copied 中英字符串（zh=en=2706） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen/ExploreViewModel）；check-string-parity zh=en=2706。按用户要求未跑编译/测试。

## 1.93 2026-08-07 新目标（重建·第 3 次）第 18 轮：动态点赞者列表（#474）

| # | 项 | 说明 |
|---|----|------|
| 474 | **点赞者列表（全栈）** | 此前无「谁赞了」入口。Server：`PostRepository.listPostLikers`（PostLikes⋈Users，按 createdAt DESC，limit 100，过滤双向拉黑，走 `canViewInTransaction` 可见性校验）+ `GET /api/posts/{id}/likers` 路由 + `PostLikersResponse` 模型；App：`PostLikersResponse`/`getPostLikers` API + `ExploreViewModel.openLikers/closeLikers`（likersPostId/likers/isLikersLoading 状态）+ 点赞数文本可点击弹出 `LikersDialog`（头像+昵称列表）；新增 explore_likers_title/empty 中英字符串（zh=en=2708） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen/ExploreViewModel/SocialApiModels/ApiService/Models/PostRepository/Routing）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.94 2026-08-07 新目标（重建·第 3 次）第 19 轮：动态详情页打通（#475）

| # | 项 | 说明 |
|---|----|------|
| 475 | **动态详情页打通** | 此前 PostDetailScreen 只能从通知中心进入，动态卡片无法打开完整详情页。① `ExploreScreen` 增 `onOpenPost` 回调（NavGraph → `Routes.postDetail`），卡片正文点击打开详情；② PostDetailScreen 帖子卡片新增点赞/评论操作行（点赞按钮 + 可点击点赞数 → `openLikers` + 评论计数）；③ `toggleLike` 从 `posts` 回退到 `detailPost`（通知深链进入时也有效），乐观/服务端应用/回滚/取消四路径同步更新 `detailPost`（`updatePost` 同步更新）；④ `LikersDialog` 改 internal 供 Explore 与 PostDetail 共用 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen/ExploreSubScreens/ExploreViewModel/NavGraph）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.95 2026-08-07 新目标（重建·第 3 次）第 20 轮：详情页评论操作（#476）

| # | 项 | 说明 |
|---|----|------|
| 476 | **详情页评论可操作** | PostDetailScreen 评论行此前只读（无点赞/删除）。新增操作列：点赞（`toggleCommentLike`，乐观+回滚已有）+ 点赞数显示（已赞粉色高亮）、本人评论显示删除按钮（`deleteComment`，行乐观移除）；`currentUserId` 从 TokenManager 取 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.96 2026-08-07 新目标（重建·第 3 次）第 21 轮：deploy 非交互环境守卫（#477）

| # | 项 | 说明 |
|---|----|------|
| 477 | **非交互部署守卫** | 交互式引导已用 `-t 0` 防阻塞，但非交互环境（CI/cron 管道）缺 `--host` 时 HOST 保持空值继续跑，PUBLIC_HOST 为空带病启动（Caddy 证书/域名错乱难排查）。新增：非交互（`! -t 0`）且 .env 无有效 PUBLIC_HOST（空/占位/示例值）且未给 `--host` → `fail` 明确报错指引首次交互式部署或传参。对 update.sh 间接生效（deploy 末尾 exec） |

**验证方式**：`bash -n` 语法检查通过（deploy/update/backup/restore/verify 全绿）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.97 2026-08-07 新目标（重建·第 3 次）第 22 轮：详情页评论输入条（#478）

| # | 项 | 说明 |
|---|----|------|
| 478 | **详情页评论输入** | PostDetailScreen 此前只读展示评论、无法发评/回复。重构评论区为 `when` 分支（加载/空/列表均用 `weight(1f)` 占位，不再 `return@Scaffold`），底部新增常驻 `CommentComposerBar`（输入框+发送，复用 `sendComment`/`onCommentTextChange`，支持 `replyToComment` 提示条可取消）；评论行新增「回复」按钮（`setReplyToComment`），与点赞/删除并列，详情页评论全操作闭环 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.98 2026-08-07 新目标（重建·第 3 次）第 23 轮：朋友圈卡片可点赞（#479）

| # | 项 | 说明 |
|---|----|------|
| 479 | **朋友圈点赞** | MomentsScreen（朋友圈）卡片此前只展示点赞数、爱心图标不可点。改为 IconButton 切换点赞（`toggleLike`，已赞粉色实心/未赞描边），与 Explore 流行为一致；点赞状态经 `updatePost` 同步到该页 ViewModel 的 posts |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.99 2026-08-07 新目标（重建·第 3 次）第 24 轮：详情页图片网格（#480）

| # | 项 | 说明 |
|---|----|------|
| 480 | **详情页图片展示** | PostDetailScreen 帖子卡片此前只显示文本，不含图片（从通知深链进入时完全看不到图）。`ImageGrid` 改 internal 供 Explore 与 PostDetail 共用；详情页在文本下方插入 `ImageGrid(post.imageUrls)`（点击全屏查看逻辑不变） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen/ExploreSubScreens）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.100 2026-08-07 新目标（重建·第 3 次）第 25 轮：详情页分享（#481）

| # | 项 | 说明 |
|---|----|------|
| 481 | **详情页分享** | PostDetailScreen 帖子卡片操作行新增分享按钮（`Icons.Outlined.Share`），与动态流分享逻辑一致（正文引用 + [图片] 占位，ACTION_SEND 系统分享）；补 `outlined.Share` 导入 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.101 2026-08-07 新目标（重建·第 3 次）第 26 轮：详情页发评自动滚动（#482）

| # | 项 | 说明 |
|---|----|------|
| 482 | **发评自动滚动** | 详情页评论为「旧→新」列表（分页在头部插入、发评在尾部追加）。发送新评论后此前不滚动，长列表时看不到自己刚发的评论。新增 `commentListState` + 末条 id 侦测：仅当「末条 id 变化且此前已有评论」时 `animateScrollToItem(lastIndex)`（首次加载与分页头部插入不触发） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.102 2026-08-07 新目标（重建·第 4 次）第 1 轮：verify 拓扑回归 likers 路由（#483）

| # | 项 | 说明 |
|---|----|------|
| 483 | **拓扑校验补端点回归** | 1.93 新增 `GET /api/posts/{id}/likers`（App 依赖），但 verify-production-topology.sh --live 未覆盖。新增：无凭据探测 `/_probe_/likers` 返回 404 即判 FAIL（路由在 authenticate 块内，存在时返回 401） |

**验证方式**：`bash -n` 语法检查通过（verify/deploy）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.103 2026-08-07 新目标（重建·第 4 次）第 2 轮：会话列表「正在输入」（#484）

| # | 项 | 说明 |
|---|----|------|
| 484 | **列表输入中提示** | 输入指示此前仅会话内显示，会话列表看不到。新增 `TypingPresenceStore`（进程级单例）：订阅 `WebSocketClient.events` 的 `UserTyping`，按 chatId 维护对端输入（3s 过期），登录用户变化自动重置、登出 `clear()`；`ChatListViewModel` 收集 `typingByChat` 进状态；`ChatListItem` 预览最优先显示「对方正在输入…」（Primary 高亮，锁/密聊列表不泄露）；复用既有 `chat_typing` 字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（TypingPresenceStore/MaodouchatApp/ChatListVM/ChatListScreen/SettingsVM）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.104 2026-08-07 新目标（重建·第 4 次）第 3 轮：详情页回复目标标识（#485）

| # | 项 | 说明 |
|---|----|------|
| 485 | **详情页回复标识** | PostDetailScreen 评论行此前不显示「回复 @父作者」（仅评论弹窗有）。新增：`commentAuthorById` 索引（remember 一次构建，避免 O(n²)）+ 有 parentId 且父作者存在时在作者名下方显示「回复 @父作者」（Primary 小字），与评论弹窗一致 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.105 2026-08-07 新目标（重建·第 4 次）第 4 轮：详情页回复标识可跳父评论（#486）

| # | 项 | 说明 |
|---|----|------|
| 486 | **回复标识跳转** | 1.104 的「回复 @父作者」标识为纯展示。改为可点击：`filteredComments.indexOfFirst` 定位父评论 → `commentListState.animateScrollToItem`（父评论不在当前过滤结果时不可点），与评论弹窗行为一致；新增 `commentListScope` |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.106 2026-08-07 新目标（重建·第 4 次）第 5 轮：通话记录回拨保留通话类型（#487）

| # | 项 | 说明 |
|---|----|------|
| 487 | **回拨类型修复** | CallHistoryScreen 回拨硬编码 `"AUDIO"`，视频通话记录点回拨却发起语音。`CallLogEntry.isVideo` 已记录但未用——改为 `if (entry.isVideo) "VIDEO" else "AUDIO"`，与行内视频图标一致 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（CallHistoryScreen）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.107 2026-08-07 新目标（重建·第 4 次）第 6 轮：详情页作者行可点开主页（#488）

| # | 项 | 说明 |
|---|----|------|
| 488 | **详情页作者入口** | PostDetailScreen 帖子卡片作者行此前不可点。新增 `onOpenAuthor` 回调（NavGraph → `Routes.authorProfile`），作者行（头像+名字）点击打开作者主页 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens/NavGraph）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.108 2026-08-07 新目标（重建·第 4 次）第 7 轮：动态流下拉刷新（#489）

| # | 项 | 说明 |
|---|----|------|
| 489 | **动态下拉刷新** | ExploreScreen 动态流此前仅顶部刷新按钮。用 `PullToRefreshLayout`（会话列表同款组件）包裹 LazyColumn：`isRefreshing=uiState.isLoading` + `onRefresh=viewModel::refresh`，下拉手势触发刷新 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2708。按用户要求未跑编译/测试。

## 1.109 2026-08-07 新目标（重建·第 4 次）第 8 轮：动态流只看我的（#490）

| # | 项 | 说明 |
|---|----|------|
| 490 | **只看我的过滤** | 动态流只有全文搜索。新增「只看我的」FilterChip（与搜索框同行，`showOnlyMine` 可持久化状态）：过滤 `isMine || author.id==currentUserId`，与搜索过滤叠加；新增 explore_feed_only_mine 中英字符串（zh=en=2709） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2709。按用户要求未跑编译/测试。

## 1.110 2026-08-07 新目标（重建·第 4 次）第 9 轮：动态卡片作者行开主页（#491）

| # | 项 | 说明 |
|---|----|------|
| 491 | **动态卡片作者入口** | ExploreScreen 动态流 PostCard 作者行此前不可点。新增 `onOpenAuthor` 回调（NavGraph → `Routes.authorProfile`），PostCard 作者行（头像+名字）点击打开作者主页，与详情页/朋友圈一致 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen/NavGraph）；check-string-parity zh=en=2709。按用户要求未跑编译/测试。

## 1.111 2026-08-07 新目标（重建·第 4 次）第 10 轮：朋友圈下拉刷新（#492）

| # | 项 | 说明 |
|---|----|------|
| 492 | **朋友圈下拉刷新** | MomentsScreen 此前仅顶部刷新按钮。用 `PullToRefreshLayout`（动态流/会话列表同款）包裹朋友圈 LazyColumn：`isRefreshing=state.isLoading` + `onRefresh=viewModel::refresh` |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2709。按用户要求未跑编译/测试。

## 1.112 2026-08-07 新目标（重建·第 4 次）第 11 轮：Explore 标签互动角标（#493）

| # | 项 | 说明 |
|---|----|------|
| 493 | **动态互动角标** | 底部导航 Explore 标签此前无角标，动态互动（点赞/评论/回复通知）不易发现。新增 `ExploreBadgeStore.count`（全局，同 UnreadBadgeStore 模式）；ChatListViewModel 从 `notificationRepo.items` 派生未读 POST_INTERACTION 数推入 store（读后自动归零）；BottomNavBar 的 Explore 项显示 Badge（>99 显示 99+） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen/ChatListViewModel）；check-string-parity zh=en=2709。按用户要求未跑编译/测试。

## 1.113 2026-08-07 新目标（重建·第 4 次）第 12 轮：评论被赞通知文案区分（#494）

| # | 项 | 说明 |
|---|----|------|
| 494 | **评论点赞通知细分** | 1.87 评论被赞通知用「LIKE」类型，App 显示「有人赞了你的动态」不准确。Server：`enqueuePostInteraction` 允许集加入 `COMMENT_LIKE`，评论点赞通知改发 `COMMENT_LIKE`；App：`showPostInteraction` 增 `interaction` 参数按类型选文案（`notification_post_comment_like` 新增）、通知中心 subtitle/id/kind 同步区分；FCM 处理器透传 interaction。新增中英字符串（zh=en=2710） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（AppNotifier/MaodouFirebaseMessagingService/FcmPushService/Routing）；check-string-parity zh=en=2710。按用户要求未跑编译/测试。

## 1.114 2026-08-07 新目标（重建·第 4 次）第 13 轮：详情页作者在线状态（#495）

| # | 项 | 说明 |
|---|----|------|
| 495 | **详情页作者在线点** | PostDetailScreen 作者头像未显示在线状态（动态流/朋友圈均有）。Avatar 补 `isOnline = post.author.isOnline`，与 feed/朋友圈一致 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2710。按用户要求未跑编译/测试。

## 1.115 2026-08-07 新目标（重建·第 4 次）第 14 轮：详情页评论复制（#496）

| # | 项 | 说明 |
|---|----|------|
| 496 | **详情页评论复制** | PostDetailScreen 评论操作列只有回复/点赞/删除，缺复制（评论弹窗已有 1.92）。新增「复制」TextButton → 复用 `viewModel.copyComment`（回复评论复制为「@作者: 内容」），与评论弹窗行为一致 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2710。按用户要求未跑编译/测试。

## 1.116 2026-08-07 新目标（重建·第 4 次）第 15 轮：设置页「我的动态」入口（#497）

| # | 项 | 说明 |
|---|----|------|
| 497 | **我的动态入口** | 设置-账号组新增「我的动态」（`Icons.Outlined.Article`）→ `onOpenMyPosts` 回调（NavGraph 用当前用户 id 跳 `Routes.authorProfile`），从作者主页视角查看/管理自己发布的动态。新增 settings_my_posts 中英字符串（zh=en=2711） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（SettingsScreen/NavGraph）；check-string-parity zh=en=2711。按用户要求未跑编译/测试。

## 1.117 2026-08-07 新目标（重建·第 4 次）第 16 轮：删评论同步递减计数（#498）

| # | 项 | 说明 |
|---|----|------|
| 498 | **删评论计数同步** | `deleteComment` 成功后仅从列表移除，帖子/详情页的 commentCount 不变（发评已递增 1.81/1.94）。新增 `ExploreFeedPolicy.decrementCommentCount`（不低于 0），删除评论时同步递减 `posts` 与 `detailPost` 的 commentCount |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreViewModel/ExploreFeedPolicy）；check-string-parity zh=en=2711。按用户要求未跑编译/测试。

## 1.118 2026-08-07 新目标（重建·第 4 次）第 17 轮：评论弹窗发评自动滚动（#499）

| # | 项 | 说明 |
|---|----|------|
| 499 | **弹窗发评滚动** | 1.101 的「发评自动滚动到底」只在详情页实现，评论弹窗（feed）发送新评论后长列表看不到新评论。复制同款逻辑到 CommentsDialog：末条 id 侦测 → `animateScrollToItem(lastIndex)`（首次加载与分页头部插入不触发） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2711。按用户要求未跑编译/测试。

## 1.119 2026-08-07 新目标（重建·第 4 次）第 18 轮：通知设置发送测试通知（#500）

| # | 项 | 说明 |
|---|----|------|
| 500 | **测试通知** | 通知设置页仅能改配置、无法验证生效。新增「发送测试通知」ActionRow（在声音开关后）：`AppNotifier.showTestNotification` 用当前铃声/震动偏好发一条本地通知（`NOTIFY_TAG_TEST`，静音偏好生效），Toast 提示。新增 notifications_test_title/subtitle/sent/body 中英字符串（zh=en=2715） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（AppNotifier/SettingsSubScreens）；check-string-parity zh=en=2715。按用户要求未跑编译/测试。

## 1.120 2026-08-07 新目标（重建·第 4 次）第 19 轮：群邀请链接复制（#501）

| # | 项 | 说明 |
|---|----|------|
| 501 | **邀请链接复制** | 群邀请弹窗只有扫码+系统分享，无法直接复制链接粘贴到聊天。新增「复制邀请链接」按钮（QR 下方，payload 非空时显示）：复制 payload 到剪贴板 + Toast。新增 group_detail_invite_copy/copied 中英字符串（zh=en=2717） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（GroupDetailScreen）；check-string-parity zh=en=2717。按用户要求未跑编译/测试。

## 1.121 2026-08-07 新目标（重建·第 4 次）第 20 轮：开动态批量已读互动通知（#502）

| # | 项 | 说明 |
|---|----|------|
| 502 | **动态互动批量已读** | 打开某条动态仅标记点击的那条通知已读，同 post 的多条互动（赞/评/回复/评论赞）仍留未读。新增 `NotificationCenterRepository.markPostInteractionsRead(postId)`（mergeKey `post_{id}` 或 extra.postId 匹配）；NavGraph 的 `maodouchat:post:` 跳转前调用，Explore 角标/通知中心未读同步归零 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（NotificationCenterRepository/NavGraph）；check-string-parity zh=en=2717。按用户要求未跑编译/测试。

## 1.122 2026-08-07 新目标（重建·第 4 次）第 21 轮：回复目标通知文案区分（#503）

| # | 项 | 说明 |
|---|----|------|
| 503 | **回复通知细分** | 1.80 回复目标作者通知用「COMMENT」类型，App 显示「有人评论了你的动态」不准确。Server：`enqueuePostInteraction` 允许集加入 `REPLY`，回复目标作者通知改发 `REPLY`（发帖作者仍为 COMMENT）；App：`showPostInteraction` 按类型选文案（新增「有人回复了你的评论」）、FCM 处理器 REPLY 计入 isComment。新增 notification_post_reply 中英字符串（zh=en=2718） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（AppNotifier/MaodouFirebaseMessagingService/FcmPushService/Routing）；check-string-parity zh=en=2718。按用户要求未跑编译/测试。

## 1.123 2026-08-07 新目标（重建·第 4 次）第 22 轮：详情页作者状态显示（#504）

| # | 项 | 说明 |
|---|----|------|
| 504 | **详情页作者状态** | PostDetailScreen 作者行只显示名字+时间，作者个性签名（`UserDto.status`）未展示。非空时在名字下方显示（labelSmall、一行省略），便于了解作者当前状态 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2718。按用户要求未跑编译/测试。

## 1.124 2026-08-07 新目标（重建·第 4 次）第 23 轮：通知中心动态互动图标区分（#505）

| # | 项 | 说明 |
|---|----|------|
| 505 | **互动图标区分** | 通知中心 POST_INTERACTION 一律用爱心图标，无法一眼区分评论/点赞。`iconForType` 改为接收 item，按 `extra["kind"]` 区分：comment/comment_like → 聊天气泡图标（Primary），like → 爱心（粉色）；补 ChatBubbleOutline 导入 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（NotificationCenterScreen）；check-string-parity zh=en=2718。按用户要求未跑编译/测试。

## 1.125 2026-08-07 新目标（重建·第 4 次）第 24 轮：评论回复防跨帖引用（#506）

| # | 项 | 说明 |
|---|----|------|
| 506 | **回复目标同帖校验** | `addComment` 回复目标仅校验「存在」，可回复到其他动态的评论（跨帖引用，父评论不在该帖评论列表中出现）。改为 `(id==replyToId) and (postId==postId)` 同帖校验，跨帖引用返回 404 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（PostRepository）；check-string-parity zh=en=2718。按用户要求未跑编译/测试。

## 1.126 2026-08-07 新目标（重建·第 4 次）第 25 轮：删评/删帖即时清理评论点赞（#507）

| # | 项 | 说明 |
|---|----|------|
| 507 | **评论点赞即时清理** | 删除评论/动态时其 CommentLikes 不删除，仅靠 1.81 的 6h 周期 `purgeOrphanedCommentLikes` 兜底（期间 orphan 行残留）。`deleteCommentForUser` 删前清理该评论点赞；`deletePostRow` 先收集该帖全部评论 id → 批量删 CommentLikes → 再删评论/点赞/帖子（均在事务内） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（PostRepository）；check-string-parity zh=en=2718。按用户要求未跑编译/测试。

## 1.127 2026-08-07 新目标（重建·第 5 次）第 1 轮：动态图片保存到相册（#508）

| # | 项 | 说明 |
|---|----|------|
| 508 | **动态图保存** | 动态图片走 `/api/files/post-image/{filename}` 认证路由，此前无法保存。新增 `ApiService.downloadPostImage`（Bearer 认证 GET → 本地缓存文件，失败清理）；ImageGrid 全屏查看器左上角新增「保存」按钮（`Icons.Outlined.Download`）：下载 → `MediaExport.saveToGallery`（MediaStore 相册）→ Toast 结果。新增 explore_save_image/saved_to_gallery/save_failed 中英字符串（zh=en=2721） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ApiService/ExploreScreen）；check-string-parity zh=en=2721。按用户要求未跑编译/测试。

## 1.128 2026-08-07 新目标（重建·第 5 次）第 2 轮：会话列表单聊在线绿点（#509）

| # | 项 | 说明 |
|---|----|------|
| 509 | **列表在线状态** | 会话列表头像未显示在线状态（详情/通讯录已有）。ChatListItem 的 Avatar 补 `isOnline = !chat.isGroup && otherUser?.isOnline == true`：单聊显示对方在线绿点，群聊不显示 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen）；check-string-parity zh=en=2721。按用户要求未跑编译/测试。

## 1.129 2026-08-07 新目标（重建·第 5 次）第 3 轮：图片保存下载中指示（#510）

| # | 项 | 说明 |
|---|----|------|
| 510 | **保存转圈** | 1.127 保存按钮下载大图时无反馈且可重复点击。ImageGrid 增 `savingImage` 状态：下载期间按钮显示转圈并禁止重复触发（`finally` 复位），完成后恢复下载图标 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2721。按用户要求未跑编译/测试。

## 1.130 2026-08-07 新目标（重建·第 5 次）第 4 轮：互动通知附内容预览（#511）

| # | 项 | 说明 |
|---|----|------|
| 511 | **互动通知预览** | 评论/回复/评论赞通知只有通用文案，看不到内容。Server：`enqueuePostInteraction` 增 `preview` 参数（截断 80），评论创建（COMMENT）、回复（REPLY）、评论赞（COMMENT_LIKE）调用处附内容；`PostRepository.getComment` 公开（路由取预览用）。App：`showPostInteraction` 增 `preview` 参数——通知栏正文「文案：内容」、通知中心 item.preview 显示内容；FCM 处理器透传 preview |

**验证方式**：brace_strip2.py 括号平衡 delta=0（AppNotifier/MaodouFirebaseMessagingService/FcmPushService/Routing/PostRepository）；check-string-parity zh=en=2721。按用户要求未跑编译/测试。

## 1.131 2026-08-07 新目标（重建·第 5 次）第 5 轮：动态空态直达发布框（#512）

| # | 项 | 说明 |
|---|----|------|
| 512 | **空态发布入口** | 动态流空态（无任何动态）只有文案无动作。EmptyState 增 `actionText=explore_empty_action`，点击 `animateScrollToItem(1)` 滚动到发布框（composer 为列表第 2 项）。新增中英字符串（zh=en=2722） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2722。按用户要求未跑编译/测试。

## 1.132 2026-08-07 新目标（重建·第 5 次）第 6 轮：互动通知跳转定位评论（#513）

| # | 项 | 说明 |
|---|----|------|
| 513 | **通知跳转评论** | 评论/回复/评论赞通知打开动态不定位具体评论。Server：`enqueuePostInteraction` 增 `commentId`（评论创建/回复/评论赞调用处附评论 id）；App：FCM 透传 commentId → 通知中心 deeplink 带 `?comment=` 查询参数 + extra.commentId；NavGraph 解析查询 → `Routes.postDetail(postId, commentId)`（路由增 `?comment={comment}` 参数）；`PostDetailScreen` 增 `initialCommentId`：评论出现在已加载集合后 `scrollToItem` 一次（best-effort） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（FcmPushService/Routing/AppNotifier/MaodouFirebaseMessagingService/NavGraph/ExploreSubScreens）；check-string-parity zh=en=2722。按用户要求未跑编译/测试。

## 1.133 2026-08-07 新目标（重建·第 5 次）第 7 轮：通知震动开关（#514）

| # | 项 | 说明 |
|---|----|------|
| 514 | **震动开关** | 通知设置只有声音开关，无震动控制。新增 `NotificationPreferences.vibrationEnabled/setVibrationEnabled`（按账号隔离，KEY_VIBRATION 入 BOOLEAN_KEYS 迁移）；`ensureChannels` 对 MESSAGES/GROUP/CALLS/AI_TASKS 渠道 `enableVibration(pref)`（改动后重建渠道生效）；设置页新增「震动」SwitchRow（`setVibrationEnabled` + ensureChannels）；新增 notifications_vibration_title/subtitle 中英字符串（zh=en=2724） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（NotificationPreferences/AppNotifier/SettingsSubViewModels/SettingsSubScreens）；check-string-parity zh=en=2724。按用户要求未跑编译/测试。

## 1.134 2026-08-07 新目标（重建·第 5 次）第 8 轮：通知跳转评论短暂高亮（#515）

| # | 项 | 说明 |
|---|----|------|
| 515 | **跳转评论高亮** | 1.132 通知跳转只滚动到目标评论、无视觉定位。PostDetailScreen 增 `highlightedCommentId`：定位成功后设为目标评论 id，2.5s 后清除；该评论行加 `Primary.copy(alpha=0.08f)` 圆角背景短暂高亮 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2724。按用户要求未跑编译/测试。

## 1.135 2026-08-07 新目标（重建·第 5 次）第 9 轮：语音倍速偏好持久化（#516）

| # | 项 | 说明 |
|---|----|------|
| 516 | **倍速持久化** | 语音倍速选择只在进程内保留（`stopInternal` 保留 speed），重启后回到 1x。`VoicePlayer` 增 `loadSavedSpeed`（ensureContext 时一次性加载，SharedPreferences `voice_player_settings`）+ `persistSpeed`（setSpeed 时写入）；重启后仍记住用户倍速偏好 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（VoicePlayer）；check-string-parity zh=en=2724。按用户要求未跑编译/测试。

## 1.136 2026-08-07 新目标（重建·第 5 次）第 10 轮：动态图片系统分享（#517）

| # | 项 | 说明 |
|---|----|------|
| 517 | **图片分享** | 全屏查看器已有保存（1.127）无分享。新增「分享」按钮（保存按钮下方，`Icons.Outlined.Share`）：认证下载到缓存 → `MediaExport.share`（ACTION_SEND）；`sharingImage` 下载中转圈防重复；复用 chat_share 字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2724。按用户要求未跑编译/测试。

## 1.137 2026-08-07 新目标（重建·第 5 次）第 11 轮：禁止自赞动态（#518）

| # | 项 | 说明 |
|---|----|------|
| 518 | **禁止自赞** | 此前作者可给自己的动态点赞（点赞数可虚高）。Server：点赞端点检查 `getPostAuthorId(postId)==userId` → 400「不能给自己的动态点赞」；App：`toggleLike` 对 `isMine`/作者本人动态直接提示（`explore_self_like_denied`，避免乐观回滚抖动）。新增中英字符串（zh=en=2725） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（Routing/ExploreViewModel）；check-string-parity zh=en=2725。按用户要求未跑编译/测试。

## 1.138 2026-08-07 新目标（重建·第 5 次）第 12 轮：详情页作者在线/最后在线（#519）

| # | 项 | 说明 |
|---|----|------|
| 519 | **作者在线状态文字** | PostDetailScreen 作者行只有头像在线点（1.114），无文字说明。新增：在线 → 「在线」（Primary），否则 `lastSeen>0` → 「最后在线 X 分钟前」（`user_last_seen_prefix` + `DateUtils.getRelativeTimeSpanString`），复用既有字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2725。按用户要求未跑编译/测试。

## 1.139 2026-08-07 新目标（重建·第 5 次）第 13 轮：动态计数紧凑显示（#520）

| # | 项 | 说明 |
|---|----|------|
| 520 | **大数紧凑** | 点赞/评论数大时显示原始长数字。新增 `ExploreFeedPolicy.formatCount`（<1000 原样；<1万 1.2K 形式去尾零；≥1万 12K）；应用到动态流 PostCard 与详情页操作行的点赞/评论计数 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreFeedPolicy/ExploreScreen/ExploreSubScreens）；check-string-parity zh=en=2725。按用户要求未跑编译/测试。

## 1.140 2026-08-07 新目标（重建·第 5 次）第 14 轮：动态分享附作者主页链接（#521）

| # | 项 | 说明 |
|---|----|------|
| 521 | **分享带主页链接** | 动态分享文本只有内容+[图片]，无来源入口。作者有 `username` 时在分享文本末尾附 `https://chat.mdou.me/u/{username}`（公开主页），动态流与详情页分享逻辑一致 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen/ExploreSubScreens）；check-string-parity zh=en=2725。按用户要求未跑编译/测试。

## 1.141 2026-08-07 新目标（重建·第 5 次）第 15 轮：密聊不显示在线绿点（#522）

| # | 项 | 说明 |
|---|----|------|
| 522 | **密聊在线隐私** | 1.128 会话列表单聊在线绿点未排除密聊（存在侧信道泄漏在线状态，与密聊 presence 门控原则冲突）。ChatListItem 的 `isOnline` 加 `!isSecret` 条件，密聊不显示在线点 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen）；check-string-parity zh=en=2725。按用户要求未跑编译/测试。

## 1.142 2026-08-07 新目标（重建·第 5 次）第 16 轮：会话长按清除草稿（#523）

| # | 项 | 说明 |
|---|----|------|
| 523 | **清除草稿** | 会话有草稿时无法从列表清除（需打开会话删空）。新增 `ChatListViewModel.clearChatDraft`（本地 deleteForChat）；会话长按菜单在存在草稿时显示「清除草稿」项。新增 chat_clear_draft 中英字符串（zh=en=2726） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen/ChatListViewModel）；check-string-parity zh=en=2726。按用户要求未跑编译/测试。

## 1.143 2026-08-07 新目标（重建·第 5 次）第 17 轮：动态全屏图片缩放（#524）

| # | 项 | 说明 |
|---|----|------|
| 524 | **全屏图缩放** | 动态图片全屏查看器此前只展示（无缩放）。改用 `ZoomableAsyncImage`（聊天图片同款）：支持捏合缩放/双击放大/拖拽平移，保持 OwnerScopedImageKeys 认证加载 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2726。按用户要求未跑编译/测试。

## 1.144 2026-08-07 新目标（重建·第 5 次）第 18 轮：黑名单搜索（#525）

| # | 项 | 说明 |
|---|----|------|
| 525 | **黑名单搜索** | BlockedUsersScreen 无搜索，屏蔽用户多时难找。新增 `blockedSearch` 过滤（昵称/ID 不区分大小写）+ 搜索框（有用户时显示）+ 无匹配空态；复用既有 blocked_search_hint/empty 字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（SettingsSubScreens）；check-string-parity zh=en=2726。按用户要求未跑编译/测试。

## 1.145 2026-08-07 新目标（重建·第 5 次）第 19 轮：评论弹窗标题显示评论数（#526）

| # | 项 | 说明 |
|---|----|------|
| 526 | **弹窗评论数** | CommentsDialog 标题仅「评论」，无数量。改为复用 `explore_comments_count`（评论（%1$d））显示已加载评论数 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2726。按用户要求未跑编译/测试。

## 1.146 2026-08-07 新目标（重建·第 5 次）第 20 轮：会话列表定时消息提示（#527）

| # | 项 | 说明 |
|---|----|------|
| 527 | **定时待发送提示** | 会话列表不显示待发送的定时消息，用户可能忘。`ChatListViewModel` 增 `scheduledByChat`（`ScheduledMessageStore.list` 按 chatId 计数）+ `refreshScheduledCounts`（init 与 ON_RESUME 时刷新）；ChatListItem 在预览最优先显示「定时 N 条待发送」（Secondary 高亮，锁/密聊不显示）。新增 chat_scheduled_list_preview 中英字符串（zh=en=2727） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen/ChatListViewModel）；check-string-parity zh=en=2727。按用户要求未跑编译/测试。

## 1.147 2026-08-07 新目标（重建·第 5 次）第 21 轮：动态卡片作者在线/最后在线（#528）

| # | 项 | 说明 |
|---|----|------|
| 528 | **卡片作者状态** | 动态流 PostCard 作者行只有在线点（1.110 后），无文字状态。与详情页一致：在线 → 「在线」（Primary）；否则 `lastSeen>0` → 「最后在线 X」（`user_last_seen_prefix` + `DateUtils.getRelativeTimeSpanString`）；复用既有字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2727。按用户要求未跑编译/测试。

## 1.148 2026-08-07 新目标（重建·第 5 次）第 22 轮：会话列表搜索关键词高亮（#529）

| # | 项 | 说明 |
|---|----|------|
| 529 | **搜索关键词高亮** | 会话列表搜索仅过滤不高亮，匹配不直观。ChatListItem 接收 `searchQuery`：搜索激活时名称与预览用 `highlightedText`（复用 `GlobalSearchTextHighlight.buildSnippet`，关键词 Primary 加粗+淡背景），与全局搜索一致；非搜索态走原 Text |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen）；check-string-parity zh=en=2727。按用户要求未跑编译/测试。

## 1.149 2026-08-07 新目标（重建·第 5 次）第 23 轮：合并转发（#530）

| # | 项 | 说明 |
|---|----|------|
| 530 | **合并转发** | 多选转发只能逐条发送。转发弹窗新增「合并转发」开关（仅 2+ 条纯文本消息时可用）：开启后把所选文本合并为一条（每行「发送者：内容」，用 `sendTextToChat` 发送），与附带留言叠加。新增 chat_forward_merge_title/subtitle 中英字符串（zh=en=2729） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2729。按用户要求未跑编译/测试。

## 1.150 2026-08-07 新目标（重建·第 5 次）第 24 轮：动态卡片作者个性签名（#531）

| # | 项 | 说明 |
|---|----|------|
| 531 | **卡片作者签名** | 动态流 PostCard 作者区只显示名字+时间+在线状态，未显示作者个性签名（详情页/作者主页均有）。作者 `status` 非空时在名字下显示（一行省略），与详情页一致 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2729。按用户要求未跑编译/测试。

## 1.151 2026-08-07 新目标（重建·第 6 次）第 1 轮：会话列表在线/最后在线标签（#532）

| # | 项 | 说明 |
|---|----|------|
| 532 | **列表在线标签** | 会话列表单聊只有头像在线点（1.128），预览区无文字状态。预览行起始处：单聊且非密聊时，在线 → 「在线」（Primary）；否则 `lastSeen>0` → 「最后在线 X」（`user_last_seen_prefix` + `DateUtils.getRelativeTimeSpanString`），复用既有字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen）；check-string-parity zh=en=2729。按用户要求未跑编译/测试。

## 1.152 2026-08-07 新目标（重建·第 6 次）第 2 轮：撤回倒计时提示（#533）

| # | 项 | 说明 |
|---|----|------|
| 533 | **撤回倒计时** | 撤回限 5 分钟但菜单/确认框无提示（超时只报失败）。长按菜单撤回项显示「撤回（剩余 %1$d 分钟）」（向上取整）；确认弹窗按钮同步显示剩余分钟。新增 chat_revoke_with_limit 中英字符串（zh=en=2730） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2730。按用户要求未跑编译/测试。

## 1.153 2026-08-07 新目标（重建·第 6 次）第 3 轮：会话顶栏静音图标（#534）

| # | 项 | 说明 |
|---|----|------|
| 534 | **顶栏静音图标** | 会话详情顶栏标题旁无静音状态提示（仅会话列表有）。`state.chat?.notificationsMuted == true` 时在标题旁显示 `Icons.Outlined.NotificationsOff`（TextSecondary 16dp，contentDescription=chat_mute_notifications），进入静音会话一眼可见 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2730。按用户要求未跑编译/测试。

## 1.154 2026-08-07 新目标（重建·第 6 次）第 4 轮：动态卡片复制正文（#535）

| # | 项 | 说明 |
|---|----|------|
| 535 | **复制动态正文** | 评论可复制（1.92）但动态正文不可。PostCard 分享旁新增复制按钮（`Icons.Outlined.ContentCopy`）：复制 `post.content`（无正文纯图片时复制「[图片]」）到剪贴板 + Toast。新增 explore_copy_post/post_copied/post_copied_image 中英字符串（zh=en=2733） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2733。按用户要求未跑编译/测试。

## 1.155 2026-08-07 新目标（重建·第 6 次）第 5 轮：会话内置顶/取消置顶（#536）

| # | 项 | 说明 |
|---|----|------|
| 536 | **会话内置顶** | 置顶此前仅在会话列表长按/滑动。会话详情溢出菜单新增「置顶/取消置顶」项：`ChatDetailViewModel.toggleChatPinned` 乐观更新 `state.chat.pinnedAt`（`UpdateChatSettingsRequest(pinned=...)` 服务端同步，失败回滚，CHAT_PIN 开关门控） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailViewModel/ChatDetailScreen）；check-string-parity zh=en=2733。按用户要求未跑编译/测试。

## 1.156 2026-08-07 新目标（重建·第 6 次）第 6 轮：会话内标记未读（#537）

| # | 项 | 说明 |
|---|----|------|
| 537 | **会话内标未读** | 标记未读此前仅在会话列表长按。会话详情溢出菜单新增「标记未读/标记已读」项：`toggleChatMarkedUnread` 乐观更新 `state.chat.markedUnread` + `UpdateChatSettingsRequest(markedUnread=...)` 服务端同步（失败回滚） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailViewModel/ChatDetailScreen）；check-string-parity zh=en=2733。按用户要求未跑编译/测试。

## 1.157 2026-08-07 新目标（重建·第 6 次）第 7 轮：合并转发附带留言并入（#538）

| # | 项 | 说明 |
|---|----|------|
| 538 | **合并转发留言并入** | 1.149 合并转发时附带留言单独发第二条。改为附带留言非空时并入合并文本首行（`$note\n$merged`）一次性发送；非合并转发仍单独发送留言 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2733。按用户要求未跑编译/测试。

## 1.158 2026-08-07 新目标（重建·第 6 次）第 8 轮：动态发布框剪贴板粘贴图片（#539）

| # | 项 | 说明 |
|---|----|------|
| 539 | **剪贴板贴图** | 动态发布框只能从相册选图。新增「粘贴图片」AssistChip：读剪贴板首项 —— content:// 图片 Uri 直接 `addImages`；bitmap 项在 IO 线程 `coerceToBitmap` → 解码 PNG 到 cache `attachment-sources/`（file://）→ `addImages`；无图 Toast 提示。新增 explore_paste_images/clipboard_no_image 中英字符串（zh=en=2735） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2735。按用户要求未跑编译/测试。

## 1.159 2026-08-07 新目标（重建·第 6 次）第 9 轮：转发弹窗最近会话快捷选择（#540）

| # | 项 | 说明 |
|---|----|------|
| 540 | **最近会话快捷勾选** | 转发弹窗目标列表为扁平勾选，无快捷入口。列表上方新增「最近会话」FilterChip 行（`filteredForwardTargets.take(6)`，横向滚动，点击勾选/取消），复用现有 recency 排序与多选逻辑 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2735。按用户要求未跑编译/测试。

## 1.160 2026-08-07 新目标（重建·第 6 次）第 10 轮：复制带发送者与时间（#541）

| # | 项 | 说明 |
|---|----|------|
| 541 | **复制含时间** | 复制带发送者（1.73）不含时间。长按菜单新增「复制带发送者与时间」：格式「MM-dd HH:mm 发送者: 内容」（纯图消息省略内容、无发送者省略前缀）。新增 chat_copy_with_sender_time 中英字符串（zh=en=2736） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2736。按用户要求未跑编译/测试。

## 1.161 2026-08-07 新目标（重建·第 6 次）第 11 轮：清空全部收藏（#542）

| # | 项 | 说明 |
|---|----|------|
| 542 | **清空收藏** | 收藏列表只能逐条取消。TopAppBar 新增「清空全部」按钮（`Icons.Outlined.DeleteSweep`，有收藏时显示）+ 确认弹窗；`StarredMessagesViewModel.clearAllStarred` 逐条 `toggleStarMessage`（乐观清空，失败恢复该条）。新增 starred_clear_all/confirm 中英字符串（zh=en=2738） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（StarredMessagesScreen）；check-string-parity zh=en=2738。按用户要求未跑编译/测试。

## 1.162 2026-08-07 新目标（重建·第 6 次）第 12 轮：输入框草稿已恢复提示（#543）

| # | 项 | 说明 |
|---|----|------|
| 543 | **草稿恢复提示** | 草稿从本地恢复填充输入框时，在输入区上方显示「已恢复上次编辑的草稿」横条 + 一键清除（`Icons.Outlined.EditNote`，点清除调 `onInputChange("")` + 新增 `ChatDetailViewModel.clearDraftPersistence` 删除 ChatDraftDao 记录）；状态新增 `hasSavedDraft`（restoreDraft 置 true，onInputChange 置 false）。新增 chat_draft_restored 中英字符串（zh=en=2739） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen/ViewModel/UiModels）；check-string-parity zh=en=2739。按用户要求未跑编译/测试。

## 1.163 2026-08-07 新目标（重建·第 6 次）第 13 轮：群聊气泡下「已读 X/Y」（#544）

| # | 项 | 说明 |
|---|----|------|
| 544 | **群聊已读人数** | 群聊最后一条自己消息气泡下方显示「已读 X/Y」小字：状态新增 `groupReadCounts: Map<String, ReadCountUi>`（read/total）；VM 新增 `loadGroupReadCount`（轻量拉取 `getMessageReadReceipts` 并缓存，失败静默）+ `maybeAutoLoadLastGroupReadCount`（群聊加载完成后自动拉最后一条已送达消息，钩在 loadChat 消息落库后）；`loadReadReceipts` 成功时同步刷新缓存。UI 在 ChatItem.Msg 分支按 `lastOutgoingMsgId` 计算 `groupReadLabel`（Column 包裹气泡 + 右下小字）。新增 chat_group_read_status 中英字符串（zh=en=2740） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen/ViewModel/UiModels）；check-string-parity zh=en=2740。按用户要求未跑编译/测试。

## 1.164 2026-08-07 新目标（重建·第 6 次）第 14 轮：转发弹窗消息内容预览（#545）

| # | 项 | 说明 |
|---|----|------|
| 545 | **转发预览** | 转发目标弹窗顶部新增被转消息内容预览：最多 3 条（媒体类用可读占位 message_preview_*，文本取前 40 字符单行省略），超过 3 条显示「另有 N 条消息」。新增 chat_forward_more_previews 中英字符串（zh=en=2741） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2741。按用户要求未跑编译/测试。

## 1.165 2026-08-07 新目标（重建·第 6 次）第 15 轮：会话列表安全码已变更警告（#546）

| # | 项 | 说明 |
|---|----|------|
| 546 | **安全码变更警告** | 会话列表对身份密钥已变更的单聊显示红色警告图标（`Icons.Outlined.WarningAmber`，tint UnreadRed）：`IdentityTrustDao` 新增 `getAllTrustForUser`（按账号+对端聚合各设备）；`ChatListViewModel` 新增 `identityChangedUserIds: Set<String>` 状态 + `refreshIdentityWarnings()`（纯本地扫描 identity_trust，trustState == "CHANGED" 标记），挂在 loadChats 成功/失败路径；`ChatListItem` 新增 `identityChanged` 参数渲染（contentDescription=安全码已变更）。新增 chat_identity_changed_warning_short 中英字符串（zh=en=2742） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen/ViewModel/IdentityTrustDao）；check-string-parity zh=en=2742。按用户要求未跑编译/测试。

## 1.166 2026-08-07 新目标（重建·第 6 次）第 16 轮：一键部署状态查看 status.sh（#547）

| # | 项 | 说明 |
|---|----|------|
| 547 | **status.sh** | 新增 `scripts/status.sh`：一条命令查看生产状态（服务 compose ps + /health/ready 健康检查 + 最新备份时间），纯只读；`--json` 输出机器可读 JSON（监控/定时任务）。风格与 deploy/backup/restore 一致（compose --env-file、PUBLIC_HOST、HEALTH_TIMEOUT_SECONDS、backups/ 目录）。docs/docker-deployment.md 新增「6.1 一键状态查看」章节。bash -n 全过 |

**验证方式**：bash -n status.sh + 既有 deploy/backup/restore 脚本全部语法通过。按用户要求未跑编译/测试。

## 1.167 2026-08-07 新目标（重建·第 6 次）第 17 轮：群聊已读数发送后刷新 + 缓存修剪（#548）

| # | 项 | 说明 |
|---|----|------|
| 548 | **已读数刷新/修剪** | 补全 1.163：群聊发送成功路径（sendMessage 群分支 finalMessage 落库后）调用 `maybeAutoLoadLastGroupReadCount()`，让新发的最后一条消息立即拉取「已读 X/Y」；`maybeAutoLoadLastGroupReadCount` 开头按最近 20 条消息修剪 `groupReadCounts` 缓存防止无限增长（无变化时不触发更新） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailViewModel）；check-string-parity zh=en=2742。按用户要求未跑编译/测试。

## 1.168 2026-08-07 新目标（重建·第 6 次）第 18 轮：定时消息「立即发送」（#549）

| # | 项 | 说明 |
|---|----|------|
| 549 | **立即发送** | 定时消息列表每条新增「立即发送」按钮（`ScheduledMessagesListSheet` 新增 `onSendNow` 回调 → `ChatDetailViewModel.sendScheduledNow`：取消定时 + 移除本地存储 + 调 `sendMessage`）。`sendMessage` 新增 `forceText: String?` 参数：传入时以该文本发送而不读取输入框、不动草稿/输入框内容（`clearDraft` 与 `inputText=""` 仅当 forceText==null 时生效），走完整加密/提及/封禁路径。新增 schedule_send_now/schedule_sent_now 中英字符串（zh=en=2744） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailViewModel/ChatDetailScreen）；check-string-parity zh=en=2744。按用户要求未跑编译/测试。

## 1.169 2026-08-07 新目标（重建·第 6 次）第 19 轮：聊天消息分享到系统（#550）

| # | 项 | 说明 |
|---|----|------|
| 550 | **消息系统分享** | 消息长按操作菜单新增「分享到系统」（`Intent.ACTION_SEND` + createChooser，text/plain；非 Activity 上下文加 FLAG_ACTIVITY_NEW_TASK）：文本/Markdown 用 `ChatMarkdown.toPlainText`，媒体类用可读占位；密聊受 `SECRET_COPY_BLOCK` 门控防外泄（与复制同款守卫）。新增 chat_share_message/chat_share_message_title 中英字符串（zh=en=2746） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2746。按用户要求未跑编译/测试。

## 1.170 2026-08-07 新目标（重建·第 6 次）第 20 轮：通知中心长按清除该会话通知（#551）

| # | 项 | 说明 |
|---|----|------|
| 551 | **清会话通知** | 通知中心长按某条通知 → 确认弹窗 → 清除该会话全部通知：`NotificationRow` 支持 `onLongClick`（combinedClickable，无 chatId 时不启用）；列表侧 `item.extra["chatId"]` 非空时接长按 → `clearChatId` 状态 + AlertDialog；ViewModel 新增 `removeChat(chatId)`（调 `repo.removeChatItems` + `dismissTrayFor` 同步收托盘）。新增 notif_center_clear_chat_* 中英字符串（zh=en=2749） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（NotificationCenterScreen）；check-string-parity zh=en=2749。按用户要求未跑编译/测试。

## 1.171 2026-08-07 新目标（重建·第 6 次）第 21 轮：会话列表长按清除本地记录（#552）

| # | 项 | 说明 |
|---|----|------|
| 552 | **清本地记录** | 会话列表长按菜单新增「清除本地记录」（红色，走确认弹窗）：`ChatListViewModel.clearLocalChatHistory(chatId)` 复用 ChatDetail 同款本地清理（取消附件传输、清 ScheduledMessageStore、删消息/搜索索引/attachment wire 内容/媒体缓存/通知中心该会话条目 + cancelMessage、clearChatCursors、刷新 chat lastMessage 缓存后 loadChats），保留会话/PIN/草稿，服务端密文仍在重开再同步。新增 chat_clear_local_history(_confirm) 中英字符串（zh=en=2751） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen/ViewModel）；check-string-parity zh=en=2751。按用户要求未跑编译/测试。

## 1.172 2026-08-07 新目标（重建·第 6 次）第 22 轮：动态卡片屏蔽该作者（#553）

| # | 项 | 说明 |
|---|----|------|
| 553 | **屏蔽作者** | 动态卡片他人动态操作区新增「屏蔽该作者」（`Icons.Outlined.Block`）：`ExploreViewModel.blockPostAuthor(userId)` 调 `ApiService.blockUser`（自屏蔽拦截提示），成功后从流与详情移除该作者动态 + infoMessage；`PostCard` 新增 `onBlock` 回调接线。新增 explore_block_author/_done/_failed/_self 中英字符串（zh=en=2755） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen/ViewModel）；check-string-parity zh=en=2755。按用户要求未跑编译/测试。

## 1.173 2026-08-07 新目标（重建·第 6 次）第 23 轮：动态详情页屏蔽该作者（#554）

| # | 项 | 说明 |
|---|----|------|
| 554 | **详情页屏蔽** | 动态详情页作者行尾部为他人动态新增「屏蔽该作者」图标（`Icons.Outlined.Block`，非本人显示）：`PostDetailScreen` 复用 `viewModel.blockPostAuthor`，成功后详情从 `detailPost` 移除并回 infoMessage。复用 1.172 中英字符串（zh=en=2755 不变） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2755。按用户要求未跑编译/测试。

## 1.174 2026-08-07 新目标（重建·第 6 次）第 24 轮：定时消息「全部取消」（#555）

| # | 项 | 说明 |
|---|----|------|
| 555 | **全部取消** | 定时消息列表标题旁新增「全部取消」（红色，有定时时显示）+ 确认弹窗：`ScheduledMessagesListSheet` 新增 `onCancelAll` 回调 + 内部确认 AlertDialog；VM 新增 `cancelAllScheduledMessages()`（`ScheduledMessageStore.clearForChat` 全清 + 逐个 cancel 调度 + refresh + infoMessage）。新增 schedule_cancel_all/_confirm/_cancelled_all 中英字符串（zh=en=2758） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen/ViewModel）；check-string-parity zh=en=2758。按用户要求未跑编译/测试。

## 1.175 2026-08-07 新目标（重建·第 6 次）第 25 轮：输入框「回车发送」开关（#556）

| # | 项 | 说明 |
|---|----|------|
| 556 | **回车发送** | 新增 `util/ComposerPreferences.kt`（账号隔离 SharedPreferences）：`enterToSend` 布尔。设置→通用新增「回车发送」SwitchRow（标题+副标题）；`ChatInputBar` 输入框按偏好切换（开=单行 + ImeAction.Send + KeyboardActions.onSend；关=多行回车换行）。新增 general_enter_to_send_title/_subtitle 中英字符串（zh=en=2760） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（SettingsSubScreens/ChatDetailScreen）；check-string-parity zh=en=2760。按用户要求未跑编译/测试。

## 1.176 2026-08-07 新目标（重建·第 7 次）第 1 轮：语音消息未读红点（#557）

| # | 项 | 说明 |
|---|----|------|
| 557 | **语音未读红点** | Telegram 式：他人语音消息未播放时气泡下方显示红色小点（8dp 圆点），播放自然完成后消失。新增 `util/VoicePlayedStore.kt`（账号隔离 SharedPreferences，`isPlayed/markPlayed`）；`VoicePlayer.setOnCompletionListener` 自然完成时 `markPlayed`；`MessageBubble.VoiceBubble` 用 `remember(message.id)` 读状态，`!isOwnMessage && !isVoicePlayed && !isThisPlaying` 时渲染 `UnreadRed` 圆点 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（MessageBubble/VoicePlayer）；check-string-parity zh=en=2760。按用户要求未跑编译/测试。

## 1.177 2026-08-07 新目标（重建·第 7 次）第 2 轮：动态发布框「已恢复草稿」提示（#558）

| # | 项 | 说明 |
|---|----|------|
| 558 | **发布草稿提示** | 动态发布框打开时如恢复上次未发布草稿，流顶部显示「已恢复上次未发布草稿」横条 + 关闭按钮（复用 `EditNote`/`Secondary`）：`ExploreUiState` 新增 `composerDraftRestored`（init 时由 `readDraftComposer().isNotBlank()` 置位），`onComposerTextChange`/发布成功后清除；VM 新增 `dismissComposerDraftHint()`。新增 explore_composer_draft_restored 中英字符串（zh=en=2761） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen/ViewModel）；check-string-parity zh=en=2761。按用户要求未跑编译/测试。

## 1.178 2026-08-07 新目标（重建·第 7 次）第 3 轮：动态流「回到最新」浮窗（#559）

| # | 项 | 说明 |
|---|----|------|
| 559 | **回到最新** | 动态流滚动超过 4 项后右下角显示「回到最新」`SmallFloatingActionButton`（`KeyboardArrowUp`，点击 `listState.animateScrollToItem(0)`）：新增 `showScrollToTop` 状态 + `snapshotFlow { firstVisibleItemIndex }` 追踪。新增 explore_back_to_top 中英字符串（zh=en=2762） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2762。按用户要求未跑编译/测试。

## 1.179 2026-08-07 新目标（重建·第 7 次）第 4 轮：status.sh 持续监控模式 --watch（#560）

| # | 项 | 说明 |
|---|----|------|
| 560 | **status --watch** | `scripts/status.sh --watch` 持续监控：每 N 秒（`STATUS_WATCH_INTERVAL`，默认 5s）清屏重跑只读状态一览，Ctrl-C 退出（INT trap）；`--json` 分支保持互斥（--watch 优先）。docs/docker-deployment.md 6.1 补充 --watch 说明。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.180 2026-08-07 新目标（重建·第 7 次）第 5 轮：群聊「已读 X/Y」可点击打开阅读详情（#561）

| # | 项 | 说明 |
|---|----|------|
| 561 | **已读点击** | 1.163 的群聊最后一条自己消息「已读 X/Y」小字可点击：点击后 `loadReadReceipts(message.id)` + `messageForReadReceipts = message` 打开阅读详情弹窗（与状态图标点击同款路径）。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2762。按用户要求未跑编译/测试。

## 1.181 2026-08-07 新目标（重建·第 7 次）第 6 轮：动态详情页复制正文（#562）

| # | 项 | 说明 |
|---|----|------|
| 562 | **详情复制** | 动态详情页操作行新增「复制正文」图标（`ContentCopy`，分享按钮前）：文本 + 纯图 `[图片]` 拼接复制到剪贴板 + Toast。新增 explore_copied 中英字符串（zh=en=2763） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2763。按用户要求未跑编译/测试。

## 1.182 2026-08-07 新目标（重建·第 7 次）第 7 轮：会话列表点击未读角标标记已读（#563）

| # | 项 | 说明 |
|---|----|------|
| 563 | **角标已读** | 会话列表有未读时，直接点击红色未读角标（不进入会话）即可标记已读：`ChatListItem` 新增 `onBadgeClick` 参数（Box clickable），列表侧接 `viewModel.toggleMarkedUnread(chat)`（与长按菜单「标记已读」同款）。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen）；check-string-parity zh=en=2763。按用户要求未跑编译/测试。

## 1.183 2026-08-07 新目标（重建·第 7 次）第 8 轮：评论头像/名字点击打开作者主页（#564）

| # | 项 | 说明 |
|---|----|------|
| 564 | **评论作者跳转** | 动态评论区每条评论的头像与作者名可点击打开作者主页：`CommentsDialog` 新增 `onOpenAuthor` 参数，评论 Row 的 Avatar（支持 modifier）+名字加 clickable；列表侧接 `onOpenAuthor`（与动态卡片作者行同款）。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2763。按用户要求未跑编译/测试。

## 1.184 2026-08-07 新目标（重建·第 7 次）第 9 轮：点赞者列表点击打开作者主页（#565）

| # | 项 | 说明 |
|---|----|------|
| 565 | **点赞者跳转** | 动态点赞者弹窗每行可点击打开作者主页：`LikersDialog` 新增 `onOpenUser` 参数，点赞者 Row 加 clickable；列表侧接 `onOpenAuthor`。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2763。按用户要求未跑编译/测试。

## 1.185 2026-08-07 新目标（重建·第 7 次）第 10 轮：会话列表长按「查看共享媒体」（#566）

| # | 项 | 说明 |
|---|----|------|
| 566 | **列表媒体入口** | 会话列表长按菜单新增「查看共享媒体」→ 直达 `Routes.mediaCenter(chatId)`：`ChatListScreen` 新增 `onOpenMediaCenter` 参数 + 菜单项；NavGraph 三个 ChatListScreen 调用点（主 Tab / 双窗格主列 / 双窗格副列）均接线（launchSingleTop）。新增 chat_view_shared_media 中英字符串（zh=en=2764） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen/NavGraph）；check-string-parity zh=en=2764。按用户要求未跑编译/测试。

## 1.186 2026-08-07 新目标（重建·第 7 次）第 11 轮：评论输入框回车发送（#567）

| # | 项 | 说明 |
|---|----|------|
| 567 | **评论回车发送** | 动态评论弹窗输入框（已 singleLine）补 `KeyboardOptions(ImeAction.Send)` + `KeyboardActions(onSend=...)`：键盘「发送」键可直接发评论（空文本/发送中不触发）。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2764。按用户要求未跑编译/测试。

## 1.187 2026-08-07 新目标（重建·第 7 次）第 12 轮：backup.sh --keep N 保留覆盖（#568）

| # | 项 | 说明 |
|---|----|------|
| 568 | **--keep N** | `backup-production.sh --keep N` 命令行覆盖备份保留份数（优先级最高：`--keep` > `BACKUP_KEEP` env > `.env` > 默认 14）；用法与参数校验（非法值报错 Usage）。docs/docker-deployment.md 6 补充 --keep 示例。bash -n 通过 |

**验证方式**：bash -n backup/status/deploy 全部语法通过。按用户要求未跑编译/测试。

## 1.188 2026-08-07 新目标（重建·第 7 次）第 13 轮：聊天详情「回到最新」浮窗（#569）

| # | 项 | 说明 |
|---|----|------|
| 569 | **回到最新** | 聊天详情向上翻阅历史（firstVisibleItemIndex>5）时显示「回到最新」`SmallFloatingActionButton`（`KeyboardArrowDown`，点击 `animateScrollToItem(0)`，reverseLayout 下回到最新一条）：新增 `showJumpToLatest` + `snapshotFlow` 追踪。新增 chat_jump_to_latest 中英字符串（zh=en=2765）。顺带修复 values/strings.xml 第 1521 行两条字符串误合并问题 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2765。按用户要求未跑编译/测试。

## 1.189 2026-08-07 新目标（重建·第 7 次）第 14 轮：update.sh --skip-backup/--skip-verify（#570）

| # | 项 | 说明 |
|---|----|------|
| 570 | **update 跳过** | `update.sh --skip-backup` 跳过更新前自动备份、`--skip-verify` 跳过离线拓扑校验（默认都执行；未知参数报错退出 2）。docs/docker-deployment.md 8 补充用法。bash -n 通过 |

**验证方式**：bash -n update.sh 语法通过。按用户要求未跑编译/测试。

## 1.190 2026-08-07 新目标（重建·第 7 次）第 15 轮：点赞者列表在线状态（#571）

| # | 项 | 说明 |
|---|----|------|
| 571 | **点赞者在线** | 动态点赞者弹窗每行头像显示在线绿点（`Avatar isOnline`），在线者名字旁补「在线」小字（复用 chat_online 字符串）。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2765。按用户要求未跑编译/测试。

## 1.191 2026-08-07 新目标（重建·第 7 次）第 16 轮：restore.sh --dry-run 校验模式（#572）

| # | 项 | 说明 |
|---|----|------|
| 572 | **restore 校验** | `restore-production.sh --dry-run <backup>` 只校验备份（文件完整性 + SHA-256 + 归档路径安全），不停止服务、不写入，校验通过即退出 0；用法与参数校验更新。docs/docker-deployment.md 6 补充示例。bash -n 通过 |

**验证方式**：bash -n restore-production.sh 语法通过。按用户要求未跑编译/测试。

## 1.192 2026-08-07 新目标（重建·第 7 次）第 17 轮：动态流「只看图片」过滤（#573）

| # | 项 | 说明 |
|---|----|------|
| 573 | **只看图片** | 动态流搜索行新增「只看图片」FilterChip（与「只看我的」并列，可叠加）：`showOnlyMedia` 状态 + `filteredPosts` 按 `post.imageUrls.isNotEmpty()` 过滤。新增 explore_feed_only_media 中英字符串（zh=en=2766） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2766。按用户要求未跑编译/测试。

## 1.193 2026-08-07 新目标（重建·第 7 次）第 18 轮：status.sh 显示代码版本（#574）

| # | 项 | 说明 |
|---|----|------|
| 574 | **status 版本** | `status.sh` 新增代码版本行：`git describe --always --tags --dirty`（非 git 仓库显示 `-`），普通输出在健康检查前显示，`--json` 增加 `version` 字段（监控可核对部署版本）。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.194 2026-08-07 新目标（重建·第 7 次）第 19 轮：未读 AI 摘要复制（#575）

| # | 项 | 说明 |
|---|----|------|
| 575 | **摘要复制** | 未读 AI 摘要横幅新增「复制」图标（`ContentCopy`，摘要非空时显示）：`UnreadSummaryBanner` 新增 `onCopy` 参数，列表侧复制摘要到剪贴板 + 已复制 Toast（复用 chat_copied/chat_copy）。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2766。按用户要求未跑编译/测试。

## 1.195 2026-08-07 新目标（重建·第 7 次）第 20 轮：verify 拓扑补 block 路由回归（#576）

| # | 项 | 说明 |
|---|----|------|
| 576 | **block 回归** | `verify-production-topology.sh --live` 新增用户屏蔽路由存在性回归：无凭据探 `GET /api/users/block/_probe_`，404 即 fail（App 1.172 动态屏蔽依赖 POST /api/users/block/{userId}）；与 likers 回归同风格。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.196 2026-08-07 新目标（重建·第 7 次）第 21 轮：发布成功后自动滚动流到顶部（#577）

| # | 项 | 说明 |
|---|----|------|
| 577 | **发布滚顶** | 动态发布成功后自动滚动流到顶部（新动态在列表头部）：`ExploreUiState` 新增 `publishRevision` 计数器（发布成功 +1），ExploreScreen `LaunchedEffect(publishRevision)` 触发 `listState.animateScrollToItem(0)`。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen/ViewModel）；check-string-parity zh=en=2766。按用户要求未跑编译/测试。

## 1.197 2026-08-07 新目标（重建·第 7 次）第 22 轮：图片消息系统分享原图（#578）

| # | 项 | 说明 |
|---|----|------|
| 578 | **分享原图** | 消息「分享到系统」增强：图片/GIF 且本地媒体可读（`MediaCache.isReadableLocalUri`）时改走 `ACTION_SEND image/*` + `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION` 分享真实图片；否则回退文本分享（原逻辑）。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2766。按用户要求未跑编译/测试。

## 1.198 2026-08-07 新目标（重建·第 7 次）第 23 轮：分享原图扩展到视频/文件（#579）

| # | 项 | 说明 |
|---|----|------|
| 579 | **分享文件** | 1.197 的原图分享扩展到视频与文件：按类型取 mime（image/*、video/*、application/octet-stream），本地媒体可读时 `ACTION_SEND` + `EXTRA_STREAM` + READ 授权分享真实文件；否则回退文本分享。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2766。按用户要求未跑编译/测试。

## 1.199 2026-08-07 新目标（重建·第 7 次）第 24 轮：评论区「楼主/作者」徽章（#580）

| # | 项 | 说明 |
|---|----|------|
| 580 | **作者徽章** | 动态评论弹窗中，动态作者自己的评论在作者名旁显示「作者」徽章（`Primary` 淡底小标签，与名字同行）：`CommentsDialog` 新增 `postAuthorId` 参数（列表侧从 detailPost/posts 取），名字行改为 Row 包裹 + 徽章。新增 explore_comment_author_badge 中英字符串（zh=en=2767） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2767。按用户要求未跑编译/测试。

## 1.200 2026-08-07 新目标（重建·第 7 次）第 25 轮：详情页评论区作者徽章（#581）

| # | 项 | 说明 |
|---|----|------|
| 581 | **详情作者徽章** | 动态详情页内联评论区同样显示「作者」徽章（评论者即动态作者时，作者名旁同行淡主色标签）：`PostDetailScreen` 评论行名字改为 Row + 徽章（复用 explore_comment_author_badge 字符串，zh=en=2767 不变） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2767。按用户要求未跑编译/测试。

## 1.201 2026-08-07 新目标（重建·第 8 次）第 1 轮：status.sh 显示备份份数（#582）

| # | 项 | 说明 |
|---|----|------|
| 582 | **备份份数** | `status.sh` 备份区新增「份数」：普通输出在最新备份上方显示备份目录计数，`--json` 的 `backup` 对象增加 `count` 字段（监控核对保留策略）。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.202 2026-08-07 新目标（重建·第 8 次）第 2 轮：发布框「清空」按钮（#583）

| # | 项 | 说明 |
|---|----|------|
| 583 | **发布清空** | 动态发布框标题行新增「清空」按钮（有文本或已选图片时显示）：`ComposerCard` 新增 `onClear` 参数，VM 新增 `clearComposer()`（清空文本 + `imageDrafts` + 持久化草稿）。新增 explore_composer_clear 中英字符串（zh=en=2768） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen/ViewModel）；check-string-parity zh=en=2768。按用户要求未跑编译/测试。

## 1.203 2026-08-07 新目标（重建·第 8 次）第 3 轮：评论头像在线绿点（#584）

| # | 项 | 说明 |
|---|----|------|
| 584 | **评论在线点** | 动态评论（弹窗 + 详情页内联）作者头像显示在线绿点（`Avatar isOnline`），与点赞者/动态作者一致。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen/ExploreSubScreens）；check-string-parity zh=en=2768。按用户要求未跑编译/测试。

## 1.204 2026-08-07 新目标（重建·第 8 次）第 4 轮：作者主页动态总数（#585）

| # | 项 | 说明 |
|---|----|------|
| 585 | **作者动态数** | 作者主页有动态时，在搜索框上方显示「共 N 条动态」（`state.posts.size`）：新增 `explore_author_post_count` 中英字符串（zh=en=2769） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（AuthorProfileScreen）；check-string-parity zh=en=2769。按用户要求未跑编译/测试。

## 1.205 2026-08-07 新目标（重建·第 8 次）第 5 轮：详情页评论头像点击打开作者主页（#586）

| # | 项 | 说明 |
|---|----|------|
| 586 | **详情评论跳转** | 动态详情页内联评论头像可点击打开作者主页（`onOpenAuthor(c.author.id)`），与评论弹窗（1.183）一致。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2769。按用户要求未跑编译/测试。

## 1.206 2026-08-07 新目标（重建·第 8 次）第 6 轮：status.sh --short 一行摘要（#587）

| # | 项 | 说明 |
|---|----|------|
| 587 | **status 摘要** | `status.sh --short` 输出一行摘要（适合 cron/巡检）：`ready=... version=... services_up=... backups=... latest=...`（services 行数含 running 统计）。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.207 2026-08-07 新目标（重建·第 8 次）第 7 轮：发布框已选图片点击预览大图（#588）

| # | 项 | 说明 |
|---|----|------|
| 588 | **图片预览** | 动态发布框已选图片（非上传中）点击弹出全屏预览（`Dialog` + `AsyncImage` ContentScale.Fit，点击任意处关闭）。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2769。按用户要求未跑编译/测试。

## 1.208 2026-08-07 新目标（重建·第 8 次）第 8 轮：deploy.sh --skip-health-wait（#589）

| # | 项 | 说明 |
|---|----|------|
| 589 | **跳过健康等待** | `deploy.sh --skip-health-wait` 跳过启动后的 /health/ready 等待（CI/bootstrap 场景）；默认仍等待（HEALTH_TIMEOUT_SECONDS 可调），usage 更新。bash -n 通过 |

**验证方式**：bash -n deploy.sh 语法通过。按用户要求未跑编译/测试。

## 1.209 2026-08-07 新目标（重建·第 8 次）第 9 轮：backup.sh --no-prune（#590）

| # | 项 | 说明 |
|---|----|------|
| 590 | **--no-prune** | `backup-production.sh --no-prune` 保留全部备份（跳过保留策略清理）；默认仍按 keep 清理；usage 更新。bash -n 通过 |

**验证方式**：bash -n backup-production.sh 语法通过。按用户要求未跑编译/测试。

## 1.210 2026-08-07 新目标（重建·第 8 次）第 10 轮：restore.sh 停止前校验 compose 配置（#591）

| # | 项 | 说明 |
|---|----|------|
| 591 | **恢复前校验** | `restore-production.sh --confirm` 在停止服务前先 `docker compose config --quiet` 校验配置，无效则中止且不触碰服务（防配置漂移导致停服后拉不起）。bash -n 通过 |

**验证方式**：bash -n restore/deploy/backup/status 全部语法通过。按用户要求未跑编译/测试。

## 1.211 2026-08-07 新目标（重建·第 8 次）第 11 轮：发布图片上传失败重试（#592）

| # | 项 | 说明 |
|---|----|------|
| 592 | **上传重试** | 动态发布框图片上传失败时，覆盖层从错误图标改为「重试」按钮（`Icons.Outlined.Refresh`）：`ComposerCard` 新增 `onRetryImage`，VM 新增 `retryDraftImage(draftId)`（复用原 uri 重新压缩上传，上传中/已成功不重试）。新增 explore_retry_upload 中英字符串（zh=en=2770） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen/ViewModel）；check-string-parity zh=en=2770。按用户要求未跑编译/测试。

## 1.212 2026-08-07 新目标（重建·第 8 次）第 12 轮：status.sh --health-check（#593）

| # | 项 | 说明 |
|---|----|------|
| 593 | **健康退出码** | `status.sh --health-check` 仅按健康状态退出（ready→0，否则→1，打印 ready/not ready），适合监控/cron/告警。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.213 2026-08-07 新目标（重建·第 8 次）第 13 轮：详情页评论作者名点击打开主页（#594）

| # | 项 | 说明 |
|---|----|------|
| 594 | **详情评论名跳转** | 动态详情页内联评论作者名可点击打开作者主页（与头像 1.205、评论弹窗一致）。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2770。按用户要求未跑编译/测试。

## 1.214 2026-08-07 新目标（重建·第 8 次）第 14 轮：status.sh --watch TTY 感知（#595）

| # | 项 | 说明 |
|---|----|------|
| 595 | **watch TTY** | `status.sh --watch` 在非 TTY（管道/cron 日志）下不清屏，改用时间戳分隔线；TTY 下仍清屏。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.215 2026-08-07 新目标（重建·第 8 次）第 15 轮：会话列表长按「查看收藏」（#596）

| # | 项 | 说明 |
|---|----|------|
| 596 | **查看收藏** | 会话列表长按菜单新增「查看收藏」→ `Routes.starredMessages(chatId)`：`ChatListScreen` 新增 `onOpenStarredMessages(chatId)` 参数 + 菜单项；NavGraph 三个 ChatListScreen 调用点接线（launchSingleTop；既有 1481 无参 lambda 兼容）。新增 chat_view_starred 中英字符串（zh=en=2771） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen/NavGraph）；check-string-parity zh=en=2771。按用户要求未跑编译/测试。

## 1.216 2026-08-07 新目标（重建·第 8 次）第 16 轮：update.sh 透传 deploy 参数（#597）

| # | 项 | 说明 |
|---|----|------|
| 597 | **透传参数** | `update.sh` 除 `--skip-backup/--skip-verify` 外的参数透传给 `deploy.sh`（如 `update.sh --skip-health-wait`），并在输出中提示透传的参数。bash -n 通过 |

**验证方式**：bash -n update.sh/deploy.sh 语法通过。按用户要求未跑编译/测试。

## 1.217 2026-08-07 新目标（重建·第 8 次）第 17 轮：restore.sh 支持 SKIP_HEALTH_WAIT（#598）

| # | 项 | 说明 |
|---|----|------|
| 598 | **restore 跳过等待** | `restore-production.sh` 支持 `SKIP_HEALTH_WAIT=true` 跳过恢复后的健康等待（与 deploy.sh --skip-health-wait 一致）；默认仍等待。bash -n 通过 |

**验证方式**：bash -n restore-production.sh 语法通过。按用户要求未跑编译/测试。

## 1.218 2026-08-07 新目标（重建·第 8 次）第 18 轮：verify 离线补 block 路由回归（#599）

| # | 项 | 说明 |
|---|----|------|
| 599 | **离线 block 回归** | `verify-production-topology.sh --offline` 新增服务端源码含 `/api/users/block/{userId}` 路由的检查（`rg server/src`，App 1.172 依赖），缺失即 fail。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.219 2026-08-07 新目标（重建·第 8 次）第 19 轮：restore --dry-run 同时校验 compose 配置（#600）

| # | 项 | 说明 |
|---|----|------|
| 600 | **dry-run 校验** | `restore-production.sh --dry-run` 在备份完整性校验之外，同时 `docker compose config --quiet` 校验（失败即中止），确保真实恢复前配置可用。bash -n 通过 |

**验证方式**：bash -n restore-production.sh 语法通过。按用户要求未跑编译/测试。

## 1.220 2026-08-07 新目标（重建·第 8 次）第 20 轮：备份 METADATA 记录代码版本（#601）

| # | 项 | 说明 |
|---|----|------|
| 601 | **备份版本** | `backup-production.sh` 在 METADATA.txt 中写入 `code_version`（git describe，非 git 仓库为 `-`），恢复时可核对部署版本。bash -n 通过 |

**验证方式**：bash -n backup-production.sh 语法通过。按用户要求未跑编译/测试。

## 1.221 2026-08-07 新目标（重建·第 8 次）第 21 轮：status.sh 显示最新备份代码版本（#602）

| # | 项 | 说明 |
|---|----|------|
| 602 | **备份版本显示** | `status.sh` 读取最新备份 METADATA 的 `code_version` 并显示（普通输出「备份代码版本」行，`--json` backup 对象增加 `code_version` 字段）。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.222 2026-08-07 新目标（重建·第 8 次）第 22 轮：status.sh --short 增加备份版本（#603）

| # | 项 | 说明 |
|---|----|------|
| 603 | **摘要备份版本** | `status.sh --short` 一行摘要增加 `latest_backup_version=` 字段（读取最新备份 METADATA code_version）。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.223 2026-08-07 新目标（重建·第 8 次）第 23 轮：会话列表长按复制会话名称（#604）

| # | 项 | 说明 |
|---|----|------|
| 604 | **复制会话名** | 会话列表长按菜单新增「复制会话名称」：按群名/对方昵称复制到剪贴板 + 已复制 Toast。新增 chat_copy_chat_name 中英字符串（zh=en=2772） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen）；check-string-parity zh=en=2772。按用户要求未跑编译/测试。

## 1.224 2026-08-07 新目标（重建·第 8 次）第 24 轮：status.sh --watch 透传其他参数（#605）

| # | 项 | 说明 |
|---|----|------|
| 605 | **watch 透传** | `status.sh --watch` 与其他参数（`--short`/`--json`）组合时，内部调用透传这些参数（`--watch` 自身除外）。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.225 2026-08-07 新目标（重建·第 8 次）第 25 轮：status.sh --json 增加 generated_at（#606）

| # | 项 | 说明 |
|---|----|------|
| 606 | **json 时间戳** | `status.sh --json` 增加 `generated_at`（UTC ISO 时间戳），便于监控侧判断数据新鲜度。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.226 2026-08-07 新目标（重建·第 9 次）第 1 轮：restore 后核对备份/当前代码版本（#607）

| # | 项 | 说明 |
|---|----|------|
| 607 | **恢复版本核对** | `restore-production.sh` 完成后读取备份 METADATA 的 `code_version` 与当前 git 版本比较，不一致则 WARN（提示数据可能来自旧部署）。bash -n 通过 |

**验证方式**：bash -n restore-production.sh 语法通过。按用户要求未跑编译/测试。

## 1.227 2026-08-07 新目标（重建·第 9 次）第 2 轮：会话列表草稿铅笔图标（#608）

| # | 项 | 说明 |
|---|----|------|
| 608 | **草稿图标** | 会话列表有草稿（且非定时/输入中）时，预览前显示 `EditNote` 铅笔小图标。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen）；check-string-parity zh=en=2772。按用户要求未跑编译/测试。

## 1.228 2026-08-07 新目标（重建·第 9 次）第 3 轮：restore --dry-run 打印备份元数据（#609）

| # | 项 | 说明 |
|---|----|------|
| 609 | **dry-run 元数据** | `restore-production.sh --dry-run` 校验通过后打印备份路径、created_at_utc、code_version（来自 METADATA.txt），便于核对。bash -n 通过 |

**验证方式**：bash -n restore-production.sh 语法通过。按用户要求未跑编译/测试。

## 1.229 2026-08-07 新目标（重建·第 9 次）第 4 轮：verify block 路由无鉴权 200 回归（#610）

| # | 项 | 说明 |
|---|----|------|
| 610 | **block 鉴权回归** | `verify-production-topology.sh --live` 的 block 路由探针：若返回 200（无凭据成功）即 fail（路由未鉴权）；404 fail 保持不变。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.230 2026-08-07 新目标（重建·第 9 次）第 5 轮：backup.sh --tag NAME 备份后缀（#611）

| # | 项 | 说明 |
|---|----|------|
| 611 | **--tag 备份名** | `backup-production.sh --tag NAME` 给备份目录加可读后缀（`maodouchat-$timestamp-$tag`），便于人工识别；参数改为 while 循环解析（支持组合 `--keep N --no-prune --tag X`），未知参数报错。bash -n 通过 |

**验证方式**：bash -n backup/restore/status 全部语法通过。按用户要求未跑编译/测试。

## 1.231 2026-08-07 新目标（重建·第 9 次）第 6 轮：status.sh --watch --interval N（#612）

| # | 项 | 说明 |
|---|----|------|
| 612 | **watch 间隔参数** | `status.sh --watch --interval N` 命令行指定刷新秒数（需正整数，非法报错退出 2；默认 STATUS_WATCH_INTERVAL/5s），`--watch`/`--interval` 不透传给内部调用。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.232 2026-08-07 新目标（重建·第 9 次）第 7 轮：收藏页搜索关键词高亮（#613）

| # | 项 | 说明 |
|---|----|------|
| 613 | **收藏搜索高亮** | 收藏页搜索时，消息预览文本高亮匹配关键词（复用 `GlobalSearchTextHighlight.buildSnippet`，风格与 ChatList 一致）：`StarredMessageRow` 新增 `searchQuery` 参数 + `highlightedText` helper。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（StarredMessagesScreen）；check-string-parity zh=en=2772。按用户要求未跑编译/测试。

## 1.233 2026-08-07 新目标（重建·第 9 次）第 8 轮：verify 离线校验运维脚本存在（#614）

| # | 项 | 说明 |
|---|----|------|
| 614 | **脚本存在校验** | `verify-production-topology.sh --offline` 校验 6 个运维脚本存在（deploy/backup/restore/update/status/verify），缺失即 fail；可执行位缺失不视为失败（Windows 检出兼容）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.234 2026-08-07 新目标（重建·第 9 次）第 9 轮：deploy.sh --health-timeout N（#615）

| # | 项 | 说明 |
|---|----|------|
| 615 | **健康超时参数** | `deploy.sh --health-timeout N` 命令行覆盖健康等待超时秒数（需正整数，非法报错退出 2；等效 HEALTH_TIMEOUT_SECONDS）。usage 更新。bash -n 通过 |

**验证方式**：bash -n deploy.sh 语法通过。按用户要求未跑编译/测试。

## 1.235 2026-08-07 新目标（重建·第 9 次）第 10 轮：详情页评论双击点赞（#616）

| # | 项 | 说明 |
|---|----|------|
| 616 | **评论双击点赞** | 动态详情页评论内容双击点赞（`combinedClickable` + onDoubleClick → `toggleCommentLike(c)`，与动态卡片双击点赞一致）。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2772。按用户要求未跑编译/测试。

## 1.236 2026-08-07 新目标（重建·第 9 次）第 11 轮：verify likers 路由无鉴权 200 回归（#617）

| # | 项 | 说明 |
|---|----|------|
| 617 | **likers 鉴权回归** | `verify-production-topology.sh --live` 的 likers 路由探针：若返回 200（无凭据成功）即 fail（路由未鉴权），与 block 路由一致。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.237 2026-08-07 新目标（重建·第 9 次）第 12 轮：语音开始播放即标记已播（#618）

| # | 项 | 说明 |
|---|----|------|
| 618 | **播放即已读** | `VoicePlayer` 在开始播放（prepared + start）时即 `VoicePlayedStore.markPlayed`（1.176 只做自然完成时标记），未读红点在开始播放时立即消失。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（VoicePlayer）；check-string-parity zh=en=2772。按用户要求未跑编译/测试。

## 1.238 2026-08-07 新目标（重建·第 9 次）第 13 轮：backup.sh --dry-run（#619）

| # | 项 | 说明 |
|---|----|------|
| 619 | **backup dry-run** | `backup-production.sh --dry-run` 只校验 docker + compose 配置可用并打印将创建的备份目录，不停止服务、不写备份。bash -n 通过 |

**验证方式**：bash -n backup-production.sh 语法通过。按用户要求未跑编译/测试。

## 1.239 2026-08-07 新目标（重建·第 9 次）第 14 轮：backup.sh --list 列出备份（#620）

| # | 项 | 说明 |
|---|----|------|
| 620 | **备份列表** | `backup-production.sh --list` 只读列出全部备份（目录名 + created_at + code_version，来自 METADATA），不停止服务。bash -n 通过 |

**验证方式**：bash -n backup-production.sh 语法通过。按用户要求未跑编译/测试。

## 1.240 2026-08-07 新目标（重建·第 9 次）第 15 轮：备份 dump 完整性校验（#621）

| # | 项 | 说明 |
|---|----|------|
| 621 | **dump 校验** | `backup-production.sh` 创建 database.dump 后用 `pg_restore --list` 校验其可读性（损坏即 fail 中止，cleanup 清理 partial 并恢复服务）。bash -n 通过 |

**验证方式**：bash -n backup-production.sh 语法通过。按用户要求未跑编译/测试。

## 1.241 2026-08-07 新目标（重建·第 9 次）第 16 轮：restore 前 pg_restore --list 校验 dump（#622）

| # | 项 | 说明 |
|---|----|------|
| 622 | **恢复前 dump 校验** | `restore-production.sh --confirm` 在停止服务前用 `pg_restore --list` 校验 database.dump 可读（损坏即中止，不触碰服务），与 1.240 备份侧校验呼应。bash -n 通过 |

**验证方式**：bash -n restore-production.sh 语法通过。按用户要求未跑编译/测试。

## 1.242 2026-08-07 新目标（重建·第 9 次）第 17 轮：status.sh --json 增加 services_up（#623）

| # | 项 | 说明 |
|---|----|------|
| 623 | **json 服务数** | `status.sh --json` 增加 `services_up`（running 服务数，与 --short 的 services_up 一致），监控可直接消费。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.243 2026-08-07 新目标（重建·第 9 次）第 18 轮：收藏消息长按复制（#624）

| # | 项 | 说明 |
|---|----|------|
| 624 | **收藏复制** | 收藏页消息行长按复制内容到剪贴板（`StarredMessageRow` 新增 `onCopy`，combinedClickable onLongClick）+ 已复制 Toast。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（StarredMessagesScreen）；check-string-parity zh=en=2772。按用户要求未跑编译/测试。

## 1.244 2026-08-07 新目标（重建·第 9 次）第 19 轮：会话列表静音会话排序靠后（#625）

| # | 项 | 说明 |
|---|----|------|
| 625 | **静音靠后** | 会话列表排序在未读优先关闭时增加最后一级 tiebreaker：时间相近时静音会话排在非静音之后（微信式；未读优先开启时 activityScore 已含 muted 权重）。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListViewModel）；check-string-parity zh=en=2772。按用户要求未跑编译/测试。

## 1.245 2026-08-07 新目标（重建·第 9 次）第 20 轮：评论举报（#626）

| # | 项 | 说明 |
|---|----|------|
| 626 | **评论举报** | 动态详情页他人评论新增「举报」图标（`Flag`）：`ExploreViewModel.reportComment` 调 `createReport(targetType="COMMENT")`（服务端已支持 COMMENT target，见 Routing reportRepo），成功/失败 infoMessage 复用。新增 explore_report_comment 中英字符串（zh=en=2773） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreViewModel/ExploreSubScreens）；check-string-parity zh=en=2773。按用户要求未跑编译/测试。

## 1.246 2026-08-07 新目标（重建·第 9 次）第 21 轮：评论弹窗举报按钮（#627）

| # | 项 | 说明 |
|---|----|------|
| 627 | **弹窗举报** | 评论弹窗他人评论同样显示「举报」图标（`Flag`，`CommentsDialog` 新增 `onReportComment` → `viewModel::reportComment`），与详情页 1.245 一致。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2773。按用户要求未跑编译/测试。

## 1.247 2026-08-07 新目标（重建·第 9 次）第 22 轮：详情页评论长按操作菜单（#628）

| # | 项 | 说明 |
|---|----|------|
| 628 | **评论长按菜单** | 动态详情页评论内容长按弹操作菜单（AlertDialog）：回复 / 复制 / 举报（他人）/ 删除（自己）；与双击点赞共存（combinedClickable onDoubleClick+onLongClick）。新增 explore_comment_actions 中英字符串（zh=en=2774） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2774。按用户要求未跑编译/测试。

## 1.248 2026-08-07 新目标（重建·第 9 次）第 23 轮：restore.sh --inspect 列出归档内容（#629）

| # | 项 | 说明 |
|---|----|------|
| 629 | **备份检查** | `restore-production.sh --inspect <backup>` 列出 uploads/caddy-data 归档前 20 项 + database.dump 大小，不执行恢复；用法更新。bash -n 通过 |

**验证方式**：bash -n restore-production.sh 语法通过。按用户要求未跑编译/测试。

## 1.249 2026-08-07 新目标（重建·第 9 次）第 24 轮：会话列表长按复制会话 ID（#630）

| # | 项 | 说明 |
|---|----|------|
| 630 | **复制会话 ID** | 会话列表长按菜单新增「复制会话 ID」（便于反馈/排查）。新增 chat_copy_chat_id 中英字符串（zh=en=2775） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen）；check-string-parity zh=en=2775。按用户要求未跑编译/测试。

## 1.250 2026-08-07 新目标（重建·第 10 次）第 1 轮：评论弹窗双击点赞（#631）

| # | 项 | 说明 |
|---|----|------|
| 631 | **弹窗双击点赞** | 动态评论弹窗评论内容双击点赞（`combinedClickable` onDoubleClick → `onToggleLike(comment)`），与详情页 1.235 一致。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2775。按用户要求未跑编译/测试。

## 1.251 2026-08-07 新目标（重建·第 10 次）第 2 轮：会话列表长按「查看资料」（#632）

| # | 项 | 说明 |
|---|----|------|
| 632 | **查看资料** | 会话列表长按菜单新增「查看资料」：群聊/频道 → `onOpenGroupDetail`，单聊 → `onOpenProfile`（`Routes.authorProfile`）；`ChatListScreen` 新增 `onOpenProfile` 参数，NavGraph 三个调用点接线（launchSingleTop；顺带修复 1449 行语句合并问题）。新增 chat_view_profile 中英字符串（zh=en=2775） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen/NavGraph）；check-string-parity zh=en=2775。按用户要求未跑编译/测试。

## 1.252 2026-08-07 新目标（重建·第 10 次）第 3 轮：verify 离线校验 Caddyfile 压缩（#633）

| # | 项 | 说明 |
|---|----|------|
| 633 | **压缩回归** | `verify-production-topology.sh --offline` 校验 Caddyfile 启用 `encode zstd gzip`（缺省即 fail，带宽/性能回归）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.253 2026-08-07 新目标（重建·第 10 次）第 4 轮：verify 离线校验 db 健康检查（#634）

| # | 项 | 说明 |
|---|----|------|
| 634 | **db 健康检查** | `verify-production-topology.sh --offline` 校验 docker-compose.yml 中 db 健康检查基于 `pg_isready`（缺失即 fail，启动顺序回归）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.254 2026-08-07 新目标（重建·第 10 次）第 5 轮：verify 离线校验 compose restart 策略（#635）

| # | 项 | 说明 |
|---|----|------|
| 635 | **restart 回归** | `verify-production-topology.sh --offline` 校验 docker-compose.yml 中服务 `restart: unless-stopped` 数量 ≥ 3（异常退出自动拉起，缺省即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.255 2026-08-07 新目标（重建·第 10 次）第 6 轮：verify 离线校验 Caddyfile 安全响应头（#636）

| # | 项 | 说明 |
|---|----|------|
| 636 | **安全头回归** | `verify-production-topology.sh --offline` 校验 Caddyfile 含 `X-Content-Type-Options nosniff` 与 `X-Frame-Options DENY`（缺省即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.256 2026-08-07 新目标（重建·第 10 次）第 7 轮：verify 离线校验 deploy.sh 核心 flags（#637）

| # | 项 | 说明 |
|---|----|------|
| 637 | **deploy flags 回归** | `verify-production-topology.sh --offline` 校验 deploy.sh 支持文档承诺的 `--no-build/--skip-health-wait/--health-timeout/--dry-run/--relaxed`（缺省即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.257 2026-08-07 新目标（重建·第 10 次）第 8 轮：verify 离线校验 Caddyfile 请求体限制（#638）

| # | 项 | 说明 |
|---|----|------|
| 638 | **请求体回归** | `verify-production-topology.sh --offline` 校验 Caddyfile 含 `request_body max_size`（缺省即 fail，防上传滥用）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.258 2026-08-07 新目标（重建·第 10 次）第 9 轮：verify 离线校验 compose 关键密钥注入（#639）

| # | 项 | 说明 |
|---|----|------|
| 639 | **密钥注入回归** | `verify-production-topology.sh --offline` 校验 compose 通过 `.env` 注入 `POSTGRES_PASSWORD/JWT_SECRET/PUSH_HMAC_SECRET`（含 `:?` 强制校验，缺省即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.259 2026-08-07 新目标（重建·第 10 次）第 10 轮：verify 离线校验数据持久化命名卷（#640）

| # | 项 | 说明 |
|---|----|------|
| 640 | **命名卷回归** | `verify-production-topology.sh --offline` 校验 compose 用命名卷持久化 `postgres_data`（pg 数据）与 `server_uploads`（上传），容器重建不丢数据（缺省即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.260 2026-08-07 新目标（重建·第 10 次）第 11 轮：verify live 校验 public/status 为合法 JSON（#641）

| # | 项 | 说明 |
|---|----|------|
| 641 | **JSON 回归** | `verify-production-topology.sh --live` 的 public/status 探针增加 JSON 合法性校验（`python3 -c json.load`，解析失败即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.261 2026-08-07 新目标（重建·第 10 次）第 12 轮：compose server 日志轮转 + verify 回归（#642）

| # | 项 | 说明 |
|---|----|------|
| 642 | **日志轮转** | docker-compose.yml server 服务新增 `logging: json-file max-size 20m / max-file 4`（防日志写满磁盘）；`verify-production-topology.sh --offline` 校验 `max-size` 存在。compose config 校验通过（设齐必需 env 后），bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过；docker compose config --quiet 通过（设齐 PUBLIC_HOST/ACME_EMAIL/BASE_URL/密钥）。按用户要求未跑编译/测试。

## 1.262 2026-08-07 新目标（重建·第 10 次）第 13 轮：verify 离线校验 backup/restore/update flags（#643）

| # | 项 | 说明 |
|---|----|------|
| 643 | **脚本 flags 回归** | `verify-production-topology.sh --offline` 校验 backup（--keep/--no-prune/--tag/--dry-run/--list）、restore（--confirm/--dry-run/--inspect）、update（--skip-backup）支持文档承诺的 flags（缺省即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.263 2026-08-07 新目标（重建·第 10 次）第 14 轮：compose server init: true + verify 回归（#644）

| # | 项 | 说明 |
|---|----|------|
| 644 | **init 回归** | docker-compose.yml server 服务新增 `init: true`（僵尸进程回收，长驻更稳）；`verify-production-topology.sh --offline` 校验其存在。compose config 校验通过，bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过；docker compose config --quiet 通过。按用户要求未跑编译/测试。

## 1.264 2026-08-07 新目标（重建·第 10 次）第 15 轮：verify 离线校验容器非 root 用户（#645）

| # | 项 | 说明 |
|---|----|------|
| 645 | **非 root 回归** | `verify-production-topology.sh --offline` 校验 server/Dockerfile 以非 root 用户（`USER maodou`）运行（最小权限，缺省即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.265 2026-08-07 新目标（重建·第 10 次）第 16 轮：verify 离线校验容器加固（#646）

| # | 项 | 说明 |
|---|----|------|
| 646 | **容器加固回归** | `verify-production-topology.sh --offline` 校验 compose server `read_only: true` + `cap_drop` + `no-new-privileges:true`（最小权限，缺省即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.266 2026-08-07 新目标（重建·第 10 次）第 17 轮：verify 离线校验对外端口 env 可配置（#647）

| # | 项 | 说明 |
|---|----|------|
| 647 | **端口可配置回归** | `verify-production-topology.sh --offline` 校验 proxy 端口通过 `${HTTP_PORT}`/`${HTTPS_PORT}` env 可配置（非硬编码，缺省即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.267 2026-08-07 新目标（重建·第 10 次）第 18 轮：会话列表长按「全部已读」（#648）

| # | 项 | 说明 |
|---|----|------|
| 648 | **全部已读** | 会话列表长按菜单（有未读时）新增「全部已读」→ `viewModel.markAllUnreadChatsRead()`。新增 chat_mark_all_read 中英字符串（zh=en=2776）；顺带清理 values/values-en 中 `chat_view_profile` 重复条目（各保留 1 个） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen）；check-string-parity zh=en=2776（chat_view_profile 已去重）。按用户要求未跑编译/测试。

## 1.268 2026-08-07 新目标（重建·第 10 次）第 19 轮：通知中心长按操作菜单（复制/清除会话）（#649）

| # | 项 | 说明 |
|---|----|------|
| 649 | **通知操作菜单** | 通知中心长按某条通知改为弹操作菜单：复制通知内容（标题+正文到剪贴板 + Toast）/ 清除该会话全部通知（转 1.170 确认弹窗）。新增 notif_center_actions 中英字符串（zh=en=2777） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（NotificationCenterScreen）；check-string-parity zh=en=2777。按用户要求未跑编译/测试。

## 1.269 2026-08-07 新目标（重建·第 10 次）第 20 轮：status.sh --json 增加 services_total（#650）

| # | 项 | 说明 |
|---|----|------|
| 650 | **json 服务总数** | `status.sh --json` 增加 `services_total`（compose ps 行数），与 `services_up` 配套供监控计算就绪比例。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.270 2026-08-07 新目标（重建·第 10 次）第 21 轮：评论弹窗搜索关键词高亮（#651）

| # | 项 | 说明 |
|---|----|------|
| 651 | **评论搜索高亮** | 评论弹窗搜索时，匹配的评论内容高亮关键词（复用 `GlobalSearchTextHighlight.buildSnippet`，风格与 ChatList/收藏页一致）；`ExploreScreen` 新增 `highlightedText` helper。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreScreen）；check-string-parity zh=en=2777。按用户要求未跑编译/测试。

## 1.271 2026-08-07 新目标（重建·第 10 次）第 22 轮：verify 离线校验 Caddyfile 访问日志（#652）

| # | 项 | 说明 |
|---|----|------|
| 652 | **访问日志回归** | `verify-production-topology.sh --offline` 校验 Caddyfile 含 `log {` 访问日志块（排障可观测性，缺省即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.272 2026-08-07 新目标（重建·第 10 次）第 23 轮：发布框清空恢复默认可见范围（#653）

| # | 项 | 说明 |
|---|----|------|
| 653 | **清空恢复默认** | `ExploreViewModel.clearComposer` 清空文本/图片/草稿的同时，恢复默认可见范围（`defaultPostVisibility`，nullable 兜底当前值；`useDefaultPostVisibility=true`）。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreViewModel）；check-string-parity zh=en=2777。按用户要求未跑编译/测试。

## 1.273 2026-08-07 新目标（重建·第 10 次）第 24 轮：status.sh --json 增加备份年龄（#654）

| # | 项 | 说明 |
|---|----|------|
| 654 | **备份年龄** | `status.sh --json` 的 backup 对象增加 `age_hours`（最新备份距今小时数，由 created_at_utc 计算，监控新鲜度）。bash -n 通过 |

**验证方式**：bash -n status.sh 语法通过。按用户要求未跑编译/测试。

## 1.274 2026-08-07 新目标（重建·第 10 次）第 25 轮：deploy.sh --version（#655）

| # | 项 | 说明 |
|---|----|------|
| 655 | **deploy 版本** | `deploy.sh --version` 打印工具版本（`git describe`，非 git 仓库为 unknown）后退出；usage 更新。bash -n 通过 |

**验证方式**：bash -n deploy.sh 语法通过；`bash scripts/deploy.sh --version` 正常退出。按用户要求未跑编译/测试。

## 1.275 2026-08-07 新目标（重建·第 11 次）第 1 轮：Caddyfile 统一 JSON 错误 + verify 回归（#656）

| # | 项 | 说明 |
|---|----|------|
| 656 | **JSON 错误** | deploy/Caddyfile 新增 `handle_errors` 块：4xx/5xx 统一返回 JSON 错误包（避免泄漏 Caddy 默认 HTML）；`verify-production-topology.sh --offline` 校验其存在。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.276 2026-08-07 新目标（重建·第 11 次）第 2 轮：verify 离线校验部署文档 status flags（#657）

| # | 项 | 说明 |
|---|----|------|
| 657 | **文档 flags 回归** | `verify-production-topology.sh --offline` 校验 docs/docker-deployment.md 文档化 status.sh 的 `--json/--short/--health-check/--watch`（缺省即 fail）；补齐文档中缺失的 `--short`/`--health-check` 示例。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过；docs/docker-deployment.md 现覆盖全部 4 个 flags。按用户要求未跑编译/测试。

## 1.277 2026-08-07 新目标（重建·第 11 次）第 3 轮：verify 离线校验 Dockerfile EXPOSE（#658）

| # | 项 | 说明 |
|---|----|------|
| 658 | **EXPOSE 回归** | `verify-production-topology.sh --offline` 校验 server/Dockerfile `EXPOSE 8080` 与 compose 端口一致（缺省即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.278 2026-08-07 新目标（重建·第 11 次）第 4 轮：verify 离线校验健康门控启动顺序（#659）

| # | 项 | 说明 |
|---|----|------|
| 659 | **健康门控回归** | `verify-production-topology.sh --offline` 校验 compose 至少 2 处 `condition: service_healthy`（proxy→server、server→db 启动顺序门控，缺省即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.279 2026-08-07 新目标（重建·第 11 次）第 5 轮：verify 离线校验 Caddy ACME 自动 HTTPS（#660）

| # | 项 | 说明 |
|---|----|------|
| 660 | **自动 HTTPS 回归** | `verify-production-topology.sh --offline` 校验 Caddyfile 配置 `email {$ACME_EMAIL}`（启用 Caddy 自动 HTTPS/证书，缺省即 fail）。bash -n 通过 |

**验证方式**：bash -n verify-production-topology.sh 语法通过。按用户要求未跑编译/测试。

## 1.280 2026-08-08 新目标（重建·第 11 次）第 6 轮：通知中心「标为未读」（#661）

| # | 项 | 说明 |
|---|----|------|
| 661 | **标为未读** | 通知中心长按操作菜单（已读条目时）新增「标为未读」：`NotificationCenterRepository.markUnread(itemId)` 将 `read=true` 条目反转为未读（未读角标 / UNREAD 过滤 / 条目高亮同步恢复）；`NotificationCenterViewModel.markUnread(id)` 桥接。新增 `notif_center_mark_unread` 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（NotificationCenterScreen / NotificationCenterRepository）；check-string-parity zh=en=2778。按用户要求未跑编译/测试。

## 1.281 2026-08-08 新目标（重建·第 11 次）第 7 轮：deploy.sh --doctor 部署预检（#662）

| # | 项 | 说明 |
|---|----|------|
| 662 | **预检模式** | `deploy.sh --doctor` 只读预检（不创建/修改任何文件，检查后退出）：docker / docker compose 环境、.env 就绪性、密钥强度（JWT_SECRET / POSTGRES_PASSWORD / PUSH_HMAC_SECRET）、域名 DNS 解析与 ACME 邮箱、`docker compose config` 可解析性；`[FAIL]` 存在时退出码 1。`deploy.ps1 -Doctor` 同步实现；顺带修复 ps1 `$Host` 参数与 PowerShell 只读自动变量冲突（改 `-Host` alias + `$Hostname`）。verify 离线回归校验 `--doctor` 存在，docs 补齐说明 |

**验证方式**：bash -n deploy.sh / verify-production-topology.sh 通过；pwsh 解析 + `-Doctor` 实跑（无 .env=WARN、占位密钥=FAIL 退出 1、全部就绪=OK 退出 0）；check-string-parity zh=en=2778。按用户要求未跑编译/测试。

## 1.282 2026-08-08 新目标（重建·第 11 次）第 8 轮：通话记录单条删除（#663）

| # | 项 | 说明 |
|---|----|------|
| 663 | **单条删除** | 通话记录页长按某条 → 删除确认弹窗（标题含对端显示名，未知回退「未知联系人」）：`CallLogStore.remove(context, entryId)` 删除本地 call-log 条目（返回是否命中）；未接来电同 id 记录经 `MissedCallRepository.delete` 同步清理，保持会话列表未接角标/卡片一致。新增 4 对中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（CallHistoryScreen / CallLogStore）；check-string-parity zh=en=2782。按用户要求未跑编译/测试。

## 1.283 2026-08-08 新目标（重建·第 11 次）第 9 轮：verify live 校验 status.sh --json + 缺失 .env 容错（#664）

| # | 项 | 说明 |
|---|----|------|
| 664 | **status JSON 回归** | `verify-production-topology.sh --live` 新增 `status.sh --json` 回归：输出必须为合法 JSON 且含监控字段 `generated_at / services / services_up / services_total / backup / version`（下游 jq/告警依赖，缺省即 fail）。顺带修复 status.sh 在 `.env` 缺失时因 `set -euo pipefail` + `sed` 失败导致静默退出（exit 2 无输出）：`public_host` 读取加 `|| true`，现在无 .env 也输出合法 JSON（host 回退 localhost） |

**验证方式**：bash -n status.sh / verify-production-topology.sh 通过；实跑 `status.sh --json` 输出合法 JSON 且含全部监控 key（无 .env 环境验证通过）；verify --offline 全绿；check-string-parity zh=en=2782。按用户要求未跑编译/测试。

## 1.284 2026-08-08 新目标（重建·第 11 次）第 10 轮：通知中心搜索关键词高亮（#665）

| # | 项 | 说明 |
|---|----|------|
| 665 | **通知搜索高亮** | 通知中心搜索框有查询词时，标题/副标题/预览三处匹配关键词高亮（复用 `GlobalSearchTextHighlight.buildSnippet` + 同款 SpanStyle 背景高亮，风格与 Explore 评论 1.270 / 收藏 1.232 / 会话列表一致）；无查询词时原样渲染。`NotificationRow` 新增 `highlightQuery` 参数 + `highlightedText` helper。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（NotificationCenterScreen）；check-string-parity zh=en=2782。按用户要求未跑编译/测试。

## 1.285 2026-08-08 新目标（重建·第 11 次）第 11 轮：backup 磁盘空间预检（#666）

| # | 项 | 说明 |
|---|----|------|
| 666 | **磁盘预检** | `backup-production.sh` 在停止服务写 dump 前做磁盘空间预检：`df -Pm $backup_root` 可用空间 < `BACKUP_MIN_FREE_MB`（默认 1024MB）即 fail（避免 pg_dump 写满磁盘后服务不可用）；df 不可用时降级 WARN 跳过（与 status.sh 同款容错）。verify 离线回归校验 `BACKUP_MIN_FREE_MB` 存在；docs 补齐用法示例 |

**验证方式**：bash -n backup / verify 通过；磁盘预检逻辑三态模拟验证（充足=通过、不足=FAIL、df 缺失=跳过）；verify --offline 全绿。按用户要求未跑编译/测试。

## 1.286 2026-08-08 新目标（重建·第 11 次）第 12 轮：联系人搜索关键词高亮（#667）

| # | 项 | 说明 |
|---|----|------|
| 667 | **联系人搜索高亮** | 联系人搜索结果列表中，匹配用户的显示名高亮关键词（复用 `GlobalSearchTextHighlight.buildSnippet` + 同款 SpanStyle 背景高亮，风格与 Explore 1.270 / 收藏 1.232 / 通知中心 1.284 / 会话列表一致）；`SearchResultList` 传入 `query`，`SearchUserRow` 新增 `highlightQuery` 参数 + `highlightedText` helper。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ContactsScreen）；check-string-parity zh=en=2782。按用户要求未跑编译/测试。

## 1.287 2026-08-08 新目标（重建·第 11 次）第 13 轮：作者主页拉黑/解除拉黑（#668）

| # | 项 | 说明 |
|---|----|------|
| 668 | **作者主页拉黑** | `AuthorProfileViewModel.toggleBlock(authorId)`：拉黑/解除拉黑（与 ChatDetail.blockContact 同模式——BLOCK_REPORT 门控、BackgroundSessionGate 守卫、`ApiService.blockUser/unblockUser`）；load() 时 `getBlockedUsers` 预载 isBlocked 决定按钮文案；成功后本地清空该作者动态、infoMessage 提示；作者卡片新增「拉黑/解除拉黑」按钮。新增 6 对中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（AuthorProfileScreen）；check-string-parity zh=en=2788。按用户要求未跑编译/测试。

## 1.288 2026-08-08 新目标（重建·第 11 次）第 14 轮：compose proxy 健康检查（#669）

| # | 项 | 说明 |
|---|----|------|
| 669 | **proxy 健康检查** | docker-compose.yml proxy 服务新增 healthcheck：容器内探测 Caddy 管理 API `http://127.0.0.1:2019/config`（存活即健康，与 server / db 健康门控配套；`docker compose ps` / status.sh 可看到 proxy healthy，且为后续依赖 proxy 的启动门控打基础）。verify 离线回归校验存在 |

**验证方式**：docker compose config -q 通过（临时 .env 设齐必填 env 后）；bash -n verify 通过；verify --offline 全绿；check-string-parity zh=en=2788。按用户要求未跑编译/测试。

## 1.289 2026-08-08 新目标（重建·第 11 次）第 15 轮：未接来电弹窗长按单条删除（#670）

| # | 项 | 说明 |
|---|----|------|
| 670 | **弹窗单删** | 会话列表未接来电弹窗长按某条 → 删除该条通话记录：`CallLogStore.remove`（本地 call-log）+ `ChatListViewModel.removeMissedCallLocally`（Room missed_calls 同步删除保持角标一致 + 本地 state 即时消失 + 托盘阴影取消）；`MissedCallsSheet` 新增 `onDeleteRow` 回调 + `combinedClickable` 长按。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatListScreen / ChatListViewModel）；check-string-parity zh=en=2788。按用户要求未跑编译/测试。

## 1.290 2026-08-08 新目标（重建·第 11 次）第 16 轮：status.sh --json 每服务健康状态（#671）

| # | 项 | 说明 |
|---|----|------|
| 671 | **服务健康状态** | `status.sh --json` 每个 service 行新增 `health` 字段（从 compose status 解析 `healthy`/`unhealthy`/`unknown`，proxy 健康检查 1.288 之后可被正确标注）；新增顶层 `services_unhealthy` 计数（监控/告警直接按此值判断，无需自行解析）。verify live 回归补校验 `services_unhealthy` key |

**验证方式**：bash -n status / verify 通过；`status.sh --json` 实跑合法 JSON 且含新 key；健康解析三态模拟验证（healthy/unhealthy/unknown + 计数）；verify --offline 全绿。按用户要求未跑编译/测试。

## 1.291 2026-08-08 新目标（重建·第 11 次）第 17 轮：联系人列表长按拉黑（#672）

| # | 项 | 说明 |
|---|----|------|
| 672 | **联系人拉黑** | 联系人列表长按菜单新增「拉黑」（置于备注/解除好友下方，红色警示）→ 拉黑确认弹窗（含对端将被隐藏动态/资料说明）→ `ContactsViewModel.blockUser(user)`（`ApiService.blockUser`，成功后移出联系人列表 + infoMessage 提示）。与设置页黑名单管理、作者主页拉黑（1.287）配套。新增 4 对中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ContactsScreen / ContactsViewModel）；check-string-parity zh=en=2792。按用户要求未跑编译/测试。

## 1.292 2026-08-08 新目标（重建·第 11 次）第 18 轮：deploy 就绪后校验容器健康状态（#673）

| # | 项 | 说明 |
|---|----|------|
| 673 | **容器健康校验** | `deploy.sh` 在 `/health/ready` 就绪后追加 `docker compose ps` unhealthy 扫描：任一服务 `unhealthy`（db/server/proxy 均有 healthcheck）即打印 WARN + 明细，捕获「API 就绪但某服务不健康」的潜伏问题（如 db 被 OOM、proxy 配置异常）。verify 离线回归校验 `unhealthy_services` 存在 |

**验证方式**：bash -n deploy / verify 通过；--doctor 不受影响（就绪后扫描在 doctor 出口之后）；verify --offline 全绿；check-string-parity zh=en=2792。按用户要求未跑编译/测试。

## 1.293 2026-08-08 新目标（重建·第 11 次）第 19 轮：公开主页国际化修复（#674）

| # | 项 | 说明 |
|---|----|------|
| 674 | **公开主页 i18n** | PublicProfileScreen 全量替换硬编码中文字符串为 stringResource：标题/返回/错误文案（用户不存在/加载失败/网络错误）/发消息/复制链接/分享/在线离线/链接已复制/分享文本/重试，新增 14 对中英字符串（parity 2792→2805）。修复双语言 App 在公开主页仍显示中文的问题 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（PublicProfileScreen）；check-string-parity zh=en=2805；确认无残留硬编码中文字符串。按用户要求未跑编译/测试。

## 1.294 2026-08-08 新目标（重建·第 11 次）第 20 轮：restore 破坏性恢复交互确认（#675）

| # | 项 | 说明 |
|---|----|------|
| 675 | **恢复确认门控** | `restore-production.sh --confirm` 在停止服务前增加交互确认：终端输入 `yes` 才继续（打印将覆盖 DB/uploads/TLS 的警示）；非交互/CI 需显式 `CONFIRM_RESTORE=yes`，否则 fail——防止误操作覆盖当前数据。usage/docs 补齐，verify 离线回归校验 `CONFIRM_RESTORE` 存在 |

**验证方式**：bash -n restore / verify 通过；确认门控逻辑两态模拟验证（无 env 非交互=FAIL、CONFIRM_RESTORE=yes=跳过）；verify --offline 全绿；check-string-parity zh=en=2805。按用户要求未跑编译/测试。

## 1.295 2026-08-08 新目标（重建·第 11 次）第 21 轮：群成员搜索关键词高亮（#676）

| # | 项 | 说明 |
|---|----|------|
| 676 | **群成员搜索高亮** | 群详情成员搜索时，匹配成员显示名高亮关键词（复用 `GlobalSearchTextHighlight.buildSnippet` + 同款 SpanStyle 背景高亮，风格与 Explore 1.270 / 收藏 1.232 / 通知中心 1.284 / 联系人 1.286 一致）；`MemberRow` 新增 `highlightQuery` 参数 + `highlightedText` helper。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（GroupDetailScreen）；check-string-parity zh=en=2805。按用户要求未跑编译/测试。

## 1.296 2026-08-08 新目标（重建·第 11 次）第 22 轮：verify 群成员路由回归（#677）

| # | 项 | 说明 |
|---|----|------|
| 677 | **群成员路由回归** | `verify-production-topology.sh` 新增群成员路由双重回归：`--live` 探针 `GET /api/chats/{id}/members`（404=路由缺失、200=未鉴权，均 fail，App 群详情依赖）；`--offline` 校验服务端源码含 `getGroupMembers` handler（缺省即 fail）。与 block 路由回归（1.218）同模式 |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增 getGroupMembers 源码检查）；check-string-parity zh=en=2805。按用户要求未跑编译/测试。

## 1.297 2026-08-08 新目标（重建·第 11 次）第 23 轮：好友请求长按拉黑请求者（#678）

| # | 项 | 说明 |
|---|----|------|
| 678 | **请求者拉黑** | 收到的好友请求行新增长按操作：长按请求者 → 复用 1.291 拉黑确认弹窗（含对方动态/资料将隐藏说明）→ `viewModel.blockUser(req.user)` 拉黑（拦截骚扰）。`FriendRequestRow` 新增 `onBlock` 回调（仅传入的请求传入），`combinedClickable` 长按触发；发出的请求（onCancel 路径）不受影响。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ContactsScreen）；check-string-parity zh=en=2805。按用户要求未跑编译/测试。

## 1.298 2026-08-08 新目标（重建·第 11 次）第 24 轮：update.sh git 守卫 + --version（#679）

| # | 项 | 说明 |
|---|----|------|
| 679 | **update 健壮性** | `update.sh` 增加 git 检出前置守卫：非 git 工作树（手工拷贝/解压部署）时提前给出清晰指引（建议重新 clone）而非一串 git 报错；新增 `--version` 打印当前/目标版本后退出（与 deploy.sh --version 配套）。verify 离线回归校验 `is-inside-work-tree` + `--version` 存在 |

**验证方式**：bash -n update / verify 通过；`--version` 实跑正常退出（非 git 环境回退 unknown）；git 守卫逻辑模拟验证；verify --offline 全绿；check-string-parity zh=en=2805。按用户要求未跑编译/测试。

## 1.299 2026-08-08 新目标（重建·第 12 次）第 1 轮：消息菜单复制消息 ID（#680）

| # | 项 | 说明 |
|---|----|------|
| 680 | **复制消息 ID** | 聊天详情长按消息菜单新增「复制消息 ID」按钮（置于语音转写复制之后、AI 场景分区之前）：复制 msg.id 到剪贴板 + Toast（反查排障用）；消息 ID 本身不涉密，密聊会话也可用（不套 SECRET_COPY_BLOCK 门控）。与会话列表复制会话 ID（1.249）配套。新增 chat_copy_message_id 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2806。按用户要求未跑编译/测试。

## 1.300 2026-08-08 新目标（重建·第 12 次）第 2 轮：verify 部署文档关键 flags 覆盖回归（#681）

| # | 项 | 说明 |
|---|----|------|
| 681 | **文档 flags 回归** | `verify-production-topology.sh --offline` 新增部署文档覆盖回归：docs/docker-deployment.md 必须含 deploy/restore/update 关键 flags/开关 `--doctor` / `CONFIRM_RESTORE` / `--dry-run` / `--version`（缺省即 fail，与 status.sh flags 文档检查 1.276 同模式）；补齐文档缺失的 `--version` 用法示例 |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增文档 flags 检查）；check-string-parity zh=en=2806。按用户要求未跑编译/测试。

## 1.301 2026-08-08 新目标（重建·第 12 次）第 3 轮：群公告全文复制（#682）

| # | 项 | 说明 |
|---|----|------|
| 682 | **公告复制** | 群公告全文弹窗新增「复制公告」按钮（dismissButton 位）：复制公告全文到剪贴板 + Toast（转发到别处/归档用）；公告为空时仅关闭。新增 group_announcement_copy 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2807。按用户要求未跑编译/测试。

## 1.302 2026-08-08 新目标（重建·第 12 次）第 4 轮：status.sh --health-check 扫描 unhealthy 服务（#683）

| # | 项 | 说明 |
|---|----|------|
| 683 | **health-check 增强** | `status.sh --health-check` 在 /health/ready 就绪判断之外，同时扫描 compose 中 `unhealthy` 服务：任一服务 unhealthy 即报 `not ready (unhealthy services: <名>)` 并退出 1（监控检测「API 就绪但某服务退化」场景，与 1.290 JSON services_unhealthy / 1.292 deploy 就绪后扫描同主题）。verify 离线回归校验 `unhealthy` 扫描存在 |

**验证方式**：bash -n status / verify 通过；health-check 三态模拟验证（全健康=ready 退出 0、就绪但一服务 unhealthy=not ready 退出 1、未就绪=not ready）；verify --offline 全绿；check-string-parity zh=en=2807。按用户要求未跑编译/测试。

## 1.303 2026-08-08 新目标（重建·第 12 次）第 5 轮：deploy BASE_URL/PUBLIC_HOST 一致性校验（#684）

| # | 项 | 说明 |
|---|----|------|
| 684 | **域名一致性校验** | `deploy.sh` 双处新增 BASE_URL/PUBLIC_HOST 一致性校验：`--doctor` 预检报告 PASS/FAIL（`https://$host` 或带尾斜杠才匹配）；实际部署在必填值检查处直接 fail（`BASE_URL=https://a.example.com` 但 `PUBLIC_HOST=b.example.com` 时提前拦截，避免 App 客户端连错域名的「成功假象」）。localhost 跳过。verify 离线回归校验存在，docs 补齐说明 |

**验证方式**：bash -n deploy / verify 通过；一致性逻辑五态模拟验证（精确匹配/尾斜杠=通过、错域名/http/空=FAIL）；verify --offline 全绿；check-string-parity zh=en=2807。按用户要求未跑编译/测试。

## 1.304 2026-08-08 新目标（重建·第 12 次）第 6 轮：AI 任务清空已完成（#685）

| # | 项 | 说明 |
|---|----|------|
| 685 | **清空已完成任务** | AI 任务页顶栏新增「清空已完成」按钮（有已完成任务时显示）：`AiTaskDao.deleteCompletedByChatId`（新增 SQL）+ `AiTaskRepository.deleteCompletedByChatId`（取消已完成任务的提醒调度 + 删除记录）+ `AiTasksViewModel.clearCompleted`（会话归属守卫）。新增 ai_tasks_clear_completed / ai_tasks_clear_completed_failed 中英字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（AiTasksScreen / AiTaskRepository / AiTaskDao）；check-string-parity zh=en=2809。按用户要求未跑编译/测试。

## 1.305 2026-08-08 新目标（重建·第 12 次）第 7 轮：restore 备份格式版本兼容校验（#686）

| # | 项 | 说明 |
|---|----|------|
| 686 | **格式版本校验** | `restore-production.sh` 新增 `format_version` 兼容性检查：备份 METADATA 中 `format_version` 非空且 ≠1 时 fail（提示升级 restore 或整个仓库），防止未来备份布局变更被旧恢复器硬套（当前备份写入 v1）。verify 离线回归校验 `format_version` 检查存在 |

**验证方式**：bash -n restore / verify 通过；格式版本四态模拟验证（v1/空=接受、v2/非法=FAIL）；verify --offline 全绿；check-string-parity zh=en=2809。按用户要求未跑编译/测试。

## 1.306 2026-08-08 新目标（重建·第 12 次）第 8 轮：status.sh --short 增加 unhealthy 计数（#687）

| # | 项 | 说明 |
|---|----|------|
| 687 | **short unhealthy** | `status.sh --short` 一行摘要新增 `services_unhealthy` 字段（与 `--json` 1.290 / `--health-check` 1.302 一致），监控脚本/人眼快速识别服务退化。verify 离线回归校验 `services_unhealthy` 存在 |

**验证方式**：bash -n status / verify 通过；`status.sh --short` 实跑输出含 services_unhealthy；verify --offline 全绿；check-string-parity zh=en=2809。按用户要求未跑编译/测试。

## 1.307 2026-08-08 新目标（重建·第 12 次）第 9 轮：朋友圈搜索关键词高亮（#688）

| # | 项 | 说明 |
|---|----|------|
| 688 | **朋友圈搜索高亮** | 朋友圈（MomentsScreen）搜索时，匹配动态正文/作者名高亮关键词（复用 `GlobalSearchTextHighlight.buildSnippet` + 同款背景 SpanStyle，风格与 Explore 1.270 / 收藏 1.232 / 通知中心 1.284 / 联系人 1.286 / 群成员 1.295 一致）；新增 `highlightedText` helper + FontWeight 导入。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2809。按用户要求未跑编译/测试。

## 1.308 2026-08-08 新目标（重建·第 12 次）第 10 轮：backup --list 显示备份年龄（#689）

| # | 项 | 说明 |
|---|----|------|
| 689 | **备份年龄** | `backup-production.sh --list` 每行新增 `age=Nh` 字段（由 METADATA `created_at_utc` 计算距今小时数，与 status.sh 1.273 age_hours 同算法；解析失败/为空显示 `-`），方便人眼核对备份新鲜度。verify 离线回归校验 `age=` 存在 |

**验证方式**：bash -n backup / verify 通过；年龄计算三态模拟验证（合法日期=小时、空/非法=`-`）；verify --offline 全绿；check-string-parity zh=en=2809。按用户要求未跑编译/测试。

## 1.309 2026-08-08 新目标（重建·第 12 次）第 11 轮：动态详情页举报动态（#690）

| # | 项 | 说明 |
|---|----|------|
| 690 | **详情页举报** | 动态详情页作者行新增「举报动态」按钮（他人动态时显示，位于屏蔽作者旁，与动态流卡片举报一致）：`viewModel.reportPost(post)`。复用已有 `explore_report_post` 字符串与 Flag 图标，无新增资源。与评论举报（1.246）/动态流举报（1.06）补齐 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2809。按用户要求未跑编译/测试。

## 1.310 2026-08-08 新目标（重建·第 12 次）第 12 轮：会话周报复制（#691）

| # | 项 | 说明 |
|---|----|------|
| 691 | **周报复制** | 会话周报弹窗新增「复制」按钮（dismissButton 位，仅报告已生成时显示）：复制周报全文到剪贴板 + Toast（转发/归档用）。`WeeklyReportDialog` 新增 `onCopyReport` 回调，调用点接线（复用 `chat_copied`/`chat_copy` 字符串）。与 AI 摘要复制（1.197）配套 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2809。按用户要求未跑编译/测试。

## 1.311 2026-08-08 新目标（重建·第 12 次）第 13 轮：update.sh 已是最新跳过重建（#692）

| # | 项 | 说明 |
|---|----|------|
| 692 | **无变更跳过** | `update.sh` 在 `git pull` 前后记录 HEAD：无变更（已是最新）时提示「已是最新」并跳过镜像重建/重启（避免无谓构建；提示若手改 .env 可 `deploy.sh --no-build`）；有变更则显示 `旧 -> 新` 版本并继续。verify 离线回归校验 `Already up to date` 存在 |

**验证方式**：bash -n update / verify 通过；verify --offline 全绿；check-string-parity zh=en=2809。按用户要求未跑编译/测试。

## 1.312 2026-08-08 新目标（重建·第 12 次）第 14 轮：deploy --dry-run 展示管理员/注册配置（#693）

| # | 项 | 说明 |
|---|----|------|
| 693 | **dry-run 管理配置** | `deploy.sh --dry-run` 配置摘要新增 `MASTER_ADMINS` / `BOOTSTRAP`（BOOTSTRAP_FIRST_USER_AS_ADMIN）/ `ALLOW_REG`（ALLOW_REGISTRATION）三行（首次部署易遗漏的管理员与注册开关，供部署前核对）。verify 离线回归校验两者存在 |

**验证方式**：bash -n deploy / verify 通过；sed 提取逻辑 bash 实跑验证（MASTER_ADMINS/BOOTSTRAP/ALLOW_REG 正确读取）；verify --offline 全绿；check-string-parity zh=en=2809。按用户要求未跑编译/测试。

## 1.313 2026-08-08 新目标（重建·第 12 次）第 15 轮：deploy --doctor 校验非 relaxed SMTP（#694）

| # | 项 | 说明 |
|---|----|------|
| 694 | **SMTP 校验** | `deploy.sh --doctor` 新增非 relaxed 模式 SMTP 校验：`.env` 存在且 `RELAXED_VERIFICATION != true` 时，SMTP_HOST 未配置/占位即 FAIL（验证码邮件发不出去会卡注册流程，提前暴露）；relaxed 模式 PASS（验证码打日志）。verify 离线回归校验存在 |

**验证方式**：bash -n deploy / verify 通过；SMTP 校验五态模拟验证（非 relaxed 空/占位=FAIL、已配置/relaxed/未设 relaxed=通过）；verify --offline 全绿；check-string-parity zh=en=2809。按用户要求未跑编译/测试。

## 1.314 2026-08-08 新目标（重建·第 12 次）第 16 轮：群玩法 i18n 修复（4 屏硬编码中文）（#695）

| # | 项 | 说明 |
|---|----|------|
| 695 | **群玩法 i18n** | 批量修复群玩法 4 屏硬编码中文字符串：GroupChainScreen（加载失败/标题主题无效/创建失败/接龙不存在/接龙失败）、GroupPollScreen（加载失败/问题选项无效/创建失败/投票失败/已结束/人参与）、GroupPkScreen（加载失败/PK 标题无效/创建失败/投票失败/已结束/人参与/已投）、GroupCheckinScreen（加载失败/签到失败）。各 ViewModel 新增 `text`/`str` 资源 helper，UI 用 stringResource；新增 12 对中英字符串（parity 2809→2820） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（4 文件）；check-string-parity zh=en=2820；确认 4 屏无残留硬编码中文字符串。按用户要求未跑编译/测试。

## 1.315 2026-08-08 新目标（重建·第 12 次）第 17 轮：全 App 硬编码中文扫描 + 设置页分享文案 i18n（#696）

| # | 项 | 说明 |
|---|----|------|
| 696 | **i18n 收尾** | 全 App 扫描硬编码中文字符串：发现并修复 SettingsScreen 分享文案（复用 `public_profile_share_text` 资源，1.293 已加）；其余 Text/Toast/Snackbar/error 硬编码中文为零（login/call/lock/settings/explore/chatlist/chatdetail/contacts/notification 全部干净；状态预设字面量是服务端 key→资源映射、注释非 UI 串不处理） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（SettingsScreen）；check-string-parity zh=en=2820；全 App 扫描确认无残留用户可见硬编码中文。按用户要求未跑编译/测试。

## 1.316 2026-08-08 新目标（重建·第 12 次）第 18 轮：verify 群投票路由回归（#697）

| # | 项 | 说明 |
|---|----|------|
| 697 | **群投票路由回归** | `verify-production-topology.sh` 新增群投票路由双重回归（与 members 1.296 同模式）：`--live` 探针 `GET /api/chats/{id}/polls`（404=缺失、200=未鉴权，均 fail，App 群玩法依赖）；`--offline` 校验服务端源码含 `isPollsEnabled` 门控 handler |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增 isPollsEnabled 源码检查）；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.317 2026-08-08 新目标（重建·第 12 次）第 19 轮：会话画像复制（#698）

| # | 项 | 说明 |
|---|----|------|
| 698 | **画像复制** | 会话画像（ConversationProfile）弹窗新增「复制」按钮（dismissButton 位，仅生成成功时显示）：`profileText()` 序列化统计/时段/高频词/叙述为纯文本 → 剪贴板 + Toast（转发/归档用）。`ConversationProfileDialog` 新增 `onCopyProfile` 回调。与周报复制（1.310）/AI 摘要复制（1.197）配套 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.318 2026-08-08 新目标（重建·第 12 次）第 20 轮：deploy --doctor 识别 --relaxed CLI 标志（#699）

| # | 项 | 说明 |
|---|----|------|
| 699 | **--relaxed 等效** | `deploy.sh --doctor` 的 SMTP 校验改为同时认 `--relaxed` CLI 标志：`--doctor --relaxed` 首次预检（.env 尚未写 RELAXED_VERIFICATION=true）时不再误报 SMTP 缺失（此前只读 .env，导致首次预检假 FAIL）。verify 离线回归校验 `$RELAXED == "true"` 处理存在 |

**验证方式**：bash -n deploy / verify 通过；`--doctor --relaxed` 实跑 SMTP 显示 relaxed 通过、无 --relaxed 时按 .env 判定；verify --offline 全绿；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.319 2026-08-08 新目标（重建·第 12 次）第 21 轮：verify 补充 Caddyfile 安全头回归（#700）

| # | 项 | 说明 |
|---|----|------|
| 700 | **安全头回归** | `verify-production-topology.sh --offline` 扩展 Caddyfile 安全响应头回归：`includeSubDomains`（HSTS 子域）/ `Referrer-Policy` / `-Server`（移除 Server 头）/ 管理页 `Permissions-Policy` 全部缺省即 fail（此前只查 nosniff + X-Frame-Options，已存在的头未覆盖） |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增 4 项安全头检查）；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.320 2026-08-08 新目标（重建·第 12 次）第 22 轮：媒体中心搜索关键词高亮（#701）

| # | 项 | 说明 |
|---|----|------|
| 701 | **媒体中心高亮** | 媒体中心（共享媒体）搜索时，文件列表文件名 / 链接列表域名与 URL 高亮关键词（复用 `GlobalSearchTextHighlight.buildSnippet` + 同款背景 SpanStyle，风格与 Explore 1.270 / 收藏 1.232 / 通知中心 1.284 等一致）；`FileList`/`LinkList` 新增 `highlightQuery` 参数 + `highlightedText` helper。语音/图片列表无文本可高亮不适用。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（MediaCenterScreen）；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.321 2026-08-08 新目标（重建·第 12 次）第 23 轮：verify 动态流路由回归（#702）

| # | 项 | 说明 |
|---|----|------|
| 702 | **动态流路由回归** | `verify-production-topology.sh` 新增动态流路由双重回归（与 members 1.296 / polls 1.316 同模式）：`--live` 探针 `GET /api/posts`（404=缺失、200=未鉴权，均 fail，App Explore 依赖）；`--offline` 校验服务端源码含 `postRepo` 列表 handler |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增 postRepo 源码检查）；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.322 2026-08-08 新目标（重建·第 12 次）第 24 轮：backup --tag 安全字符校验（#703）

| # | 项 | 说明 |
|---|----|------|
| 703 | **tag 校验加固** | `backup-production.sh --tag` 校验收紧为仅允许 `[A-Za-z0-9_-]+`：拒绝空白、`/`、`.`（路径穿越 `..`）、空串等——tag 嵌入备份目录名，此前只拒 `/` 存在空格/点号/`..` 注入风险。verify 离线回归校验存在 |

**验证方式**：bash -n backup / verify 通过；tag 校验八态模拟验证（字母/数字/下划线/连字符=接受；空白/斜杠/点号/空=拒绝）；verify --offline 全绿；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.323 2026-08-08 新目标（重建·第 12 次）第 25 轮：通话记录搜索 + 关键词高亮（#704）

| # | 项 | 说明 |
|---|----|------|
| 704 | **通话记录搜索** | 通话记录页新增搜索框（≥6 条时显示，复用 `missed_calls_search_hint`）：按对端名/ID 过滤，无匹配时显示 `missed_calls_search_empty`；匹配对端名高亮关键词（复用 `GlobalSearchTextHighlight.buildSnippet` + 同款背景 SpanStyle，与全 App 高亮风格一致）。`filteredLogs` 替代原列表。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（CallHistoryScreen）；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.324 2026-08-08 新目标（重建·第 13 次）第 1 轮：.env.docker.example 补齐运维可调项文档（#705）

| # | 项 | 说明 |
|---|----|------|
| 705 | **运维键文档** | `.env.docker.example` Ops 段补齐 `BACKUP_MIN_FREE_MB`（1.285 磁盘预检）与 `STATUS_WATCH_INTERVAL`（1.231 watch 刷新间隔）注释示例；verify 离线回归校验 4 个运维可调项（BACKUP_KEEP / BACKUP_MIN_FREE_MB / HEALTH_TIMEOUT_SECONDS / STATUS_WATCH_INTERVAL）在示例 env 有文档 |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增运维键文档检查）；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.325 2026-08-08 新目标（重建·第 13 次）第 2 轮：restore --inspect 附备份元数据（#706）

| # | 项 | 说明 |
|---|----|------|
| 706 | **inspect 元数据** | `restore-production.sh --inspect` 输出追加备份元数据行（created_at_utc / code_version / format_version），与 dry-run 输出一致，便于恢复前核对备份来源；`--inspect` 继续只读不执行恢复。verify 离线回归校验 `备份元数据` 存在 |

**验证方式**：bash -n restore / verify 通过；verify --offline 全绿；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.326 2026-08-08 新目标（重建·第 13 次）第 3 轮：backup --dry-run 报告磁盘空间（#707）

| # | 项 | 说明 |
|---|----|------|
| 707 | **dry-run 磁盘** | `backup-production.sh --dry-run` 追加磁盘空间报告（复用 1.285 磁盘预检逻辑，只读不写）：充足=OK、不足=WARN（提示实际备份可能失败）、df 缺失=WARN——部署前一次性发现空间不足，无需等真实备份停服后失败。verify 离线回归校验 `dry_free_mb` 存在 |

**验证方式**：bash -n backup / verify 通过；verify --offline 全绿；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.327 2026-08-08 新目标（重建·第 13 次）第 4 轮：verify 示例 env 关键键覆盖扩展（#708）

| # | 项 | 说明 |
|---|----|------|
| 708 | **env 键回归扩展** | `verify-production-topology.sh --offline` 的 `.env.docker.example` 必需键检查从 10 个扩展至 18 个：新增 `MODERATOR_EMAILS` / `MASTER_ADMINS` / `ALLOW_REGISTRATION` / `DEVELOPER_USER_IDS` / `EMAIL_DOMAIN_BLOCKLIST` / `OPENAI_API_KEY` / `OPENAI_MODEL` / `FCM_PROJECT_ID`（管理/治理/AI/推送配置漂移防护，缺省即 fail） |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增 8 键检查）；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.328 2026-08-08 新目标（重建·第 13 次）第 5 轮：update.sh 打印提交日志摘要（#709）

| # | 项 | 说明 |
|---|----|------|
| 709 | **提交摘要** | `update.sh` 在 HEAD 有变化时打印本次更新的提交日志摘要（`git log --oneline` 前 30 条，旧..新），便于运维了解本次部署变更内容（release notes 视角）；无变更时仍走 1.311 跳过。verify 离线回归校验 `Commits in this update` 存在 |

**验证方式**：bash -n update / verify 通过；verify --offline 全绿；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.329 2026-08-08 新目标（重建·第 13 次）第 6 轮：deploy.ps1 -Version（与 deploy.sh 对齐）（#710）

| # | 项 | 说明 |
|---|----|------|
| 710 | **ps1 -Version** | `deploy.ps1` 新增 `-Version` 开关：打印工具版本（git describe，非 git 回退 unknown）后退出（与 deploy.sh --version 1.274 对齐）；usage 头文档补齐。保持 Windows 与 Linux 部署脚本能力一致 |

**验证方式**：pwsh 解析通过；`-Version` 实跑正常退出（非 git 环境回退 unknown）；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.330 2026-08-08 新目标（重建·第 13 次）第 7 轮：朋友圈「只看图片」过滤（#711）

| # | 项 | 说明 |
|---|----|------|
| 711 | **朋友圈只看图片** | 朋友圈（MomentsScreen）新增「只看图片」过滤 chip（与 Explore 动态流 1.192 一致）：仅显示带图片的公开动态，可与搜索叠加；复用已有 `explore_feed_only_media` 字符串。无新增资源 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ExploreSubScreens）；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.331 2026-08-08 新目标（重建·第 13 次）第 8 轮：verify live 校验 health 探针 JSON（#712）

| # | 项 | 说明 |
|---|----|------|
| 712 | **health JSON 回归** | `verify-production-topology.sh --live` 的 `/health/live` 与 `/health/ready` 探针新增 JSON 合法性校验（`python3 -c json.load`，解析失败即 fail，与 public/status JSON 校验 1.260 一致）——下游监控/探活解析依赖，防「文本 200 但结构漂移」 |

**验证方式**：bash -n verify 通过；verify --offline 全绿；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.332 2026-08-08 新目标（重建·第 13 次）第 9 轮：verify live 校验 /admin 安全头（#713）

| # | 项 | 说明 |
|---|----|------|
| 713 | **/admin 安全头回归** | `verify-production-topology.sh --live` 的 `/admin` HEAD 检查在 HSTS 之外追加 `X-Content-Type-Options` 与 `X-Frame-Options` 断言（与离线 Caddyfile 安全头检查 1.255/1.319 一致），防「配置漂移只存在于离线声明、线上未生效」 |

**验证方式**：bash -n verify 通过；verify --offline 全绿；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.333 2026-08-08 新目标（重建·第 13 次）第 10 轮：通话记录「只看未接」过滤（#714）

| # | 项 | 说明 |
|---|----|------|
| 714 | **只看未接** | 通话记录页新增「只看未接」过滤 chip（有未接记录时显示，与 1.323 搜索可叠加）：仅显示 MISSED 状态通话，快速定位漏接；复用未接徽标语义。新增 call_history_only_missed 中英字符串（parity 2820→2821） |

**验证方式**：brace_strip2.py 括号平衡 delta=0（CallHistoryScreen）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.334 2026-08-08 新目标（重建·第 13 次）第 11 轮：status.sh --short 附带备份年龄（#715）

| # | 项 | 说明 |
|---|----|------|
| 715 | **short 备份年龄** | `status.sh --short` 一行摘要新增 `latest_backup_age` 字段（与 `--json` age_hours 1.273 一致），人眼快速核对备份新鲜度；无备份时显示 none。verify 离线回归校验存在 |

**验证方式**：bash -n status / verify 通过；`status.sh --short` 实跑输出含 latest_backup_age；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.335 2026-08-08 新目标（重建·第 13 次）第 12 轮：verify 群签到路由回归（#716）

| # | 项 | 说明 |
|---|----|------|
| 716 | **群签到路由回归** | `verify-production-topology.sh --offline` 新增群签到 handler 源码检查：服务端含 `group_n` 表/签到处理即 ok（缺省 fail，App 群玩法依赖，与 polls 1.316 同模式）。群签到路由因源码混淆不探活（与 posts 处理一致用离线符号覆盖） |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增 group_n 源码检查）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.336 2026-08-08 新目标（重建·第 13 次）第 13 轮：备份 METADATA 记录工具版本与源主机（#717）

| # | 项 | 说明 |
|---|----|------|
| 717 | **备份溯源** | `backup-production.sh` METADATA.txt 新增 `backup_tool_version` 与 `backup_hostname`（hostname 命令，失败回退 unknown）——恢复/审计时确认备份来源主机与工具版本；dry-run/list/inspect 展示一致性（inspect 已展示元数据）。verify 离线回归校验两者存在 |

**验证方式**：bash -n backup / verify 通过；verify --offline 全绿（含新增 2 项 METADATA 检查）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.337 2026-08-08 新目标（重建·第 13 次）第 14 轮：verify live 校验 Referrer-Policy（#718）

| # | 项 | 说明 |
|---|----|------|
| 718 | **Referrer-Policy 回归** | `verify-production-topology.sh --live` 的 `/admin` HEAD 检查追加 `Referrer-Policy` 断言（与离线 1.319 一致），补齐 live 侧安全头覆盖（此前 HSTS/XCTO/XFO 已 live 校验，Referrer-Policy 仅离线） |

**验证方式**：bash -n verify 通过；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.338 2026-08-08 新目标（重建·第 13 次）第 15 轮：restore 健康等待 .env 缺失容错（#719）

| # | 项 | 说明 |
|---|----|------|
| 719 | **restore 容错** | `restore-production.sh` 健康等待的 `PUBLIC_HOST` 读取加 `|| true`（`set -euo pipefail` 下 `.env` 缺失时 `sed` 失败会静默退出，与 status.sh 1.283 同款修复）；缺失时回退 localhost 继续等待。verify 离线回归校验 `|| true` 容错存在 |

**验证方式**：bash -n restore / verify 通过；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.339 2026-08-08 新目标（重建·第 13 次）第 16 轮：deploy 校验 ACME_EMAIL 邮箱格式（#720）

| # | 项 | 说明 |
|---|----|------|
| 720 | **ACME 邮箱校验** | `deploy.sh` 必填值检查新增 `ACME_EMAIL` 邮箱格式校验（`^user@domain.tld` 正则，Caddy 对非法邮箱报错晦涩，提前拦截）；placeholder/admin@example.com 仍按未配置处理。verify 离线回归校验存在 |

**验证方式**：bash -n deploy / verify 通过；邮箱格式六态模拟验证（合法=接受、占位/空/格式非法=拒绝）；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.340 2026-08-08 新目标（重建·第 13 次）第 17 轮：deploy 校验 POSTGRES_PASSWORD 强度（#721）

| # | 项 | 说明 |
|---|----|------|
| 721 | **DB 口令校验** | `deploy.sh` 必填值检查新增 `POSTGRES_PASSWORD` 强度校验（≥16 字符、非占位、不等于 JWT_SECRET——数据库访问凭据弱口令即安全风险，占位会致服务端启动失败）；8.48 注释原本声称校验但仅验了 JWT_SECRET。verify 离线回归校验存在 |

**验证方式**：bash -n deploy / verify 通过；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.341 2026-08-08 新目标（重建·第 13 次）第 18 轮：非 localhost 部署 BASE_URL 必须 https（#722）

| # | 项 | 说明 |
|---|----|------|
| 722 | **BASE_URL https** | `deploy.sh` 在非 localhost 部署时追加 `BASE_URL` 必须为 `https://` 前缀校验（Caddy 自动 HTTPS；http 会致客户端证书/安全策略异常），与 1.303 域名一致性校验叠加。verify 离线回归校验存在 |

**验证方式**：bash -n deploy / verify 通过；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.342 2026-08-08 新目标（重建·第 13 次）第 19 轮：deploy --doctor 校验 DATABASE_POOL_SIZE（#723）

| # | 项 | 说明 |
|---|----|------|
| 723 | **连接池校验** | `deploy.sh --doctor` 新增 `DATABASE_POOL_SIZE` 数值校验（整数 2..128，服务端要求范围；非法值致连接池初始化失败提前暴露）；空值跳过。verify 离线回归校验存在 |

**验证方式**：bash -n deploy / verify 通过；连接池七态模拟验证（空/2/8/128=通过，1/129/abc=FAIL）；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.343 2026-08-08 新目标（重建·第 13 次）第 20 轮：聊天内搜索当前结果闪烁高亮（#724）

| # | 项 | 说明 |
|---|----|------|
| 724 | **搜索结果高亮** | 聊天内搜索（ChatSearchBar）导航到当前结果时，复用 `navigationHighlightMessageId` 机制对该消息气泡闪烁高亮 1.8s（此前只滚动不标记，定位困难）；上一结果清空后高亮自动清除。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.344 2026-08-08 新目标（重建·第 13 次）第 21 轮：status.sh --watch --json 保持逐行合法 JSON（#725）

| # | 项 | 说明 |
|---|----|------|
| 725 | **watch JSON 纯净** | `status.sh --watch --json` 组合时抑制每轮分隔线（改空行分隔），保证每轮输出为合法 JSON——此前分隔线文本混入会破坏下游 `while read -r json` 逐行解析（监控/管道消费场景）。verify 离线回归校验 `suppress_sep` 存在 |

**验证方式**：bash -n status / verify 通过；`status.sh --json` 输出合法 JSON 实跑验证；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.345 2026-08-08 新目标（重建·第 13 次）第 22 轮：update.sh 未提交改动警告（#726）

| # | 项 | 说明 |
|---|----|------|
| 726 | **脏树警告** | `update.sh` 在 git pull 前警告未提交改动（staged 或 unstaged 任一即 WARN）：本地改动会阻挡 ff-only pull 或使重建镜像与 HEAD 不一致；提交/暂存后再继续。verify 离线回归校验存在 |

**验证方式**：bash -n update / verify 通过；脏树三态模拟验证（干净=CLEAN、staged/unstaged=DIRTY）；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.346 2026-08-08 新目标（重建·第 13 次）第 23 轮：restore --dry-run 暴露格式版本不兼容（#727）

| # | 项 | 说明 |
|---|----|------|
| 727 | **dry-run 格式校验** | `restore-production.sh --dry-run` 输出 `format_version` 并校验（≠1 即 fail，与真实恢复 1.305 一致）：停服前一次性发现备份格式不兼容，避免恢复过程进行到一半才失败。verify 离线回归校验存在 |

**验证方式**：bash -n restore / verify 通过；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.347 2026-08-08 新目标（重建·第 13 次）第 24 轮：deploy --doctor 校验 SMTP_PORT 与 TURN TTL（#728）

| # | 项 | 说明 |
|---|----|------|
| 728 | **数值校验扩展** | `deploy.sh --doctor` 新增 `SMTP_PORT`（端口 1..65535）与 `TURN_CREDENTIAL_TTL_SECONDS`（正整数）数值校验（与 1.342 DATABASE_POOL_SIZE 同模式，非法值致服务端解析/启动异常提前暴露）；空值跳过。verify 离线回归校验存在 |

**验证方式**：bash -n deploy / verify 通过；doctor 实跑非法值 FAIL 验证（SMTP_PORT=99999 / TURN TTL=0）；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.348 2026-08-08 新目标（重建·第 13 次）第 25 轮：verify compose expose 与 Dockerfile EXPOSE 一致（#729）

| # | 项 | 说明 |
|---|----|------|
| 729 | **端口双端一致** | `verify-production-topology.sh --offline` 在 1.277 Dockerfile EXPOSE 检查基础上，追加 compose server `expose: 8080` 一致性校验（两侧同源防漂移：改端口时任一遗漏即 fail）。verify 离线回归校验存在 |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增 compose expose 检查）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.349 2026-08-08 新目标（重建·第 14 次）第 1 轮：消息分类结果复制（#730）

| # | 项 | 说明 |
|---|----|------|
| 730 | **分类复制** | 消息分类统计弹窗新增「复制」按钮（dismissButton 位，仅分类成功时显示）：`classifyText()` 序列化各分类名+计数+免责声明为纯文本 → 剪贴板 + Toast。`MessageClassifyDialog` 新增 `onCopyClassify` 回调；与周报复制（1.310）/画像复制（1.317）补齐。无新增字符串 |

**验证方式**：brace_strip2.py 括号平衡 delta=0（ChatDetailScreen）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.350 2026-08-08 新目标（重建·第 14 次）第 2 轮：deploy 校验 PUBLIC_HOST 为裸域名（#731）

| # | 项 | 说明 |
|---|----|------|
| 731 | **域名格式校验** | `deploy.sh` 必填值检查新增 `PUBLIC_HOST` 格式校验（非 localhost：拒绝 scheme/path/空格/端口，仅允许裸域名如 chat.example.com——Caddy 对非法站点地址报错晦涩，提前拦截）；localhost/127.0.0.1 跳过。verify 离线回归校验存在 |

**验证方式**：bash -n deploy / verify 通过；域名格式八态模拟验证（裸域名=通过、scheme/端口/空格/path=FAIL、localhost 跳过）；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.351 2026-08-08 新目标（重建·第 14 次）第 3 轮：deploy --doctor 校验管理员配置完整性（#732）

| # | 项 | 说明 |
|---|----|------|
| 732 | **管理员配置** | `deploy.sh --doctor` 新增 `MASTER_ADMINS` 配置完整性检查：占位且 bootstrap 关闭时 WARN（首个注册用户将不是管理员——常见首次部署遗漏）；bootstrap 启用或 MASTER_ADMINS 已设则 PASS。verify 离线回归校验存在 |

**验证方式**：bash -n deploy / verify 通过；doctor 实跑占位+bootstrap off=WARN 验证；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.352 2026-08-08 新目标（重建·第 14 次）第 4 轮：verify 校验 deploy.ps1 存在与开关对齐（#733）

| # | 项 | 说明 |
|---|----|------|
| 733 | **ps1 回归** | `verify-production-topology.sh --offline` 运维脚本检查扩展：`deploy.ps1` 必须存在（Windows 部署对齐）且支持 `-Doctor`/`-Version` 核心开关（与 deploy.sh 1.281/1.329 对齐）。verify 离线回归校验存在 |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增 deploy.ps1 存在+开关检查）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.353 2026-08-08 新目标（重建·第 14 次）第 5 轮：deploy.ps1 -DryRun（与 deploy.sh 对齐）（#734）

| # | 项 | 说明 |
|---|----|------|
| 734 | **ps1 -DryRun** | `deploy.ps1` 新增 `-DryRun` 开关（在 compose 校验后、启动前打印配置摘要并退出，与 deploy.sh --dry-run 1.274 对齐）：PUBLIC_HOST/BASE_URL/ACME_EMAIL/RELAXED/BOOTSTRAP/MASTER_ADMINS/ALLOW_REG；usage 头文档补齐。verify 离线回归校验存在 |

**验证方式**：pwsh 解析通过；`-DryRun` 实跑正常创建 .env+生成密钥并走到启动前退出；verify --offline 全绿（含新增 -DryRun 检查）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.354 2026-08-08 新目标（重建·第 14 次）第 6 轮：deploy --doctor 提示非 relaxed TURN 缺失（#735）

| # | 项 | 说明 |
|---|----|------|
| 735 | **TURN 校验** | `deploy.sh --doctor` 新增非 relaxed 模式 TURN 检查：TURN_URLS 未配置/占位时 WARN（通话降级 STUN-only、NAT 穿透可靠性下降）；relaxed 模式或已配置则通过。verify 离线回归校验存在 |

**验证方式**：bash -n deploy / verify 通过；doctor 实跑非 relaxed+TURN 空=WARN 验证；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.355 2026-08-08 新目标（重建·第 14 次）第 7 轮：verify live 扩展 public/status 运行时开关覆盖（#736）

| # | 项 | 说明 |
|---|----|------|
| 736 | **status 开关回归** | `verify-production-topology.sh --live` 的 `/api/public/status` 检查从 4 键扩展至 16 键：新增 App 消费的 `aiAnalyzeFileEnabled` / `aiGroupAssistantEnabled` / `aiSemanticSearchEnabled` / `aiSummaryEnabled` / `aiTranscribeEnabled` / `appLockEnabled` / `chatFoldersEnabled` / `maintenance` 等（防运行时开关回归导致 App 功能漂移） |

**验证方式**：bash -n verify 通过；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.356 2026-08-08 新目标（重建·第 14 次）第 8 轮：备份工具版本跟随仓库版本（#737）

| # | 项 | 说明 |
|---|----|------|
| 737 | **工具版本溯源** | `backup-production.sh` 的 `backup_tool_version` 由硬编码 `1.336` 改为 `backup-tool@${code_version}`（跟随 git 版本，防过期误导审计）；`backup_hostname` 保留。verify 离线回归校验派生逻辑存在 |

**验证方式**：bash -n backup / verify 通过；verify --offline 全绿（含新增派生检查）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.357 2026-08-08 新目标（重建·第 14 次）第 9 轮：deploy --doctor 校验 CORS_ORIGINS（#738）

| # | 项 | 说明 |
|---|----|------|
| 738 | **CORS 校验** | `deploy.sh --doctor` 新增 `CORS_ORIGINS` 校验：非空且非占位时检查是否含 `http(s)://` 源（逗号分隔），否则 WARN（拼写错误会致 web 端被 CORS 拦截）；空值跳过。verify 离线回归校验存在 |

**验证方式**：bash -n deploy / verify 通过；doctor 实跑 CORS=not-a-url=WARN 验证；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.358 2026-08-08 新目标（重建·第 14 次）第 10 轮：deploy --doctor 校验上传/配额限制数值（#739）

| # | 项 | 说明 |
|---|----|------|
| 739 | **上传限制校验** | `deploy.sh --doctor` 新增 `MAX_BASE64_IMAGE_CHARS` / `MAX_IMAGE_BYTES` / `MAX_IMAGE_DIMENSION` / `MAX_ATTACHMENT_BYTES` / `USER_STORAGE_QUOTA_BYTES` 数值校验（服务端按整数解析，非法值致启动/上传异常提前暴露）；空值跳过。verify 离线回归校验存在 |

**验证方式**：bash -n deploy / verify 通过；doctor 实跑 MAX_IMAGE_DIMENSION=abc=FAIL 验证；verify --offline 全绿；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.359 2026-08-08 新目标（重建·第 14 次）第 11 轮：backup --list 附备份工具版本（#740）

| # | 项 | 说明 |
|---|----|------|
| 740 | **list 工具版本** | `backup-production.sh --list` 每行追加 `tool=` 字段（读取 METADATA `backup_tool_version`，1.336/1.356 已写入），审计时一眼可见备份由哪个工具版本产生。verify 离线回归校验存在 |

**验证方式**：bash -n backup / verify 通过；verify --offline 全绿（含新增 tool= 检查）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.360 2026-08-08 新目标（重建·第 14 次）第 12 轮：部署文档补齐 backup --list 与 docs flags 覆盖（#741）

| # | 项 | 说明 |
|---|----|------|
| 741 | **备份检查文档** | `docs/docker-deployment.md` 备份段补齐 `backup-production.sh --list` 示例（含年龄/工具版本审计说明）；verify 离线文档 flags 覆盖把 `--list` 纳入（1.300 检查集从 4 项扩至 5 项）。verify 离线回归校验存在 |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增 --list 文档检查）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.361 2026-08-08 新目标（重建·第 14 次）第 13 轮：verify compose 治理/管理员 env 透传回归（#742）

| # | 项 | 说明 |
|---|----|------|
| 742 | **治理 env 透传** | `verify-production-topology.sh --offline` 新增 compose 透传回归：`MODERATOR_EMAILS` / `MASTER_ADMINS` / `ALLOW_REGISTRATION` / `DEVELOPER_USER_IDS` 必须出现在 server 环境（与 PUSH_HMAC_SECRET 1.258 同模式，缺省即 fail——治理/管理员/注册配置漂移防护） |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增 4 项透传检查）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.362 2026-08-08 新目标（重建·第 14 次）第 14 轮：verify Caddyfile 关闭管理 API 回归（#743）

| # | 项 | 说明 |
|---|----|------|
| 743 | **Caddy admin off** | `verify-production-topology.sh --offline` 新增 `admin off` 检查：Caddyfile 全局块必须关闭管理 API（防远程配置访问/密钥泄漏，缺省即 fail——此前该安全加固无回归覆盖） |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增 admin off 检查）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.363 2026-08-08 新目标（重建·第 14 次）第 15 轮：verify Dockerfile 多阶段构建回归（#744）

| # | 项 | 说明 |
|---|----|------|
| 744 | **多阶段构建** | `verify-production-topology.sh --offline` 新增 Dockerfile 多阶段构建回归：必须 `AS build`（build 阶段）且运行阶段为 `jre-jammy`（非 JDK，精简镜像体积/攻击面，缺省即 fail）。verify 离线回归校验存在 |

**验证方式**：bash -n verify 通过；verify --offline 全绿（含新增多阶段/JRE 检查）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.364 2026-08-08 新目标（重建·第 14 次）第 16 轮：restore --inspect 附工具版本与源主机（#745）

| # | 项 | 说明 |
|---|----|------|
| 745 | **inspect 溯源** | `restore-production.sh --inspect` 元数据行追加 `tool=`（backup_tool_version）与 `host=`（backup_hostname，1.336/1.356 已写入 METADATA），审计时一眼可见备份来源工具/主机。verify 离线回归校验存在 |

**验证方式**：bash -n restore / verify 通过；verify --offline 全绿（含新增 backup_hostname 检查）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.365 2026-08-08 新目标（重建·第 14 次）第 17 轮：update.sh origin/上游分支前置守卫（#746）

| # | 项 | 说明 |
|---|----|------|
| 746 | **update 守卫** | `update.sh` 新增两个前置守卫：① 无 `origin` 远程 → 明确报错并给 `git remote add origin` 指引；② HEAD 无上游跟踪分支 → 报错并给 `git branch --set-upstream-to=origin/<branch>` 指引（此前 `git pull --ff-only` 会报晦涩错误）。verify 离线回归校验存在 |

**验证方式**：bash -n update / verify 通过；verify --offline 全绿（含新增 origin/upstream 检查）；check-string-parity zh=en=2821。按用户要求未跑编译/测试。

## 1.366 2026-08-08 新目标（重建·第 14 次）第 18 轮：通话记录长按「查看资料」+操作菜单（#747）

| # | 项 | 说明 |
|---|----|------|
| 747 | **通话记录菜单** | `CallHistoryScreen` 长按单条由「删除确认弹窗」升级为底部操作菜单：① 删除该条通话记录（保留原单条删除 + 未接来电 Room 同步逻辑）；② 查看对方资料（新 `onOpenProfile` 回调，默认空实现）。NavGraph 接线跳 `Routes.authorProfile(userId)`。新增 `call_history_open_profile` 资源；删除废弃的 `call_history_delete_title`/`call_history_delete_confirm`（无引用）；`call_history_delete` 改为带 `%1$s` 格式（删除与 %1$s 的通话记录） |

**验证方式**：brace_strip delta=0（screen+NavGraph）；check-string-parity zh=en=2820（净删 1 条，两侧一致）。按用户要求未跑编译/测试。

## 1.367 2026-08-08 新目标（重建·第 14 次）第 19 轮：status.sh 本地部署健康探测 scheme 回退（#748）

| # | 项 | 说明 |
|---|----|------|
| 748 | **本地健康探测** | `status.sh` 健康探测由硬编码 `https://$PUBLIC_HOST` 改为 scheme 感知：`PUBLIC_HOST=localhost/127.0.0.1/::1/裸 IP` 时用 `http` + `curl -kL`（本地 Caddy 自签证书 + 80 端口 308 跳转，严格 https 探测必失败），真实域名保持严格 https 校验（防中间人）。--json 的 `health.url` 与普通模式健康行同步反映 scheme。verify 离线回归校验存在 |

**验证方式**：bash -n status / verify 通过；scheme 分支手测（localhost/IP→http，chat.example.com→https）；verify --offline 全绿；check-string-parity zh=en=2820。按用户要求未跑编译/测试。

## 1.368 2026-08-08 新目标（重建·第 14 次）第 20 轮：会话列表多选批量操作（#749）

| # | 项 | 说明 |
|---|----|------|
| 749 | **会话多选** | 对标 TG/微信的会话列表批量操作：长按菜单新增「多选」入口（先勾选当前会话），进入多选模式后顶栏变为「已选 N」+ 置顶/已读/删除操作条，点按行勾选（前置复选框+高亮），系统返回优先退出多选。ViewModel 新增 `selectionMode`/`selectedChatIds` 状态与 `enterSelectionMode`/`exitSelectionMode`/`toggleSelectChat`/`batchTogglePinSelected`/`batchMarkReadSelected`（复用 `ApiService.batchMarkRead` 一次请求）/`batchDeleteSelected`。新增 `chat_list_selected_count`/`chat_multi_select` 资源 |
| — | **顺手修复** | ChatListScreen 公告 AlertDialog 块括号失衡（`}` 误为 `)` 所致函数提前闭合，delta=-1）修正为 `)` 闭合调用，brace delta 归 0 |

**验证方式**：brace_strip delta=0（screen+viewmodel）；check-string-parity zh=en=2822。按用户要求未跑编译/测试。

## 1.369 2026-08-08 新目标（重建·第 14 次）第 21 轮：deploy 健康等待 scheme 感知（#750）

| # | 项 | 说明 |
|---|----|------|
| 750 | **deploy 本地健康等待** | `deploy.sh` 健康等待与 status.sh 1.367 对齐：`PUBLIC_HOST=localhost/127.0.0.1/::1/裸 IP` 时用 `http` + `curl -kL`（本地 Caddy 自签证书 + 80 端口 308 跳转，原严格 https 必失败），真实域名保持严格 https 校验。就绪提示同步显示 scheme。verify 离线回归校验存在 |

**验证方式**：bash -n deploy / verify 通过；verify --offline 全绿（含新增 deploy health_scheme 检查）；check-string-parity zh=en=2822。按用户要求未跑编译/测试。

## 1.370 2026-08-08 新目标（重建·第 14 次）第 22 轮：restore 停服前 tar 校验 + 健康等待 scheme 感知（#751）

| # | 项 | 说明 |
|---|----|------|
| 751 | **restore 加固** | `restore-production.sh` 两处：① 停服+交互确认前校验 `uploads.tar.gz`/`caddy-data.tar.gz` 非空且 `gzip -t` + `tar -tzf` 可读（此前仅 dump 有校验，损坏 tar 会「清空目录后才发现无法解压」）；② 健康等待 scheme 感知（与 status.sh 1.367 / deploy.sh 1.369 对齐：localhost/IP → http + curl -kL，真实域名严格 https）。verify 离线回归各新增一项校验 |

**验证方式**：bash -n restore / verify 通过；verify --offline 全绿（含新增 gzip -t / health_scheme 检查）；check-string-parity zh=en=2822。按用户要求未跑编译/测试。

## 1.371 2026-08-08 新目标（重建·第 14 次）第 23 轮：deploy --doctor 校验 docker 守护进程（#752）

| # | 项 | 说明 |
|---|----|------|
| 752 | **daemon 预检** | `deploy.sh --doctor` 新增 docker 守护进程活性校验：`docker compose version` 不连 daemon，若守护进程未运行部署会直接失败，doctor 此前会误报「OK」。现在 `docker info` 校验 daemon，未运行则 `[FAIL]` 并给出启动指引（systemctl/Desktop）。verify 离线回归校验存在 |

**验证方式**：bash -n deploy / verify 通过；verify --offline 全绿（含新增 docker daemon 检查）；check-string-parity zh=en=2822。按用户要求未跑编译/测试。

## 1.372 2026-08-08 新目标（重建·第 14 次）第 24 轮：deploy.ps1 -Doctor 守护进程校验对齐（#753）

| # | 项 | 说明 |
|---|----|------|
| 753 | **ps1 daemon 对齐** | `deploy.ps1 -Doctor` 新增 docker 守护进程活性校验（`docker info`，与 deploy.sh 1.371 对齐），daemon 未运行报 `[FAIL]` 并给启动指引。verify 离线回归新增校验 |

**验证方式**：ps1 Parser 解析通过；verify --offline 全绿（含新增 deploy.ps1 docker daemon 检查）；check-string-parity zh=en=2822。按用户要求未跑编译/测试。

## 1.373 2026-08-08 新目标（重建·第 14 次）第 25 轮：多选批量删除加确认（#754）

| # | 项 | 说明 |
|---|----|------|
| 754 | **批量删除确认** | 1.368 的多选批量删除原本点删除图标立即清空，易误触。现在点删除图标先弹确认框（`确定删除选中的 N 个会话吗？`），确认后才执行 `batchDeleteSelected`。新增 `chat_list_batch_delete_confirm` 资源（zh/en 各一） |

**验证方式**：brace_strip delta=0（ChatListScreen）；check-string-parity zh=en=2823。按用户要求未跑编译/测试。

## 1. 总体状态


| 维度 | 状态 | 说明 |
|------|------|------|
| 目标 | **进行中** | 对标 Telegram / 微信 / QQ 的轻量丝滑 IM，集成优点；Signal 级 E2EE；强后台；AI；官网 + 开发者机器人 |
| 策略 | **先功能后修 bug** | 功能完善期 **不频繁** 全量编译 / 全量测试，优先吞吐；功能面收敛后再无限查修 |
| 主 IM 闭环 | **代码面基本齐全** | 单聊 / 群 / 动态 / 通话 / 附件 / 通知 / 设置 |
| 密聊隐私 | **#60–#70 已形成栈** | runtime 门控 + 客户端 prefs + bot + admin + 群玩法 / Markdown / 官网；#67 typing / #68 read-receipt / #69 presence / #70 lastSeen 门控 |
| Bot / 后台 | **持续增强** | Bot 开发者 API、管理后台 CSV / 运行时开关、公开官网文案已跟到 surface 66 |
| E2EE | **主干可用，未达宣称** | libsignal 1:1 + 群 Sender Key + 加密附件；sealed-sender / PQXDH 多为 **flag + 客户端门控**，非完整密码学闭环 |
| 可发布 | **否** | 缺设备矩阵、生产拓扑填表、弱网 / 多端 E2EE 证据 |

**一句话**：基础 IM + 密聊防泄漏栈已大幅推进；距离「媲美 TG/微信/QQ + 无 bug」仍远，Goal 保持 **active**。

---

## 2. 当前阶段焦点

### 已优先完成（密聊与隐私）

1. **防截屏 / 录屏 / 多任务预览**：`FLAG_SECURE`、recents 排除、密聊策略  
2. **盲水印 + 可见水印**：DCT-QIM 频域盲水印、可见叠加（时间 / 用户 ID 等）、后台提取路径  
3. **截屏检测与对端告警**：`ScreenshotDetector` + `CAPTURE_ALERT:` + prefs  
4. **阅后即焚 / 消失计时 / spoiler / view-once**  
5. **密聊交互收紧**：复制、媒体导出分享、转发、会话导出、外链预览、外链打开、通知预览、列表预览、反应、标星  
6. **Sealed-sender / PQXDH**：证书拉取缓存与客户端开关（**未**等同 Signal 完整 sealed-sender 证书链）

### Feature surface 账本（2026-07-27 更新）

| # | 主题 | Runtime keys（主） | Bot 健康名 | 客户端门控要点 |
|---|------|-------------------|------------|----------------|
| 60 | 复制 / 媒体导出 | `secret_copy_block_enabled`, `secret_media_export_block_enabled` | `leakz` | 密聊禁止复制与媒体导出分享 |
| 61 | 转发 / 会话导出 | `secret_forward_block_enabled`, `secret_chat_export_block_enabled` | `vaultz` | 密聊禁止转发与 chat export |
| 62 | Sealed / PQXDH 客户端开关 | `sealed_sender_enabled`, `pqxdh_preview` | `sealz` | 证书预取 / 预览门控 |
| 63 | 可见水印 / 自动消失 | `visible_watermark_enabled`, `secret_auto_disappear_enabled` | `markz` | 开密聊默认 24h 消失（可关） |
| 64 | 链接隐私 | `secret_link_preview_block_enabled`, `secret_external_link_block_enabled` | `linkz` | 密聊禁预览；外链打开默认更松 |
| 65 | 通知 / 列表预览 | `secret_notif_preview_block_enabled`, `secret_list_preview_block_enabled` | `privz` | AppNotifier + 会话列表草稿/预览 |
| 66 | 反应 / 标星元数据 | `secret_reaction_block_enabled`, `secret_star_block_enabled` | `metaz` | `requireReactions` / `toggleStarMessage` |
| 67 | 输入状态侧信道 | `secret_typing_block_enabled` | `typtz` | `announceTypingStarted` / `stopTypingAnnouncement` |
| 68 | 已读回执侧信道 | `secret_read_receipt_block_enabled` | `redz` | `markReadJob` / `markAllAsRead` / `onCleared` |
| 69 | 在线状态侧信道 | `secret_presence_block_enabled` | `presz` | presence 推送门控 |
| 70 | 最后上线时间侧信道 | `secret_last_seen_block_enabled` | `lastsz` | last-seen 显示门控 |

注：surface 67–70 已在 `RuntimeConfigService` / `/api/public/status` / Bot flags / `SecretXxxPrefs` / 群玩法 / Markdown / admin 全链路对齐。详见 `docs/bot-developer-api.md` §3.1。

**Surface 统一落地模式**（后续 #67+ 沿用）：

1. `RuntimeConfigService` keys / defaults / known / getters  
2. `/api/public/status` 与 feature JSON 短名  
3. 客户端 `*Prefs` + `ChatListScreen` 同步  
4. ViewModel / Policy / UI 门控  
5. Bot routes + `listCapabilities` + 唯一 health 名  
6. Admin CSV + `admin.js` 行  
7. 群玩法 + 菜单 + strings + Markdown 快捷符 + Motion  
8. 文档 + 官网 article  
9. **静态核验**（路由存在、prefs 同步、能力表对齐）；功能期避免全量 Gradle

### 下一刀（未完成）

| 优先级 | 项 | 说明 |
|--------|----|------|
| P0 | **#67** | 密聊 **正在输入 / 已读回执** 门控（钩子已在 `ChatDetailViewModel` / WS） |
| P0 | 继续 #68+ 密聊 / 群玩法 / 后台硬化 | 同 pattern 批量化 |
| P1 | 真 sealed-sender / PQXDH 会话密码学 | 现仅 flag 级 |
| P1 | ChatList 恢复文件 polish | 曾被清空后重写，功能可用，细节可能回退 |
| P2 | 功能面收敛后无限 bug hunt | 子代理 + 静态扫描；**少次**编译 |
| P2 | iOS / 桌面 / SFU / 国内推送 | 产品级未做 |

---

## 3. 代码与工程体量（约数，会漂）

| 模块 | 说明 |
|------|------|
| Android App | Kotlin + Compose；Room/SQLCipher；libsignal；WebRTC；FCM |
| Server | Ktor + Exposed + JWT；可选 Postgres；Admin 静态页；Bot REST |
| 公共文档 | `docs/*` 台账 / 验收清单 / Bot API |
| 官网 | `server/src/main/resources/public/index.html` |
| 管理台 | `server/src/main/resources/admin/*` + `AdminRouting.kt` |

**注意**：`ChatDetailViewModel.kt`、`ChatDetailScreen.kt`、`Routing.kt`、`GroupPlayPolicy.kt` 仍然 **超大**，变更需谨慎（禁止写空文件；优先 Node/Python 补丁）。

### 关键事故（已恢复）

| 事件 | 处理 |
|------|------|
| `ChatListScreen.kt` 曾被写空 | 已按 NavGraph API 与 prefs 同步面重写恢复；可能丢失部分原 UI 抛光 |

---

## 4. 验证策略（当前）

| 类型 | 现状 |
|------|------|
| 功能期全量 Gradle / 全测 | **刻意少跑**（用户要求加速） |
| 静态检查 | 路由字符串、prefs 键、`listCapabilities`、brace 粗扫、文档对照 |
| 历史单测 / 编译记录 | 早期会话有过绿记录；**2026-07-20 密聊 surface 大批量后未作为完成门禁重跑** |
| 真机 / 双机 E2EE / 弱网 | 仍依赖 `docs/*-verification.md` 人工填表 |

**Routing.kt**：历史存在 brace delta ≈ 1 的已知噪声；新增 surface 时以静态存在性检查为主，不全量编译。

---

## 5. 关键路径速查

| 领域 | 路径 |
|------|------|
| 运行时配置 | `server/.../service/RuntimeConfigService.kt` |
| 用户 API / Bot | `server/.../plugins/Routing.kt` |
| 管理后台 API | `server/.../plugins/AdminRouting.kt` |
| 管理台前端 | `server/src/main/resources/admin/admin.js` |
| 密聊 prefs | `app/.../util/Secret*Prefs.kt`、`ScreenSecurePrefs`、`BlindWatermarkPrefs` 等 |
| 聊天核心 | `ChatDetailViewModel.kt`、`ChatDetailScreen.kt`、`ChatListScreen.kt` |
| 群玩法 | `GroupPlayPolicy.kt` |
| Markdown / 动效 | `MarkdownMessage.kt`、`Motion.kt` |
| 功能台账 | `docs/feature-inventory.md` |
| Bot 开发者文档 | `docs/bot-developer-api.md` |

---

## 6. 与目标的差距（诚实）

| 目标表述 | 当前判断 |
|----------|----------|
| 媲美 TG / 微信 / QQ 且轻量丝滑 | **未达成** — 主功能有，体验 / 可靠性 / 跨端未闭环 |
| 最高级防截图录屏 + 盲水印取证 | **代码面强** — 真机取证与对抗性验证未完成 |
| 后台极致 | **持续完善** — 开关 / 导出 / 审计多，角色体系与体验仍可挖 |
| 官网 + 开发者机器人 | **基本可用骨架** — Bot API 面大；开放平台产品化仍浅 |
| 单聊阅后即焚等 | **有** — 与密聊策略联动 |
| 群聊更多玩法 | **大量 GroupPlay + Markdown 快捷符** — 可持续加 |
| Markdown 发送与渲染 | **有** |
| Signal 级 E2EE | **主干有，级称未证** — sealed-sender/PQ/多端矩阵未完成 |
| 先完善功能再修到无 bug | **仍在功能期** — 未进入「无限修到无 bug」收口 |

---

## 7. 工作约定（给后续代理 / 协作者）

1. Goal **保持 active**，勿因「做了很多 surface」就标 complete。  
2. 功能期：**少编译、少全测**；以静态核验推进。  
3. 新能力优先走 **surface pattern**（见 §2），health 名全局唯一。  
4. Sealed-sender：**禁止**在聊天 WS/历史路径 redact `senderId`。  
5. Admin / Bot 导出：**禁止**倾倒 E2EE 明文正文。  
6. 大文件补丁用 Node/Python；写后检查非空、关键符号、能力表同步。  
7. `ChatListScreen` 恢复后的 polish 可做，但勿再次整文件清零。  
8. 文档：本文件记阶段与差距；`feature-inventory.md` 记功能完整度；验收证据只写 `*-verification.md`。

---

## 8. 近期交付快照（#60–#70 + ConnectionService）

| Surface | Health | 要点 |
|---------|--------|------|
| 60 | leakz | 复制锁 / 媒体导出封 |
| 61 | vaultz | 转发封 / 会话导出锁 |
| 62 | sealz | sealed + pqxdh 客户端开关 |
| 63 | markz | 可见水印 + 密聊自动消失 |
| 64 | linkz | 链接预览 / 外链 |
| 65 | privz | 通知预览 / 列表预览 |
| 66 | metaz | 反应 / 标星元数据封堵 |
| 67 | typtz | 密聊 typing 门控 — 防输入状态侧信道 |
| 68 | redz | 密聊 read-receipt 门控 — 防已读回执侧信道 |
| 69 | presz | 密聊 presence 门控 — 防在线状态侧信道 |
| 70 | lastsz | 密聊 last-seen 门控 — 防最后上线时间侧信道 |

**#67 / #68**：typing 与 read-receipt 门控已按 #66 同模式落地（prefs / runtime key / bot 健康与 hint 路由 / 群玩法 / Markdown / admin / 文档）。

**#69 / #70**：presence 与 last-seen 门控按 #67/#68 同模式落地（`SecretPresenceBlockPrefs` / `SecretLastSeenBlockPrefs` + runtime key + `presz` / `lastsz` 健康与 hint 路由 + `PRESENCESEAL` / `LASTSEENSEAL` 群玩法 + `~ps` / `~ls` Markdown + admin / 文档）。

### 8.1 ConnectionService 系统来电集成（2026-07-21）

| 文件 | 变更 |
|------|------|
| `app/.../telecom/TelecomHelper.kt` | 新建：PhoneAccount 注册 + addNewIncomingCall 派发 |
| `app/.../telecom/MaodouchatConnectionService.kt` | 新建：self-managed ConnectionService + onAnswer/onReject/onDisconnect/onAbort |
| `app/src/main/AndroidManifest.xml` | +READ_PHONE_STATE / +MANAGE_OWN_CALLS 权限 + BIND_TELECOM_CONNECTION_SERVICE 声明 |
| `app/.../MaodouchatApp.kt` | onCreate 注册 PhoneAccount |
| `app/.../ui/navigation/NavGraph.kt` | 来电 setPending 后触发 TelecomHelper.placeIncomingCall |
| `app/.../MainActivity.kt` | consumeNotificationIntent 处理 ANSWER_CALL / INCOMING_CALL intent |

### 8.2 Feature Inventory 修正（2026-07-21）

| 项目 | 原标注 | 修正后 |
|------|--------|--------|
| 独立 TOTP/2FA | 未做 | 完整（TotpService + 4 endpoints + 客户端 setup/login UI） |
| 多管理员角色体系 | 未做 | 完整（3 角色 + 权限矩阵 + 审计日志 + 客户端 UI） |
| ConnectionService 系统来电 | 未做 | 完整（self-managed + TelecomManager + Connection 回调） |

### 8.3 B2 服务端开关 → App 端消费闭环（2026-08-02）

**问题**：B2 服务端 `GET /api/public/status` 已下发 8 个密聊 surface 开关（screenshotBurn / autoDestroy / forwardWhitelist / simChange / twoFaGate / newDeviceRisk / deviceVerify / sessionNotice），但 App 端从未解析消费——服务端开关形同虚设。

**修复**：`ChatListScreen.kt` 的 public/status 同步块末尾追加解析 `secretSurfaceFlags` 嵌套对象，8 个开关分别写入对应 `SecretXxxPrefs`（SecretScreenshotBurnPrefs / SecretAutoDestroyPrefs / SecretForwardWhitelistPrefs / SecretSimChangePrefs / Secret2faGatePrefs / SecretNewDeviceRiskPrefs / SecretDeviceVerifyPrefs / SecretSessionNoticePrefs），App 本地行为与全局运维开关同步。

**验证**：App 编译通过 + `:app:testDebugUnitTest` 全绿（483 单测），无回归。

### 8.4 调优循环 #67–#74：B2 功能上线补齐 + 服务端开关消费（2026-08-02）

本轮修复 6 个「代码存在但从未接线」的 B2/B4 功能缺口：

| # | 问题 | 修复 |
|---|------|------|
| 67 | `ScreenshotBurnDetector`（截屏即焚 burnz）从未实例化 | ChatDetailScreen 密聊会话内 LaunchedEffect 接入（MediaStore 双通道 + ScreenCaptureCallback，焚毁本地解密缓存并提示） |
| 68 | `AiConversationProfile` / `AiWeeklyReport`（会话画像/周报）零调用者 | ChatInputBar AI 菜单加两项 + ConversationProfileDialog / WeeklyReportDialog；密聊会话禁用 |
| 69 | `SecretForwardWhitelistPrefs`（转发白名单 fwlz）无消费 | ChatDetailViewModel.forwardMessage 拦截非白名单目标（空白名单=完全禁止） |
| 70 | `Secret2faGatePrefs`（双因素门禁 2faz）无消费 | SensitiveActionGate 新增 `confirmSystemAuth`（无门控系统认证）；进入密聊会话时验证，窗口期内免重复 |
| 71 | `maintenance` 键名不匹配（服务端下发 `maintenance`，App 读 `maintenanceMode`） | ChatListScreen 双键名兼容 |
| 72 | `registrationOpen` / `inviteOnlyHint` / `aiEnabled` 服务端开关零消费 | LoginScreen 拉 status 显示维护/邀请横幅 + 注册 tab 禁用；RuntimeFlags 新增 `AI_MASTER` 总开关 → AiEntryPolicy context 重载 → ChatDetailScreen 5 调用点 |
| 73 | B2 8 开关无设置 UI（字符串已定义但无渲染） | AccountSecurityScreen 新增「密聊安全」SecurityGroup，8 个 Switch 全接入（截屏即焚/自动销毁/转发白名单/SIM 防护/2FA 门禁/新设备风控/设备核验/双向提示） |
| 74 | 跨聊问答 | 已由全局 AI 搜索覆盖（同能力，不重复接入） |

**验证**：`maintenance` 键名修复 + AI_MASTER + B4 画像/周报对话框先过一轮；2FA 门禁/转发白名单/设置 UI 追加后回归中。所有改动均为追加式，未触碰红线文件既有逻辑。

### 8.5 调优循环 #75–#80：情绪回复 + 公告中心（2026-08-02）

| # | 变更 |
|---|------|
| 75 | B4 情绪回复（AiEmotionReply）接入：AI 菜单「情绪回应」→ 检测会话情绪生成回复（服务端 emotion-reply 路由 + 本地模板回退），生成后填入输入框待确认发送；密聊会话禁用 |
| 76 | 公告中心：ApiService 新增 `getActiveAnnouncements` / `ackAnnouncement`；ChatListViewModel 登录后拉取 → AnnouncementPolicy.filterForDisplay（未读+生效窗口+级别过滤）；ChatListScreen 高优先级（EMERGENCY/MAINTENANCE）公告弹窗不可跳过，确认后 ack |
| 77 | `AnnouncementPolicy`（此前零消费者）正式启用 |
| 78 | B2 设置 UI 收尾：`settings_secret_security` 区块标题 + `announcement_title_default` 等字符串 |
| 79 | 修正 2 处重复字符串（secret_2fa_gate_title / secret_auto_destroy_ttl_hint 与既有定义冲突）→ 改用既有资源名 |
| 80 | 服务端 `publicSecretSurfaceFlags()` 8 键名与 App 消费键名逐一核对一致（8/8） |

**B4 六能力现状**：写作风格 / 智能回复 / 总结 / AI 搜索（跨聊问答语义）原已接入；本轮补齐 会话画像 / 周报 / 情绪回复 —— 六能力全部有 UI 入口。

**验证**：公告中心 + 情绪回复接入后 `:app:testDebugUnitTest` BUILD SUCCESSFUL（3m30s），无回归。

### 8.6 调优循环 #81–#85：B2 八 surface 全闭环（2026-08-02）

此前 8 个 B2 密聊 surface 中 3 个有行为（自动销毁/SIM 防护/截屏即焚）、5 个仅有 Prefs 写入。本轮补齐全部行为，8 个 surface 全部「设置 UI + Prefs + 行为」闭环：

| Surface | 行为接入 |
|---------|----------|
| burnz 截屏即焚 | ScreenshotBurnDetector（ChatDetailScreen 密聊会话内） |
| ttlz 自动销毁 | SecretSessionTtl + SecretAutoDestroyPrefs |
| fwlz 转发白名单 | ChatDetailViewModel.forwardMessage 拦截非白名单目标 |
| simz SIM 变更 | SimChangeWatcher（既有） |
| 2faz 双因素门禁 | SensitiveActionGate.confirmSystemAuth 新增 + 进入密聊系统认证，窗口期内免重复 |
| ndz 新设备风控 | Android ID+安装时间窗设备指纹；首次进入密聊弹登记对话框，未登记保持锁定 |
| dvz 设备核验 | 进入密聊自动弹安全码页；verifyAndTrustIdentity 成功后本地 markFingerprintVerified |
| sntz 双向提示 | 开关关闭时隐藏密聊横幅（服务端无逐用户密聊状态，退化为本机文案控制） |

另：#81 公告 ack 防重入（inFlight set，ViewModel 层）；#82 修正 `secret_new_device_risk_registered` 字符串重复（改用既有定义）。

**验证**：`:app:testDebugUnitTest` BUILD SUCCESSFUL（2m48s），无回归。B2 八 surface 全部「服务端开关 → App Prefs → 设置 UI → 行为」闭环完成。

### 8.7 调优循环 #86：管理端点混合 mapOf 运行时崩溃修复（2026-08-02）

**问题**：`GET /api/admin/users/{id}/sessions`（会话/设备/推送列表）与 `GET /api/admin/messages/search` 使用混合类型 `mapOf`（String+Long+Boolean+Long?）+ `Json.encodeToString` —— 编译可通过，但**运行时** kotlinx.serialization 对 `Map<String, Comparable<*> & Serializable>` 无 serializer → SerializationException → 400「请求体格式无效」。此前 56 个 server 测试未覆盖这两个端点，生产必崩。

**修复**：两端点改为 `buildJsonObject`/`JsonArray` 直接构造 JSON（AdminRouting.kt sessions/devices/push/rows 四块），并新增 AdminEnhanceRoutesTest 断言（refreshSessions/signalDevices/pushTokens 键存在）防回归。

**验证**：`:server:test` BUILD SUCCESSFUL（5m52s，含新断言）；全仓库扫描确认无同类「混合 mapOf + encodeToString」残留（剩余 mapOf 均为 String→String 统一类型或 @Serializable DTO）。

### 8.8 调优循环 #87–#91：optString "null" 字面量 bug 簇（2026-08-02）

**问题**：`JSONObject.optString(key)` 在键缺失时返回字面字符串 `"null"`（而非空串），`.ifBlank` 无法过滤 → 横幅显示 "null"；更严重的是 P0 修复（服务端不再下发 pushHmacKey）后 App 端 `optString("pushHmacKey")` 返回 "null" 并被写入 PushVerifyPrefs → 存量新装设备以垃圾 key 校验所有 FCM 推送签名（fail-closed），推送全部被拒。

| # | 文件 | 修复 |
|---|------|------|
| 87 | ChatListScreen.kt | status 同步块新增 `safeOpt`（has 检查 + 排除 "null"），banner/e2eeBanner/announcement/maintMsg/minApp/pushHmacKey 全部改用 |
| 88 | PushVerifyPrefs.kt | `getKey` 排除字面 "null"（存量垃圾 key 自动降级 fail-open）；`setKey` 拒绝 "null" |
| 89 | LoginScreen.kt | inviteOnlyHint/maintenanceMessage 显式排除 "null" |
| 90 | ChatListViewModel.kt | 公告解析 safeOpt + id 为空直接丢弃 |
| 91 | WebSocketClient.kt | AdminBroadcast fallback 的 text 排除 "null" |

低风险项复核：SealedSenderSupport 证书解析（"null" 走 owner mismatch 失败路径，行为等价）、ApiService.accessTokenSubject（JWT 必有 sub，实际不触发）、MessageBubble pollJson（发送方必含字段）——均不改。

**验证**：`:app:testDebugUnitTest` 两轮全绿（1m31s / 46s），无回归。

### 8.9 调优循环 #92：转发白名单管理 UI（2026-08-02）

**问题**：转发白名单开关开启后无任何管理入口 → 白名单恒为空 → 密聊转发被完全禁止（空白名单=完全禁止语义），用户无法解锁。

**修复**：ChatDetailScreen 转发目标对话框内联管理——源为密聊会话且白名单开启时：非白名单目标显示「加入白名单」按钮（点击即 setWhitelist 加入并 toast 确认），白名单目标正常转发；非白名单目标转发按钮禁用。设置页开关 + 对话框内联管理 = 完整闭环。

**验证**：`:app:testDebugUnitTest` BUILD SUCCESSFUL（1m37s），无回归。

### 8.10 调优循环 #93：新设备风控「拒绝即锁定」落实（2026-08-02）

**问题**：新设备风控（ndz）此前仅弹登记对话框，用户拒绝后仅 toast——密聊内容仍照常展示，「未登记设备密聊锁定」语义未落实。

**修复**：ChatDetailScreen 拒绝登记 → `deviceRiskLocked=true` → 复用 ChatLockGate 同款门控渲染链（chatLockPending → deviceRiskLocked → chatLockBlocking）显示锁定页（提示 + 「登记当前设备」按钮重新登记）；登记成功后解锁。LaunchedEffect 增加 deviceRiskLocked 依赖防循环。

**验证**：`:app:testDebugUnitTest` BUILD SUCCESSFUL（54s），无回归。

### 8.11 调优循环 #94：密聊进入三道门串行抑制（2026-08-02）

**问题**：2FA 门禁（系统认证）/ 设备核验（安全码页）/ 新设备风控（登记）三个 LaunchedEffect 相互独立——2FA 认证被拒后，后两个弹窗仍触发，用户被三层弹窗轰炸。

**修复**：新增 `secretGateBlocked` 状态——2FA 开启且未通过时置 true，设备核验与风控的 LaunchedEffect 增加该依赖并提前返回；2FA 成功/门已开时置 false。密聊进入三道门串行：2FA 通过 → 设备核验 → 风控登记。

**验证**：`:app:testDebugUnitTest` BUILD SUCCESSFUL（43s），无回归。

### 8.12 调优循环 #95：推送 HMAC key 认证通道（P0 修复后续）（2026-08-02）

**背景**：P0 修复移除了 status 端点匿名暴露 pushHmacKey（防止伪造 FCM 签名），但无替代通道 → 新装客户端永远拿不到密钥（fail-open 校验），存量客户端靠本地缓存。

**实现**（对称密钥经认证通道下发）：
- 服务端 `GET /api/push/verify-key`（auth-jwt 认证）：配置了非 dev 密钥时返回 `{"key": ...}`，否则 `{"key": null}`；匿名 401
- App ChatListViewModel 登录后拉取 → 有 key 则 PushVerifyPrefs.setKey；服务端明确 null 则 clearKey（fail-open）；PushVerifyPrefs 新增 `clearKey`
- 测试：MinimalRouteTest 断言认证可取 + 匿名 401

**验证**：`:server:test` BUILD SUCCESSFUL（7m40s）+ `:app:testDebugUnitTest` BUILD SUCCESSFUL（2m22s），无回归。

### 8.13 调优循环 #96–#98：公告刷新时机 + 领域复核（2026-08-02）

| # | 变更 |
|---|------|
| 96 | 公告仅启动时拉取（无推送机制）→ ChatListScreen ON_RESUME 追加 `refreshAnnouncements()`，回到前台即同步新公告 |
| 97 | 复核：群玩法 4 Screen + NavGraph 路由完整；Room 迁移 5→28 完整；SQLCipher SupportFactory+passphrase 清零；通知渠道 3 个（消息/通话/AI 任务）含描述；votePk forUpdate 行锁+每人一票防刷；AI 预算按 JWT subject 归属；服务端 B4 DTO 与 App 请求模型字段匹配；AiEnhanceHttp BASE_URL+token+timeout+CancellationException 处理正确 |
| 98 | Release 重建（含全部新代码）：R8 通过，APK 12.32MB |

**验证**：`:app:testDebugUnitTest` BUILD SUCCESSFUL（1m45s），无回归。

### 8.14 调优循环 #99：密聊 AI 泄漏防御（安全高危）（2026-08-02）

**问题**：密聊会话（E2EE）中的全部 AI 功能均未 gate——消息本地解密后，总结/改写/建议回复/翻译/转写/图像文件分析/群 AI/语义搜索会把**解密明文发送到服务端 AI**。服务端无法区分密聊（请求体只有明文文本），唯一防线在 App 端，此前完全缺失。

**修复**（三层防御）：
1. **菜单层**：AI 菜单全部 16 项（改写 11 项 + 智能回复 9 tone + 总结/画像/周报/情绪回应/群 AI/任务）`enabled = aiEnabled && !isSecretChat`；长按消息 AI 动作区 `state.isSecretChat != true` 时隐藏
2. **执行层**：9 个入口函数（requestAiSummary / requestAiRewrite / requestAiSuggestions / transcribeVoiceMessage / analyzeImageMessage / analyzeFileMessage / translateTextMessage / groupAssistant / requestSemanticSearch）开头密聊检查 → `secret_chat_ai_blocked` 提示
3. **链路确认**：rewriteDraft/generateAiSuggestions/semanticSearch 流式实现由已 gate 的 PendingAiAction 分派触发 ✅

**复核**：AiArchiveSuggestion 无调用者（无泄漏面，暂不接入）；离线 AI 建议由同一链路 gate。

**验证**：`:app:testDebugUnitTest` BUILD SUCCESSFUL（2m47s），无回归。

### 8.15 调优循环 #100：密聊无活动 TTL 清扫上线（2026-08-02）

**背景**：SecretSessionTtl.sweepExpired 因 SecretChatEntity 无活动时间字段而无法接入（WatchdogWorker 注释标记"暂不接入"）——密聊自动销毁（ttlz）的会话级语义缺失。

**实现**：
- Room 迁移 28→29：`secret_chats` 加 `lastActivityAt INTEGER NOT NULL DEFAULT 0`
- SecretChatDao：`touchActivity(chatId, now)` + `listActivity(): List<SecretChatActivity>`（Room 不支持 Map 返回，用 data class）
- ChatDetailScreen：密聊会话驻留心跳 LaunchedEffect——每 60s touchActivity（进入立即 touch），离开取消
- SecretSurfaceWatchdogWorker：周期任务（15min）追加 TTL 清扫——listActivity → sweepExpired → 销毁过期会话本地解密缓存（Log 记录）

**验证**：`:app:testDebugUnitTest` BUILD SUCCESSFUL（3m），无回归。密聊自动销毁完整闭环：开关（设置 UI）→ 活动数据（lastActivityAt）→ 心跳（60s）→ 清扫（15min Worker）。

### 8.16 调优循环 #101：公告发布 FCM 推送（2026-08-02）

**背景**：公告发布此前仅落库，App 端依赖 ON_RESUME/启动拉取——用户未打开 App 时收不到高优先级公告。

**实现**：
- 服务端：FcmPushService 新增 `enqueueAnnouncement`（type=ANNOUNCEMENT）；PushTokenRepository 新增 `listUserIds`（去重）；publish 端点发布成功后，EMERGENCY/MAINTENANCE 级公告向受众广播（ALL → 全部注册推送用户；TAGGED → 标签用户分页遍历去重）；configureAdminEnhanceRouting 注入 pushService/pushTokenRepo（默认 null 兼容测试）
- Delivery 新增 `breakthroughDnd`：EMERGENCY 公告突破 DND（维护/紧急语义），MAINTENANCE 遵循静默
- App：MaodouFirebaseMessagingService 新增 ANNOUNCEMENT 分支 → AppNotifier.showAnnouncement（高优先级通知 + BigText + 点击打开 App，详情由公告中心拉取）

**验证**：`:server:test` BUILD SUCCESSFUL（8m21s）+ `:app:testDebugUnitTest` BUILD SUCCESSFUL（3m9s），无回归。

### 8.17 调优循环 #102：公告推送幂等 + EMERGENCY 突破 DND（2026-08-02）

| # | 变更 |
|---|------|
| 102a | publish 端点重复调用会重复 FCM 广播 → 发布前快照公告状态，仅首次发布（此前非 ACTIVE）推送 |
| 102b | Delivery 新增 `breakthroughDnd`：EMERGENCY 公告突破 DND（维护/紧急语义），MAINTENANCE 遵循静默 |
| 102c | 最终验证：`:server:test` BUILD SUCCESSFUL（24m39s，含 WS 取消异常为测试预期路径）+ `:app:testDebugUnitTest` 全绿 + Release APK 12.32MB（12:47，含全部最新代码） |

### 8.18 调优循环 #103：消息内 URL 可点击 + 密聊外链拦截联动（2026-08-02）

**问题**：消息文本/Markdown 中的 URL 此前仅渲染为下划线样式，不可点击——用户无法直接打开链接；密聊外链拦截（SECRET_EXTERNAL_LINK_BLOCK）此前仅 LinkPreviewCard 消费，纯文本 URL 无拦截。

**修复**：
- MarkdownMessage：`[label](url)` 与 bare URL 分支改用 `LinkAnnotation.Clickable` + `LinkInteractionListener`，点击触发 `onLinkClick(url)` 回调
- RichTextContent（非 markdown 文本）：新增 `findUrlRanges` 扫描 http/https 区间，渲染为可点击 LinkAnnotation；URL 优先于 @mention 匹配（避免 URL 内 @ 误判）
- MessageBubble：两处调用点传入 `onLinkClick`——密聊会话且 SECRET_EXTERNAL_LINK_BLOCK 开启时 toast 拦截，否则 Intent.ACTION_VIEW 打开（失败 toast 提示）

**验证**：`:app:testDebugUnitTest` BUILD SUCCESSFUL（3m14s），无回归。

**补充**：#104 链接 scheme 白名单——`[label](url)` 的 url 来自消息内容（可能 `javascript:`/`intent:`），onLinkClick 内统一 Uri.parse + scheme 校验，仅 http/https 放行，其余 toast 拒绝。`:app:testDebugUnitTest` BUILD SUCCESSFUL（49s）。

### 8.19 调优循环 #105：深链接公开资料页接入（2026-08-02）

**问题**：AndroidManifest 注册了 `maodouchat://u/<username>` 与 `https://chat.mdou.me/u/<username>` 深链 intent-filter，但 MainActivity.consumeNotificationIntent 从未解析 intent.data——用户点击外部链接无法打开对应用户资料页。

**修复**：
- NotificationTarget 新增 `PublicProfile(username, ...)` 变体
- consumeNotificationIntent 开头追加深链解析：ACTION_VIEW + intent.data → 提取 username（两种 scheme/host 均支持）→ 设置 PublicProfile target → 清空 intent.data
- 导航 collect 分支追加 `Routes.publicProfile(username)` 跳转

**验证**：`:app:testDebugUnitTest` BUILD SUCCESSFUL（33s），无回归。

### 8.20 调优循环 #106：封禁用户社区互动拦截补齐（2026-08-02）

**问题**：服务端 `rejectIfSuspended` 封禁检查覆盖消息发送/群加入/帖子发布/评论，但好友请求与帖子点赞遗漏——封禁用户仍可发好友请求、点赞动态，绕过社区隔离。

**修复**：
- `POST /api/friends/requests`：创建好友请求前加 `rejectIfSuspended`
- `POST /api/posts/{id}/like`：点赞前加 `rejectIfSuspended`（unlike 允许——封禁用户可取消自己的历史点赞）

**验证**：`:server:test` BUILD SUCCESSFUL（11m30s），无回归。

### 8.21 调优循环 #107：密聊消息搜索索引过滤（2026-08-02）

**问题**：`indexSearchableMessage` 在 4 处调用（消息接收/历史加载/编辑/批量同步）均无密聊过滤——密聊明文会落本地搜索索引。虽然查询端（GlobalSearchScreen）已过滤密聊不展示，且本地 SQLCipher 已加密，但密聊明文进入可搜索缓存违反最小化原则（与 ImageOcrAutoIndexer 主动跳过密聊不一致）。

**修复**：`indexSearchableMessage` 开头加 `secretChatRepo.isSecret(chatId)` 检查，密聊消息直接跳过索引（集中防御，4 处调用点全覆盖）。

**验证**：`:app:testDebugUnitTest` BUILD SUCCESSFUL（2m8s），无回归。

### 8.22 调优循环 #108：密聊 TTL 销毁清理搜索索引（2026-08-02）

**问题**：`SecretSessionTtl.destroySession` 仅清媒体缓存（MediaCache），不删搜索索引——索引过滤启用前已写入的存量密聊消息在 TTL 销毁后仍残留在搜索索引中。

**修复**：destroySession 追加 `messageSearchDao().deleteChatIndex(chatId)`（runBlocking + runCatching 防御），TTL 销毁时同步清理该会话的全部搜索索引。

**验证**：`:app:testDebugUnitTest` BUILD SUCCESSFUL（44s），无回归。

### 8.23 调优循环 #109：SIM 变更清除搜索索引（2026-08-02）

**问题**：`SecretChatSession.clearAllSurfaces`（SIM 变更触发）仅清媒体缓存，不清搜索索引——SIM 变更紧急清除时存量密聊索引残留。

**修复**：clearAllSurfaces 对每个活动密聊会话追加 `messageSearchDao().deleteChatIndex(chatId)`（与 destroySession 一致）。

**验证**：`:app:testDebugUnitTest` BUILD SUCCESSFUL（35s），无回归。

### 8.24 调优循环 #110：低内存压力处理（2026-08-02）

**问题**：MaodouchatApp 无 `onLowMemory`/`onTrimMemory` 处理——低内存时图片缓存（Coil，最大内存 15-20%）不释放，低端设备 OOM 风险。

**修复**：覆写 `onLowMemory`（清 Coil 内存缓存）+ `onTrimMemory`（TRIM_MEMORY_RUNNING_LOW 及以上清缓存），与登出清理复用同一 `memoryCache?.clear()`。

**验证**：`:app:testDebugUnitTest` BUILD SUCCESSFUL（33s），无回归。

### 8.25 调优循环 #111：用户列表 limit 参数未传递（2026-08-02）

**问题**：`GET /api/users` 空 q 分支调用 `userRepo.getAll()` 未传 limit 参数——端点计算的 limit（默认 30，上限 100）被忽略，getAll 用默认 100，客户端请求 limit=10 仍返回 100 条。

**修复**：`getAll(limit)` 传入端点计算的 limit。

**验证**：`:server:test` BUILD SUCCESSFUL（7m9s），无回归。

### 8.26 调优循环 #112：全库数据保留期清理大会战（2026-08-03）

**背景**：双端扫描发现 16 张表/4 类本地缓存无任何清理机制，长期运行无限增长。

**服务端（12 张表全部接入 6h 周期清理循环）**：

| 表 | 清理方法 | 保留期 |
|---|---|---|
| `ai_audit_logs` | `AiRepository.purgeOldAuditLogs` | 90 天 |
| `announcement_acks` | `purgeAdminOperationalData` | 90 天 |
| `device_event_consistency_log` | `purgeAdminOperationalData` | 30 天 |
| `audit_export_records` | `purgeAdminOperationalData` | 180 天 |
| `moderation_audit_log` | `purgeAdminOperationalData` | 365 天 |
| `friend_requests`（PENDING） | `FriendRepository.expireStalePending` | 30 天 |
| `group_checkins` / `group_chains` / `group_chain_entries` / `group_pk_rounds` / `group_pk_votes` | `GroupCheckinRepository.purgeOldData` | 365 天 |
| `group_audit_logs` | `ChatRepository.purgeOldAuditLogs` | 365 天 |
| `bot_command_logs` | `BotRepository.purgeOldCommandLogs` | 180 天 |
| `signal_keys`（consumed_pre_key） | `SignalKeyRepository.purgeConsumedPreKeys` | 30 天 |
| `sender_key_distributions` | `SenderKeyDistributionRepository.purgeOldRecords` | 365 天 |
| `read_receipts` / `message_reactions` / `star_messages` | `MessageRepository.purgeOldDerivedRows`（子查询按消息 timestamp） | 365 天 |
| `reports`（已办结） | `ReportRepository.purgeResolvedOlderThan` | 365 天 |

**删群级联补齐**：`deleteChatRows` 原缺 B3 群玩法 5 张表（删群后成孤儿），补 chainId/pkId 先明细后主表的级联删除。

**App 端**：
- `ai_summary_cache`：`AiSummaryCacheDao.deleteOlderThan` 90 天（打开聊天时清理）
- `ai_tasks`：`AiTaskDao.deleteCompletedOlderThan` 已完成 90 天（打开聊天时清理）
- 登出/切号 purge 补调 `GifSearchPreferences.clearForUser` / `ChatFolderPreferences.clearForUser`（旧账号 key 滞留）
- `MediaCache.cleanup()` 增加 `attachment-uploads` / `attachment-sources` 48h 年龄兜底（reconcile 之外防崩溃残留）

**验证**：新增 `AdminOperationalDataPurgeTest` / `DataRetentionPurgeTest`（H2 内存库 + forkEvery 隔离，断言超期删/近期留）全绿；`:server:test` 全量 BUILD SUCCESSFUL；`:app:compileDebugKotlin` 通过。

### 8.27 调优循环 #113：并发安全 + 客户端安全面修复（2026-08-03）

**服务端并发安全（双 agent 扫描 0 高危；修复 1 中危 + 6 低危）**：
- `reports` 举报去重：Postgres 部分唯一索引 `uidx_reports_open_dedup`（(reporter_id,target_type,target_id) WHERE status='OPEN'）+ insert 冲突重读幂等（H2 不支持部分索引，方言守卫跳过）
- `friend_requests`：部分唯一索引 `uidx_friend_requests_pending`（PENDING 双向）+ 冲突回读
- `NearbyRepository.updateLocation`：并发首次定位双 INSERT 撞 PK → 冲突捕获转 UPDATE
- `UserTagRepository.assignTags`：手动+风控并发打标 PK 冲突 → 捕获忽略
- `AnnouncementRepository.publish/cancel`：无条件 UPDATE 覆盖 → CAS（publish 仅 DRAFT/SCHEDULED，cancel 仅 ACTIVE/SCHEDULED）
- `PushTokenRepository`：同 token 并发注册 delete+insert 交叉撞唯一索引 → 冲突重试一次
- 测试基建修复：保留期测试原先 `System.setProperty("DATABASE_URL")` 可能泄漏污染同 JVM 其它测试类（全量跑 3 类偶发失败）→ 改直接参数连接；全量重跑 14m+ BUILD SUCCESSFUL

**App 客户端安全面（双 agent 扫描 1 高 + 6 中 + 4 低；修复 1 高 + 4 中 + 2 低）**：
- **H1** `MainActivity.consumeNotificationIntent` 加调用来源校验（callingPackage 非本应用/系统即丢弃）——防第三方伪造来电、锁屏内容暴露、取消通知；移除 manifest 级 `showWhenLocked`/`turnScreenOn`（改为来电路径动态设置）
- **M1** `FakeChatManager` PIN：明文 SharedPreferences + 默认 "0000" → PBKDF2WithHmacSHA256 600k 迭代 + 随机盐哈希存储，拒绝 "0000"/"1234" 弱密码，连续 5 次失败锁 30s，旧明文自动迁移
- **M3** FileProvider `path="."` 暴露整个 cacheDir → 收窄为各已知子目录（voice_recordings 等）
- **M4** WebRTC .so 下载校验 fail-open（ETag 缺失跳过）→ fail-closed（服务端恒返回 ETag）
- **M5** 推送 HMAC 密钥缺失 fail-open → 敏感类型（INCOMING_CALL/ANNOUNCEMENT）fail-closed
- **L1** 小组件 Provider `onReceive` 校验发送者 UID（getSentFromUid/getSendingUid 反射兼容 SDK 36 移除）
- **L4** 深链 username 只取单路径段 + 字符白名单

**验证**：`:server:test` 全量 BUILD SUCCESSFUL（14m23s）；`:app:compileDebugKotlin` ✅；App 单测运行中。

### 8.28 调优循环 #114：输入验证 + App 功能完整性修复（2026-08-03）

**服务端输入验证/DoS 面（1 高危 + 5 中危 + 1 低修复）**：
- **HIGH** `/api/search` 无每用户限流 + 底层 LIKE 全表扫描 → `globalSearchRateLimiter` 10/min + 查询最小长度 2 字符
- **MEDIUM** 群加人接口 `participantIds` 数组无上限（可打爆 PG 参数上限 65535）→ 路由层按 MAX_GROUP_MEMBERS/MAX_CHANNEL_SUBSCRIBERS 预检
- **MEDIUM** `POST /api/bot/sendMessage` 无每 bot 限流（200 人群 fanout 风暴）→ `botSendRateLimiter` 30/min
- **MEDIUM** reaction 无每用户限流（全群 WS fanout）→ 60/min
- **MEDIUM** nearby-location PUT/GET 无每用户限流 → 更新 10/min、查询 30/min
- **MEDIUM** `/api/bots` 创建与 token 轮换无限流（churn 刷 DB）→ 5/min、10/min
- **LOW** 图片解码无像素总数上限（4096×4096 ≈ 67MB 堆/请求）→ `MAX_IMAGE_PIXELS` 16M
- 顺带：好友数上限保护（接收方 >2000 拒绝新申请，防超大好友集 fanout）

**App 功能完整性（1 高 + 1 中高修复）**：
- **HIGH** DND 按整点判定：设置页是分钟级 TimePicker（22:30 计划），但 `LocalNotificationSuppressPolicy` 把分钟截成 0 → 22:31–22:59 漏抑制且与 AI 提醒路径（分钟级）行为不一致 → 新增 `currentMinute` 参数，FCM/WS 列表/设置页三调用点传日历分钟
- **中高** 密聊 8 开关被服务端推送无条件覆盖（"关了又开"）：设置页声明"仅本机生效"但 `ChatListScreen` 每次挂载用服务端 `secretSurfaceFlags` 覆盖 → 8 个 SecretXxxPrefs 增加 `KEY_USER_SET` 标记 + `applyServerDefault`（仅用户未显式设置时接受服务端默认值）
- **L3** ChatLockRepository PBKDF2 100k→600k 迭代 + 连续 5 次失败锁 30s（与 FakeChatManager 同标准）
- **LOW 遗留**（记录不修）：振动设置不存在（产品取舍）、AUTO_DOWNLOAD flag 只写不读（预留功能）、SecretSessionNoticePrefs 对端徽标未接线

**验证**：双端全量测试后台运行中。

### 8.29 调优循环 #115：Bot 平台可靠性 + 多设备同步收敛（2026-08-03）

**Bot 平台（1 高 + 4 中修复）**：
- **HIGH** webhook 投递零重试零兜底（事件一次性丢失，manifest 宣称 3 次重试与实际不符）→ `BotWebhookService` 指数退避重试（3 次：2s/4s）+ 全部失败回退写收件箱（inbox 轮询兜底），`notifyChatEvent`/`notifyBotDirect` 两路径统一
- **MEDIUM** `pinChatMessage` `actorIsManager = if (chat.isGroup) true else true` 恒 true（死代码）→ 改为 `isOwnerOrAdmin`（与 unpinChatMessage 口径一致）
- **MEDIUM** `copyMessage` 原样拷贝用户内容中的 `<meta>` 键盘块 → 伪造 callback-data 注入信任该数据的 bot → 新增 `stripInlineMeta`（剥离成对 meta 块 + 孤立标签），copyMessage 与 `insertBotMessage` 双路径剥离
- **MEDIUM** 仅 sendMessage 有 per-bot 限流 → 全部 242 个 bot 端点（sendPoll/sendDice/sendLocation/sendSticker/sendVoice/sendDocument/sendPhoto/sendContact/sendVenue/forwardMessage/editMessage/sendChatAction 及全部 get 查询）统一 `botSendRateLimiter` 60/min
- **LOW** 图片解码无像素上限 → `MAX_IMAGE_PIXELS` 16M（4096×4096 ≈ 67MB 堆/请求）

**多设备同步（1 中-高 + 3 中修复）**：
- **中-高** FCM TTL 60s（Doze 设备确定性漏通知）→ 86400s（24h），断线推送等设备唤醒补达
- **中-高** 断线窗口消息不收敛（WS 20 次重连上限后永久无通道，无后台周期同步）→ 新增 `BacklogSyncWorker`（15 分钟周期 + 网络约束）：按同步游标拉增量消息合并落库、推进游标、补发加密预览通知（非活跃会话 + 未静音 + DND 不抑制）；MainActivity 幂等注册
- **MEDIUM** WS auth-death（1008/踢线/设备被删）只停止重连不触发登录失效 → `isAuthDeathReason` 补「已被移除」理由 + `ApiService.notifyTokenExpired`（复用 401 purge+跳登录路径），`onClosing` 时立即触发
- **MEDIUM** 已读状态跨设备不同步（另一设备未读角标长期虚假）→ 服务端 mark-read 广播新事件 `CHAT_MARKED_READ`（新 payload `ChatMarkedReadPayload`），客户端 `WebSocketClient` 解析 + `ChatDao.markAllRead`（unreadCount=0）+ 列表刷新

**验证**：
- `:server:test` 全量 BUILD SUCCESSFUL（7m6s；期间发现并修复公告 publish CAS 回归——create 时 startsAt<=now 的公告直接是 ACTIVE，publish CAS 白名单需含 ACTIVE 保持幂等、仍拒绝 CANCELLED 复活）
- `:app:testDebugUnitTest` 全绿（含 BacklogSyncWorker/跨设备已读/WS auth-death）
- Release APK 构建成功 12.34MB

### 8.30 调优循环 #116：隐私边界 + 数据库性能（2026-08-03）

**隐私边界（双 agent 扫描 2 高 + 2 中修复）**：
- **HIGH** 拉黑单向语义：A 拉黑 B 后 B 仍可无限期读 A 的历史消息/附件/未读 → `blockedSenderIdsForViewerInTx` 与附件下载改为**双向拉黑过滤**（与动态/附近/WS 广播一致）
- **HIGH** 资料接口无拉黑过滤（被拉黑方/匿名者可查 avatar/status/isOnline/lastSeen）→ `getPublicById`/`getAll`/`searchUsers` 加 viewerId 双向拉黑过滤（拉黑返回 404）；公开主页 `findByUsername` 匿名脱敏（status/isOnline/lastSeen 全隐藏）
- **MEDIUM** `lastSeen` 精确时间戳对陌生人全量可见 → 仅「存在 1:1 会话」的 viewer 可见（与好友列表/群成员界面一致）
- **MEDIUM** `getOrCreateDirectChat` 无拉黑检查（被拉黑方可重建会话壳）→ 双向拉黑拒绝创建

**数据库性能（扫描 5 高 + 5 中 + 若干低修复）**：
- **A1** 群消息 fanout 每成员 2-3 次事务（500 人群 = 1000+ 事务/消息）→ `blockedEitherWayIdsInTx` + `mutedUserIdsInTx` 批量一次 SQL（WS + REST 双路径）
- **A2** `getChatsForUser` 逐 chat 查最后消息（100 会话 = 100 RTT）→ 单条 ROW_NUMBER 窗口函数 SQL
- **A3** `getContactIds` 逐 chat 查参与者 → inList + groupBy 一次 SQL
- **A4** 好友申请列表 2N 条用户查询 → 批量 inList
- **B1** `markAllAsRead` 无 limit 全量加载 + 逐行 upsert → 每批 500 行分页 + `batchInsert(ignore=true)` 批量回执 + 阅后即焚批量 UPDATE
- **F1/F2/F3/F5** 14 条时间列独立索引 DDL（refresh_tokens.expires_at、signaling_messages.timestamp、messages.timestamp、message_mutations/group_audit_logs/bot_update_inbox/bot_command_logs/ai_audit_logs/sender_key_distributions/risk_events/group_checkins/group_chains/group_pk_rounds/user_locations 时间列）——消除保留期清理与 deleteExpired 的全表扫描

**验证**：
- `:server:test` 全量 BUILD SUCCESSFUL（9m10s）
- 修复过程：H2 下 `timestamp`/`signal_keys.key_type` 列以小写引号存储（原生 DDL 需加引号，其余列大写裸写）；`batchInsert(ignore=true)` H2 普通模式不支持 → 先查后插；批量方法补事务包裹；A2 ROW_NUMBER SQL 的 timestamp 加引号

### 8.31 调优循环 #117：运维可靠性 + App 主线程性能（2026-08-03）

**服务端运维（1 CRITICAL + 3 HIGH + 3 MEDIUM 修复）**：
- **CRITICAL** 无数据库连接池：Exposed 0.46 `Database.connect(url, driver)` 不再自动建池，每次事务裸连 Postgres（高并发打爆 max_connections）→ 接入 HikariCP（最大连接数 env `DATABASE_POOL_SIZE` 默认 CPU*2+1，connectionTimeout 5s、leakDetection 30s），`Database.connect(dataSource)` + 优雅关闭 `dataSource.close()`
- **HIGH** 无请求超时（慢速客户端无限占连接）→ Netty `requestReadTimeoutSeconds/responseWriteTimeoutSeconds = 60` + `requestQueueLimit = 256`
- **HIGH** `PUSH_HMAC_SECRET` 生产用默认值 = 推送签名可伪造 → 生产校验拒绝默认值启动
- **HIGH** 登录失败/账号锁定零日志（仅内存计数）→ `recordLoginFailure` 锁定阈值时 warn 日志
- **MEDIUM-HIGH** FCM 无超时无重试（瞬时故障=通知永久丢失）→ HttpClient 显式超时（15s/10s/10s）+ 瞬态（429/5xx/网络）重试一次 1s 退避
- **MEDIUM** AI 上游失败只写审计表零日志 → `respondUpstream` 加结构化 error 日志（feature/userId/statusCode/duration）
- **MEDIUM** 限流统计采样器不随 ApplicationStopped 关闭 → 返回执行器 + `environment.monitor` 注册 `shutdownNow()`

**App 主线程/查询性能（双 agent 扫描 3 高 + 1 高修复 + 1 中修复）**：
- **F1** 聊天列表收到消息编辑事件时主线程解密 + 阻塞 Room 读（SignalProtocol 内部同步 DAO）→ 解密包 `withContext(Dispatchers.IO)`（失败语义不变）
- **F5** 导航/账号切换时主线程 `SecretChatSession.clearAllSurfaces`（文件递归删除 + 阻塞查库）→ 包 IO
- **F18** 全局搜索每次打开全量重建索引（数万行卡数秒）→ `refreshIndexIfStale()`：文档数与消息数偏差 <10%（最小 50）时跳过全量重建（依赖增量索引），`countDocuments()` 新 DAO
- **F14** `cacheChats` 逐参与者 N+1 → `UserDao.getUsersByIds` 批量一次 SQL
- 记录不修：F3（FCM 主线程 runBlocking 已切 IO，等待时间毫秒级）、F7（内联 LIKE 搜索依赖 F18 索引表为方向）

**验证**：
- `:server:test` 全量 BUILD SUCCESSFUL（8m39s，含 HikariCP 依赖解析 + 全部运维改动）
- `:app:testDebugUnitTest` 全绿（1m51s，含 F1/F5/F18/F14 性能修复）
- Release APK 构建验证中

### 8.32 调优循环 #118：通知/未读一致性 + API 端点一致性（2026-08-03）

**App 通知/未读一致性（双 agent 扫描 1 中-高 + 3 中修复）**：
- **F9（中-高，隐私）** 阅后即焚消息到期删除后 tray 通知与通知中心条目残留（正文预览泄露）→ `NotificationCenterRepository.deleteItemsForMessages` 批量清理 + `AppNotifier.cancelMessage`，ChatDetailViewModel 1 秒循环与 MaodouchatApp 5 分钟全局扫描双点补齐
- **F1（中）** 跨设备已读（CHAT_MARKED_READ）只清未读角标不清 tray/中心 → 补 `cancelMessage` + `markChatMessagesRead`
- **F7（中）** FCM 迟到推送对已读消息重复提醒 → NEW_MESSAGE 分支加 Room 已存在检查（与 WS existingSameMessage 去重对齐）
- **F2（中）** 停留在聊天页锁屏/切后台期间新消息零通知零未读（activeChatId 进程级不清）→ MainActivity.onPause 清空 activeChatId + ChatDetailScreen ON_RESUME 恢复（Compose LifecycleEventObserver）
- 记录不修：F5（Backlog 不递增未读）、F8（FCM messageId 兜底）；F4（widget mark-read 不调服务端）已于 9.7 修复，F6（AI 任务中心行已读）已于 9.8 修复

**服务端 API 端点一致性（1 高 + 2 中 + 1 中修复）**：
- **非成员错误统一 403**：PollRouting 签到/接龙/群 PK/投票 + createPoll + polls 列表（此前 400 或静默 200 []）→ 前置 `PollRepository.isMember` 检查，非成员 403、其余失败保持 400（createPoll 拆开参数/权限语义）
- **`/api/users/me/public` 去私有字段**：此前返回含 email 的私有形态 → 新增 `getPublicMe`（公开形态 + 自己 lastSeen 可见）
- **功能禁用统一 403**：bot platform disabled 503 → 403（与 nearby/posts/chat_folders 一致）
- **资源不存在 404 化**：moderation rules 更新「不存在/参数无效」合并 400 → 拆开（`ruleExists` 预检 404 + 参数 400）
- **凭据错误 403 保持**（有意设计）：改密/注销原密码错误 403 有注释说明——401 会被客户端 executeWithRefresh 误判为会话过期清库，agent 建议 401 实际会引入 bug，保持 403 + WRONG_PASSWORD code

**验证**：
- `:server:test` 全量 BUILD SUCCESSFUL（9m3s，含 PollRouting 403 一致性 + me/public 脱敏 + 规则 404 化）
- `:app:testDebugUnitTest` 全绿（2m41s，含 F9/F1/F7/F2 通知一致性修复）

### 8.33 调优循环 #119：账户生命周期隐私 + 安全敏感路径补测（2026-08-03）

**App 账户生命周期隐私（3 修复）**：
- **#3 登出泄露附近可见性（中）**：登出/删号仅删本地位置，服务端 `nearby-locations` 行保留 → `SecureSessionManager.purgeLocalSessionLocked` 在 token 清空前 `ApiService.stopNearbyLocationSharing`（失败仅 Log.w 不阻塞登出）；测试钩子 F4 stopNearbyLocationSharing 已在 8.33 之前批次补过
- **#4 水印门控不一致（中）**：聊天列表/详情/媒体/星标 4 处盲水印用 `BLIND && VISIBLE` 双条件（用户关「可见水印」仍残留）→ 统一仅 `VISIBLE_WATERMARK` 门控，StarredMessagesScreen 补上 VISIBLE；MediaCenterScreen 频域盲水印（528/611）保留 BLIND 语义
- **#2 删号本地清理失败卡死（中）**：`purgeLocalSession` 失败（数据库文件占用等）时 `isDeletingAccount` 永不清除 → 失败分支也置 false + 新字符串 `settings_account_deleted_local_purge_failed`

**安全敏感路径补测 P0（此前零覆盖，新测试类 ×2）**：
- `LoginLockoutPrivacyRouteTest`（4 测试）：5 次错误密码 → 429 + `ACCOUNT_LOCKED`；锁定期间正确密码也被拒且不泄露密码正误；成功登录清零失败计数；拉黑后——被拉黑方查资料 404（getPublicById 双向过滤）、重建 1:1 私聊 403（拉黑预检）、向已有私聊发消息 403；解除拉黑恢复资料可见
- `TotpFlowRouteTest`（4 逻辑 + 1 流程）：TotpService 纯逻辑（secret 生成/verify/±1 窗口容差/非法 secret fail-closed/provisioning URI）；完整 API 流程 setup→confirm（错误码 400 TOTP_INVALID）→登录 requiresTotp=true（纯密码与错误码均不泄露）→正确码登录成功→disable（错误码拒绝）→恢复纯密码登录
- 测试基建经验：`loginLockouts`/限流器为 `configureRouting` 局部状态（每 testApplication 重建，无跨测试污染）；`AUTH_RATE_LIMIT_PER_MINUTE=1000` 放宽连续登录测试；TOTP 客户端生成器与 `TotpService` 同算法（RFC 6238/SHA-1/30s/6 位）

**验证**：
- `:server:test --tests LoginLockoutPrivacyRouteTest`（4 测试全过）+ `--tests TotpServiceLogicTest/TotpFlowRouteTest`（5 测试全过）BUILD SUCCESSFUL
- 8.32 全量回归 + 双端全量测试验证中

### 8.34 调优循环 #120：Bot 封禁绕过 + 成员变更广播 + 账户生命周期（2026-08-03）

**双 agent 并行扫描（App 媒体/重连 + 服务端群管理/Bot），本轮修复 13 项**：

**服务端安全（1 HIGH + 2 MEDIUM-HIGH）**：
- **HIGH-1 bot 封禁绕过**：bot 端点只校验 token 有效，被封禁用户的自有 bot 可继续收发消息（绕过 suspend 语义）→ `BotRepository.authenticate` 认证源头校验 owner 的 suspendedUntil/deletedAt，全部 bot 端点一次收口
- **MEDIUM-5 封禁用户残留交互面**：附近更新/查询、bot-callback、群签到/接龙/PK/投票（PollRouting 6 个 POST）、头像上传、资料修改此前无 `rejectIfSuspended` → 全部补齐；`getNearby` 结果同时过滤被封禁账号（位置=实时行踪，封禁期间必须消失）
- **MEDIUM-6 bot 绕过频道单向约束**：bot sendMessage 只查 isParticipant → 对 CHANNEL 类型补 `isChannelOwner` 校验（与用户发消息一致）

**服务端成员变更广播一致性（3 MEDIUM）**：
- **bot 退群**：补 memberRevision 广播（与用户退群一致，退群前抓取成员）；群主 bot 退群从「status ok」改为 409 `GROUP_OWNER_TRANSFER_REQUIRED`
- **删除 bot**：`BotRepository.delete` bump memberRevision 但无广播 → 新增 `groupChatIdsFor` + 路由侧向涉及群广播 `BOT_REMOVED`
- **删号**：`deactivateAccount` 自动群主转让/成员移除 bump memberRevision 但无广播 → 新增 `ChatRepository.groupMembershipSnapshotForDeletion` 删号前快照，路由侧向剩余成员广播 `MEMBER_REMOVED`

**服务端输入/一致性（1 MEDIUM + 3 LOW）**：
- **bot 禁言无上限**：restrictChatMember 的 until 直接透传 → 与用户端一致 clamp 到 `MAX_MUTE_DURATION_MS`（30 天）
- **bot fanout 拉黑过滤缺口**：sendContact/sendVenue/sendCode/setMessageReaction 4 端点直推全部参与者 → 与 sendMessage 一致补 `hasBlocked(pid, bot.id)` 过滤
- **webhook 回声循环**：`notifyChatEvent` targets 含事件发送者自身（bot 发消息→收自己事件→自动回复→无限循环）→ 排除 senderId
- **`/api/users/search` q 无上限**：截断 100 字符（底层 LIKE 全表扫描）

**App 修复（2 MEDIUM + 3 LOW）**：
- **WS 登出↔重连竞态（MEDIUM）**：重连 job 在 delay 后可能与 disconnect() 交错，用旧 token 复活「当前会话」（旧账号持续在线、事件污染新账号）→ 三重守卫：connect() 拒绝空白 token、`isReconnect && !shouldReconnect` 拒绝、重连 job 捕获失败时会话代号（`WebSocketSessionGate.current()` 新 API）并在 connect() 校验仍为当前会话（杜绝旧 job 在重新登录后拆掉新连接）
- **VoicePlayer toggle 崩溃（MEDIUM）**：pause/start 无防护，MediaPlayer Error 态抛 IllegalStateException 直达主线程 → 与 play() 一致 runCatching + 失败走 stopInternal
- **AudioFocus 丢失空实现（LOW）**：来电/其他 App 抢占时语音继续外放 → LOSS 暂停 + GAIN 自动续播（resumeAfterFocusLoss + currentSource 跟踪）
- **附件上传会话替换即失败（LOW）**：`require(status.id == attachmentId)` 抛 IllegalArgumentException 被归为不可重试、传输永久失败 → 改为重新锚定新会话继续上传（与 verify 路径 410 语义一致）
- **附件下载取消残留 .part（LOW）**：取消时主动删除残片，不再依赖 48h 兜底
- **图片缓存键缺尺寸（LOW）**：列表 1024px 与查看器 2048px 共用键 → 反复全量解码且查看器位图滞留列表气泡 → 键追加 `#size:WxH`

**验证**：
- `:server:test` 全量 BUILD SUCCESSFUL（6m54s，含全部服务端修复 + P0 补测）
- `:app:compileDebugKotlin` BUILD SUCCESSFUL（WS 竞态/VoicePlayer/ApiService/缓存键）
- `:app:testDebugUnitTest` 回归验证中

### 8.35 调优循环 #121：功能接线大会战 + 跨端一致性（2026-08-04）

**高置信 Bug 修复（3）**：
1. **密聊新设备风控指纹每天变化**：`deviceRiskId` 用 `System.currentTimeMillis()/86400`（=当前日期），已登记设备次日被误判为新设备 → 改用 `PushRegistrationManager.currentDeviceId`（安装级 UUID，跨重启稳定；应用关闭系统备份，重装后即新设备，语义吻合）
2. **WS 丢 sealedSender**：`IncomingMessage` 缺该字段（服务端 NEW_MESSAGE 下发、REST DTO 均有），WS 实时消息密封标记恒 false → 补字段并传入 Message
3. **bot sendDice 恒等条件**：`if (it == "dart") 6 else 6` → emoji 参与面数映射（🏀⚽ 5、🎰 64、其余 6），消息内容携带实际 emoji

**接线/功能闭环（9）**：
1. **引用点击跳转修复**：onReplyPreviewClick 原跳当前消息自身 → 改用 `meta.replyToId`；目标未加载时自动翻页加载，翻尽仍无则提示并放弃（置顶跳转同享自动翻页）
2. **静默发送 UI 接线**：ChatInputBar 补 `silentSend`/`onToggleSilentSend` 两参数（链路早已存在，仅 UI 未传）
3. **AUTO_DOWNLOAD 补读**：开关此前只写不读 → 实时到达媒体消息在 flag 开启 + 非计量网络（Wi-Fi/非计费）下自动下载；密聊不做
4. **密聊 TTL 进入会话前即时校验**：此前仅 15 分钟周期清扫 → 进入密聊时先查 lastActivityAt，过期立即销毁本地解密缓存再以本次进入为新起点
5. **表情最近使用**：新 `EmojiRecentPreferences`（36 条/账号隔离），表情面板顶部「最近使用」行，点击记录去重；登出清理
6. **快捷短语（常用语）**：新 `QuickPhrasePolicy`/`QuickPhrasePreferences`（默认 8 条 + 自定义增删，40 条上限/80 字），附件菜单入口 + 弹窗，点按插入草稿；登出清理
7. **自定义图片聊天壁纸**：新 `persistCustomWallpaper`（content URI 复制进 filesDir/wallpapers 防授权失效），聊天页 Box 层绘制（无壁纸时不引入额外层）；设置页「自定义图片」芯片 + 移除；自定义优先于色板、仅显式移除恢复
8. **联系人资料卡**：底部弹层（大头像/ID/状态/最后在线 + 发消息/语音/视频/屏蔽/举报），入口在「联系人操作」弹窗
9. **聊天气泡颜色**：新 `ChatBubbleColorPalette`（6 档 + 明暗自适配 + 渐变）与 `LocalChatBubbleColor` CompositionLocal，NavGraph 双调用点注入，MessageBubble 10 处发送气泡/链接预览/渐变消费；全局主题色因 Primary 静态色上千处使用不可行，改为气泡级个性化（按账号本机）
10. **未读文件夹「全部已读」**：顶栏 DoneAll 按钮，逐会话 mark-read + 乐观清零落库 + tray 清理（服务端广播 CHAT_MARKED_READ 多设备同步）
11. **OnDemandStickerStore 接线**：服务端新增 `GET /api/stickers/manifest.json`（STORAGE_DIR/stickers-manifest.json，无文件返回空清单）+ `GET /static/stickers/{packId}/{name}`（字符白名单 + canonical 路径防穿越）；客户端贴纸包管理对话框新增「远程贴纸包」区（刷新清单/下载/状态）
12. **转发附带留言**：新 `sendTextToChat`（复用 encryptForwardedContent 双路径加密），转发弹窗附带留言输入框（500 字），转发后向目标会话发送一条加密文本

**死代码清理（2）**：
1. `PinnedMessageRepository.removeByMessageId` 冗余（deleteMessage/deleteMessageForModeration/revoke/批量过期/删会话/删号全部已内联清理置顶）→ 删除
2. `AiContextManager` 未接线缓存子系统（buildContext/cacheContext/getCachedContext/invalidateCache/clearCache + ContextMessage）→ 删除；明确不接线的理由：缓存跨请求复用造成陈旧上下文 + 服务端内存留存明文违背 E2EE 最小化原则（KDoc 已注明）

**文案（1）**：黑名单说明补齐双向语义（对方无法发消息/看资料/通话、好友关系解除），中英对称。

### 8.36 调优循环 #122：第三轮深度 bug hunt（11 修复 + 3 编译错误）（2026-08-04）

**编译错误（3，静态核查发现）**：
1. `Icons.Outlined.Quickreply` 不存在（项目仅 material-icons-core 48 图标）→ 改用已导入的 `SentimentSatisfied`
2. `Icons.Outlined.Flag` 不存在 → 改用已导入的 `Warning`
3. `common_delete` 字符串缺失 → 中英成对补充

**运行时修复（11）**：
1. **[CRITICAL] WS 每 15 分钟强制登出**：服务端 authWatchdog/帧复检对 access token 到期一律以 1008 关闭，客户端把所有 1008 当永久鉴权死亡 → purge 加密库 + 跳登录（15 分钟 token 有效期 = 每 15 分钟登出一次）。三层修复：服务端过期改用可恢复码 **1013 TRY_AGAIN_LATER**（吊销/封禁/踢线仍 1008）；客户端 `onClosing` 对 1008 + 「过期」reason 走重连而非 purge（兼容旧服务端）；重连前若 token 临近过期主动 `refreshAccessTokenForCurrentSession()`（新公开方法，复用 REST 同一套 refresh 机制）
2. **[HIGH] NUDGE/TYPING 拉黑单向过滤**：拍一拍 fanout/push 与 typing 用 `hasBlocked`（单向）→ 改 `blockedEitherWayIdsInTx` 批量双向（与 SEND_MESSAGE 一致）；群 NUDGE 整体预检删除（与发消息一致，由 fanout 按人过滤）
3. **[HIGH] cancelForChat/deleteAll 删 SENDING 服务端对象竞态**：finalize（verify→sendMessage）在途时删服务端附件 → 消息引用已删附件收件人无法下载 → SENDING 行不再删服务端对象（24h TTL 兜底），仅取消调度 + 清本地文件 + 删行
4. **[MEDIUM-HIGH] BacklogSyncWorker 门控用消息时间戳**：活跃会话游标时间戳始终贴近 now → WS 断线期间周期同步被无限跳过 → 新增独立「上次同步尝试墙钟」`getLastBacklogSyncAtMs/markBacklogSyncAttempted`（成功/失败均记录）
5. **[MEDIUM] TURN 凭据 1h 过期无刷新**：`configuredIceServers` 改 @Volatile var + `refreshIceServers()`；CallViewModel 通话中每 30 分钟重新 fetch ICE 并热替换（新 PeerConnection/重建用新凭据），endCall 清理 job
6. **[MEDIUM] FCM HMAC ±5min 与 24h TTL 自相矛盾**：Doze 延迟推送全被拒 → 过期窗口放宽到 24h（与服务端 TTL 对齐；重放防护由消息 ID 去重/来电 callId 幂等兜底）
7. **[MEDIUM] 上传 offset==total 被 require 误判不可重试失败**：服务端 DB 进度滞后于文件写入时 → 重新拉取状态自愈（服务端 reconcile 补齐 UPLOADED），不再直接失败
8. **[MEDIUM] offer 双通道重复派发**：FCM 唤醒轮询对同 callId offer 无 pending 比对 → 重复响铃/系统双来电/30s 计时重置 → poll 路径与 WS 路径均按 callId（空 callId 按联系人）对 coordinator pending 去重，幂等跳过
9. **[LOW-MEDIUM] 空 callId 未接来电双写**：NavGraph 计时器与 CallViewModel RINGING 超时用各自 nowMs 生成不同 id → `missedRecordId` 空 callId 回退改为固定 `mc_<peer>`（双路径幂等）
10. **[LOW] 通知中心 referencesMessage 后缀误匹配**：`endsWith("_$messageId")` 在 id 互为后缀时误删 → 精确解析 `msg_{chatId}_{messageId}` 第三段
11. **[LOW] WS SIGNALING 无频控**：ice-candidate/answer 等非 invite 信令可高频刷量 → 新增 `wsSignalingRateLimiter` 120/min（初次 offer 仍走 CallInviteRateLimiter）

**验证**：全部为静态核验（未执行编译/测试——按用户要求功能期只写代码）。新增 strings 均中英成对；所有新文件按既有模式（账号隔离 + clearForUser 钩入 SecureSessionManager 登出清理）。

### 8.37 调优循环 #123：第四轮静态回归 + 收尾（2026-08-04）

- 静态回归核查 13 项：12 项通过；唯一实质问题为 `MissedCallTimeoutPolicyTest.blankCallIdFallsBackToSynthetic` 期望值过期（`mc_1000_u9` → `mc_u9`，与 8.36 #9 固定前缀实现矛盾）→ 测试已更新
- 忙等防护：uploadEncryptedAttachment 的 offset==total 自愈轮询加 3 次上限 + 500ms 间隔，服务端异常时不再忙等
- 新功能：联系人资料卡头像大图预览（点击头像 → 全屏缩放查看，复用 ZoomableAsyncImage，点按关闭）

### 8.38 调优循环 #124：第五轮深扫（Admin/Bot/用户/FCM/加密/设置 9 修复）（2026-08-04）

1. **[HIGH] SettingsViewModel.saveUsername 丢弃服务端 Result**：`setUsername(...)` 的 Result 被当语句丢弃、无条件 success——重复/非法/网络失败被吞掉还提示「已更新」→ 返回真实 Result（clearUsername 分支同步修正）
2. **[HIGH] BotRepository.authenticate 未查消息/动态限制**：封禁检查只覆盖 suspendedUntil/deletedAt，被禁发消息/禁发动态的账号可借自有 bot 绕过 → 认证源头补 `messageRestrictedUntil`/`postRestrictedUntil` 校验
3. **[MED-HIGH] AdminRouting 删用户磁盘清理失败 500 不可重试**：DB 注销已提交后任一文件删除抛异常 → 500，重试命中 404 导致孤儿密文/图片 → 全部清理步骤逐项 bestEffort（runCatching + 日志，disconnect 单独 runCatching），存储层 TTL/孤儿清理兜底
4. **[MED-HIGH] SenderKeyRetryManager 瞬态网络失败升级破坏性操作**：任何异常都 invalidateGroupSenderKey + 清空 READY 附件 wire（一次网络抖动让其他设备已按旧 SKDM 加密的密文无法解密）→ 抽 `isTransientNetworkError`（与 markFailure 退避共用），网络类仅入队退避重试
5. **[MED] FCM 服务端 DND 用注册时区**：跨时区旅行后按旧时区静默 → 通知永久丢失（客户端本地 DND 才是权威）→ PushTokenRecord 补 `updatedAt`，注册超过 14 天的 token 跳过服务端 DND（fail-open），fresh token 仍走服务端 DND 省配额
6. **[MED-LOW] SignalProtocol.ensurePreKeysAvailable 随机 PreKey id 冲突**：随机 startId 撞上已上传未消费的 PreKey 会被覆盖 → 对端按旧 id 发来的 PreKeySignalMessage 永久无法解密 → 与 replenishPreKeysIfNeeded 一致取 store 最大 id + 1 续号
7. **[MED-LOW] AdminRouting 批量禁发消息时间无校验**：过去时间戳被静默写成已过期限制、Long.MAX_VALUE 绕过上限 → 与单用户端点一致的时间合法性校验（400）
8. **[MED-LOW] BotRepository.delete 群主 bot 不留所有权**：直接移除成员资格导致群无主 → 与 UserRepository.removeOwnedBots 一致：删除前按 ADMIN>MEMBER>joinedAt 选后继 + OWNERSHIP_TRANSFERRED 审计
9. **[LOW] UserRepository.replaceAvatar require 抛 500**：非法头像地址 IllegalArgumentException → 500 → 改返回 null（路由按不存在处理）
10. BotRepository.create 用户名撞车静默失败（#9 记录不修）：TOCTOU 已被 owner 行锁串行化覆盖，仅错误分类不清晰

**验证**：全部为静态核验（未执行编译/测试）。

### 8.39 调优循环 #125：第六轮双路深扫（App 社交面 + 服务端社交面，20 修复）（2026-08-04）

**服务端（11 项）**：
1. **[HIGH] globalSearch 不过滤双向拉黑**：被拉黑方仍可经全局搜索读对方明文/元数据 → 复用 `blockedSenderIdsForViewerInTx`（与 getMessages/WS fanout 不变量一致）
2. **[MED-HIGH] 动态编辑绕过内容审核**：发动态/评论过 moderationRuleRepo，编辑不跑 → 编辑路径同样 evaluate，命中即 422/429
3. **[MED-HIGH] 星标列表只过滤单向拉黑**：漏掉「拉黑我的」方向 → 双向集合 + toggleStar 语义对齐
4. **[MED] nearby 无 SQL LIMIT**：边界盒全量物化上万行内存排序 → SQL 层粗上限 limit*8 + 精确 take
5. **[MED] 点赞/取消点赞无限流**：可对作者反复 like/unlike 刷 FCM → `postLikeRateLimiter` 30/min（like/unlike 双端）
6. **[MED] 审核正则 CallerRunsPolicy 绕过超时**：池满时请求线程内联执行正则（future.get 失效 + 占 DB 连接）→ 改 AbortPolicy + RejectedExecutionException 显式记日志
7. **[MED] GET /api/users q 未截断**：四列 LIKE 全表扫描成本放大 → take(100)（与 /api/users/search 一致）
8. **[LOW-MED] 用户名/隐私写路径缺封禁检查**：改用户名 + 清除用户名 + 隐私开关补 `rejectIfSuspended`；用户名设置加限流（防占用枚举）
9. **[LOW-MED] 星标列表无上限**：O(n) join 无限增长 → 取最近 1000 条（客户端搜索覆盖全量语义）
10. **[LOW] 举报 POST/COMMENT 不校验可见性**：可对被拉黑用户刷举报 → 复用 `PostRepository.canView`，不可见返回无权
11. **[LOW] nearby 冲突捕获过宽**：死锁/连接中断也转 UPDATE 假装成功 → 仅唯一冲突（23505/unique）转 UPDATE，其余 rethrow
12. **[LOW] 评论分页游标按 batch.last() 推进**：前几批全被拉黑作者时可见评论被跳过 → 游标按可见评论 last 推进 + 迭代上限 5→20

**App（9 项）**：
1. **[HIGH] 改密/删号/设备操作 busy 标志残留**：会话门禁失败提前返回不复位 → 弹窗永久转圈无法关闭（changePassword/deleteAccount/removeMyDevice/renameMyDevice 4 处 + changePassword 成功路径二次门禁 2 处，全部复位）
2. **[MED-HIGH] FCM NEW_MESSAGE 占位 id 逻辑颠倒**：`push_…` 占位被当「已存在」→ 通知静默丢弃 → 占位 id 直接照常展示，仅真实 id 做 Room 去重
3. **[MED] 好友申请失败空列表覆盖**：网络失败 getOrNull().orEmpty() 清空现有申请 → 失败保留旧列表，仅双双成功才覆盖
4. **[MED] 附近的人重叠刷新互相覆盖**：慢响应覆盖快响应/旧失败态 → refreshGeneration 代际计数（5 条路径全覆）
5. **[MED] AiTasks observeTasks 重复订阅**：解锁/加锁切换每次新注册 collector 永不清除 → 先取消旧 job 再订阅
6. **[MED] 接受好友丢 lastSeen + UserEntity 不持久化**：accept 补传 lastSeen；UserEntity 加 lastSeen 列（Room 迁移 29→30）+ toDomain/toEntity + UserRepository/ChatRepository 合并保留（仅真实值更新）
7. **[LOW] Feed 分页阈值 30 vs 服务端 40**：统一 FEED_PAGE_SIZE=40，消除多余空请求
8. **[LOW] 扫码用户全量 getUsers()**：非好友/网络失败误判「查不到用户」→ 改 `getUser(id)` 定向查询 + scannedUserError 区分错误
9. **[LOW] AiTasks unlockWithPin 主线程 runBlocking**：记录（ChatLockGate 同步接口限制，单条索引查询延迟毫秒级，ANR 风险低，未重构）

**验证**：全部为静态核验（未执行编译/测试）；15 项改动经子代理逐一核查（Exposed 0.46 limit/notInList 签名、Ktor 422、RejectedExecutionException 分支类型、迁移注册、lastSeen 合并）全部通过，无编译级错误。

### 8.40 调优循环 #126：第七轮双路深扫（App 群/媒体/通话面 + 服务端剩余面，16 修复）（2026-08-04）

**服务端（9 项）**：
1. **[HIGH] 删设备误吊销未绑定会话**：`signalDeviceId IS NULL` 分支把该用户所有未绑定设备（新登录未传密钥包/历史遗留）的会话一起吊销、连带登出 → 只吊销明确绑定该设备的会话
2. **[HIGH] DeveloperRouting 分析无上限全表物化**：uniqueUsers 用 map+distinct+size 全量物化（可 OOM）→ 改 SQL 侧 `select(col).withDistinct().count()`
3. **[MED] GroupPlay 读路径 FOR UPDATE**：listChatPolls/getPoll 只读请求拿写锁，与签到/接龙/PK 写事务互相阻塞 → 读路径去 forUpdate，写路径保留
4. **[MED] sealed-sender 空 jwtSecret 回退硬编码密钥**：任何人可用公开字符串为任意用户签发合法证书伪造认证 → 空密钥 fail-closed（sign 返回 null，issue/verify 已判空）
5. **[MED] notifyBotDirect webhook 失败重复入 inbox**：调用方已入队、失败又补投 → bot 拉取收到同一事件两次 → 不再补投，只记日志
6. **[MED] Signaling 每次 store/consume 全表 purge**：写锁持有被拉长 → purge 节流为每 60s 一次（AtomicLong CAS 单飞）
7. **[MED] SenderKeyDistribution upsert 刷新 createdAt**：保留期清理因无限续期失效 → `onUpdate = listOf(createdAt to createdAt)`（冲突时 createdAt 引用原行保持不变，其余列用 insert 值更新；已用字节码验证 Exposed 0.46 onUpdate 语义）
8. **[LOW] checkins/me 缺成员校验**：非成员落到 404 与群不存在合并 → 与其余端点一致路由层 isMember 403
9. **[LOW] refresh token 重复提交吊销会话**：记录为刻意反重放设计（客户端已 refreshMutex 单飞，改弱会降低安全性），未改

**App（7 项）**：
1. **[HIGH] 群资料草稿与数据永不同步**：LaunchedEffect(chatId) 在 load 完成前用空值初始化且永不重跑 → 群名/公告/昵称输入框恒空，保存误清空已设内容 → 新增 lastSynced* 状态 + 数据加载后按「草稿==上次同步值或空白」条件同步（不覆盖用户编辑）
2. **[MED-HIGH] 已接听来电被 30s 振铃超时误杀**：WebRTC 原生库首次联网下载可超 30s → answerCall 开头即取消 ringingTimeoutJob
3. **[MED] 星标页已解锁会话仍强制 PIN**：load() 不回填 isChatUnlocked → 补 `ChatLockSession.isUnlocked(chatId)` 回填
4. **[MED] 信令去重 key 用 hashCode()**：群 mesh 候选量大碰撞概率达百分之几，不同候选碰撞被静默丢弃导致 ICE 卡死 → key 改用完整 payload
5. **[MED] 直连 SDP 失败无反馈**：onOperationError 从未接线，用户干等 30s → configureReliabilityCallbacks 接线（结束通话 + 可读错误，新增 call_operation_failed 字符串）
6. **[MED] 群通话缺权限检查**：群 mesh 权限缺失静默失败 → ensureLocalMedia 前与直连一致的 RECORD_AUDIO/CAMERA 预检
7. **[LOW] 群审计 limit 默认 50 vs UI 阈值 80**：展开更多永不出现、历史静默截断 → 显式传 limit=100；公告上限 1200 vs 计数 1000 不一致 → 统一 take(1000)

**验证**：全部为静态核验（未执行编译/测试）；12 项改动经子代理逐一核查，其中唯一编译错误（Exposed 0.46 `upsert` 无 `onUpdateExclude` 参数，改为 `onUpdate` 列对并验证字节码语义）已修复。

### 8.41 调优循环 #127：第八轮双路深扫（App 基础设施 + 服务端认证面，19 修复）（2026-08-04）

**服务端（9 项）**：
1. **[HIGH] 登录锁定计数永不过期**：锁定期满后计数不清、任何一次失败立即重新锁定 15 分钟 → 攻击者周期性错 1 次即可无限期锁死账号 → 锁定期满即清计数
2. **[HIGH] TOTP confirm/disable 无限流**：6 位码 ±1 窗口可爆破禁用 2FA → `totpManageRateLimiter` 5/min（双端点）
3. **[MED] 消息 edit/revoke/delete 无限流**：每次 mutation 向全成员 WS 广播成 fanout 放大 → `messageMutateRateLimiter` 60/min（三端点）
4. **[MED] 密码无最大长度**：BCrypt 静默截断 72 字节、前 72 字节相同即等价 → 新增 `isValidPassword`（≥6 字符且 UTF-8 ≤72 字节），4 个写路径统一接入
5. **[LOW-MED] reset-password 先限流再校验**：空邮箱先占配额再被 429 拒 → 先校验再按账号限流
6. **[LOW] 用户名格式非法映射 409**：→ 路由层先校验格式（400），仅唯一冲突 409
7. **[LOW] 好友 accept/reject/cancel 缺封禁检查**：被封禁仍可触发 WS/FCM fanout → 三端点补 `rejectIfSuspended`
8. **[LOW] acceptRequest 好友上限复查**：sendRequest 只查发送时刻、积压期间可能超限 → accept 时复查 `MAX_FRIENDS_PER_USER`
9. **[LOW] 未处理项记录**：moderator report 400/404 语义、InactiveAuthSessionException 500、refresh 封禁状态 oracle——记录待后续

**App（10 项）**：
1. **[HIGH] 密聊明文媒体残留磁盘**：SECRET_CACHE_DIR 不在 cacheDirectories、clearAllSurfaces 只删当前活跃 surface（进程被杀后集合为空）→ 新增 `MediaCache.deleteAllSecretChatMedia`（整目录擦除），登出 purge 调用
2. **[HIGH] 小组件配置被 APPWIDGET_UPDATE 抹掉**：launcher 重建/桌面刷新即清空钉住配置 → `saveConfig` 置 configured 标记，onUpdate 只清理未配置实例
3. **[MED] getSyncCursor 旧全局游标播种**：新会话从旧全局时刻起步跳过历史 → 无 per-chat 键时从 0 全量拉取
4. **[MED-LOW] lockoutRemainingMs 返回绝对时间戳**：`(lockedUntil ?: 0L - now)` 优先级错误 → 改为 `(until - now).coerceAtLeast(0)`
5. **[MED-LOW] SIM 拔出不触发密聊清除**：currentSimId 为 null 直接 return false → 有基线时视为「SIM 已移除」并清除
6. **[MED-LOW] 小组件打开 intent 缺 NEW_TASK**：无前台 Activity 时点击无反应 → 补 FLAG + 失败记日志
7. **[LOW] installationId 首装 apply**：生成后被杀换新 deviceId 留旧 FCM 绑定 → 首次 commit()
8. **[LOW] BacklogSyncWorker 失败也记录尝试**：retry 退避在节流窗内空转 → 仅成功时记录
9. **[LOW] 悬浮球重启/切号不恢复**：设置显示「开」但永不出现 → 冷启动 + 代际变化按账号设置 start
10. **[LOW] 未处理项记录**：路由变化 clearAllSurfaces 语义过宽、FCM 主线程 runBlocking、冷启动协程账号切换竞态——记录待后续

**验证**：全部为静态核验（未执行编译/测试）；12 项改动经子代理逐一核查全部通过，无编译级错误。

### 8.42 调优循环 #128：第九轮（E2EE 核心修复 7 项 + 新功能「消息稍后提醒」）（2026-08-04）

**E2EE 发送/重试路径修复（7 项）**：
1. **[MED-HIGH] SK 覆盖瞬态失败标 FAILED**：断网/超时发生在 SK 分发阶段 → 群消息直接 FAILED、flusher 永不重试 → 新增 `TransientCoverageException`（IOException 子类），ensureCoverageNow 网络瞬态分支改抛它，`shouldMarkOutboxFailed` 识别并保持 SENDING
2. **[MED] SenderKey 重试任务并发处理**：Worker、应用内 60s 循环、发送路径可同时 redistribute 同一任务（双重 SKDM + 双重密钥重置）→ `processMutex` 互斥串行 processDueTasks
3. **[MED] enqueue 冲掉指数退避**：同 epoch 重入每次把 nextAttemptAt 拉回 30s → SKDM 风暴 → 仅新 epoch 重置，同 epoch 保留更晚退避时间
4. **[MED] AI envelope Failed 被 ACK 永久丢失**：瞬时解密失败（ratchet 在途）→ 跨设备数据永久丢失 → Failed 不再 ACK，与 NoSession 一致重试（envelope 有 30 天保留期）
5. **[LOW] 解密失败占位写搜索索引**：占位文本进 FTS 成为长期噪声 → 占位不索引（DB 保留以保持 UI 一致）
6. **[记录] WS 投递+flusher 重发 messageCount 双计 → 提前轮换**：WS 投递与 SENT ack 之间存在窄窗口，双计只偶尔发生；移除 WS→REST 回退安全网的风险大于收益，记录待后续
7. **[记录] 同 epoch 重试重新 mint SenderKey / FutureEpoch 死锁 / 缓存 epoch 无 TTL**：涉及 crypto 核心发送路径，改动风险大，记录待专项

**新功能：消息「稍后提醒」（Remind Me Later）**：
- 长按消息 → 「稍后提醒」→ 快捷档位（1m/5m/15m/30m/1h/2h/3h/4h/6h/12h/24h/2d/3d/7d，复用定时发送档位文案）→ WorkManager 唯一作业到点发通知 → 点击直达聊天并高亮原消息
- 新文件：`MessageReminderStore`（SharedPreferences，账号隔离，无 DB 迁移风险）/ `MessageReminderScheduler`（唯一 work + tag + 指数退避）/ `MessageReminderWorker`（账号校验 + 时间拨回重排 + 通知）/ `MessageReminderPolicy`（窗口 1m~30d）
- 接入：AppNotifier.showMessageReminder + EXTRA_OPEN_MESSAGE_ID；MainActivity 读取 messageId → Chat target 加 messageId → `Routes.chatDetail(id, messageId)`（跳转高亮链路复用现有 navigationTargetMessageId）；ChatDetailViewModel.scheduleMessageReminder；登出清理（cancelAll + clearForUser）
- 中英字符串成对：message_reminder_menu / message_reminder_scheduled / message_reminder_notification_title

**验证**：全部为静态核验（未执行编译/测试）；10 项改动经子代理逐一核查，唯一编译问题（SenderKeyRetryManager 缺 `import kotlinx.coroutines.sync.withLock`）已修复。

### 8.43 调优循环 #129：第十轮（清理已记录待办 + 新功能群头像大图）（2026-08-04）

**清理已记录待办（4 项）**：
1. **[MED-HIGH] 密聊 surface 路由变化清磁盘过宽**：MainActivity 每次路由变化调用 clearAllSurfaces 会销毁返回栈中其它密聊会话的媒体缓存 + 搜索索引 → 新增 `SecretChatSession.clearSurfaceMarkers()`（只清标记，FLAG_SECURE 释放依据），路由变化改用；磁盘清除由离开单 surface / 登出 / SIM 变更 / 密聊禁用承担
2. **[LOW] 审核举报 status 400 合并**：`/api/moderator/reports/{id}/status` 缺失/冲突/参数错误一律 400 → 404（举报不存在）/ 409（已处置不可变）/ 400 分离
3. **[LOW] InactiveAuthSessionException 500**：登录/注册签发 refresh 时会话并发失效应回 400（此前 IllegalStateException 落全局 Throwable 500）→ 改 extends IllegalArgumentException
4. **[LOW] refresh 封禁状态 oracle**：UserSuspended 403 与 InvalidToken 401 可区分 → 持有他人 refresh token 可探测封禁状态 → 统一 401 通用文案（封禁账号客户端走 401 正常登出）
5. **[MED-LOW] 冷启动加密初始化账号切换竞态**：冷启动期间极速登出/换号 → 旧账号密钥状态写入新库 → initialize/replenish/rotate 三个挂起点前 `stillCurrent()` 会话复核

**新功能：群头像大图查看**：群资料页头像（普通成员非上传态）点击 → 全屏缩放预览（复用 ZoomableAsyncImage，点按关闭），与联系人资料卡头像预览同模式

**验证**：全部为静态核验（未执行编译/测试）；6 项改动经子代理逐一核查，唯一编译问题（GroupHeader 内引用越界的 showAvatarFull，加 onShowAvatarFull 参数）已修复。

### 8.44 调优循环 #130：第十一轮（新功能图片发送预览 + 通知 tag 隔离 + 跨面一致性）（2026-08-04）

**跨面一致性扫描（WorkManager 名 / PendingIntent requestCode / 字符串 / Room 迁移链 全部无冲突；通知 tag 3 项真实问题）**：
1. **[MED] 来电/未接/动态互动共用 null-tag id 空间**：三功能 id 均为不同公式的 hashCode，跨功能哈希碰撞时响应来电会被动态互动通知顶掉 → 新增 `NOTIFY_TAG_CALL/MISSED/POST` 独立 tag（show + cancel 全对齐）
2. **[MED] CallForegroundService 前台通知 id=9001 在 null-tag 空间**：任何 hashCode==9001 的通知会顶掉前台通话通知 → API 29+ 用 `startForeground(tag,id,type)` 独立 tag；pre-Q 用远离哈希低位区的高位 id `0x444F5543`
3. **[LOW] cancelAiTaskReminder 不清理 group summary**：通知中心取消单条 AI 任务后该 chat 的 summary 残留托盘 → 通知中心 AI_TASK 分支优先按 chat 整组清理（含 summary）

**新功能：图片发送前预览确认**：选图 → 预览对话框（图 + 发送/取消）→ 确认才发送；单次查看/剧透标记在选取时刻捕获并在确认时应用（`PendingImageSend`）。杜绝误发错图。

**验证**：全部为静态核验（未执行编译/测试）；5 项改动经子代理逐一核查，2 处硬语法错误（AppNotifier 同行双 const、PendingImageSend 插入 import 之间）已修复。

### 8.45 调优循环 #131：第十二轮（语音进度拖动 + ChatList 重写抛光恢复 6 项）（2026-08-04）

**新功能：语音消息拖动进度条**：语音气泡播放时显示可拖动 Slider（仅当前激活消息），VoicePlayer 新增 `seekTo(messageId, progressMs)`（暂停态也允许 seek 只改进度，MediaPlayer Error 态 runCatching 防护）

**ChatListScreen 重写抛光恢复（Part B 扫描 6 项）**：
1. **[HIGH] 语音 Slider 暂停态假可交互**：isThisActive 不要求播放中、seekTo 暂停返回 → Slider 显示但拖动无反应 → 放宽 seekTo（暂停允许 seek 只改进度）
2. **[MED] 自定义文件夹空态副标题误用操作按钮文案**（chat_folder_show_all「查看全部会话」）→ 新增 `chat_folder_empty_subtitle`（中英）
3. **[MED] 未读优先提示条死状态**：showUnreadPriorityHint/setUnreadPriorityEnabled 从未被 UI 消费（重写丢失）→ 列表上方恢复可关闭提示条（显示未读会话数 + 知道了按钮关闭）
4. **[MED] 删除无进行中反馈**：deletingChatIds 未被消费 → ChatListItem 加 isDeleting（0.45 透明度 + 点击/长按守卫）
5. **[MED] 未接来电时间硬编码英文**（"now"/"5m"/"3h"）→ DateUtils.getRelativeTimeSpanString（地区分区）
6. **[LOW-MED] 列表非空刷新零指示 + 无下拉刷新** → isLoading 且列表非空时顶部细进度条

**验证**：全部为静态核验（未执行编译/测试）；4 项改动 + 3 文件括号平衡经子代理逐一核查全部通过，无编译级错误。

### 8.46 调优循环 #132：第十三轮（为新增/改动纯函数补齐单元测试）（2026-08-04）

**新增测试（4 个测试类 / 12 个用例）**：
1. **MessageMutationPolicyTest** 新增：`TransientCoverageException` → shouldMarkOutboxFailed=false（群 SK 覆盖瞬态失败保持 SENDING）、IllegalStateException epoch 变化 → true
2. **MessageReminderPolicyTest**（新）：窗口常量（1m~30d）、14 档快捷时间严格递增且在窗口内、首档 1m/末档 7d
3. **QuickPhrasePolicyTest**（新）：默认短语合法、trim/空白拒绝、重复拒绝、80 字上限/40 条上限、remove 精确匹配
4. **PasswordValidationTest**（新，server）：≥6 字符、72 ASCII 字节通过 / 73 拒绝、多字节按字节计（"中"×24=72 通过、"中"×25=75 拒绝）

**说明**：按用户要求功能期只写代码、不运行测试——本批为纯函数单测代码（写代码），不执行；供后续收口阶段运行验证。

**验证**：4 项测试经子代理逐一核查（引用/断言/字节计算与实现一致）全部通过。

### 8.47 调优循环 #133：第十四轮（会话免打扰时段 + AI 后端硬化 4 项）（2026-08-04）

**新功能：会话免打扰时段（per-chat 静音窗）**：
- 新文件 `ChatQuietHoursStore`（账号隔离 JSON 存储）+ `ChatQuietHoursPolicy`（纯函数，窗口语义与全局 DND 一致：start==end 关闭、跨天支持）
- 三处通知抑制接入：FCM NEW_MESSAGE、ChatListViewModel WS 路径、BacklogSyncWorker
- UI：聊天溢出菜单「免打扰时段」→ 快捷时段（夜间 22-07/午休 12-14/睡眠 23-08/工作时间 09-18）+ 关闭；单聊/群聊均可用；纯本机不触碰服务端
- 登出清理 hook

**AI 后端硬化（Part B 扫描 4 项）**：
1. **[HIGH] streamResponse 无分块超时**：上游保持连接不发数据时挂起至 120s 客户端超时 → `withTimeoutOrNull(30s)` + isClosedForRead 区分 EOF/超时；超时消息含 "timeout" 关键字（isTransient 可识别重试）
2. **[MED] streamSuggestReplies tone 注入面**：tone 原样拼进 developer 消息 → 截断 40 字符 + 声明不可信（与非流式对齐）
3. **[记录] AiStreamingService 死代码**：streamChatCompletion 用 /responses 解析格式消费 /chat/completions（永不命中 → 零内容成功流）、重试重复流已流出内容——未接线但接线前必修，记录
4. **[记录] 幂等缓存 key 无 userId**：结果由内容派生、危害有限，记录不做大改
5. **[LOW] 清单**：跨聊 QA 候选 chatId 未校验参与者（当前仅回显来源）、token 预算只估输入——记录待后续

**验证**：全部为静态核验（未执行编译/测试）；7 项改动经子代理逐一核查全部通过，无编译级错误；2 处小项（超时消息关键字、未用 import）已顺手修。

### 8.48 调优循环 #134：第十五轮（清理记录项：跨聊 QA 越权 + AI 流解析 + SSE 心跳）（2026-08-04）

**修复记录项（3 项）**：
1. **[MED] 跨聊 QA 候选 chatId 越权回显**：/cross-chat-qa 候选 chatId 未校验参与者 → 响应把任意 chatId 回显为来源（未来按 messageId 取文即升级越权）→ 路由层逐个 `chatRepo.isParticipant` 过滤，无可用会话 403；预算/审计改用过滤后候选
2. **[MED] AiStreamingService.streamChatCompletion 解析缺陷（死代码）**：用 /responses 的顶层 type/delta 解析 /chat/completions 的 SSE（永不命中 → 零内容成功流）→ 改为 `choices[0].delta.content` 解析 + error 字段
3. **[LOW] streamResponse 空行提前结束**：SSE 心跳空行被当流结束 → 改 continue

**验证**：全部为静态核验（未执行编译/测试）；4 大项（含十四轮回归）经子代理逐一核查全部通过，无编译级错误。

### 8.49 调优循环 #135：第十六轮（消息稍后提醒管理闭环）（2026-08-04）

**完善「消息稍后提醒」功能闭环**：此前只有「长按设提醒」入口，无查看/取消现有提醒的 UI——
- ChatDetailViewModel 新增 `listRemindersForChat`（按 chatId 过滤待触发）+ `cancelReminder`（取消 WorkManager 作业 + 清存储）
- ChatDetailScreen 溢出菜单新增「提醒列表」→ 对话框展示本会话待触发提醒（消息预览 + 相对时间），逐条删除（取消后列表即时刷新）
- 字符串中英成对：message_reminder_list_menu/title/empty/media

**验证**：全部为静态核验（未执行编译/测试）；3 项改动经子代理逐一核查全部通过，无编译级错误。

### 8.50 调优循环 #136：第十七轮（好友申请批量操作）（2026-08-04）

**新功能：好友申请批量操作**：
- ContactsViewModel 新增 `acceptAllFriendRequests` / `rejectAllFriendRequests` / `batchMutateFriendRequests`（串行逐个调用既有接口，结果汇总提示）
- ContactsScreen 待处理申请区头部（非搜索态、申请数 >1）显示「全部同意 / 全部忽略」按钮
- 字符串中英成对：accept_all / reject_all / accepted_all / rejected_all / batch_partial（含 %1$d/%2$d）

**验证**：全部为静态核验（未执行编译/测试）；静态复核发现 1 处编译错误（text() 单参传 3 参，改 vararg 变参零影响既有调用）已修。

### 8.51 调优循环 #137：第十八轮（近 10 轮改动回归修复 6 项）（2026-08-04）

**回归扫描（Part B 发现 8 项，修复 6 项）**：
1. **[HIGH] 消息稍后提醒 owner 丢失**：Store encode/decode 不持久化 owner → 时钟回拨重排时 Worker 拿不到 owner 生成僵尸提醒 → encode/decode 补 "owner" 字段；decode 跳过 8.51 前无 owner 的旧行（upsert 自愈清理）
2. **[HIGH] Worker 通知失败孤儿提醒**：showMessageReminder=false 只记日志不清理 → 明确放弃（remove + cancel 作业）
3. **[MED-HIGH] ensureCoverageNow 未纳入互斥**：锁只盖 processDueTasks，发送路径可并发 redistribute 同一任务（双重 SKDM + 双重密钥重置）→ redistribute→verify→delete 段包 processMutex.withLock（已核实无死锁、类型 Result<Long>）
4. **[MED] deleteChat 失败回滚未去重**：WS/刷新已把会话加回列表时重复插入 → 两处回滚加 `none { it.id == chatId }` 守卫
5. **[MED-LOW] scheduleMessageReminder owner 被 "me" 兜底架空**：未登录时写 owner="me" 脏数据 → 严格取 `tokenManager.getUserId()?.takeIf`
6. **[LOW] 打开聊天不清提醒通知**：cancelMessage 只清消息通知 → 遍历 activeNotifications 清 reminder tag（与 cancelAiTaskRemindersForChat 打开即清对齐）
7. **[记录] deleteChat 外层 try 无 catch**：cleanupLocalChat 抛非 Cancellation 异常会逃逸 → 记录待后续
8. **[记录] AiStreamingService 重试重复前缀**：死代码，接线前补 firstDeltaEmitted 防护

**验证**：全部为静态核验（未执行编译/测试）；6 项改动经子代理逐一核查（withLock 非局部返回/死锁/类型、smart cast、activeNotifications 遍历）全部通过，无编译级错误。

### 8.52 调优循环 #138：第十九轮（全量通话记录功能）

**新功能：通话记录（呼出/已接/未接 + 时长）**：
1. **新文件 call/CallLogStore.kt**：账号隔离 SharedPreferences 存储，200 条上限，Direction(OUTGOING/INCOMING) + State(MISSED/ANSWERED)，upsert 幂等（@Synchronized）、list/listForChat 按时间倒序
2. **MissedCallRecorder**：未接来电同步写 CallLogStore（传 expectedUserId 守卫防换号竞态跨账号写入）
3. **CallViewModel**：
   - 新增 writeCallLog(state) helper（isGroupCall 跳过；isVideo=CallType.VIDEO；呼出方向按 isIncoming）
   - 呼出 startCall 占位 MISSED（startedAt=发起时刻）
   - startDurationTimer 接通回写 ANSWERED（呼出占位时 startedAt 重置为接通时刻 → 时长=纯通话不含响铃）
   - endCall 挂断前按最终状态回写（已接通话补时长）
4. **SecureSessionManager**：登出清理加 CallLogStore.clearForUser
5. **ChatListScreen**：未接来电弹层升级为「通话记录」——Room 未接（历史）+ CallLogStore 合并去重倒序；行显示 视频/音频·相对时间·未接徽标/通话时长，missed 红 tint；清空同时清 CallLogStore
6. **字符串**：call_log_title（通话记录/Call history）、missed_calls_badge（未接/Missed）中英成对
7. **静态复核发现 2 编译错误并修复**：@Composable 误打在 CallLogRow data class 上（annotation target 不含 class）、MissedCallsSheet 缺 @Composable（+@OptIn ExperimentalFoundationApi 一并移正）；Icons.Filled.CallMade/CallReceived 不在 core 48 图标集 → 改 Call + tint

**验证**：全部为静态核验（未执行编译/测试）；子代理用 Gradle 缓存 runtime 1.11.1 字节码验证 @Composable target 集合；两处编译错误已修复，其余项通过。

### 8.53 调优循环 #139：第二十轮（近三轮功能回归修复 10 项）

**回归扫描（§8.49 提醒列表 / §8.50 好友批量 / §8.52 通话记录 / 多选批量删除 四区块）**：
1. **[MED] writeCallLog 三处缺 expectedUserId**：通话中换号把旧通话写进新账号 key → helper 内统一传 `tokenManager.getUserId()?.takeIf`，与 MissedCallRecorder 对齐
2. **[MED] 好友批量 busy 永久卡死**：循环内账号切换 return@launch 不复位 isFriendActionBusy → batch/mutate 两处 try/finally 里账号变更时复位
3. **[MED] 批量删除含他人消息 → 403 批量回滚**：批量删除与单条语义对齐——只删本人消息，对话框提示跳过他人条数（新字符串 chat_batch_delete_skipped_others）
4. **[MED] select-all 删 100+ 命中 429 限流「删一半剩一半」**：429 请求未应用 → 加入 isAmbiguousTransportFailure（乐观删除保留，mutation tracker 退避重试）
5. **[MED] 删除会话后提醒不清理**：deleteChat 成功路径按 chatId 取消该会话全部 MessageReminder 作业+存储
6. **[LOW] 提醒列表/取消用 "me" 兜底半删除**：listRemindersForChat/cancelReminder 改严格 getUserId（与 schedule 一致）
7. **[LOW] 拒接/被拒记 MISSED**：主动拒接非未接——endCall 加 logMissed 参数，rejectIncomingCall 传 false（已接通话仍回写时长）
8. **[LOW] Room 优先使应答竞态显 MISSED**：callLogRows 合并改 CallLogStore 优先、Room 兜底
9. **[LOW] 时钟回拨重排忙循环**：MessageReminderScheduler delay 下限 coerceAtLeast(1_000L)
10. **[LOW] 多选可删在途消息**：selectableIds 排除 SENDING + 附件 preparing 消息
11. **[记录] 升级旧提醒孤儿作业**：无 owner 旧行被 decode 丢弃，对应一次性作业到点后无操作自终（可接受）；CallLogStore 截断按插入序（注释已改准）；已接无 callId 不进日志（接受不对称）

**验证**：全部为静态核验（未执行编译/测试）；子代理逐项核查 9 项（expectedUserId 类型、finally 位置、return@withContext 标签、枚举存在性）——发现并修复 1 处编译错误：MessageStatus.PREPARING 枚举不存在 → 改 `it.id !in state.preparingAttachmentMessageIds`。

### 8.54 调优循环 #140：第二十一轮（聊天记录文本导出功能）

**新功能：导出为文本（系统分享）**——与既有「导出」JSON 版本互补（SAF 存完整数据 vs 可读文本分享）：
1. **新文件 util/ChatExport.kt**：buildText（渲染 `<=== 会话 === / exported_at / count / [时间] 发送者: 内容`，SYSTEM/SK_DIST 跳过、REVOKED 标 [消息已撤回]、媒体类型给占位符）、write（cacheDir/exports，文件名净化）、share（FileProvider + ACTION_SEND，UTF-8）
2. **ChatDetailViewModel.exportChatHistory()**：会话门禁 → messageRepo 全量消息 takeLast(2000) → 群聊按 participants 解析成员名、单聊取对端名、「我」标注本人 → IO 写文件 → 分享；空/失败/成功走 infoMessage 提示
3. **ChatDetailScreen**：聊天溢出菜单加「导出为文本」项（紧邻既有「导出」JSON 项）
4. **字符串**：chat_export_history/chat_export_share_title/chat_export_done 中英成对；空/失败复用既有 chat_export_empty/chat_export_failed
5. **静态复核发现 2 问题并修复**：① 重复资源（chat_export_empty/failed 已存在于 269-270 行）→ 删除新增重复项，复用既有；② file_provider_paths.xml 缺 exports/ cache-path → 分享必失败 → 补 `<cache-path name="exports" path="exports/"/>`
6. 失败提示统一走 infoMessage（原误用 groupEncryptionWarning）

**验证**：全部为静态核验（未执行编译/测试）；子代理验证 Regex 转义、FileProvider authority 与 Manifest 一致、when 穷尽、R.string 引用一致。

### 8.55 调优循环 #141：第二十二轮（近四轮回归修复 6 项）

**回归扫描（§8.51–§8.54 四轮改动）**：
1. **[MED 安全] 密聊未门控文本导出**：明文可经分享面板泄出，绕过 JSON 导出的 isSecretChat 门控 → 「导出为文本」菜单项包 `if (state.isSecretChat != true)`
2. **[MED] 导出全量加载 OOM 防护失效**：getMessagesByChatId 全量物化后再 takeLast → 改用 `getRecentMessages(chat.id, MAX_MESSAGES)` 源头限量（DESC+asReversed）
3. **[MED] 429 无实际重试 + 批量超限**：select-all 删 100+ 命中 60/min 限流 → 批量删除封顶 60 条，超限提示分批（新字符串 chat_batch_delete_capped）；429 保留 ambiguous 不回滚
4. **[MED] deleteChat 本地清理非取消异常逃逸**：外层 try 补 catch(CancellationException){rethrow} + catch(Exception){Log.w}；**静态复核发现我引入的多余 `}`（编译错误）并修复**，python 脚本验证四文件括号平衡
5. **[LOW] writeCallLog expectedUserId 恒真守卫**：改传通话开始时的账号快照 callLogOwnerUserId（startCall/呼入 RINGING 各快照一次），通话中异地换号不再写错 key
6. **[记录] 提醒列表对话框/弹层打开期间不刷新**（A5/C4 残余，纯 UI 陈旧，可接受）

**验证**：全部为静态核验（未执行编译/测试）；子代理逐项核查 6 项，发现并修复 1 处编译错误（deleteChat finally 前多余 `}`）+ 1 处结构隐患（CancellationException 被 Exception catch 吞掉 → 加 rethrow 守卫）；用 Python 剥离注释/字符串后校验 5 个改动文件括号全部平衡。

### 8.56 调优循环 #142：第二十三轮（通话核心子系统回归修复 7 项）

**通话子系统扫描（CallViewModel/WebRTCManager/信令/群 mesh/Telecom）**：
1. **[HIGH P1] 群 mesh 边 offer 静默丢弃**：成员先接听时本端 manager 未建，offer 被 `?: return` 丢弃 → 边永久丢失 → 缓冲 pendingGroupOffers（keyed by fromUserId），startCall/answerCall 建 manager 后统一 flush；endCall 清缓冲防串入下一通
2. **[HIGH P2] Telecom 系统来电「接听」不接通应用链路**：用户点系统接听后面应用仍 RINGING 需二次点击，30s 超时还会误记 MISSED → IncomingCallWake/PendingIncomingCall 加 autoAnswer 标志，ANSWER_CALL intent 带 autoAnswer 贯穿 pollPendingOffers→resolveCallerAndNavigate→setPending，IncomingCallRoute 进入即自动接听（权限仍走统一请求）；WS 路径默认 false 不受影响
3. **[MED P5] 原生线程改 VM 状态非 volatile**：endingCall/iceRestartAttempts 改 @Volatile（可见性）
4. **[MED P6] REST 轮询未过滤群 mesh offer**：旋转/FCM 唤醒把群内边 offer 当新来电 RINGING → 轮询过滤加 `groupId.isBlank() || groupInvite`（与 WS 路径对齐）
5. **[LOW P9] 门禁失败不 release manager**：startCall/answerCall/startGroupCall 三处门禁失败分支统一 `runCatching { manager.release() }`
6. **[LOW P3 记录] 被叫乐观 CONNECTED vs 主叫等 ICE 日志分裂**：改动风险高，记入待定（不改动）
7. **[LOW P4 记录] TURN 刷新对活跃直连 PC 无效**：需重建 PC/重协商，记入待定；P7 群被取消不记 MISSED、P8 800ms 窗口新来电被挂、P10 群 MISSED 记发起者均记入待定

**验证**：全部为静态核验（未执行编译/测试）；子代理逐项核查 5 项（hasGroupPeer/acceptGroupOffer 签名、autoAnswer 贯穿各调用点、@Volatile 写法、门禁 release 语法）全部通过；补 2 处一致性（endCall 清缓冲、startGroupCall release）后 python 脚本验证 3 个改动文件括号平衡。

### 8.57 调优循环 #143：第二十四轮（群公告会话顶部横幅功能）

**新功能：群公告在会话顶部展示**（此前仅群详情可编辑查看，对标 TG/微信群公告栏）：
1. **ChatDetailScreen 状态**：showAnnouncementBanner（默认显示、会话内可折叠）、showAnnouncementDialog（全文弹窗）
2. **GroupAnnouncementBanner**（新 Composable，镜像 PinnedMessagesBanner 样式）：Primary 淡底、Info 图标、标题「群公告」+ 单行省略公告正文、点击开全文、右侧关闭按钮；仅 `chatIsGroup && groupAnnouncement 非空` 时显示
3. **公告全文弹窗**：verticalScroll 长文、关闭按钮（common_close）
4. **字符串**：group_announcement_banner_title / group_announcement_dialog_title 中英成对（无重复）；复用 common_close；修正我误引的 common_ok/chat_pinned_banner_dismiss（不存在）
5. **import 补齐**：Icons.Outlined.Info、Icons.Filled.Close（均在 core 48 图标集）

**验证**：全部为静态核验（未执行编译/测试）；子代理逐项核查（state 字段、智能转换、9 个 import 齐全、字符串无重复、括号平衡 2288/2288）全部通过，无需修改。

### 8.58 调优循环 #144：第二十五轮（发现/动态子系统回归修复 7 项）

**动态子系统扫描（Feed/发帖/评论/点赞/草稿/附近/审核联动）**：
1. **[HIGH P1] 附近 stopSharing 与在途 refresh 竞态**：关闭共享后位置被 refresh 重新广播 → stopSharing 启动即 `++refreshGeneration` 失效在途 refresh + POST 前 `generation/isSharing` 二次校验
2. **[MED-HIGH P2] 审核删除无传播且无提示**：版主删除后客户端残留 → toggleLike/loadPostDetail/getPostComments 三处 onFailure 识别 404 → 本地移除 + 「该动态已被删除」提示（新字符串 explore_post_deleted）；WS POST_DELETED 实时事件记入待定（需服务端配合）
3. **[MED P3 记录] 朋友圈不接分页**：只显示首页 40 条过滤子集 → 记入待定（UI 接入 loadMore 触发）
4. **[MED-LOW P4] 草稿 legacy 迁移跨账号泄漏 + 共享标记吞 composer**：一键迁移改每键独立标记（composer_migrated_v1 / visibility_migrated_v1），避免先登录账号继承上一用户文本、也避免初始化顺序吞掉 composer 旧草稿
5. **[LOW P6] openComments 未重置 isSendingComment**：切换帖子时显式复位，防按钮永久禁用
6. **[LOW P7] postId URL 编码不一致**：editPost/deletePost/likePost/unlikePost/getPostComments/createPostComment 六处统一 URLEncoder（与 getPost 对齐）
7. **[LOW P9] 搜索过滤激活仍触发 loadMore**：snapshotFlow 加 `feedSearch.isBlank()` 守卫暂停上拉加载
8. **[LOW P5/P8/P10 记录]**：评论删除/编辑（需服务端 API）、详情页冗余拉 feed、作者页可见性图标/编辑入口 → 记入待定

**验证**：全部为静态核验（未执行编译/测试）；子代理逐项核查 6 项（generation 作用域、SharedPreferences 标记、isSendingComment 字段、六处编码括号、feedSearch 作用域、ApiException 字段）全部通过；发现并修复 1 处逻辑问题（P4 共享迁移标记被 visibility 先读吞掉 composer 草稿 → 改每键独立标记）；括号平衡验证通过。

### 8.59 调优循环 #145：第二十六轮（朋友圈分页功能）

**新功能/修复：朋友圈（Moments）接入分页**（补齐上轮 §8.58 P3 记录项）：
1. **MomentsScreen 分页**：`rememberLazyListState` + `snapshotFlow` 滚动到底触发 `viewModel.loadMore()`（与 ExploreScreen 同款模式）；搜索激活或已到底（`state.hasMore`）暂停，避免过滤视图不增长时持续拉取
2. **import**：补 `kotlinx.coroutines.flow.distinctUntilChanged`
3. **空态不误拉取**：`totalItemsCount > 0` 条件使空列表时不触发
4. 此前朋友圈仅显示首页 40 条过滤子集（PUBLIC + 近 30 天），无任何翻页——现可滚动加载后续页

**验证**：全部为静态核验（未执行编译/测试）；子代理逐项核查 5 项（import 无重复、LaunchedEffect/rememberLazyListState 全限定路径、作用域捕获、snapshotFlow 类型与括号配平、空态提前 return 不影响状态接续）全部通过，无需修改。

### 8.60 调优循环 #146：第二十七轮（附件管道回归修复 4 项）

**附件管道扫描（分片上传/续传/下载/协调器/加密/清理/弱网）**：
1. **[HIGH B] Worker claim 丢失后传输永久卡死**：进程死亡 2min 内重启，行 updatedAt 新鲜无法重领 → claim 失败改重读行，仍处 QUEUED/UPLOADING/FAILED 返回 `Result.retry()`（WorkManager 指数退避，stale 窗口过期后重领），不再直接 success
2. **[MED C] finalize 与删除聊天竞态 → 幽灵 SENT 消息**：sendMessage 成功后被 cancelForChat/deleteAll 删行，insertMessage 仍落库 → send 成功后、insertMessage 前重读 transfer 行，已删除则返回 ClaimInvalidated（服务端已投递，重同步拉回）
3. **[MED D] 同 attachmentId 并发下载共用 .part 损坏**：`createEncryptedDownloadFile` 加 discriminator 参数（messageId），两处调用点传入——临时文件按 attachmentId+messageId 命名，防互相截断/删除
4. **[记录 A] 已 COMMITTED 附件被 DELETE**：核验服务端 `removeUncommitted` 有 `status neq COMMITTED` 守卫，已提交附件服务端拒删 → 无数据丢失；客户端 ignore 删除结果属无害（行清理语义正确）
5. **[记录 E/F/G/H/I/J]**：48h 清理不感知在途行、cancelAll 跳过 SENDING 计数、取消删 .part 取舍、mutex 永不清、Retry-After 未用、进度近似值 → 记入待定

**验证**：全部为静态核验（未执行编译/测试）；子代理逐项核查 3 项（when 多条件分支、smart cast 类型、默认参数兼容 + 全库仅两处调用、正则无转义问题）全部通过；括号平衡验证通过。

### 8.61 调优循环 #147：第二十八轮（登录/认证子系统回归修复 7 项）

**认证子系统扫描（Token 生命周期/登出/注册/找回密码/TOTP/设备管理/竞态）**：
1. **[MED P2-2] TOTP 等端点绕过 401 自动刷新**：getTotpStatus/setup/confirm/disable 走 executeForText 无刷新 → 改为带 Authorization 时走 executeWithRefresh（401 自动刷新 + tokenExpired 触发），匿名端点仍走 executeRequest
2. **[MED P2-3] 删除当前设备自锁**：客户端本地 signalProtocol.getDeviceId() 重建后不可靠 → 服务端 delete-device 路由新增守卫：按 auth session 绑定的 signalDeviceId 判定，目标 = 当前登录设备则 400「不能移除当前登录设备」（服务端权威）
3. **[MED P2-5] 冷启动假登录态**：isLoggedIn() 只判 token 非空 → 增加 refreshTokenExpiresAt>0 且已过期则视为未登录（服务端吊销/refresh 到期后不再长期假登录）
4. **[LOW P3-2] LoginViewModel.logout() 无重入守卫**：加 logoutJob isActive 守卫（与 SettingsViewModel 一致）
5. **[LOW P3-3] 邮箱校验过弱**：两处 contains("@") 改 `Patterns.EMAIL_ADDRESS`（并修正注释表述）
6. **[LOW P3-5] 改邮箱不重置倒计时**：onEmailChange 取消 countdownJob 并重置 codeSent/codeCountdown
7. **[LOW P3-7] tab 切换残留 TOTP 码**：onTabSelected(0) 清 totpCode
8. **[HIGH P1-1 核验为误报]**：改密 mutex 实际有 `job.invokeOnCompletion { unlock() }`（1793 行）——扫描漏看，锁在作业完成/失败/取消时均释放，无永久泄漏
9. **[记录 P2-1/P2-4/P3-1/P3-4/P3-6]**：TOTP 无密码复核+无恢复码（锁死风险）、登出与刷新竞态、缺「退出所有设备」入口（logoutAll 无调用点）、注册无 codeSent 前置/确认密码、TOTP secret 明文/剪贴板 → 记入待定

**验证**：全部为静态核验（未执行编译/测试）；子代理逐项核查 7 项（executeRequest/executeWithRefresh 签名、HttpResult 字段、isLoggedIn 全库调用方兼容、logoutJob、Patterns、onEmailChange/onTabSelected 字段存在、JwtConfig.authSessionId 签名 + AuthSessions 列 + Exposed 0.46 语法）全部通过；6 文件括号平衡验证通过。

### 8.62 调优循环 #148：第二十九轮（「退出所有设备」功能）

**新功能：退出所有设备**（补齐 §8.61 P3-1 记录项——设备丢失/被盗时远程撤销全部会话）：
1. **SettingsViewModel.logoutAllDevices()**：accountMutationJob 守卫 → 门禁校验 → `ApiService.logoutAll`（此前有 API 无 UI 调用点）→ 成功后在 NonCancellable 内 WS 断开 + `purgeLocalSession(expectedOwnerUserId)`（服务端已吊销当前会话，本地必须 purge 防假登录态）→ isLoggedOut
2. **SettingsUiState.isLoggingOutAll**：busy 态驱动按钮禁用 + 转圈
3. **AccountSecurityScreen**：设备列表后新 SecurityGroup 行（Warning 图标 + 副文案），点击弹确认对话框（含明示「当前设备也将被登出，本机加密数据会保留」），busy 时禁关闭/确认
4. **字符串**：account_logout_all / account_logout_all_subtitle / account_logout_all_confirm / settings_logout_all_failed 中英成对（无重复）

**验证**：全部为静态核验（未执行编译/测试）；子代理逐项核查 4 项（logoutAll 签名、purgeLocalSession 参数名 expectedOwnerUserId、clickable(enabled) 语法 + 同文件先例、4 个字符串无重复、括号平衡 373/373 与 683/683）全部通过，无需修改。

### 8.63 调优循环 #149：第三十轮（群组治理回归修复 5 项）

**群组治理扫描（权限矩阵/转让/禁言/邀请/成员/审计/公告/大群）**：
1. **[HIGH A] 频道邀请先入群后 403**：join-by-invite 原在 consumeGroupInvite 之后才查 CHANNEL 类型 → 用户已被写入成员+useCount+memberRevision 才收到 403 → 在 consumeGroupInvite 事务内（写入成员前）判定频道并返回 channelRejected 标志，路由据此 403；Bot exportChatInviteLink 同样拦截频道（与 App 侧 invite-token 一致）
2. **[MED-HIGH C] 被禁言 WS 发消息无提示、SENDING 永久卡死**：服务端 WS ERROR 帧无 messageId 无法精确对位 → ChatDetailViewModel 加 WebSocketEvent.ServerError 分支，识别禁言/拉黑/无权限/限流/内容无效/单向广播类拒绝（排除「消息 ID 已存在」接受语义），将本会话在途 SENDING 标 FAILED + 横幅提示（此前仅靠 REST outbox flush 触发 403 才有反馈）
3. **[LOW F] 群昵称长度 120 vs 100**：客户端 take(100) 与服务端对齐
4. **[LOW G] 公告 1000 vs 1200 + OWNER 残留禁言徽标**：客户端公告 take(1200)；成员列表对 OWNER 角色豁免禁言徽标（服务端同样豁免发言拦截）
5. **[MED B 记录] 邀请参数打开弹窗静默重置**、**[MED D 记录] 群审计无 offset 分页**、**[MED E 记录] 加人候选仅前 30 人**、**[LOW H 记录] 群玩法未禁言校验** → 记入待定
6. **核验**：权限矩阵/转让并发/禁言拦截纵深/邀请并发消费/头像公告并发安全/成员数上限均正确，无越权

**验证**：全部为静态核验（未执行编译/测试）；子代理逐项核查 4 项（ChatType 常量比较、getChatByIdInTx 存在、join-by-invite 旧调用已删、ServerError 字段 + markWsRejectedSending 在 collect suspend 上下文合法 + 误编辑已修复）全部通过；4 文件括号平衡（剥离注释/字符串后完全平衡）验证通过。

### 8.64 调优循环 #150：第三十一轮（群审计 offset 分页功能）

**新功能：群审计历史翻页**（补齐 §8.63 D 记录项——此前历史审计最多可见 100 条，活跃群的更早记录永远无法获取）：
1. **服务端 ChatRepository.getGroupAudit**：加 `offset: Int = 0` 参数，`.limit(limit, safeOffset.toLong())`（Exposed 0.46 limit(Int, Long) 重载，与 AdminRouting 先例一致）
2. **服务端路由**：audit 端点读 `offset` query 参数
3. **客户端 ApiService.getGroupAudit**：加 offset 参数（默认 0，既有调用兼容）
4. **GroupDetailViewModel（同文件）loadMoreAudit()**：守卫（isLoadingMoreAudit/hasMoreAudit/token）→ 门禁 → offset=已加载条数 → 空页终止 / 追加 + distinctBy + hasMoreAudit=page>=100
5. **UiState**：加 isLoadingMoreAudit / hasMoreAudit；load() 初始 `hasMoreAudit = auditLogs.size >= 100`
6. **UI**：展开按钮四态（加载中… / 加载更早记录 / 收起 / 还有 N 条），展开后 hasMore 时点击分页加载
7. **字符串**：group_detail_audit_load_more / group_detail_audit_loading 中英成对（无重复）

**验证**：全部为静态核验（未执行编译/测试）；子代理逐项核查 6 项（limit(Int, Long) 重载 + AdminRouting 佐证、表列名、位置实参顺序、调用点兼容（全库仅 2 处）、UiState 字段/import 齐全、四态 when、字符串无重复）全部通过；4 文件括号平衡（单遍状态机扫描归零）验证通过。

---

*本报告于 2026-07-20 按代码树现状重写，取代此前按 push #1…#66 无限追加的进度日志。*

### 9.1 2026-08-13 无限调优：官网重构 / clean URL / 构建与安全加固

**官网 UI 重构**：
1. 首页整体重做为「毛豆绿 + 暖纸色 + 墨色」双主题，移除旧深紫渐变、光斑和 emoji 卡片。
2. Hero 改为沉浸式聊天产品场景，支持聊天 / 密聊 / AI 三态交互预览；桌面与移动端均无横向溢出。
3. FAQ / 隐私 / 条款 / 安全 / 帮助 / 开发者中心统一新视觉、logo、SVG 主题切换和 favicon。
4. 首页新增 `/api/public/status` 实时服务状态指示，维护 / 在线 / 未知三态，异常与超时优雅降级。
5. 新增 PWA 支持：`/manifest.webmanifest`、512×512 图标、首页 manifest 声明与 Service Worker 离线壳层，可安装为桌面/手机应用。

**clean URL 与 SEO**：
1. 新增 `/faq`、`/privacy`、`/terms`、`/security`、`/help`、`/developer` 无后缀路由。
2. 旧 `/xxx.html` 全部 301 到新地址；canonical、站内链接、`robots.txt` 已同步。
3. 新增 `/sitemap.xml`（仅收录 clean URL）与 `/.well-known/security.txt`。
4. 全站补齐 `theme-color`，新增官网端到端脚本 `npm run test:website`。

**Bug 修复与加固**：
1. 服务端复用 JSON 实例、清理 deprecated 群成员上限委托、删除重复 `/` 路由。
2. 修复群 PK 快捷符 `?:` 优先级、群接龙 null 崩溃、WebSocket 恒真条件、TOTP/AI 预算内存表无界增长、登录锁定表无界增长。
3. App 清理 57 处无效 `it ?: ""`、冗余 `!!`、弃用 AutoMirrored 图标与 Coil opt-in。
4. 审计路径穿越、SQL 注入、命令执行、Android 清单暴露面，未发现可复现高危问题。
5. 落地待定项：群玩法禁言校验——签到、接龙创建/参与、PK 创建/投票对被禁言成员统一返回 403，与发消息/附件/回应路径一致。

**构建与验证**：
1. `:server:test` 全量通过；`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过。
2. `:app:assembleDebug` 通过；`:app:assembleRelease` 通过（unsigned，12.21MB，`verifyReleaseSize` 通过）。
3. `npm run test:website`（14 页面/视口检查 + 3 静态路由）与 `Admin browser E2E` 通过。
4. `git diff --check`、`node --check`、中英 string parity 全部通过。

### 9.2 2026-08-13 无限调优：TOTP 重放与群玩法禁言收口

1. **TOTP 同窗口重复码放行**：`TotpService` 原用 `merge + maxOf` 判断，旧值等于新 counter 时仍返回 true，同一 30s 窗口的 code 可被重复接受；改为 `compute` 显式标记是否插入/推进，并新增回归测试。
2. **TOTP 内存清理单位错误**：原 sweep 把 TOTP counter（30s 步进）与毫秒时间戳比较，达到阈值会误删全部记录；记录改为 `ReplayRecord(counter, acceptedAtMs)` 后按实际接受时间清理。
3. **关闭 TOTP 被内存重放守卫误拦**：`disableTotp` 本意只做时限校验，现通过 `trackReplay = false` 显式跳过进程内重放守卫（DB 权威层仍不受影响）。
4. **群玩法禁言补漏**：常规投票创建/投票端点同样对被禁言成员返回 403；接龙参与改为非成员/接龙不存在统一 403，并在限流前拦截，避免消耗群玩法配额。
5. **登录锁定表清理时机**：sweep 从「仅失败时」扩展到每次登录尝试前执行，成功登录后仍无法触发新请求的陈旧条目也不会长期滞留。

**验证**：`:server:test` 74 个测试全绿（新增 TOTP 同窗口重放、禁言投票/接龙断言）；`:app:testDebugUnitTest`、`:app:lintDebug`、`npm run test:website`、`Admin browser E2E` 全部通过；`git diff --check` 与中英 string parity 通过。

### 9.3 2026-08-13 无限调优：并发锁清理竞态与取消语义

1. **SignalProtocol.ensureSession 锁清理竞态**：原实现持锁结束时直接 `sessionSetupLocks.remove(lockKey)`，正在等待同一把锁的协程仍会继续，后续调用会新建 Mutex 并发建立会话，可能双消耗一次性预密钥；改为带引用计数的 `SessionSetupLock`，条目只在最后一个使用者退出时移除。
2. **LinkPreviewRepository 同类竞态**：`inFlight` 也在持锁结束直接移除，并发同 URL 拉取可能重复请求；同样改为引用计数清理。
3. **SenderKeyRetryManager.adoptOrphans 吞取消**：`runCatching` 包裹 suspend DAO 调用，Worker 取消时 CancellationException 被吞；改为 try/catch 并重抛取消，与 BacklogSyncWorker 口径一致。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；服务端全量测试维持上一轮全绿状态。

### 9.4 2026-08-13 无限调优：Worker 取消语义收口

1. **SecretSurfaceWatchdogWorker TTL 清扫吞取消**：`listActivity`/`sweepExpired` 整体包在 `runCatching` 内，WorkManager 停止时 CancellationException 被吞；改为 try/catch 重抛，并同步修正“暂不接入 TTL”的过时注释。
2. **SecretSessionTtl.destroySession 吞取消**：搜索索引清理的 `runBlocking` 由 `runCatching` 包裹，同样改为重抛取消。
3. **ScheduledMessageWorker 重复定时重排吞取消**：重排下一次定时消息的 `runCatching` 不再吞 CancellationException；失败仍保留原有“跳过重排、继续移除当前条目”语义。
4. **ScheduledMessageWorker.abandonScheduledMessage 吞取消**：达重试上限后的放弃路径整体包在 `runCatching` 内，取消时仍会弹失败通知并返回 success；改为重抛取消。
5. **AttachmentTransferWorker.finalize 标记失败吞取消**：重试耗尽后标记 FAILED 的 `runCatching` 不再吞 CancellationException。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.28 2026-08-13 无限调优：ChatDetail 草稿删除取消语义

1. **clearDraftPersistence 吞取消**：Room 草稿删除用 `runCatching` 包裹，取消会被吞；改为 try/catch 重抛 `CancellationException`。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.27 2026-08-13 无限调优：ChatDetail 清空聊天取消语义

1. **clearLocalChatContent 吞取消**：ChatDetail 的“清空本地聊天”清理路径用多个 `runCatching` 包裹挂起调用；改为 `bestEffort` 辅助，统一重抛 `CancellationException`。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.26 2026-08-13 无限调优：AI 本地清理取消语义

1. **ChatDetail AI 缓存/任务清理吞取消**：`pruneOlderThan` 两处 `runCatching` 会吞取消；改为 try/catch 重抛 `CancellationException`。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.25 2026-08-13 无限调优：删号本地清理进 NonCancellable

1. **deleteAccount purge 可被取消打断**：服务端删号成功后本地 `purgeLocalSession` 与 presence 清理原先不在 `NonCancellable`，协程取消会留下半清理状态；改为 `NonCancellable` 内执行。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.24 2026-08-13 无限调优：退出所有设备/删号清理 presence

1. **logoutAllDevices/deleteAccount 未清 presence**：普通登出会清 `TypingPresenceStore`，但退出所有设备和删号路径漏掉；补上，并在 logoutAll 中放入 `NonCancellable`。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.23 2026-08-13 无限调优：登出 presence 清理进 NonCancellable

1. **logout presence 清理可能被取消跳过**：`SettingsViewModel.logout` 原先在 `NonCancellable` 外清 `TypingPresenceStore`，purge 后协程取消会残留对端“正在输入”状态；移入 `NonCancellable` 内。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.21 2026-08-13 无限调优：运行时配置刷新失败保留旧缓存

1. **RuntimeConfigService DB 瞬时故障清空配置**：`refreshIfStale` 原先 `runCatching(...).getOrDefault(emptyMap())` 后无条件 clear + 推进 loadedAt，DB 抖动会让运行配置短暂失效；改为失败时直接返回，保留旧缓存且不推进 loadedAt。

**验证**：`:server:test` 74 个测试全绿；`git diff --check` 无输出。

### 9.22 2026-08-13 无限调优：清除草稿取消语义

1. **clearChatDraft 吞取消**：`ChatListViewModel.clearChatDraft` 用 `runCatching` 包裹 Room 删除；改为 try/catch 重抛 `CancellationException`。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.20 2026-08-13 无限调优：小组件快捷回复账号归属校验

1. **handleReplySent 缺账号归属校验**：旧账号残留 widget 的快捷回复会拿当前账号 token 尝试发送；补 `NotificationIntentPolicy.belongsToCurrentAccount`，与打开会话/mark-read 路径一致。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.18 2026-08-13 无限调优：WS session 发送锁清理竞态

1. **sessionSendLocks 直接删除导致并发写帧**：`sendSafe` 取得锁后，关闭路径直接 `remove` 锁条目；等待中的发送者仍会继续，后续发送会新建锁并发写同一 session。改为 `SessionSendLock(lock, users)` 引用计数，仅无使用者时删除。

**验证**：`:server:test` 74 个测试全绿；`git diff --check` 无输出。

### 9.19 2026-08-13 无限调优：群通话远端轨道挂接竞态

1. **handleGroupRemoteTrack 未与 renderer 共用锁**：群通话信令线程写 `groupRemoteVideoTracks`/读 `groupRemoteRenderers`，而 attach/remove/release 都在 UI 线程持 `this` 锁；改为同一把 `this` 锁，避免对端移除后仍挂 sink。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.17 2026-08-13 无限调优：清空本地聊天记录取消语义

1. **clearLocalChatHistory 吞取消**：整段清理用多个 `runCatching` 包裹挂起调用，取消后仍会继续清索引/角标并 reload；改为 `bestEffort` 辅助函数，统一重抛 `CancellationException`，非取消异常仍按尽力而为吞掉。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.16 2026-08-13 无限调优：AI 画像/OCR 索引取消语义

1. **AiConversationProfile.build 吞取消**：拉取服务端叙事摘要的 `runCatching` 会吞掉取消；改为 try/catch 重抛。
2. **ImageOcrAutoIndexer 吞取消**：密聊 ID 读取、OCR 下载、结果落库三处 `runCatching` 均可能吞取消；改为 try/catch 重抛。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.15 2026-08-13 无限调优：AI 任务清理提醒调度取消语义

1. **deleteCompletedByChatId 吞取消**：清理已完成任务时逐条取消提醒调度的 `runCatching` 会吞 `CancellationException`；改为 try/catch 重抛取消，与其余 Worker/仓库清理路径一致。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.14 2026-08-13 无限调优：密聊媒体缓存目录路径隔离

1. **chatId 直接拼密聊缓存目录**：写入/创建/删除密聊媒体时曾直接用 chatId 作为子目录名；异常 chatId（`.`、`..`、含路径分隔符/空白）可能越界写或误删整个密聊缓存根目录。新增 `secretChatDir` 统一解析：拒绝非法字符并做 canonical 越界校验，三个入口（写入、创建、删除）全部改用它。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.13 2026-08-13 无限调优：Backlog 同步后前台列表预览刷新

1. **Backlog 同步不刷新列表预览**：后台增量同步插入新消息后，前台聊天列表的尾部预览/排序要等下一次 `getChats` 才更新；现同步成功且有新消息时补发 `emitChatListPreviewRefresh(chatId)`，从 Room 重算尾部。未读计数仍以服务端会话列表为准，避免本地误增造成跨设备角标残留。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.12 2026-08-13 无限调优：WebRTC onTrack 渲染器竞态

1. **onTrack 无锁访问渲染器字段**：直接通话 `onTrack` 在信令线程写 `remoteVideoTrack`/读 `remoteRenderer`，而 `attach/detachRemoteRenderer` 在 UI 线程持 `this` 锁，可能看到旧值或复合读写交错；改为同一把 `this` 锁，避免黑屏/释放后挂 sink，且不引入死锁（回调移出锁外）。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.11 2026-08-13 无限调优：WebSocket 空 reason 空指针

1. **onClosing reason 可空被当非空**：OkHttp `onClosing` 的 `reason` 实际可为 null；此前清理 `reason != null` 判断后，1008 + 空 reason 会先撞进 `isRecoverableExpiryReason(reason: String)` 触发 NPE。两个 reason 判定函数改为可空参数，并保护“连接数超限”分支。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.9 2026-08-13 无限调优：AI 预算并发锁清理竞态

1. **budgetMonitors 提前删除导致预算并发守卫失效**：`AiGatewayService.checkBudget` 在“已取 monitor 但尚未写入预留”的窗口内，sweep 看到 `budgetReservations` 为空会删除该 monitor，后续请求新建锁即可与进行中的请求并发通过预算检查；改为 `BudgetMonitor(lock, users)` 引用计数，取锁 +1、释放 -1，仅无使用者和无预留时删除。

**验证**：`:server:test` 74 个测试全绿；`git diff --check` 无输出。

### 9.10 2026-08-13 无限调优：通话记录截断按时间裁剪

1. **CallLogStore 截断可能裁掉较新记录**：`upsert` 原先按 JSON 数组插入序 `remove(0)` 截断到 200 条；乱序写入（晚写旧记录）时可能裁掉较新条目。改为按 `startedAt` 降序排序后只保留最近 200 条。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.8 2026-08-13 无限调优：AI 任务中心通知已读

1. **AI 任务中心行已读**：打开 AI 任务页只清系统托盘与 WorkManager 提醒作业，通知中心 `AI_TASK` 行仍是未读；新增 `NotificationCenterRepository.markAiTasksRead(chatId)`，在任务真正开始展示时调用（含解锁后路径），未读角标/过滤同步收敛。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.6 2026-08-13 无限调优：群玩法“满员/已关闭”错误语义

1. **群接龙满员误报成功**：`joinChain` 满员时原返回 DTO（200 + myJoined=false），客户端会以为加入成功；改为返回 null，路由回 400「接龙已结束或人数已满」。
2. **已关闭投票再投票误报成功**：`GroupPlayRepository.vote` 对已关闭 poll 返回 null，路由回 400「投票失败」，不再返回旧状态 200。
3. **已关闭 PK 再投票误报成功**：`GroupCheckinRepository.votePk` 对已关闭 PK 返回 null，路由回 400「PK 投票失败」。

**验证**：`:server:test` 74 个测试全绿（新增满员接龙、关闭投票/PK 再投票断言）；`git diff --check` 无输出。

### 9.7 2026-08-13 无限调优：小组件标记已读同步服务端

1. **Widget mark-read 只清本地角标**：`ConversationWidgetProvider.handleMarkRead` 此前只调 `ChatRepository.markChatRead`，服务端未读计数与已读回执不更新，换设备/重同步后未读会复活；现先校验会话并调用 `ApiService.markAllAsRead`，成功/失败均保留本地清理兜底。
2. **Widget mark-read 缺账号归属校验**：旧账号残留 widget 点击会拿当前账号 token 标记任意 chatId；补 `NotificationIntentPolicy.belongsToCurrentAccount`，与打开会话路径一致。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.5 2026-08-13 无限调优：跨设备 AI 同步重复投递

1. **AiMessageMetaSyncRepository Duplicate 不 ACK**：`DecryptResult.Duplicate` 表示 libsignal 已消费该信封（重复/乱序投递），原实现与 NoSession 一样跳过 ACK，服务端会持续重投同一 payload；现改为 ACK。
2. **AiSummarySyncRepository 同问题**：摘要同步对 Duplicate 信封同样改为 ACK，避免每次周期拉取都重复解密同一信封。

**验证**：`:app:testDebugUnitTest` 与 `:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.29 2026-08-13 无限调优：群加人候选全量分页

1. **群“添加成员”候选只显示前 30 人**：`ChatDetailViewModel.loadGroupCandidates` 与 `GroupDetailScreen.load` 都调用不带参数的 `getUsers()`，服务端 `/api/users` 默认 `limit=30` 且无 `offset`，用户量大时大部分可加联系人永远不出现。现服务端 `GET /api/users` 支持 `offset` 分页；App 新增 `ApiService.getAllSearchableUsers`（每页 100 人循环拉取，上限 1000，避免撞用户搜索限流），两处群加人 UI 统一改用它。
2. **拉黑用户占掉分页容量**：`getAll`/`searchUsers` 原先 SQL `LIMIT` 后才内存过滤拉黑用户，分页首 100 行若多为拉黑用户，候选页会缩水甚至清空；改为 `notInList` 把拉黑过滤下沉到 SQL 条件后再分页。

**验证**：`:server:test` 76 个测试全绿（新增 offset 分页无重叠、拉黑不占页容量断言）；`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.30 2026-08-13 无限调优：Room v27→v30 迁移测试补全

1. **迁移测试只到 v26，v27→v30 零验证**：新增 `AppDatabaseMigrationTest.migrate25To30RunsFullChainAndPreservesChatUsersAndSecretChats`，从 v25 一路迁移到 v30，覆盖群聊 `chatType` 推导、密聊 `lastActivityAt` 默认值、用户 `lastSeen` 默认值、B7 新增索引与消息/聊天数据保留。

**验证**：`:app:compileDebugAndroidTestKotlin` 通过（迁移测试为 androidTest，需真机/模拟器执行，未在本机跑 `connectedAndroidTest`）；`:server:test` 75 个测试全绿；`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.31 2026-08-13 无限调优：过期 refresh token 前缀不再误吊销活跃会话

1. **管理端按 tokenHash 前缀吊销会把过期 token 当候选**：`AuthTokenRepository.revokeByHashPrefixWithSessions` 的候选查询只过滤 `revokedAt IS NULL`，未过滤 `expiresAt <= now`；若管理员用旧导出/旧审计里的过期前缀操作，会把该过期 token 所属的整个活跃会话（含新轮换 token）一起吊销。候选与加锁重读两处均补 `expiresAt > now`，仅活跃 token 可触发会话吊销。
2. **新增回归测试**：`AuthTokenPrefixRevocationTest` 构造“同会话过期 token + 新活跃 token”，断言过期前缀返回 `count=0`、不吊销 session、不吊销活跃 token。

**验证**：`:server:test` 77 个测试全绿；`git diff --check` 无输出。

### 9.32 2026-08-13 无限调优：来电页 800ms 窗口误清新来电

1. **旧来电结束后的延迟清理会吞掉新来电**：`IncomingCallRoute` 在 `DISCONNECTED` 后固定延迟 800ms 再 `IncomingCallCoordinator.clear()` + `popBackStack`；若这 800ms 内新 offer 已写入 pending，旧协程会把新来电一起清掉并退出页面。延迟清理与手动挂断路径都改为先校验当前 pending 属于已结束来电（同 callId；空 callId 用联系人兜底）或为空，才清理/退出。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.33 2026-08-13 无限调优：群发起者振铃期取消不记 MISSED

1. **群通话 hang-up 分支只移除对端**：收到群发起者的取消信号时，若本机仍处于 RINGING，原逻辑仅 `removeGroupPeer` + 标记终端，未落未接记录也不退出来电页。现当 `fromUserId == 群发起者 contactId` 且为呼入 RINGING 时，与 1:1 挂断一致地写 `MissedCallRecorder`（稳定 callId 幂等）、清 pending 并结束来电；普通成员退出仍按对端离开处理，不影响其余群成员通话。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.34 2026-08-13 无限调优：AI 日预算预检补输出 token 预留

1. **预算预检只估输入**：全部 `checkBudget` 调用都用 `estimateTokens(输入文本)`，输出 token 要等请求结束落账后才计入，连续大输出请求可在实际记账前反复通过预检、突破每日预算。`checkBudget` 现在在输入估算上固定追加 1024 token 保守输出预留，软预算更贴近真实计费。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.35 2026-08-13 无限调优：Bot 创建失败错误分类

1. **创建 bot 失败一律折叠成 null**：`BotRepository.create` 对用户名占用、用户名非法、数量上限、账号异常全部返回 null，两个路由只能回 400“用户名非法或已占用”。改为 `BotCreateResult` 区分 `Success/InvalidInput/UsernameTaken/MaxBotsReached/OwnerInvalid`；用户名占用和数量上限回 409，非法输入回 400，成功照常返回 DTO。
2. **新增回归测试**：`BotCreateOutcomeTest` 断言重复用户名与非法用户名返回不同结果。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.36 2026-08-13 无限调优：贴纸包下载锁清理

1. **`packLocks` 只增不减**：`OnDemandStickerStore` 用 `computeIfAbsent` 为每个包永久保留 Mutex，长期运行/频繁换包时锁表无界增长。改为带引用计数的 `PackLock`，使用完且无等待者时移除条目，与 Signal/WS/AI 预算锁同一清理模式。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.37 2026-08-13 无限调优：评论编辑闭环

1. **评论编辑此前无服务端 API**：新增 `PUT /api/posts/{id}/comments/{cid}`，仅作者本人可编辑，路由层走与发评论一致的 `isValidCommentPayload` + 内容审核 + 限流；`PostRepository.updateCommentForUser` 校验评论归属和所属动态后原子更新并返回最新评论。
2. **客户端入口**：详情页评论长按菜单新增“编辑评论”，弹窗输入新内容后调用 `ApiService.editPostComment`，成功后替换本地评论列表；中英文案成对。
3. **新增端到端测试**：`CommentEditRouteTest` 覆盖“发帖 → 发评论 → 编辑 → 断言新内容返回且旧内容不残留”。

**验证**：`:server:test` 79 个测试全绿；`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.38 2026-08-13 无限调优：注册前置校验补全

1. **注册无 codeSent 前置/确认密码**：`LoginViewModel.submit` 对注册和找回密码补“未先获取验证码”拦截，注册 tab 新增确认密码输入框；两次密码不一致或未先发码时在本地返回明确错误，不再盲目打服务端。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.39 2026-08-13 无限调优：Post 图片元数据锁热点

1. **容量裁剪持全局锁回查 DB**：`createPost` 原先在 `imageClaimLock` 内调用 `trimImageMetaIfNeeded`，超过 10k 映射时会全表回查动态 ID，阻塞所有发帖/删帖。裁剪移到锁外执行（只影响进程内映射缓存，不改变 DB 正确性）。
2. **旧图清理持全局锁做全量引用扫描**：`deleteStaleUnreferencedImages` 原先锁内 `allReferencedImageFilenames()` 全表扫；改为先列过期候选文件，再逐文件在锁内做“引用检查 + 删除”，锁持有时间从全表扫描降为单文件检查。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.40 2026-08-13 无限调优：强制断连补离线广播

1. **强制踢线/登出不广播离线**：`disconnectUserSessions`/`ByAuthSessionIds`/`ByAccessJti` 之前只删在线会话并落库 offline，不向其他在线用户广播 `USER_STATUS`，对方会一直看到旧在线状态。三条路径统一走 `markOfflineAndBroadcastIfNoSessions`：状态锁内落库，锁外二次确认无新会话后广播离线。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.41 2026-08-13 无限调优：Bot 删除广播批量取群快照

1. **删除 bot 逐群 2 次查询广播**：`DELETE /api/bots/{botId}` 原先对每个受影响群先 `getChatById` 再 `getParticipantIds`（各一次事务）。新增 `ChatRepository.getGroupRevisionAndParticipantIds` 一次事务批量取群 `memberRevision` 与参与者；路由改用 `notifyGroupRevisionChangedWithData` 直接广播，删除 bot 的 fanout 从 2N 次查询降为 1 次。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.42 2026-08-13 无限调优：群 SenderKey 计数并发安全

1. **`markGroupSenderKeyMessageSent` 读改写未加锁**：并发群消息发送时多次读改写 metadata 可能丢计数，导致 `GROUP_SENDER_KEY_MAX_MESSAGES` 轮换偏晚。改为在 `cryptoLock` 内完成读改写，与其它 Signal 状态操作同一把锁。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.43 2026-08-13 无限调优：Backlog 断线窗口递增本地未读

1. **Backlog 同步不递增未读**：断线/Doze 期间后台同步插入新消息只刷新列表预览，本地角标不涨，直到下次服务端会话快照才纠正。现在对“非活跃会话 + 非本人 + 非控制消息 + 本地确为新消息”的行增量未读；服务端会话快照仍会覆盖校准，避免跨设备残留。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.44 2026-08-13 无限调优：好友列表排除拉黑/注销用户

1. **`listFriends` 不排除拉黑且 LIMIT 后过滤注销**：被拉黑联系人刷新后会“复活”回通讯录；已注销用户还会占掉分页容量。改为 SQL 层同时过滤 `deletedAt IS NOT NULL` 与双向拉黑后再生效 LIMIT，和联系人列表“拉黑即移除”的本地行为一致。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.45 2026-08-13 无限调优：贴纸清单解析上限

1. **清单无条目上限**：`OnDemandStickerStore.parseManifest` 对包数/每包贴纸数无上限，`fetchManifestRaw` 也不限制响应体大小。新增 1MB 清单体上限、最多 64 个包、每包 300 张贴纸；超限直接拒用该清单，回退内置基础表情。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.46 2026-08-13 无限调优：AI 跨设备 FutureEpoch 不再 ACK

1. **FutureEpoch 被当已处理 ACK 导致数据永久丢失**：AI 消息 meta/摘要同步对 `DecryptResult.FutureEpoch` 直接 ACK，服务端停止重投；本地收到更新的 SenderKey 分发后这条信封已不存在。改为与 `Failed/NoSession` 一致不 ACK，靠服务端 30 天保留期等 SK 追赶后重试成功。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.47 2026-08-13 无限调优：用户分页固定排序

1. **`/api/users` offset 分页无确定排序**：`getAll` 直接 `LIMIT/OFFSET`，PostgreSQL 下同一页内容可能随执行计划变化，翻页出现重叠/漏行。固定按 `Users.id ASC` 排序后分页，与群加人候选全量拉取共用同一稳定顺序。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.48 2026-08-13 无限调优：TURN 刷新应用到活跃 PeerConnection

1. **刷新 ICE 只改字段**：`refreshIceServers` 此前只更新 `configuredIceServers`，已建直连/群 PeerConnection 仍用旧 TURN 凭据，1h 到期后网络切换/ICE 重启仍失败。现在刷新时同时对新旧连接调用 `setConfiguration`（保持 UNIFIED_PLAN / GATHER_CONTINUALLY），后续 `restartIce` 会使用新凭据。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.49 2026-08-13 无限调优：朋友圈可见性过滤批次上限

1. **feed 可见性过滤批次上限过低**：`getFeed` 每批 SQL `LIMIT` 后再过滤拉黑/私密动态，最多 5 批就提前返回；大量不可见动态时可见帖子会被跳过。迭代上限提到 20，并把批次放大系数从 3 提到 5，与评论分页的 5→20 修复对齐。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.50 2026-08-13 无限调优：搜索索引重建去 OOM 风险

1. **重建索引全量载入消息 ID**：`refreshIndex` 先用 `getSearchableMessageIds().toSet()` 载入全部可搜索消息 ID 再做孤儿判定，大库重建时内存峰值高。改为 `MessageSearchDao.deleteDocumentsNotInSearchableTypes` 用 SQL `NOT IN (SELECT ...)` 批量清理不存在/不可搜索消息的索引文档，token 由外键级联删除。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.51 2026-08-13 无限调优：搜索索引指纹分批查询

1. **指纹表仍全量载入**：`refreshIndex` 在清理孤儿后仍调用 `getFingerprints()` 一次性载入全部 `(messageId, contentHash)`。新增 `getFingerprintsForIds`，随 500 条消息批次只查询本批指纹，重建索引的内存峰值不再随文档总数线性增长。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.52 2026-08-13 无限调优：点赞者列表可见性分页

1. **likers LIMIT 后过滤拉黑**：`listPostLikers` 先 `LIMIT boundedLimit*2` 再过滤双向拉黑，前几行若多为拉黑用户会少返回。改为按 `(createdAt, userId)` 游标分批拉取，最多 20 批，直到凑够可见用户或到表尾。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.53 2026-08-13 无限调优：好友申请列表排除拉黑

1. **待处理申请不排除拉黑关系**：`listIncoming/listOutgoing` 会返回与当前用户已互相拉黑者的申请，UI 显示后才在 accept 时报 BLOCKED。现在列表层按双向拉黑过滤申请方/接收方，与好友列表和接受语义一致。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.54 2026-08-13 无限调优：群 SenderKey messageCount 去重

1. **WS 与 REST 双计同一群消息**：群消息通过 WS 投递与 REST ack 两条路径都会调 `markGroupSenderKeyMessageSent`，同一条消息被计两次，1000 条轮换阈值提前触达。现在按 `groupId + epoch + messageId` 去重，传入 messageId 的路径只计一次；去重集合有界（2000 条）。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.55 2026-08-13 无限调优：朋友圈可见性下沉 SQL

1. **feed 可见性仍在内存过滤**：上一轮只提高了批次上限，`getFeed` 每批仍把拉黑/私密动态拉进内存后再过滤。现在在 SQL 层直接排除已注销作者、双向拉黑和不可见 visibility，内存过滤仅作纵深防御，批次扫描显著减少。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.56 2026-08-13 无限调优：FutureEpoch 同步死锁

1. **FutureEpoch 卡住 backlog 游标**：FutureEpoch 占位被当作普通解密失败阻塞游标，排在后面的 SKDM 永远处理不到，形成死锁。现在 FutureEpoch 分支保留原始密文并推进游标；SKDM 安装后重开/重载即可重新解密，且不落占位文本。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.57 2026-08-13 无限调优：群通话记录

1. **通话历史跳过群通话**：`writeCallLog` 对 `isGroupCall` 直接 return，群呼出/呼入/未接都不进历史。`CallLogEntry` 新增 `isGroup`，群通话按会话/发起者入历史；历史页对群记录不回拨。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.58 2026-08-13 无限调优：PIN 解锁改异步

1. **PIN 解锁主线程 runBlocking**：ChatDetail/Starred/AiTasks/MediaCenter 四处的 `unlockChatWithPin/unlockWithPin` 都在 UI 线程同步 `runBlocking(IO)` 等 Room 校验。`ChatLockGate` 改为 `(pin, onResult) -> Unit` 异步回调，四处 ViewModel 改为协程 `withContext(IO)` 后回调结果，消除 ANR 风险。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.59 2026-08-13 无限调优：媒体缓存周期维护

1. **`MediaCache.cleanup` 无调用方**：媒体/附件缓存只会在登出或本地存储重建时全清，长期运行会只涨不清。新增 6 小时应用内维护循环调用 `MediaCache.cleanup`，按年龄与总字节上限清理，并保留过期消息清理互不冲突。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.60 2026-08-13 无限调优：Admin 吊销前缀下限对齐

1. **路由允许 8 位前缀但仓库要求 12 位**：`POST /api/admin/users/{id}/sessions/revoke` 的 8-11 位 hash 前缀会通过校验后静默返回 `count=0`。路由校验改为 12-64 位，与 `revokeByHashPrefixWithSessions` 的碰撞保护下限一致。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.61 2026-08-13 无限调优：动态删除实时广播

1. **作者/版主删除动态不广播**：此前客户端只能靠 404/刷新收敛残留动态。服务端新增 WS `POST_DELETED`，用户自删、admin 删帖、moderator 处置举报删帖三条路径统一广播；客户端 `WebSocketEvent.PostDeleted` 即时从 feed/detail/comments 移除并提示“该动态已被删除”。

**验证**：`:server:test`、`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.62 2026-08-13 无限调优：传输目录清理保护在途行

1. **48h 清理不感知在途行**：`MediaCache.cleanupTransferDirectory` 只按文件时间删除，可能误删仍在上传/等待的密文与源文件。上传/源文件目录删除前先通过 `runBlocking(IO)` 取全账号 `attachment_transfers` 行引用白名单，只清理真正孤儿。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.63 2026-08-13 无限调优：附件上传 429 可重试

1. **429 被当永久失败**：`AttachmentTransferWorker.isRetryableTransferError` 只认网络/超时/5xx，服务端限流 429 会直接标 FAILED。现在 `retryAfterSeconds != null`、HTTP 429 都走退避重试，与其他限流路径一致。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.64 2026-08-13 无限调优：好友申请隐藏已注销用户

1. **已注销用户仍出现在申请列表**：`FriendRepository.mapRequestList/mapRequest` 的 Users 回查不过滤 `deletedAt`，删除账号的申请/接收方仍显示。映射查询统一加 `deletedAt IS NULL`，列表和单条路径一致。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.65 2026-08-13 无限调优：POST_DELETED 账号隔离

1. **Explore 实时删除事件未校验当前账号**：`POST_DELETED` collector 直接处理事件，切号后旧账号删除广播可能影响新账号页面。加 `BackgroundSessionGate` 当前账号校验，切号/登出期间丢弃旧事件。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.66 2026-08-13 无限调优：附件下载锁清理

1. **`attachmentDownloadMutexes` 只增不减**：每个查看过的附件消息都永久保留一个 Mutex，长期浏览会无界增长。改为带引用计数的 `AttachmentDownloadLock`，无使用者时移除，与 Signal/贴纸锁同一模式。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.67 2026-08-13 无限调优：反应锁清理

1. **`reactionMutexes` 只增不减**：每个点过赞/反应的 messageId 都永久保留一个 Mutex。改为带引用计数的 `ReactionLock`，反应协程结束时移除。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.68 2026-08-13 无限调优：批量取消 SENDING 计数

1. **cancelAll 跳过 SENDING 计数**：批量取消把 SENDING（finalize 在途）排除在计数外，即使任务已交收尾，UI 仍可能显示取消 0 个。现在 SENDING 计为已处理，不再中断 finalize，`complete()` 仍幂等收尾。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.69 2026-08-13 无限调优：缓存写入路径不带 DB 白名单

1. **`cleanup` 的 DB 在途白名单会拖慢缓存写入**：媒体写入/恢复路径也调 `cleanup`，每次都可能 `runBlocking(IO)` 查全账号附件行。现在 `cleanup(protectInFlight=false)` 用于写入路径（只按年龄/字节清理），周期维护仍默认带在途白名单。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.70 2026-08-13 无限调优：写入路径不再清理传输目录

1. **`cleanup(protectInFlight=false)` 仍会误删在途上传**：9.69 让写入路径跳过 DB 白名单，但同一函数仍会对 `attachment-uploads/sources` 做 48h 年龄清理；若存在暂停/慢速传输超过 48h，用户发一条新媒体就可能把在途源文件删掉。现在媒体写入/恢复路径只调 `cleanupMediaCache`（媒体年龄/字节上限），传输目录只由周期维护清理，周期维护仍带在途白名单。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.71 2026-08-13 无限调优：TOTP 请求恢复协程取消语义

1. **`executeForText` 与 TOTP 三个接口吞掉 CancellationException**：`executeForText` 用 `runCatching` 包住可挂起的请求，取消被当成 `Result.failure` 返回；`totpStatus/regenerateTotpCodes/confirmTotp` 外层又包一层 `runCatching`，协程取消无法传播，页面退出/切号时请求不会及时中止。现在两处都改为 `try/catch` 显式重抛 `CancellationException`，普通网络/解析失败仍走 `Result.failure`。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.72 2026-08-13 无限调优：密聊明文不进全局搜索

1. **多条入口会把密聊明文写入全局搜索索引**：`ChatListViewModel` 的列表解密、`TextOutboxFlusher` 后台补发、AI 结果落库都会直接调 `MessageSearchRepository.indexMessage`，只有聊天页入口做了 `secretChatRepo.isSecret` 拦截；全量重建也未排除密聊会话。现在密聊排除下沉到搜索仓库与 DAO：`indexMessage` 一律删除并拒绝写入密聊会话文档，`search/searchByTypes` SQL 层排除 `secret_chats`，全量重建与新鲜度计数也不再把密聊算作可搜索消息。

**验证**：`:app:testDebugUnitTest`（新增 3 个 MockK 用例）、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.73 2026-08-13 无限调优：后台 AI 读取排除密聊

1. **AI 本地统计仍会扫描密聊明文**：`getSearchableMessages/getSearchableMessagesForChat` 被会话画像、周报、情绪回复、消息分类、归档建议共用，没有排除 `secret_chats`；即使聊天页按钮有门禁，归档建议这类全局后台任务仍会逐条读取密聊正文做关键词统计。两条 DAO 查询统一加 `chatId NOT IN (SELECT chatId FROM secret_chats)`，密聊不参与任何 AI 本地聚合。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.74 2026-08-13 无限调优：offset 分页固定二级排序

1. **多张列表只按 createdAt DESC + OFFSET 分页**：同一毫秒内插入多条记录时，翻页会跳过或重复行。群审计、举报、公告、用户标签、Bot 日志/列表统一补主键（复合键取全列）作为稳定 tie-break，保证每行恰好出现一次。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.75 2026-08-13 无限调优：注销清理按用户残留数据

1. **账号注销遗漏按 userId 存储的行**：公告已读确认、用户标签、设备事件序列/异常日志、群签到、群接龙条目、群 PK 投票在注销后仍保留；群签到排行和接龙列表会继续展示已注销用户的参与记录。注销事务统一删除这些行，并新增 H2 集成测试验证只删目标账号、不影响其他用户。

**验证**：`:server:test` 全量通过（含新增 `AccountDeactivationCleanupTest`）；`git diff --check` 无输出。

### 9.76 2026-08-13 无限调优：注销清理评论点赞

1. **`CommentLikes` 未随注销删除**：用户给他人动态评论点的赞在注销后仍计入评论点赞数，点赞者列表会显示已注销用户。注销事务补删 `CommentLikes.userId = 当前用户`，回归测试同时验证其他用户点赞保留。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.77 2026-08-13 无限调优：WebSocket 重连恢复取消传播

1. **重连刷新 token 的 `runCatching` 吞掉取消**：`refreshTokenThenReconnect` 与 `scheduleReconnectAt` 在 `scope.launch` 里用 `runCatching` 包可挂起刷新，WebSocket scope 取消后协程仍继续执行调度分支。两处改为 `try/catch` 显式重抛 `CancellationException`，普通刷新失败仍按空结果处理。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.78 2026-08-13 无限调优：动态图片占用下沉 DB 唯一表

1. **动态图片“只能被一条动态使用”只靠进程内缓存**：`PostRepository.createPost` 只查 `imageFilenameToPostId`，服务重启/缓存淘汰后同一张图片可被两条动态引用，删一条会把另一条动态的图片一并清掉。新增 `post_image_claims` 唯一占用表，发帖事务内先查再写占用，删除动态/注销时同步清理；`findPostIdByImageFilename` 优先走占用表，旧行保留 Posts LIKE 兜底。新增测试用全新 `PostRepository` 实例模拟缓存冷启动，确认 DB 仍拒绝重复引用。

**验证**：`:server:test` 全量通过（含新增 `PostImageClaimTest`）；`git diff --check` 无输出。

### 9.79 2026-08-13 无限调优：撤回消息清除反应元数据

1. **撤回不清除旧 reaction**：`revokeMessage` 会删附件、置顶和已读回执，但保留 `MessageReactions`，其他成员仍能看到“撤回的消息曾被谁回应”的元数据。撤回事务补删该消息全部 reaction，新增 H2 集成测试验证撤回后 reaction 表为空且消息 type 为 `REVOKED`。

**验证**：`:server:test` 全量通过（含新增 `MessageRevokeReactionCleanupTest`）；`git diff --check` 无输出。

### 9.80 2026-08-13 无限调优：动态图片占用表补 postId 索引

1. **注销/删帖按 postId 清理占用行没有索引**：`post_image_claims` 只有 filename 主键，按 `postId` 删除会全表扫。补 `idx_post_image_claims_post`，注销/删帖路径不再随占用表增长退化。

**验证**：`:server:test`（PostImageClaimTest）通过；`git diff --check` 无输出。

### 9.81 2026-08-13 无限调优：补齐剩余 offset 分页稳定排序

1. **admin/开发端内联分页与若干仓库列表仍只按 createdAt 排序**：同毫秒多条记录时翻页/导出会跳行或重复。补全 AdminRouting、DeveloperRouting 及 RefreshTokens、AI 同步队列、群接龙/PK/投票、风控事件、好友申请、AI 审计等列表的 id/复合键 tie-break，服务端分页列表已全部具备稳定二级排序。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.82 2026-08-13 无限调优：非 createdAt 的 offset 分页补 tie-break

1. **用户/群聊/推送 token/消息/设备日志按 lastSeen、memberRevision、updatedAt、timestamp 排序仍不稳定**：这些列表同样使用 OFFSET，同值多行会跳/重。统一补主键/复合键二级排序，服务端所有 offset 分页均有稳定顺序。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.83 2026-08-13 无限调优：附近的人游标分批补齐可见结果

1. **附近的人只拉 `limit*8` 候选再过滤**：拉黑/已注销/停权用户占满前几批时，过滤后可能不足 limit，少返回附近用户。改为按 `userId` 游标分批拉取（最多 20 批），直到凑满可见结果或到表尾，并新增测试验证首行被拉黑用户占用时仍返回后续可见用户。

**验证**：`:server:test` 全量通过（含新增 `NearbyVisibilityBatchTest`）；`git diff --check` 无输出。

### 9.84 2026-08-13 无限调优：密聊开关同步清理搜索索引

1. **关闭密聊后旧索引重新可见**：`MessageSearchDao.search` 按当前 `secret_chats` 过滤，关闭密聊会移除过滤条件，历史写入的索引文档可能重新出现在全局搜索；开启密聊时已有索引也不会立即清。`setSecretChatEnabled` 的启用/禁用分支都补 `deleteChatIndex`，保证密聊历史不会在开关切换时回流搜索。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.85 2026-08-13 无限调优：好友列表返回上限对齐好友上限

1. **好友最多 2000 但列表只返回 200**：`listFriends` 的 limit 上限是 200，超过 200 好友的用户永远看不到完整好友列表，且没有 offset 可翻。默认值和上限改为 `MAX_FRIENDS_PER_USER`（2000），一次拉全所有好友。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.86 2026-08-13 无限调优：旧动态图片复用兜底

1. **升级前发布的动态没有图片占用行**：`post_image_claims` 只覆盖新发帖，存量旧动态的图片仍可能被重复引用（删一条会破坏另一条）。`createPost` 校验改为“占用表 + 旧 `Posts.imageUrls` LIKE 精确匹配”兜底，新增测试验证无占用行的旧动态同样阻止图片复用。

**验证**：`:server:test` 全量通过（含新增 `PostImageLegacyClaimTest`）；`git diff --check` 无输出。

### 9.87 2026-08-13 无限调优：AI 同步队列稳定顺序

1. **pending 队列只按 createdAt ASC**：同毫秒写入多条 AI 同步信封时拉取顺序不稳定。补 `id` 二级排序，与其它分页/队列修复保持一致。

**验证**：`:server:test` 全量通过；`git diff --check` 无输出。

### 9.88 2026-08-13 无限调优：批量已读恢复取消传播

1. **`ChatListViewModel` 批量已读用 `runCatching` 包 suspend 请求**：会话列表关闭/切号时取消被吞掉，协程继续执行。两处 `batchMarkRead` 改为 `try/catch` 显式重抛 `CancellationException`，普通失败仍静默留给下次同步收敛。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。

### 9.89 2026-08-13 无限调优：AI 限流会话表有界化

1. **`perChatLastCall` 只增不减**：每个 chatId × 分类的最近调用时间在登出前永久保留，长期使用无界增长。超过 2048 个键时按全局窗口淘汰过期条目，限流语义不变。

**验证**：`:app:testDebugUnitTest`、`:app:lintDebug` 通过；`git diff --check` 无输出。
