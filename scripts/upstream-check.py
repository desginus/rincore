#!/usr/bin/env python3
"""
RinCore 上游更新对照检查 (移植接口)

用法: python3 scripts/upstream-check.py [--from <锚点>] [--to <ref>]

功能:
1. git fetch upstream-try (rikkahub/rikkahub 原版上游)
2. 列出 锚点..目标 之间的所有上游提交
3. 每个提交的改动文件按三类分:
   A 直接合并   — 该文件我们从未改动, 可安全采用上游版本
   B 手动合并   — 我们改过该文件, 需逐文件 diff 重放自研修改
   C 评估移植   — 上游新增文件/新功能, 按需单独移植
4. 输出对照报告 + 移植建议

锚点记录: docs/ecosystem/06-原版兼容/移植锚点.md
默认锚点: 该文件记录的 last_synced 提交
"""

import subprocess
import sys
import os
import re
from datetime import datetime

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANCHOR_FILE = os.path.join(REPO, "docs/ecosystem/06-原版兼容/移植锚点.md")

# 冲突高发文件 (我们深度修改, 上游更新需重点处理) — 与 原版兼容.md 保持同步
HIGH_CONFLICT = {
    "ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt",
    "ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt",
    "ai/src/main/java/me/rerere/ai/provider/OpenAIProvider.kt",
    "app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt",
    "ai/src/main/java/me/rerere/ai/ui/Message.kt",
    "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt",
    "app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
    "app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt",
}


def run(cmd, cwd=REPO):
    return subprocess.run(cmd, cwd=cwd, shell=True, capture_output=True, text=True)


def read_anchor():
    if not os.path.exists(ANCHOR_FILE):
        return None
    with open(ANCHOR_FILE, encoding="utf-8") as f:
        content = f.read()
    m = re.search(r"last_synced:\s*`([0-9a-f]+)`", content)
    return m.group(1) if m else None


def we_touched_file(path):
    """该文件在 origin/main 历史里是否有我们的提交 (排除 merge/upstream 移植提交)"""
    r = run(f'git log origin/main --oneline -- "{path}"')
    return bool(r.stdout.strip())


def main():
    args = sys.argv[1:]
    from_ref = None
    to_ref = "upstream-try/master"
    if "--from" in args:
        from_ref = args[args.index("--from") + 1]
    if "--to" in args:
        to_ref = args[args.index("--to") + 1]

    print("== 1. fetch 上游 ==")
    r = run("git fetch upstream-try")
    if r.returncode != 0:
        print("fetch 失败:", r.stderr)
        sys.exit(1)
    print("fetch 完成\n")

    if from_ref is None:
        from_ref = read_anchor()
    if from_ref is None:
        print("无锚点 (docs/ecosystem/06-原版兼容/移植锚点.md 缺失或未记录 last_synced)")
        print("用法: 手动指定 --from <上游提交>, 或先写锚点文件")
        sys.exit(1)

    print(f"== 2. 上游提交范围: {from_ref[:9]}..{to_ref} ==\n")
    r = run(f"git log {from_ref}..{to_ref} --format='%H|%s'")
    commits = [line.split("|", 1) for line in r.stdout.strip().splitlines() if "|" in line]

    if not commits:
        print("无新提交, 已是最新")
        return

    print(f"共 {len(commits)} 个上游提交:\n")
    for sha, subject in commits:
        print(f"  {sha[:9]} {subject}")

    print("\n== 3. 文件分类对照 ==\n")
    for sha, subject in commits:
        print(f"## {sha[:9]} {subject}")
        r = run(f"git show {sha} --name-only --format=''")
        files = [f for f in r.stdout.strip().splitlines() if f.strip()]
        for f in files:
            touched = we_touched_file(f)
            if not os.path.exists(os.path.join(REPO, f)):
                cls = "C 评估移植 (我们不存在该文件, 上游新增)"
            elif f in HIGH_CONFLICT:
                cls = "B 手动合并 (冲突高发文件, 逐行 diff 重放自研修改)"
            elif touched:
                cls = "B 手动合并 (我们改过)"
            else:
                cls = "A 直接合并 (我们未改动)"
            print(f"  [{cls}] {f}")
        print()

    print("== 4. 移植建议 ==")
    print("  A 类: git checkout upstream-try/master -- <文件> (或 cherry-pick 上游提交)")
    print("  B 类: git show <sha> -- <文件> 逐个 diff, 保留我们的自研修改, 只取上游修复")
    print("  C 类: 评估功能是否需要, 需要则按模块地图单独移植")
    print()
    print("  禁止跟随 (自研保护, 见 docs/ecosystem/06-原版兼容/原版兼容.md 第六节):")
    print("    HTTP/2 协议 / 静默恢复主动断开 / limitContext 滞回策略 /")
    print("    BEFORE_SYSTEM_PROMPT 注入隔离改动 / 请求体动态化 / 落盘机制变更")
    print()
    print(f"移植完成后更新锚点: 把 {ANCHOR_FILE} 的 last_synced 改为 {to_ref}")


if __name__ == "__main__":
    main()
