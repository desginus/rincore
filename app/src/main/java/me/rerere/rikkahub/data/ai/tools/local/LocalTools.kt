package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.service.CronJobScheduler
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val scheduledJobRepository: ScheduledJobRepository,
    private val scheduledJobRunRepository: ScheduledJobRunRepository,
    private val cronJobScheduler: CronJobScheduler,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    fun getTools(options: List<LocalToolOption>, invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.CronJobs)) {
            val knownToolNamesProvider: () -> List<String> = {
                this.getTools(options, invocationContext).map { it.name }
            }
            tools.add(scheduleJobTool(scheduledJobRepository, cronJobScheduler, settingsStore, knownToolNamesProvider))
            tools.add(listJobsTool(scheduledJobRepository))
            tools.add(deleteJobTool(scheduledJobRepository, scheduledJobRunRepository, cronJobScheduler))
            tools.add(pauseJobTool(scheduledJobRepository, cronJobScheduler))
            tools.add(resumeJobTool(scheduledJobRepository, cronJobScheduler))
            tools.add(triggerJobNowTool(scheduledJobRepository, cronJobScheduler))
            tools.add(getJobHistoryTool(scheduledJobRepository, scheduledJobRunRepository))
        }
        return tools
    }
}
