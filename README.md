# Maodouchat

Maodouchat（毛豆聊天）是 Android 即时通讯应用：Jetpack Compose 客户端 + Ktor 后端，覆盖聊天/群/动态/音视频通话、Signal 系 E2EE、加密附件、可选 AI 与管理后台。

**功能与完整度全表**见 [`docs/feature-inventory.md`](docs/feature-inventory.md)（代码盘点；≠ 发版验收）。  
**阶段进度**见 [`docs/progress-report.md`](docs/progress-report.md)。  
**Bot API**见 [`docs/bot-developer-api.md`](docs/bot-developer-api.md)。

## 环境要求

- **JDK 21（强制用于本仓库 Gradle 构建）**：推荐 Android Studio 自带 JBR 21  
  - 源/目标兼容仍为 Java 17（`compileOptions` / `jvmTarget`）  
  - **不要用本机默认 JDK 25 跑 Gradle**：当前 Kotlin DSL 会报 `IllegalArgumentException: 25.0.2`
- Android Studio / Android SDK，当前 `compileSdk = 36`
- Windows Git Bash 或 PowerShell
- 网络可访问 Gradle/Maven 仓库

### 构建前设置 JAVA_HOME（Windows Git Bash）

```bash
# 路径按本机 Android Studio 安装位置调整
export JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # 应显示 21.x

# 或使用仓库脚本（会探测常见 JBR 路径）
source scripts/use-jdk21.sh
```

PowerShell：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
```

也可在用户级 `~/.gradle/gradle.properties` 写入（**不要提交到仓库**）：

```properties
org.gradle.java.home=C:/Program Files/Android/Android Studio1/jbr
```

常用命令：

```bash
# App
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:testDebugUnitTest

# Server（独立工程）
cd server && ../gradlew.bat compileKotlin test

# 官网端到端（需本地 Ktor 已启动；可用 BASE_URL/CHROME_PATH 覆盖）
npm run test:website
```

> `gradle.properties` 已通过 `android.suppressUnsupportedCompileSdk=36` 静默 `compileSdk = 36` 的兼容性警告。构建时若看到 `SDK processing ... SDK XML versions up to 3 ... version 4 was encountered` 提示，这是 AGP/sdklib 读取 SDK `package.xml` 的无害警告，不影响构建。

## Gradle Wrapper 状态

仓库已补充 `gradlew` 和 `gradlew.bat` 启动脚本；如果 `gradle/wrapper/gradle-wrapper.jar` 不存在，需要先在有 Gradle 的机器上生成完整 wrapper：

```bash
gradle wrapper --gradle-version 8.14.5
```

或使用 Android Studio 打开项目后生成/同步 Gradle Wrapper。

## 后端配置

复制环境变量示例并按需修改：

```bash
cp .env.example .env
```

重要变量：

- `APP_ENV`：运行环境；设为 `production` 或 `prod` 时启用生产配置校验。
- `HOST`：默认 `0.0.0.0`。
- `PORT`：默认 `8080`。
- `BASE_URL`：头像等文件返回 URL 的基础地址；生产环境必须为 `https://...`。
- `JWT_SECRET`：生产环境必须替换为不少于 32 字符的随机字符串，不能使用默认值。
- `DATABASE_URL` / `DATABASE_DRIVER`：开发默认 H2 内存库；生产环境禁止使用内存库，必须配置持久化数据库。
- `STORAGE_DIR`：上传文件目录，默认 `./uploads`。
- `SEED_DEMO_USERS`：是否创建演示用户；开发默认开启，生产环境必须关闭。
- `MODERATOR_EMAILS`：逗号分隔的平台审核员邮箱；拥有内容审核页和 `/api/admin/reports` 权限。
- `MASTER_ADMINS`：逗号分隔的主管理员用户 ID（不是邮箱）；拥有独立管理后台的用户、内容、规则和审计权限。生产环境先注册管理员账号、取得用户 ID，配置后重启服务，再访问 `/admin`。主管理员自动继承审核员权限，但普通审核员不能进入完整后台。
- `SMTP_*`：邮箱验证码配置；不配置 `SMTP_HOST` 时走开发模式，验证码打印到控制台；生产环境必须配置 SMTP。
- `OPENAI_API_KEY` / `OPENAI_MODEL` / `OPENAI_TRANSCRIPTION_MODEL` / `OPENAI_BASE_URL`：AI Gateway 配置；未配置 key 时 `/api/ai/*` 返回 503，但普通聊天服务不受影响。

生产环境启动前会校验：JWT 密钥（≥32）、HTTPS `BASE_URL`、持久化数据库（禁止 H2 mem/file）、关闭演示用户、配置 SMTP、配置 TURN（URL + ≥32 共享密钥）。`OPENAI_API_KEY` 可选；未配置时仅 `/api/ai/*` 返回 503，聊天不受影响。不满足强制条件会直接启动失败，避免弱配置上线。生产数据库为 **PostgreSQL**（Docker Compose 自带），**不支持 MySQL**。

## 本地运行后端

如果 wrapper 完整：

```bash
cd /d/Maodouchat/server
../gradlew.bat run
```

如果只有全局 Gradle：

```bash
cd /d/Maodouchat/server
gradle run
```

### 管理后台

配置 `MASTER_ADMINS` 并重启服务后，通过 `https://<服务域名>/admin` 访问。生产环境必须使用 HTTPS，不要通过公网明文 HTTP 输入管理员密码。

- 普通 App Access Token 不能直接访问完整管理 API。
- 后台登录会再次校验主管理员密码，并换取用途限定的 5 分钟 `admin_session` Token。
- 管理 Token 只保存在页面内存，不写入 `localStorage`，不能使用自身续签；到期后页面强制退出。
- 后台 HTML、CSS 和 JavaScript 使用同源独立资源，CSP 不允许 `unsafe-inline`，静态资源禁止跨源复用。
- 用户、举报、动态、评论、风控规则和审计均可在后台处理；审计页支持防表格公式注入的 UTF-8 CSV 导出。
- `MODERATOR_EMAILS` 仅授予 App 内内容审核能力，不等于完整后台权限。

## Docker 运行后端

后端已提供 Docker Compose 部署骨架，默认使用 Caddy 自动 HTTPS、PostgreSQL、coturn 和持久化上传目录；Ktor 8080 仅在容器内部网络开放。

**一键部署**（推荐，自动生成密钥 / 等待健康检查 / 打印管理员指引）：

```bash
bash scripts/deploy.sh --host your.domain.com --email you@example.com
```

个人/小团队自托管（无 SMTP / TURN 服务器）加 `--relaxed`；想让首个注册账号自动成为主管理员再加 `--bootstrap-admin`。Windows 服务器用 `scripts/deploy.ps1`。手动方式：`cp .env.docker.example .env` 后编辑，再 `docker compose up -d --build`。

生产模式默认强制校验 HTTPS `BASE_URL`、强 `JWT_SECRET` / `PUSH_HMAC_SECRET`、持久化数据库、关闭演示用户和 SMTP/TURN 配置；自托管可设置 `RELAXED_VERIFICATION=true` 放宽 SMTP/TURN（验证码打日志、通话仅 STUN），核心安全校验不变。详细说明见 [`docs/docker-deployment.md`](docs/docker-deployment.md)。

生产探针使用 `/health/live` 与 `/health/ready`；一致性备份和显式确认恢复分别使用 `scripts/backup-production.sh`、`scripts/restore-production.sh`。备份包含 PostgreSQL、密文上传对象和 Caddy TLS 状态，并生成 SHA-256 清单。

## CI

仓库已提供 GitHub Actions 工作流 [`ci.yml`](.github/workflows/ci.yml)，覆盖：

- Server：`compileKotlin` + `test` + 系统 Chrome 管理后台 E2E（登录、短会话、规则 CRUD、审计导出与截图）
- Android：`:app:compileDebugKotlin` + `:app:testDebugUnitTest`
- Docker：`docker compose --env-file .env.docker.example config`

## 发布 GitHub Release

工作流 [`release.yml`](.github/workflows/release.yml) 在推送 `v*` 标签（或手动触发）时自动构建并发布 Release：

- Android 签名 APK（`app-release.apk`）——已启用 R8 + 资源收缩 + `:app:verifyReleaseSize` 体积护栏
- Ktor 服务端发行包（`server/build/install` 打成的 tar.gz）
- `SHA256SUMS.txt` 校验和 + 基于 git log 的自动变更说明

仓库需配置以下 Secrets：

| Secret | 说明 |
|--------|------|
| `RELEASE_API_BASE_URL` | 发布版 App 指向的服务端地址，如 `https://chat.example.com` |
| `RELEASE_WS_URL` | WebSocket 地址，如 `wss://chat.example.com/ws`（缺省时由 API 地址推导） |
| `KEYSTORE_BASE64` | 签名 `.jks`/`.keystore` 的 base64（不配则发布无法安装的未签名 APK） |
| `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` | 签名证书信息 |

触发方式：

```bash
git tag v1.2.3
git push origin v1.2.3
```

或仓库 Actions → Release → Run workflow（可填版本号、API/WSS 地址、预发布标记）。
版本号同时作为 `versionName` 写入 APK；`versionCode` 默认取 git 提交数，也可手动指定。

## 构建 Android

Debug 构建默认连接 Android 模拟器宿主机地址：

```bash
cd /d/Maodouchat
./gradlew.bat :app:assembleDebug
```

或仅编译 Kotlin：

```bash
./gradlew.bat :app:compileDebugKotlin
```

Release 构建必须显式配置 HTTPS/WSS 地址，否则会失败：

```bash
./gradlew.bat :app:assembleRelease \
  -PMAODOU_RELEASE_API_BASE_URL=https://api.example.com \
  -PMAODOU_RELEASE_WS_URL=wss://api.example.com/ws
```

Release 默认仅打包 **arm64-v8a**，并启用 R8 与资源收缩；体积基线见 [`docs/size-baseline.md`](docs/size-baseline.md)。

## Android 网络与备份策略

Debug：

- 默认 API：`http://10.0.2.2:8080`
- 默认 WebSocket：`ws://10.0.2.2:8080/ws`
- 允许本地明文调试网络。

真机调试不能使用 `10.0.2.2`，需要改成本机局域网 IP，例如：

```bash
./gradlew.bat :app:assembleDebug \
  -PMAODOU_API_BASE_URL=http://192.168.1.10:8080 \
  -PMAODOU_WS_URL=ws://192.168.1.10:8080/ws
```

Release：

- 仅允许 HTTPS/WSS。
- 网络安全配置禁止明文流量。
- Android 备份已关闭，并通过 data extraction rules 排除数据库、SharedPreferences 和文件目录，降低本地敏感数据被系统备份迁移的风险。

## 隐私与安全状态

- WebSocket 鉴权已改为 `Authorization: Bearer <token>` 请求头，服务端暂时保留 query token 兼容旧客户端。
- 服务端异常响应对客户端返回通用错误，详细异常只记录在服务端日志中。
- 账号鉴权已接入短期 access token + refresh token 轮换和强制失效：access token 默认 15 分钟有效，并携带 `jti` 与用户 token 版本号；refresh token 30 天有效且服务端只保存 SHA-256 哈希；App 在 REST 401 时会自动刷新并重试一次。退出登录会撤销当前 refresh/access token，全部退出会撤销所有 refresh token 并滚动 token 版本，使旧 access token 立即失效。
- 账号注销已基础完成：账号与安全页输入当前密码后，服务端会匿名化账号资料、撤销会话、清理账号级密钥/设备/偏好/动态数据；App 成功后销毁本机加密存储并回到登录态，历史会话中显示为“已注销用户”。
- 头像上传加入 Base64 长度、图片字节数、图片尺寸和格式校验。
- 消息分页 `limit` 已限制在 `1..100`。
- REST/WebSocket 消息发送会校验消息类型、内容长度和客户端消息 ID；重复客户端消息 ID 会按幂等重试处理，冲突 ID 返回错误。
- 消息状态、WebRTC 信令类型/载荷、拍一拍目标名均加入基础白名单与长度校验。
- Signal PreKey bundle 获取增加基础频率限制；允许拉取自己的其他设备 bundle，用于多设备 Sender Key 分发。
- 本地 Room 数据库已接入 SQLCipher，并通过 Android Keystore 保护随机数据库口令；数据库无法打开或本地会话清理失败时会销毁本地加密存储并重建。
- 单聊文本消息和基础媒体消息已接入第一阶段 Signal E2EE：客户端将内容加密为 `signal-v2` JSON envelope，服务端只存储不透明密文；接收端在历史消息与实时 WebSocket 消息中解密展示。
- 群聊 Sender Key 已有基础接入和分发流程；客户端聊天页可发送群聊文本/基础媒体，并区分群聊安全提示。
- 群详情页已支持群名、成员角色、群头衔、群昵称、群公告、群邀请二维码和群聊密钥状态；群公告由群主/管理员编辑，普通成员可查看；群邀请二维码使用服务端签发 token，扫码后由服务端校验并加入群聊。
- 群禁言已基础完成：群主可禁言管理员/成员，管理员可禁言普通成员；服务端会在 REST 和 WebSocket 两条发送路径拦截被禁言成员发消息/拍一拍，群详情页可设置快捷禁言时长或解除禁言。
- 屏蔽用户已基础完成：私聊可屏蔽/取消屏蔽，服务端会拦截消息、typing 和通话信令；设置页屏蔽名单展示头像、昵称、用户 ID 和状态。
- 举报/风控审核闭环已基础完成：私聊联系人和消息长按菜单可提交举报，服务端写入 `reports` 表并校验举报对象和消息可见性；配置为审核员的账号可在设置页进入“内容审核”，查看举报、流转状态，删除被举报的消息/动态/评论，并对目标账号执行禁发消息、禁发动态或临时封禁。
- 自动风控规则已基础接入：动态和评论发布前会执行可配置的关键词、短链和频率规则，命中结果写入风险事件并可在审核页确认；审核员可启停规则并编辑动作、命中阈值和统计窗口。为保持 E2EE 边界，服务端不扫描聊天密文。
- 可选 FCM 离线推送已基础接入：服务端通过 HTTP v1 推送消息、拍一拍、来电、动态点赞和评论，Android 无需 `google-services.json`，使用 Gradle 属性手动初始化；令牌按安装管理并覆盖登录、账号切换和退出生命周期。推送 payload 只含路由元数据，消息正文固定显示“收到一条加密消息”，不会把 E2EE 明文交给 Firebase；未配置 Firebase 时 App 和 Server 保持正常运行。
- 应用级多语言第六阶段代码已完成：通用设置支持跟随系统、简体中文和 English，Android 13+ 同步系统应用语言；登录、四个主 Tab、联系人/二维码/安全码、发现/附近/朋友圈/作者/动态详情、系统通知、聊天/群管理/通话、共享消息气泡、导航、全局搜索、AI 总结范围/记录、AI 任务和提醒、AI 图片/文件理解、完整设置模块均已迁移，当前有 971 组对称中英文资源、5 组复数与 1 组字符串数组。位置、HTTP、WebSocket、Signal 和附件底层错误使用稳定类别并由 UI 映射文案；仍需统一编译和中英文手机/平板布局验证。
- AI 翻译支持中、英、日、韩、西、法六种目标语言：输入框可翻译草稿，长按文本消息可保存多个本地翻译结果并切换当前显示语言，重复选择已有结果不会再次消耗 AI 请求。
- 当前聊天 AI 语义搜索已完成代码闭环：搜索栏可在关键词与 AI 语义模式间切换，按原文/转写/翻译/星标和时间范围筛选候选；仅在用户点击后提交最多 60 条必要片段，服务端校验聊天成员和 AI 权限，不保存明文，并过滤模型返回的越权消息 ID。
- 跨聊天搜索已完成代码闭环：本机 SQLCipher 使用文档/分词倒排表索引已解密文本、语音转写与翻译，支持中文和拉丁词召回；全局搜索可完全本地运行关键词模式，AI 模式仅在用户显式触发并确认后，对允许 AI 的少量候选做跨聊天重排。服务端逐聊天复核成员与 AI 开关，结果只能落在候选 `(chatId, messageId)` 白名单内，点击可定位并高亮原消息。
- AI 未读摘要已改用逐用户精确窗口：App 在自动已读前向服务端获取最近未读消息 ID，再从本机已解密文本中构造最小摘要上下文；服务端不接触 E2EE 明文。群聊未读数和批量已读也已统一使用逐用户回执，避免一名成员阅读后误清空其他成员未读。
- AI 聊天总结已支持选择最近聊天、今天、最近 7 天或当前关键词/语义搜索结果；日期范围从 SQLCipher 本机已解密历史筛选，候选可包含文本、语音转写和翻译。首次授权前会冻结最多 30 条必要片段，结果展示范围与实际上下文数量。
- AI 摘要跨设备同步已完成代码闭环：缓存键、范围、消息边界和摘要正文一起进入 Signal 多设备 envelope，服务端只按已确认目标设备暂存最多 30 天不透明密文；其他设备验证并解密后写入 SQLCipher、确认删除队列项。聊天 AI 菜单可查看最近 20 条本地或同步摘要记录，删除会话会同步清理本机摘要和关联提醒数据。
- AI 图片理解已完成代码闭环：长按图片可主动选择内容描述、文字提取或安全风险检查；App 限尺寸压缩后复用现有 AI 授权和聊天开关，服务端校验成员关系、图片格式/尺寸并通过统一 Gateway 调用视觉模型，只记录不含明文的审计信息，不持久化图片内容。
- AI 文件摘要与问答已完成代码闭环：文件消息通过版本化 E2EE payload 跨端保留真实文件名、MIME 和大小，兼容旧版纯 Base64 文件；长按 PDF 或 UTF-8 文本类文件可生成摘要或提出具体问题。文件仅在用户主动选择后由本机读取并提交，服务端执行成员、格式、大小和 AI 开关校验，不持久化文件明文。
- 端到端加密附件对象管线已完成代码闭环：Android 对 100 MB 内文件流式执行 AES-256-GCM 加密，服务端仅保存不透明密文并按聊天成员鉴权下载；Signal/Sender Key envelope 只携带随机内容密钥、IV、哈希和文件元数据。发送重试、按需下载打开、AI 文件读取、重新加密转发、1 GB 用户配额、24 小时未提交清理，以及删除/撤回/聊天/账号生命周期清理均已接入；仍待统一编译、双端真机和弱网验证。
- 加密附件分块、断点续传与后台恢复已完成代码接入：Android 将密文按 4 MB 分块上传，服务端记录偏移并支持同块幂等重放、文件锁、`fsync`、块哈希和最终总哈希；响应丢失后客户端查询实际偏移继续，下载支持标准 Range 和 `.part` 恢复。SQLCipher 持久化文件密钥、会话 ID、密文路径、偏移、阶段和已生成的 E2EE wire envelope，网络约束 WorkManager 可在进程重启后续传；文件气泡支持暂停、继续、取消和分类失败重试。同一消息 ID 的网络重试复用完全相同的 Signal/Sender Key 密文，发送前校验对象仍有效，24 小时过期后自动回退到重新分片上传。上传完成后的消息仍只在对应聊天上下文激活时发送，避免后台绕过群 Sender Key 分发；待统一编译、杀进程、弱网和双端真机验证。
- 图片与视频已迁移到同一 E2EE 对象管线：图片先压缩为私有 JPEG，视频在 100 MB 上限内直接流式 AES-256-GCM 加密，不再整体 Base64 入内存或消息 envelope。IMAGE/VIDEO 共享 SQLCipher + WorkManager 恢复、暂停/继续/取消、固定尺寸进度遮罩、接收端按需自动下载、失败手动重试和缓存失效回源；转发会下载本机明文后生成新密钥与新对象，AI 图片理解也先取得本机解密副本。服务端将 FILE/IMAGE/VIDEO 都与消息事务绑定并补提交状态测试；旧 inline 图片/视频消息继续兼容。
- 安全型群聊 `@AI` 助手已完成代码闭环：支持问答、总结、决策和结构化待办提取，结果默认仅发起者可见；确认后才以发起者本人身份通过群 Sender Key 加密分享，并显示“AI 辅助、发送者已确认”标识，不让服务端机器人持有群密钥或冒充系统身份。
- 群聊结构化 AI 任务已完成代码闭环：待办模式返回标题、负责人和截止信息，用户预览确认后才写入本机 SQLCipher 数据库；任务页按聊天隔离，支持待完成/已完成统计、完成切换、逾期提示和删除确认。AI Gateway 不持久化任务明文，退出或切换账号销毁本地加密库时任务一并清除。
- AI 任务到期提醒已完成代码闭环：每项任务使用只携带本地任务 ID 的唯一 WorkManager 作业，支持周期校准、开机/系统时间/时区变化重排、勿扰延后、通知预览与声音设置、点击直达任务页，以及完成/删除/退出时取消；任务也可交由系统日历应用确认插入，App 不申请读取整个日历的权限。
- 文本消息元数据已修正为随 Signal/Sender Key 密文跨端发送；回复、提及和 AI 辅助标识不再只存在于发送端本地缓存。
- 消息 Reaction 表情回应已基础完成：服务端按消息/用户保存回应，App 长按消息可快速回应，聊天页会展示回应汇总，并通过 WebSocket 同步到聊天成员。
- 贴纸/GIF 已基础完成：输入栏提供表情/贴纸面板和 GIF 文件选择器，内置贴纸带弹性入场动效；贴纸和 GIF 均接入单聊/群聊 E2EE、缓存、失败重试、转发和会话预览。
- 位置共享已基础完成：应用仅在用户主动点击“位置”时申请定位权限，坐标、精度和采集时间通过单聊/群聊 E2EE 发送；新消息不再把本地化标签写入协议，位置气泡按当前语言显示并兼容旧消息；可跳转系统地图，不接入常驻定位或后台追踪。
- “附近的人”已使用真实距离：用户主动开启后只上传约 100 米精度的临时位置，30 分钟自动过期；服务端不会向其他用户返回坐标，并过滤双向屏蔽关系。
- 已加入 `signal-sender-key-v1` envelope 识别/构造与分发骨架；App 内加人/移除成员成功后会失效本地群 Sender Key，并在下次发送前重新分发；服务端已维护群成员 `member_revision`，并通过 WebSocket 推送群成员/群资料变更事件，App 会在 revision 增大时即时失效本地 Sender Key；`SK_DIST` 和群消息 envelope 已绑定当前 revision epoch，且 `SK_DIST` 会通过 `signal-multi-device-v1` 逐设备加密给群成员设备；服务端会记录每个 epoch 的目标设备分发状态，群详情页可查看基础密钥状态。
- Signal 本地密钥/会话状态会持久化到加密数据库；损坏的单条 Signal 记录会被隔离删除，不再因为局部损坏清空全部 Signal 状态。
- 客户端已加入基础身份信任状态：首次看到对方身份密钥时本地 TOFU 信任，身份密钥变化时阻止解密并提示核对安全码；聊天页可查看安全码和逐设备身份指纹二维码，扫码匹配后可将对应设备标记为已验证。

当前 E2EE 范围限制：

- 已覆盖单聊文本消息。
- 图片、视频、GIF、语音和文件均已使用加密对象 + 小型密钥/元数据 envelope（AES-256-GCM），服务端只存不透明密文；兼容读取旧版 inline 图片/视频/文件消息。
- `signal-v2` envelope 支持密文类型、设备 ID 与 payload type 字段，并兼容读取旧 `signal-v1` envelope.
- 已对常见解密失败给出区分提示：缺少会话密钥、安全码变化、不支持的加密消息版本或通用解密失败。
- 群聊 Sender Key 已基础接入，可支持当前群聊内容的加密收发；成员加入/移除后的本地轮换触发、服务端 `member_revision` 检测、WebSocket 跨设备群变更通知、envelope epoch 绑定、`SK_DIST` 逐设备加密 fanout、基础分发状态 UI、手动重新分发入口、每 epoch 一次轻量自动补偿、SQLCipher 本地持久失败重试队列、WorkManager 后台重试和按时间/消息量定期轮换已接入，但仍缺少弱网退避细化、完整群聊安全 UI 和多设备一致性验证。
- `signal-sender-key-v1` envelope 仍属于过渡实现，上线前需要补齐弱网退避细化、必要时的前台服务级重试和多设备一致性验证。
- 已加入基础身份信任/当前设备安全码展示和 QR 扫码核验流程；新生成的安全 QR 使用双方设备身份 key 的 SHA-256 指纹进行核对，不再把安全码本身写入二维码，并兼容旧安全码二维码；本地信任记录已按设备 ID 建模。服务端已提供设备列表、逐设备 PreKey bundle、设备命名、移除本人设备和新设备登录确认接口；账号与安全页可查看当前/其他设备、重命名设备、批准/拒绝待确认设备并移除非当前设备。单聊消息会同时加密给对方已确认设备和发送者自己的其他已确认设备，服务端也会把新增/编辑/撤回/删除事件同步给发送者账号的其他在线连接；签名式身份证明和完整真机多端一致性验证仍需继续完善。

## 当前架构说明

- Android：Kotlin + Jetpack Compose + Room + OkHttp/WebSocket + libsignal + WebRTC 基础模块。
- Server：Ktor + JWT + Exposed + H2/可配置数据库 + WebSocket。

## License

本项目采用 [Apache License 2.0](LICENSE)。
