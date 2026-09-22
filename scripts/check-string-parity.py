#!/usr/bin/env python3
"""Fail if values/ and values-en/ string resource names diverge (W5-03)."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ZH = ROOT / "app/src/main/res/values/strings.xml"
EN = ROOT / "app/src/main/res/values-en/strings.xml"
NAME = re.compile(r'<string\s+name="([^"]+)"')

# G220b：每侧 string 条数下限。实测 values/ 与 values-en/ 各约 2843 条，
# 取 1000 留有大量余量，又远高于任何「解析不到」的情形。
MIN_STRING_COUNT = 1000


def names(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    return set(NAME.findall(text))


def main() -> int:
    if not ZH.is_file() or not EN.is_file():
        print(f"missing strings file: {ZH} or {EN}", file=sys.stderr)
        return 2
    zh, en = names(ZH), names(EN)

    # G220b：**空扫描不得通过**。两个文件都在、但一条 string 都解析不出来时，
    # 原来的逻辑会算出 only_zh = only_en = 空集，于是打印「parity OK」并返回 0。
    # 实测把 names() 打成空集，main() 真的返回 0。
    # 什么情况下会走到这：string 元素写法被改（不再是 `<string name=`）、
    # 或 values/ 被拆成多个文件而本脚本仍只看 strings.xml。
    # 这些都需要人 consciously 更新门禁，而不是静默放行。
    if len(zh) < MIN_STRING_COUNT or len(en) < MIN_STRING_COUNT:
        print(
            f"string name parity check is vacuous: zh={len(zh)} en={len(en)}, "
            f"expected >= {MIN_STRING_COUNT} each. Refusing to pass.",
            file=sys.stderr,
        )
        return 1

    only_zh = sorted(zh - en)
    only_en = sorted(en - zh)
    print(f"zh={len(zh)} en={len(en)} only_zh={len(only_zh)} only_en={len(only_en)}")
    if only_zh:
        print("only in values:", ", ".join(only_zh[:40]), file=sys.stderr)
    if only_en:
        print("only in values-en:", ", ".join(only_en[:40]), file=sys.stderr)
    if only_zh or only_en:
        return 1
    print("string name parity OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
