#!/usr/bin/env bash
set -Eeuo pipefail

umask 077
workspace="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$workspace"

env_file="${ENV_FILE:-.env}"
# 1.187：--keep N 命令行覆盖保留份数；否则沿用 BACKUP_KEEP/.env/默认 14
# 1.209：--no-prune 保留全部备份（不清理）
# 1.230：--tag NAME 给备份目录加后缀（便于人工识别）
backup_root="$workspace/backups"
keep_override=""
no_prune=0
backup_tag=""
# 1.238：--dry-run 只校验环境，不停止服务、不写备份
# 1.239：--list 列出已有备份（只读）
dry_run=0
list_only=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) dry_run=1; shift ;;
    --list) list_only=1; shift ;;
    --keep)
      keep_override="${2:-}"
      if [[ ! "$keep_override" =~ ^[0-9]+$ ]] || (( keep_override <= 0 )); then
        echo "Usage: $0 [--keep N] [--no-prune] [--tag NAME] [backup_dir]" >&2
        exit 2
      fi
      shift 2
      ;;
    --no-prune) no_prune=1; shift ;;
    --tag)
      backup_tag="${2:-}"
      # 1.322：tag 仅允许字母/数字/下划线/连字符（目录名安全），拒绝空白、路径分隔、点号等
      if [[ -z "$backup_tag" || ! "$backup_tag" =~ ^[A-Za-z0-9_-]+$ ]]; then
        echo "Usage: $0 [--tag NAME] (name must be non-empty; only letters/digits/_/- allowed)" >&2
        exit 2
      fi
      shift 2
      ;;
    --) shift; break ;;
    -*) echo "Unknown option: $1" >&2; exit 2 ;;
    *) backup_root="$1"; shift ;;
  esac
done
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
final_dir="$backup_root/maodouchat-$timestamp${backup_tag:+-$backup_tag}"
mkdir -p "$backup_root"
partial_dir="$(mktemp -d "$backup_root/.partial-$timestamp-XXXXXX")"
compose=(docker compose --env-file "$env_file")
services_stopped=0

# 1.239：--list 列出已有备份（只读，不停止服务）
if (( list_only )); then
  if [[ ! -d "$backup_root" ]]; then
    echo "(backups/ 目录不存在)"
    exit 0
  fi
  echo "备份目录: $backup_root"
  find "$backup_root" -maxdepth 1 -type d -name 'maodouchat-*' | sort | while read -r dir; do
    created="$(sed -n 's/^created_at_utc=//p' "$dir/METADATA.txt" 2>/dev/null | head -n1)"
    version="$(sed -n 's/^code_version=//p' "$dir/METADATA.txt" 2>/dev/null | head -n1)"
    tool_version="$(sed -n 's/^backup_tool_version=//p' "$dir/METADATA.txt" 2>/dev/null | head -n1)"
    # 1.308：备份年龄（小时；created 解析失败/为空时显示 -）
    age="$(if [[ -n "$created" ]]; then python3 - "$created" <<'PY'
import sys, datetime
created = sys.argv[1]
try:
    dt = datetime.datetime.strptime(created, "%Y%m%dT%H%M%SZ").replace(tzinfo=datetime.timezone.utc)
    age = (datetime.datetime.now(datetime.timezone.utc) - dt).total_seconds() / 3600.0
    print(f"{age:.1f}h")
except Exception:
    print("-")
PY
else echo "-"; fi)"
    # 1.359：--list 附备份工具版本（审计溯源，1.336/1.356 已写入 METADATA）
    printf '%-60s created=%s age=%s version=%s tool=%s\n' "$(basename "$dir")" "${created:-unknown}" "$age" "${version:-unknown}" "${tool_version:-unknown}"
  done
  exit 0
fi

# 1.238：--dry-run 只校验 docker + compose 配置可用，不停止服务、不写备份
if (( dry_run )); then
  docker compose version >/dev/null 2>&1 || { echo "FAIL: docker compose not available" >&2; exit 1; }
  "${compose[@]}" config --quiet || { echo "FAIL: compose config invalid" >&2; exit 1; }
  echo "Dry-run: docker + compose config OK."
  # 1.326：dry-run 也报告磁盘空间（复用同一预检，只读不写），部署前一次性发现空间不足
  dry_min_free_mb="${BACKUP_MIN_FREE_MB:-1024}"
  dry_free_mb="$(df -Pm "$backup_root" 2>/dev/null | awk 'NR==2{print $4}' || true)"
  if [[ "$dry_free_mb" =~ ^[0-9]+$ ]] && (( dry_free_mb < dry_min_free_mb )); then
    echo "WARN: low disk space on $backup_root (${dry_free_mb}MB < ${dry_min_free_mb}MB required). Actual backup may fail."
  elif [[ -n "$dry_free_mb" && "$dry_free_mb" =~ ^[0-9]+$ ]]; then
    echo "Disk space OK: ${dry_free_mb}MB available on $backup_root."
  else
    echo "WARN: could not determine free space (df unavailable); skipping disk check."
  fi
  echo "Would create: $backup_root/maodouchat-$timestamp${backup_tag:+-$backup_tag}"
  echo "No services stopped, nothing written."
  exit 0
fi

# 1.285：磁盘空间预检——备份前确认目标盘可用空间 ≥ 预留阈值，
# 避免 pg_dump 写满磁盘导致服务不可用（docker compose stop 之后才失败）。
min_free_mb="${BACKUP_MIN_FREE_MB:-1024}"
free_mb="$(df -Pm "$backup_root" 2>/dev/null | awk 'NR==2{print $4}' || true)"
if [[ "$free_mb" =~ ^[0-9]+$ ]] && (( free_mb < min_free_mb )); then
  echo "FAIL: not enough free space on $backup_root (${free_mb}MB < ${min_free_mb}MB required). Set BACKUP_MIN_FREE_MB to override." >&2
  exit 1
fi
if [[ -n "$free_mb" && "$free_mb" =~ ^[0-9]+$ ]]; then
  echo "Disk space OK: ${free_mb}MB available on $backup_root (min ${min_free_mb}MB)."
else
  echo "WARN: could not determine free space on $backup_root (df unavailable); skipping disk preflight."
fi

cleanup() {
  status=$?
  if (( services_stopped )); then
    "${compose[@]}" up -d server proxy >/dev/null || true
  fi
  if (( status != 0 )); then
    rm -rf -- "$partial_dir"
  fi
  exit "$status"
}
trap cleanup EXIT

echo "Stopping public writes for a consistent backup..."
"${compose[@]}" stop proxy server
services_stopped=1

"${compose[@]}" exec -T db sh -ec \
  'exec pg_dump --create --format=custom -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  > "$partial_dir/database.dump"

# 1.240：校验 dump 可读（pg_restore --list），捕获损坏备份
"${compose[@]}" exec -T db sh -ec \
  'exec pg_restore --list' < "$partial_dir/database.dump" >/dev/null 2>&1 || {
  echo "FAIL: database dump is not a valid pg_custom archive" >&2
  exit 1
}

"${compose[@]}" run --rm --no-deps -T --entrypoint tar server \
  -C /app/uploads -czf - . > "$partial_dir/uploads.tar.gz"

"${compose[@]}" run --rm --no-deps -T --entrypoint tar proxy \
  -C /data -czf - . > "$partial_dir/caddy-data.tar.gz"

# 1.220：记录代码版本，便于恢复时核对部署一致性
code_version="$(git describe --always --tags --dirty 2>/dev/null || echo "-")"
# 1.336：记录备份工具版本与源主机（恢复/审计时确认备份来源）
# 1.356：工具版本跟随仓库版本，避免硬编码过期
backup_tool_version="backup-tool@${code_version}"
backup_hostname="$(hostname 2>/dev/null || echo 'unknown')"

cat > "$partial_dir/METADATA.txt" <<EOF
created_at_utc=$timestamp
format_version=1
code_version=$code_version
backup_tool_version=$backup_tool_version
backup_hostname=$backup_hostname
database=PostgreSQL custom format with CREATE DATABASE
uploads=encrypted attachments and protected media
caddy_data=TLS certificates and ACME state
EOF

(
  cd "$partial_dir"
  sha256sum database.dump uploads.tar.gz caddy-data.tar.gz METADATA.txt > SHA256SUMS
)

mv -- "$partial_dir" "$final_dir"
"${compose[@]}" up -d server proxy
services_stopped=0
trap - EXIT

# 1.25：备份保留策略——默认保留最近 14 份（BACKUP_KEEP 可覆盖，shell 环境变量或 .env），
# 按时间戳由旧到新清理超出的；1.187：--keep N 优先级最高；1.209：--no-prune 跳过清理
if (( no_prune )); then
  echo "Skipping backup pruning (--no-prune)."
else
keep="${keep_override:-${BACKUP_KEEP:-}}"
if [[ -z "$keep" && -f "$env_file" ]]; then
  keep="$(sed -n 's/^BACKUP_KEEP=//p' "$env_file" | head -n1 | tr -d '"')"
fi
keep="${keep:-14}"
if [[ "$keep" =~ ^[0-9]+$ ]] && (( keep > 0 )); then
  mapfile -t backup_dirs < <(find "$backup_root" -maxdepth 1 -type d -name 'maodouchat-*' | sort)
  excess=$(( ${#backup_dirs[@]} - keep ))
  if (( excess > 0 )); then
    for dir in "${backup_dirs[@]:0:$excess}"; do
      echo "Pruning old backup: $dir"
      rm -rf -- "$dir"
    done
  fi
fi
fi

echo "Backup complete: $final_dir"
echo "Keep this directory private; it contains account metadata and TLS private keys."
