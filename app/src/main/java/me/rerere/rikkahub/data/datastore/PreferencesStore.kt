package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.pebbletemplates.pebble.PebbleEngine
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV4Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV5Migration
import me.rerere.rikkahub.data.model.Assistant
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

private const val SETTINGS_STORE_NAME = "settings"

// 读取失败时的最大重试次数
private const val READ_MAX_RETRIES = 3

@Volatile
private var settingsDataStore: DataStore<Preferences>? = null

// 进程内单例, 同一文件只能存在一个 DataStore 实例
private val Context.settingsStore: DataStore<Preferences>
    get() = settingsDataStore ?: synchronized(SettingsStore::class) {
        settingsDataStore ?: createSettingsDataStore(applicationContext).also { settingsDataStore = it }
    }

private fun createSettingsDataStore(context: Context): DataStore<Preferences> {
    val file = context.preferencesDataStoreFile(SETTINGS_STORE_NAME)
    return PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { exception ->
            // 文件已损坏无法解析, 先留一份原文件用于排查/抢救, 再重建为空
            Log.e(TAG, "Settings datastore corrupted, resetting", exception)
            runCatching {
                file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            }.onFailure {
                Log.e(TAG, "Failed to backup corrupted settings file", it)
            }
            emptyPreferences()
        },
        migrations = listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(),
            PreferenceStoreV4Migration(),
            PreferenceStoreV5Migration(),
        ),
        produceFile = { file },
    )
}

/**
 * 应用设置的读写入口。
 *
 * 磁盘只在启动时读一次，加载完成后内存里的 [settingsFlow] 就是唯一的事实来源，所有修改都先改内存再落盘。
 * 不把写完的值从盘上读回内存：读回来的是旧值，会盖掉这期间的新修改。
 */
class SettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : KoinComponent {
    constructor(context: Context, scope: AppScope) : this(context.settingsStore, scope)

    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val NETWORK_SETTING = stringPreferencesKey("network_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val FAST_MODEL_REASONING_LEVEL = stringPreferencesKey("fast_model_reasoning_level")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val ENABLE_AUTO_TITLE = booleanPreferencesKey("enable_auto_title")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val TRANSLATE_TARGET_LANGUAGE = stringPreferencesKey("translate_target_language")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")
        val UPLOAD_S3_CONFIG = stringPreferencesKey("upload_s3_config")

        // 媒体生成
        val MEDIA_GENERATION_PROVIDERS = stringPreferencesKey("media_generation_providers")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")

        // ── RinCore 自研键族（反向换底迁移：与原 write 链逐字段对齐）──
        val OPENCODE_API_KEY = stringPreferencesKey("opencode_api_key")
        val OPENCODE_API_KEYS = stringPreferencesKey("opencode_api_keys")
        val OPENCODE_WARM_ENABLED = booleanPreferencesKey("opencode_warm_enabled")
        val COMMAND_CODE_WARM_ENABLED = booleanPreferencesKey("command_code_warm_enabled")
        val CC_IMAGE_COMPAT = booleanPreferencesKey("cc_image_compat")
        val IMAGE_UPLOAD_MODE = stringPreferencesKey("image_upload_mode")
        val FILE_UPLOAD_MODE = stringPreferencesKey("file_upload_mode")
        val USAGE_VIEW_MODE = stringPreferencesKey("usage_view_mode")
        val ENABLE_WEB_SEARCH = booleanPreferencesKey("enable_web_search")
        val DEFER_AUTO_REPLY = booleanPreferencesKey("defer_auto_reply")
        val SWAP_SEND_REASONING_KEYS = booleanPreferencesKey("swap_send_reasoning_keys")
        val PROJECT_PACK_CLICK_STATS = stringPreferencesKey("project_pack_click_stats")
        val OPENCODE_REQUEST_MODE = booleanPreferencesKey("opencode_request_mode")
        val VIDEO_GENERATION_MODEL = stringPreferencesKey("video_generation_model")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        val ROUTING_MODEL = stringPreferencesKey("routing_model")
        // 工具矩阵（v4.8.83）
        val TOOL_ZONES = stringPreferencesKey("tool_zones")
        val TOOL_ZONE_LINKS = stringPreferencesKey("tool_zone_links")
        val HIDDEN_ZONES = stringPreferencesKey("hidden_zones")
        val TOP_LEVEL_ADDITIONS = stringPreferencesKey("top_level_additions")
        val TOP_LEVEL_REMOVALS = stringPreferencesKey("top_level_removals")
        val TOOL_ZONE_SEEDED = booleanPreferencesKey("tool_zone_seeded")
        val ZONE_MODEL_VERSION = intPreferencesKey("zone_model_version")
        // 子代理 / 产出 / 技能
        val SUBAGENT_TOKEN_BUDGET = longPreferencesKey("subagent_token_budget")
        val SUBAGENT_TOKEN_USAGE = stringPreferencesKey("subagent_token_usage")
        val CONVERSATION_PRODUCED_FILES = stringPreferencesKey("conversation_produced_files")
        val SUBAGENT_BUDGET_UPGRADED = booleanPreferencesKey("subagent_budget_upgraded")
        val SUBAGENT_CONCURRENCY_UPGRADED = booleanPreferencesKey("subagent_concurrency_upgraded")
        val PRESET_SKILLS_SEED_VERSION = intPreferencesKey("preset_skills_seed_version")
        val TOOL_DESCRIPTION_OVERRIDES = stringPreferencesKey("tool_description_overrides")
        val TOOL_NAME_OVERRIDES = stringPreferencesKey("tool_name_overrides")
        val CLASSIFIER_PROMPT = stringPreferencesKey("classifier_prompt")
        // 旧「工具域」键（仅 V5 迁移期读取）
        val TOOL_DOMAIN_OVERRIDES = stringPreferencesKey("tool_domain_overrides")
        val CUSTOM_DOMAIN_DESCRIPTIONS = stringPreferencesKey("custom_domain_descriptions")
        val CUSTOM_DOMAINS = stringPreferencesKey("custom_domains")
        val CUSTOM_DOMAIN_KEYWORDS = stringPreferencesKey("custom_domain_keywords")
        val DOMAIN_NAME_OVERRIDES = stringPreferencesKey("domain_name_overrides")
        val HIDDEN_DOMAINS = stringPreferencesKey("hidden_domains")
        val REMOVED_BUILTIN_DOMAINS = stringPreferencesKey("removed_builtin_domains")
        val EXEMPT_FROM_DOMAIN_TOOLS = stringPreferencesKey("exempt_from_domain_tools")

        // Uses the same DataStore singleton without starting settings flows or requiring Koin.
        /** RinCore 兼容层：旧两参调用点。 */
        internal suspend fun restoreBeforeInitialization(context: Context, settings: Settings) =
            restoreBeforeInitialization(context, settings, settings.launchCount)

        internal suspend fun restoreBeforeInitialization(context: Context, settings: Settings, launchCount: Int) {
            require(!settings.init) { "Cannot restore uninitialized settings" }
            // 启动次数和设置在同一次写入里落盘
            context.settingsStore.edit { preferences ->
                preferences[LAUNCH_COUNT] = launchCount
                preferences.putSettings(settings.normalized())
            }
        }
    }

    // 读取失败时绝不能回退为空配置, 否则默认值会被当成用户数据写回, 覆盖全部设置
    // 偶发 IO 错误重试, 仍失败则向上抛出 (文件损坏由 corruptionHandler 处理)
    private val preferencesFlow = dataStore.data
        .retryWhen { cause, attempt ->
            val shouldRetry = cause is IOException && cause !is CorruptionException && attempt < READ_MAX_RETRIES
            if (shouldRetry) {
                Log.w(TAG, "Failed to read settings, retrying (${attempt + 1}/$READ_MAX_RETRIES)", cause)
                delay((100L shl attempt.toInt()).milliseconds)
            }
            shouldRetry
        }

    private val state = MutableStateFlow(Settings.dummy())

    /** 当前设置。加载完成前是 [Settings.dummy]，需要真实设置时用 [awaitLoaded]。 */
    val settingsFlow: StateFlow<Settings> = state.asStateFlow()

    // ── RinCore 兼容层（B1 并树）：旧 UI/服务层消费的接口在上游 3.0.0 被移除，
    // 此处保留同名同语义薄适配，避免全层重写；UI 批再逐点收敛。 ──
    val settingsRevision = MutableStateFlow(0)

    val settingsFlowRaw: StateFlow<Settings> get() = settingsFlow

    fun updateSync(settings: Settings) {
        if (settings.init) return
        state.value = settings
        settingsRevision.value = settingsRevision.value + 1
    }

    suspend fun update(settings: Settings) {
        update { settings }
    }

    /** 等启动时的读盘完成后返回当前设置，不会返回 [Settings.dummy]。已经加载完时直接返回。 */
    suspend fun awaitLoaded(): Settings = state.first { !it.init }

    private val stateLock = Any()

    // 已经落盘的那一份，只在持有 persistMutex 时读写。
    // 启动后第一次写入前是 null，那一次整份写入：读盘时规整过、补出来的值要固定到盘上，不能只留在内存里
    private var persisted: Settings? = null
    private val persistMutex = Mutex()

    init {
        // 整份设置的 JSON 解码很重，不能放在主线程
        scope.launch(Dispatchers.Default) {
            state.value = try {
                preferencesFlow.first().toSettings().withOurDataRepairs().normalized()
                    .let { settings -> me.rerere.rikkahub.data.ai.tools.routing.ZoneOps.normalize(settings) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 读不出设置就不能带着占位值继续运行：界面上是一份默认设置，而任何修改都落不了盘
                Log.e(TAG, "Failed to load settings", e)
                Runtime.getRuntime().halt(1)
                throw e
            }
        }
        // RinCore：Pebble 模板缓存随设置变化失效（模板变量/注入变化立即可见）
        scope.launch {
            state.collect { loaded ->
                if (!loaded.init) runCatching { get<PebbleEngine>().templateCache.invalidateAll() }
            }
        }
    }

    // 启动次数每次启动都会变，不放进 Settings，否则刚进入应用所有读取设置的界面就要重组一遍
    val launchCountFlow: Flow<Int> = preferencesFlow
        .map { preferences -> preferences[LAUNCH_COUNT] ?: 0 }
        .distinctUntilChanged()

    /**
     * 基于最新的设置做修改，返回时修改已经落盘。
     *
     * [fn] 拿到的一定是最新值，并发的修改不会互相覆盖。修改在调用线程上立即生效（[settingsFlow] 马上能读到），
     * 落盘在后台进行，调用方中途被取消也会写完。
     *
     * 没有「传入整份设置」的版本：界面手里的那份可能已经过时，整份写回会盖掉这期间别处的修改。
     */
    suspend fun update(fn: (Settings) -> Settings) {
        // 加载完成前的修改等真实设置出来再应用。已加载时这里不能挂起：输入框、slider 依赖修改同步生效
        if (state.value.init) awaitLoaded()
        synchronized(stateLock) {
            val updated = fn(state.value)
            if (updated.init) {
                Log.w(TAG, "Cannot update dummy settings")
                return
            }
            // 规整会重建提供商、助手等对象，而界面按引用判断它们有没有变：规整没改动任何东西时保留传入的实例
            val normalized = updated.withOurDataRepairs().normalized()
                .let { settings -> me.rerere.rikkahub.data.ai.tools.routing.ZoneOps.normalize(settings) }
            state.value = if (normalized == updated) updated else normalized
            settingsRevision.value = settingsRevision.value + 1
        }
        persist()
    }

    /**
     * RinCore v4.8.89：原子读-改-写并返回操作结果 — 供工具矩阵事务（ZoneOps.applyZoneOp）
     * 在锁内计算并携带结果。修改与 [update] 同语义：内存立即生效、落盘后台进行。
     */
    suspend fun <T> updateWithResult(fn: (Settings) -> Pair<Settings, T>): T {
        if (state.value.init) awaitLoaded()
        var applied = false
        var result: Any? = null
        synchronized(stateLock) {
            val (updated, r) = fn(state.value)
            if (updated.init) {
                Log.w(TAG, "Cannot update dummy settings")
            } else {
                applied = true
                result = r
                val normalized = updated.withOurDataRepairs().normalized()
                    .let { settings -> me.rerere.rikkahub.data.ai.tools.routing.ZoneOps.normalize(settings) }
                state.value = if (normalized == updated) updated else normalized
            settingsRevision.value = settingsRevision.value + 1
            }
        }
        if (applied) persist()
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    // 连续修改只写最新的一份：排队等锁的调用拿到锁时，它的修改多半已经被前一次写盘带上了。
    // 不跟着调用方取消：页面关闭（viewModelScope 取消）时修改已经在内存里生效，不能只差落盘这一步。
    // DataStore 在调用方的上下文里执行编码，所以要在这里切到后台线程
    private suspend fun persist() {
        withContext(NonCancellable + Dispatchers.Default) {
            persistMutex.withLock {
                val latest = state.value
                val base = persisted
                if (latest !== base) {
                    dataStore.edit { preferences -> preferences.putSettings(latest, base) }
                    persisted = latest
                }
            }
        }
    }

    // 只原子地修改单个 key，不经过 Settings
    suspend fun incrementLaunchCount(): Int {
        var count = 0
        dataStore.edit { preferences ->
            count = (preferences[LAUNCH_COUNT] ?: 0) + 1
            preferences[LAUNCH_COUNT] = count
        }
        return count
    }

    suspend fun setLaunchCount(count: Int) {
        dataStore.edit { preferences -> preferences[LAUNCH_COUNT] = count }
    }

    /** 切换当前选中的助手。 */
    suspend fun selectAssistant(assistantId: Uuid) {
        update { it.copy(assistantId = assistantId) }
    }

    /** RinCore 兼容层：旧调用点（切换当前助手）。 */
    suspend fun updateAssistant(assistantId: Uuid) = selectAssistant(assistantId)

    private suspend fun updateAssistant(assistantId: Uuid, transform: (Assistant) -> Assistant) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) transform(assistant) else assistant
                }
            )
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        updateAssistant(assistantId) { it.copy(chatModelId = modelId) }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        updateAssistant(assistantId) { it.copy(reasoningLevel = reasoningLevel) }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        updateAssistant(assistantId) { it.copy(enableWebSearch = enabled) }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        updateAssistant(assistantId) { it.copy(mcpServers = mcpServers) }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        updateAssistant(assistantId) {
            it.copy(
                modeInjectionIds = modeInjectionIds,
                lorebookIds = lorebookIds,
                quickMessageIds = quickMessageIds,
            )
        }
    }
}
