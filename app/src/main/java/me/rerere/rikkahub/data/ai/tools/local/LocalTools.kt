package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.costguards.checkTokenUsageTool
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.service.CronJobScheduler
import me.rerere.rikkahub.subagent.SubAgentEngine
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.subagent.subagentCancelTool
import me.rerere.rikkahub.subagent.subagentDispatchTool
import me.rerere.rikkahub.subagent.subagentGetTool
import me.rerere.rikkahub.subagent.subagentListTool
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val scheduledJobRepository: ScheduledJobRepository,
    private val scheduledJobRunRepository: ScheduledJobRunRepository,
    private val cronJobScheduler: CronJobScheduler,
    private val subAgentEngine: SubAgentEngine,
    private val subAgentRegistry: SubAgentRegistry,
    private val conversationRepo: ConversationRepository,
) {
    val javascriptTool by lazy { buildJavascriptTool() }
    val timeTool by lazy { buildTimeInfoTool() }
    val clipboardTool by lazy { buildClipboardTool(context) }
    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }
    val askUserTool by lazy { buildAskUserTool() }
    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }
    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }
    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }
    val costGuardTool by lazy { checkTokenUsageTool(settingsStore, conversationRepo) }

    fun getTools(
        options: List<LocalToolOption>,
        invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) tools.add(javascriptTool)
        if (options.contains(LocalToolOption.TimeInfo)) tools.add(timeTool)
        if (options.contains(LocalToolOption.Clipboard)) tools.add(clipboardTool)
        if (options.contains(LocalToolOption.Tts)) tools.add(ttsTool)
        if (options.contains(LocalToolOption.AskUser)) tools.add(askUserTool)
        if (options.contains(LocalToolOption.ScreenTime)) tools.add(screenTimeTool)
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
        if (options.contains(LocalToolOption.ToastAndNotification)) {
            tools.add(toastTool(context))
            tools.add(notificationTool(context))
        }
        if (options.contains(LocalToolOption.SubAgents)) {
            tools.add(subagentDispatchTool(subAgentEngine, invocationContext))
            tools.add(subagentListTool(subAgentRegistry))
            tools.add(subagentGetTool(subAgentRegistry))
            tools.add(subagentCancelTool(subAgentRegistry))
        }
        if (options.contains(LocalToolOption.SystemIntents)) {
            tools.add(showLocationOnMapTool(context))
        }
        if (options.contains(LocalToolOption.Location)) {
            tools.add(locationTool(context))
        }
        if (options.contains(LocalToolOption.Battery)) {
            tools.add(batteryTool(context))
        }
        if (options.contains(LocalToolOption.MediaPlayer)) {
            tools.add(playMediaTool(context, invocationContext))
            tools.add(stopMediaTool(context))
            tools.add(pauseMediaTool(context))
            tools.add(resumeMediaTool(context))
            tools.add(seekMediaTool(context))
            tools.add(getMediaStatusTool())
        }
        if (options.contains(LocalToolOption.MediaScanner)) {
            tools.add(mediaScannerTool(context))
        }
        if (options.contains(LocalToolOption.Files)) {
            tools.add(listFilesTool())
            tools.add(readFileTool())
            tools.add(writeBinaryFileTool())
            tools.add(deleteFileTool())
            tools.add(moveFileTool())
            tools.add(copyFileTool())
            tools.add(createDirectoryTool())
            tools.add(fileInfoTool())
            tools.add(findFilesTool())
            tools.add(showImageTool(context, invocationContext.modelCanSeeImages))
            tools.add(openFileTool(context, invocationContext))
            tools.add(batchCopyTool())
            tools.add(batchMoveTool())
            tools.add(batchDeleteTool())
        }
        if (options.contains(LocalToolOption.Download)) {
            tools.add(downloadTool(context))
            tools.add(writeTextFileTool(context))
        }
        if (options.contains(LocalToolOption.Archive)) {
            tools.add(zipFilesTool(context))
            tools.add(unzipFileTool(context))
            tools.add(listZipContentsTool(context))
        }
        if (options.contains(LocalToolOption.CostGuards)) {
            tools.add(costGuardTool)
        }
        return tools
    }
}
