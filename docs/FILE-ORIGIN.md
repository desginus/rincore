# 文件来源总表（FILE-ORIGIN）

> **自动生成，不要手改。** 生成命令：`bin/origin audit`

> 基线 = `e22e21497`（3.1.0 移植基线 4.8.189，rincore-src main）

> 上游 = `/workspace/versions/upstream-3.1.1`（真实 rikkahub 3.1.1）

> 当前 HEAD = `77da2771bc`，共 **1353** 个 .kt


## 判据

| 类别 | 含义 | 迁移动作 |
|---|---|---|
| `UPSTREAM` | 与 3.1.0 基线**逐字节一致** | 可安全整体回退，零冲突 |
| `MODIFIED` | 基线有、我们改过 | 回退会丢改动；移植上游需三方合并 |
| `PORTED` | 基线没有、上游 3.1.1 有且内容一致 | 属上游资产，升级时整文件替换 |
| `PORTED-MODIFIED` | 从 3.1.1 移植后我们又改过 | 升级需三方合并 |
| `OURS` | 两份上游都没有 | 自研，与上游无冲突 |
| `DELETED` | 基线有、我们删了 | 回归上游时需确认是否有意删除 |

## 汇总

| 类别 | 数量 | 占比 |
|---|---|---|
| `UPSTREAM` | 778 | 57.5% |
| `MODIFIED` | 231 | 17.1% |
| `PORTED` | 200 | 14.8% |
| `PORTED-MODIFIED` | 67 | 5.0% |
| `OURS` | 56 | 4.1% |
| `DELETED` | 21 | 1.6% |

## OURS（56）

- `ai/src/main/java/me/rerere/ai/core/RetryAfter.kt`
- `ai/src/test/java/me/rerere/ai/core/StreamProgressTest.kt`
- `app/src/androidTest/java/me/rerere/rikkahub/data/db/dao/MessageNodeStatsTest.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/ModelLatencyStats.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/ModelRacer.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/ModelRouting.kt`
- `app/src/main/java/me/rerere/rikkahub/data/datastore/LegacyCustomDomain.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/dao/MemoryFileDAO.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_32_33.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_33_34.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_34_35.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_35_36.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_36_37.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_37_38.kt`
- `app/src/main/java/me/rerere/rikkahub/data/diagnostics/BackgroundHealth.kt`
- `app/src/main/java/me/rerere/rikkahub/data/files/InboxStore.kt`
- `app/src/main/java/me/rerere/rikkahub/data/workspace/ShellSessionPool.kt`
- `app/src/main/java/me/rerere/rikkahub/data/workspace/StorageCleanup.kt`
- `app/src/main/java/me/rerere/rikkahub/data/workspace/TarGz.kt`
- `app/src/main/java/me/rerere/rikkahub/data/workspace/WorkspaceArchive.kt`
- `app/src/main/java/me/rerere/rikkahub/data/workspace/WorkspaceHealer.kt`
- `app/src/main/java/me/rerere/rikkahub/data/workspace/WorkspaceLock.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ai/QueueIndicator.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ai/SubAgentBudgetSheet.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ExtensionTab.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPageHost.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ExportPdf.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAppStoragePage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingCrashReportsPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingModelPoolPage.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/ModelLatencyStatsTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/ModelRoutingTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/RetryPolicyTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/tools/routing/ZoneOpsTest.kt`
- `docs/upstream-3.1.0-staging/ai/src/main/java/me/rerere/ai/provider/AccountCredentialProvider.kt`
- `docs/upstream-3.1.0-staging/ai/src/main/java/me/rerere/ai/provider/Provider.kt`
- `docs/upstream-3.1.0-staging/ai/src/main/java/me/rerere/ai/provider/ProviderManager.kt`
- `docs/upstream-3.1.0-staging/ai/src/main/java/me/rerere/ai/provider/ProviderQuota.kt`
- `docs/upstream-3.1.0-staging/ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt`
- `docs/upstream-3.1.0-staging/ai/src/main/java/me/rerere/ai/provider/QuotaQueryRunner.kt`
- `docs/upstream-3.1.0-staging/ai/src/main/java/me/rerere/ai/provider/providers/AccountAuth.kt`
- `docs/upstream-3.1.0-staging/ai/src/main/java/me/rerere/ai/provider/providers/chatgpt/ChatGPTProvider.kt`
- `docs/upstream-3.1.0-staging/ai/src/main/java/me/rerere/ai/provider/providers/grok/GrokProvider.kt`
- `docs/upstream-3.1.0-staging/app/src/main/java/me/rerere/rikkahub/data/ai/UsageRecorder.kt`
- `docs/upstream-3.1.0-staging/app/src/main/java/me/rerere/rikkahub/data/ai/account/ChatGPTAuth.kt`
- `docs/upstream-3.1.0-staging/app/src/main/java/me/rerere/rikkahub/data/ai/account/GrokAuth.kt`
- `docs/upstream-3.1.0-staging/app/src/main/java/me/rerere/rikkahub/data/ai/account/Jwt.kt`
- `docs/upstream-3.1.0-staging/app/src/main/java/me/rerere/rikkahub/data/ai/account/ProviderAccountManager.kt`
- `docs/upstream-3.1.0-staging/app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV5Migration.kt`
- `docs/upstream-3.1.0-staging/app/src/main/java/me/rerere/rikkahub/data/db/dao/UsageRecordDAO.kt`
- `docs/upstream-3.1.0-staging/app/src/main/java/me/rerere/rikkahub/data/db/entity/UsageRecordEntity.kt`
- `docs/upstream-3.1.0-staging/app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_30_31.kt`
- `docs/upstream-3.1.0-staging/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ProviderQuotaText.kt`
- `docs/upstream-3.1.0-staging/app/src/main/java/me/rerere/rikkahub/ui/context/LocalWorkspace.kt`
- `docs/upstream-3.1.0-staging/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/QuotaQueryOption.kt`
- `docs/upstream-3.1.0-staging/common/src/main/java/me/rerere/common/http/sse/ServerSentEventReader.kt`

## MODIFIED（231）

按改动量倒序；`+`/`-` 为相对基线的行数。

| 文件 | + | - |
|---|---|---|
| `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt` | 1017 | 312 |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt` | 215 | 1072 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt` | 702 | 514 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSpeechPage.kt` | 303 | 561 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderDetailPage.kt` | 428 | 416 |
| `app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt` | 750 | 83 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantPromptPage.kt` | 382 | 425 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMcpPage.kt` | 18 | 733 |
| `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt` | 666 | 78 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt` | 435 | 289 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/WebDavTab.kt` | 160 | 446 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/S3Tab.kt` | 111 | 477 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt` | 374 | 212 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt` | 246 | 334 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/translator/TranslatorPage.kt` | 328 | 206 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageActions.kt` | 173 | 305 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesGeneralPage.kt` | 153 | 313 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingModelPage.kt` | 238 | 200 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatAttachmentPicker.kt` | 327 | 107 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantBasicPage.kt` | 201 | 224 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt` | 346 | 75 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt` | 374 | 39 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesUIPage.kt` | 155 | 207 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/PromptPage.kt` | 184 | 161 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/ImportExportTab.kt` | 181 | 162 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ai/McpPicker.kt` | 232 | 108 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ExtensionContent.kt` | 201 | 137 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt` | 309 | 22 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ASRProviderConfigure.kt` | 166 | 163 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingModelPromptPage.kt` | 197 | 111 |
| `markdown/src/main/java/org/intellij/markdown/util/LatexCommandSanitizer.kt` | 306 | 0 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationArtifactsSheet.kt` | 201 | 102 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderPage.kt` | 213 | 79 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt` | 206 | 77 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingFilesPage.kt` | 182 | 95 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingBuiltinToolsPage.kt` | 257 | 19 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt` | 176 | 94 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/Export.kt` | 194 | 54 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt` | 174 | 72 |
| `markdown/src/test/java/org/intellij/markdown/util/LatexCommandSanitizerTest.kt` | 244 | 0 |
| `app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt` | 194 | 45 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAboutPage.kt` | 186 | 45 |
| `search/src/main/java/me/rerere/search/ExaSearchService.kt` | 144 | 77 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/RetryPolicy.kt` | 179 | 39 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantDetailVM.kt` | 198 | 16 |
| `app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt` | 192 | 12 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ui/CardGroup.kt` | 187 | 10 |
| `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt` | 114 | 81 |
| `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt` | 153 | 35 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantDetailPage.kt` | 128 | 60 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesNotificationPage.kt` | 141 | 45 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ModelList.kt` | 145 | 40 |
| `ai/src/main/java/me/rerere/ai/core/WatchdogPolicy.kt` | 156 | 27 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantExtensionsPage.kt` | 71 | 111 |
| `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt` | 164 | 17 |
| `app/src/main/java/me/rerere/rikkahub/data/sync/BackupManager.kt` | 158 | 9 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/stats/StatsPage.kt` | 165 | 2 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ConversationTools.kt` | 160 | 0 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingUploadInboxPage.kt` | 137 | 18 |
| `app/src/main/java/me/rerere/rikkahub/service/EnvironmentOptimizer.kt` | 140 | 14 |
| `app/src/test/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformerTest.kt` | 7 | 145 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspacePage.kt` | 56 | 86 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/PropertyEditor.kt` | 58 | 73 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ReadImageTool.kt` | 111 | 19 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/BalanceOption.kt` | 57 | 70 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/LatexText.kt` | 99 | 27 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/ZoomableAsyncImage.kt` | 118 | 8 |
| `app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt` | 102 | 10 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/AssistantPage.kt` | 60 | 51 |
| `speech/src/main/java/me/rerere/tts/provider/providers/QwenTTSProvider.kt` | 60 | 48 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/TTSProviderConfigure.kt` | 66 | 41 |
| `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt` | 90 | 15 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/translator/TranslatorVM.kt` | 77 | 27 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/JavascriptTool.kt` | 74 | 29 |
| `ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt` | 94 | 8 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ProducedFiles.kt` | 95 | 7 |
| `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt` | 90 | 7 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/Background.kt` | 27 | 69 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/DocumentAsPromptTransformer.kt` | 86 | 6 |
| `app/src/main/java/me/rerere/rikkahub/data/sync/s3/AwsSignatureV4.kt` | 86 | 0 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/BackupPage.kt` | 39 | 44 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWeb.kt` | 81 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt` | 45 | 37 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/stats/StatsVM.kt` | 81 | 0 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchDetailPage.kt` | 49 | 31 |
| `app/src/main/java/me/rerere/rikkahub/ui/hooks/ChatInputState.kt` | 68 | 9 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/EmptyOrbitSelector.kt` | 71 | 6 |
| `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt` | 58 | 18 |
| `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt` | 52 | 23 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/ReminderTab.kt` | 31 | 44 |
| `app/src/main/java/me/rerere/rikkahub/data/db/dao/MemoryDAO.kt` | 65 | 6 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConnectionTester.kt` | 44 | 27 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ui/FloatingWindow.kt` | 33 | 33 |
| `common/src/main/java/me/rerere/common/js/QuickJSFetch.kt` | 34 | 27 |
| `search/src/main/java/me/rerere/search/CustomJsSearchService.kt` | 25 | 36 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/QuickMessagesPage.kt` | 17 | 42 |
| `workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt` | 58 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesThemePage.kt` | 28 | 30 |
| `app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt` | 45 | 13 |
| `app/src/main/java/me/rerere/rikkahub/ui/activity/SafeModeActivity.kt` | 45 | 7 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt` | 14 | 34 |
| `app/src/main/java/me/rerere/rikkahub/data/sync/PendingRestore.kt` | 38 | 9 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesPage.kt` | 16 | 29 |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/DraftStore.kt` | 37 | 5 |
| `workspace/src/test/java/me/rerere/workspace/ExampleUnitTest.kt` | 20 | 21 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt` | 23 | 17 |
| `app/src/main/java/me/rerere/rikkahub/data/files/SkillManager.kt` | 38 | 1 |
| `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabaseFactory.kt` | 33 | 5 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt` | 38 | 0 |
| `app/src/main/java/me/rerere/rikkahub/ui/hooks/AvatarShape.kt` | 7 | 30 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchPage.kt` | 16 | 20 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt` | 34 | 1 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/routing/ZoneOps.kt` | 23 | 11 |
| `app/src/test/java/me/rerere/rikkahub/data/ai/transformers/TimeReminderTransformerTest.kt` | 33 | 0 |
| `ai/src/main/java/me/rerere/ai/ui/Message.kt` | 31 | 1 |
| `ai/src/main/java/me/rerere/ai/provider/providers/ClaudeProvider.kt` | 26 | 5 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/ToolPreviewBuilder.kt` | 29 | 2 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationPrompts.kt` | 1 | 29 |
| `app/src/main/java/me/rerere/rikkahub/ui/hooks/HeroAnimation.kt` | 25 | 5 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/routing/ToolZone.kt` | 28 | 1 |
| `workspace/src/test/java/me/rerere/workspace/RootfsInstallerTest.kt` | 28 | 1 |
| `app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt` | 26 | 2 |
| `app/src/main/java/me/rerere/rikkahub/service/ConversationSessionManager.kt` | 23 | 5 |
| `speech/src/main/java/me/rerere/tts/provider/TTSProviderSetting.kt` | 22 | 6 |
| `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt` | 27 | 0 |
| `app/src/main/java/me/rerere/rikkahub/service/SandboxPrewarmer.kt` | 20 | 6 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ReasoningPicker.kt` | 0 | 25 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/BackupVM.kt` | 23 | 2 |
| `app/src/main/java/me/rerere/rikkahub/data/operit/runtime/OperitBuiltinPackages.kt` | 16 | 7 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingWebPage.kt` | 8 | 15 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageEditedFiles.kt` | 12 | 10 |
| `markdown/src/main/java/org/intellij/markdown/parser/sequentialparsers/impl/MathParser.kt` | 21 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingToolListPage.kt` | 19 | 2 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/MessageImageSnapshotTransformer.kt` | 20 | 0 |
| `app/src/main/java/me/rerere/rikkahub/data/files/ChatMediaStore.kt` | 19 | 1 |
| `app/src/main/java/me/rerere/rikkahub/di/RepositoryModule.kt` | 19 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawerVM.kt` | 20 | 0 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/MeshGradientBackground.kt` | 7 | 13 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingVM.kt` | 18 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ProjectPackSelection.kt` | 18 | 0 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolsBuilder.kt` | 13 | 4 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatFileActions.kt` | 17 | 0 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailPage.kt` | 16 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAdvancedPage.kt` | 16 | 0 |
| `workspace/src/main/java/me/rerere/workspace/ProotShellRunner.kt` | 16 | 0 |
| `app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt` | 13 | 2 |
| `app/src/main/java/me/rerere/rikkahub/service/ProviderWarmup.kt` | 14 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt` | 10 | 5 |
| `speech/src/main/java/me/rerere/tts/provider/providers/StepTTSProvider.kt` | 7 | 8 |
| `ai/src/main/java/me/rerere/ai/core/Usage.kt` | 14 | 0 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/routing/ZoneRouter.kt` | 12 | 2 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ai/WorkspaceCwdPicker.kt` | 13 | 1 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt` | 13 | 0 |
| `app/src/main/java/me/rerere/rikkahub/data/sync/s3/S3Client.kt` | 13 | 0 |
| `app/src/main/java/me/rerere/rikkahub/data/db/entity/MemoryEntity.kt` | 12 | 0 |
| `app/src/main/java/me/rerere/rikkahub/utils/AppDispatchers.kt` | 10 | 2 |
| `search/src/main/java/me/rerere/search/SearchService.kt` | 10 | 2 |
| `speech/src/main/java/me/rerere/tts/provider/providers/XAITTSProvider.kt` | 6 | 6 |
| `ai/src/test/java/me/rerere/ai/ui/ToolApprovalStateTest.kt` | 11 | 0 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolImagePayload.kt` | 9 | 2 |
| `app/src/main/java/me/rerere/rikkahub/data/db/dao/ConversationDAO.kt` | 11 | 0 |
| `app/src/main/java/me/rerere/rikkahub/data/db/entity/ConversationEntity.kt` | 4 | 7 |
| `app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavSync.kt` | 10 | 1 |
| `app/src/main/java/me/rerere/rikkahub/data/db/fts/MessageFtsManager.kt` | 6 | 4 |
| `app/src/main/java/me/rerere/rikkahub/sandbox/SandboxBridgeServer.kt` | 8 | 2 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ai/CompressContextDialog.kt` | 9 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ai/SubAgentDetailSheet.kt` | 10 | 0 |
| `material3/material-color-utilities/kotlin/dynamiccolor/ColorSpec2025.kt` | 4 | 6 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingCallTracePage.kt` | 6 | 3 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/PresetThemeButton.kt` | 2 | 7 |
| `app/src/main/java/me/rerere/rikkahub/data/db/entity/FolderEntity.kt` | 2 | 6 |
| `search/src/main/java/me/rerere/search/LinkUpService.kt` | 2 | 6 |
| `search/src/main/java/me/rerere/search/MetasoSearchService.kt` | 2 | 6 |
| `search/src/main/java/me/rerere/search/ZhipuSearchService.kt` | 2 | 6 |
| `speech/src/main/java/me/rerere/tts/provider/providers/ElevenLabsTTSProvider.kt` | 2 | 6 |
| `speech/src/main/java/me/rerere/tts/provider/providers/FishAudioTTSProvider.kt` | 2 | 6 |
| `speech/src/main/java/me/rerere/tts/provider/providers/GroqTTSProvider.kt` | 2 | 6 |
| `app/src/main/java/me/rerere/rikkahub/data/model/Folder.kt` | 1 | 6 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ai/CropLauncher.kt` | 6 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/AssistantVM.kt` | 7 | 0 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/PromptVM.kt` | 7 | 0 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/operiteco/OperitEcoPage.kt` | 6 | 1 |
| `speech/src/main/java/me/rerere/tts/provider/providers/SystemTTSProvider.kt` | 1 | 6 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/routing/ToolOrigin.kt` | 5 | 1 |
| `app/src/main/java/me/rerere/rikkahub/subagent/SubAgentBudget.kt` | 6 | 0 |
| `app/src/main/java/me/rerere/rikkahub/ui/theme/Color.kt` | 6 | 0 |
| `search/src/main/java/me/rerere/search/GrokSearchService.kt` | 1 | 5 |
| `search/src/main/java/me/rerere/search/PerplexitySearchService.kt` | 1 | 5 |
| `search/src/main/java/me/rerere/search/RikkaHubSearchService.kt` | 1 | 5 |
| `search/src/main/java/me/rerere/search/SearXNGService.kt` | 1 | 5 |
| `speech/src/main/java/me/rerere/asr/providers/MiMoASRController.kt` | 1 | 5 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/AskUserTool.kt` | 4 | 1 |
| `app/src/main/java/me/rerere/rikkahub/data/sync/s3/S3Config.kt` | 5 | 0 |
| `app/src/main/java/me/rerere/rikkahub/utils/WorkspaceImageResolver.kt` | 4 | 1 |
| `speech/src/main/java/me/rerere/tts/provider/TTSManager.kt` | 4 | 1 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ZoneTools.kt` | 3 | 1 |
| `app/src/main/java/me/rerere/rikkahub/data/sync/S3Sync.kt` | 4 | 0 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceVM.kt` | 4 | 0 |
| `material3/src/main/java/me/rerere/material3/DynamicSchemeExt.kt` | 0 | 4 |
| `app/src/main/java/me/rerere/rikkahub/data/db/dao/MessageNodeDAO.kt` | 3 | 0 |
| `app/src/main/java/me/rerere/rikkahub/data/permissions/PermissionInventory.kt` | 3 | 0 |
| `app/src/main/java/me/rerere/rikkahub/data/provider/WorkspaceDocumentsProvider.kt` | 2 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/share/handler/ShareHandlerPage.kt` | 2 | 1 |
| `ai/src/main/java/me/rerere/ai/provider/Model.kt` | 2 | 0 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpToolSchema.kt` | 1 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownNew.kt` | 1 | 1 |
| `app/src/main/java/me/rerere/rikkahub/ui/components/ui/Export.kt` | 1 | 1 |
| `app/src/test/java/me/rerere/rikkahub/data/datastore/DefaultProvidersTest.kt` | 2 | 0 |
| `search/src/main/java/me/rerere/search/BingSearchService.kt` | 0 | 1 |
| `search/src/main/java/me/rerere/search/BochaSearchService.kt` | 0 | 1 |
| `search/src/main/java/me/rerere/search/BraveSearchService.kt` | 0 | 1 |
| `search/src/main/java/me/rerere/search/DoubaoSearchService.kt` | 0 | 1 |
| `search/src/main/java/me/rerere/search/FirecrawlSearchService.kt` | 0 | 1 |
| `search/src/main/java/me/rerere/search/JinaSearchService.kt` | 0 | 1 |
| `search/src/main/java/me/rerere/search/OllamaSearchService.kt` | 0 | 1 |
| `search/src/main/java/me/rerere/search/SerperSearchService.kt` | 0 | 1 |
| `search/src/main/java/me/rerere/search/TavilySearchService.kt` | 0 | 1 |
| `search/src/main/java/me/rerere/search/TinyfishSearchService.kt` | 0 | 1 |
| `speech/src/main/java/me/rerere/asr/ASRController.kt` | 0 | 1 |
| `speech/src/main/java/me/rerere/asr/ASRProviderSetting.kt` | 0 | 1 |
| `speech/src/main/java/me/rerere/asr/ASRState.kt` | 0 | 1 |
| `speech/src/main/java/me/rerere/asr/AudioAmplitude.kt` | 0 | 1 |
| `speech/src/main/java/me/rerere/asr/providers/DashScopeASRController.kt` | 0 | 1 |
| `speech/src/main/java/me/rerere/asr/providers/StepASRController.kt` | 0 | 1 |
| `speech/src/main/java/me/rerere/tts/controller/AudioPlayer.kt` | 0 | 1 |
| `speech/src/main/java/me/rerere/tts/controller/TextChunker.kt` | 0 | 1 |
| `speech/src/main/java/me/rerere/tts/controller/TtsSynthesizer.kt` | 0 | 1 |
| `speech/src/main/java/me/rerere/tts/model/PlaybackState.kt` | 0 | 1 |
| `speech/src/main/java/me/rerere/tts/model/TTSRequest.kt` | 0 | 1 |
| `speech/src/main/java/me/rerere/tts/model/TTSResponse.kt` | 0 | 1 |
| `speech/src/main/java/me/rerere/tts/provider/TTSProvider.kt` | 0 | 1 |

## PORTED（200）

- `ai/src/main/java/me/rerere/ai/agent/Agent.kt`
- `ai/src/main/java/me/rerere/ai/agent/Retry.kt`
- `ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeStreamDecoder.kt`
- `ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleStreamDecoder.kt`
- `ai/src/main/java/me/rerere/ai/provider/providers/google/vertex/ServiceAccountTokenProvider.kt`
- `ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsStreamDecoder.kt`
- `ai/src/main/java/me/rerere/ai/registry/ModelsDevCatalog.kt`
- `ai/src/test/java/me/rerere/ai/agent/RetryTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/ProviderMessageGroupingTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/claude/ClaudePromptCacheRequestTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/claude/ClaudeRequestMessageTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/claude/ClaudeServerToolTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/google/GoogleRequestMessageTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/google/GoogleToolCombinationTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/openai/ChatCompletionsMoonshotRequestTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/openai/ChatCompletionsRequestMessageTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/openai/ChatCompletionsStreamDecoderNullTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/openai/ChatCompletionsToolSchemaTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/openai/ChatCompletionsUsageParsingTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/openai/OpenAIProviderImageEditTest.kt`
- `ai/src/test/java/me/rerere/ai/ui/MessageContextLimitTest.kt`
- `ai/src/test/java/me/rerere/ai/ui/MessageToolMigrationTest.kt`
- `ai/src/test/java/me/rerere/ai/ui/MessageUploadValidationTest.kt`
- `ai/src/test/java/me/rerere/ai/ui/UIMessagePartTest.kt`
- `ai/src/test/java/me/rerere/ai/ui/UIMessageSerializationTest.kt`
- `app/baselineprofile/src/main/java/me/rerere/baselineprofile/MockOpenAIServer.kt`
- `app/src/androidTest/java/me/rerere/rikkahub/data/repository/MediaCreationRepositoryTest.kt`
- `app/src/androidTest/java/me/rerere/rikkahub/data/sync/BackupManagerTest.kt`
- `app/src/androidTest/java/me/rerere/rikkahub/data/sync/DatabaseBackupTest.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryContentGuard.kt`
- `app/src/main/java/me/rerere/rikkahub/data/datastore/DefaultSettings.kt`
- `app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV4Migration.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/dao/MediaCreationDAO.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/dao/MemoryConsolidationDAO.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/dao/TranslationHistoryDAO.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/dao/UsageRecordDAO.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/entity/MediaCreationEntity.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/entity/MemoryConsolidationEntity.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/entity/TranslationHistoryEntity.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/entity/UsageRecordEntity.kt`
- `app/src/main/java/me/rerere/rikkahub/data/files/MediaCreationFiles.kt`
- `app/src/main/java/me/rerere/rikkahub/data/model/ConversationConfig.kt`
- `app/src/main/java/me/rerere/rikkahub/data/model/ConversationFork.kt`
- `app/src/main/java/me/rerere/rikkahub/data/model/GenerationPhase.kt`
- `app/src/main/java/me/rerere/rikkahub/data/model/Leaderboard.kt`
- `app/src/main/java/me/rerere/rikkahub/data/model/MediaCreation.kt`
- `app/src/main/java/me/rerere/rikkahub/data/remote/ProxyConfig.kt`
- `app/src/main/java/me/rerere/rikkahub/data/remote/RemoteFileStore.kt`
- `app/src/main/java/me/rerere/rikkahub/data/remote/RikkaHubAPI.kt`
- `app/src/main/java/me/rerere/rikkahub/data/remote/SponsorAPI.kt`
- `app/src/main/java/me/rerere/rikkahub/data/repository/FavoriteAdapter.kt`
- `app/src/main/java/me/rerere/rikkahub/data/repository/MediaCreationRepository.kt`
- `app/src/main/java/me/rerere/rikkahub/data/repository/NodeFavoriteAdapter.kt`
- `app/src/main/java/me/rerere/rikkahub/data/repository/TranslationHistoryRepository.kt`
- `app/src/main/java/me/rerere/rikkahub/data/skills/BuiltinSkills.kt`
- `app/src/main/java/me/rerere/rikkahub/data/skills/SkillFrontmatterParser.kt`
- `app/src/main/java/me/rerere/rikkahub/data/skills/SkillPaths.kt`
- `app/src/main/java/me/rerere/rikkahub/data/skills/SkillValidator.kt`
- `app/src/main/java/me/rerere/rikkahub/service/MediaCreationForegroundService.kt`
- `app/src/main/java/me/rerere/rikkahub/service/MemoryDreamForegroundService.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ai/TranslationLanguages.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageAttachments.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageContextCheckpoint.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ui/AppLogo.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ConnectedChoiceButtons.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ui/PagerTabs.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ui/S3ConfigItems.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ui/SwipeToDelete.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ui/VideoPlayerDialog.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/floating/ChatFloatingWindows.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/hooks/ExportHooks.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/hooks/Lifecycle.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/SectionHeader.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/components/BackupComponents.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceFileType.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationComposer.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationSessionsVM.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/memory/MemoryConsolidationText.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/memory/MemoryFileVM.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingDonatePage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/CustomThemeButton.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderSheets.kt`
- `app/src/main/java/me/rerere/rikkahub/utils/SecretCipher.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/BackgroundTextGenerationParamsTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/MemoryConsolidatorTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/TransformNewPartsTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpConnectionKeyTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/tools/SearchToolsTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/transformers/ThinkTagTransformerTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/datastore/FindProviderTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/datastore/PreferenceStoreV4MigrationTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/datastore/SettingsStoreTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/model/ConversationConfigTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/model/ConversationForkTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/model/ConversationMessagesTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/model/ConversationModelSnapshotTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/model/ConversationToolCallsTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/model/ConversationUpsertMessageTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/model/MediaCreationTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/remote/ProxyConfigTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/remote/RemoteFileStoreTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/skills/BuiltinSkillsTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/skills/SkillPathsTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/sync/s3/AwsSignatureV4Test.kt`
- `app/src/test/java/me/rerere/rikkahub/service/MessageQueueTest.kt`
- `app/src/test/java/me/rerere/rikkahub/service/QueuedAttachmentCleanupTest.kt`
- `app/src/test/java/me/rerere/rikkahub/ui/hooks/ChatInputStateTest.kt`
- `build-logic/src/main/kotlin/AndroidLibraryComposeConventionPlugin.kt`
- `build-logic/src/main/kotlin/AndroidLibraryConventionPlugin.kt`
- `common/src/main/java/me/rerere/common/cache/KeyCodec.kt`
- `common/src/main/java/me/rerere/common/cache/PerKeyFileCacheStore.kt`
- `document/src/main/java/me/rerere/document/pdf/CffFont.kt`
- `document/src/main/java/me/rerere/document/pdf/CidToUnicode.kt`
- `document/src/main/java/me/rerere/document/pdf/PdfCMap.kt`
- `document/src/main/java/me/rerere/document/pdf/PdfEncodings.kt`
- `document/src/main/java/me/rerere/document/pdf/PdfFile.kt`
- `document/src/main/java/me/rerere/document/pdf/PdfFilters.kt`
- `document/src/main/java/me/rerere/document/pdf/PdfFont.kt`
- `document/src/main/java/me/rerere/document/pdf/PdfLexer.kt`
- `document/src/main/java/me/rerere/document/pdf/PdfObjects.kt`
- `document/src/main/java/me/rerere/document/pdf/PdfSecurity.kt`
- `document/src/main/java/me/rerere/document/pdf/PdfTextExtractor.kt`
- `document/src/main/java/me/rerere/document/pdf/StandardFontMetrics.kt`
- `document/src/main/java/me/rerere/document/pdf/TrueTypeCmap.kt`
- `mediagen/src/androidTest/java/me/rerere/mediagen/ExampleInstrumentedTest.kt`
- `mediagen/src/main/java/me/rerere/mediagen/model/MediaGenerationInput.kt`
- `mediagen/src/main/java/me/rerere/mediagen/model/MediaGenerationModel.kt`
- `mediagen/src/main/java/me/rerere/mediagen/model/MediaGenerationRequest.kt`
- `mediagen/src/main/java/me/rerere/mediagen/model/MediaGenerationTask.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/MediaGenerationCapabilities.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/MediaGenerationManager.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/MediaGenerationProvider.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/providers/ProviderHttp.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/providers/ProviderImages.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/providers/aliyun/AliyunImageGeneration.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/providers/aliyun/AliyunMediaGenerationProvider.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/providers/aliyun/AliyunVideoGeneration.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/providers/minimax/MiniMaxMediaGenerationProvider.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/providers/openrouter/OpenRouterImageGeneration.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/providers/openrouter/OpenRouterMediaGenerationProvider.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/providers/openrouter/OpenRouterVideoGeneration.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/providers/volcengine/VolcengineImageGeneration.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/providers/volcengine/VolcengineMediaGenerationProvider.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/providers/volcengine/VolcengineVideoGeneration.kt`
- `mediagen/src/test/java/me/rerere/mediagen/provider/MediaGenerationCapabilitiesTest.kt`
- `mediagen/src/test/java/me/rerere/mediagen/provider/MediaGenerationProviderSettingTest.kt`
- `mediagen/src/test/java/me/rerere/mediagen/provider/providers/FakeHttp.kt`
- `mediagen/src/test/java/me/rerere/mediagen/provider/providers/aliyun/AliyunMediaGenerationProviderTest.kt`
- `mediagen/src/test/java/me/rerere/mediagen/provider/providers/openrouter/OpenRouterMediaGenerationProviderTest.kt`
- `mediagen/src/test/java/me/rerere/mediagen/provider/providers/volcengine/VolcengineMediaGenerationProviderTest.kt`
- `search/src/test/java/me/rerere/search/CustomJsSearchServiceTest.kt`
- `search/src/test/java/me/rerere/search/ExaSearchServiceTest.kt`
- `speech/src/main/java/me/rerere/tts/provider/providers/VolcengineTTSProvider.kt`
- `speech/src/test/java/me/rerere/asr/ASRProviderSettingVolcengineTest.kt`
- `speech/src/test/java/me/rerere/asr/ASRVoiceTurnTest.kt`
- `speech/src/test/java/me/rerere/asr/providers/VolcengineASRProtocolTest.kt`
- `speech/src/test/java/me/rerere/tts/provider/providers/VolcengineTTSProviderTest.kt`
- `ui/src/main/java/me/rerere/ui/charts/ChartCard.kt`
- `ui/src/main/java/me/rerere/ui/charts/ChartPlot.kt`
- `ui/src/main/java/me/rerere/ui/charts/ChartScale.kt`
- `ui/src/main/java/me/rerere/ui/charts/ChartSpec.kt`
- `ui/src/main/java/me/rerere/ui/charts/ChartTable.kt`
- `ui/src/main/java/me/rerere/ui/components/BitmapComposer.kt`
- `ui/src/main/java/me/rerere/ui/components/ConfirmDialog.kt`
- `ui/src/main/java/me/rerere/ui/components/DotLoading.kt`
- `ui/src/main/java/me/rerere/ui/components/Form.kt`
- `ui/src/main/java/me/rerere/ui/components/Input.kt`
- `ui/src/main/java/me/rerere/ui/components/ListSelectableItem.kt`
- `ui/src/main/java/me/rerere/ui/components/Select.kt`
- `ui/src/main/java/me/rerere/ui/components/StickyHeader.kt`
- `ui/src/main/java/me/rerere/ui/components/Switch.kt`
- `ui/src/main/java/me/rerere/ui/components/ToggleSurface.kt`
- `ui/src/main/java/me/rerere/ui/components/Tooltip.kt`
- `ui/src/main/java/me/rerere/ui/easteregg/EmojiBurst.kt`
- `ui/src/main/java/me/rerere/ui/icons/DiscordIcon.kt`
- `ui/src/main/java/me/rerere/ui/icons/Heart.kt`
- `ui/src/main/java/me/rerere/ui/icons/Reasoning.kt`
- `ui/src/main/java/me/rerere/ui/icons/TencentQQIcon.kt`
- `ui/src/main/java/me/rerere/ui/markdown/Markdown.kt`
- `ui/src/main/java/me/rerere/ui/markdown/MarkdownBlocks.kt`
- `ui/src/main/java/me/rerere/ui/markdown/MarkdownCss.kt`
- `ui/src/main/java/me/rerere/ui/markdown/MarkdownDefaults.kt`
- `ui/src/main/java/me/rerere/ui/markdown/MarkdownInline.kt`
- `ui/src/main/java/me/rerere/ui/markdown/MarkdownNode.kt`
- `ui/src/main/java/me/rerere/ui/richtext/DiffView.kt`
- `ui/src/main/java/me/rerere/ui/sketch/SketchBrush.kt`
- `ui/src/main/java/me/rerere/ui/sketch/SketchBrushMenu.kt`
- `ui/src/main/java/me/rerere/ui/sketch/SketchColorPicker.kt`
- `ui/src/main/java/me/rerere/ui/sketch/SketchPaperMenu.kt`
- `ui/src/main/java/me/rerere/ui/sketch/SketchSelection.kt`
- `ui/src/main/java/me/rerere/ui/sketch/SketchState.kt`
- `ui/src/main/java/me/rerere/ui/sketch/SketchTextInput.kt`
- `ui/src/main/java/me/rerere/ui/sketch/SketchToolbar.kt`
- `ui/src/main/java/me/rerere/ui/table/DataTable.kt`
- `ui/src/main/java/me/rerere/ui/toast/Toaster.kt`
- `ui/src/main/java/me/rerere/ui/webview/WebView.kt`
- `ui/src/main/java/me/rerere/ui/webview/WebViewContentCache.kt`
- `ui/src/main/java/me/rerere/ui/webview/WebViewLocalAssets.kt`
- `ui/src/test/java/me/rerere/ui/webview/WebViewContentCacheTest.kt`
- `workspace/src/test/java/me/rerere/workspace/RootfsPathResolutionTest.kt`

## PORTED-MODIFIED（67）

- `ai/src/main/java/me/rerere/ai/agent/AgentLoop.kt`
- `ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt`
- `ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleProvider.kt`
- `ai/src/main/java/me/rerere/ai/provider/providers/openai/OpenAIProvider.kt`
- `ai/src/test/java/me/rerere/ai/agent/AgentLoopTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/openai/ResponseApiRequestMessageTest.kt`
- `ai/src/test/java/me/rerere/ai/registry/ModelRegistryTest.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/ChatRequestFactory.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/ConversationMetaGenerator.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/MemoryConsolidationScheduler.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/MemoryConsolidator.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/TranslationHandler.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/prompts/MemoryPrompts.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt`
- `app/src/main/java/me/rerere/rikkahub/data/datastore/RecommendedProviders.kt`
- `app/src/main/java/me/rerere/rikkahub/data/datastore/Settings.kt`
- `app/src/main/java/me/rerere/rikkahub/data/datastore/SettingsExt.kt`
- `app/src/main/java/me/rerere/rikkahub/data/datastore/SettingsFields.kt`
- `app/src/main/java/me/rerere/rikkahub/data/datastore/SettingsNormalization.kt`
- `app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV5Migration.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/entity/MemoryFileEntity.kt`
- `app/src/main/java/me/rerere/rikkahub/data/model/ConversationMessages.kt`
- `app/src/main/java/me/rerere/rikkahub/data/model/MemoryFile.kt`
- `app/src/main/java/me/rerere/rikkahub/data/skills/SkillManager.kt`
- `app/src/main/java/me/rerere/rikkahub/service/ChatFloatingTracker.kt`
- `app/src/main/java/me/rerere/rikkahub/service/ChatGenerationForegroundService.kt`
- `app/src/main/java/me/rerere/rikkahub/service/MediaCreationService.kt`
- `app/src/main/java/me/rerere/rikkahub/service/WorkspaceDocumentsProvider.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInputHaze.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ai/PickerHeader.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ai/SearchPicker.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/floating/ChatFloatingContent.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceFileEditorPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationCommon.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationRecordItem.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationSessionsPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationVM.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/memory/MemoryFilePage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMcpDetailPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMediaPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/MediaGenerationProviderConfigure.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/translator/TranslationHistory.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/MemoryConsolidationSchedulerTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/prompts/MemoryPromptTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/tools/ExternalWebSearchTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/tools/MemoryToolsTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/tools/SkillsToolsTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/datastore/SettingsFieldsTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/model/ConversationCheckpointTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/model/ConversationContextTokensTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/model/MemoryFileTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/repository/FakeMemoryDAO.kt`
- `app/src/test/java/me/rerere/rikkahub/data/repository/MemoryRepositoryTest.kt`
- `app/src/test/java/me/rerere/rikkahub/service/ConversationSessionManagerTest.kt`
- `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/VoiceSessionControllerTest.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/MediaGenerationProviderSetting.kt`
- `mediagen/src/main/java/me/rerere/mediagen/provider/providers/openai/OpenAIMediaGenerationProvider.kt`
- `mediagen/src/test/java/me/rerere/mediagen/provider/providers/minimax/MiniMaxMediaGenerationProviderTest.kt`
- `mediagen/src/test/java/me/rerere/mediagen/provider/providers/openai/OpenAIMediaGenerationProviderTest.kt`
- `search/src/main/java/me/rerere/search/DeepSeekSearchService.kt`
- `ui/src/main/java/me/rerere/ui/markdown/MarkdownParser.kt`
- `ui/src/main/java/me/rerere/ui/sketch/SketchCanvas.kt`
- `ui/src/main/java/me/rerere/ui/sketch/SketchDialog.kt`
- `ui/src/main/java/me/rerere/ui/sketch/SketchImage.kt`
- `ui/src/main/java/me/rerere/ui/toast/ToasterState.kt`
- `ui/src/test/java/me/rerere/ui/markdown/MarkdownParserTest.kt`

## DELETED（21）

- `ai/src/androidTest/java/me/rerere/ai/ExampleInstrumentedTest.kt`
- `ai/src/test/java/me/rerere/ai/ExampleUnitTest.kt`
- `ai/src/test/java/me/rerere/ai/ModelRegistryTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/ClaudeProviderMessageTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/ClaudeProviderPromptCacheTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/GoogleProviderMessageTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/ProviderMessageUtilsTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPIMessageTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPIMoonshotTest.kt`
- `ai/src/test/java/me/rerere/ai/provider/providers/openai/ResponseAPIMessageTest.kt`
- `ai/src/test/java/me/rerere/ai/ui/MessageTest.kt`
- `app/src/main/java/me/rerere/rikkahub/data/export/ExportHooks.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/videogen/VideoGenPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/videogen/VideoGenVM.kt`
- `app/src/test/java/me/rerere/rikkahub/browser/BrowserNavigationTrackerTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/ai/protocol/MessageProtocolTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/files/SkillPathsTest.kt`
- `app/src/test/java/me/rerere/rikkahub/data/files/UploadCodesTest.kt`
- `app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt`
- `app/src/test/java/me/rerere/rikkahub/ui/components/webview/WebViewContentCacheTest.kt`
- `app/src/test/java/me/rerere/rikkahub/utils/WorkspacePathResolverTest.kt`
