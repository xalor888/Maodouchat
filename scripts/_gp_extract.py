import re

SRC = "app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt"
DST = "app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailGroupPlay.kt"

lines = open(SRC, encoding="utf-8").read().splitlines()
# 1-indexed 行 5150-6871 -> 0-indexed 5149-6870
block = lines[5149:6871]

# 检查块内是否有 suspend fun 或其它特殊签名
suspend_fns = [l for l in block if re.match(r"^\s*suspend fun ", l)]
print("块内 suspend fun 数:", len(suspend_fns))
if suspend_fns:
    print("需人工处理:", suspend_fns[:3])

# 转换函数签名：fun xxx(...) -> internal fun ChatDetailViewModel.xxx(...)
def conv(l):
    m = re.match(r"^(\s*)(internal\s+)?(suspend\s+)?fun\s+([A-Za-z0-9_]+)(\(.*)$", l)
    if m:
        indent, _internal, suspend, name, rest = m.groups()
        prefix = "suspend " if suspend else ""
        return f"{indent}internal {prefix}fun ChatDetailViewModel.{name}{rest}"
    return l

out = []
fn_count = 0
for l in block:
    if re.match(r"^\s*(internal\s+)?(suspend\s+)?fun\s+[A-Za-z0-9_]+\(.*\)\s*\{?$", l) or re.match(r"^\s*(internal\s+)?(suspend\s+)?fun\s+[A-Za-z0-9_]+\(.*\)\s*\{?$", l):
        fn_count += 1
    out.append(conv(l))

print("转换函数数:", fn_count)

# 新文件头
header = """package com.maodouchat.ui.screen.chatdetail

import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.launch

/**
 * 群玩法发送逻辑（骰子/签到/投票/抽奖/真心话等 140+ 个游戏）。
 *
 * 以扩展函数形式挂在 [ChatDetailViewModel] 上，与 UI 调用点（viewModel.sendXxx()）
 * 语法完全一致；从 ChatDetailViewModel.kt 拆分而来，行为不变。
 */
"""

content = header + "\n".join(out) + "\n"
open(DST, "w", encoding="utf-8").write(content)
print("已写入:", DST, "行数:", len(out) + len(header.splitlines()))

# 从原 VM 删除 block
remaining = lines[:5149] + lines[6871:]
open(SRC, "w", encoding="utf-8").write("\n".join(remaining) + "\n")
print("原 VM 已删除 5150-6871，剩余行数:", len(remaining))
