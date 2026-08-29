# Maodouchat

Maodouchat（毛豆聊天）是端到端加密的 Android 即时通讯应用：Jetpack Compose 客户端 + Ktor 后端。覆盖单聊/群聊、动态、音视频通话、Signal 协议 E2EE、加密附件、可选 AI 能力与管理后台。

功能清单与完整度见 [`docs/feature-inventory.md`](docs/feature-inventory.md)，Bot API 见 [`docs/bot-developer-api.md`](docs/bot-developer-api.md)。

## 技术栈

| 层 | 技术 |
|----|------|
| 客户端 | Kotlin、Jetpack Compose、Room + SQLCipher、WorkManager |
| 实时 | WebSocket `/ws` + REST 补偿 |
| 加密 | libsignal-client（1:1 会话 + 群 Sender Key）、附件 AES-256-GCM |
| 通话 | WebRTC（1:1 + 群 mesh）、TURN 短期凭据 |
| 服务端 | Ktor、Exposed、JWT、PostgreSQL |
| 部署 | Docker Compose（Caddy + Postgres + server） |

## 环境要求

- **JDK 21**：本仓库 Gradle 构建强制要求；不要用 JDK 25 跑 Gradle。
- Android Studio / Android SDK，`compileSdk = 37`。

常用命令：

```bash
# App
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest

# Server（独立工程）
cd server && ../gradlew compileKotlin test

# 官网端到端测试（需本地 Ktor 已启动）
npm run test:website
```

macOS 可用 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` 设置 JDK；Windows 用 `source scripts/use-jdk21.sh` 或在 `~/.gradle/gradle.properties` 配置 `org.gradle.java.home`。

## 后端配置

```bash
cp .env.example .env
```

| 变量 | 说明 |
|------|------|
| `APP_ENV` | 设为 `production` 时启用生产校验 |
| `HOST` / `PORT` | 监听地址，默认 `0.0.0.0:8080` |
| `BASE_URL` | 文件 URL 基础地址；生产必须 HTTPS |
| `JWT_SECRET` | 生产必须 ≥32 字符随机串 |
| `DATABASE_URL` / `DATABASE_DRIVER` | 开发默认 H2 内存库；生产必须 PostgreSQL |
| `STORAGE_DIR` | 上传目录，默认 `./uploads` |
| `USER_STORAGE_QUOTA_BYTES` | 单用户存储配额（1GB~1TB，默认 20GB） |
| `SEED_DEMO_USERS` | 演示用户开关；生产必须关闭 |
| `MODERATOR_EMAILS` | 内容审核员邮箱，授予 App 内审核能力 |
| `MASTER_ADMINS` | 主管理员用户 ID，授予完整管理后台权限 |
| `SMTP_*` | 邮箱验证码；未配置时验证码打印到控制台 |
| `OPENAI_API_KEY` 等 | 仅动态/评论审核可选；聊天明文推理在客户端自配模型 |

生产模式启动前强制校验：HTTPS `BASE_URL`、强 JWT 密钥、持久化数据库、关闭演示用户、SMTP 与 TURN 配置；不满足直接拒绝启动。自托管可加 `--relaxed` 放宽 SMTP/TURN。

## 运行后端

本地开发：

```bash
cd server && ../gradlew run
```

Docker 一键部署（自动生成密钥、等待健康检查、打印管理员指引）：

```bash
bash scripts/deploy.sh --host your.domain.com --email you@example.com
```

个人自托管无 SMTP/TURN 时加 `--relaxed`；首个注册账号自动成为主管理员加 `--bootstrap-admin`；Windows 用 `scripts/deploy.ps1`。详见 [`docs/self-host-quickstart.md`](docs/self-host-quickstart.md) 与 [`docs/docker-deployment.md`](docs/docker-deployment.md)。

生产探针：`/health/live`、`/health/ready`。备份恢复用 `scripts/backup-production.sh` / `restore-production.sh`。

## 管理后台

配置 `MASTER_ADMINS` 并重启后，访问 `https://<域名>/<ADMIN_PATH>/admin`（部署时生成随机前缀）。后台登录需二次确认密码，换取 5 分钟用途限定 Token；Token 仅存内存，到期强制退出。主管理员自动继承审核员权限，反之不成立。

## 构建 Android

Debug 默认连接模拟器宿主机（`http://10.0.2.2:8080`）；真机调试改为局域网 IP：

```bash
./gradlew :app:assembleDebug \
  -PMAODOU_API_BASE_URL=http://192.168.1.10:8080 \
  -PMAODOU_WS_URL=ws://192.168.1.10:8080/ws
```

Release 仅允许 HTTPS/WSS 且必须显式指定：

```bash
./gradlew :app:assembleRelease \
  -PMAODOU_RELEASE_API_BASE_URL=https://api.example.com \
  -PMAODOU_RELEASE_WS_URL=wss://api.example.com/ws
```

Release 启用 R8 + 资源收缩，默认仅 arm64-v8a。运行时也可在 App「设置 → 服务器」切换服务器地址，无需重新构建。

安全基线：本地库 SQLCipher + Keystore；access token 15 分钟 + refresh token 轮换吊销；消息正文 E2EE，服务端只存密文与元数据；后台消息走 WebSocket 保活（无 FCM）；Android 备份已关闭并排除敏感目录。

## CI 与发布

[`.github/workflows/ci.yml`](.github/workflows/ci.yml)：Server 编译+测试+管理后台 E2E、App 编译+单测、Compose 配置校验。

推送 `v*` 标签触发 [`release.yml`](.github/workflows/release.yml)，产出签名 APK、服务端发行包与 SHA256SUMS。需要配置 Secrets：`RELEASE_API_BASE_URL`、`RELEASE_WS_URL`、`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

## 文档

- [`docs/feature-inventory.md`](docs/feature-inventory.md) — 功能与完整度台账
- [`docs/bot-developer-api.md`](docs/bot-developer-api.md) — Bot REST API
- [`docs/self-host-quickstart.md`](docs/self-host-quickstart.md) — 自托管快速上手
- [`docs/docker-deployment.md`](docs/docker-deployment.md) — Docker 部署细节

## License

[Apache License 2.0](LICENSE)
