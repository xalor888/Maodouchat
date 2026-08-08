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


def names(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    return set(NAME.findall(text))


def main() -> int:
    if not ZH.is_file() or not EN.is_file():
        print(f"missing strings file: {ZH} or {EN}", file=sys.stderr)
        return 2
    zh, en = names(ZH), names(EN)
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
