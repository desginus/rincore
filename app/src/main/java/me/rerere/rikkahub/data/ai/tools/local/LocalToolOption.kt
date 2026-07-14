package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable @SerialName("javascript_engine") data object JavascriptEngine : LocalToolOption()
    @Serializable @SerialName("time_info") data object TimeInfo : LocalToolOption()
    @Serializable @SerialName("clipboard") data object Clipboard : LocalToolOption()
    @Serializable @SerialName("tts") data object Tts : LocalToolOption()
    @Serializable @SerialName("ask_user") data object AskUser : LocalToolOption()
    @Serializable @SerialName("screen_time") data object ScreenTime : LocalToolOption()
    @Serializable @SerialName("calendar") data object Calendar : LocalToolOption()
    @Serializable @SerialName("cron_jobs") data object CronJobs : LocalToolOption()
    @Serializable @SerialName("toast_notification") data object ToastAndNotification : LocalToolOption()
    @Serializable @SerialName("sub_agents") data object SubAgents : LocalToolOption()
    @Serializable @SerialName("system_intents") data object SystemIntents : LocalToolOption()
    @Serializable @SerialName("location") data object Location : LocalToolOption()
    @Serializable @SerialName("battery") data object Battery : LocalToolOption()
    @Serializable @SerialName("media_player") data object MediaPlayer : LocalToolOption()
    @Serializable @SerialName("media_scanner") data object MediaScanner : LocalToolOption()
    @Serializable @SerialName("files") data object Files : LocalToolOption()
    @Serializable @SerialName("download") data object Download : LocalToolOption()
    @Serializable @SerialName("archive") data object Archive : LocalToolOption()
    @Serializable @SerialName("cost_guards") data object CostGuards : LocalToolOption()
    @Serializable @SerialName("browser") data object Browser : LocalToolOption()
}
