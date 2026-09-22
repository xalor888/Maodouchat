#!/usr/bin/env python3
"""把 DIRECTION.md §0 的数字按实测值改写（G188b）。

背景：§0 的表格本来就得靠人手同步，而我反复「推导」而不是实测
（被 DirectionDocFreshnessTest 抓到过四次：G156b / G172b / G183b / G185b）。
这个脚本把那一步机械化：跑一遍和门禁**完全相同**的测量，然后改写表格。

用法：
    python3 scripts/sync-direction-numbers.py --check   # 只报差异，不改文件
    python3 scripts/sync-direction-numbers.py --write   # 直接改写 DIRECTION.md

退出码：--check 模式下不一致返回 1（可当 CI 卡点）；--write 模式成功返回 0。
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIRECTION = os.path.join(ROOT, "DIRECTION.md")
CHECKLIST = os.path.join(ROOT, "docs", "full-project-refactor-checklist.md")

PLUGINS = "server/src/main/kotlin/com/maodouchat/server/plugins"
REPO = "server/src/main/kotlin/com/maodouchat/server/repository"
APP_MAIN = "app/src/main/java"


def sh(cmd: str) -> str:
    return subprocess.run(
        ["bash", "-c", cmd], cwd=ROOT, capture_output=True, text=True
    ).stdout.strip()


def count_kt(base: str) -> int:
    total = 0
    for dirpath, _dirs, files in os.walk(os.path.join(ROOT, base)):
        if os.sep + "build" + os.sep in dirpath + os.sep:
            continue
        total += sum(1 for f in files if f.endswith(".kt"))
    return total


def count_tests(pattern: str) -> int:
    import glob

    total = 0
    for p in glob.glob(os.path.join(ROOT, pattern)):
        with open(p, encoding="utf-8", errors="replace") as fh:
            head = fh.read(3000)
        m = re.search(r'tests="(\d+)"', head)
        if m:
            total += int(m.group(1))
    return total


def _plausible(n: int, floor: int, label: str) -> int:
    """用例数低于 floor 说明上一次不是全量跑——这时宁可不返回，也不要写错。"""
    if n < floor:
        raise SystemExit(
            f"拒绝同步：{label} 用例数只有 {n}，低于合理下限 {floor}。"
            "这通常意味着上一次跑的是过滤后的子集"
            "（如 --tests '*某个类'）。请先跑一次全量再同步。"
        )
    return n


def worst_file() -> tuple[int, str]:
    worst = (0, "")
    for dirpath, _dirs, files in os.walk(os.path.join(ROOT, APP_MAIN)):
        if os.sep + "build" + os.sep in dirpath + os.sep:
            continue
        for f in files:
            if not f.endswith(".kt"):
                continue
            p = os.path.join(dirpath, f)
            n = sum(1 for _ in open(p, encoding="utf-8", errors="replace"))
            if n > worst[0]:
                worst = (n, os.path.relpath(p, os.path.join(ROOT, APP_MAIN)))
    return worst


def measure() -> dict[str, list[tuple[int, str]]]:
    """返回「行首特征 -> [(该行第几个数字, 新值), ...]」——只换数字，保留人类可读装饰。"""
    worst_n, worst_p = worst_file()
    server_kt = count_kt("server/src/test")
    app_kt = count_kt("app/src/test")
    # ⚠️ 用例数只能来自**上一次全量跑**的 XML。若刚跑过 `--tests '*某个类'`
    # 过滤，目录里只剩那一个类的结果（实测过：11 而不是 1959）——
    # 照抄会把 DIRECTION.md 写坏。所以加下限守卫，宁可不动也不写错。
    server_tests = _plausible(
        count_tests("server/build/test-results/test/*.xml"), 400, "server"
    )
    app_tests = _plausible(
        count_tests("app/build/test-results/testDebugUnitTest/*.xml"), 1900, "app JVM"
    )
    txn = sh("grep -rho 'transaction {' %s | wc -l" % PLUGINS)
    exposed = sh("grep -rl org.jetbrains.exposed %s | wc -l" % PLUGINS)
    back = sh(
        "grep -rn 'com.maodouchat.server.plugins' %s | grep -c import" % REPO
    )
    services = sh("ls %s | grep -c 'Service.kt'" % REPO)
    repo_total = sh("ls %s/*.kt | wc -l" % REPO)
    return {
        "已跟踪文件": [(0, str(len(sh("git ls-files").splitlines())))],
        "服务端测试文件": [(0, str(server_kt)), (1, str(server_tests))],
        "客户端 JVM 测试文件": [(0, str(app_kt)), (1, str(app_tests))],
        "instrumented": [(0, str(count_kt("app/src/androidTest")))],
        "自审清单体量": [(0, f"{os.path.getsize(CHECKLIST):,}")],
        "transaction": [(0, txn), (1, txn)],
        "最差单文件": [(0, str(worst_n))],
        "import Exposed": [(0, exposed)],
        "反向依赖": [(0, back)],
        "Service.kt": [(0, services), (1, repo_total)],
    }


# 表格行首列的特征串 -> measure() 的键
ROW_KEYS = [
    ("已跟踪文件", "已跟踪文件"),
    ("服务端测试文件", "服务端测试文件"),
    ("客户端 JVM 测试文件", "客户端 JVM 测试文件"),
    ("instrumented", "instrumented"),
    ("自审清单体量", "自审清单体量"),
    ("transaction {", "transaction"),
    ("最差单文件", "最差单文件"),
    ("import Exposed", "import Exposed"),
    ("反向依赖", "反向依赖"),
    ("Service.kt", "Service.kt"),
]


def _replace_nth_number(cell: str, nth: int, value: str) -> tuple[str, bool]:
    """把 cell 里第 nth 个连续数字换成 value，其余文本原样保留。"""
    out, last, hits = [], 0, 0
    for m in re.finditer(r"\d[\d,]*", cell):
        out.append(cell[last:m.start()])
        out.append(value if hits == nth else m.group(0))
        hits += 1
        last = m.end()
    out.append(cell[last:])
    new = "".join(out)
    return new, new != cell


def sync(write: bool) -> int:
    vals = measure()
    lines = open(DIRECTION, encoding="utf-8").read().split("\n")
    changed = 0
    for idx, line in enumerate(lines):
        if not line.startswith("|"):
            continue
        # 必须按「未被反斜杠转义的 |」切分——Markdown 表格里 `\|` 是字面管道符
        # （§0 的来源列写着 `git ls-files \| wc -l`）。第一版直接 split("|")
        # 会把它拆坏成 `git ls-files \ | wc -l`。
        raw = line.strip()
        cells = [c.strip() for c in re.split(r"(?<!\\)\|", raw)]
        cells = [c for c in cells if c != ""]
        if len(cells) < 3:
            continue
        for prefix, key in ROW_KEYS:
            if prefix not in cells[0]:
                continue
            new_cell = cells[1]
            touched = False
            for nth, value in vals[key]:
                new_cell, ok = _replace_nth_number(new_cell, nth, value)
                touched = touched or ok
            if touched:
                print(f"  {cells[0][:40]:42s} {cells[1]!r:40s} -> {new_cell!r}")
                changed += 1
                if write:
                    lines[idx] = "| " + " | ".join([cells[0], new_cell] + cells[2:])
            break
    if write:
        # ⚠️ --write 模式下「有改动」是**成功**，必须返回 0。
        # 第一版无论哪个模式都 return 1 if changed，于是同步成功后
        # 调用方（finish-round.sh）看到非零，误报「同步被拒绝」并中止。
        if changed:
            open(DIRECTION, "w", encoding="utf-8").write("\n".join(lines))
            print(f"已改写 DIRECTION.md（{changed} 处）")
        else:
            print("§0 表格与实测一致，无需改动")
        return 0
    if not changed:
        print("§0 表格与实测一致，无需改动")
    return 1 if changed else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--check", action="store_true")
    g.add_argument("--write", action="store_true")
    a = ap.parse_args()
    sys.exit(sync(write=a.write))
