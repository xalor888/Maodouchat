#!/usr/bin/env python3
"""Reject misspellings of the user-facing product name."""
from __future__ import annotations

import os
import re
import sys
from collections.abc import Iterator
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SELF = Path(__file__).resolve()
EXCLUDED_DIRS = {
    ".git",
    ".gradle",
    ".gradle-tmp",
    ".idea",
    ".kotlin",
    ".mimocode",
    ".trae",
    "_tools",
    "build",
    "node_modules",
    "tmp",
}
WRONG_TERMS = (
    re.compile(r"猫\s*豆"),
    re.compile(r"貓\s*豆"),
    re.compile(r"\\u\{?732b\}?\s*\\u\{?8c46\}?", re.IGNORECASE),
    re.compile(r"\\u\{?8c93\}?\s*\\u\{?8c46\}?", re.IGNORECASE),
    re.compile(r"&#x0*732b;?\s*&#x0*8c46;?", re.IGNORECASE),
    re.compile(r"&#x0*8c93;?\s*&#x0*8c46;?", re.IGNORECASE),
    re.compile(r"&#0*29483;?\s*&#0*35910;?"),
    re.compile(r"&#0*35987;?\s*&#0*35910;?"),
)


def source_files() -> Iterator[Path]:
    for current_root, dir_names, file_names in os.walk(ROOT):
        dir_names[:] = [name for name in dir_names if name.lower() not in EXCLUDED_DIRS]
        current_path = Path(current_root)
        for file_name in file_names:
            path = current_path / file_name
            if path.resolve() != SELF and path.stat().st_size <= 5 * 1024 * 1024:
                yield path


def main() -> int:
    violations: list[tuple[Path, int, str]] = []
    checked = 0
    for path in source_files():
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        checked += 1
        for line_number, line in enumerate(text.splitlines(), start=1):
            if any(pattern.search(line) for pattern in WRONG_TERMS):
                violations.append((path.relative_to(ROOT), line_number, line.strip()))

    if violations:
        print("Use the product name '毛豆聊天'; forbidden misspellings found:", file=sys.stderr)
        for path, line_number, line in violations:
            print(f"{path}:{line_number}: {line}", file=sys.stderr)
        return 1

    print(f"brand terminology OK ({checked} text files checked)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
