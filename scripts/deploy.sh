#!/usr/bin/env bash
# Maodouchat 一键部署脚本
#
# 用法（在服务器上，仓库根目录）：
#   bash scripts/deploy.sh --host chat.example.com --email you@example.com
#   bash scripts/deploy.sh --host chat.example.com --email you@example.com --relaxed
#   bash scripts/deploy.sh --host chat.example.com --email you@example.com --relaxed --bootstrap-admin
#
# 自动完成：生成/补全 .env（自动生成全部强随机密钥）、启动容器、等待健康检查、
# 打印管理后台与后续指引。重复运行幂等，只补缺失键、不改已有值。
set -euo pipefail

workspace="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$workspace"

HOST=""
EMAIL=""
RELAXED="false"
NO_BUILD="false"
BOOTSTRAP_ADMIN="false"
DRY_RUN="false"
DOCTOR="false"

usage() {
  cat <<'EOF'
Usage: bash scripts/deploy.sh [options]

Run without options on a terminal and it will guide you interactively
(asks hostname, email, and relaxed mode). Options override prompts:

Options:
  --host <domain>       Public hostname (e.g. chat.example.com). Required on first run.
  --email <email>       ACME certificate notice email. Required on first run.
  --relaxed             Self-hosted relaxed mode: no SMTP/TURN needed
                        (verification codes go to logs, calls STUN-only).
  --bootstrap-admin     Make the FIRST registered user the owner admin automatically
                        (one-time; set BOOTSTRAP_FIRST_USER_AS_ADMIN=false afterwards).
  --no-build            Reuse existing images (skip docker build).
  --skip-health-wait    1.208: skip the /health/ready wait after containers start
                        (for CI/bootstrap where health polling is not applicable).
  --health-timeout N    1.234: override the /health/ready wait timeout in seconds.
  --dry-run             8.47: only prepare/fix .env and print the config summary,
                        do NOT start containers (useful to review generated secrets).
  --doctor              1.281: read-only preflight. Checks prerequisites, .env
                        completeness/secret strength, DNS resolution and compose
                        config, then reports PASS/FAIL/WARN and exits (no changes).
  --version             1.274: print the deploy tool version (git describe) and exit.
  --help                Show this help.

Examples:
  bash scripts/deploy.sh                                  # interactive (recommended first time)
  bash scripts/deploy.sh --host chat.example.com --email admin@example.com
  bash scripts/deploy.sh --host chat.example.com --email admin@example.com --relaxed --bootstrap-admin
  bash scripts/deploy.sh --host chat.example.com --email admin@example.com --dry-run
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) HOST="${2:-}"; shift 2 ;;
    --email) EMAIL="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN="true"; shift ;;
    --relaxed) RELAXED="true"; shift ;;
  --no-build) NO_BUILD="true"; shift ;;
  --skip-health-wait) SKIP_HEALTH_WAIT="true"; shift ;;
  --health-timeout)
    if [[ "${2:-}" =~ ^[0-9]+$ ]] && (( $2 > 0 )); then
      HEALTH_TIMEOUT_SECONDS="$2"
    else
      echo "--health-timeout requires a positive integer (seconds)" >&2
      exit 2
    fi
    shift 2
    ;;
  --bootstrap-admin) BOOTSTRAP_ADMIN="true"; shift ;;
  --doctor) DOCTOR="true"; shift ;;
  --version)
    echo "deploy.sh $(git describe --always --tags --dirty 2>/dev/null || echo 'unknown')"
    exit 0
    ;;
  --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  OK: $*"; }

gen_secret() { # hex(24) = 48 chars, URL-safe, no special chars
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  else
    tr -dc 'a-f0-9' </dev/urandom | head -c 48
  fi
}

# ────────────────────────────────────────────
# 0. --doctor 预检（只读，不修改任何文件，检查后退出）
# ────────────────────────────────────────────
doctor_checks() {
  local status=0
  local warn=0

  doctor_pass() { echo "  [PASS] $*"; }
  doctor_warn() { echo "  [WARN] $*"; warn=1; }
  doctor_fail() { echo "  [FAIL] $*"; status=1; }

  echo "== Maodouchat deploy doctor (preflight) =="

  # 1. 基础环境
  if command -v docker >/dev/null 2>&1; then
    doctor_pass "docker found"
  else
    doctor_fail "docker not found. Install Docker 24+ and Docker Compose v2 first."
  fi
  if docker compose version >/dev/null 2>&1; then
    doctor_pass "docker compose v2 available"
  else
    doctor_fail "docker compose v2 not available."
  fi
  # 1.371：docker 守护进程在运行（compose version 不连 daemon，若 daemon 未起部署会直接失败）
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    doctor_pass "docker daemon running"
  elif command -v docker >/dev/null 2>&1; then
    doctor_fail "docker daemon not running. Start Docker (e.g. systemctl start docker / launch Docker Desktop)."
  fi

  # 2. .env 就绪性（不创建，仅报告）
  if [[ -f .env ]]; then
    doctor_pass ".env exists"
  elif [[ -f .env.docker.example ]]; then
    doctor_warn ".env missing — deploy will create it from .env.docker.example"
  else
    doctor_fail ".env missing and .env.docker.example missing"
  fi

  # 3. 必需密钥强度（仅当 .env 已存在）
  if [[ -f .env ]]; then
    local jwt
    jwt="$(sed -n 's/^JWT_SECRET=//p' .env | head -n1)"
    if [[ ${#jwt} -lt 32 || "$jwt" == replace-with* || -z "$jwt" ]]; then
      doctor_fail "JWT_SECRET weak/placeholder (<32 chars or replace-with). deploy will auto-generate."
    else
      doctor_pass "JWT_SECRET strong (${#jwt} chars)"
    fi

    local pg
    pg="$(sed -n 's/^POSTGRES_PASSWORD=//p' .env | head -n1)"
    if [[ -z "$pg" || "$pg" == replace-with* ]]; then
      doctor_warn "POSTGRES_PASSWORD placeholder — deploy will auto-generate"
    else
      doctor_pass "POSTGRES_PASSWORD set"
    fi

    local push
    push="$(sed -n 's/^PUSH_HMAC_SECRET=//p' .env | head -n1)"
    local relaxed
    relaxed="$(sed -n 's/^RELAXED_VERIFICATION=//p' .env | head -n1)"
    if [[ -z "$push" || "$push" == replace-with* ]]; then
      if [[ "$relaxed" == "true" ]]; then
        doctor_pass "PUSH_HMAC_SECRET blank (relaxed mode allows it)"
      else
        doctor_warn "PUSH_HMAC_SECRET placeholder — deploy will auto-generate"
      fi
    else
      doctor_pass "PUSH_HMAC_SECRET set"
    fi
  else
    doctor_warn "skipping .env secret checks (no .env yet)"
  fi

  # 1.342：DATABASE_POOL_SIZE 数值校验（服务端要求 2..128；非法值会致连接池初始化失败）
  if [[ -f .env ]]; then
    local pool
    pool="$(sed -n 's/^DATABASE_POOL_SIZE=//p' .env | head -n1)"
    if [[ -n "$pool" && ! "$pool" =~ ^[0-9]+$ ]] || [[ -n "$pool" && "$pool" =~ ^[0-9]+$ && ( "$pool" -lt 2 || "$pool" -gt 128 ) ]]; then
      doctor_fail "DATABASE_POOL_SIZE='$pool' invalid (expected integer 2..128)"
    elif [[ -n "$pool" ]]; then
      doctor_pass "DATABASE_POOL_SIZE=$pool"
    fi

    # 1.347：SMTP_PORT 应为有效端口（1..65535）；TURN_CREDENTIAL_TTL_SECONDS 应为正整数
    local smtp_port
    smtp_port="$(sed -n 's/^SMTP_PORT=//p' .env | head -n1)"
    if [[ -n "$smtp_port" && ( ! "$smtp_port" =~ ^[0-9]+$ || "$smtp_port" -lt 1 || "$smtp_port" -gt 65535 ) ]]; then
      doctor_fail "SMTP_PORT='$smtp_port' invalid (expected port 1..65535)"
    elif [[ -n "$smtp_port" ]]; then
      doctor_pass "SMTP_PORT=$smtp_port"
    fi

    local turn_ttl
    turn_ttl="$(sed -n 's/^TURN_CREDENTIAL_TTL_SECONDS=//p' .env | head -n1)"
    if [[ -n "$turn_ttl" && ( ! "$turn_ttl" =~ ^[0-9]+$ || "$turn_ttl" -lt 1 ) ]]; then
      doctor_fail "TURN_CREDENTIAL_TTL_SECONDS='$turn_ttl' invalid (expected positive integer)"
    elif [[ -n "$turn_ttl" ]]; then
      doctor_pass "TURN_CREDENTIAL_TTL_SECONDS=$turn_ttl"
    fi
  fi

  # 4. 域名 / HTTPS
  local host
  host="$(sed -n 's/^PUBLIC_HOST=//p' .env 2>/dev/null | head -n1 || true)"
  local email
  email="$(sed -n 's/^ACME_EMAIL=//p' .env 2>/dev/null | head -n1 || true)"
  if [[ -z "$host" || "$host" == replace-with* || "$host" == api.example.com ]]; then
    doctor_warn "PUBLIC_HOST unset — run with --host your.domain.com (or interactive)"
  elif [[ "$host" == localhost* || "$host" == 127.0.0.1* ]]; then
    doctor_pass "PUBLIC_HOST=$host (localhost — HTTPS not required)"
  else
    local resolved
    resolved="$(getent ahostsv4 "$host" 2>/dev/null | head -n1 | awk '{print $1}')"
    if [[ -n "$resolved" ]]; then
      doctor_pass "DNS $host -> $resolved"
    else
      doctor_fail "DNS $host does not resolve here (TLS will fail until DNS points to this server)"
    fi
  fi
  if [[ -z "$email" || "$email" == replace-with* || "$email" == admin@example.com ]]; then
    doctor_warn "ACME_EMAIL unset — run with --email you@example.com"
  else
    doctor_pass "ACME_EMAIL=$email"
  fi

  # 1.303：BASE_URL 必须与 PUBLIC_HOST 一致（不一致 → 客户端 API 指向错误域名，部署「看起来成功」但 App 无法连接）
  local base_url
  base_url="$(sed -n 's/^BASE_URL=//p' .env 2>/dev/null | head -n1 || true)"
  if [[ -z "$host" || "$host" == replace-with* || "$host" == api.example.com ]]; then
    : # PUBLIC_HOST 未配置时跳过（已有 warn）
  elif [[ -z "$base_url" || "$base_url" == replace-with* ]]; then
    doctor_warn "BASE_URL unset — deploy will set it to https://$host"
  else
    local expected_base="https://$host"
    if [[ "$base_url" == "$expected_base" || "$base_url" == "https://${host}/" ]]; then
      doctor_pass "BASE_URL=$base_url matches PUBLIC_HOST"
    else
      doctor_fail "BASE_URL=$base_url does not match PUBLIC_HOST=$host (expected $expected_base) — App 会连错域名"
    fi
  fi

  # 1.313：非 relaxed 模式必须配置 SMTP（否则验证码邮件发不出去，注册流程卡死）
  if [[ -f .env ]]; then
    local relaxed_val
    relaxed_val="$(sed -n 's/^RELAXED_VERIFICATION=//p' .env | head -n1)"
    # 1.318：--relaxed CLI 标志与 .env 值等效（`--doctor --relaxed` 首次预检不应误报 SMTP 缺失）
    if [[ "$RELAXED" == "true" ]]; then
      relaxed_val="true"
    fi
    local smtp_host
    smtp_host="$(sed -n 's/^SMTP_HOST=//p' .env | head -n1)"
    if [[ "$relaxed_val" == "true" ]]; then
      doctor_pass "relaxed mode: SMTP optional (codes logged)"
    elif [[ -z "$smtp_host" || "$smtp_host" == replace-with* ]]; then
      doctor_fail "SMTP_HOST unset in non-relaxed mode — verification emails cannot be sent. Configure SMTP or pass --relaxed."
    else
      doctor_pass "SMTP_HOST=$smtp_host"
    fi

    # 1.351：管理员配置完整性（MASTER_ADMINS 占位或 bootstrap 未启用 → 首个注册用户可能拿不到管理权限）
    local masters
    masters="$(sed -n 's/^MASTER_ADMINS=//p' .env | head -n1)"
    local bootstrap
    bootstrap="$(sed -n 's/^BOOTSTRAP_FIRST_USER_AS_ADMIN=//p' .env | head -n1)"
    if [[ -z "$masters" || "$masters" == replace-with* ]]; then
      if [[ "$bootstrap" == "true" ]]; then
        doctor_pass "admin bootstrap enabled (first user becomes owner admin)"
      else
        doctor_warn "MASTER_ADMINS placeholder & bootstrap off — first registered user will NOT be admin. Configure MASTER_ADMINS or set BOOTSTRAP_FIRST_USER_AS_ADMIN=true."
      fi
    else
      doctor_pass "MASTER_ADMINS set"
    fi

    # 1.354：非 relaxed 模式下 TURN 建议配置（缺失时通话降级 STUN-only，NAT 穿透可靠性下降）
    local turn_urls
    turn_urls="$(sed -n 's/^TURN_URLS=//p' .env | head -n1)"
    if [[ "$relaxed_val" == "true" ]]; then
      : # relaxed 模式 STUN-only 可接受
    elif [[ -z "$turn_urls" || "$turn_urls" == replace-with* ]]; then
      doctor_warn "TURN_URLS unset in non-relaxed mode — calls fall back to STUN-only (NAT traversal may fail). Configure TURN or use --relaxed."
    else
      doctor_pass "TURN_URLS set"
    fi

    # 1.357：CORS_ORIGINS 若配置应为逗号分隔的 https 源（非空且非占位时校验，防拼写错误导致 web 端被 CORS 拦截）
    local cors
    cors="$(sed -n 's/^CORS_ORIGINS=//p' .env | head -n1)"
    if [[ -n "$cors" && "$cors" != replace-with* ]]; then
      if echo "$cors" | grep -qE 'https?://[^,]+'; then
        doctor_pass "CORS_ORIGINS set"
      else
        doctor_warn "CORS_ORIGINS='$cors' does not look like comma-separated origins (e.g. https://app.example.com) — web 端可能被 CORS 拦截"
      fi
    fi
  fi

  # 5. compose 配置可解析（有 .env 且 docker 可用时）
  if [[ -f .env ]] && command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    if docker compose --env-file .env config -q >/dev/null 2>&1; then
      doctor_pass "docker compose config valid"
    else
      doctor_fail "docker compose config invalid (see errors above)"
    fi
  fi

  # 1.358：上传/配额限制数值校验（服务端按整数解析，非法值致启动/上传异常）
  if [[ -f .env ]]; then
    for key in MAX_BASE64_IMAGE_CHARS MAX_IMAGE_BYTES MAX_IMAGE_DIMENSION MAX_ATTACHMENT_BYTES USER_STORAGE_QUOTA_BYTES; do
      local num
      num="$(sed -n "s/^${key}=//p" .env | head -n1)"
      if [[ -n "$num" && ! "$num" =~ ^[0-9]+$ ]]; then
        doctor_fail "$key='$num' invalid (expected positive integer)"
      fi
    done
  fi

  echo
  if (( status == 1 )); then
    echo "Doctor: FAIL — fix the [FAIL] items above, then re-run."
    exit 1
  elif (( warn == 1 )); then
    echo "Doctor: OK (with warnings)."
  else
    echo "Doctor: OK — ready to deploy."
  fi
  exit 0
}

if [[ "$DOCTOR" == "true" ]]; then
  doctor_checks
fi

# ────────────────────────────────────────────
# 1. 基础环境检查
# ────────────────────────────────────────────
command -v docker >/dev/null 2>&1 || fail "docker not found. Install Docker 24+ and Docker Compose v2 first."
docker compose version >/dev/null 2>&1 || fail "docker compose v2 not available."

echo "== Maodouchat deploy =="

# ────────────────────────────────────────────
# 2. .env 准备（幂等）
# ────────────────────────────────────────────
if [[ ! -f .env ]]; then
  cp .env.docker.example .env
  echo ".env created from .env.docker.example"
fi

# ────────────────────────────────────────────
# 首次部署交互式引导（0.65）：无 --host/--email 且终端可用时逐项提问，
# 不再要求记忆参数名；已有有效值（幂等重跑）则自动跳过。
# ────────────────────────────────────────────
if [[ -z "$HOST" && -t 0 ]]; then
  existing_host="$(sed -n 's/^PUBLIC_HOST=//p' .env | head -n1)"
  if [[ -z "$existing_host" || "$existing_host" == replace-with* || "$existing_host" == api.example.com ]]; then
    read -r -p "Public hostname (e.g. chat.example.com): " HOST
    HOST="$(printf '%s' "$HOST" | tr -d '[:space:]')"
  fi
fi
if [[ -z "$EMAIL" && -t 0 ]]; then
  existing_email="$(sed -n 's/^ACME_EMAIL=//p' .env | head -n1)"
  if [[ -z "$existing_email" || "$existing_email" == replace-with* || "$existing_email" == admin@example.com ]]; then
    read -r -p "ACME certificate email (you@example.com): " EMAIL
    EMAIL="$(printf '%s' "$EMAIL" | tr -d '[:space:]')"
  fi
fi
# 交互式部署追加一次 relaxed 确认（默认 yes）：自托管无 SMTP/TURN 也能跑。
if [[ -z "$HOST" && -z "$EMAIL" && -t 0 && "$RELAXED" == "false" ]]; then
  read -r -p "Enable relaxed self-hosted mode (no SMTP/TURN needed)? [Y/n] " ans
  case "$ans" in
    n|N|no|NO) : ;;
    *) RELAXED="true" ;;
  esac
fi

# 1.96：非交互环境（CI/cron 管道）缺失首次参数时直接报错，
# 避免 PUBLIC_HOST 为空带病启动导致 Caddy 证书/域名错乱难以排查
if [[ -z "$HOST" && ! -t 0 ]]; then
  existing_host="$(sed -n 's/^PUBLIC_HOST=//p' .env | head -n1)"
  if [[ -z "$existing_host" || "$existing_host" == replace-with* || "$existing_host" == api.example.com ]]; then
    fail "No PUBLIC_HOST configured and no --host given. First run interactively (bash scripts/deploy.sh) or pass --host and --email."
  fi
fi

ensure_key() { # ensure_key KEY [default-or-empty]
  local key="$1" default="${2:-}"
  if ! grep -q "^${key}=" .env; then
    echo "${key}=${default}" >> .env
    ok "added ${key}"
  fi
}

replace_key() { # replace_key KEY NEW_VALUE  (only replaces placeholder/blank values)
  local key="$1" value="$2"
  local cur
  cur="$(sed -n "s/^${key}=//p" .env | head -n1)"
  if [[ -z "$cur" || "$cur" == replace-with* ]]; then
    sed -i "s|^${key}=.*|${key}=${value}|" .env
    ok "${key} set to a generated random value"
  fi
}

if [[ -n "$HOST" ]]; then
  ensure_key PUBLIC_HOST
  replace_key PUBLIC_HOST "$HOST"
fi
if [[ -n "$EMAIL" ]]; then
  ensure_key ACME_EMAIL
  replace_key ACME_EMAIL "$EMAIL"
fi
ensure_key BASE_URL
if [[ -n "$HOST" ]]; then
  replace_key BASE_URL "https://${HOST}"
fi

# 密钥自动生成（占位或空值才替换）
replace_key JWT_SECRET "$(gen_secret)$(gen_secret)"
replace_key PUSH_HMAC_SECRET "$(gen_secret)$(gen_secret)"
replace_key POSTGRES_PASSWORD "$(gen_secret)"
replace_key TURN_SHARED_SECRET "$(gen_secret)$(gen_secret)"
replace_key TURN_REALM "${HOST:+turn.${HOST}}"
replace_key TURN_URLS "${HOST:+turn:turn.${HOST}:3478?transport=udp,turn:turn.${HOST}:3478?transport=tcp}"
# 隐藏管理后台：生成随机前缀，完整地址为 https://<host>/<ADMIN_PATH>/admin
ensure_key ADMIN_PATH ""
replace_key ADMIN_PATH "admin-$(tr -dc 'a-z0-9' </dev/urandom | head -c 14)"

# 模式开关
if [[ "$RELAXED" == "true" ]]; then
  replace_key RELAXED_VERIFICATION true
  replace_key DEV_LOG_CODES true
  # 8.47 修复：不再清空用户已配置的 SMTP_HOST——relaxed 只放宽校验，用户已有 SMTP 时
  # 仍可发真邮件（DEV_LOG_CODES 仅兜底）；此前 sed 强制清空会覆盖用户配置。
  ensure_key SMTP_HOST ""
  ok "relaxed mode: SMTP/TURN optional, codes printed to logs"
fi
if [[ "$BOOTSTRAP_ADMIN" == "true" ]]; then
  replace_key BOOTSTRAP_FIRST_USER_AS_ADMIN true
  ok "bootstrap-admin: the first registered user becomes owner admin"
fi

# 必填值检查
PUBLIC_HOST_VALUE="$(sed -n 's/^PUBLIC_HOST=//p' .env | head -n1)"
BASE_URL_VALUE="$(sed -n 's/^BASE_URL=//p' .env | head -n1)"
ACME_EMAIL_VALUE="$(sed -n 's/^ACME_EMAIL=//p' .env | head -n1)"
[[ -z "$PUBLIC_HOST_VALUE" || "$PUBLIC_HOST_VALUE" == replace-with* || "$PUBLIC_HOST_VALUE" == api.example.com ]] \
  && fail "PUBLIC_HOST not set. Run with --host your.domain.com"
[[ -z "$BASE_URL_VALUE" || "$BASE_URL_VALUE" == replace-with* ]] \
  && fail "BASE_URL not set. Run with --host your.domain.com"
[[ -z "$ACME_EMAIL_VALUE" || "$ACME_EMAIL_VALUE" == replace-with* || "$ACME_EMAIL_VALUE" == admin@example.com ]] \
  && fail "ACME_EMAIL not set. Run with --email you@example.com"
# 1.339：ACME_EMAIL 格式校验（Caddy 对非法邮箱报错晦涩，提前拦截）
if [[ -n "$ACME_EMAIL_VALUE" && "$ACME_EMAIL_VALUE" != localhost* ]]; then
  if [[ ! "$ACME_EMAIL_VALUE" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
    fail "ACME_EMAIL='$ACME_EMAIL_VALUE' does not look like a valid email (e.g. you@example.com)"
  fi
fi
# 1.350：PUBLIC_HOST 格式校验（非 localhost：裸域名，无 scheme/path/空格/端口——Caddy 报错晦涩，提前拦截）
if [[ -n "$PUBLIC_HOST_VALUE" && "$PUBLIC_HOST_VALUE" != localhost* && "$PUBLIC_HOST_VALUE" != 127.0.0.1* ]]; then
  if [[ "$PUBLIC_HOST_VALUE" =~ [\ /] ]] || [[ "$PUBLIC_HOST_VALUE" == http* ]] || [[ "$PUBLIC_HOST_VALUE" == *:* ]]; then
    fail "PUBLIC_HOST='$PUBLIC_HOST_VALUE' invalid (expected bare hostname like chat.example.com — no scheme, path, space or port)"
  fi
fi
# 8.48：提前校验弱配置——JWT_SECRET/POSTGRES_PASSWORD/PUSH_HMAC_SECRET 若仍是占位或过短，
# 服务端生产校验会启动失败，与其在容器日志里绕圈，不如启动前直接拦截（含部署后手改 .env 的场景）
JWT_SECRET_VALUE="$(sed -n 's/^JWT_SECRET=//p' .env | head -n1)"
[[ ${#JWT_SECRET_VALUE} -lt 32 || "$JWT_SECRET_VALUE" == replace-with* ]] \
  && fail "JWT_SECRET must be at least 32 random characters (regenerate or set a strong value in .env)"
# 1.340：POSTGRES_PASSWORD 强度校验（数据库访问凭据，弱口令即安全风险；占位会致服务端启动失败）
POSTGRES_PASSWORD_VALUE="$(sed -n 's/^POSTGRES_PASSWORD=//p' .env | head -n1)"
[[ ${#POSTGRES_PASSWORD_VALUE} -lt 16 || "$POSTGRES_PASSWORD_VALUE" == replace-with* || "$POSTGRES_PASSWORD_VALUE" == "$JWT_SECRET_VALUE" ]] \
  && fail "POSTGRES_PASSWORD must be at least 16 random characters and not equal to JWT_SECRET (regenerate or set a strong value in .env)"

# 1.303：BASE_URL 必须与 PUBLIC_HOST 一致（避免 App 连错域名）
if [[ "$PUBLIC_HOST_VALUE" != localhost* && "$PUBLIC_HOST_VALUE" != 127.0.0.1* ]]; then
  expected_base="https://$PUBLIC_HOST_VALUE"
  if [[ "$BASE_URL_VALUE" != "$expected_base" && "$BASE_URL_VALUE" != "https://${PUBLIC_HOST_VALUE}/" ]]; then
    fail "BASE_URL=$BASE_URL_VALUE does not match PUBLIC_HOST=$PUBLIC_HOST_VALUE (expected $expected_base) — App 会连错域名。修正 .env 或重跑 --host"
  fi
  # 1.341：非 localhost 部署 BASE_URL 必须为 https（Caddy 自动 HTTPS；http 会致客户端证书/安全策略异常）
  if [[ "$BASE_URL_VALUE" != https://* ]]; then
    fail "BASE_URL=$BASE_URL_VALUE must use https:// for non-localhost PUBLIC_HOST ($PUBLIC_HOST_VALUE)"
  fi
fi

# ────────────────────────────────────────────
# 3. 域名解析提示（不阻断）
# ────────────────────────────────────────────
if [[ "$PUBLIC_HOST_VALUE" != localhost* && "$PUBLIC_HOST_VALUE" != 127.0.0.1* ]]; then
  resolved="$(getent ahostsv4 "$PUBLIC_HOST_VALUE" 2>/dev/null | head -n1 | awk '{print $1}')"
  if [[ -z "$resolved" ]]; then
    echo "  WARN: $PUBLIC_HOST_VALUE does not resolve here. TLS (Caddy) will fail until DNS points to this server."
  else
    ok "DNS $PUBLIC_HOST_VALUE -> $resolved"
  fi
fi

# ────────────────────────────────────────────
# 4. 启动
# ────────────────────────────────────────────
if [[ "$DRY_RUN" == "true" ]]; then
  echo "== DRY RUN: .env prepared, not starting containers =="
  echo
  echo " Current configuration:"
  echo "   PUBLIC_HOST   = $PUBLIC_HOST_VALUE"
  echo "   BASE_URL      = $BASE_URL_VALUE"
  echo "   ACME_EMAIL    = $ACME_EMAIL_VALUE"
  echo "   RELAXED       = $(sed -n 's/^RELAXED_VERIFICATION=//p' .env | head -n1)"
  echo "   JWT_SECRET    = $(sed -n 's/^JWT_SECRET=//p' .env | head -n1 | cut -c1-6)…$(sed -n 's/^JWT_SECRET=//p' .env | head -n1 | wc -c) chars"
  echo "   POSTGRES_PASS = $(sed -n 's/^POSTGRES_PASSWORD=//p' .env | head -n1 | cut -c1-6)…$(sed -n 's/^POSTGRES_PASSWORD=//p' .env | head -n1 | wc -c) chars"
  echo "   SMTP_HOST     = $(sed -n 's/^SMTP_HOST=//p' .env | head -n1)"
  echo "   TURN_URLS     = $(sed -n 's/^TURN_URLS=//p' .env | head -n1)"
  echo "   BACKUP_KEEP   = $(sed -n 's/^BACKUP_KEEP=//p' .env | head -n1)"
  echo "   ADMIN_PATH    = $(sed -n 's/^ADMIN_PATH=//p' .env | head -n1)"
  # 1.312：dry-run 展示管理员与注册配置（首次部署易遗漏）
  echo "   MASTER_ADMINS = $(sed -n 's/^MASTER_ADMINS=//p' .env | head -n1)"
  echo "   BOOTSTRAP     = $(sed -n 's/^BOOTSTRAP_FIRST_USER_AS_ADMIN=//p' .env | head -n1)"
  echo "   ALLOW_REG     = $(sed -n 's/^ALLOW_REGISTRATION=//p' .env | head -n1)"
  echo
  echo " Review .env, then run without --dry-run to deploy."
  exit 0
fi

echo "== Validating compose =="
docker compose --env-file .env config -q || fail "docker compose config invalid (see errors above)"

echo "== Starting stack =="
if [[ "$NO_BUILD" == "true" ]]; then
  docker compose up -d
else
  docker compose up -d --build
fi
# 1.374：Caddyfile 是 bind mount，compose 不会因文件内容变化重建 proxy；强制重载保证配置生效
docker compose up -d --no-deps --force-recreate proxy

# ────────────────────────────────────────────
# 5. 健康等待
# ────────────────────────────────────────────
# 1.85：HEALTH_TIMEOUT_SECONDS 可覆盖（默认 300s），慢启动/大迁移环境放宽等待
# 1.208：--skip-health-wait 跳过等待（CI/bootstrap 不适用健康轮询时）
if [[ "${SKIP_HEALTH_WAIT:-}" == "true" ]]; then
  echo "== Skipping /health/ready wait (--skip-health-wait) =="
else
health_timeout="${HEALTH_TIMEOUT_SECONDS:-300}"
# 1.369：本地部署（localhost/IP）时 Caddy 用自签证书且 80 端口 308 跳转，严格 https 探测必失败；
# 与 status.sh 1.367 的 scheme 感知一致，真实域名保持严格 https 校验（防中间人）。
health_scheme="https"
curl_insecure=""
if [[ "$PUBLIC_HOST_VALUE" == "localhost" || "$PUBLIC_HOST_VALUE" == "127.0.0.1" || "$PUBLIC_HOST_VALUE" == "::1" || "$PUBLIC_HOST_VALUE" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  health_scheme="http"
  curl_insecure="-kL"
fi
echo "== Waiting for /health/ready (up to ${health_timeout}s) =="
attempt=0
until curl -fsS $curl_insecure --max-time 10 "$health_scheme://${PUBLIC_HOST_VALUE}/health/ready" 2>/dev/null | grep -q ready; do
  attempt=$((attempt + 1))
  if [[ $((attempt * 10)) -ge "$health_timeout" ]]; then
    echo "FAIL: server not ready after ${health_timeout}s. Check logs:" >&2
    docker compose logs --tail=80 server >&2
    exit 1
  fi
  if [[ $((attempt % 6)) -eq 0 ]]; then
    echo "  ... still waiting (${attempt}0s)"
    docker compose ps --format "table {{.Service}}\t{{.Status}}"
  fi
  sleep 10
done
ok "$health_scheme://${PUBLIC_HOST_VALUE}/health/ready is ready"
# 1.292：就绪后再校验各容器健康状态（db/server/proxy 均有 healthcheck），
# 捕获「API 就绪但某服务 unhealthy」的潜伏问题（如 db 被 OOM 后 pg_isready 仍过、proxy 配置异常）
unhealthy_services="$(docker compose --env-file .env ps --format '{{.Service}}\t{{.Status}}' 2>/dev/null | grep -i 'unhealthy' || true)"
if [[ -n "$unhealthy_services" ]]; then
  echo "  WARN: the following services are unhealthy — investigate before relying on the deployment:" >&2
  echo "$unhealthy_services" | sed 's/^/    /' >&2
fi
fi

# ────────────────────────────────────────────
# 6. 总结与指引
# ────────────────────────────────────────────
echo
echo "==============================="
echo " Maodouchat is UP:"
echo "   API:   $BASE_URL_VALUE"
echo "   Admin: $BASE_URL_VALUE/$(sed -n 's/^ADMIN_PATH=//p' .env | head -n1)/admin  (MASTER_ADMINS only)"
echo "   Web:   $BASE_URL_VALUE"
echo
echo " First-time admin setup:"
if [[ "$BOOTSTRAP_ADMIN" == "true" ]]; then
  echo "   1. Register your account in the app (first account = owner admin)."
  echo "   2. After registering, edit .env: set BOOTSTRAP_FIRST_USER_AS_ADMIN=false"
  echo "      then run: bash scripts/deploy.sh --no-build"
else
  echo "   1. Register your account in the app."
  echo "   2. Get your user id: docker compose exec -T db psql -U maodouchat -d maodouchat -c \"SELECT id,email FROM users\""
  echo "   3. Edit .env: MASTER_ADMINS=<your-user-id>, then run: bash scripts/deploy.sh --no-build"
fi
echo
echo " Backup:   bash scripts/backup-production.sh"
echo " Restore:  bash scripts/restore-production.sh"
 echo " Update:   bash scripts/update.sh (backup + git pull + rebuild)"
echo " Logs:     docker compose logs -f server"
echo "==============================="
