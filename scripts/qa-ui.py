#!/usr/bin/env python3
"""adb 驱动的轻量 UI 检查工具（仅本地开发用）。

用法：
  python3 scripts/qa-ui.py dump            # 打印当前界面可交互节点（文本 + 坐标）
  python3 scripts/qa-ui.py shot NAME       # 截图到 .qa-live/NAME.png
  python3 scripts/qa-ui.py tap X Y
  python3 scripts/qa-ui.py text STR        # 输入文本（需先点中输入框）
  python3 scripts/qa-ui.py log [-n 200]    # 打印最近崩溃/异常日志
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
OUT = os.path.join(ROOT, ".qa-live")
PKG = "com.maodouchat"


def adb(*args, check=False):
    serial = os.environ.get("QA_SERIAL", "")
    prefix = ["-s", serial] if serial else []
    return subprocess.run([ADB, *prefix, *args], capture_output=True, text=True, check=check)


def dump():
    os.makedirs(OUT, exist_ok=True)
    adb("shell", "uiautomator", "dump", "/sdcard/qa-ui.xml")
    adb("pull", "/sdcard/qa-ui.xml", os.path.join(OUT, "ui.xml"))
    tree = ET.parse(os.path.join(OUT, "ui.xml"))
    rows = []
    for node in tree.iter("node"):
        text = node.get("text") or ""
        desc = node.get("content-desc") or ""
        rid = (node.get("resource-id") or "").replace(PKG + ":id/", "")
        bounds = node.get("bounds") or ""
        if not (text or desc):
            continue
        rows.append(f"{bounds:<24} {rid:<28} {text!r} {desc!r}")
    print("\n".join(rows) if rows else "(no text nodes)")


def shot(name):
    os.makedirs(OUT, exist_ok=True)
    adb("shell", "screencap", "-p", "/sdcard/qa-shot.png")
    path = os.path.join(OUT, f"{name}.png")
    adb("pull", "/sdcard/qa-shot.png", path)
    print(path)


def log(n=200):
    out = adb("logcat", "-d", "-v", "brief").stdout
    lines = [l for l in out.splitlines() if "AndroidRuntime" in l or "FATAL EXCEPTION" in l
             or " E " in l or "AndroidRuntimeException" in l or "maodouchat" in l.lower()]
    print("\n".join(lines[-int(n):]))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    if cmd == "dump":
        dump()
    elif cmd == "shot":
        shot(sys.argv[2] if len(sys.argv) > 2 else "shot")
    elif cmd == "tap":
        print(adb("shell", "input", "tap", sys.argv[2], sys.argv[3]).stdout)
    elif cmd == "text":
        esc = sys.argv[2].replace(" ", "%s").replace("'", "")
        print(adb("shell", "input", "text", esc).stdout)
    elif cmd == "log":
        log(sys.argv[2] if len(sys.argv) > 2 else 200)
    elif cmd == "start":
        print(adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1").stdout)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
