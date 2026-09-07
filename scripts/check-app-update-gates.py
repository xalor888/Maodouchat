#!/usr/bin/env python3
"""P09 门禁：应用更新发布渠道 / 签名 / 回滚策略文档与代码锚点。

用法：python3 scripts/check-app-update-gates.py（仓库根目录）。
防止文档或核心策略类被静默删除，导致“禁止降级 / 官方 HTTPS / 安装签名校验”失去事实源。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

REQUIRED_PATHS = [
    "docs/app-update-release.md",
    "app/src/main/java/com/maodouchat/update/AppUpdatePolicy.kt",
    "app/src/main/java/com/maodouchat/update/AppUpdateInstallPolicy.kt",
    "app/src/main/java/com/maodouchat/update/AppUpdateDownloadRetryPolicy.kt",
    "app/src/main/java/com/maodouchat/update/OfficialApkInstaller.kt",
    "app/src/test/java/com/maodouchat/update/AppUpdatePolicyTest.kt",
    "app/src/test/java/com/maodouchat/update/AppUpdateInstallPolicyTest.kt",
    "app/src/test/java/com/maodouchat/update/AppUpdateDownloadRetryPolicyTest.kt",
    "server/src/main/kotlin/com/maodouchat/server/update/AppUpdatePublishPolicy.kt",
    "server/src/test/kotlin/com/maodouchat/server/update/AppUpdatePublishPolicyTest.kt",
]

REQUIRED_SNIPPETS = [
    (
        "app/src/main/java/com/maodouchat/update/AppUpdatePolicy.kt",
        "fun shouldOfferUpdate",
    ),
    (
        "app/src/main/java/com/maodouchat/update/AppUpdatePolicy.kt",
        "fun isOfficialApkUrl",
    ),
    (
        "app/src/main/java/com/maodouchat/update/AppUpdateInstallPolicy.kt",
        "fun acceptsArchiveVersion",
    ),
    (
        "app/src/main/java/com/maodouchat/update/AppUpdateInstallPolicy.kt",
        "fun isPackageAndSignerTrusted",
    ),
    (
        "server/src/main/kotlin/com/maodouchat/server/update/AppUpdatePublishPolicy.kt",
        "fun isDowngrade",
    ),
    (
        "docs/app-update-release.md",
        "回滚策略",
    ),
    (
        "docs/app-update-release.md",
        "签名证书信任",
    ),
]


def main() -> int:
    errors: list[str] = []
    for rel in REQUIRED_PATHS:
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            errors.append(f"missing file: {rel}")
    for rel, needle in REQUIRED_SNIPPETS:
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            continue
        text = open(path, encoding="utf-8").read()
        if needle not in text:
            errors.append(f"{rel}: missing snippet {needle!r}")
    if errors:
        print("APP UPDATE GATES CHECK FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(
        f"app update gates OK "
        f"({len(REQUIRED_PATHS)} files, {len(REQUIRED_SNIPPETS)} snippets)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
