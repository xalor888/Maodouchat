#!/usr/bin/env python3
"""Fail if the lint report has active (unbaselined) issues outside an allowlist.

为什么要有这一步（2026-09-27）：`LintBaselineRatchetTest` 只棘轮「基线文件里有多少条」，
**从未进过基线的活跃 issue 对它是不可见的**——本轮实测到 3 处活跃的 `UseKtx`
（AppLinkOpener 迁包、ChatDetailMessageActionsSheet 抽层后产生）在报告里挂了若干轮，
没有任何门禁出声。本脚本把「活跃集」也接进门禁。

- 白名单（依赖更新类 + 信息性提示）之外的任何活跃 issue → 退出码 1；
- 报告不存在 → 退出码 2（说明 lintDebug 没跑，别静默通过）。

用法：check-lint-active.py [report.xml]
默认 app/build/reports/lint-results-debug.xml（`:app:lintDebug` 的产物）。
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REPORT = ROOT / "app/build/reports/lint-results-debug.xml"

# 白名单：依赖更新是 dependabot 的决策域；LintBaseline* 是信息性提示（不算问题）。
ALLOW = {
    "GradleDependency",
    "NewerVersionAvailable",
    "LintBaseline",
    "LintBaselineFixed",
}


def main() -> int:
    report = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_REPORT
    if not report.is_file():
        print(f"✋ lint 报告不存在：{report}——先跑 `./gradlew :app:lintDebug` 再执行本守卫。")
        return 2

    bad = []
    for iss in ET.parse(report).getroot().iter("issue"):
        i = iss.get("id")
        if i in ALLOW:
            continue
        loc = iss.find("location")
        bad.append((
            i,
            iss.get("severity") or "?",
            loc.get("file") if loc is not None else "?",
            loc.get("line") if loc is not None else "?",
            (iss.get("message") or "")[:110],
        ))

    if bad:
        print(f"✋ 发现 {len(bad)} 条活跃（未进基线）lint issue——要么修掉，要么显式进基线"
              f"（进基线=接受为债务，棘轮只许缩）：")
        for i, sev, f, ln, msg in bad[:60]:
            print(f"  [{sev}] {i}  {f}:{ln}  {msg}")
        if len(bad) > 60:
            print(f"  …… 另有 {len(bad) - 60} 条")
        return 1

    print("active lint guard OK：白名单之外零活跃 issue。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
