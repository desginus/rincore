---
schema: rincore-delivery/v1
agent: integration-agent
task: 3.2.0 非手机项收口 + 手机操控 P1（A11y + DSH 三级点击 + 截图 + 拦截）
version: 5.3.77
base_commit: e760bb9fe
head_commit: c603b87e3
# 发布后另有纯文档交接提交；最新 HEAD 以 git 为准
status: MERGED
evidence: E5
started_at: 2026-11-13 19:30
finished_at: 2026-11-13 22:35
---

# v5.3.77 交付：手机操控 P1 + 3.2.0 非手机收口

> 本批从 v5.3.74（e760bb9fe）起算，覆盖 v5.3.75/76/77。

## TL;DR

- 已交付：手机操控的 **A11y 主路径**（读屏、DSH 三级点击、手势、输入、按键、应用、截图、支付/银行拦截），以及 3.2.0 非手机项（自动压缩、Claude/Mistral、通知/CA/FGS/调试/提供商/Web UI）。
- 现在能不能用：**代码与发布产物已就绪；手机操控尚未真机验证**。这是当前最高优先级。
- 最重要的风险：手机操控只有 **A11y + 截图视觉网格**，没有 Mika 的 ADB/Shizuku shell 与 OCR；不应向用户宣称“三传输路径已齐”。
- 需要谁决策：先由用户真机验证 P1；再决定 P2 接 shell 还是 OCR（建议先 shell，或按真机失败场景决定）。

## 范围

### 改了什么

- 手机操控：
  - 新增 `phoneuse/PhoneController.kt`、`PhoneUseAccessibilityService.kt`、`GestureIndicator.kt`。
  - 新增 `PhoneUseTool.kt`、`PhoneUseToolUI.kt`。
  - 注册 `LocalToolOption.PhoneUse`、`LocalTools`、Koin `PhoneUseManager`、`ToolUIRegistry`。
  - 主 Manifest 注册无障碍服务；新增 `phone_use_accessibility.xml`。
  - 助手本地工具页新增“手机操控”入口与无障碍跳转；`SettingBuiltinToolsPage` 加入系统设备组。
  - 点击采用 DSH 三级：节点 → 可点击祖先 → 坐标手势。
  - 支付/银行类包名硬拦截；本应用前台时拒绝操作；悬浮窗建议但不强制。
- 3.2.0 非手机项：
  - 自动压缩：回合结束 + 工具轮中途 `pauseAfterTools` 语义。
  - Claude `redacted_thinking` / `signature_delta`；Mistral content thinking / 422。
  - 通知权限、用户 CA、specialUse 前台服务、500k 调试对话、默认提供商/STA1N、Web UI lastUsage。
  - 快捷消息追加/长按替换、模型列表手动刷新（v5.3.74/75）。

### 没改什么

- 没有接 ADB/Shizuku shell。
- 没有接 OCR 视觉识别；视觉路径 = 截图 + 0~1000 网格，由模型选坐标。
- 没有做 ChatListPreview 的 `produceState + Dispatchers.Default` 长列表重构。
- 没有做 Mika 双模式；v5.3.51 已回撤，当前单一工作模式。
- 没有做“用户画像”记忆；记忆按工作知识组织。

## 变更文件

| 文件 | 改动 | 原因 |
|---|---|---|
| `app/src/main/java/me/rerere/rikkahub/phoneuse/PhoneController.kt` | 新增 | 控制器接口 + 三级点击约定 |
| `app/src/main/java/me/rerere/rikkahub/phoneuse/PhoneUseAccessibilityService.kt` | 新增 | 无障碍读屏/手势/输入/截图/应用 |
| `app/src/main/java/me/rerere/rikkahub/phoneuse/GestureIndicator.kt` | 新增 | 无障碍覆盖层落点提示 |
| `app/.../data/ai/tools/local/PhoneUseTool.kt` | 新增 | `phone_use` 工具 |
| `app/.../ui/components/message/tools/PhoneUseToolUI.kt` | 新增 | 工具卡片 |
| `app/.../data/ai/tools/local/LocalToolOption.kt` | +1 | 新增 PhoneUse 选项 |
| `app/.../data/ai/tools/local/LocalTools.kt` | +23 | 注入 phoneUseManager/filesManager + 工具 |
| `app/.../di/AppModule.kt` | +5 | Koin 注册 |
| `app/.../ui/components/message/tools/ToolUI.kt` | +1 | 注册 UI |
| `app/.../ui/pages/assistant/detail/AssistantLocalToolPage.kt` | +36 | 设置入口/状态/无障碍跳转 |
| `app/.../ui/pages/setting/SettingBuiltinToolsPage.kt` | +3 | 系统设备组 + label/desc |
| `app/.../data/ai/tools/routing/ToolZone.kt` | +1 | `phone_use` 归类关键词 |
| `app/src/main/AndroidManifest.xml` | +22 | 无障碍服务声明 |
| `app/src/main/res/xml/phone_use_accessibility.xml` | 新增 | 服务配置 |
| `app/src/main/res/values/strings.xml` | +30 | 入口/动作/无障碍文案 |
| `app/.../data/ai/GenerationHandler.kt` | +14 | `pauseAfterTools` 暂停点 |
| `app/.../service/ChatService.kt` | +83 | 中途自动压缩 + 去重 |
| `web-ui/...` | +35/-15 | lastUsage/stepCount 展示 |
| `docs/3.2.0-移植进度.md` | 更新 | 进度口径 |
| `docs/PHONE-CONTROL-DESIGN.md` | 新增 | 三方选型 |

## 验证

| 检查 | 命令 | 退出码/结果 | 证据等级 |
|---|---|---|---|
| 语法 | `tree-sitter` 手机/设置/压缩相关文件 | 0 ERROR/MISSING | E1 |
| 静态 | `bin/rc check` | PREFLIGHT-PASS + CI-CHECK-PASS | E1 |
| 全量体检 | `bin/audit` | AUDIT-OK；wire 0；MAINIO 0 / COMPOSE-IO 0；render 26/26 | E3 |
| 环境 | `bin/doctor` | 14 通过 / 0 警告 / 0 失败 | E1 |
| 检查器 | `bin/selfcheck` | 14/14 探针 | E1 |
| CI | run `43441103268` | build + unit-tests success | E3 |
| 发布 | v5.3.77 | draft=false / APK 79,950,950 B / SHAs `c603b87e3` | E5 |
| 真机 | 未执行 | — | E0 |

## 未验证 / 已知风险

| 风险 | 触发条件 | 影响 | 建议 |
|---|---|---|---|
| 无障碍服务无法开启 | Android 13+ 受限设置/厂商限制 | 手机工具不可用 | 设置页跳转；必要时引导“允许受限设置” |
| 读屏为空 | 安全窗口/游戏画布/节点树缺失 | 模型看不到元素 | 用截图 + 网格；后续 OCR |
| 点击无效 | WebView/自绘控件/节点不可点击 | 动作失败 | 三级点击已回退手势；仍失败则用截图坐标 |
| 输入失败 | 输入法/自定义编辑框不支持 `ACTION_SET_TEXT` | 无法输入 | 后续 shell `input text` 或 OCR/坐标方案 |
| 后台启动应用被限制 | 厂商后台限制 | `open_app` 失败 | 引导用户手动打开；检查无障碍服务存活 |
| 本应用前台拒绝操作 | 用户没切走 | 工具报错 | 先 `open_app` 或 `press home` |
| 未真机验证 | 当前无设备 | 不能称为“已完成” | P0 按测试步骤验收 |
| shell/OCR 缺失 | 需要更强控制 | 真实能力低于 Mika | P2 分阶段接，先做可失败旁路 |

## 决策与取舍

- 先做 A11y 主路径，不把 ADB/Shizuku/OCR 混进同一版：手机权限链风险高，失败会拖累主链路。
- 三级点击采用 DSH 的 NODE → ANCESTOR → GESTURE，而不是只按坐标：真实成功率更高。
- 悬浮窗从硬前提改为建议：避免与“宠物/悬浮球”功能耦合导致手机工具不可用；落点仍由 `GestureIndicator` 显示。
- 支付/银行硬拦截保留：支付类应用一旦在屏，拒绝读屏/操作。
- 版本号按用户纪律只改第三位：5.3.76 → 5.3.77。

## 下一步

1. **P0 真机验证手机操控 P1**（见 `docs/手机操控-v5.3.77.md`）。
2. 根据真机失败场景决定：ADB/Shizuku shell（输入/截图/启动兜底）或 OCR（节点树为空）。
3. ChatListPreview 长列表性能：先拿 framestats，再做 `produceState + Dispatchers.Default`。
4. 31 条测试债逐条重写；全量 unit-tests 转硬门禁。
5. 更新 `KNOWN-ISSUES.md` 与主交接文档。

## 接手回执

```yaml
accepted_by:
accepted_at:
base_commit: c603b87e3
verified_commands:
open_questions:
```
