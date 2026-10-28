/**
 * Koin 依赖注入注册 — 模块: G. 其他 / di
 *
 * 职责: 全局单例注册 (Repository/Manager/Scheduler/Handler)。
 * 注意: 新增组件必须在此注册 — 缺失会 NoDefinitionFoundException (R8 混淆类名不可信)。
 *
 * 问题定位: 闪退 NoDefinitionFoundException → 查本文件是否缺注册
 */
/* 【域 E·设置体系】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.di


/* ───【原版对齐】AppModule | 差异 +116 行
 * 来源: 原版移植 + 自研 (DI 注册扩展)
 * 差异: ChatService/WebServer/PluginManager 等自研注入
 * ───────────────────────────────────────────────────────────────*/
import me.rerere.rikkahub.data.firebase.StubAnalytics
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunBootRecovery
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.alarm.AlarmRepository
import me.rerere.rikkahub.data.alarm.AlarmScheduler
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.workflow.trigger.TriggerRegistry
import me.rerere.rikkahub.workflow.condition.ContextProvider
import me.rerere.rikkahub.workflow.execution.WorkflowActionRunner
import me.rerere.rikkahub.workflow.execution.WorkflowEmergencyController
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.subagent.SubAgentEngine
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.browser.BrowserPreferences
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.ChatFloatingTracker
import me.rerere.rikkahub.ui.floating.ChatFloatingWindows
import me.rerere.rikkahub.service.CronJobScheduler
import me.rerere.rikkahub.service.DirectModeActionRunner
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    // v5.3.2（3.1.1 对齐）：悬浮窗 —— 决定显示哪个会话/是否展开（纯决策层，不涉及窗口）
    single {
        ChatFloatingTracker(
            appScope = get(),
            lazyChatService = lazy { get() },
            settingsStore = get(),
        )
    }

    // v5.3.2：进程启动即开始监听前后台切换，驱动悬浮球/面板的显示
    single(createdAtStart = true) {
        ChatFloatingWindows(
            context = get(),
            appScope = get(),
            tracker = get(),
            lazyChatService = lazy { get() },
        )
    }

    single { StubAnalytics }
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        BrowserPreferences(get())
    }

    single {
        LocalTools(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }

    single {
        UpdateChecker(get())
    }

    single {
        ScheduledJobRepository(get<me.rerere.rikkahub.data.db.AppDatabase>().scheduledJobDao())
    }

    single {
        AlarmRepository(get<me.rerere.rikkahub.data.db.AppDatabase>().alarmDao())
    }

    single {
        AlarmScheduler(get(), get())
    }

    single {
        WorkflowRepository(
            get<me.rerere.rikkahub.data.db.AppDatabase>().workflowDao(),
            get<me.rerere.rikkahub.data.db.AppDatabase>().workflowRunDao(),
        )
    }

    single {
        ScheduledJobRunRepository(get<me.rerere.rikkahub.data.db.AppDatabase>().scheduledJobRunDao())
    }

    single {
        DirectModeActionRunner(get())
    }

    single {
        WorkflowEmergencyController()
    }

    single {
        WorkflowActionRunner(get<DirectModeActionRunner>())
    }

    single {
        ContextProvider(get())
    }

    single {
        WorkflowEngine(get(), get(), get(), get(), get())
    }

    single {
        TriggerRegistry(get(), get(), get())
    }

    single {
        CronJobScheduler(get(), get())
    }

    single {
        AgentRunRepository(get<me.rerere.rikkahub.data.db.AppDatabase>().agentRunDao())
    }

    single {
        AgentRunBootRecovery(get(), get())
    }

    single {
        SubAgentRegistry(
            runDao = get<me.rerere.rikkahub.data.db.AppDatabase>().subAgentRunDao(),
        ).also { it.restoreFromDisk() }
    }

    single {
        SubAgentEngine(
            registry = get(),
            conversationRepo = get(),
            settingsStore = get(),
            appScope = get(),
            agentRunRepo = get(),
        )
    }

    single {
        AppScope()
    }

    // v5.0.77：收件箱单一门面（上传→收件箱→编码→模型读取 全链收口）
    single {
        me.rerere.rikkahub.data.files.InboxStore(get())
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        SoundEffectPlayer(get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            httpClient = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            memoryConsolidationScheduler = get(),
            pluginManager = getOrNull(),
            operitToolProvider = getOrNull(), // v4.5.31 岔路口计划: Operit 脚本工具注入
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
