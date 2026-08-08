# 生产拓扑验收清单

**用途**：生产环境部署拓扑的离线/在线校验。
**关联**：`scripts/verify-production-topology.sh`、`docker-compose.yml`、`deploy/Caddyfile`、`docs/release-checklist.md`。

---

## 1. 组件检查

| # | 组件 | 检查 | 通过 |
|---|------|------|------|
| 1 | Caddy | 80/443 监听，自动 TLS | [ ] |
| 2 | Caddy | 安全头（HSTS/CSP/X-Frame） | [ ] |
| 3 | PostgreSQL | 5432 仅容器内网 | [ ] |
| 4 | PostgreSQL | 数据卷持久化 | [ ] |
| 5 | Ktor server | 8080 仅容器内网 | [ ] |
| 6 | TURN | 3478/UDP + 5349/TLS 开放 | [ ] |
| 7 | Uploads | 持久化卷挂载 | [ ] |

## 2. 网络隔离

| # | 检查 | 通过 |
|---|------|------|
| 1 | PostgreSQL 不对外暴露 | [ ] |
| 2 | Ktor 不直接对外暴露（经 Caddy 反代） | [ ] |
| 3 | 管理后台 `/admin` 仅 HTTPS | [ ] |
| 4 | WebSocket `/ws` 经 Caddy WSS | [ ] |

## 3. 安全配置

| # | 检查 | 通过 |
|---|------|------|
| 1 | `APP_ENV=production` | [ ] |
| 2 | `JWT_SECRET` ≥ 32 字符，非默认 | [ ] |
| 3 | `BASE_URL` 为 HTTPS | [ ] |
| 4 | `DATABASE_URL` 为 PostgreSQL（非 H2 mem） | [ ] |
| 5 | `SEED_DEMO_USERS=false` | [ ] |
| 6 | SMTP 已配置 | [ ] |
| 7 | TURN URL + ≥32 共享密钥 | [ ] |
| 8 | `MASTER_ADMINS` 已配置用户 ID | [ ] |
| 9 | `MODERATOR_EMAILS` 按需配置 | [ ] |

## 4. 健康探针

| # | 检查 | 通过 |
|---|------|------|
| 1 | `/health/live` -> 200 | [ ] |
| 2 | `/health/ready` -> 200（DB 连接正常） | [ ] |
| 3 | Caddy 健康检查间隔 + 超时合理 | [ ] |

## 5. 离线校验脚本

```bash
bash scripts/verify-production-topology.sh
```

检查项：
- docker-compose.yml 语法
- 环境变量完整性
- Caddyfile 安全头
- 端口暴露范围
- 数据卷挂载

## 6. 在线校验

| # | 检查 | 通过 |
|---|------|------|
| 1 | `curl https://domain/health/live` -> 200 | [ ] |
| 2 | `curl https://domain/health/ready` -> 200 | [ ] |
| 3 | WSS 连接 `wss://domain/ws` 成功 | [ ] |
| 4 | `/admin` 返回登录页 | [ ] |
| 5 | 文件上传/下载经 HTTPS | [ ] |
| 6 | TURN 连通性测试 | [ ] |

## 7. 容量

| # | 检查 | 通过 |
|---|------|------|
| 1 | PostgreSQL `max_connections` 够用 | [ ] |
| 2 | Ktor `maxConnections` 配置 | [ ] |
| 3 | 磁盘空间 ≥ 附件存储 2 倍 | [ ] |
| 4 | 内存 ≥ JVM 堆 + PostgreSQL shared_buffers | [ ] |

---

## 8. 代码级验证证据（自动化测试 + 本地集成）

以下项已通过本地服务器启动和 Docker Compose 配置校验验证：

| 验证项 | 方法 | 结果 |
|--------|------|------|
| Docker Compose 配置有效性 | `docker compose --env-file .env.docker.example config` | ✅ 配置有效 |
| 服务器启动（H2 开发模式） | `gradlew run` + 环境变量 | ✅ 启动成功 |
| `/health/live` 返回 200 | `curl http://localhost:8080/health/live` | ✅ `{"status":"ok"}` |
| `/health/ready` 返回 200 + DB/存储就绪 | `curl http://localhost:8080/health/ready` | ✅ `{"database":"ok","storage":"ok"}` |
| 用户注册 + JWT 签发 | `POST /api/auth/register` | ✅ 返回 token + refreshToken |
| `/admin` 返回登录页 | `curl http://localhost:8080/admin` | ✅ 返回 HTML |
| Caddy + PostgreSQL + server 容器定义 | `docker compose config` 输出 | ✅ 三服务 + 卷定义正确 |
