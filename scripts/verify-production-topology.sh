#!/usr/bin/env bash
# W5-01 static / optional live checks for Maodouchat production topology.
# Does not claim deployment success without --live green evidence.
set -euo pipefail

workspace="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$workspace"

mode="${1:-}"
target_url="${2:-}"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "OK: $*"; }

require_file() {
  [[ -f "$1" ]] || fail "missing file: $1"
  ok "file $1"
}

offline_checks() {
  require_file "docker-compose.yml"
  require_file "deploy/Caddyfile"
  require_file ".env.docker.example"
  require_file "server/Dockerfile"
  require_file "docs/docker-deployment.md"
  require_file "scripts/backup-production.sh"
  require_file "scripts/restore-production.sh"
  require_file "scripts/admin-e2e.mjs"
  require_file "scripts/deploy.sh"

  grep -q 'health/ready' docker-compose.yml || fail "compose healthcheck missing /health/ready"
  ok "compose healthcheck references /health/ready"

  # 1.288：proxy 应有自身健康检查（Caddy 进程存活探针；admin API 关闭后不能再用 2019 探测）
  grep -q 'pidof caddy' docker-compose.yml || fail "compose proxy healthcheck missing (Caddy process liveness)"
  ok "compose proxy healthcheck uses Caddy process liveness"

  # 1.253：db 健康检查应基于 pg_isready，server 依赖 db healthy 才启动
  grep -q 'pg_isready' docker-compose.yml || fail "compose db healthcheck missing pg_isready"
  ok "compose db healthcheck uses pg_isready"

  # 1.258：关键密钥必须通过 .env 注入（含 :? 强制校验，缺省拒绝启动）
  for var in POSTGRES_PASSWORD JWT_SECRET PUSH_HMAC_SECRET; do
    grep -q "\${$var:?\|${var}:" docker-compose.yml || fail "compose missing required env $var"
  done
  ok "compose requires critical secrets via .env"

  # 1.259：数据持久化应使用命名卷（容器重建不丢数据）
  grep -q 'postgres_data:/var/lib/postgresql' docker-compose.yml || fail "compose missing postgres named volume"
  grep -q 'server_uploads:/app/uploads' docker-compose.yml || fail "compose missing uploads named volume"
  ok "compose persists db + uploads via named volumes"

  # 1.261：server 日志应轮转（防磁盘写满）
  grep -q 'max-size' docker-compose.yml || fail "compose server logging missing max-size"
  ok "compose server log rotation configured"

  # 1.263：server 应启用 init（僵尸进程回收）
  grep -q 'init: true' docker-compose.yml || fail "compose server missing init: true"
  ok "compose server uses init: true"

  # 1.278：启动顺序应通过健康检查门控（proxy→server、server→db）
  if (( "$(grep -c 'condition: service_healthy' docker-compose.yml)" < 2 )); then
    fail "compose should health-gate service startup (depends_on service_healthy)"
  fi
  ok "compose health-gates service startup"

  # 1.264：容器应以非 root 用户运行（最小权限）
  grep -q '^USER maodou' server/Dockerfile || fail "server Dockerfile must run as non-root user"
  ok "server runs as non-root user"

  # 1.363：Dockerfile 应为多阶段构建（build→JRE 精简运行镜像，减小镜像体积与攻击面）
  grep -q 'AS build' server/Dockerfile || fail "server Dockerfile must use multi-stage build"
  grep -q 'jre-jammy' server/Dockerfile || fail "server Dockerfile runtime must be JRE (not JDK)"
  ok "server Dockerfile multi-stage (build→JRE)"

  # 1.277：server Dockerfile EXPOSE 应与 compose server 端口一致（8080）
  grep -q 'EXPOSE 8080' server/Dockerfile || fail "server Dockerfile must EXPOSE 8080"
  # 1.348：compose server expose 也应与 Dockerfile EXPOSE 一致（两侧同源防漂移）
  grep -q 'expose:' docker-compose.yml || fail "compose server missing expose block"
  if ! grep -A2 'expose:' docker-compose.yml | grep -q '8080'; then
    fail "compose server expose must include 8080 (matches Dockerfile EXPOSE)"
  fi
  ok "server Dockerfile EXPOSE 8080 matches compose"

  # 1.265：compose 容器加固（read_only + cap_drop ALL + no-new-privileges）
  grep -q 'read_only: true' docker-compose.yml || fail "compose server missing read_only"
  grep -q 'cap_drop' docker-compose.yml || fail "compose server missing cap_drop"
  grep -q 'no-new-privileges:true' docker-compose.yml || fail "compose server missing no-new-privileges"
  ok "compose server hardened (read_only/cap_drop/no-new-privileges)"

  # 1.266：对外端口应通过 env 可配置（HTTP_PORT/HTTPS_PORT），非硬编码
  grep -q '\${HTTP_PORT' docker-compose.yml || fail "compose proxy missing HTTP_PORT env port"
  grep -q '\${HTTPS_PORT' docker-compose.yml || fail "compose proxy missing HTTPS_PORT env port"
  ok "compose proxy ports configurable via env"

  # 1.254：服务应配置 restart: unless-stopped（异常退出自动拉起）
  restart_count="$(grep -c 'restart: unless-stopped' docker-compose.yml)"
  if (( restart_count >= 3 )); then
    ok "compose services use restart: unless-stopped (x$restart_count)"
  else
    fail "expected restart: unless-stopped on services (found $restart_count)"
  fi

  grep -q 'reverse_proxy server:8080' deploy/Caddyfile || fail "Caddyfile missing reverse_proxy to server:8080"
  ok "Caddy reverse_proxy -> server:8080"

  grep -q 'Strict-Transport-Security' deploy/Caddyfile || fail "Caddyfile missing HSTS"
  ok "Caddy HSTS header"

  grep -q 'expose:' docker-compose.yml || fail "server should expose 8080 not publish publicly by default"
  if grep -E '^\s+ports:\s*$' -A3 docker-compose.yml | grep -q '8080:8080'; then
    fail "server must not publish 8080:8080 on the public host"
  fi
  ok "server 8080 not published as host port mapping"

  # Compose must pass PUSH_HMAC_SECRET (production refuses to start with the dev default)
  grep -q 'PUSH_HMAC_SECRET' docker-compose.yml || fail "compose missing PUSH_HMAC_SECRET passthrough"
  ok "compose passes PUSH_HMAC_SECRET"

  # 隐藏后台路径必须透传，否则 Caddyfile 无法解析随机前缀
  grep -q 'ADMIN_PATH:' docker-compose.yml || fail "compose missing ADMIN_PATH passthrough"
  ok "compose passes ADMIN_PATH"

  # 1.361：compose 应透传治理/管理员/注册 env（MODERATOR_EMAILS/MASTER_ADMINS/ALLOW_REGISTRATION/DEVELOPER_USER_IDS）
  for key in MODERATOR_EMAILS MASTER_ADMINS ALLOW_REGISTRATION DEVELOPER_USER_IDS; do
    grep -q "^      ${key}:" docker-compose.yml || fail "compose missing ${key} passthrough"
  done
  ok "compose passes governance/admin env"

  # 1.256：deploy.sh 应支持文档承诺的关键 flags
  for flag in --no-build --skip-health-wait --health-timeout --dry-run --relaxed; do
    grep -q -- "--${flag#--}" scripts/deploy.sh || fail "deploy.sh missing documented flag $flag"
  done
  # 1.281：--doctor 只读预检模式
  grep -q -- '--doctor' scripts/deploy.sh || fail "deploy.sh missing --doctor preflight mode"
  # 1.371：doctor 应校验 docker 守护进程在运行（compose version 不连 daemon）
  grep -q 'docker daemon' scripts/deploy.sh || fail "deploy.sh --doctor missing daemon liveness check"
  # 1.292：deploy 就绪后应校验容器健康状态（捕捉 unhealthy 潜伏问题）
  grep -q 'unhealthy_services' scripts/deploy.sh || fail "deploy.sh missing post-ready container health check"
  # 1.369：deploy 健康等待应对本地部署（localhost/IP）回退 http+自签（与 status.sh 1.367 一致）
  grep -q 'health_scheme' scripts/deploy.sh || fail "deploy.sh missing scheme-aware health wait"
  # 1.303：deploy 应校验 BASE_URL 与 PUBLIC_HOST 一致（防 App 连错域名）
  grep -q 'does not match PUBLIC_HOST' scripts/deploy.sh || fail "deploy.sh missing BASE_URL/PUBLIC_HOST consistency check"
  # 1.312：deploy --dry-run 应展示管理员/注册配置（首次部署易遗漏）
  grep -q 'MASTER_ADMINS' scripts/deploy.sh || fail "deploy.sh --dry-run missing MASTER_ADMINS summary"
  grep -q 'ALLOW_REGISTRATION' scripts/deploy.sh || fail "deploy.sh --dry-run missing ALLOW_REGISTRATION summary"
  # 1.313：--doctor 应校验非 relaxed 模式 SMTP 已配置（验证码邮件前提）
  grep -q 'SMTP_HOST unset in non-relaxed' scripts/deploy.sh || fail "deploy.sh --doctor missing SMTP non-relaxed check"
  # 1.318：--doctor 应把 --relaxed CLI 标志视为等效 relaxed 模式（首次预检不误报 SMTP）
  grep -q '"\$RELAXED" == "true"' scripts/deploy.sh || fail "deploy.sh --doctor missing --relaxed CLI handling"
  # 1.339：deploy 应校验 ACME_EMAIL 邮箱格式（Caddy 报错晦涩，提前拦截）
  grep -q 'does not look like a valid email' scripts/deploy.sh || fail "deploy.sh missing ACME_EMAIL format check"
  # 1.340：deploy 应校验 POSTGRES_PASSWORD 强度（弱口令/占位即安全风险）
  grep -q 'POSTGRES_PASSWORD must be at least' scripts/deploy.sh || fail "deploy.sh missing POSTGRES_PASSWORD strength check"
  # 1.341：非 localhost 部署 BASE_URL 必须 https（Caddy 自动 HTTPS）
  grep -q 'must use https://' scripts/deploy.sh || fail "deploy.sh missing BASE_URL https-scheme check"
  # 1.342：--doctor 应校验 DATABASE_POOL_SIZE 数值范围（2..128）
  grep -q 'DATABASE_POOL_SIZE' scripts/deploy.sh || fail "deploy.sh --doctor missing DATABASE_POOL_SIZE range check"
  # 1.347：--doctor 应校验 SMTP_PORT 与 TURN_CREDENTIAL_TTL_SECONDS 数值
  grep -q 'SMTP_PORT=' scripts/deploy.sh || fail "deploy.sh --doctor missing SMTP_PORT range check"
  grep -q 'TURN_CREDENTIAL_TTL_SECONDS' scripts/deploy.sh || fail "deploy.sh --doctor missing TURN TTL check"
  # 1.350：deploy 应校验 PUBLIC_HOST 为裸域名（无 scheme/path/空格/端口）
  grep -q 'expected bare hostname' scripts/deploy.sh || fail "deploy.sh missing PUBLIC_HOST format check"
  # 1.351：--doctor 应校验管理员配置完整性（MASTER_ADMINS 占位/未 bootstrap）
  grep -q 'MASTER_ADMINS placeholder' scripts/deploy.sh || fail "deploy.sh --doctor missing MASTER_ADMINS check"
  # 1.354：--doctor 应提示非 relaxed 模式 TURN 缺失（通话 STUN-only 降级）
  grep -q 'TURN_URLS unset in non-relaxed' scripts/deploy.sh || fail "deploy.sh --doctor missing TURN non-relaxed check"
  # 1.357：--doctor 应校验 CORS_ORIGINS 为逗号分隔源（防拼写错误致 web 被拦截）
  grep -q 'CORS_ORIGINS' scripts/deploy.sh || fail "deploy.sh --doctor missing CORS_ORIGINS check"
  # 1.358：--doctor 应校验上传/配额限制数值（MAX_* / USER_STORAGE_QUOTA_BYTES）
  grep -q 'USER_STORAGE_QUOTA_BYTES' scripts/deploy.sh || fail "deploy.sh --doctor missing upload-limit numeric checks"
  ok "deploy.sh documents/supports core flags (incl. --doctor)"

  # 1.262：backup/restore/update 脚本应支持文档承诺的 flags
  for flag in --keep --no-prune --tag --dry-run --list; do
    grep -q -- "--${flag#--}" scripts/backup-production.sh || fail "backup-production.sh missing documented flag $flag"
  done
  # 1.285：备份磁盘空间预检（BACKUP_MIN_FREE_MB 可配置）
  grep -q 'BACKUP_MIN_FREE_MB' scripts/backup-production.sh || fail "backup-production.sh missing disk-space preflight (BACKUP_MIN_FREE_MB)"
  # 1.308：backup --list 应显示备份年龄（新鲜度监控）
  grep -q 'age=' scripts/backup-production.sh || fail "backup-production.sh --list missing backup age"
  # 1.322：backup --tag 应仅允许安全字符（目录名安全，防路径穿越/空白注入）
  grep -q 'A-Za-z0-9_-' scripts/backup-production.sh || fail "backup-production.sh --tag missing safe-char validation"
  # 1.326：backup --dry-run 也应报告磁盘空间（部署前一次性发现空间不足）
  grep -q 'dry_free_mb' scripts/backup-production.sh || fail "backup-production.sh --dry-run missing disk space check"
  # 1.336：备份 METADATA 应记录工具版本与源主机（恢复/审计溯源）
  grep -q 'backup_tool_version' scripts/backup-production.sh || fail "backup-production.sh METADATA missing backup_tool_version"
  grep -q 'backup_hostname' scripts/backup-production.sh || fail "backup-production.sh METADATA missing backup_hostname"
  # 1.356：备份工具版本应跟随仓库版本（防硬编码过期）
  grep -q 'backup-tool@\${code_version}' scripts/backup-production.sh || fail "backup-production.sh backup_tool_version must derive from code_version"
  # 1.359：backup --list 应附备份工具版本（审计溯源）
  grep -q 'tool=' scripts/backup-production.sh || fail "backup-production.sh --list missing backup tool version"
  for flag in --confirm --dry-run --inspect; do
    grep -q -- "--${flag#--}" scripts/restore-production.sh || fail "restore-production.sh missing documented flag $flag"
  done
  # 1.294：破坏性恢复必须带交互确认（防误操作覆盖当前数据）
  grep -q 'CONFIRM_RESTORE' scripts/restore-production.sh || fail "restore-production.sh missing CONFIRM_RESTORE guard"
  # 1.305：restore 应校验备份 format_version 兼容性（防止新备份格式硬套旧恢复器）
  grep -q 'format_version' scripts/restore-production.sh || fail "restore-production.sh missing format_version compatibility check"
  # 1.325：restore --inspect 应附备份元数据（created/version/format），便于核对
  grep -q '备份元数据' scripts/restore-production.sh || fail "restore-production.sh --inspect missing backup metadata"
  # 1.364：restore --inspect 应附备份工具版本与源主机（审计溯源）
  grep -q 'backup_hostname' scripts/restore-production.sh || fail "restore-production.sh --inspect missing backup hostname"
  # 1.338：restore 健康等待的 PUBLIC_HOST 读取应容忍 .env 缺失（set -e 下防静默退出）
  grep -q '2>/dev/null | head -n1 || true' scripts/restore-production.sh || fail "restore-production.sh health-wait missing .env tolerance"
  # 1.346：restore --dry-run 应暴露 format_version 不兼容（避免停服后才发现）
  grep -q 'format_version' scripts/restore-production.sh || fail "restore-production.sh dry-run missing format_version check"
  # 1.370：restore 应在停服前校验 uploads/caddy tar 可读（防清空目录后才发现损坏）
  grep -q 'gzip -t' scripts/restore-production.sh || fail "restore-production.sh missing pre-restore tar validation"
  # 1.370：restore 健康等待应 scheme 感知（与 status.sh/deploy.sh 一致）
  grep -q 'health_scheme' scripts/restore-production.sh || fail "restore-production.sh missing scheme-aware health wait"
  grep -q -- '--skip-backup' scripts/update.sh || fail "update.sh missing --skip-backup"
  # 1.298：update.sh 应带 git 检出守卫 + --version
  grep -q 'is-inside-work-tree' scripts/update.sh || fail "update.sh missing git working-tree guard"
  grep -q -- '--version' scripts/update.sh || fail "update.sh missing --version flag"
  # 1.311：update.sh 应识别「已是最新」并跳过重建
  grep -q 'Already up to date' scripts/update.sh || fail "update.sh missing no-op pull detection"
  # 1.328：update.sh 应打印本次更新提交摘要（运维了解变更内容）
  grep -q 'Commits in this update' scripts/update.sh || fail "update.sh missing commit summary"
  # 1.345：update.sh 应警告未提交改动（阻挡 pull 或镜像与 HEAD 不一致）
  grep -q 'uncommitted changes' scripts/update.sh || fail "update.sh missing dirty-tree warning"
  # 1.365：update.sh 应预检 origin 远程与上游跟踪分支（缺失时 git pull 报错晦涩）
  grep -q 'remote get-url origin' scripts/update.sh || fail "update.sh missing origin preflight"
  grep -q 'upstream tracking branch' scripts/update.sh || fail "update.sh missing upstream preflight"
  ok "backup/restore/update scripts support documented flags"

  for key in PUBLIC_HOST ACME_EMAIL BASE_URL JWT_SECRET POSTGRES_PASSWORD PUSH_HMAC_SECRET ADMIN_PATH \
             RELAXED_VERIFICATION BOOTSTRAP_FIRST_USER_AS_ADMIN SMTP_HOST TURN_URLS TURN_SHARED_SECRET; do
    grep -q "^$key=" .env.docker.example || fail ".env.docker.example missing $key"
  done
  # 1.327：管理/治理/AI/FCM 键也应在示例 env 中声明（配置漂移防护）
  for key in MODERATOR_EMAILS MASTER_ADMINS ALLOW_REGISTRATION DEVELOPER_USER_IDS \
             EMAIL_DOMAIN_BLOCKLIST OPENAI_API_KEY OPENAI_MODEL FCM_PROJECT_ID; do
    grep -q "^$key=" .env.docker.example || fail ".env.docker.example missing $key"
  done
  ok ".env.docker.example has required production keys"

  # 1.324：运维可调项（备份保留/磁盘预检/健康超时/status 刷新间隔）应在 .env.docker.example 有文档注释
  for key in BACKUP_KEEP BACKUP_MIN_FREE_MB HEALTH_TIMEOUT_SECONDS STATUS_WATCH_INTERVAL; do
    grep -q "^# $key=" .env.docker.example || fail ".env.docker.example missing documented ops key $key"
  done
  ok ".env.docker.example documents ops tunables"

  # Caddyfile must not hardcode the project's own public domain
  if grep -q 'chat.mdou.me' deploy/Caddyfile; then
    fail "deploy/Caddyfile must not hardcode chat.mdou.me"
  fi
  ok "Caddyfile uses only \$PUBLIC_HOST"

  # 1.252：Caddyfile 应启用响应压缩（zstd/gzip），节省带宽
  if grep -Eq '^\s*encode\s+(zstd\s+)?gzip' deploy/Caddyfile; then
    ok "Caddyfile enables response compression (zstd/gzip)"
  else
    fail "Caddyfile missing 'encode zstd gzip' — bandwidth/performance regression"
  fi

  # 1.255：Caddyfile 安全响应头（nosniff / DENY frame）
  grep -q 'X-Content-Type-Options "nosniff"' deploy/Caddyfile || fail "Caddyfile missing nosniff header"
  grep -q 'X-Frame-Options "DENY"' deploy/Caddyfile || fail "Caddyfile missing X-Frame-Options DENY"
  # 1.319：补充安全头回归（HSTS 子域 / Referrer-Policy / Server 移除 / 管理页权限策略）
  grep -q 'includeSubDomains' deploy/Caddyfile || fail "Caddyfile missing HSTS includeSubDomains"
  grep -q 'Referrer-Policy' deploy/Caddyfile || fail "Caddyfile missing Referrer-Policy"
  grep -q '\-Server' deploy/Caddyfile || fail "Caddyfile missing Server header removal"
  grep -q 'Permissions-Policy' deploy/Caddyfile || fail "Caddyfile missing admin Permissions-Policy"
  ok "Caddyfile security headers present"

  # 1.257：Caddyfile 应限制请求体大小（防上传滥用）
  grep -q 'request_body' deploy/Caddyfile || fail "Caddyfile missing request_body max_size"
  ok "Caddyfile limits request body size"

  # 1.271：Caddyfile 应开启访问日志（排障可观测性）
  grep -q '^\s*log\s*{' deploy/Caddyfile || fail "Caddyfile missing access log block"
  ok "Caddyfile access log enabled"

  # 1.275：Caddyfile 应统一错误为 JSON（避免泄漏默认 HTML）
  grep -q 'handle_errors' deploy/Caddyfile || fail "Caddyfile missing handle_errors"
  ok "Caddyfile returns JSON errors"

  # 1.279：Caddyfile 应配置 ACME email（启用自动 HTTPS）
  grep -q 'email {$ACME_EMAIL}' deploy/Caddyfile || fail "Caddyfile missing ACME email (auto-HTTPS)"
  ok "Caddyfile auto-HTTPS via ACME"

  # 1.362：Caddyfile 应关闭管理 API（admin off —— 防远程配置访问/泄漏）
  grep -q 'admin off' deploy/Caddyfile || fail "Caddyfile must disable admin API (admin off)"
  ok "Caddyfile disables admin API"

  # 1.373：Caddyfile 应隐藏 /admin 并只放行随机 ADMIN_PATH 前缀
  grep -q 'respond @admin 404' deploy/Caddyfile || fail "Caddyfile must return 404 for /admin"
  grep -q 'ADMIN_PATH' deploy/Caddyfile || fail "Caddyfile missing ADMIN_PATH hidden admin route"
  ok "Caddyfile hides /admin behind ADMIN_PATH"

  # 1.276：部署文档应说明 status.sh 的监控 flags（--json/--short/--health-check/--watch）
  for flag in --json --short --health-check --watch; do
    grep -q -- "--${flag#--}" docs/docker-deployment.md || fail "docs/docker-deployment.md missing status.sh $flag"
  done
  ok "docs document status.sh monitoring flags"

  # 1.302：status.sh --health-check 应同时扫描 compose unhealthy 服务（API 就绪但服务退化也要告警）
  grep -q 'unhealthy' scripts/status.sh || fail "status.sh --health-check missing unhealthy-service scan"
  # 1.306：status.sh --short 一行摘要应含 unhealthy 计数（与 --json/--health-check 一致）
  grep -q 'services_unhealthy' scripts/status.sh || fail "status.sh --short missing services_unhealthy count"
  # 1.334：status.sh --short 应含备份年龄（与 --json age_hours 一致）
  grep -q 'latest_backup_age' scripts/status.sh || fail "status.sh --short missing latest_backup_age"
  # 1.344：--watch --json 组合应保持逐行合法 JSON（分隔线会破坏下游解析）
  grep -q 'suppress_sep' scripts/status.sh || fail "status.sh --watch missing --json separator suppression"
  # 1.367：status.sh 健康探测应对本地部署（localhost/IP）回退 http+自签（否则本地健康检查必失败）
  grep -q 'health_scheme' scripts/status.sh || fail "status.sh missing scheme-aware health check"

  # 1.300：部署文档应覆盖 deploy/restore/update 的关键 flags/开关（--doctor/CONFIRM_RESTORE/--dry-run）
  for token in --doctor CONFIRM_RESTORE --dry-run --version --list; do
    grep -q -- "$token" docs/docker-deployment.md || fail "docs/docker-deployment.md missing $token"
  done
  ok "docs document deploy/restore/update key flags"

  # 1.218：离线回归——用户屏蔽路由在服务端源码中存在（App 1.172 依赖）
  if rg -q 'api/users/block/' server/src 2>/dev/null || grep -rq 'users/block' server/src; then
    ok "server source defines /api/users/block/{userId}"
  else
    fail "server source missing /api/users/block/{userId} route (App 1.172 depends on it)"
  fi

  # 1.296：离线回归——群成员路由在服务端源码中存在（App 群详情依赖 getGroupMembers）
  if rg -q 'getGroupMembers' server/src 2>/dev/null || grep -rq 'getGroupMembers' server/src; then
    ok "server source defines group members route (getGroupMembers)"
  else
    fail "server source missing group members handler (getGroupMembers) — App 群详情依赖"
  fi

  # 1.316：离线回归——群投票路由在服务端源码中存在（App 群玩法依赖 isPollsEnabled 门控）
  if rg -q 'isPollsEnabled' server/src 2>/dev/null || grep -rq 'isPollsEnabled' server/src; then
    ok "server source defines group polls route (isPollsEnabled gate)"
  else
    fail "server source missing group polls handler (isPollsEnabled) — App 群玩法依赖"
  fi

  # 1.335：离线回归——群签到路由在服务端源码中存在（App 群玩法依赖 group_n 表/签到处理）
  if rg -q 'group_n' server/src 2>/dev/null || grep -rq 'group_n' server/src; then
    ok "server source defines group checkin handler (group_n)"
  else
    fail "server source missing group checkin handler (group_n) — App 群玩法依赖"
  fi

  # 1.321：离线回归——动态流路由在服务端源码中存在（App Explore 依赖 postRepo 列表 handler）
  if rg -q 'postRepo' server/src 2>/dev/null || grep -rq 'postRepo' server/src; then
    ok "server source defines posts feed handler (postRepo)"
  else
    fail "server source missing posts feed handler (postRepo) — App Explore 依赖"
  fi

  # 1.233：运维脚本存在性（部署/备份/恢复/状态）
  for script in deploy.sh backup-production.sh restore-production.sh update.sh status.sh verify-production-topology.sh; do
    [[ -f "scripts/$script" ]] && [[ -x "scripts/$script" ]] || {
      # 允许非可执行但存在（Windows 检出可能无 exec bit）
      [[ -f "scripts/$script" ]] || fail "scripts/$script missing"
    }
  done
  # 1.352：Windows 部署脚本存在且支持核心开关（-Doctor / -Version，与 deploy.sh 对齐）
  [[ -f "scripts/deploy.ps1" ]] || fail "scripts/deploy.ps1 missing (Windows deploy parity)"
  grep -q -- '-Doctor' scripts/deploy.ps1 || fail "deploy.ps1 missing -Doctor"
  grep -q -- '-Version' scripts/deploy.ps1 || fail "deploy.ps1 missing -Version"
  # 1.353：deploy.ps1 应支持 -DryRun（与 deploy.sh --dry-run 对齐）
  grep -q -- '-DryRun' scripts/deploy.ps1 || fail "deploy.ps1 missing -DryRun"
  # 1.372：deploy.ps1 -Doctor 应校验 docker 守护进程（与 deploy.sh 1.371 对齐）
  grep -q 'docker daemon' scripts/deploy.ps1 || fail "deploy.ps1 -Doctor missing daemon liveness check"
  ok "ops scripts present (incl. deploy.ps1 parity)"
}

compose_config_check() {
  command -v docker >/dev/null 2>&1 || fail "docker not installed"
  local env_file=".env"
  if [[ ! -f "$env_file" ]]; then
    echo "WARN: .env missing; using .env.docker.example for config parse only (do not deploy with example secrets)"
    env_file=".env.docker.example"
  fi
  docker compose --env-file "$env_file" config -q
  ok "docker compose config -q"
}

live_checks() {
  local base="${1:?usage: --live https://api.example.com}"
  base="${base%/}"
  command -v curl >/dev/null 2>&1 || fail "curl required for --live"

  local live_body ready_body
  live_body="$(curl -fsS --max-time 15 "$base/health/live")" || fail "live probe failed"
  echo "$live_body" | grep -q 'ok' || fail "live body unexpected: $live_body"
  # 1.331：health 探针响应应为合法 JSON（下游监控/探活解析）
  if ! echo "$live_body" | python3 -c 'import json,sys; json.load(sys.stdin)' 2>/dev/null; then
    fail "health/live is not valid JSON"
  fi
  ok "GET $base/health/live"

  ready_body="$(curl -fsS --max-time 15 "$base/health/ready")" || fail "ready probe failed"
  echo "$ready_body" | grep -q 'ready' || fail "ready body unexpected: $ready_body"
  if ! echo "$ready_body" | python3 -c 'import json,sys; json.load(sys.stdin)' 2>/dev/null; then
    fail "health/ready is not valid JSON"
  fi
  ok "GET $base/health/ready"

  local admin_headers
  admin_headers="$(curl -fsSI --max-time 15 "$base/admin")" || fail "admin HEAD failed"
  echo "$admin_headers" | grep -qi 'strict-transport-security' || fail "missing HSTS on /admin"
  # 1.332：live 校验 /admin 安全头（与离线 1.255/1.319 一致）
  echo "$admin_headers" | grep -qi 'x-content-type-options' || fail "missing X-Content-Type-Options on /admin"
  echo "$admin_headers" | grep -qi 'x-frame-options' || fail "missing X-Frame-Options on /admin"
  # 1.337：live 校验 Referrer-Policy（与离线 1.319 一致）
  echo "$admin_headers" | grep -qi 'referrer-policy' || fail "missing Referrer-Policy on /admin"
  ok "GET headers $base/admin include security headers"

  # 1.33：公开状态接口回归——App 依赖的运行时开关（含 1.11 消费的 contactCardEnabled）必须可匿名读取
  local status_body
  status_body="$(curl -fsS --max-time 15 "$base/api/public/status")" || fail "public status probe failed"
  for key in contactCardEnabled sealedSenderEnabled secretChatEnabled screenSecureRuntimeEnabled; do
    echo "$status_body" | grep -q "$key" || fail "public status missing $key"
  done
  # 1.355：App 消费的扩展运行时开关（AI/聊天文件夹/维护等）也必须在 public/status 中存在
  for key in aiAnalyzeFileEnabled aiAnalyzeImageEnabled aiGroupAssistantEnabled aiRewriteEnabled \
             aiSemanticSearchEnabled aiSuggestRepliesEnabled aiSummaryEnabled aiTranscribeEnabled \
             aiTranslateEnabled appLockEnabled chatFoldersEnabled maintenance; do
    echo "$status_body" | grep -q "$key" || fail "public status missing $key"
  done
  # 1.260：public/status 必须是合法 JSON（下游客户端解析）
  if ! echo "$status_body" | python3 -c 'import json,sys; json.load(sys.stdin)' 2>/dev/null; then
    fail "public status is not valid JSON"
  fi
  ok "GET $base/api/public/status exposes runtime flags"

  # 1.102：动态点赞者路由存在性回归——无凭据应返回 401（路由存在），404 说明端点缺失
  local likers_status
  likers_status="$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' "$base/api/posts/_probe_/likers")" || fail "likers probe failed"
  if [[ "$likers_status" == "404" ]]; then
    fail "likers route missing (404) — App 1.93 依赖 GET /api/posts/{id}/likers"
  fi
  # 1.236：无凭据不应返回 200（路由未鉴权）
  if [[ "$likers_status" == "200" ]]; then
    fail "likers route returns 200 without auth — auth gate missing"
  fi
  ok "POST likers route reachable (unauth status=$likers_status)"

  # 1.195：用户屏蔽路由存在性回归（App 1.172 动态屏蔽依赖 POST /api/users/block/{id}）
  local block_status
  block_status="$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' "$base/api/users/block/_probe_")" || fail "block probe failed"
  if [[ "$block_status" == "404" ]]; then
    fail "block route missing (404) — App 1.172 依赖 POST /api/users/block/{userId}"
  fi
  # 1.229：无凭据不应得到 200（成功）——200 说明路由未做鉴权
  if [[ "$block_status" == "200" ]]; then
    fail "block route returns 200 without auth — auth gate missing"
  fi
  ok "block route reachable (unauth status=$block_status)"

  # 1.296：群成员路由存在性回归（App 群详情依赖 GET /api/chats/{id}/members）
  local members_status
  members_status="$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' "$base/api/chats/_probe_/members")" || fail "members probe failed"
  if [[ "$members_status" == "404" ]]; then
    fail "group members route missing (404) — App 群详情依赖 GET /api/chats/{chatId}/members"
  fi
  # 无凭据不应返回 200（路由未鉴权）
  if [[ "$members_status" == "200" ]]; then
    fail "group members route returns 200 without auth — auth gate missing"
  fi
  ok "group members route reachable (unauth status=$members_status)"

  # 1.316：群投票路由存在性回归（App 群玩法依赖 GET /api/chats/{id}/polls）
  local polls_status
  polls_status="$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' "$base/api/chats/_probe_/polls")" || fail "polls probe failed"
  if [[ "$polls_status" == "404" ]]; then
    fail "group polls route missing (404) — App 群玩法依赖 GET /api/chats/{chatId}/polls"
  fi
  if [[ "$polls_status" == "200" ]]; then
    fail "group polls route returns 200 without auth — auth gate missing"
  fi
  ok "group polls route reachable (unauth status=$polls_status)"

  # 1.321：动态流路由存在性回归（App Explore 依赖 GET /api/posts）
  local posts_status
  posts_status="$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' "$base/api/posts")" || fail "posts probe failed"
  if [[ "$posts_status" == "404" ]]; then
    fail "posts feed route missing (404) — App Explore 依赖 GET /api/posts"
  fi
  # 无凭据不应返回 200（路由未鉴权）
  if [[ "$posts_status" == "200" ]]; then
    fail "posts feed route returns 200 without auth — auth gate missing"
  fi
  ok "posts feed route reachable (unauth status=$posts_status)"

  # 1.283：status.sh --json 输出必须是合法 JSON 且含监控所需字段（下游 jq/告警依赖）
  local status_json
  status_json="$(scripts/status.sh --json 2>/dev/null)" || fail "status.sh --json failed"
  if ! echo "$status_json" | python3 -c 'import json,sys; json.load(sys.stdin)' 2>/dev/null; then
    fail "status.sh --json is not valid JSON"
  fi
  for key in generated_at services services_up services_total services_unhealthy backup version; do
    echo "$status_json" | grep -q "\"$key\"" || fail "status.sh --json missing key $key"
  done
  ok "status.sh --json is valid JSON with monitoring keys"
}

usage() {
  cat <<'EOF'
Usage:
  scripts/verify-production-topology.sh --offline
  scripts/verify-production-topology.sh --compose-config
  scripts/verify-production-topology.sh --live https://api.example.com
  scripts/verify-production-topology.sh --all https://api.example.com   # offline + compose-config + live
EOF
}

case "$mode" in
  --offline)
    offline_checks
    ;;
  --compose-config)
    compose_config_check
    ;;
  --live)
    live_checks "$target_url"
    ;;
  --all)
    offline_checks
    compose_config_check
    live_checks "$target_url"
    ;;
  *)
    usage
    exit 2
    ;;
esac

echo "All requested checks passed."
