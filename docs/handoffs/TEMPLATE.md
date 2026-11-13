---
schema: rincore-delivery/v1
agent: <agent-name>
task: <one line, <= 120 chars>
version: 5.3.x
base_commit: <sha>
head_commit: <sha>
status: DRAFT
evidence: E0
started_at: YYYY-MM-DD HH:mm
finished_at: YYYY-MM-DD HH:mm
---

# <任务标题>

## TL;DR

- 做了什么：
- 现在能不能用：
- 最重要的风险：
- 需要谁决策：

## 范围

### 改了什么

- 

### 没改什么（明确边界）

- 

## 变更文件

| 文件 | 改动 | 原因 |
|---|---|---|
| `path` | +N/-M |  |

## 验证

> 只写真实跑过的命令与退出码；没跑过的写“未跑”。

| 检查 | 命令 | 退出码/结果 | 证据等级 |
|---|---|---|---|
| 语法 | `tree-sitter` / `bin/rc check` |  | E1 |
| 纯逻辑 | `kotlinc` / 单测 |  | E2 |
| CI | run `<id>` |  | E3 |
| 真机 | 设备/操作 |  | E4 |
| 发布 | tag/APK/SHA |  | E5 |

## 未验证 / 已知风险

| 风险 | 触发条件 | 影响 | 建议 |
|---|---|---|---|
|  |  |  |  |

## 决策与取舍

- 
- 与用户既有定案是否冲突：否 / 是（说明）

## 下一步

1. 
2. 

## 接手回执

```yaml
accepted_by:
accepted_at:
base_commit:
verified_commands:
open_questions:
```
