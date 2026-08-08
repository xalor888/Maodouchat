#!/usr/bin/env bash
# Maodouchat 一键更新脚本
#
# 用法（服务器上，仓库根目录）：
#   bash scripts/update.sh
#
# 自动完成：更新前自动备份（DB + uploads + Caddy）→ git pull（快进合并）→
# 重新构建镜像并部署（deploy.sh 默认 --build，确保 git pull 的代码真正生效）。
# 1.16 修复：此前用 deploy.sh --no-build 复用旧镜像，git pull 拉下的服务端代码从未
# 进入容器（Dockerfile 构建时 COPY server/src 烘焙进镜像，无源码卷挂载）。
# 依赖 deploy.sh 的幂等性：已有 .env 时自动复用（PUBLIC_HOST/BASE_URL/密钥 均不覆盖）。
# 若 git pull 有本地改动冲突会中止，请先处理冲突。
# 1.189：--skip-backup 跳过更新前备份；--skip-verify 跳过离线拓扑校验（默认均执行）
# 1.216：其余参数透传给 deploy.sh（如 --skip-health-wait / --no-build）
# 1.298：--version 打印当前/目标版本后退出；git 仓库前置守卫（非 git 检出直接给出修复指引）
set -euo pipefail

skip_backup=0
skip_verify=0
deploy_extra=()
for arg in "$@"; do
  case "$arg" in
    --skip-backup) skip_backup=1 ;;
    --skip-verify) skip_verify=1 ;;
    --version)
      echo "update.sh current=$(git describe --always --tags --dirty 2>/dev/null || echo 'unknown')"
      echo "  target: $(git ls-remote --heads origin HEAD 2>/dev/null | awk '{print $1}' | head -n1 || echo 'unknown (no origin)')"
      exit 0
      ;;
    *) deploy_extra+=("$arg") ;;
  esac
done

workspace="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$workspace"

if [[ ! -f .env ]]; then
  echo "FAIL: .env not found. Run deploy.sh first (bash scripts/deploy.sh)" >&2
  exit 1
fi

# 1.298：git 检出前置守卫——非 git 部署（手工拷贝/解压）时 git pull 必然失败，
# 提前给出清晰指引而非一串 git 报错
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "FAIL: not a git working tree. update.sh requires a git checkout of the Maodouchat repo." >&2
  echo "  If you copied files manually, re-clone instead: git clone <repo-url> && run deploy.sh again." >&2
  exit 1
fi

# 1.365：远程/上游分支前置守卫——无 origin 或 HEAD 无上游跟踪分支时 git pull 报错晦涩，
# 提前给出修复指引
if ! git remote get-url origin >/dev/null 2>&1; then
  echo "FAIL: no 'origin' remote configured. update.sh pulls from origin." >&2
  echo "  Add it: git remote add origin <repo-url>" >&2
  exit 1
fi
upstream_branch="$(git rev-parse --abbrev-ref --symbolic-full-name @{u} 2>/dev/null || true)"
if [[ -z "$upstream_branch" ]]; then
  current_branch="$(git branch --show-current 2>/dev/null || echo '<none>')"
  echo "FAIL: HEAD ($current_branch) has no upstream tracking branch. git pull needs one." >&2
  echo "  Set it: git branch --set-upstream-to=origin/$current_branch" >&2
  exit 1
fi

# 1.345：未提交改动警告——本地改动会阻挡 ff-only pull 或使重建镜像与 HEAD 不一致
if ! { git diff --quiet && git diff --cached --quiet; }; then
  echo "WARN: working tree has uncommitted changes."
  echo "      git pull --ff-only may fail if they touch pulled files; the rebuilt image may not match HEAD."
  echo "      Commit or stash before continuing."
fi

# 0.86：更新前自动备份——更新失败/回滚时数据有保障（备份失败则中止更新）
if (( skip_backup )); then
  echo "== Skipping pre-update backup (--skip-backup) =="
else
  echo "== Backing up before update =="
  bash scripts/backup-production.sh
fi

echo "== Pulling latest code =="
before_head="$(git rev-parse HEAD 2>/dev/null || true)"
git pull --ff-only
after_head="$(git rev-parse HEAD 2>/dev/null || true)"
# 1.311：git pull 无变更（已是最新）时提示跳过重建，避免无谓的镜像构建/重启
if [[ -n "$before_head" && "$before_head" == "$after_head" ]]; then
  echo "Already up to date (HEAD=$after_head). No rebuild needed."
  echo "If you changed .env manually and want to apply it, run: bash scripts/deploy.sh --no-build"
  exit 0
fi
echo "Updated: $before_head -> $after_head"
# 1.328：打印本次更新的提交日志摘要（便于运维了解变更内容）
if [[ -n "$before_head" && -n "$after_head" && "$before_head" != "$after_head" ]]; then
  echo "== Commits in this update =="
  git log --oneline --no-decorate "$before_head..$after_head" 2>/dev/null | head -n 30 || true
fi

# 1.69：更新前离线校验部署拓扑（compose/Caddyfile/.env 键位等），配置漂移先暴露再构建
if (( skip_verify )); then
  echo "== Skipping topology verification (--skip-verify) =="
else
  echo "== Verifying production topology (offline) =="
  bash scripts/verify-production-topology.sh --offline
fi

echo "== Rebuilding image and redeploying =="
if (( ${#deploy_extra[@]} > 0 )); then
  echo "   extra deploy args: ${deploy_extra[*]}"
fi
exec bash scripts/deploy.sh "${deploy_extra[@]}"
