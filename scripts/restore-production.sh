#!/usr/bin/env bash
set -Eeuo pipefail

umask 077
workspace="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$workspace"

if [[ $# -ne 2 || "$1" != "--confirm" && "$1" != "--dry-run" && "$1" != "--inspect" ]]; then
  echo "Usage: $0 --confirm /absolute/path/to/maodouchat-backup" >&2
  echo "       $0 --dry-run  /absolute/path/to/maodouchat-backup   # 只校验备份，不执行恢复" >&2
  echo "       $0 --inspect  /absolute/path/to/maodouchat-backup   # 1.248: 列出归档内容，不执行恢复" >&2
  echo "This replaces the current database, uploads and Caddy certificate state." >&2
  echo "Interactive terminals must type 'yes'; non-interactive use CONFIRM_RESTORE=yes." >&2
  exit 2
fi

# 1.191：--dry-run 只校验（文件完整性 + SHA-256 + 归档路径安全），不停止服务、不写入
dry_run=0
if [[ "$1" == "--dry-run" ]]; then
  dry_run=1
fi
# 1.248：--inspect 列出备份归档内容（tar -tzf），不执行恢复
inspect_only=0
if [[ "$1" == "--inspect" ]]; then
  inspect_only=1
fi
# 1.217：--skip-health-wait 跳过恢复后的健康等待（与 deploy.sh 一致）
skip_health_wait=0
if [[ "${SKIP_HEALTH_WAIT:-}" == "true" ]]; then
  skip_health_wait=1
fi

backup_dir="$(realpath "$2")"
env_file="${ENV_FILE:-.env}"
compose=(docker compose --env-file "$env_file")
required=(database.dump uploads.tar.gz caddy-data.tar.gz METADATA.txt SHA256SUMS)

for file in "${required[@]}"; do
  [[ -f "$backup_dir/$file" ]] || { echo "Missing backup file: $file" >&2; exit 1; }
done

(
  cd "$backup_dir"
  sha256sum -c SHA256SUMS
)

validate_archive() {
  local archive="$1"
  if tar -tzf "$archive" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
    echo "Unsafe path found in archive: $archive" >&2
    exit 1
  fi
}
validate_archive "$backup_dir/uploads.tar.gz"
validate_archive "$backup_dir/caddy-data.tar.gz"

# 1.248：--inspect 只列出归档内容（uploads + caddy-data），不执行恢复
if (( inspect_only )); then
  # 1.325：inspect 附备份元数据（created/version/format），与 dry-run 输出一致，便于核对
  # 1.364：附工具版本与源主机（1.336/1.356 写入 METADATA，审计溯源）
  inspect_version="$(sed -n 's/^code_version=//p' "$backup_dir/METADATA.txt" 2>/dev/null | head -n1)"
  inspect_created="$(sed -n 's/^created_at_utc=//p' "$backup_dir/METADATA.txt" 2>/dev/null | head -n1)"
  inspect_format="$(sed -n 's/^format_version=//p' "$backup_dir/METADATA.txt" 2>/dev/null | head -n1)"
  inspect_tool="$(sed -n 's/^backup_tool_version=//p' "$backup_dir/METADATA.txt" 2>/dev/null | head -n1)"
  inspect_host="$(sed -n 's/^backup_hostname=//p' "$backup_dir/METADATA.txt" 2>/dev/null | head -n1)"
  echo "备份元数据: created_at_utc=${inspect_created:-unknown} code_version=${inspect_version:-unknown} format_version=${inspect_format:-unknown} tool=${inspect_tool:-unknown} host=${inspect_host:-unknown}"
  echo "uploads.tar.gz 内容（前 20 项）:"
  tar -tzf "$backup_dir/uploads.tar.gz" | head -n 20
  echo "caddy-data.tar.gz 内容（前 20 项）:"
  tar -tzf "$backup_dir/caddy-data.tar.gz" | head -n 20
  echo "database.dump 大小: $(stat -c '%s' "$backup_dir/database.dump" 2>/dev/null || echo '?') bytes"
  echo "No services stopped, nothing restored."
  exit 0
fi

# 1.219：--dry-run 也校验 compose 配置（失败即中止，确保真实恢复不会被配置漂移卡住）
echo "Validating compose config..."
"${compose[@]}" config --quiet || {
  echo "FAIL: docker compose config invalid. Restore is not viable until this is fixed." >&2
  exit 1
}

if (( dry_run )); then
  echo "Dry-run: backup + compose config validated successfully."
  # 1.228：dry-run 打印备份元数据，便于核对
  backup_version="$(sed -n 's/^code_version=//p' "$backup_dir/METADATA.txt" 2>/dev/null | head -n1)"
  backup_created="$(sed -n 's/^created_at_utc=//p' "$backup_dir/METADATA.txt" 2>/dev/null | head -n1)"
  backup_format="$(sed -n 's/^format_version=//p' "$backup_dir/METADATA.txt" 2>/dev/null | head -n1)"
  echo "  backup: $backup_dir"
  echo "  created_at_utc: ${backup_created:-unknown}"
  echo "  code_version: ${backup_version:-unknown}"
  echo "  format_version: ${backup_format:-unknown}"
  # 1.346：dry-run 即暴露格式版本不兼容（与真实恢复 1.305 一致），避免停服后才发现
  if [[ -n "$backup_format" && "$backup_format" != "1" ]]; then
    echo "FAIL: backup format_version=$backup_format is not supported by this restore (expected 1)." >&2
    echo "      Upgrade scripts/restore-production.sh (or the whole repo) before restoring this backup." >&2
    exit 1
  fi
  echo "No services stopped, nothing restored."
  exit 0
fi

# 1.210：停止服务前先校验 compose 配置——避免配置漂移导致停服后无法拉起
echo "Validating compose config before stopping services..."
"${compose[@]}" config --quiet || {
  echo "FAIL: docker compose config invalid. Aborting restore; no services touched." >&2
  exit 1
}

# 1.241：停止服务前用 pg_restore --list 校验 dump 可读，捕获损坏备份
echo "Validating database.dump readability (pg_restore --list)..."
if ! "${compose[@]}" exec -T db sh -ec 'exec pg_restore --list' < "$backup_dir/database.dump" >/dev/null 2>&1; then
  echo "FAIL: database.dump is not a readable pg_custom archive. Aborting restore." >&2
  exit 1
fi

# 1.370：停止服务前校验 uploads/caddy tar 可读（与 dump 校验一致），
# 捕获损坏 tar 避免「停服+清空目录后才发现无法解压」
echo "Validating uploads.tar.gz / caddy-data.tar.gz readability (gzip -t)..."
for tar_file in uploads.tar.gz caddy-data.tar.gz; do
  if [[ ! -s "$backup_dir/$tar_file" ]]; then
    echo "FAIL: $tar_file is missing or empty. Aborting restore." >&2
    exit 1
  fi
  if ! gzip -t "$backup_dir/$tar_file" >/dev/null 2>&1; then
    echo "FAIL: $tar_file is not a valid gzip archive. Aborting restore." >&2
    exit 1
  fi
  if ! tar -tzf "$backup_dir/$tar_file" >/dev/null 2>&1; then
    echo "FAIL: $tar_file does not contain a readable tar listing. Aborting restore." >&2
    exit 1
  fi
done

# 1.294：破坏性恢复前的交互确认（terminal 上必须显式输入 yes，防误操作覆盖当前数据）
# 非交互/CI 场景用 CONFIRM_RESTORE=yes 显式跳过提示。
if [[ "${CONFIRM_RESTORE:-}" != "yes" ]]; then
  if [[ -t 0 ]]; then
    echo ""
    echo "WARNING: this will REPLACE the current database, uploads and Caddy TLS state"
    echo "with the contents of: $backup_dir"
    printf 'Type "yes" to continue: '
    read -r answer
    if [[ "$answer" != "yes" ]]; then
      echo "Aborted (no confirmation)." >&2
      exit 1
    fi
  else
    echo "FAIL: non-interactive restore requires CONFIRM_RESTORE=yes" >&2
    exit 1
  fi
fi

services_stopped=0
cleanup() {
  status=$?
  if (( services_stopped )); then
    "${compose[@]}" up -d server proxy >/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT

echo "Stopping public traffic and application writes..."
"${compose[@]}" stop proxy server
services_stopped=1

cat "$backup_dir/database.dump" | "${compose[@]}" exec -T db sh -ec \
  'exec pg_restore --clean --if-exists --create -U "$POSTGRES_USER" -d postgres'

gzip -dc "$backup_dir/uploads.tar.gz" | \
  "${compose[@]}" run --rm --no-deps -T --entrypoint sh server -ec \
  'find /app/uploads -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +; tar -C /app/uploads -xzf -'

gzip -dc "$backup_dir/caddy-data.tar.gz" | \
  "${compose[@]}" run --rm --no-deps -T --entrypoint sh proxy -ec \
  'find /data -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +; tar -C /data -xzf -'

"${compose[@]}" up -d server proxy
services_stopped=0
trap - EXIT

# 1.39：恢复后等待健康检查（与 deploy.sh 一致），避免用户误以为恢复成功而提前放行流量
# 1.90：HEALTH_TIMEOUT_SECONDS 可覆盖（默认 300s，与 deploy.sh 一致）
# 1.217：SKIP_HEALTH_WAIT=true 跳过等待
if (( skip_health_wait )); then
  echo "Skipping health wait (SKIP_HEALTH_WAIT=true)."
else
public_host="$(sed -n 's/^PUBLIC_HOST=//p' "$env_file" 2>/dev/null | head -n1 || true)"
health_url="${public_host:-localhost}"
health_timeout="${HEALTH_TIMEOUT_SECONDS:-300}"
# 1.370：与 status.sh 1.367 / deploy.sh 1.369 一致——本地部署（localhost/IP）用 http + 自签跟随，
# 真实域名保持严格 https 校验（防中间人）
health_scheme="https"
curl_insecure=""
if [[ "$health_url" == "localhost" || "$health_url" == "127.0.0.1" || "$health_url" == "::1" || "$health_url" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  health_scheme="http"
  curl_insecure="-kL"
fi
echo "Waiting for $health_scheme://$health_url/health/ready (up to ${health_timeout}s)..."
attempt=0
until curl -fsS $curl_insecure --max-time 10 "$health_scheme://$health_url/health/ready" 2>/dev/null | grep -q ready; do
  attempt=$((attempt + 1))
  if [[ $((attempt * 10)) -ge "$health_timeout" ]]; then
    echo "FAIL: server not ready after ${health_timeout}s. Check logs:" >&2
    "${compose[@]}" logs --tail=80 server >&2
    exit 1
  fi
  sleep 10
done
fi

# 1.226：恢复后核对备份代码版本 vs 当前版本
backup_version="$(sed -n 's/^code_version=//p' "$backup_dir/METADATA.txt" 2>/dev/null | head -n1)"
current_version="$(git describe --always --tags --dirty 2>/dev/null || echo "-")"
if [[ -n "$backup_version" && "$backup_version" != "$current_version" ]]; then
  echo "WARN: backup code_version=$backup_version differs from current=$current_version"
  echo "      Restore succeeded but the DB/attachments may be from an older deploy."
fi

# 1.305：备份格式版本兼容校验——未来 dump 布局变更时旧 restore 不应硬套
backup_format="$(sed -n 's/^format_version=//p' "$backup_dir/METADATA.txt" 2>/dev/null | head -n1)"
if [[ -n "$backup_format" && "$backup_format" != "1" ]]; then
  echo "FAIL: backup format_version=$backup_format is not supported by this restore (expected 1)." >&2
  echo "      Upgrade scripts/restore-production.sh (or the whole repo) before restoring this backup." >&2
  exit 1
fi

echo "Restore complete. Verify /health/ready and administrator login before reopening traffic."
