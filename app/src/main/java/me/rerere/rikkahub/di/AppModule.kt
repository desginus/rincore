/**
 * Koin 依赖注入注册 — 模块: G. 其他 / di
 *
 * 职责: 全局单例注册 (Repository/Manager/Scheduler/Handler)。
 * 注意: 新增组件必须在此注册 — 缺失会 NoDefinitionFoundException (R8 混淆类名不可信)。
 *
 * 问题定位: 闪退 NoDefinitionFoundException → 查本文件是否缺注册
 */
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
        SubAgentRegistry()
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
            pluginManager = getOrNull()
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
