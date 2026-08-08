# Maodouchat 功能全景与完整度盘点
  
**更新时间**：2026-07-20（按代码现状重写附录；主体表继承盘点）  
**完整度标签**：

| 标签 | 含义 |
|------|------|
| **完整** | 主路径有 UI/API/持久化/关键策略，可内部演示 |
| **基本完整** | 主路径可用，有明确产品边界或体验限制 |
| **半成品** | 有骨架/门禁/接口，功能未闭环或默认关闭 |
| **未做** | 代码中不存在或明确不做 |

> **诚实边界**：本表描述「代码里有什么」，**不等于**可发布、不等于已过双机/弱网/公网验收。E2EE 多设备、附件弱网、通话真机等仍以对应 `docs/*-verification.md` 填表为准。

---

## 1. 产品定位（代码事实）

| 项 | 内容 |
|----|------|
| 名称 | Maodouchat（毛豆聊天） |
| 客户端 | Android，Jetpack Compose |
| 服务端 | Ktor + Exposed，可选 Postgres |
| 核心价值 | 即时通讯 + 群治理 + 动态 + 音视频通话 + Signal 系 E2EE + 加密附件 + 可选 AI + 管理后台 |
| 明确未做 | iOS / 桌面客户端、支付/红包、小程序、大群 SFU 会议，网页端 （暂时不考虑）|
| Bot / 官网 | **已有** Bot REST API + 官网静态页 + 开发者文档；开放平台产品化仍浅 |

---

## 2. 技术栈速览

| 层 | 技术 |
|----|------|
| UI | Jetpack Compose、Navigation、Material 主题深浅色 |
| 本地 | Room + **SQLCipher**、EncryptedSharedPreferences、WorkManager |
| 实时 | WebSocket `/ws` + REST 补偿 |
| 加密 | libsignal-client（1:1 会话 + 群 Sender Key）、附件 AES-256-GCM |
| 通话 | WebRTC（1:1 + 群 mesh）、TURN 短期凭据、通话前台服务 |
| 推送 | FCM |
| 服务端 | Ktor、JWT（用户 + 短时 admin）、BCrypt、限流、SMTP 验证码 |
| 部署 | Docker Compose（Caddy + Postgres + server）、备份/恢复脚本 |

---

## 3. 客户端功能全表

### 3.1 认证与会话

| 功能 | 完整度 | 说明 | 关键路径 |
|------|--------|------|----------|
| 邮箱密码登录 | 完整 | Tab 登录/注册/找回 | `ui/screen/login/` |
| 邮箱验证码注册 | 完整 | 发码倒计时 | 同上 |
| 忘记密码 / 重置 | 完整 | 验证码 purpose=reset + 吊销会话 | `LoginScreen` tab2 + `/api/auth/reset-password` |
| Token 持久化 | 完整 | Access/Refresh 加密存储 | `network/TokenManager.kt` |
| 自动刷新 / 登出清会话 | 完整 | 含 WS 断开、任务取消 | `security/SecureSessionManager.kt` |

### 3.2 会话列表与组织

| 功能 | 完整度 | 说明 | 关键路径 |
|------|--------|------|----------|
| 会话列表 | 完整 | 预览/草稿/未读/置顶/静音/归档 | `ui/screen/chatlist/` |
| 滑动与多选批处理 | 完整 | 左滑归档/删除/静音；多选批归档/静音/已读/删除；归档视图可切回 | `ChatListScreen` |
| 未读文件夹「全部已读」 | 完整 | 未读文件夹选中时顶栏「全部已读」：逐会话服务端 mark-read（广播 CHAT_MARKED_READ 同步多设备）+ 乐观清零落库 | `ChatListViewModel.markAllUnreadChatsRead` + ChatListScreen |
| 会话文件夹 | 完整 | 本地缓存 + `/api/chat-folders` 云同步；最多 28 个、名 48 字；创建/重命名/删除/移入；系统筛选：群聊/单聊/未读（含手动标未读）/密聊/已锁（后二者有数据时再显示）；空态引导；全部芯片显示未读会话数；未读优先条可进未读筛选；WS 失败条可手动刷新；管理/移入弹窗 ≥5 可搜文件夹名（64 字） | `ChatFolderPolicy` + ChatList 管理/移入弹窗 |
| 未读优先排序 | 完整 | 设置可关 | `util/UnreadPriorityPolicy.kt` |
| 全局消息搜索 | 完整 | 本地索引 + 跳转定位；排除 PIN 锁定/密聊正文并明示排除会话数 | `GlobalSearchScreen.kt` |
| 智能归档建议 | 完整 | 纯本地启发式（静置时长/消息密度/收尾信号打分）；会话列表顶部卡片前 3 条，采纳走现有归档流程、可单条/一键忽略；账号隔离、一次性计算 | `AiArchiveSuggestion` + `ChatListScreen` |
| 应用内通知中心 | 完整 | 消息/未接/AI/动态/好友申请；按类型/未读筛选；本地最多保留 520 条/180 天；≥5 条可按标题/预览/类型本地搜索；`maodouchat:contacts` 跳转通讯录 Tab | `NotificationCenterScreen.kt` + `MaodouchatApp.emitOpenContacts` |
| 未接来电条 | 完整 | 会话列表卡片 + 完整列表（回拨/打开会话/标已读/清空）；28 天保留；≥5 条可搜来电人 | `MissedCallRecorder` + `ChatListScreen` MissedCallsSheet |

### 3.3 聊天详情（核心）

| 功能 | 完整度 | 说明 | 关键路径 |
|------|--------|------|----------|
| 文本收发 | 完整 | 幂等 client msg id、发件箱；群 @ 提及候选上限 80 + 可滚动选择条 | `chatdetail/ChatDetailViewModel.kt` + `MentionPolicy` |
| 图/视频/文件/GIF/贴纸 | 完整 | 走加密附件管道；本机 GIF 搜索最近 48/结果 160/查询 80 字；贴纸最近 48；内置 3 包各 28 张+中英关键词（含生病/摇滚/食物/好运等）；内置表情扩展+关键词搜索；表情「最近使用」本地 36 条；远程图片贴纸包按需下载（OnDemandStickerStore + 服务端 manifest/静态文件端点，运营商在 STORAGE_DIR 放置 stickers-manifest.json 即启用） | `attachment/` + ChatDetail GIF/贴纸/表情面板 + `slim/OnDemandStickerStore` |
| 快捷短语（常用语） | 完整 | 附件菜单「快捷短语」面板：默认 8 条 + 自定义增删（上限 40 条/80 字），点按插入草稿；按账号本地隔离，登出清理 | `util/QuickPhrase*` + ChatDetail `QuickPhrasesDialog` |
| 剪贴板粘贴图片 | 完整 | 附件菜单「剪贴板图片」：系统剪贴板图片 uri 直接进入确认发送（含 view-once/spoiler）；bitmap 兜底存 FileProvider 授权目录；无图片时提示 | ChatInputBar `onPasteFromClipboard` + `PendingImageSend` |
| 引用跳转 | 完整 | 点击引用预览跳转被回复消息（meta.replyToId）；目标未加载时自动翻页加载，翻尽不存在则提示 | `ChatDetailScreen` + `jumpToMessage` |
| 语音消息 | 完整 | 录制预览发送 | VoiceRecorder/Player |
| 位置消息 | 完整 | 本机定位权限/服务校验；气泡显示精度米数 | ChatDetail + `LocationProvider` |
| 拍一拍 / 戳一戳 | 完整 | WS 发送；系统气泡本地化文案 | `sendNudge` + `NudgeDisplayPolicy` |
| 回复/编辑/撤回/删除 | 完整 | 服务端 mutation 同步；编辑/撤回窗口 5 分钟；多选可全选当前列表/清空选择后批量转发/星标/删除 | Message API + `ChatSelectionToolbar` |
| 表情反应 | 完整 | 快捷 ❤️ + 扩展选择条（34 emoji，含🥰/💪/🤝/😊/🙌/🤩/🥲/🤣/👌/🫶）；服务端 allowlist 对齐；文本/图/语音/视频/文件/贴纸/位置气泡均可展示；反应条可点切换/取消；最多 24 种 + 溢出 | ChatDetail `ReactionPickerRow` + `ReactionSummaryRow` |
| 星标 / 收藏列表 | 完整 | 会话内 + 设置「全部星标」；可搜索；可跳回消息；全局星标排除 PIN 锁定会话 | `StarredMessagesScreen.kt` |
| 消息置顶 | 完整 | 顶栏 序号/总数；点击打开并轮询下一条；上限 20（客户端/服务端对齐） | `MessagePinPolicy` + `PinnedMessagesBanner` |
| 已读回执 | 完整 | 单聊双勾/已读；群可读详情比例；≥5 人可按姓名/ID 搜索 | MessageStatus + `chat_read_details_*` |
| 转发 | 完整 | 目标按置顶/最近排序；排除归档；可搜索；默认 64 条可展开；可滚动；**可附带留言**（最长 500 字，转发后向目标会话发送一条加密文本，1:1 Signal / 群 Sender Key） | ChatDetail 转发弹窗 + `sendTextToChat` |
| 定时发送 | 完整 | 本地 WorkManager；1:1/群纯文本；快捷 1m/5m/15m/30m/1h/2h/3h/4h/6h/12h/24h/2d/3d/7d + 自定义；每会话待发上限 56；待发列表/改期/取消；≥4 条可搜 | `ScheduledMessageWorker` + ChatDetail 横幅/Sheet |
| 阅后即焚 | 完整 | 单聊；群强制关；时长 30s/1m/2m/5m/15m/1h/2h/4h/8h/12h/24h/7d/30d；会话列表计时指示；对话框明示截屏/他端备份边界 | `DisappearingMessagePolicy` + ChatList 计时标 |
| 输入草稿 | 完整 | 按账号/会话隔离；防抖约 350ms | `ChatDraftPolicy.kt` |
| 对方输入中 | 完整 | 会话门闩防串号 | `TypingSessionPolicy.kt` |
| 会话内搜索 | 完整 | 关键词/语义双模式；范围含媒体/星标；时间窗全部/今天/7天/30天；语义候选上限 100 | `ChatSearchBar` + `ChatSearchModel` |
| 链接预览 | 完整 | 可关；气泡卡片 + 设置/云同步；标题/描述截断 220/400；缓存 96 条/抓取 96KB | `LinkPreviewPolicy` + `MessageBubble` + client-prefs |
| 拉黑 / 举报 | 完整 | 会话菜单屏蔽；消息/用户举报理由 11 档预设（含暴力威胁/隐私侵犯/侵权投诉/未成年人相关）+ 选填说明最长 800 字；屏蔽文案明示双向语义（对方无法发消息/看资料/通话，好友关系解除） | ChatDetail ReportDialog + report |
| 联系人资料卡 | 完整 | 单聊「联系人操作」弹窗新增「查看资料」→ 底部资料卡：大头像、ID、状态、最后在线、发消息/语音/视频/屏蔽/举报 | `ContactProfileSheet` |
| 导出聊天 | 完整 | 敏感 step-up；空/失败/成功；PIN 未解锁拒绝 | `ChatDetailViewModel.exportToUri` + SensitiveAction |
| 清除本地消息 | 完整 | 菜单确认 + 敏感 step-up；清消息/索引/媒体/定时；保留会话与 PIN | `ChatDetailViewModel.clearLocalChatHistory` |
| 媒体中心 | 完整 | 图/GIF/视频/贴纸/文件/语音/位置/链接；预览/保存/分享/跳原消息；文件长按导出；PIN 门闩；分类内按文件名/链接/位置搜索 | `MediaCenterScreen.kt` + `ChatLockSession` |
| 会话 PIN 锁 | 完整 | 本地 PIN（单聊/群）；设置/关闭/忘记清本地；列表锁标+预览隐藏+系统「已锁」筛选；搜索/媒体/AI 任务/星标/通知脱敏；进程内解锁缓存；登出清缓存；轻量 SHA-256 | `ChatLockGate` + `ChatLockSession` + ChatDetail/MediaCenter/AiTasks/ChatList/AppNotifier |
| 密聊 | 完整 | 本机会话开关；强制 FLAG_SECURE（全局关也生效）；详情/群资料/媒体/AI 任务/星标盲水印；列表预览脱敏+指示+系统「密聊」筛选；通知脱敏；禁导出；确认开关；横幅明示本机/外置相机边界；全局/列表搜索排除；退群/删会话/登出清本地；非服务端协议 | `SecretChatEntity` + `SecretChatSession` + `ScreenSecurePolicy` + `blindWatermark` |

> 实现密度极高：`ChatDetailScreen` / `ChatDetailViewModel` 体量巨大，维护风险在复杂度，而非功能空壳。

### 3.4 群组

| 功能 | 完整度 | 说明 | 关键路径 |
|------|--------|------|----------|
| 建群 / 成员管理 | 完整 | 群详情成员可搜（含 userId）默认 100 可展开；会话内群资料弹窗可搜成员/候选且候选默认 20 可展开 | `GroupDetailScreen.kt` + ChatDetail 群资料 + Chat API |
| 角色 / 群主转让 | 完整 | 群主转让确认；成员角色展示 | `GroupDetailScreen` transferOwnership |
| 群公告 / 改名 / 头像 | 完整 | 公告 1200 字；群名 50 字；头像相册上传 | `GroupDetailScreen` |
| 禁言成员 | 完整 | 时长预设 5m/10m/30m/1h/2h/3h/8h/1d/7d/30d（≤服务端 30 天上限） | `GroupMutePolicy.kt` |
| 邀请链接 | 完整 | 生成/轮换/加入；有效期 1/3/7/30 天；次数 1/5/10/50/100/200/500/1000 预设；状态文案 | `GroupDetailScreen` GroupInviteDialog |
| 群审计日志展示 | 完整 | 含退群/转让群主等动作文案；操作人/对象/动作搜索（中英关键词）；默认 80 条可展开 | `GroupDetailScreen` audit rows |
| 群添加成员候选 | 完整 | 可添加联系人搜索；默认 32 条可展开 | `GroupDetailScreen` candidates |
| Sender Key 覆盖与重发 | 完整 | 成员变更可解释补偿；设备列表可按全部/失败/待处理/已分发筛选；默认 20 条可展开 | `crypto/SenderKey*` + GroupDetail |
| 独立「群列表」Tab | 完整* | 会话列表内置「群聊/单聊/未读/密聊/已锁」系统筛选芯片+空态引导（非独立底部 Tab） | `ChatFolderPolicy.SYSTEM_*` + `ChatFolderStrip` |

### 3.5 通讯录 / 二维码

| 功能 | 完整度 | 说明 | 关键路径 |
|------|--------|------|----------|
| 联系人列表与索引 | 完整 | 主列表 = 好友关系（非全站目录） | `ui/screen/contacts/` |
| 搜索用户开聊 | 完整 | 防抖服务端搜索；结果可开聊 | Contacts + `/api/users/search` |
| 联系人本地备注 | 完整 | Room 备注最长 50 字；同步用户资料时保留；长按设置 | `UserDao.setNickname` + Contacts UI |
| 建群入口 | 完整 | 成员搜索（姓名/ID/邮箱/备注）/在线优先排序；匹配空态；需群名+至少 1 成员才可创建 | Contacts `NewGroupDialog` |
| 在线状态 | 完整 | WS 二元在线点 + lastSeen 时间戳协议；单聊标题显式离线+最后在线时间；通讯录「仅在线」筛选+人数 | Contacts + ChatHeaderStatus |
| 我的二维码 | 完整 | 号展示/复制；二维码保存相册/分享图/分享号；刷新；扫一扫入口 | `MyQrCodeScreen` + `MediaExport.saveBitmapToGallery` |
| 扫一扫加好友/入群 | 完整 | 相机扫码；好友/入群/安全码分流 | `ScanScreen` |
| 安全码扫码校验 | 完整 | 扫码比对 + 标记信任；设备/账号错配文案 | ScanScreen + SignalProtocol |
| 好友申请/待验证流 | 完整 | 发送（可附验证信息最长 300 字）/收件/同意/拒绝/撤回 + WS/FCM `FRIEND_REQUEST` + 通知中心/系统托盘；本地列表可按姓名/备注/验证信息筛选（&lt;2 字时） | `FriendRepository` + Contacts UI + `FcmPushService` |

### 3.6 发现 / 动态 / 附近

| 功能 | 完整度 | 说明 | 关键路径 |
|------|--------|------|----------|
| 发现 Feed | 完整 | 分页图文（默认每页 40）+ 点赞评论编辑；已加载 Feed 可按正文/作者本地筛选 | `ui/screen/explore/` |
| 发帖 / 可见性 | 完整 | 公开/联系人/仅自己；配图 | Explore 发帖 + visibility |
| 点赞 / 评论 | 完整 | 乐观更新策略；评论最长 800 字；搜索栏 100 字；≥5 条可按正文/作者筛选 | `ExploreFeedPolicy` + CommentsDialog/PostDetail |
| 编辑 / 删除 | 完整 | 作者编辑正文；删除确认 | Explore + PostDetail |
| 草稿按账号隔离 | 完整 | 发帖草稿按账号本地隔离 | `ExploreDraftPolicy` |
| 朋友圈/详情/作者页 | 完整 | 主 Feed 同源 API；详情/作者页独立路由；朋友圈/作者页正文搜索 160 字（remember 置于 Scaffold 外）；作者页帖子默认 80 | `ExploreSubScreens` + `AuthorProfileScreen` |
| 附近的人 | 完整 | 列表+距离+半径档位(0.5/1/2/5/10/15/20/25/30km)+可见剩余时间+位置更新相对时间+在线优先排序+下拉刷新；≥4 人可搜（搜索 100 字）；无地图（产品边界） | `NearbyPolicy` + `NearbyScreen` |
| 禁动态错误提示 | 完整 | 服务端限制 + 客户端分类文案 | `ExplorePublishErrorPolicy` + ExploreViewModel |

### 3.7 音视频通话

| 功能 | 完整度 | 说明 | 关键路径 |
|------|--------|------|----------|
| 1:1 语音/视频 | 完整 | WebRTC | `webrtc/` + `ui/screen/call/` |
| 群通话 mesh | 完整 | 最多 6 人 mesh；大群需选成员（≥4 人可搜姓名/ID）；类型/选人对话框明示上限；通话中显示人数/上限；非 SFU；弱网 ICE 重连（GATHER_CONTINUALLY + restartIce） | `GroupCallPolicy` + `WebRTCManager` + ChatDetail/CallScreen |
| 来电 / 接听 / 挂断 | 完整 | FCM + WS + pending；前台服务保活 | Call + `FcmPushService` |
| 静音 / 摄像头 / 切换镜头 | 完整 | 通话中控件；前后摄像头切换 | `CallScreen` + WebRTC |
| 音频路由（听筒/扬声器/蓝牙） | 完整 | 听筒/扬声器/蓝牙切换与回退 | `CallAudioController` |
| 通话前台服务 | 完整 | Android 14 类型 | `CallForegroundService` |
| 未接来电记录 | 完整 | 列表回拨/开聊/清空；28 天保留；≥5 条搜索 | `MissedCallRecorder` + ChatList sheet |
| ICE / TURN | 完整 | 服务端签发 + STUN 回退；通话 UI 明示「仅 STUN」；GATHER_CONTINUALLY + DISCONNECTED/FAILED 时主动 restartIce；最多 2 次重启重试才结束通话 | `getIceConfig` + `CallIceServer.isStunOnly` + `WebRTCManager` + `CallReliabilityPolicy` |
| 连接质量 stats | 完整 | PeerConnection getStats → RTT/丢包；通话 UI 质量胶囊 | `WebRTCManager` + `CallScreen.NetworkQualityPill` |
| 屏幕共享 / 录制 / SFU | 未做 | | — |

### 3.8 设置 / 安全 / 隐私

| 功能 | 完整度 | 说明 | 关键路径 |
|------|--------|------|----------|
| 资料与状态 | 完整 | 改名/头像；个性签名 80 字 + 24 档快捷预设（含工作/通话/开车/游戏/睡觉/写作/出差/充电/听歌/阅读/观影/做饭；中英标签、wire 中文多端一致） | `SettingsScreen` + `CustomStatusPolicy` |
| 隐私开关 / 黑名单 | 完整 | 屏蔽名单可搜索后取消屏蔽 | Settings `BlockedUsersDialog` |
| 改密 / 设备管理 / 删号 | 完整 | ≥4 台设备可按名称/ID/状态搜索 | `AccountSecurityScreen` |
| 应用锁（生物识别/设备凭据） | 完整 | 超时 1/2/5/10/15/30/60/120/240/360 分钟多端同步；开关仅本机（副文案明示，依赖设备生物识别） | `AppLockManager` + client-prefs |
| 敏感操作二次验证 | 完整 | 登出/删号/导出等；门闩开关多端同步 | `SensitiveActionGate` + client-prefs |
| 防截屏 FLAG_SECURE | 完整 | 账号开关 + 聊天面；开关多端同步；密聊表面强制（不依赖全局开关） | `ScreenSecureManager` + `ScreenSecurePolicy` + client-prefs |
| E2EE / 安全码入口 | 完整 | 粘性 CHANGED 提示 | `SafetyCodePolicy` 等 |
| 安全范围说明卡片 | 完整 | E2EE/安全码格式/密聊本机边界/FCM/AI/备份文案 | 设置安全中心 |
| 通知设置 | 完整 | 声音/预览/DND/AI 任务；DND 快捷时段（夜间/午休/睡眠/晚间/工作日/专注/上午/午后/晚间尾声/深夜/清晨）+ 滑条；预览文案明示 FCM 占位与密聊/锁脱敏 | |
| AI 隐私设置 | 完整 | 总开关/同意/风格；调用元数据列表 ≥5 条可搜索 | |
| 主题 / 壁纸 / 字号 / 语言 | 完整 | 本地 + `/api/client-prefs` 多端同步；壁纸 16 档（含靛蓝/琥珀/青绿/石墨）+ **自定义图片壁纸**（本地 URI，复制进应用私有目录防授权失效，仅本机）；**聊天气泡颜色** 6 档（蓝/绿/紫/橙/粉/青，CompositionLocal 注入发送气泡/链接预览，按账号本机）；字号 5 档（small→xxlarge） | General + `ChatAppearancePolicy` / `AppLocaleManager` + `ChatBubbleColorPalette` |
| 链接预览 / 未读优先 | 完整 | 同上云同步 | `LinkPreviewPreferences` / `UnreadPriorityPreferences` |
| 内容审核（版主） | 完整 | 需 isModerator；设置入口 + 规则/事件/举报；风险/规则/举报列表可搜索 | `ModerationScreen` |
| 关于页 | 完整 | 版本/安全摘要 + 产品边界（mesh/FCM/密聊/安全码） | `AboutScreen` |
| 独立 TOTP/2FA | 完整 | RFC 6238 TOTP（SHA-1/30s/6位）无外部依赖；setup/confirm/disable + 登录 requiresTotp 流程；SettingsSubScreens 二维码 + 验证码 | `TotpService` + `UserRepository` + `SettingsSubScreens` + `LoginViewModel` |
| 服务器设置（运行时） | 完整 | 设置 → 服务器：填写自建地址（http/https、局域网 IP）立即生效，免重新构建 APK；WS 自动推导（http→ws / https→wss + /ws）；URL 白名单校验；恢复默认；切换服务器需重新登录该服务器账号 | `ApiConfig.setServer` + `ServerSettingsScreen` |

### 3.9 AI（客户端）

| 功能 | 完整度 | 说明 | 关键路径 |
|------|--------|------|----------|
| 改写（含流式） | 完整 | 草稿流式改写；模式 polish/shorten/formal/gentle/casual/professional/expand/bullet/clarify/translate；可取消 | `ApiService` + ChatDetail |
| 回复建议（含流式） | 完整 | 流式候选条最多 4 条；上下文最多 20 条；语气 友好/自然/正式/简洁/温和/幽默/直接/共情/鼓励；可点入草稿 | ChatDetail AI 建议条 |
| 翻译 | 完整 | 24 种目标语言（含印尼/印地/意/土/荷/波/瑞典/马来/芬兰/希腊/捷克/罗马尼亚/捷克/罗马尼亚）；语言列表可搜索；已译语言打勾 | ChatDetail `TranslationLanguageDialog` |
| 会话/范围摘要 | 完整 | 风格 brief/detailed/decisions/tasks/timeline/risks + 范围最近/今天/7天/30天/搜索结果；上下文最多 48 条；多端摘要同步 envelope；摘要历史 ≥4 条可按正文/范围搜索 | ChatDetail `AiSummaryHistoryDialog` |
| 群助手 + 任务提取 | 完整 | 模式 chips 问答/总结/决策/待办/时间线/风险 + 私有预览 + 确认后分享/落本地任务（任务提取上限 30） | `GroupAiSharePolicy` + ChatDetail |
| 语音转写 | 完整 | 气泡请求/复制；预览 320 字可展开；最长 6000；空结果提示 | `VoiceTranscriptPolicy` + ChatDetail |
| 图片/文件分析 | 完整 | 描述/OCR/风险；文件总结/提问 | ChatDetail AI 图片/文件对话框 |
| 语义搜索 | 完整 | 关键词预过滤+星标优先+多发送者多样性(>2人时限占比)+上限100；UI明示候选数/边界；非全库向量 | `ChatSearchModel` + `ChatSearchBar` |
| 费用/限流/取消可见 | 完整 | 调用中可取消；限流/费用提示条 | `AiCostVisibilityPolicy` |
| Prompt 注入防护 | 完整 | 用户输入消毒与系统提示隔离 | `AiPromptSafetyPolicy` |
| 写作风格偏好 | 完整 | 默关、账号隔离 + `/api/client-prefs` 多端同步；预设含简洁/正式/温和/商务/轻松/俏皮/共情/直接/热情/得体；自定义说明 320 字 | `AiWritingStyle*` |
| AI 任务列表与本地提醒 | 完整 | 完成/删除/日历；待办/已完成筛选；≥4 条可按标题/负责人/来源搜索；WorkManager 到期提醒；PIN 门闩 | `AiTasksScreen` + `AiTaskReminderWorker` + `ChatLockSession` |
| 端侧 embedding | 暂不考虑
| 消息分类统计 | 完整 | 纯本地词典规则（通知/待办/财务/学习/技术/情感闲聊/其他）；AI 菜单「消息分类」→ 分类计数 + 比例进度条 + 免责声明；密聊禁用；结果仅存本机 SQLCipher | `AiMessageClassifier` + ChatDetail `MessageClassifyDialog` |

### 3.10 附件与后台任务

| 功能 | 完整度 | 说明 | 关键路径 |
|------|--------|------|----------|
| 加密上传分片/续传 | 完整 | AES-GCM 分片；断点续传 | `attachment/*` |
| Commit 绑定消息 | 完整 | 上传完成 commit 后绑定 client msg | attachment pipeline |
| 下载 Range + 哈希校验 | 完整 | API 完备 | `ApiService` |
| 暂停/恢复/取消 | 完整 | Coordinator + 气泡/会话级批量 | `AttachmentTransferCoordinator` |
| Worker 进程恢复 | 完整 | 进程死后 reconcile 可恢复态 | `AttachmentTransferWorker` + Scheduler |
| Sender Key 后台重试 | 完整 | 群分发失败 WorkManager 重试 | `SenderKeyRetryWorker` |
| 定时消息 Worker | 完整 | 群走 TextOutboxFlusher Sender Key | `ScheduledMessageWorker` |

### 3.11 推送与系统集成

| 功能 | 完整度 | 说明 | 关键路径 |
|------|--------|------|----------|
| FCM 注册/注销 | 完整 | 注册状态持久化(UNKNOWN/INITIALIZING/REGISTERED/FAILED)+最后注册时间+失败原因；通知设置页展示通道健康度 | `PushRegistrationManager` + NotificationSettings |
| 消息/来电/动态/好友申请推送 | 完整 | 消息预览多为加密占位；好友申请仅路由元数据；无厂商通道 | FCM `FRIEND_REQUEST` + tray → 通讯录 |
| 本地通知渠道 | 完整 | messages/calls/ai_tasks | `AppNotifier` |
| 厂商推送通道 | 未做 | 仅 FCM | — |
| ConnectionService 系统来电 | 完整 | self-managed PhoneAccount + TelecomManager.addNewIncomingCall；锁屏/后台展示系统原生通话界面（接听/拒接/挂断）；onAnswer→MainActivity→CallScreen；onReject→CallActionBus 挂断 | `MaodouchatConnectionService` + `TelecomHelper` + `NavGraph` |

---

## 4. 安全与 E2EE 能力

| 能力 | 完整度 | 说明 |
|------|--------|------|
| Signal 1:1 多设备会话 | 完整 | libsignal + 持久化 store；逐设备 fan-out + 自身多设备同步 |
| 群 Sender Key | 完整 | 分发/epoch/覆盖重试；弱网退避细化（网络错误 10s 起 8 次 vs 协议错误 30s 起 5 次 + ±25% 抖动防惊群） | `crypto/SenderKeyRetryManager` |
| PreKey 上传/拉取/设备管理 | 完整 | 批量校验：最少 10 个 PreKey + keyId 1..16777215 + 签名长度 64..512 + base64 字符集 + 去重；账号安全页设备列表可搜索 |
| 身份信任 + 安全码 + QR | 完整 | 自研 5 位分组 digest；TOFU + CHANGED 拦截 + QR 扫码核验 + **一键验证所有设备**（verifyAllDevices）；多设备 ≥4 可搜 ID/信任/指纹并可滚动 |
| 附件 AES-GCM（服务端只存密文） | 完整 | IMAGE/VIDEO/GIF/VOICE/FILE 全部走加密附件管道；客户端加解密；密钥随消息 meta；服务端密文；兼容旧版 inline FILE 消息 |
| SQLCipher 本地库 | 完整 | Keystore 包 passphrase；损坏记录隔离删除；打开失败时销毁重建 |
| 换号/登出本地硬销毁 | 完整 | purge DB/缓存/任务/Signal 状态/FCM token |
| BackgroundSessionGate | 完整 | Worker 防串号；每次异步操作前校验 userId + token 一致性 |
| JWT + Refresh 轮换/吊销 | 完整 | 服务端 version/jti；access 15min + refresh 30d SHA-256 哈希；REST 401 自动刷新重试 |
| App 锁 / 敏感操作 / 截屏 | 完整 | 锁开关本机；超时/门闩/防截屏云同步 |
| 密聊（本机防截屏+盲水印） | 完整 | 非双端协议；防截屏+显示水印；无法对抗外置相机 |
| 阅后即焚 | 完整 | 截屏检测回调（ContentObserver 监听 Screenshots 目录，检测到截屏时 Toast 提醒）；无法对抗外置相机/他端备份 | `ScreenshotDetector` + ChatDetail |
| 消息诈骗启发式扫描 | 完整 | 纯本地启发式；气泡横幅 | `MessageSafetyScanner` + ChatDetail |
| Sealed Sender | 未做 | 元数据对服务端可见 |
| 后量子 Kyber 会话 | 半成品 | store 接口有，会话未用 |

---

## 5. 服务端 API 域

| 域 | 完整度 | 代表路由/模块 |
|----|--------|----------------|
| 健康检查 | 完整 | `/health/live`、`/health/ready` |
| 认证 | 完整 | register/login/refresh/logout、验证码、reset-password |
| 好友 | 完整 | requests incoming/outgoing、accept/reject/cancel、list/delete |
| 会话文件夹云同步 | 完整 | GET/PUT `/api/chat-folders` |
| 客户端外观/语言/写作风格/安全 UX 偏好 | 完整 | GET/PUT `/api/client-prefs`（主题/语言/壁纸/字号/链接预览/未读优先/AI 写作风格/应用锁超时/防截屏/敏感操作门闩） |
| 用户/隐私/拉黑 | 完整 | profile、blocks、自删 |
| 附近的人 | 完整 | nearby-location / nearby |
| 通知偏好 / Push Token | 完整 | notification-settings、push-tokens |
| 会话/群治理 | 完整 | chats、members、invite、audit、mute… |
| 消息 | 完整 | 收发、状态、撤回编辑、反应、星标、置顶、mutations；置顶清理已覆盖全部删除路径（delete/revoke/moderation/批量过期/删会话） |
| 加密附件 | 完整 | 分块上传、commit、下载、配额 |
| 动态 | 完整 | posts、like、comment；受禁动态限制 |
| 举报 | 完整 | 用户提交 + 管理处置 |
| 风控规则/风险事件 | 完整 | moderation rules + events；列表搜索 |
| AI 网关 | 完整* | 依赖 `OPENAI_*` 配置 |
| 按需贴纸 | 完整 | `GET /api/stickers/manifest.json`（STORAGE_DIR/stickers-manifest.json，无文件返回空清单）+ `GET /static/stickers/{packId}/{name}`（白名单 + canonical 路径防穿越） |
| Signal 密钥/设备 | 完整 | keys/upload、devices… |
| 通话信令/TURN | 完整* | signaling + ice-config；依赖 TURN |
| WebSocket 实时 | 完整 | `/ws` Bearer JWT |

\* 功能代码完整，运行依赖外部配置。

---

## 6. 管理后台（MASTER）

| 能力 | 完整度 | 说明 |
|------|--------|------|
| 短时 admin-jwt 二次确认登录 | 完整 | 不写 localStorage |
| 仪表盘 / 趋势 / 系统统计 | 完整 | 概览卡片 + 趋势图 |
| 用户列表/详情 | 完整 | 搜索/分页；详情含设备与状态 |
| 封禁/解封（原因模板+审计） | 完整 | `AdminDispositionPolicy` |
| 禁动态/解除（模板+API） | 完整 | `PUT .../post-restriction` |
| 停用账号（匿名化） | 完整 | 不可逆确认后匿名化 |
| 动态/评论管理删除 | 完整 | 版主删除 + 审计 |
| 群聊管理/解散 | 完整 | 列表/解散确认 |
| 举报处理 | 完整 | 队列处置 + 模板原因 |
| 风控规则 CRUD + 二次确认 | 完整 | 规则启停/编辑需确认 |
| 风险事件处理 | 完整 | 事件列表 + 处置 |
| AI 用量审计（仅元数据） | 完整 | 无 prompt 明文；列表可按功能/状态/模型搜索 |
| 推送令牌只读 | 完整 | 按用户查看 token 元数据 |
| 审计日志 + CSV 导出 | 完整 | 动作筛选 + 操作者/目标/动作/详情关键词搜索 + CSV 导出 |
| 消息正文检索/窃听 | 未做 | 符合 E2EE 取向 |
| 多管理员角色体系 | 完整 | 3 角色（OWNER/ADMIN/MEMBER）+ ADMIN_ROLES 权限矩阵 + 所有权转让 + 成员提升/降级/禁言/踢出 + 审计日志（17 种 action）+ 客户端 GroupDetailScreen 角色管理 UI + isOwnerOrAdmin 检查 |

静态资源：`server/src/main/resources/admin/admin.html|css|js`  
路由：`server/.../plugins/AdminRouting.kt`

---

## 7. 运维与工程资产

| 资产 | 说明 |
|------|------|
| 一键部署脚本 | `scripts/deploy.sh`（bash）+ `scripts/deploy.ps1`（Windows）：自动生成密钥/补全 .env、域名提示、健康等待、管理员指引；`--relaxed`（自托管宽松档：无 SMTP/TURN 可起）与 `--bootstrap-admin`（首个注册用户自动成为主管理员） |
| `docker-compose.yml` | Caddy + Postgres + server |
| `deploy/Caddyfile` | TLS、安全头 |
| `scripts/backup-production.sh` / `restore-production.sh` | 生产备份恢复 |
| `scripts/verify-production-topology.sh` | 拓扑离线/在线校验 |
| `scripts/check-string-parity.py` | 中英 string name 对齐 |
| `scripts/admin-e2e.mjs` | 管理后台 Playwright（需自行执行） |
| `.github/workflows/ci.yml` | CI 配置存在 |
| 验收清单文档 | `docs/*-verification.md`、`release-checklist.md`、`size-baseline.md` 等（**清单就绪 ≠ 已全部勾选**） |

---

## 8. 导航结构（用户可见）

```
启动 → 登录 | 主框架（可叠应用锁）
主 Tab：会话 | 通讯录 | 发现 | 设置
二级：聊天详情、群详情、收藏、媒体中心、AI 任务、通话、全局搜索、
      通知中心、设置子页、扫一扫、附近的人、朋友圈/动态详情/作者页、我的二维码
```

入口：`app/.../ui/navigation/NavGraph.kt`、`MainActivity.kt`

---

## 9. 已知缺口与风险（代码级）

### 9.1 产品缺口

1. 应用锁 **开关** 仍 **仅本机**（依赖设备生物识别/凭据是否可用；超时档位、防截屏、敏感操作门闩已云同步）。主题/语言/壁纸/字号/链接预览/未读优先/AI 写作风格已云同步；冷启动/登录后 `ClientPrefsSync` 拉取。  
2. 群通话 **mesh 有上限**，非会议级 SFU。  
3. 推送仅 FCM，无国内厂商通道。  
4. 端侧 embedding **明确禁止进包**。  
5. sealed-sender / PQXDH 多为 **runtime flag + 客户端门控**，非完整 Signal 证书链 / 会话密码学。  
6. 服务端仍可见会话元数据（非内容）。  
7. 无支付 / iOS / 桌面；Bot API 已存在但开放平台未产品化。  
8. 联系人备注 **仅本机**（不进云端，避免服务端存社交图备注）。  
9. 无独立底部「群列表」Tab（会话条内群聊/单聊筛选已覆盖主路径）。

### 9.2 工程风险

1. `ChatDetailViewModel` / `ChatDetailScreen` / `Routing.kt` **超大文件**，变更成本高。  
2. Admin 存在 **admin-jwt 控制台** 与 **moderator 捷径 API** 双通道，路径命名不完全统一。  
3. 安全码为 **自研格式**，多端必须同算法。  
4. Chat PIN 为轻量 SHA-256，非 KDF。  
5. 真机/弱网/公网验收依赖人工填表，**本盘点未执行测试**。

---

## 10. 与「可发布」的关系

| 问题 | 答案 |
|------|------|
| 功能是否「都写了」？ | 主 IM 闭环、安全主干、动态、通话、AI、管理后台 **代码面基本齐全** |
| 是否等于可发布？ | **否**。须完成设备矩阵、生产拓扑、备份恢复、无障碍、包体等清单证据 |
| 是否等于 TG/微信/QQ 水平？ | **不得据此宣称**。对标是体验+可靠性+运维共同结果，不是功能 checklist 勾满 |

相关清单（保留，非路线图）：

- `docs/e2ee-multidevice-verification.md`
- `docs/attachment-reliability-verification.md`
- `docs/call-reliability-verification.md`
- `docs/production-topology-acceptance.md`
- `docs/backup-restore-acceptance.md`
- `docs/release-checklist.md`
- `docs/ui-motion-performance-budget.md`
- `docs/size-baseline.md`

---

## 11. 文档维护约定

1. **本文件**是功能与完整度的主台账；增删大功能时更新对应表行。  
2. 旧 `roadmap-v*.md`、`project-status-roadmap.md` **已删除**，勿再引用。  
3. 验收类文档只记证据，不重复写功能清单。  
4. 描述完整度时区分：**代码完整** vs **真机/运维已证**。

---

*生成方式：多子代理只读扫描 `app` / `server` / `deploy` / `scripts` 后汇总。未执行 Gradle/单元测试/真机。*


---

## 12. 2026-07-20 运行时门控与密聊隐私栈（重写合并）

> 本节合并原附录中 push #4…#66 的碎片记录。完整度均为 **代码面**；功能期以静态核验为主，**未**作为全量 Gradle 完成门禁。

### 12.1 Surface 总表（密聊重点 #60–#68）

| # | 主题 | Runtime keys | Bot / health | 客户端 |
|---|------|--------------|--------------|--------|
| 60 | 复制 / 媒体导出 | `secret_copy_block_enabled`, `secret_media_export_block_enabled` | `leakz` + hint 路由 | `SecretCopyBlockPrefs` / `SecretMediaExportBlockPrefs` |
| 61 | 转发 / 会话导出 | `secret_forward_block_enabled`, `secret_chat_export_block_enabled` | `vaultz` | `SecretForwardBlockPrefs` / `SecretChatExportBlockPrefs` |
| 62 | Sealed / PQXDH 开关 | `sealed_sender_enabled`, `pqxdh_preview` | `sealz` | `SealedSenderPrefs` / `PqxdhPreviewPrefs` |
| 63 | 可见水印 / 自动消失 | `visible_watermark_enabled`, `secret_auto_disappear_enabled` | `markz` | `VisibleWatermarkPrefs` / `SecretAutoDisappearPrefs` |
| 64 | 链接隐私 | `secret_link_preview_block_enabled`, `secret_external_link_block_enabled` | `linkz` | `SecretLinkPreviewBlockPrefs` / `SecretExternalLinkBlockPrefs` |
| 65 | 通知 / 列表预览 | `secret_notif_preview_block_enabled`, `secret_list_preview_block_enabled` | `privz` | `SecretNotifPreviewBlockPrefs` / `SecretListPreviewBlockPrefs` |
| 66 | 反应 / 标星 | `secret_reaction_block_enabled`, `secret_star_block_enabled` | `metaz` | `SecretReactionBlockPrefs` / `SecretStarBlockPrefs` |
| 67 | 输入状态侧信道 | `secret_typing_block_enabled` | `typtz` | `SecretTypingBlockPrefs` |
| 68 | 已读回执侧信道 | `secret_read_receipt_block_enabled` | `redz` | `SecretReadReceiptBlockPrefs` |
| 69 | 在线状态侧信道 | `secret_presence_block_enabled` | `presz` | `SecretPresenceBlockPrefs` |
| 70 | 最后上线时间侧信道 | `secret_last_seen_block_enabled` | `lastsz` | `SecretLastSeenBlockPrefs` |

配套群玩法 / Markdown（节选）：

| Surface | 群玩法前缀 | Markdown |
|---------|------------|----------|
| 60 | COPYLOCK / EXPORTSEAL / LEAKWALL | `~cp` `~ex` `~lw` |
| 61 | FORWARDSEAL / CHATEXPORTLOCK / VAULTFENCE | `~fw` `~ce` `~vf` |
| 62 | SEALSPRINT / PQXDHDASH / CERTRELAY | `~ss` `~pq` `~cr` |
| 63 | MARKSPRINT / FADETIMER / STAMPRELAY | `~mk` `~ft` `~sr` |
| 64 | LINKLOCK / PREVIEWMUTE / URLFENCE | `~ll` `~pm` `~uf` |
| 65 | NOTIFMASK / LISTBLUR / TRAYSEAL | （菜单项） |
| 66 | REACTLOCK / STARSEAL / METAFENCE | `~rx` `~st` `~mf` |
| 67 | TYPINGSEAL | `~tp` |
| 68 | READSEAL | `~rr` |
| 69 | PRESENCESEAL | `~ps` |
| 70 | LASTSEENSEAL | `~ls` |

### 12.2 密聊与取证（代码面）

| 功能 | 完整度 | 说明 |
|------|--------|------|
| FLAG_SECURE / recents 排除 | 完整 | 密聊 / 全局策略联动 |
| 频域盲水印 + 可见水印 | 完整 | 时间 / 用户 ID 等；后台可提取路径存在 |
| 截屏检测 + 对端 CAPTURE_ALERT | 完整 | ContentObserver + prefs |
| 阅后即焚 / 消失消息 / view-once / spoiler | 完整 | 与密聊默认 24h（可关）联动 |
| 密聊复制 / 导出 / 转发 / 会话导出 | 完整 | runtime 可关 |
| 密聊链接预览 / 外链 | 完整 | 外链默认更松（false） |
| 密聊通知 / 列表预览脱敏 | 完整 | AppNotifier + ChatList |
| 密聊反应 / 标星封堵 | 完整 | 防元数据侧信道 |
| 密聊 typing 门控 | 完整 | 防在线状态侧信道（announceTypingStarted / stopTypingAnnouncement） |
| 密聊 read-receipt 门控 | 完整 | 防已读观察侧信道（markReadJob / markAllAsRead / onCleared） |
| Sealed-sender 证书预取 | 半成品 | flag + cache；非完整 sealed 管道 |
| PQXDH | 半成品 | preview flag |

### 12.3 Bot / 官网 / 后台（相对 7 月中旬）

| 功能 | 完整度 | 说明 |
|------|--------|------|
| Bot REST（发送/查询/flags/healthz 族） | 基本完整 | 见 `docs/bot-developer-api.md`；`listCapabilities` 需与路由同步 |
| 隐私类 hint 与 flags（leakz…lastsz） | 基本完整 | surface 60–70 |
| 管理后台 runtime 开关行 | 完整 | `admin.js` + CSV 导出键 |
| 公开官网 feature 卡片 | 基本完整 | `public/index.html` 随 surface 追加 |
| 多管理员 RBAC / 开放平台商店 | 完整 / 半成品 | OWNER/ADMIN/MEMBER 3 角色 RBAC 完整；开放平台 Bot API 覆盖 70+ surface，开发者官网 + 开发者中心 + bot-developer-api.md |

### 12.4 更早 runtime 门控族（#22–#59 摘要）

功能期曾批量接入「全局功能开关 → public/status → *Prefs → 客户端门控 → bot flags → admin 行」模式，覆盖（非穷尽）：

- 消息：编辑 / 撤回 / 置顶 / 转发 / 星标 / 反应 / 静默发送 / Markdown / 定时  
- 媒体：图 / 视频 / 语音 / GIF / 贴纸 / 文件 / 阅后即焚 / 剧透 / 自动下载  
- 社交：好友 / 群邀请 / 动态 / 附近 / 二维码 / 名片  
- 通话：语音 / 视频  
- AI：总开关 + 翻译 / 摘要 / 改写 / 建议回复 / 转写 / 识图 / 文件 / 语义搜索 / 群助手 / 离线  
- 外观 / 通知：壁纸字号动效、预览、铃声、DND、推送  
- 安全：应用锁、会话锁、屏安、截屏检测、盲水印  

细节以 `RuntimeConfigService.kt` knownKeys 与 `ChatListScreen` 同步块为准。

### 12.5 下一步（文档层）

1. **#67**：密聊 typing / read-receipt 门控（客户端钩子已存在）。  
2. 持续 #68+：侧信道收敛、群玩法、后台体验。  
3. 功能面收敛后：静态 bug hunt → 少次编译 → 真机验收填表。  
4. 勿宣称 TG/微信/QQ 级或 Signal 全量对等，直至跨端与取证证据齐备。

---

## 13. 文档维护约定（更新）

1. **本文件**是功能与完整度主台账；大功能改表行，不靠无限追加 push 日志。  
2. **阶段进度 / 策略 / 差距**写 `docs/progress-report.md`。  
3. **Bot 路由契约**写 `docs/bot-developer-api.md`。  
4. 验收证据只写 `docs/*-verification.md` 等清单，不重复堆功能表。  
5. 完整度区分：**代码完整** vs **真机/运维已证**。  
6. 旧 roadmap / 碎片 push 附录 **不再续写**；需要历史时查版本控制。

---

*2026-07-20：删除冗长 push #4…#66 流水账附录，改为 §12 合并表；主体 §1–§11 继承既有盘点并修订过时缺口描述。*

---

## 14. 2026-08-02 B1–B8 区块最终状态（代码面）

> 各区块完成度以编译 + 测试为准：App `compileDebugKotlin`/`testDebugUnitTest`（483 单测）与 Server `compileKotlin`/`test`（含 B2/B3/B4/B6 新增端点测试）**全部通过**；Release APK 11.72MB（19.7MB 旧包含已废弃 9.86MB WebRTC .so）。

| 区块 | 交付 | 验证 |
|------|------|------|
| B1 包体 | `slim/OnDemandStickerStore`（预留）、`SizeGuard` + `verifyReleaseSize` 护栏任务、依赖审计、acknowledgments 构建期排除 | APK 19.7→11.72MB；0 残留 mapOf 响应 |
| B2 密聊 | surface #71–#78 全链路（RuntimeConfig keys + Prefs + healthz + hint + admin 行 + 行为类）+ `SecretSurfaceWatchdogWorker` 接线（SIM 变更→清密聊） | `SecretSurfaceHealthzTest` 8 路由全过 |
| B3 群玩法 | 投票/签到/接龙/PK（表 + REST + WS 推送 + 4 Screen + 扩展函数接线） | `GroupPlayRoutesTest` 全链路通过 |
| B4 AI | 6 能力（服务端 5 端点 + 客户端能力类预留）+ `AiEnhanceHttp` | `AiEnhanceRoutesTest` 通过 |
| B5 系统集成 | 小组件全套 + 悬浮球（设置页入口已接线）+ 双栏 + 快捷回复（RemoteInput 平台限制→打开会话） | App 编译/单测通过 |
| B6 运维 | 公告/标签/限流仪表盘/审计导出/设备一致性 + admin UI | `AdminEnhanceRoutesTest` 通过 |
| B7 性能 | migration 27→28（5 索引）+ perf 工具 + 动效预算文档 | 索引名与 Entity 一致（运行时无 schema 崩溃） |
| B8 质量 | strings 2440=2440 对称、术语 OK、A11y/深色 | `check-string-parity.py` 通过 |

**待接入（涉及巨型/红线文件，记录）**：OnDemandStickerStore 贴纸面板、AnnouncementPolicy 通知中心、B4 六能力 UI 入口、ScreenshotBurnDetector 前台监听、密聊 TTL 清扫（需 SecretChatEntity 补活动时间字段 + migration）。
