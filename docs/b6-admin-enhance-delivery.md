# B6 服务端运维增强 — 交付说明

日期：2026-08-01
范围：系统公告广播 / 用户标签+风控联动 / 审计时间范围导出 / 限流仪表盘 / 设备事件一致性加固

## 0. 红线合规

- ✅ 未改动 `AdminRouting.kt` 既有路由（仅其内部既有代码保持原样，新端点全部在 `AdminEnhanceRouting.kt`）。
- ✅ 未改动 `RateLimit.kt` / `Routing.kt`（新增采样器只**读取** `GlobalRateLimiter.stats()` 公开快照）。
- ✅ 所有 `/api/admin/*` 端点双重门控：`authenticate("admin-jwt")`（5 分钟高权限会话）+ `isAdminUser()`（MASTER_ADMINS 白名单）。
- ✅ 不导出 E2EE 明文：本模块只读写公告（平台明文广播，非会话正文）、用户标签元数据、审计/限流/设备一致性元数据，不触碰 `Messages` / `EncryptedAttachments` 密文列。
- ✅ 所有变更操作（建公告/发布/取消/删除、标签 CRUD、打标/移除、手动采样、导出）写 `ModerationAuditLog` 审计。

## 1. 新增文件

| 文件 | 职责 |
|---|---|
| `server/db/AdminTables.kt` | 8 张新表（见 §3） |
| `server/repository/AnnouncementRepository.kt` | 公告 CRUD / 发布 / 取消 / 按用户拉取 / 统计 |
| `server/repository/UserTagRepository.kt` | 标签 CRUD / 打标移除 / 用户-标签查询 / 风险赋值 |
| `server/repository/RateLimitStatsRepository.kt` | 限流分钟桶采样写入 / 聚合 / 清理 |
| `server/plugins/AdminEnhanceRouting.kt` | 全部新端点 + `DeviceEventConsistencyGuard` + `startRateLimitStatsSampler` |
| `app/notification/AnnouncementPolicy.kt` | 客户端公告展示/通知策略（纯函数） |

## 2. 修改文件（仅末尾/新增，不触碰既有逻辑）

| 文件 | 改动 |
|---|---|
| `server/db/Database.kt` | `initDatabase()` 表清单追加 8 表（用户标签先于公告建表以保 FK）；`ensureIndexes()` 追加 8 条索引 |
| `server/Application.kt` | 仓库实例化 + `configureAdminEnhanceRouting(...)` + `startRateLimitStatsSampler(...)`（均在既有注册之后） |
| `server/resources/admin/admin.html` | 侧边栏末尾追加「B6 运维增强」导航组（4 个 tab） |
| `server/resources/admin/admin.css` | 末尾追加 `.panel-subtitle` / `.rl-chart` / `.rl-bar` 等样式 |
| `server/resources/admin/admin.js` | IIFE 末尾追加受控访问器 `window.__b6Admin`；文件末尾追加自包含 B6 模块 |
| `app/res/values/strings.xml` / `values-en/strings.xml` | 成对追加 7 条公告文案（zh/en） |

## 3. 表结构（admin_tables）

### system_announcements — 系统公告
| 列 | 类型 | 说明 |
|---|---|---|
| id | varchar(100) PK | UUID |
| title | varchar(200) | 标题 |
| content | text | 公告明文（平台广播，非 E2EE） |
| level | varchar(20) | INFO/WARNING/MAINTENANCE/EMERGENCY |
| target_audience | varchar(20) | ALL / TAGGED |
| target_tag_id | varchar(80) FK→user_tags.id | 定向标签（TAGGED 时必填） |
| starts_at / expires_at | long | 生效窗口 |
| status | varchar(20) | DRAFT/SCHEDULED/ACTIVE/EXPIRED/CANCELLED |
| created_by / created_at / updated_at | | 审计 |
| published_at / cancelled_at / cancelled_by | | 发布/取消痕迹 |
索引：`(status, starts_at, expires_at)`、`(created_at)`

### announcement_acks — 公告已读
`PK(announcement_id, user_id)`，acked_at。

### user_tags — 用户标签（元数据，无明文）
| 列 | 说明 |
|---|---|
| id / name(unique) / color / description | 基础字段 |
| is_system | 系统内置标签不可删 |
| risk_level | NONE/LOW/MEDIUM/HIGH/CRITICAL（风控联动级别） |
| created_by / created_at / updated_at | 审计 |

### user_tag_assignments — 用户-标签多对多
`PK(tag_id, user_id)`，source=MANUAL/AUTO，assigned_by，created_at；索引 `(user_id)`。

### audit_export_records — 审计时间范围导出登记
actor_id / scope(ADMIN_AUDIT|RISK_EVENTS|ANNOUNCEMENTS|USER_TAGS|RATE_LIMIT) / from_ms / to_ms / row_count / file_ref / requested_at；索引 `(actor_id, requested_at)`。

### rate_limit_stats_snapshots — 限流仪表盘分钟桶
`bucket_start_ms`(unique, 分钟对齐) / allowed / rejected / total_buckets / max_buckets / max_per_minute / sampled_at。保留 31 天（`RATE_LIMIT_STATS_RETENTION_DAYS`）。

### device_event_sequences — 设备事件序列（幂等应用点）
`PK(user_id, device_id, event_type)`，last_applied_seq / last_event_at。

### device_event_consistency_log — 一致性异常日志
id / user_id / device_id / event_type / seq / status(STALE|DUPLICATE|OUT_OF_ORDER) / reference_id / first_seen_at / last_seen_at / detail；索引 `(user_id, last_seen_at)`、`(status, last_seen_at)`。

## 4. 端点表

### 用户端（auth-jwt，普通登录态）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/announcements/active` | 拉取当前可见公告（ALL + 命中所带标签；返回 acked 标记 + serverTime） |
| POST | `/api/announcements/{id}/ack` | 标记已读（幂等 upsert） |

### 管理端（admin-jwt + isAdminUser 双重门控）
| 方法 | 路径 | 说明 | 审计动作 |
|---|---|---|---|
| GET | `/api/admin/announcements` | 列表（status/q 过滤、分页） | — |
| POST | `/api/admin/announcements` | 新建（校验级别/受众/窗口；TAGGED 必填 tagId） | ANNOUNCEMENT_CREATED |
| GET | `/api/admin/announcements/{id}` | 详情 | — |
| PUT | `/api/admin/announcements/{id}` | 更新（CANCELLED 不可改） | ANNOUNCEMENT_UPDATED |
| POST | `/api/admin/announcements/{id}/publish` | 发布（→ACTIVE 立即生效） | ANNOUNCEMENT_PUBLISHED |
| POST | `/api/admin/announcements/{id}/cancel` | 取消（→CANCELLED 留痕） | ANNOUNCEMENT_CANCELLED |
| DELETE | `/api/admin/announcements/{id}` | 仅删 DRAFT 草稿 | ANNOUNCEMENT_DELETED |
| GET | `/api/admin/announcements/{id}/stats` | 受众规模 + 已读数 | — |
| GET | `/api/admin/user-tags` | 标签列表（含 userCount） | — |
| POST | `/api/admin/user-tags` | 建标签（去重/级别校验） | USER_TAG_CREATED |
| PUT | `/api/admin/user-tags/{id}` | 改标签（级别/颜色/描述） | USER_TAG_UPDATED |
| DELETE | `/api/admin/user-tags/{id}` | 删标签（系统内置禁止） | USER_TAG_DELETED |
| GET | `/api/admin/user-tags/{id}/users` | 标签下用户（q 过滤、分页） | — |
| GET | `/api/admin/tags/risk-summary` | HIGH/CRITICAL 风险标签聚合 | — |
| GET | `/api/admin/users/{userId}/tags` | 用户当前标签 | — |
| POST | `/api/admin/users/{userId}/tags` | 打标；HIGH/CRITICAL 写入 RiskEvents（needs_review）联动人工复核 | USER_TAGS_ASSIGNED |
| DELETE | `/api/admin/users/{userId}/tags/{tagId}` | 移除标签 | USER_TAG_DETACHED |
| GET | `/api/admin/audit/time-range-export` | 时间范围导出 CSV（scope+fromMs+toMs+limit；≤90 天） | ADMIN_AUDIT_TIME_EXPORT |
| GET | `/api/admin/rate-limit/dashboard` | 限流仪表盘（range=1h/24h/7d；含实时快照） | — |
| POST | `/api/admin/rate-limit/sample` | 立即手动采样 | RATE_LIMIT_MANUAL_SAMPLE |
| GET | `/api/admin/device-consistency/summary` | 设备序列 + 异常计数 | — |
| GET | `/api/admin/device-consistency/events` | 异常列表（status/userId 过滤、分页） | — |

## 5. 接线点

- **建表**：`Database.kt:initDatabase()` → `SchemaUtils.createMissingTablesAndColumns(...)` 追加 8 表；`ensureIndexes()` 追加 8 条 `CREATE INDEX IF NOT EXISTS`。
- **路由注册**：`Application.kt` 在既有 `configureRouting` / `configurePollRouting` / `configureDeveloperRouting` / `configureAiEnhanceRouting` 之后调用 `configureAdminEnhanceRouting(announcementRepo, userTagRepo, rateLimitStatsRepo)`。
- **限流采样器**：`Application.kt` 在 `embeddedServer(...)` 之前调用 `startRateLimitStatsSampler(rateLimitStatsRepo)`（守护线程，60s 周期；内部 `recordMinute()` 用 `GlobalRateLimiter.getInstance().stats()`，**只读不改** RateLimit.kt）。
- **设备一致性加固**：`DeviceEventConsistencyGuard.applyEvent(userId, deviceId, eventType, seq, referenceId)` 是幂等写入 API，供现有 WS/同步处理链路在「应用事件前」调用；返回 APPLIED/STALE/DUPLICATE/OUT_OF_ORDER，异常自动写入 `device_event_consistency_log`。
- **客户端公告**：`AnnouncementPolicy`（纯函数：过滤/排序/通知判定）配合 `/api/announcements/active` + `ack` 使用；文案走 `strings.xml`（zh/en 成对）。
- **管理后台**：admin.html 侧边栏追加导航；admin.js B6 模块自包含，通过 `window.__b6Admin.{api,toast,esc,date,el}` 访问主闭包（admin token 仍只存内存）。

## 6. 行为说明 / 注意

- 限流仪表盘差值语义：`GlobalRateLimiter.stats()` 为进程启动以来累计值，仪表盘对相邻分钟桶做差值即分钟增量；进程重启后首桶差值为 0 属正常。
- 公告状态机：`DRAFT→SCHEDULED→ACTIVE→(EXPIRED 自动|CANCELLED 手动)`；读取侧以窗口+状态过滤，不写后台任务推进 EXPIRED。
- 导出端点返回 CSV（UTF-8 BOM + `csvCell` 防公式注入 `= + - @`），并在 `audit_export_records` 登记 + 审计留痕。
- 多实例部署提示：`DeviceEventConsistencyGuard` 与 `GlobalRateLimiter` 同约束（进程内锁），横向扩容前需换 DB 行级锁实现。
