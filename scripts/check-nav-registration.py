#!/usr/bin/env python3
"""P08 门禁：每个 Routes 常量恰好在一个 composable() 注册。

用法：python3 scripts/check-nav-registration.py（仓库根目录执行）。
NavGraph 拆分为 feature 簇后，漏注册/重复注册只能在真机暴露；
本脚本在 CI 静态锁定：NavRoutes.kt 的全部 const val 必须在
ui/navigation/*.kt 的 composable(route = Routes.X) 中恰出现一次。
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NAV = os.path.join(ROOT, "app", "src", "main", "java", "com", "maodouchat", "ui", "navigation")

consts = {}
routes_src = open(os.path.join(NAV, "NavRoutes.kt"), encoding="utf-8").read()
for m in re.finditer(r'const val (\w+) = "([^"]+)"', routes_src):
    consts[m.group(1)] = m.group(2)

regs: dict[str, list[str]] = {}
for path in glob.glob(os.path.join(NAV, "*.kt")):
    src = open(path, encoding="utf-8").read()
    for m in re.finditer(r"composable\(\s*(?:route\s*=\s*)?Routes\.(\w+)", src):
        regs.setdefault(m.group(1), []).append(os.path.basename(path))

errors = []
for name in sorted(consts):
    files = regs.get(name, [])
    if len(files) != 1:
        errors.append(f"{name}: registered in {files or 'NOWHERE'}")
unknown = sorted(set(regs) - set(consts))
if unknown:
    errors.append(f"registrations without Routes const: {unknown}")

if errors:
    print("NAV REGISTRATION CHECK FAILED:")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print(f"nav registration OK ({len(consts)} routes, each registered exactly once)")
