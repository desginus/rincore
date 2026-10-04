/**
/* ───【域 E·设置体系】PreferencesStore.kt
 * 职责: 双 Store 同文件: PreferencesStore (布尔偏好) + SettingsStore (SSOT settingsFlow)
 * 常用改动: 新开关 → booleanPreferencesKey+双写+读; 新设置项 → Settings 字段 (改结构需四投影验证)
 * 问题定位: 设置不生效/重启丢失/四投影不一致 → 本文件
 * 基线: 技术债审计 v3.19.0 ✓ | 自研演进 | 地图: docs/APP_MAP.md §E | 历史: .claude/skills/rincore-bug-record
 * ───────────────────────────────────────────────────────────────*/
 * 偏好存储 + SettingsStore (SSOT) — 模块: B. 会话与存储
 *
 * 职责: DataStore 持久化 + SettingsStore.settingsFlow 唯一真值源 —
 *       UI / list_domains / invoke_tools / Prompt 四投影均从此派生。
 * 域配置 (toolDomainOverrides/hiddenDomains/removedBuiltinDomains 等) 也在此。
 *
 * 注意: 修改设置结构需同步四投影验证 (UI 统计行 == invoke_tools 返回工具数)。
 *
 * 问题定位: 设置不生效/四投影不一致 → 查本文件 + GenerationHandler 每步读取点
 */
package me.rerere.rikkahub.data.datastore


/* ───【原版对齐】PreferencesStore | 差异 +159 行
 * 来源: 原版移植 + 自研 (SSOT 扩展)
 * 功能: Settings 定义 + DataStore 读写 (SSOT: settingsFlow)
 * 差异: exemptFromDomainTools (v3.6.90) 等自研字段
 * ───────────────────────────────────────────────────────────────*/
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
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.tools.routing.LegacyZoneConfig
import me.rerere.rikkahub.data.ai.tools.routing.ToolZone
import me.rerere.rikkahub.data.ai.tools.routing.ZONE_MODEL_VERSION_CURRENT
import me.rerere.rikkahub.data.ai.tools.routing.migrateLinkKeys
import me.rerere.rikkahub.data.ai.tools.routing.migrateToZones
import me.rerere.rikkahub.data.ai.tools.routing.projectZoneModel
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

// v4.8.64 (2.5.5 适配移植): 设置读取失败时绝不再回退为空配置 — 旧实现 emit(emptyPreferences())
// 会把默认值当成用户数据写回, 一次性覆盖全部设置 (上游 70b382f5 同一修复, 适配我们的迁移列表)。
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
            PreferenceStoreV3Migration()
        ),
        produceFile = { file },
    )
}


/**
 * 旧「工具域」的自定义域记录 —— 仅 v4.8.83 迁移期读取，迁移后不再写入。
 * 新体系用 [me.rerere.rikkahub.data.ai.tools.routing.ToolZone]（id 即身份，无 parent 字段）。
 */
@Serializable
data class LegacyCustomDomain(
    val name: String = "",
    val description: String = "",
    val keywords: List<String> = emptyList(),
    val parent: String? = null,
)
class SettingsStore(
    context: Context,
    scope: AppScope,
) : KoinComponent {
    /** 设置读-改-写互斥锁 — 保证并行域操作(create/delete/rename/move)原子性 */
    private val settingsMutex = kotlinx.coroutines.sync.Mutex()

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
        val OPENCODE_API_KEY = stringPreferencesKey("opencode_api_key")
        val OPENCODE_API_KEYS = stringPreferencesKey("opencode_api_keys")
        // v4.8.102: 视频生成接口配置 (Veo / HappyHorse — URL/模型ID/密钥)
        val VIDEO_GEN_ENDPOINTS = stringPreferencesKey("video_gen_endpoints")
        // v3.12.8: 预热开关持久化 (v3.12.6 漏接 DataStore 两端, 重启回默认 false)
        val OPENCODE_WARM_ENABLED = booleanPreferencesKey("opencode_warm_enabled")
        val COMMAND_CODE_WARM_ENABLED = booleanPreferencesKey("command_code_warm_enabled")
        val CC_IMAGE_COMPAT = booleanPreferencesKey("cc_image_compat")
        val IMAGE_UPLOAD_MODE = stringPreferencesKey("image_upload_mode")
        val FILE_UPLOAD_MODE = stringPreferencesKey("file_upload_mode")
        val USAGE_VIEW_MODE = stringPreferencesKey("usage_view_mode")

        // 模型选择
        val ENABLE_WEB_SEARCH = booleanPreferencesKey("enable_web_search")
        val DEFER_AUTO_REPLY = booleanPreferencesKey("defer_auto_reply")
        // v4.8.3: 发送键/思考键互换 — 防生成中误触右下角打断键
        val SWAP_SEND_REASONING_KEYS = booleanPreferencesKey("swap_send_reasoning_keys")
        // v4.8.58: 项目包点击统计 (智能排序数据源; 3 天外过期真删) — JSON 字符串
        val PROJECT_PACK_CLICK_STATS = stringPreferencesKey("project_pack_click_stats")
        // v4.5.17: 仿 OpenCode 请求模式 — 对 opencode.ai 网关按模型协议映射
        // (Chat Completions / Responses / Anthropic / Google) 自动选择传输协议
        val OPENCODE_REQUEST_MODE = booleanPreferencesKey("opencode_request_mode")
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val FAST_MODEL_REASONING_LEVEL = stringPreferencesKey("fast_model_reasoning_level")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")

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

        // 4.0.1 (原版 2.4.17): 备份恢复前置 — 在 Koin/Room/SettingsFlow 启动前
        // 用同一 DataStore 单例直接写入恢复数据 (不启动 settings flow, 不经 Koin)。
        // persistSettings 字段清单必须与 update() 保持一致 (自研字段: 卡包/预热/
        // 用量视图/工具路由等), 否则恢复丢配置。
        internal suspend fun restoreBeforeInitialization(context: Context, settings: Settings) {
            require(!settings.init) { "Cannot restore uninitialized settings" }
            persistSettings(context.settingsStore, settings)
        }

        private suspend fun persistSettings(dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>, settings: Settings) {
            dataStore.edit { preferences ->
                preferences[DYNAMIC_COLOR] = settings.dynamicColor
                preferences[THEME_ID] = settings.themeId
                preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
                preferences[DEVELOPER_MODE] = settings.developerMode
                preferences[OPENCODE_API_KEY] = settings.opencodeApiKey
                preferences[USAGE_VIEW_MODE] = settings.usageViewMode
                preferences[OPENCODE_API_KEYS] = JsonInstant.encodeToString(settings.opencodeApiKeys)
                preferences[VIDEO_GEN_ENDPOINTS] = JsonInstant.encodeToString(settings.videoGenEndpoints)
                preferences[OPENCODE_WARM_ENABLED] = settings.opencodeWarmEnabled
                preferences[COMMAND_CODE_WARM_ENABLED] = settings.commandCodeWarmEnabled
                preferences[CC_IMAGE_COMPAT] = settings.ccImageCompat
                preferences[IMAGE_UPLOAD_MODE] = settings.imageUploadMode
                preferences[FILE_UPLOAD_MODE] = settings.fileUploadMode
                preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
                preferences[NETWORK_SETTING] = JsonInstant.encodeToString(settings.networkSetting)
                preferences[ENABLE_WEB_SEARCH] = settings.enableWebSearch
                preferences[DEFER_AUTO_REPLY] = settings.deferAutoReply
                preferences[SWAP_SEND_REASONING_KEYS] = settings.swapSendReasoningKeys
            preferences[PROJECT_PACK_CLICK_STATS] = settings.projectPackClickStats
                preferences[OPENCODE_REQUEST_MODE] = settings.opencodeRequestMode
                preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
                preferences[SELECT_MODEL] = settings.chatModelId.toString()
                preferences[FAST_MODEL] = settings.fastModelId.toString()
                preferences[FAST_MODEL_REASONING_LEVEL] = settings.fastModelReasoningLevel.name
                preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
                preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
                preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
                preferences[TITLE_PROMPT] = settings.titlePrompt
                preferences[TRANSLATION_PROMPT] = settings.translatePrompt
                preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
                preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
                preferences[OCR_MODEL] = settings.ocrModelId.toString()
                preferences[OCR_PROMPT] = settings.ocrPrompt
                preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
                preferences[COMPRESS_PROMPT] = settings.compressPrompt
                preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)
                preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
                preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
                preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)
                preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
                preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
                preferences[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, (settings.searchServices.size - 1).coerceAtLeast(0))
                preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
                preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
                preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
                preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
                settings.selectedTTSProviderId?.let {
                    preferences[SELECTED_TTS_PROVIDER] = it.toString()
                } ?: preferences.remove(SELECTED_TTS_PROVIDER)
                preferences[DEFAULT_TTS_PLAYBACK_SPEED] = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
                preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
                settings.selectedASRProviderId?.let {
                    preferences[SELECTED_ASR_PROVIDER] = it.toString()
                } ?: preferences.remove(SELECTED_ASR_PROVIDER)
                preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
                preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
                preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
                preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
                preferences[WEB_SERVER_PORT] = settings.webServerPort
                preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
                preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
                preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
                preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
                preferences[LAUNCH_COUNT] = settings.launchCount
                preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
                // 工具矩阵 (自研 v4.8.83 重写)
                preferences[TOOL_ZONES] = JsonInstant.encodeToString(settings.toolZones)
                preferences[TOOL_ZONE_LINKS] = JsonInstant.encodeToString(settings.toolZoneLinks)
                preferences[HIDDEN_ZONES] = JsonInstant.encodeToString(settings.hiddenZones)
                preferences[TOP_LEVEL_ADDITIONS] = JsonInstant.encodeToString(settings.topLevelAdditions)
                preferences[TOP_LEVEL_REMOVALS] = JsonInstant.encodeToString(settings.topLevelRemovals)
                preferences[TOOL_ZONE_SEEDED] = true
                preferences[MINIMAL_MODE_CONVERSATIONS] = JsonInstant.encodeToString(settings.minimalModeConversations)
                preferences[ZONE_MODEL_VERSION] = settings.zoneModelVersion
                preferences[SUBAGENT_TOKEN_BUDGET] = settings.subagentTokenBudget
                preferences[SUBAGENT_TOKEN_USAGE] = JsonInstant.encodeToString(settings.subagentTokenUsage)
                preferences[PRESET_SKILLS_SEED_VERSION] = settings.presetSkillsSeedVersion
                preferences[TOOL_DESCRIPTION_OVERRIDES] = JsonInstant.encodeToString(settings.toolDescriptionOverrides)
                preferences[CLASSIFIER_PROMPT] = settings.classifierPrompt
                // v3.6.102 工具改名 (自研)
                preferences[TOOL_NAME_OVERRIDES] = JsonInstant.encodeToString(settings.toolNameOverrides)
                // 旧「工具域」键一次性清除 (迁移已完成, 不再保留墓碑)
                listOf(
                    TOOL_DOMAIN_OVERRIDES, CUSTOM_DOMAIN_DESCRIPTIONS, CUSTOM_DOMAINS, CUSTOM_DOMAIN_KEYWORDS,
                    DOMAIN_NAME_OVERRIDES, HIDDEN_DOMAINS, REMOVED_BUILTIN_DOMAINS,
                    EXEMPT_FROM_DOMAIN_TOOLS, DEMOTED_FRAMEWORK_TOOLS,
                ).forEach { preferences.remove(it) }
            }
        }

        // 工具路由
        val TOOL_DOMAIN_OVERRIDES = stringPreferencesKey("tool_domain_overrides")
        val CUSTOM_DOMAIN_DESCRIPTIONS = stringPreferencesKey("custom_domain_descriptions")
        val CUSTOM_DOMAINS = stringPreferencesKey("custom_domains")
        val CUSTOM_DOMAIN_KEYWORDS = stringPreferencesKey("custom_domain_keywords")
        val TOOL_DESCRIPTION_OVERRIDES = stringPreferencesKey("tool_description_overrides")
        // ── 工具矩阵（v4.8.83）──
        val TOOL_ZONES = stringPreferencesKey("tool_zones")
        val TOOL_ZONE_LINKS = stringPreferencesKey("tool_zone_links")
        val HIDDEN_ZONES = stringPreferencesKey("hidden_zones")
        val TOP_LEVEL_ADDITIONS = stringPreferencesKey("top_level_additions")
        val TOP_LEVEL_REMOVALS = stringPreferencesKey("top_level_removals")
        val TOOL_ZONE_SEEDED = booleanPreferencesKey("tool_zone_seeded")
        val MINIMAL_MODE_CONVERSATIONS = stringPreferencesKey("minimal_mode_conversations")
        val ZONE_MODEL_VERSION = intPreferencesKey("zone_model_version")
        // v4.8.88: 子代理预算（每对话 Token 上限/已用）与预设技能播种版本
        val SUBAGENT_TOKEN_BUDGET = longPreferencesKey("subagent_token_budget")
        val SUBAGENT_TOKEN_USAGE = stringPreferencesKey("subagent_token_usage")
        val PRESET_SKILLS_SEED_VERSION = intPreferencesKey("preset_skills_seed_version")
        // ── 旧「工具域」键：v4.8.83 起只读一次用于迁移，不再写入（保存时清除）──
        val DOMAIN_NAME_OVERRIDES = stringPreferencesKey("domain_name_overrides")
        val HIDDEN_DOMAINS = stringPreferencesKey("hidden_domains")
        val REMOVED_BUILTIN_DOMAINS = stringPreferencesKey("removed_builtin_domains")
        val EXEMPT_FROM_DOMAIN_TOOLS = stringPreferencesKey("exempt_from_domain_tools")
        val DEMOTED_FRAMEWORK_TOOLS = stringPreferencesKey("demoted_framework_tools")
        val CLASSIFIER_PROMPT = stringPreferencesKey("classifier_prompt")
        val TOOL_NAME_OVERRIDES = stringPreferencesKey("tool_name_overrides")
    }

    private val dataStore = context.settingsStore

    val settingsFlowRaw = dataStore.data
        .retryWhen { cause, attempt ->
            // v4.8.64 (2.5.5 移植): 读取失败绝不能回退为空配置 (默认值会被当成用户
            // 数据写回, 覆盖全部设置); 偶发 IO 错误重试, 仍失败向上抛出。
            val shouldRetry =
                cause is IOException && cause !is CorruptionException && attempt < READ_MAX_RETRIES
            if (shouldRetry) {
                Log.w(TAG, "Failed to read settings, retrying (${attempt + 1}/$READ_MAX_RETRIES)", cause)
                delay((100L shl attempt.toInt()).milliseconds)
            }
            shouldRetry
        }.map { preferences ->
            // ── 工具矩阵（v4.8.83 重写）──
            // 首次加载/升级: 把旧「工具域」九件套一次性迁移成工具区模型。幂等 —— 迁移完成后
            // 保存侧写死 toolZoneSeeded=true 并清除旧键，此后只读新键，绝不重复迁移复活已删的区。
            val legacyZones = if (preferences[TOOL_ZONE_SEEDED] == true) null else LegacyZoneConfig(
                customDomains = preferences[CUSTOM_DOMAINS]?.let { runCatching { JsonInstant.decodeFromString<List<LegacyCustomDomain>>(it) }.getOrDefault(emptyList()) } ?: emptyList(),
                toolDomainOverrides = preferences[TOOL_DOMAIN_OVERRIDES]?.let { runCatching { JsonInstant.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap()) } ?: emptyMap(),
                customDomainDescriptions = preferences[CUSTOM_DOMAIN_DESCRIPTIONS]?.let { runCatching { JsonInstant.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap()) } ?: emptyMap(),
                customDomainKeywords = preferences[CUSTOM_DOMAIN_KEYWORDS]?.let { runCatching { JsonInstant.decodeFromString<Map<String, List<String>>>(it) }.getOrDefault(emptyMap()) } ?: emptyMap(),
                domainNameOverrides = preferences[DOMAIN_NAME_OVERRIDES]?.let { runCatching { JsonInstant.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap()) } ?: emptyMap(),
                hiddenDomains = preferences[HIDDEN_DOMAINS]?.let { runCatching { JsonInstant.decodeFromString<Set<String>>(it) }.getOrDefault(emptySet()) } ?: emptySet(),
                removedBuiltinDomains = preferences[REMOVED_BUILTIN_DOMAINS]?.let { runCatching { JsonInstant.decodeFromString<Set<String>>(it) }.getOrDefault(emptySet()) } ?: emptySet(),
                exemptFromDomainTools = preferences[EXEMPT_FROM_DOMAIN_TOOLS]?.let { runCatching { JsonInstant.decodeFromString<Set<String>>(it) }.getOrDefault(emptySet()) } ?: emptySet(),
            ).migrateToZones()
            val zoneSeed = legacyZones?.zones
                ?: preferences[TOOL_ZONES]?.let { runCatching { JsonInstant.decodeFromString<List<ToolZone>>(it) }.getOrDefault(emptyList()) } ?: emptyList()
            val zoneLinks = legacyZones?.links
                ?: preferences[TOOL_ZONE_LINKS]?.let { runCatching { JsonInstant.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap()) } ?: emptyMap()
            val zoneHidden = legacyZones?.hiddenZones
                ?: preferences[HIDDEN_ZONES]?.let { runCatching { JsonInstant.decodeFromString<Set<String>>(it) }.getOrDefault(emptySet()) } ?: emptySet()
            val zoneTopLevelAdd = legacyZones?.topLevelAdditions
                ?: preferences[TOP_LEVEL_ADDITIONS]?.let { runCatching { JsonInstant.decodeFromString<Set<String>>(it) }.getOrDefault(emptySet()) } ?: emptySet()
            val zoneTopLevelRemove = if (legacyZones != null) emptySet()
                else preferences[TOP_LEVEL_REMOVALS]?.let { runCatching { JsonInstant.decodeFromString<Set<String>>(it) }.getOrDefault(emptySet()) } ?: emptySet()
            // v4.8.87: 模型投影（一次性，幂等）—— 把 v1「id 即路径」显式化为 name+parentId；
            // 同时把归属键统一成完整工具名（`skill:<名>` → `skill__<净化名>`，与 SkillManager 同源）。
            val zoneModelStale = (preferences[ZONE_MODEL_VERSION] ?: 0) < ZONE_MODEL_VERSION_CURRENT
            val fixedZones = if (zoneModelStale) projectZoneModel(zoneSeed) else zoneSeed
            val fixedLinks = if (zoneModelStale) migrateLinkKeys(zoneLinks) else zoneLinks
            Settings(
                enableWebSearch = preferences[ENABLE_WEB_SEARCH] == true,
                deferAutoReply = preferences[DEFER_AUTO_REPLY] == true,
                swapSendReasoningKeys = preferences[SWAP_SEND_REASONING_KEYS] == true,
                projectPackClickStats = preferences[PROJECT_PACK_CLICK_STATS] ?: "",
                opencodeRequestMode = preferences[OPENCODE_REQUEST_MODE] == true,
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelReasoningLevel = preferences[FAST_MODEL_REASONING_LEVEL]
                    ?.let { value -> ReasoningLevel.entries.find { it.name == value } }
                    ?: ReasoningLevel.AUTO,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                // 4.0.7.1: abilities 数据层兜底 — 历史遗留模型 (旧版添加时无
                // abilities 注入) 持久化 abilities 为空, UI 门控 (ChatInput 思考
                // 按钮) 与请求层 (REASONING/TOOL 参数) 全部哑火。加载时按注册表
                // 还原真实能力 (读时增强, 不写回 DataStore), 一处修复覆盖全部
                // 消费点 — 与原版"添加时注入"语义等价的运行时补全。
                providers = JsonInstant.decodeFromString<List<ProviderSetting>>(
                    preferences[PROVIDERS] ?: "[]"
                ).map { provider ->
                    val needsFix = provider.models.any { it.abilities.isEmpty() }
                    if (!needsFix) provider else provider.copyProvider(
                        models = provider.models.map { model ->
                            if (model.abilities.isEmpty()) {
                                model.copy(abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId))
                            } else model
                        }
                    )
                },
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                opencodeApiKey = preferences[OPENCODE_API_KEY] ?: "",
                opencodeWarmEnabled = preferences[OPENCODE_WARM_ENABLED] == true,
                commandCodeWarmEnabled = preferences[COMMAND_CODE_WARM_ENABLED] == true,
                ccImageCompat = preferences[CC_IMAGE_COMPAT] == true,
                imageUploadMode = preferences[IMAGE_UPLOAD_MODE] ?: "classic",
                fileUploadMode = preferences[FILE_UPLOAD_MODE] ?: "compat",
                usageViewMode = preferences[USAGE_VIEW_MODE] ?: "cards",
                opencodeApiKeys = preferences[OPENCODE_API_KEYS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                videoGenEndpoints = preferences[VIDEO_GEN_ENDPOINTS]?.let {
                    runCatching { JsonInstant.decodeFromString<List<me.rerere.rikkahub.data.videogen.VideoGenEndpoint>>(it) }
                        .getOrDefault(me.rerere.rikkahub.data.videogen.VideoGenDefaults.endpoints)
                } ?: me.rerere.rikkahub.data.videogen.VideoGenDefaults.endpoints,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                networkSetting = JsonInstant.decodeFromString(preferences[NETWORK_SETTING] ?: "{}"),
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences[ASR_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                modeInjections = preferences[MODE_INJECTIONS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
                toolZones = fixedZones,
                toolZoneLinks = fixedLinks,
                zoneModelVersion = ZONE_MODEL_VERSION_CURRENT,
                subagentTokenBudget = preferences[SUBAGENT_TOKEN_BUDGET] ?: 100_000L,
                subagentTokenUsage = preferences[SUBAGENT_TOKEN_USAGE]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, Long>>(it) }.getOrDefault(emptyMap())
                } ?: emptyMap(),
                presetSkillsSeedVersion = preferences[PRESET_SKILLS_SEED_VERSION] ?: 0,
                hiddenZones = zoneHidden,
                topLevelAdditions = zoneTopLevelAdd,
                topLevelRemovals = zoneTopLevelRemove,
                toolZoneSeeded = true,
                minimalModeConversations = preferences[MINIMAL_MODE_CONVERSATIONS]?.let { runCatching { JsonInstant.decodeFromString<Set<String>>(it) }.getOrDefault(emptySet()) } ?: emptySet(),
                toolDescriptionOverrides = preferences[TOOL_DESCRIPTION_OVERRIDES]?.let { JsonInstant.decodeFromString(it) } ?: emptyMap(),
                classifierPrompt = preferences[CLASSIFIER_PROMPT] ?: "",
            )
        }
        .map {
            var providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (providers.none { it.id == defaultProvider.id }) {
                    providers.add(defaultProvider.copyProvider())
                }
            }
            providers = providers.map { provider ->
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                        description = defaultProvider.description,
                        shortDescription = defaultProvider.shortDescription,
                    )
                } else provider
            }.toMutableList()
            // 仅当完全为空时兜底默认助手 (数据异常保护); 不再自动补回缺失的默认助手 —
            // 用户可删除任意助手 (含默认), UI 层保证仅剩最后 1 个时禁止删除
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
            )
        }
        // v4.8.87: 工具矩阵归一化（只修不增）—— 悬空父引用 / 重复 id / 失效挂载在这里被修掉，
        // 修完的数据才是全应用（UI 与模型侧）看到的唯一事实。
        .map { me.rerere.rikkahub.data.ai.tools.routing.ZoneOps.normalize(it) }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    // v3.6.7: 写版本号 — 每次 update 递增, UI 订阅后强制重建 (防 StateFlow
    // 值去重导致写后界面不刷新; 工具写入/设置页修改均触发)
    val settingsRevision = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** v3.11.30: 同步内存写 (主线程安全, 值与 revision 立即生效; 磁盘由调用方异步持久化) */
    fun updateSync(settings: Settings) {
        if (settings.init) return
        settingsFlow.value = settings
        settingsRevision.value = settingsRevision.value + 1
    }

    /**
     * v4.8.89: 整快照写**必须持锁** —— 此前它不持锁，与 `update {}` / `updateWithResult` 的
     * 原子读-改-写交错时互相回退（"回执说已更新却没写进去"的另一半根因）。
     */
    suspend fun update(settings: Settings) {
        settingsMutex.withLock { updateLocked(settings) }
    }

    /** 无锁实现 —— 仅供已持锁的调用方（update {} / updateWithResult）使用。 */
    private suspend fun updateLocked(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        // v3.11.30: 内存写同步先落 (toggle 后紧接发送不再读到旧值 — 延迟自动
        // 回复拦截竞态根因: updateSettings 走 viewModelScope.launch 排队, 发送
        // 事件可能先于 flow 更新执行), 磁盘持久化仍挂起写入。
        updateSync(settings)
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = settings.dynamicColor
            preferences[THEME_ID] = settings.themeId
            preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
            preferences[DEVELOPER_MODE] = settings.developerMode
            preferences[OPENCODE_API_KEY] = settings.opencodeApiKey
            preferences[USAGE_VIEW_MODE] = settings.usageViewMode
            preferences[OPENCODE_API_KEYS] = JsonInstant.encodeToString(settings.opencodeApiKeys)
            preferences[OPENCODE_WARM_ENABLED] = settings.opencodeWarmEnabled
            preferences[COMMAND_CODE_WARM_ENABLED] = settings.commandCodeWarmEnabled
            preferences[CC_IMAGE_COMPAT] = settings.ccImageCompat
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
            preferences[NETWORK_SETTING] = JsonInstant.encodeToString(settings.networkSetting)

            preferences[ENABLE_WEB_SEARCH] = settings.enableWebSearch
            preferences[DEFER_AUTO_REPLY] = settings.deferAutoReply
            preferences[SWAP_SEND_REASONING_KEYS] = settings.swapSendReasoningKeys
            preferences[PROJECT_PACK_CLICK_STATS] = settings.projectPackClickStats
            preferences[OPENCODE_REQUEST_MODE] = settings.opencodeRequestMode
            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            preferences[SELECT_MODEL] = settings.chatModelId.toString()
            preferences[FAST_MODEL] = settings.fastModelId.toString()
            preferences[FAST_MODEL_REASONING_LEVEL] = settings.fastModelReasoningLevel.name
            preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
            preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
            preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
            preferences[TITLE_PROMPT] = settings.titlePrompt
            preferences[TRANSLATION_PROMPT] = settings.translatePrompt
            preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
            preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
            preferences[OCR_MODEL] = settings.ocrModelId.toString()
            preferences[OCR_PROMPT] = settings.ocrPrompt
            preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
            preferences[COMPRESS_PROMPT] = settings.compressPrompt

            preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)

            preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
            preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            preferences[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, settings.searchServices.size - 1)

            preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
            preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
            settings.selectedTTSProviderId?.let {
                preferences[SELECTED_TTS_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_TTS_PROVIDER)
            preferences[DEFAULT_TTS_PLAYBACK_SPEED] = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
            preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
            settings.selectedASRProviderId?.let {
                preferences[SELECTED_ASR_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_ASR_PROVIDER)
            preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
            preferences[WEB_SERVER_PORT] = settings.webServerPort
            preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
            preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
            preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
            preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
            preferences[LAUNCH_COUNT] = settings.launchCount
            preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt

            // 工具矩阵 (自研 v4.8.83 重写)
            preferences[TOOL_ZONES] = JsonInstant.encodeToString(settings.toolZones)
            preferences[TOOL_ZONE_LINKS] = JsonInstant.encodeToString(settings.toolZoneLinks)
            preferences[HIDDEN_ZONES] = JsonInstant.encodeToString(settings.hiddenZones)
            preferences[TOP_LEVEL_ADDITIONS] = JsonInstant.encodeToString(settings.topLevelAdditions)
            preferences[TOP_LEVEL_REMOVALS] = JsonInstant.encodeToString(settings.topLevelRemovals)
            preferences[TOOL_ZONE_SEEDED] = true
            preferences[MINIMAL_MODE_CONVERSATIONS] = JsonInstant.encodeToString(settings.minimalModeConversations)
            preferences[ZONE_MODEL_VERSION] = settings.zoneModelVersion
            preferences[SUBAGENT_TOKEN_BUDGET] = settings.subagentTokenBudget
            preferences[SUBAGENT_TOKEN_USAGE] = JsonInstant.encodeToString(settings.subagentTokenUsage)
            preferences[PRESET_SKILLS_SEED_VERSION] = settings.presetSkillsSeedVersion
            preferences[TOOL_DESCRIPTION_OVERRIDES] = JsonInstant.encodeToString(settings.toolDescriptionOverrides)
            preferences[CLASSIFIER_PROMPT] = settings.classifierPrompt
            // 旧「工具域」键一次性清除 (迁移已完成, 不再保留墓碑)
            listOf(
                TOOL_DOMAIN_OVERRIDES, CUSTOM_DOMAIN_DESCRIPTIONS, CUSTOM_DOMAINS, CUSTOM_DOMAIN_KEYWORDS,
                DOMAIN_NAME_OVERRIDES, HIDDEN_DOMAINS, REMOVED_BUILTIN_DOMAINS,
                EXEMPT_FROM_DOMAIN_TOOLS, DEMOTED_FRAMEWORK_TOOLS,
            ).forEach { preferences.remove(it) }
        }
    }

    suspend fun update(fn: (Settings) -> Settings) {
        // 原子读-改-写: 并行操作(create/delete/rename/move)基于最新值,
        // 避免丢失更新 (此前并行 create 丢域 / rename+delete 竞态)
        settingsMutex.withLock {
            updateLocked(fn(settingsFlow.value))
        }
    }

    /** 原子读-改-写并返回操作结果 — 供工具 execute 在锁内计算并携带结果 */
    suspend fun <T> updateWithResult(fn: (Settings) -> Pair<Settings, T>): T {
        settingsMutex.withLock {
            val (newSettings, result) = fn(settingsFlow.value)
            updateLocked(newSettings)
            return result
        }
    }

    // v4.8.64 (2.5.5 移植): 只原子地修改单个 key, 不能用 update() 写回整份快照
    suspend fun incrementLaunchCount(): Int {
        var count = 0
        dataStore.edit { preferences ->
            count = (preferences[LAUNCH_COUNT] ?: 0) + 1
            preferences[LAUNCH_COUNT] = count
        }
        return count
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        dataStore.edit { preferences ->
            preferences[SELECT_ASSISTANT] = assistantId.toString()
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val opencodeApiKey: String = "", // v3.8.0: OpenCode 用量查询 API Key (当前选中/最后使用)
    val opencodeApiKeys: List<String> = emptyList(), // v3.8.1: 密钥卡包 (历史密钥列表)
    val usageViewMode: String = "cards", // v3.9.10: 用量查询视图 cards=多卡片 focus=焦点单卡, 持久化
    val opencodeWarmEnabled: Boolean = false, // v3.12.6: OpenCode 专项预热开关 (默认关, 用户可选)
    val commandCodeWarmEnabled: Boolean = false, // v3.12.6: Command Code 专项预热开关 (默认关, 用户可选)
    val ccImageCompat: Boolean = false, // v3.13.3: Command Code 图片兼容适配 (默认关, opt-in, 仅 CC 通道)
    // v4.5.5: 客户端设置 — 上传模式 (用户可选, 二选一, 请求体严格按所选模式构造)
    // 图片: classic=当前 (base64 内联+预算持久标记+工具图转移 user) / compat=以前 (工具图内嵌 tool content)
    // 文件: classic=以前 (全文提取内联 prompt) / compat=当前 (精确路径引用+workspace_read_file 按需读取)
    val imageUploadMode: String = "classic",
    val fileUploadMode: String = "compat",
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val enableWebSearch: Boolean = false,
    // v3.6.13: 延迟自动回复 — 开启时发消息不触发模型回复 (消息排队,
    // 关闭后发消息触发; 解决消息未发完模型打断回复)
    val deferAutoReply: Boolean = false,
    // v4.8.3: 发送键/思考键切换 — 开启后互换发送(打断)键与思考深度键的位置与图标,
    // 防止生成中误触右下角打断键导致中断
    val swapSendReasoningKeys: Boolean = false,
    // v4.8.58: 项目包点击统计 (智能排序数据源) — JSON: {"<folderUuid>":[epochMs,...]};
    // 仅保留 3 天内事件 (过期真删), 由 ChatDrawerVM 维护
    val projectPackClickStats: String = "",
    // v4.5.17: 仿 OpenCode 请求模式 — 对齐 OpenCode 客户端对 opencode.ai 网关
    // 的每模型协议分派 (models.dev npm 同源), 使 Responses/Anthropic/Google
    // 专属模型可用; 关闭时零行为变化
    val opencodeRequestMode: Boolean = false,
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid? = null, // 类型修正: 未选择时为空 (原声明非空与 442 行 remove 逻辑矛盾)
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
    val routingModelId: Uuid? = null, // 路由表生成模型。null=用静态模板
    // ── 工具矩阵（v4.8.83 重写：工具区声明即全部，内置/自定义不再区分）──
    val toolZones: List<ToolZone> = emptyList(), // 全部工具区（出厂模板首启播种一次，之后完全归用户）
    val toolZoneLinks: Map<String, String> = emptyMap(), // 工具名 / skill:名 → 工具区 id（手动归属；空=自动归类）
    val hiddenZones: Set<String> = emptySet(), // 不在模型侧场景地图出现的工具区（保留归类与显式加载能力）
    val topLevelAdditions: Set<String> = emptySet(), // 额外提升到顶层的工具（始终注入请求体）
    val topLevelRemovals: Set<String> = emptySet(), // 从顶层降级到工具区的工具
    val toolZoneSeeded: Boolean = false, // 出厂模板是否已播种（迁移幂等标记）
    /** v4.8.85 极简模式：这些对话不注入任何工具（连 MCP/记忆/任务清单都不注入），纯文本对话。
     *  按对话持久化（DataStore，非 Room 表 → 无迁移风险）；对话删除后残留 id 无副作用。 */
    val minimalModeConversations: Set<String> = emptySet(),
    /** v4.8.87 工具区模型版本（v2 = id 不透明 + name/parentId）—— 一次性投影的幂等标记 */
    val zoneModelVersion: Int = ZONE_MODEL_VERSION_CURRENT,
    /**
     * v4.8.88 子代理预算：一个对话通过子代理最多能消耗的 Token（默认 100K）。
     * 超出即熔断：终止正在运行的子代理 + 禁止新派发 + 告知模型。
     */
    val subagentTokenBudget: Long = 100_000L,
    /** v4.8.88 每对话已消耗的子代理 Token（conversationId → tokens）。UI 可清零。 */
    val subagentTokenUsage: Map<String, Long> = emptyMap(),
    /** v4.8.88 预设技能播种版本（0=未播种；只增不复活用户删掉的技能） */
    val presetSkillsSeedVersion: Int = 0,
    val toolDescriptionOverrides: Map<String, String> = emptyMap(), // 工具名→自定义描述。覆盖原始Tool描述
    val toolNameOverrides: Map<String, String> = emptyMap(), // v3.6.102: 工具改名 — 原工具名→新工具名 (汉语名工具改为字母数字, 模型才能识别)
    val classifierPrompt: String = "", // 工具自动分类提示词。空=使用默认
    // v4.8.102: 视频生成接口 (URL / 模型 ID / 密钥 均在视频生成页内编辑; 内置 Veo + HappyHorse 双适配)
    val videoGenEndpoints: List<me.rerere.rikkahub.data.videogen.VideoGenEndpoint> = me.rerere.rikkahub.data.videogen.VideoGenDefaults.endpoints,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
data class NetworkSetting(
    // v3.16.0: UI 与请求注入已移除 (用户定版: 不需要 UA 方向的功能)。
    // 字段保留仅为 DataStore 旧 JSON 反序列化兼容, 恒为 "" 不再消费
    @Deprecated("v3.16.0 removed: UI & header injection")
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    // v3.9.15: 代理开关 — false = 完全不走代理 (即使配了代理地址)
    val proxyEnabled: Boolean = false,
    // v3.9.15: 部分开启 — true = 仅勾选模型的请求走代理; false = 全局走代理
    val proxyPartialEnabled: Boolean = false,
    // v3.15.0: 自动重试开关 (2.4.16 移植) — false = 断联直接报错不重试
    val enableAutoRetry: Boolean = true,
    // v3.16.0: 强兼容模式 — 开启后 Chat Completions 请求按 Cherry Studio
    // 极简格式发送 (不回传 reasoning_content / 不发思考控制参数 / tool 消息
    // 去 name / 纯 reasoning assistant 跳过), 以最大化任意模型可用性;
    // 代价: 思考档位调节与历史推理回传在开启期间失效
    val cherryCompatMode: Boolean = false,
    // v3.9.15: 勾选走代理的模型 id (modelId 字符串) 列表
    val proxyModelIds: List<String> = emptyList(),
)

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

// 2.5.3 移植: 输入栏背景效果 (模糊 / 玻璃)
@Serializable
enum class BackgroundEffectType {
    @SerialName("blur")
    BLUR,
    @SerialName("glass")
    GLASS,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val backgroundEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val showUpdates: Boolean = true,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

/**
 * 删除模型后的级联清理: 所有指向被删模型的引用全部清空/回退 —
 * 设置项 (chat/fast/title/image/translate/suggestion/ocr/compress/routing)
 * + 收藏列表 + 助手绑定 (回退全局默认 null)
 */
fun Settings.cleanupDeletedModels(deletedModelIds: Set<Uuid>): Settings {
    fun Uuid?.clearIfDeleted(): Uuid? = if (this != null && this in deletedModelIds) null else this
    fun Uuid.rollbackIfDeleted(): Uuid = if (this in deletedModelIds) Uuid.random() else this
    return copy(
        chatModelId = chatModelId.rollbackIfDeleted(),
        fastModelId = fastModelId.rollbackIfDeleted(),
        imageGenerationModelId = imageGenerationModelId.rollbackIfDeleted(),
        translateModeId = translateModeId.rollbackIfDeleted(),
        ocrModelId = ocrModelId.rollbackIfDeleted(),
        compressModelId = compressModelId.rollbackIfDeleted(),
        routingModelId = routingModelId.clearIfDeleted(),
        favoriteModels = favoriteModels.filterNot { it in deletedModelIds },
        assistants = assistants.map { a ->
            if (a.chatModelId != null && a.chatModelId in deletedModelIds) a.copy(chatModelId = null) else a
        },
    )
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Time: {{cur_datetime}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
