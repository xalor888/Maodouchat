# Maodouchat Bot Developer API

**更新时间**：2026-07-20  
**角色**：机器人接入契约（与 `Routing.kt` bot 路由对齐；以代码为准）  
**鉴权**：多数接口使用 `X-Bot-Token: <token>` 或 `Authorization: Bearer <bot_token>`  
**注意**：Bot **不得**假设能读取用户 E2EE 明文正文；导出 / 审计仅元数据。Admin/Bot 不得 dump 密聊明文。人对人、群成员互发始终 E2EE（Sender Key）。用户主动发给 bot 的命令走独立明文 inbox，不是把群降成云聊。

---

## 1. 生命周期

### 创建机器人（用户 JWT）

`POST /api/bots`  
`Authorization: Bearer <user_access_token>`  
`Content-Type: application/json`

```json
{ "name": "Helper", "username": "helper_bot", "description": "optional" }
```

返回含 bot id / token（只显示一次的轮换策略以服务端实现为准）。

### 轮换 Token

按服务端实现的 rotate 路由（见 `listCapabilities` / 管理端）。轮换后旧 token 立即失效。

### Webhook

- 配置 / 查询 webhook 信息：`webhook` 相关路由  
- 签名：2026-07-20 起支持签名校验（详见服务端 webhook 中间件）  
- 也可用长轮询 `getUpdates` 风格接口（若已启用）

### 身份

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/bot/me` | 当前 bot 资料 |
| GET | `/api/bot/whoami` | 鉴权探测 |
| GET | `/api/bot/chats` | bot 所在会话 |
| GET | `/api/bot/webhookInfo` | webhook 状态 |
| GET | `/api/bot/listCapabilities` | **能力清单（与真实路由同步）** |

---

## 2. 消息与互动（核心）

常见能力（名称以 `listCapabilities` 为准）：

| 能力 | 说明 |
|------|------|
| `sendMessage` | 文本；支持系统/业务类型字段以服务端校验为准 |
| 媒体类 | 图/视频/音频/文件等（受 runtime media flags 约束） |
| `pinChatMessage` 等 | 置顶 / 管理类 |
| 反应 / 投票 / dice / checklist | 互动消息 |
| 位置 / 名片 / Markdown 卡片 | 结构化内容 |
| 静默 / 定时 | 受对应 runtime 开关约束 |

**错误约定**：禁用功能返回 `403` + 稳定 error code（如 `secret_reaction_block_disabled`）；参数错误 `400`；token 无效 `401`。

---

## 3. Runtime Flags 读取

Bot 可通过一系列 `get*Flags` 读取服务端运行时开关，用于自适应行为。统一模式：

- `GET /api/bot/getXxxFlags` → JSON 布尔字段 + 可选 `"surface": N`  
- 配套短健康检查：`GET /api/bot/<namez>` → `{ ok, botId, surface, ping }`

### 3.1 密聊隐私 surface（#60–#70）

| Surface | Flags 路由 | Health | Runtime keys | Hint 路由（SYSTEM） |
|---------|------------|--------|--------------|---------------------|
| 60 | `getSecretLeakFlags` | `leakz` | `secret_copy_block_enabled`, `secret_media_export_block_enabled` | `sendSecretCopyHint`, `sendSecretMediaExportHint` 等 |
| 61 | `getSecretVaultFlags` | `vaultz` | `secret_forward_block_enabled`, `secret_chat_export_block_enabled` | 转发/导出 hint |
| 62 | `getSealedCryptoFlags` | `sealz` | `sealed_sender_enabled`, `pqxdh_preview` | sealed/pq hint |
| 63 | `getMarkPrivacyFlags` | `markz` | `secret_auto_disappear_enabled`, `blind_watermark_enabled` | 自动消失 / 整页盲水印 hint |
| 64 | `getLinkPrivacyFlags` | `linkz` | `secret_link_preview_block_enabled`, `secret_external_link_block_enabled` | 链接 hint |
| 65 | `getNotifyPrivacyFlags` | `privz` | `secret_notif_preview_block_enabled`, `secret_list_preview_block_enabled` | 通知/列表 hint |
| 66 | `getSecretMetaFlags` | `metaz` | `secret_reaction_block_enabled`, `secret_star_block_enabled` | `sendSecretReactionHint`, `sendSecretStarHint` |
| 67 | `getSecretTypingFlags` | `typtz` | `secret_typing_block_enabled` | `sendSecretTypingHint` |
| 68 | `getSecretReadReceiptFlags` | `redz` | `secret_read_receipt_block_enabled` | `sendSecretReadReceiptHint` |
| 69 | `getSecretPresenceFlags` | `presz` | `secret_presence_block_enabled` | `sendSecretPresenceHint` |
| 70 | `getSecretLastSeenFlags` | `lastsz` | `secret_last_seen_block_enabled` | `sendSecretLastSeenHint` |

注：surface 67–70 防侧信道（typing / read-receipt / presence / last-seen）；Bot flags 与 `/api/public/status` 同源 `RuntimeConfigService`，客户端经 `maodou_runtime_flags` 同步到对应 `SecretXxxPrefs`。

**#66 示例**

`GET /api/bot/getSecretMetaFlags`

```json
{
  "ok": true,
  "botId": "...",
  "secretReactionBlockEnabled": true,
  "secretStarBlockEnabled": true,
  "reactionsEnabled": true,
  "messageStarringEnabled": true,
  "surface": 66
}
```

`POST /api/bot/sendSecretReactionHint`

```json
{ "chatId": "<chat>", "hint": "optional <=120 chars" }
```

需 bot 为会话成员；且对应 runtime 开关为启用，否则 `403`。

### 3.2 其他 flags 族（摘要）

历史 surface 已铺开大量 flags（非穷尽，以代码为准）：

| 类别 | 示例路由 / health |
|------|-------------------|
| 消息策略 | `getMessagePolicyFlags` 等 |
| 媒体发送 | `getMediaSendFlags` / 媒体 privacy |
| 通话 | `getCallMediaFlags` |
| AI | 无（聊天 AI 只在客户端；服务端仅审帖） |
| 外观 / 动效 | `getAppearanceFlags`, `getMotionFlags`, `slidez` |
| 通知 / 静音 | `getNotifyFlags`, `getQuietFlags`, `quietz` |
| 触感 / 音效 | `getFeelFlags`, `fealz` |
| 截屏防护 | `getCaptureShieldFlags`, `shieldz` |
| 通用探活 | `healthz`, `readyz`, `alivez`, `statusz`, `ping`, `uptime`, `versionz` … |

完整列表：调用 **`GET /api/bot/listCapabilities`**，并与 `Routing.kt` 对照。

---

## 4. 公开状态（客户端同步）

客户端（非 bot token）通过：

- `GET /api/public/status`（及登录后 feature 矩阵相关接口）

拉取 camelCase 布尔字段（例：`secretReactionBlockEnabled`），写入本地 `maodou_runtime_flags` SharedPreferences（`*Prefs.kt`）。

Bot 侧 flags 与 public/status **同源** `RuntimeConfigService`。

---

## 5. 安全与隐私硬性约定

1. **E2EE 正文**：服务端 / bot **不可**作为明文聊天记录源。  
2. **密聊**：尊重 secret_* block 开关；hint 仅 SYSTEM 元提示。  
3. **senderId**：聊天 WS / 历史路径 **禁止** 错误 redact（sealed 相关实现约束）。  
4. **Admin 导出**：CSV 为开关/元数据，不是消息正文。  
5. **邀请进群**：群管 `POST /api/chats/{chatId}/bots`，只能拉自己的 bot。  
6. **用户命令 inbox（明文，拒绝密文）**：
   - 用户 JWT `POST /api/chats/{chatId}/bot-inbox` `{ "text": "/help", "botId": "optional" }`
   - 斜杠菜单 `GET /api/chats/{chatId}/bot-commands`
   - 用户↔bot 私聊 `POST /api/bots/{botId}/dm`
   - 群里 `/cmd`、`/cmd@username`、`@bot text` 由客户端双发：成员气泡仍是 Sender Key，命令另送 inbox。
   - webhook `message` 事件不再携带用户 TEXT 库存（那是密文）；命令正文只出现在 `user_command` 更新。
7. **Health 名唯一**：`leakz` / `vaultz` / `sealz` / `markz` / `linkz` / `privz` / `metaz` 等不可撞名。  


---

## 6. 能力发现

推荐集成流程：

1. `GET /api/bot/metaz` 或 `healthz` 确认鉴权  
2. `GET /api/bot/listCapabilities` 缓存能力  
3. 需要时拉对应 `get*Flags`  
4. 发送前检查相关 enabled 字段，避免硬失败风暴  

---

## 7. 文档维护

| 文档 | 内容 |
|------|------|
| 本文 | Bot 契约与 flags 索引 |
| `docs/feature-inventory.md` | 产品功能完整度 |
| `RuntimeConfigService.kt` | 全部 runtime key 权威源 |
| `Routing.kt` | 路由实现权威源 |

变更 bot 路由时必须同步：`listCapabilities`、本文索引表、admin 行（若新增 runtime key）。

---

*2026-07-20 按进度重写：去掉 #1…#66 碎片章节堆叠，改为索引表 + 核心约定。*
