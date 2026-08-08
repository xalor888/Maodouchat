#!/usr/bin/env bash
set -Eeuo pipefail

# status.sh — 一条命令查看生产部署状态（服务、健康、版本、备份）。
# 只读操作，不修改任何状态。
# 用法：scripts/status.sh [--json] [--short] [--watch]

umask 077
workspace="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$workspace"

json=0
short=0
# 1.212：--health-check 仅按健康状态退出（0=健康 1=不健康），适合监控/cron
health_check=0
case "${1:-}" in
  --json) json=1 ;;
  --short) short=1 ;;
  --health-check) health_check=1 ;;
esac

watch=0
watch_interval="${STATUS_WATCH_INTERVAL:-5}"
# 1.231：--watch 支持 --interval N 指定刷新秒数
while [[ $# -gt 0 ]]; do
  case "$1" in
    --watch) watch=1; shift ;;
    --interval)
      if [[ "${2:-}" =~ ^[0-9]+$ ]] && (( $2 > 0 )); then
        watch_interval="$2"
      else
        echo "--interval requires a positive integer" >&2
        exit 2
      fi
      shift 2
      ;;
    --) shift; break ;;
    *) break ;;
  esac
done
# 1.224：--watch 与其他参数（--short/--json 等）组合时，透传给内部调用
inner_args=()
for arg in "$@"; do
  case "$arg" in
    --watch|--interval) : ;;
    *) inner_args+=("$arg") ;;
  esac
done

env_file="${ENV_FILE:-.env}"
compose=(docker compose --env-file "$env_file")
backup_root="${BACKUP_ROOT:-$workspace/backups}"

public_host="$(sed -n 's/^PUBLIC_HOST=//p' "$env_file" 2>/dev/null | head -n1 || true)"
health_url="${public_host:-localhost}"
health_timeout="${HEALTH_TIMEOUT_SECONDS:-10}"

# --watch：持续监控（Ctrl-C 退出；非 TTY 时不清屏，只打印分隔线）
if (( watch )); then
  trap 'echo; exit 0' INT
  tty=0
  [[ -t 1 ]] && tty=1
  # 1.344：--watch --json 组合时每轮输出必须保持纯 JSON（分隔线会破坏下游逐行 JSON 解析）
  suppress_sep=0
  for arg in "${inner_args[@]}"; do
    [[ "$arg" == "--json" ]] && suppress_sep=1
  done
  while true; do
    if (( tty )); then
      printf '\033[2J'
      printf '\033[H'
    fi
    "$0" "${inner_args[@]}"
    if (( suppress_sep )); then
      printf '\n'  # JSON 模式仅空行分隔，保持每行合法 JSON
    else
      printf '\n-- %s 刷新间隔 %ss（Ctrl-C 退出）--\n' "$(date -u '+%Y-%m-%d %H:%M:%S UTC')" "$watch_interval"
    fi
    sleep "$watch_interval"
  done
fi

# 服务状态
services="$(docker compose --env-file "$env_file" ps --format '{{.Service}}\t{{.State}}\t{{.Status}}' 2>/dev/null || true)"

# 健康检查
# 1.367：本地部署（PUBLIC_HOST=localhost/127.0.0.1/IP）时 Caddy 用自签证书且 80 端口 308 跳转，
# 严格 https 探测必然失败。本地按 http + 跟随跳转 + 忽略自签（同一主机信任上下文），
# 真实域名保持严格 https 校验（防中间人）。
ready="no"
health_scheme="https"
curl_insecure=""
if [[ "$health_url" == "localhost" || "$health_url" == "127.0.0.1" || "$health_url" == "::1" || "$health_url" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  health_scheme="http"
  curl_insecure="-kL"
fi
if curl -fsS $curl_insecure --max-time "$health_timeout" "$health_scheme://$health_url/health/ready" 2>/dev/null | grep -q ready; then
  ready="yes"
fi

# 最新备份
latest_backup=""
latest_backup_at=""
latest_backup_version=""
# 1.273：最新备份年龄（小时；无备份时为 空）
latest_backup_age_hours=""
backup_count=0
if [[ -d "$backup_root" ]]; then
  backup_count="$(find "$backup_root" -maxdepth 1 -type d -name 'maodouchat-*' | wc -l | tr -d ' ')"
  latest_backup="$(find "$backup_root" -maxdepth 1 -type d -name 'maodouchat-*' | sort | tail -n1 || true)"
  if [[ -n "$latest_backup" ]]; then
    latest_backup_at="$(sed -n 's/^created_at_utc=//p' "$latest_backup/METADATA.txt" 2>/dev/null | head -n1)"
    if [[ -n "$latest_backup_at" ]]; then
      latest_backup_age_hours="$(python3 - "$latest_backup_at" <<'PY'
import sys, datetime
created = sys.argv[1]
try:
    dt = datetime.datetime.strptime(created, "%Y%m%dT%H%M%SZ").replace(tzinfo=datetime.timezone.utc)
    age = (datetime.datetime.now(datetime.timezone.utc) - dt).total_seconds() / 3600.0
    print(f"{age:.1f}")
except Exception:
    print("")
PY
)"
    fi
    # 1.221：最新备份记录的代码版本
    latest_backup_version="$(sed -n 's/^code_version=//p' "$latest_backup/METADATA.txt" 2>/dev/null | head -n1)"
  fi
fi

# 1.193：代码版本（git describe；非 git 仓库则为 -）
code_version="$(git describe --always --tags --dirty 2>/dev/null || echo "-")"

# 1.212：--health-check 仅按健康状态退出
# 1.302：同时扫描 compose 中 unhealthy 服务（API 就绪但某服务退化时也要告警）
if (( health_check )); then
  unhealthy_list="$(printf '%s\n' "$services" | grep -i 'unhealthy' || true)"
  if [[ "$ready" == "yes" && -z "$unhealthy_list" ]]; then
    echo "ready"
    exit 0
  fi
  if [[ -n "$unhealthy_list" ]]; then
    echo "not ready (unhealthy services: $(printf '%s\n' "$unhealthy_list" | awk -F'\t' '{print $1}' | paste -sd, -))"
  else
    echo "not ready"
  fi
  exit 1
fi

# 1.206：--short 一行摘要
if (( short )); then
  up_count="$(printf '%s\n' "$services" | grep -c 'running' || true)"
  unhealthy_count="$(printf '%s\n' "$services" | grep -c 'unhealthy' || true)"
  # 1.334：--short 附带最新备份年龄（与 --json age_hours 一致，人眼快速核对新鲜度）
  printf 'ready=%s version=%s services_up=%s services_unhealthy=%s backups=%s latest=%s latest_backup_version=%s latest_backup_age=%s\n' \
    "$ready" "$code_version" "$up_count" "$unhealthy_count" "$backup_count" "${latest_backup_at:-none}" "${latest_backup_version:-none}" "${latest_backup_age_hours:-none}"
  exit 0
fi

if (( json )); then
  python3 - "$services" "$ready" "$latest_backup" "$latest_backup_at" "$health_url" "$code_version" "$backup_count" "$latest_backup_version" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$latest_backup_age_hours" "$health_scheme" <<'PY'
import json, sys
services, ready, latest_backup, latest_backup_at, health_url, code_version, backup_count, latest_backup_version, generated_at, latest_backup_age_hours, health_scheme = sys.argv[1:12]
rows = []
unhealthy = 0
for line in services.splitlines():
    parts = line.split("\t")
    if len(parts) < 2:
        continue
    status_text = parts[2] if len(parts) > 2 else ""
    # 1.290：从 compose status 提取健康状态（"healthy"/"unhealthy"/none）
    health = "unknown"
    if "unhealthy" in status_text:
        health = "unhealthy"
        unhealthy += 1
    elif "healthy" in status_text:
        health = "healthy"
    rows.append({"service": parts[0], "state": parts[1], "status": status_text, "health": health})
print(json.dumps({
    "generated_at": generated_at,
    "health": {"url": health_scheme + "://" + health_url + "/health/ready", "ready": ready == "yes"},
    "services": rows,
    "services_up": len([r for r in rows if "running" in r.get("status", "")]),
    "services_total": len(rows),
    "services_unhealthy": unhealthy,
    "backup": {"latest": latest_backup or None, "created_at_utc": latest_backup_at or None, "count": backup_count, "code_version": latest_backup_version or None, "age_hours": latest_backup_age_hours or None},
    "version": code_version,
}, ensure_ascii=False, indent=2))
PY
  exit 0
fi

echo "== 服务状态 (docker compose ps) =="
if [[ -z "$services" ]]; then
  echo "(docker compose 未运行或不可访问)"
else
  echo "$services"
fi

echo
echo "== 代码版本 =="
echo "$code_version"

echo
echo "== 健康检查 =="
echo "$health_scheme://$health_url/health/ready -> $ready"

echo
echo "== 最新备份 =="
echo "份数: $backup_count"
if [[ -z "$latest_backup" ]]; then
  echo "(backups/ 目录下暂无备份)"
else
  echo "目录: $latest_backup"
  echo "创建时间(UTC): ${latest_backup_at:-未知}"
  if [[ -n "$latest_backup_version" ]]; then
    echo "备份代码版本: $latest_backup_version"
  fi
fi
