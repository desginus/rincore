# B2 UI 批 · 执行计划（2026-10-19 起）

## 0. 结论先行（量化证据）
- UI 域裁决分布：上游赢 26 / 微差手看 85 / 手工合并 87 / 我方赢 12（`u300-adjudication-map.tsv`）。
- **26 个「上游赢」文件中，只有 6 个是我方零语义差异（纯旧样式/旧注释）**：CardGroup、SettingProviderBalanceOption(BalanceOption)、PropertyEditor、BackupPage、ReminderTab、QuickMessagesPage → **已全部切换上游**。
- 其余 20 个都含我方真实语义（51–537 行），**禁止整件 checkout**，必须逐文件合并（保持 v4.8.189 功能面）。

## 1. 已完成（commits，均在 adopt-3.0.0）
| 提交 | 内容 |
|---|---|
| 61f318af2 | B2.1 CardGroup(formItem)/PropertyEditor/SettingProviderBalanceOption（已随 317ab11ae 进 main） |
| 86285c1ce | B2.3 QuickMessagesPage → ExtensionEmptyState/CardGroupIcon/Card 容器 |
| 301c7bf3d | B2.2 BackupPage/ReminderTab → PagerTabs（S3/WebDav/Reminder 能力集合不变） |
| 11869d4fb | B3.1 翻译器整对切换：TranslationHandler + 历史分页 + 结果回填（DB v33/DAO/Repository 已具备） |
| 411928df6 | B2.4 WorkspacePage 空态/卡片（逻辑不变） |
| 8837c55cd | B2.5 AssistantExtensionsPage + 新增 ExtensionTab 枚举（ExtensionSelector 保留我方会话版） |
| 已提交 | B2.6 AssistantDetailPage（Cookie12Sided → CircleShape，遵守排除令） |

## 2. 剩余需合并的 20 件（按域）
- 设置页：SettingSpeechPage(537) / SettingProviderDetailPage(410) / SettingPreferencesUIPage(203) / SettingModelPromptPage(107) / ASRProviderConfigure(158)
- 消息与工具：ChatMessageActions(281) / BuiltinToolUIs(75)
- 助手详情：AssistantPage(51，保我方语义) / AssistantPromptPage(421)
- 备份/扩展：WebDavTab(438) / S3Tab(467) / ImportExportTab(146) / PromptPage(157)
- 其他：TranslatorPage(已切) / ExtensionSelector(我方会话参数，保留)

## 3. 合并方法（每文件）
1. `git diff upstream-3.0.0 HEAD -- <file>` 读双向增量；2. 以我方为基，逐 hunk 吸收上游（新 API/新组件/布局）；
3. 每次改完跑 `preflight.py`；4. 子批推送 adopt（oracle）验证；5. 全部绿后 FF main。

## 4. 红线
- 主页结构我方（加号→工具箱/右上角→产出/双星轨/工具懒加载）；头像恒 CircleShape；
- 我方玻璃链不迁移（用上游 3.0.0 玻璃）；沙箱/生成稳定链/上传/CWD/渲染/检索 = 保底不动。
