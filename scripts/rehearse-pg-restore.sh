#!/usr/bin/env bash
#
# 备份 → 恢复 演练（Q06）。
#
# 这个脚本**只在本机跑**，用真实的 PostgreSQL 复现生产 `scripts/backup-production.sh` +
# `scripts/restore-production.sh` 的数据库那一半，并做逐表行数比对。
# 它不会、也不能连接生产：所有库名都带 `maodou_rehearse_` 前缀，且只操作这些库。
#
# 为什么要它：生产备份脚本的恢复路径从未被真正演练过——「有备份」不等于「能恢复」。
# 这里用与生产**完全相同**的 pg_dump 参数（`--create --format=custom`）造备份，
# 用与生产**相同**的健全性检查（`pg_restore --list`）验归档，再恢复到一个 scratch 库
# 并逐表比对行数。另外包含负面用例：截断/损坏的 dump 必须被拒绝，而不是「通过」。
#
# 分工：本脚本覆盖「备份 → 恢复」这半程（pg 工具链 + 内容比对 + 坏备份拒绝）；
# 「恢复出来的旧副本 → 升级到最新版本」那半程由
# `server/src/test/.../PostgresRestoreUpgradeTest.kt`（@Tag("postgres")）覆盖——
# 它用同样的生产参数 dump 一个停在 v3 的库，恢复后跑完整迁移链并核对版本与数据。
# 两者在 CI 的 server job 里都会真跑，合起来才是完整演练。
#
# 用法：
#   scripts/rehearse-pg-restore.sh              # 跑完整演练
#   KEEP=1 scripts/rehearse-pg-restore.sh       # 保留临时库与工作目录，便于排查
#
# 环境变量：PGHOST/PGPORT/PGUSER（默认 127.0.0.1 / 5432 / 当前用户）

set -Eeuo pipefail

PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-$(id -un)}"
KEEP="${KEEP:-0}"

suffix="$(date -u +%Y%m%d%H%M%S)-$$"
src_db="maodou_rehearse_src_${suffix:-x}"
scratch_db="maodou_rehearse_scratch_${suffix:-x}"
workdir="$(mktemp -d "${TMPDIR:-/tmp}/maodou-rehearse-XXXXXX")"

failures=0
step() { printf '\n=== %s ===\n' "$*"; }
ok()   { printf 'PASS: %s\n' "$*"; }
bad()  { printf 'FAIL: %s\n' "$*" >&2; failures=$((failures + 1)); }

psql_super() { psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 "$@"; }
psql_db()    { psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$1" -v ON_ERROR_STOP=1 "${@:2}"; }

cleanup() {
  local status=$?
  if [[ "$KEEP" == "1" ]]; then
    printf '\nKEEP=1：保留 %s 与工作目录 %s\n' "$src_db/$scratch_db" "$workdir"
  else
    psql_super -q -c "DROP DATABASE IF EXISTS \"$src_db\"" >/dev/null 2>&1 || true
    psql_super -q -c "DROP DATABASE IF EXISTS \"$scratch_db\"" >/dev/null 2>&1 || true
    rm -rf -- "$workdir"
  fi
  exit "$status"
}
trap cleanup EXIT

step "预检"
for bin in psql pg_dump pg_restore createdb dropdb; do
  command -v "$bin" >/dev/null 2>&1 || { echo "missing required binary: $bin" >&2; exit 1; }
done
pg_isready -h "$PGHOST" -p "$PGPORT" >/dev/null 2>&1 || { echo "PostgreSQL not reachable at $PGHOST:$PGPORT" >&2; exit 1; }
echo "PostgreSQL $(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -tAc 'show server_version') at $PGHOST:$PGPORT"

step "造一个「生产形状」的源库 $src_db"
psql_super -q -c "CREATE DATABASE \"$src_db\""
psql_db "$src_db" -q <<'SQL'
CREATE TABLE schema_migrations (
    version INTEGER PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    installed_at BIGINT NOT NULL
);
CREATE TABLE users (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    email VARCHAR(200) NOT NULL,
    is_online BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE chats (id VARCHAR(64) PRIMARY KEY, title VARCHAR(200));
CREATE TABLE messages (
    id VARCHAR(64) PRIMARY KEY,
    chat_id VARCHAR(64) NOT NULL REFERENCES chats(id),
    sender_id VARCHAR(64) NOT NULL REFERENCES users(id),
    ciphertext TEXT NOT NULL
);
CREATE INDEX idx_messages_chat ON messages(chat_id);
INSERT INTO schema_migrations VALUES (1,'baseline',1),(2,'retire legacy',2),(3,'backfill pairs',3);
INSERT INTO users SELECT 'u'||g, 'user '||g, 'u'||g||'@example.test', g % 2 = 0 FROM generate_series(1,200) g;
INSERT INTO chats SELECT 'c'||g, 'chat '||g FROM generate_series(1,50) g;
INSERT INTO messages SELECT 'm'||g, 'c'||((g % 50) + 1), 'u'||((g % 200) + 1), repeat('x', 64) FROM generate_series(1,5000) g;
SQL
echo "源库就绪：$(psql_db "$src_db" -tAc "SELECT (SELECT count(*) FROM users)||' users / '||(SELECT count(*) FROM messages)||' messages'")"

TABLES=(schema_migrations users chats messages)

step "备份（与生产 backup-production.sh 相同的参数）"
pg_dump -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$src_db" \
  --create --format=custom > "$workdir/database.dump"
echo "dump 大小: $(wc -c < "$workdir/database.dump") bytes"

step "归档健全性检查（与生产脚本一致：pg_restore --list）"
if pg_restore --list "$workdir/database.dump" >/dev/null 2>&1; then
  ok "pg_restore --list 能读出归档"
else
  bad "pg_restore --list 读不出归档"
fi

step "恢复到 scratch 库 $scratch_db"
psql_super -q -c "CREATE DATABASE \"$scratch_db\""
if pg_restore -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$scratch_db" \
     --no-owner --no-privileges "$workdir/database.dump" >"$workdir/restore.log" 2>&1; then
  ok "pg_restore 成功（含 --create 归档恢复到已存在的目标库）"
else
  bad "pg_restore 失败，见 $workdir/restore.log"
  tail -5 "$workdir/restore.log" >&2 || true
fi

step "逐表行数比对（源库 vs 恢复库）"
for table in "${TABLES[@]}"; do
  src_count="$(psql_db "$src_db" -tAc "SELECT count(*) FROM $table")"
  dst_count="$(psql_db "$scratch_db" -tAc "SELECT count(*) FROM $table" 2>/dev/null || echo missing)"
  if [[ "$src_count" == "$dst_count" ]]; then
    ok "$table: $src_count 行一致"
  else
    bad "$table: 源库 $src_count 行，恢复库 $dst_count 行"
  fi
done

step "内容抽查（不只比行数）"
src_sample="$(psql_db "$src_db" -tAc "SELECT ciphertext FROM messages WHERE id='m1'")"
dst_sample="$(psql_db "$scratch_db" -tAc "SELECT ciphertext FROM messages WHERE id='m1'" 2>/dev/null || echo missing)"
[[ "$src_sample" == "$dst_sample" && -n "$src_sample" ]] && ok "messages.m1 内容一致" || bad "messages.m1 内容不一致"

src_fk="$(psql_db "$src_db" -tAc "SELECT count(*) FROM pg_constraint WHERE contype='f' AND conrelid='messages'::regclass")"
dst_fk="$(psql_db "$scratch_db" -tAc "SELECT count(*) FROM pg_constraint WHERE contype='f' AND conrelid='messages'::regclass" 2>/dev/null || echo missing)"
[[ "$src_fk" == "$dst_fk" && "$src_fk" != "0" ]] && ok "messages 外键数量一致（${src_fk}）" || bad "外键数量不一致：源库 $src_fk / 恢复库 $dst_fk"

src_idx="$(psql_db "$src_db" -tAc "SELECT count(*) FROM pg_indexes WHERE tablename='messages'")"
dst_idx="$(psql_db "$scratch_db" -tAc "SELECT count(*) FROM pg_indexes WHERE tablename='messages'" 2>/dev/null || echo missing)"
[[ "$src_idx" == "$dst_idx" && "$src_idx" != "0" ]] && ok "messages 索引数量一致（${src_idx}）" || bad "索引数量不一致：源库 $src_idx / 恢复库 $dst_idx"

# ── 负面用例：坏备份必须被拒绝 ───────────────────────────────────────────────
# 这些是演练的核心价值：如果坏 dump 也能「通过」，那这个演练只是在自我安慰。

step "负面用例 1：截断的 dump 必须被拒绝"
head -c $(( $(wc -c < "$workdir/database.dump") / 2 )) "$workdir/database.dump" > "$workdir/truncated.dump"
# 已知局限（本演练实测出来的）：--list 只读归档尾部的目录，截断照样返回 0。
# 生产脚本原先只用 --list，所以坏备份会被当好的收下。这里先把它记下来。
if pg_restore --list "$workdir/truncated.dump" >/dev/null 2>&1; then
  echo "NOTE: pg_restore --list 通过了截断的 dump —— 它只校验目录，不能当作完整性检查"
fi
# 真正的完整性检查：整档读一遍。
if pg_restore -f /dev/null "$workdir/truncated.dump" >/dev/null 2>&1; then
  bad "截断的 dump 通过了整档读"
else
  ok "截断的 dump 被整档读拒绝（--list 拒绝不了它）"
fi
if pg_restore -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$scratch_db" \
     --no-owner --no-privileges "$workdir/truncated.dump" >/dev/null 2>&1; then
  bad "截断的 dump 竟然恢复了"
else
  ok "截断的 dump 恢复被拒绝"
fi

step "负面用例 2：内容损坏的 dump 必须被拒绝"
cp "$workdir/database.dump" "$workdir/corrupt.dump"
size="$(wc -c < "$workdir/corrupt.dump")"
printf 'ZZZZ' | dd of="$workdir/corrupt.dump" bs=1 seek=$(( size / 2 )) conv=notrunc status=none
if pg_restore --list "$workdir/corrupt.dump" >/dev/null 2>&1; then
  echo "NOTE: pg_restore --list 通过了损坏的 dump —— 同上，它只看目录"
fi
if pg_restore -f /dev/null "$workdir/corrupt.dump" >/dev/null 2>&1; then
  bad "损坏的 dump 通过了整档读"
else
  ok "损坏的 dump 被整档读拒绝"
fi

step "负面用例 3：恢复到不存在的目标库必须失败"
if pg_restore -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "maodou_rehearse_nope_$suffix" \
     --no-owner --no-privileges "$workdir/database.dump" >/dev/null 2>&1; then
  bad "恢复到不存在的库竟然成功了"
else
  ok "恢复到不存在的库被拒绝"
fi

step "结论"
if (( failures == 0 )); then
  echo "演练通过：备份可恢复、内容与行数一致、坏备份会被拒绝。"
else
  echo "演练失败：$failures 项检查未通过。" >&2
  exit 1
fi
