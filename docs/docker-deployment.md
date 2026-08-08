# Docker 部署指南

**用途**：生产环境 Docker Compose 部署 Maodouchat 后端。
**关联**：`docker-compose.yml`、`.env.docker.example`、`deploy/Caddyfile`、`scripts/deploy.sh`（Windows：`scripts/deploy.ps1`）。

---

## 1. 最快部署（推荐）：一条命令

在服务器上（仓库根目录）：

```bash
# 标准生产部署（需要 SMTP + TURN）
bash scripts/deploy.sh --host chat.example.com --email admin@example.com

# 自托管宽松模式：无 SMTP / TURN 也能起
#（验证码打印到日志 DEV_LOG_CODES=true；通话降级为仅 STUN）
bash scripts/deploy.sh --host chat.example.com --email admin@example.com --relaxed

# 宽松模式 + 首个注册用户自动成为主管理员（免去手动配 MASTER_ADMINS）
bash scripts/deploy.sh --host chat.example.com --email admin@example.com --relaxed --bootstrap-admin
```

脚本自动完成：

1. 创建/补全 `.env`（幂等：只补缺失键，不改已有值）
2. 自动生成全部强随机密钥（`JWT_SECRET` / `PUSH_HMAC_SECRET` / `POSTGRES_PASSWORD` / `TURN_SHARED_SECRET`）
3. 域名解析检查（不阻断，仅提示）
4. `docker compose up -d --build` 启动
5. 轮询 `https://<host>/health/ready` 直到就绪（最多 300s）
6. 打印管理后台入口与管理员配置指引

只想先审查配置、不启动容器（生成密钥后可人工检查 `.env`）：

```bash
bash scripts/deploy.sh --host chat.example.com --email you@example.com --dry-run
```

`--dry-run` 生成/修复 `.env` 并打印脱敏配置摘要（密钥前 6 位 + 长度），然后退出。

部署前想先只读体检（不创建/修改任何文件，检查后直接退出）：

```bash
bash scripts/deploy.sh --doctor
```

`--doctor` 预检逐项报告 `[PASS] / [FAIL] / [WARN]`：docker / docker compose 环境、`.env` 就绪性、密钥强度（JWT_SECRET / POSTGRES_PASSWORD / PUSH_HMAC_SECRET）、域名 DNS 解析与 ACME 邮箱、`BASE_URL` 与 `PUBLIC_HOST` 一致性、`docker compose config` 可解析性；存在 `[FAIL]` 时退出码为 1。适合首次部署前先跑一遍排查环境问题。`BASE_URL` 与 `PUBLIC_HOST` 不一致时（如 `BASE_URL=https://a.example.com` 但 `PUBLIC_HOST=b.example.com`），部署会直接 fail——否则 App 客户端会连错域名。

查看部署/更新工具版本（排障核对代码版本）：

```bash
bash scripts/deploy.sh --version   # deploy 工具版本（git describe）
bash scripts/update.sh --version   # 当前代码版本 + origin 目标版本
```

Windows 服务器 / Docker Desktop 用等价脚本：

```powershell
./scripts/deploy.ps1 -Host chat.example.com -Email admin@example.com -Relaxed -BootstrapAdmin
```

## 2. 手动配置（可选）

如不使用脚本，复制 `.env.docker.example` 为 `.env` 并修改：

- `BASE_URL`：必须为 `https://...`
- `JWT_SECRET`：≥32 字符随机串（`openssl rand -base64 48`）
- `POSTGRES_PASSWORD`：随机串
- `MASTER_ADMINS`：管理后台用户 ID（或启用 `BOOTSTRAP_FIRST_USER_AS_ADMIN=true` 让首个注册用户自动成为管理员）
- SMTP（标准模式必填；宽松模式可留空）：`SMTP_HOST / SMTP_PORT / SMTP_USER / SMTP_PASS`
- TURN（标准模式必填；宽松模式可留空）：`TURN_URLS / TURN_SHARED_SECRET / TURN_REALM`（与 coturn 共享同一 `TURN_SHARED_SECRET`）
- `PUSH_HMAC_SECRET`：推送签名密钥（≥32 字符；标准生产必填）

启动：

```bash
docker compose up -d --build
```

## 3. 部署模式

| 模式 | 配置 | 说明 |
|------|------|------|
| 标准生产 | `RELAXED_VERIFICATION=false`（默认） | 强制 HTTPS BASE_URL、强 JWT_SECRET、持久化数据库、关闭演示用户、SMTP、TURN、PUSH_HMAC_SECRET |
| 宽松自托管 | `RELAXED_VERIFICATION=true` | 允许无 SMTP（验证码打日志，需 `DEV_LOG_CODES=true`）、无 TURN（通话仅 STUN）、无 PUSH_HMAC（推送签名校验降级）。**核心安全校验（JWT/HTTPS/持久化 DB/禁演示用户）仍然强制** |

> 宽松模式仅推荐个人/小团队自托管使用；面向公众服务仍应使用标准生产模式。

## 4. 首次管理员引导

两种方式任选：

**方式 A（推荐，一键）**：`.env` 中设 `BOOTSTRAP_FIRST_USER_AS_ADMIN=true` → 启动后注册第一个账号（自动成为主管理员）→ 引导完成后设回 `false` 并重新部署。注意：此开关只对**数据库中的第一个用户**生效，之后注册的用户不会自动成为管理员。

**方式 B（手动）**：注册账号 → 查询用户 ID：

```bash
docker compose exec -T db psql -U maodouchat -d maodouchat -c "SELECT id,email FROM users"
```

→ 写入 `.env` 的 `MASTER_ADMINS=<user-id>` → 重启服务。

完成后访问 `https://<域名>/admin`。生产环境必须使用 HTTPS，不要通过公网明文 HTTP 输入管理员密码。

## 5. 健康检查

```bash
curl https://your.domain.com/health/live   # 存活探针
curl https://your.domain.com/health/ready  # 就绪探针
```

## 6. 备份与恢复

```bash
# 一致性备份（含 PostgreSQL + 密文附件 + Caddy TLS + SHA-256 清单）
bash scripts/backup-production.sh

# 保留份数覆盖（默认 14；1.187：--keep 优先级最高）
bash scripts/backup-production.sh --keep 30

# 1.285：备份前自动做磁盘空间预检（默认需 ≥1024MB 可用，BACKUP_MIN_FREE_MB 可覆盖）
BACKUP_MIN_FREE_MB=2048 bash scripts/backup-production.sh

# 显式确认恢复（交互终端需输入 yes；非交互/CI 用 CONFIRM_RESTORE=yes 跳过提示）
bash scripts/restore-production.sh --confirm /path/to/maodouchat-backup
CONFIRM_RESTORE=yes bash scripts/restore-production.sh --confirm /path/to/maodouchat-backup

# 1.191：恢复前只校验备份（完整性 + SHA-256 + 归档路径安全），不写入
bash scripts/restore-production.sh --dry-run /path/to/maodouchat-backup

# 1.239/1.308/1.336/1.359：列出已有备份（含年龄与工具版本，审计溯源）
bash scripts/backup-production.sh --list
```

## 6.1 一键状态查看（1.166 新增）

```bash
# 服务状态 + 健康检查 + 最新备份一览（只读）
bash scripts/status.sh

# 机器可读 JSON 输出（便于监控脚本/定时任务）
bash scripts/status.sh --json

# 一行摘要（适合 cron 巡检；1.206 新增）
bash scripts/status.sh --short

# 仅按健康状态退出（0=健康 1=不健康，适合监控/cron；1.212 新增）
bash scripts/status.sh --health-check

# 持续监控（默认 5s 刷新，STATUS_WATCH_INTERVAL 可调；1.179 新增）
bash scripts/status.sh --watch
```

## 7. 日志

```bash
docker compose logs -f server
docker compose logs -f caddy
```

## 8. 更新

一键更新（git pull + 复用镜像重启，需要已部署过的 `.env`）：

```bash
bash scripts/update.sh
# 1.189：跳过更新前备份 / 跳过离线拓扑校验（紧急修复时加速）
bash scripts/update.sh --skip-backup --skip-verify
```

手动方式：

```bash
git pull
bash scripts/deploy.sh --no-build   # 复用镜像，快速重启
# 或 docker compose up -d --build
```

数据库迁移在服务启动时自动执行（Exposed `SchemaUtils`）。
