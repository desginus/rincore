# 手机操控：3.2.0 / Mika / DSH Go 三方对比与选型

> 2026-11-13。权重：**真实操控能力 70%**，**后续升级/检查/维护 30%**。

## 1. 三家的实际能力

### 3.2.0 PhoneUseTool（上游）
- 单一路径：**无障碍服务**（`app/src/github` 渠道）。
- 工具面：`phone_use` 单工具，动作 `observe/screenshot/tap/long_press/swipe/scroll/type/press/open_app/list_apps/wait`。
- 目标：元素 index（当前屏有效）+ 0~1000 网格坐标；每次动作返回新元素列表。
- 安全：支付/银行类包名硬黑名单；要求悬浮窗开启（用户能看见/随时停）。
- 优点：工具 schema 最稳、描述清楚、维护面小。
- 缺点：**只有无障碍一条路**；节点树缺失/安全窗口/游戏画布就无能为力；没有 ADB/Shizuku/OCR 兜底。

### Mika
- 三条实际路径：
  1. **无障碍**：`ScreenAccessibility`（窗口/节点/手势/截图）。
  2. **内置 ADB shell**：`BuiltinShellManager` + `AdbPairing` + `BuiltinShellConnection`（无线调试配对，跑 `input`/`screencap`/`am` 等）。
  3. **视觉/OCR 兜底**：`PhoneOcr` + `LazyPhoneScreen`，节点树不可用时从截图识别。
- 优点：真实操控能力最强；有配对/恢复/看门狗/能力状态；覆盖无节点树场景。
- 缺点：接入面大（ADB 配对、证书、前台服务、权限引导）；维护成本高；三条路径语义需要统一，否则模型难选。

### DSH Go
- 路径：**无障碍（A11y） + Shizuku/ADB shell + 视觉目标**（`a11y/Actuator`、`PhoneAndroidShell`、`VisualTargets`）。
- 点击逻辑最友好：`Actuator.click` 按 **NODE → ANCESTOR → GESTURE** 三级：
  1. 目标节点可点击 → 直接 `performAction(CLICK)`；
  2. 不可点击 → 向上找可点击祖先；
  3. 仍不行 → 在可见点派发手势。
  这比“只按坐标点”稳，也比“只按节点”覆盖广。
- 另有 `InjectGuard` 防注入回环、`ControlOverlay` 用户可见、`SafetyGate`/`SensitiveWords` 安全层。
- 缺点：代码量大（phone 目录 40+ 文件），Flutter/Dart 生态与我们的 Kotlin 不同，不能直接搬；只借架构与点击策略。

## 2. 选型结论

**采用“Mika 的三路径 + DSH 的点击策略 + 3.2.0 的工具 schema”**：

| 层 | 采用 | 理由 |
|---|---|---|
| 工具面 | 3.2.0 `phone_use` 单工具 | 模型只学一个工具；动作枚举稳定；元素 index + 0~1000 网格已被验证 |
| 点击 | DSH `NODE → ANCESTOR → GESTURE` | 真实成功率最高，且不增加模型负担 |
| 路径 | Mika 三路径（A11y / Shell / Visual） | 无障碍缺失、节点树为空、游戏画布都有兜底 |
| 安全 | 3.2.0 黑名单 + DSH 确认/敏感词 + 悬浮窗 | 支付/银行硬拦；不可逆动作先问 |
| 维护 | `PhoneController` 接口 + 能力自检 | 三条实现可独立替换；状态可观测 |

## 3. 推荐架构（Kotlin）

```
PhoneUseTool (3.2.0 schema)
   └─ PhoneSession (串行 Mutex, lastScreen)
        └─ PhoneController (interface)
             ├─ A11yPhoneController   (primary)
             ├─ ShellPhoneController  (ADB/Shizuku fallback)
             └─ VisualPhoneController (OCR/visual fallback)
```

- `observe()`：A11y 节点树优先；空/安全窗口 → Visual OCR；仍空 → 返回明确失败。
- `tap(element)`：A11y `Actuator.click` 三级；节点不存在 → 用最后一次 bounds 的可见点走 GESTURE；无 A11y → Shell `input tap`。
- `type()`：A11y `ACTION_SET_TEXT` 优先；不支持 → Shell `input text`（需转义）。
- `screenshot()`：A11y `takeScreenshot` 优先；失败 → Shell `screencap -p`。
- 每次动作返回新元素列表（3.2.0 语义），模型无需重复 observe。
- `PhoneCapabilityReport`：a11y/connected、shell/paired、visual/available、overlay/on；设置页与工具结果都能看到“现在走哪条路”。

## 4. 分阶段（不混做）

1. **P1（先做）**：移植 3.2.0 `phone_use` + A11y 单路径 + DSH 三级点击 + 安全黑名单。可失败旁路：A11y 不可用 → 工具返回明确指引，不影响聊天。
2. **P2**：接 Shell 路径（Mika 的 ADB 配对或 Shizuku，二选一先做；建议 ADB 配对，免 Shizuku 依赖），用于 input/screencap/am。
3. **P3**：接 Visual/OCR 兜底（Mika `PhoneOcr` 思路），只做“节点树为空/安全窗口”的只读观察与坐标点击。
4. **P4**：能力自检页 + 路径优先级可配置 + 失败回退日志。

**本批不实现手机操控代码**：先把 3.2.0 非手机项稳定，再按上述 P1→P4 做，避免把高风险权限链与自动压缩混在一版。
