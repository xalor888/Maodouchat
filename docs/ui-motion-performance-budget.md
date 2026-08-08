# UI 动效与性能预算

**用途**：定义 UI 动效规格与性能预算，确保丝滑流畅体验。
**关联**：`app/.../ui/theme/Motion.kt`、`docs/release-checklist.md`。

---

## 1. 动效统一规格

所有动效通过 `MotionSettings` + `MotionTokens` 统一管理，尊重系统动画缩放设置。

### 1.1 时长令牌

| 令牌 | 毫秒 | 用途 |
|------|------|------|
| `Instant` | 0 | 关闭动画 / snap |
| `Fast` | 120 | 列表项淡出、按钮按压回弹 |
| `Standard` | 200 | 页面转场、Sheet 展开 |
| `Emphasized` | 280 | 消息入场、卡片弹入 |
| `Particle` | 520 | 粒子删除特效 |

### 1.2 Spring 规格

| 场景 | dampingRatio | stiffness |
|------|-------------|-----------|
| 列表项位移 (animateItem) | 0.85 | 380 |
| 消息滑动手势 | 0.82 | 620 |
| 搜索高亮缩放 | 0.58 | 420 |
| 按钮按压 | 默认 NoBouncy | Medium |

### 1.3 统一过渡入口

- `messageEnterTransition()` — fade + scaleIn(0.92)
- `messageExitTransition()` — fade + scaleOut(0.9)
- `pageEnterTransition()` — fade + slideInVertically
- `pageExitTransition()` — fade + scaleOut(0.98)
- `listItemPlacementSpec()` — spring 0.85/380
- `listItemFadeInSpec()` / `listItemFadeOutSpec()` — tween Fast

## 2. 性能预算

### 2.1 帧率

| 场景 | 目标 | 工具 |
|------|------|------|
| 消息列表滚动 | ≥ 55 fps | GPU 渲染分析 |
| 会话列表滚动 | ≥ 55 fps | 同上 |
| 图片浏览 | ≥ 50 fps | 同上 |
| 页面转场 | ≥ 50 fps | 同上 |

### 2.2 启动

| 指标 | 预算 | 说明 |
|------|------|------|
| 冷启动到首帧 | < 1.2s | SQLCipher + Signal 延后到 IO |
| 冷启动到可交互 | < 1.8s | `reportFullyDrawn()` 标记 |
| 热启动 | < 400ms | |

### 2.3 内存

| 项 | 预算 | 说明 |
|----|------|------|
| Coil 内存缓存 | 堆 20%（低内存 15%） | `MemoryCache.Builder.maxSizePercent` |
| Coil 磁盘缓存 | 100 MB | `image_cache` 目录 |
| 单张图片解码 | ≤ 2048x2048 | ZoomableAsyncImage 限制 |
| 消息列表常驻 | < 80 MB | LazyColumn + key 复用 |

### 2.4 包体

参见 `docs/size-baseline.md`。Release 仅 arm64-v8a + R8 + 资源收缩。

## 3. LazyColumn 规范

- **必须提供 `key`**：用消息 ID / 聊天 ID，不用 index
- **必须提供 `contentType`**：区分文本/图片/视频/系统消息，促进 item 复用
- **使用 `animateItem`**：用 `motion.listItemPlacementSpec()` 统一位移动画
- **避免 lambda 分配**：事件回调提取到 remember 外
- **预取**：LazyColumn 默认 `prefetchDistance = 1.5 * viewport`

## 4. 禁止事项

- 禁止在 `items` lambda 内创建新 `Modifier.clickable` 链（用 `combinedClickable`）
- 禁止在列表项内做同步 DB 查询（用 `LaunchedEffect` + `Dispatchers.IO`）
- 禁止硬编码动画时长（用 `motion.duration(MotionTokens.*)`）
- 禁止在关闭动画时仍运行粒子（`rememberMotionPulse` 会自动 collapse）

---

## 5. B7 性能预算增量（v2026.08）

新增 `app/perf/`：`StartupTracer.kt`、`ListScroller.kt`、`ImageMemoryPolicy.kt`。
本节的常量与函数即本节预算的落地来源，改动预算时须同步修改代码常量，避免文档与实现分叉。

### 5.1 热点清单（Hotspots）

| 热点 | 现象 | 处置 |
|------|------|------|
| H1 冷启动到首帧 | SQLCipher 解密 + DB 打开 + Signal 初始化占主线程 | 里程碑埋点 `StartupTracer`（begin → firstFrame → fullyDrawn），首帧 < 1.2s |
| H2 冷启动到可交互 | 首帧后导航/数据加载仍阻塞 | `reportFullyDrawn()` 时调 `StartupTracer.fullyDrawn()`，目标 < 1.8s |
| H3 会话列表滚动 | 置顶/回底连点导致滚动动画排队、掉帧 | `CoalescedScroller` 合并同帧请求 + 超距 snap；目标 ≥ 55fps |
| H4 滚动掉帧回归 | 长列表滑动手感劣化不易察觉 | `ListFrameMeter` 按窗口统计掉帧（>16ms/帧即记），< 55fps 输出 warning |
| H5 会话列表排序查询 | `archived=0 ORDER BY pinnedAt, lastMessageTime` 回表排序 | 新增 `chats(archived, pinnedAt, lastMessageTime)` 复合索引 |
| H6 会话内消息加载 | `chatId=? ORDER BY timestamp` 命中复合索引中间列 | 新增 `messages(chatId, timestamp)` 精确索引 |
| H7 本地发件箱轮询 | `status='SENDING' AND senderId=?` 低区分度过滤 | 新增 `messages(status, senderId, timestamp)` |
| H8 过期消息清扫 | `expiresAt <= now` 全表扫 | 新增 `messages(expiresAt)` |
| H9 媒体/搜索批量拉取 | `type IN (...) ORDER BY timestamp DESC LIMIT` | 新增 `messages(type, timestamp)` |
| H10 图片内存 | 高分辨率原图解码进堆、缓存超限 | `ImageMemoryPolicy`：解码 ≤ 2048 边长、缓存上限 堆 20%（低内存 15%）、磁盘 100MB |

### 5.2 索引变更表（v27 → v28，仅追加 CREATE INDEX，不重写表/列）

| 索引名 | 表 | 列 | 支撑查询（DAO） |
|--------|----|----|-----------------|
| `index_chats_archived_pinnedAt_lastMessageTime` | chats | archived, pinnedAt, lastMessageTime | `ChatDao.getActiveChats` |
| `index_messages_chatId_timestamp` | messages | chatId, timestamp | `MessageDao.getMessagesByChatId` / `getRecentMessages` / `getFirstMessageAtOrAfter` / `getEarliestMessageTimestamp` |
| `index_messages_status_senderId_timestamp` | messages | status, senderId, timestamp | `MessageDao.getSendingOutbox` / `getSendingOutboxForChat` |
| `index_messages_expiresAt` | messages | expiresAt | `MessageDao.getExpiredMessageIds` |
| `index_messages_type_timestamp` | messages | type, timestamp | `MessageDao.getImageMessages` / `getSearchableMessages*` |

> 与 `MessageEntity` / `ChatEntity` 的 `@Index` 声明一一对应（新库与迁移库 schema 等价）；
> 既有索引（`index_messages_chatId`、`index_chats_archived` 等）原样保留，未删除。
> 全部基于既有字段，未引入新列。索引由 SQLite 建在 b-tree 上，命中后以整行回表返回实体。

### 5.3 预算表（B7 生效值）

| 指标 | 预算 | 来源常量 |
|------|------|----------|
| 冷启动到首帧 | < 1200ms | `StartupTracer.BUDGET_FIRST_FRAME_MS` |
| 冷启动到可交互 | < 1800ms | `StartupTracer.BUDGET_INTERACTIVE_MS` |
| 热启动 | < 400ms | 沿用 §2.2 |
| 列表滚动帧率 | ≥ 55fps（单帧 ≤ 16ms） | `ListScroller.FRAME_BUDGET_MS` |
| 滚动跳转动画上限 | ≤ 800px（超出 snap） | `ListScroller.ANIMATED_SCROLL_MAX_DISTANCE` |
| 滚动请求合并窗口 | 16ms（同帧去重） | `ListScroller.COALESCE_WINDOW_MS` |
| 单图解码边长上限 | ≤ 2048px（power-of-2 降采样） | `ImageMemoryPolicy.MAX_DECODE_DIMENSION` |
| 低内存设备阈值 | 应用可用堆 ≤ 192MB | `ImageMemoryPolicy.LOW_MEMORY_HEAP_THRESHOLD_BYTES` |
| Coil 内存缓存占堆 | 20%（低内存 15%） | `ImageMemoryPolicy.MEMORY_CACHE_PERCENT_NORMAL / _LOW` |
| Coil 磁盘缓存 | 100MB | `ImageMemoryPolicy.DISK_CACHE_BYTES` |
