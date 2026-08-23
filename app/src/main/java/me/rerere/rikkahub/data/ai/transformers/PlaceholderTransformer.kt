package me.rerere.rikkahub.data.ai.transformers


/* ───【原版对齐】PlaceholderTransformer.kt | 差异 ±20 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import kotlinx.datetime.toInstant
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.Temporal
import java.util.Locale
import java.util.TimeZone

data class PlaceholderCtx(
    val context: Context,
    val settingsStore: SettingsStore,
    val model: Model,
    val assistant: Assistant,
    // v3.10.1: 会话时间基准 (首条用户消息 createdAt) — 时间占位符按此渲染,
    // 跨轮字节稳定, 不破坏 prompt 缓存前缀 (原 now() 每轮变化 → 缓存必断)
    val baseDate: LocalDate,
    val baseTime: LocalTime,
    val baseDateTime: LocalDateTime,
)

interface PlaceholderProvider {
    val placeholders: Map<String, PlaceholderInfo>
}

data class PlaceholderInfo(
    val displayName: @Composable () -> Unit,
    val resolver: (PlaceholderCtx) -> String
)

class PlaceholderBuilder {
    private val placeholders = mutableMapOf<String, PlaceholderInfo>()

    fun placeholder(
        key: String,
        displayName: @Composable () -> Unit,
        resolver: (PlaceholderCtx) -> String
    ) {
        placeholders[key] = PlaceholderInfo(displayName, resolver)
    }

    fun build(): Map<String, PlaceholderInfo> = placeholders.toMap()
}

fun buildPlaceholders(block: PlaceholderBuilder.() -> Unit): Map<String, PlaceholderInfo> {
    return PlaceholderBuilder().apply(block).build()
}

object DefaultPlaceholderProvider : PlaceholderProvider {
    override val placeholders: Map<String, PlaceholderInfo> = buildPlaceholders {
        placeholder("cur_date", { Text(stringResource(R.string.placeholder_current_date)) }) {
            it.baseDate.toDateString()
        }

        placeholder("cur_time", { Text(stringResource(R.string.placeholder_current_time)) }) {
            it.baseTime.toTimeString()
        }

        placeholder("cur_datetime", { Text(stringResource(R.string.placeholder_current_datetime)) }) {
            it.baseDateTime.toDateTimeString()
        }

        placeholder("model_id", { Text(stringResource(R.string.placeholder_model_id)) }) {
            it.model.modelId
        }

        placeholder("model_name", { Text(stringResource(R.string.placeholder_model_name)) }) {
            it.model.displayName
        }

        placeholder("locale", { Text(stringResource(R.string.placeholder_locale)) }) {
            Locale.getDefault().displayName
        }

        placeholder("timezone", { Text(stringResource(R.string.placeholder_timezone)) }) {
            TimeZone.getDefault().displayName
        }

        placeholder("system_version", { Text(stringResource(R.string.placeholder_system_version)) }) {
            "Android SDK v${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
        }

        placeholder("device_info", { Text(stringResource(R.string.placeholder_device_info)) }) {
            "${Build.BRAND} ${Build.MODEL}"
        }

        placeholder("battery_level", { Text(stringResource(R.string.placeholder_battery_level)) }) {
            it.context.batteryLevel().toString()
        }

        placeholder("nickname", { Text(stringResource(R.string.placeholder_nickname)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
        }

        placeholder("char", { Text(stringResource(R.string.placeholder_char)) }) {
            it.assistant.name.ifBlank { "assistant" }
        }

        placeholder("user", { Text(stringResource(R.string.placeholder_user)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
        }
    }

    private fun Temporal.toDateString() = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

    private fun Temporal.toTimeString() = DateTimeFormatter
        .ofLocalizedTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

    private fun Temporal.toDateTimeString() = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

    private fun Context.batteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}

object PlaceholderTransformer : InputMessageTransformer, KoinComponent {
    private val defaultProvider = DefaultPlaceholderProvider

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val settingsStore = get<SettingsStore>()
        // v3.10.1: 会话时间基准 = 首条用户消息的 createdAt。
        // 根因: 原实现 now() 实时求值 — system/历史消息含 {{cur_time}} 等
        // 占位符时每轮渲染不同 → 请求体前缀逐轮漂移 → DeepSeek 缓存断。
        // 现在同会话所有占位符渲染为同一基准 (发第一条消息的时刻), 跨轮稳定。
        // kotlinx.datetime: LocalDateTime.toInstant 需 kotlinx TimeZone
        val baseLdt = messages.firstOrNull { it.role == MessageRole.USER }
            ?.createdAt
            ?.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault())
            ?.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
            ?: kotlinx.datetime.Clock.System.now()
                .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        val baseDate = java.time.LocalDate.of(baseLdt.year, baseLdt.monthNumber, baseLdt.dayOfMonth)
        val baseTime = java.time.LocalTime.of(baseLdt.hour, baseLdt.minute, baseLdt.second)
        val baseDateTime = java.time.LocalDateTime.of(baseDate, baseTime)
        val baseCtx = PlaceholderCtx(
            context = ctx.context,
            settingsStore = settingsStore,
            model = ctx.model,
            assistant = ctx.assistant,
            baseDate = baseDate,
            baseTime = baseTime,
            baseDateTime = baseDateTime,
        )
        return messages.map {
            it.copy(
                parts = it.parts.map { part ->
                    if (part is UIMessagePart.Text) {
                        part.copy(
                            text = replacePlaceholders(text = part.text, ctx = ctx, settingsStore = settingsStore, baseCtx = baseCtx)
                        )
                    } else {
                        part
                    }
                }
            )
        }
    }

    private fun replacePlaceholders(
        text: String,
        ctx: TransformerContext,
        settingsStore: SettingsStore,
        baseCtx: PlaceholderCtx
    ): String {
        var result = text

        defaultProvider.placeholders.forEach { (key, placeholderInfo) ->
            val value = placeholderInfo.resolver(baseCtx)
            result = result
                .replace(oldValue = "{{$key}}", newValue = value, ignoreCase = true)
                .replace(oldValue = "{$key}", newValue = value, ignoreCase = true)
        }

        return result
    }
}
