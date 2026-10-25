package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.ai.tools.ProducedFile
import me.rerere.rikkahub.data.ai.tools.routing.ToolZone
import me.rerere.rikkahub.data.ai.tools.routing.ZONE_MODEL_VERSION_CURRENT
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val enableAutoTitle: Boolean = true,
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    // 翻译页的目标语言，存 BCP 47 语言标签
    val translateTargetLanguage: String = "zh-CN",
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
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
    // RemoteFileStore 上传临时素材用的桶，和备份的 s3Config 互不影响；items 字段在这里不使用
    val uploadS3Config: S3Config = S3Config(),
    val mediaGenerationProviders: List<MediaGenerationProviderSetting> = emptyList(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
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
    // ── RinCore 自研字段族（反向换底迁移：一个不少）──
    val opencodeApiKey: String = "",
    val opencodeApiKeys: List<String> = emptyList(),
    val usageViewMode: String = "cards",
    // v5.0.92 用户定案：默认预热 Opencode(go 订阅) 与 CommandCode 两个提供商。
    // 默认 true = 启动即起双 host 常驻心跳（60s，同池；输入栏打开时立即刷新）。
    val opencodeWarmEnabled: Boolean = true,
    val commandCodeWarmEnabled: Boolean = true,
    val ccImageCompat: Boolean = false,
    val imageUploadMode: String = "classic",
    val fileUploadMode: String = "compat",
    val enableWebSearch: Boolean = false,
    val deferAutoReply: Boolean = false,
    val swapSendReasoningKeys: Boolean = false,
    val projectPackClickStats: String = "",
    val opencodeRequestMode: Boolean = false,
    val videoGenerationModelId: Uuid = Uuid.random(),
    val compressModelId: Uuid = Uuid.random(),
    // v5.0.25 用户定案：记忆专用模型（做梦/记忆整理用；null = 回落 fastModel → chatModel）
    val memoryModelId: Uuid? = null,
    // v5.0.67 用户定案：做梦（自动记忆整理）单独一个模型，可与记忆模型不同。
    // 回落顺序：dreamMemoryModelId ?: memoryModelId ?: fastModelId ?: chatModelId
    val dreamMemoryModelId: Uuid? = null,
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val routingModelId: Uuid? = null,
    // ── 工具矩阵（v4.8.83 重写）──
    val toolZones: List<ToolZone> = emptyList(),
    val toolZoneLinks: Map<String, String> = emptyMap(),
    val hiddenZones: Set<String> = emptySet(),
    val topLevelAdditions: Set<String> = emptySet(),
    val topLevelRemovals: Set<String> = emptySet(),
    val toolZoneSeeded: Boolean = false,
    val zoneModelVersion: Int = ZONE_MODEL_VERSION_CURRENT,
    // ── 子代理/产出/技能（v4.8.88+）──
    val subagentTokenBudget: Long = 1_000_000L,
    val subagentBudgetUpgraded: Boolean = false,
    val subagentConcurrencyUpgraded: Boolean = false,
    val subagentTokenUsage: Map<String, Long> = emptyMap(),
    val conversationProducedFiles: Map<String, List<ProducedFile>> = emptyMap(),
    val presetSkillsSeedVersion: Int = 0,
    val toolDescriptionOverrides: Map<String, String> = emptyMap(),
    val toolNameOverrides: Map<String, String> = emptyMap(),
    val classifierPrompt: String = "",
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val sponsorAlertDismissedAt: Int = 0,
    // RinCore 兼容层：旧消费面（统计/调试页）仍读 Settings.launchCount；上游 3.0.0 已改为独立 flow
    val launchCount: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
data class NetworkSetting(
    // v3.16.0: UI 与请求注入已移除（字段保留仅为反序列化兼容，恒为 "" 不再消费）
    @Deprecated("v3.16.0 removed: UI & header injection")
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    // v3.9.15: 代理开关 — false = 完全不走代理
    val proxyEnabled: Boolean = false,
    // v3.9.15: 部分开启 — true = 仅勾选模型的请求走代理
    val proxyPartialEnabled: Boolean = false,
    // v3.15.0: 自动重试开关（2.4.16 移植）
    val enableAutoRetry: Boolean = true,
    // v3.16.0: 强兼容模式（Cherry Studio 极简请求格式）
    val cherryCompatMode: Boolean = false,
    // v3.9.15: 勾选走代理的模型 id 列表
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

@Serializable
enum class BackgroundEffectType {
    @SerialName("blur")
    BLUR,

    @SerialName("glass")
    GLASS,
}

@Serializable
enum class ConversationSortOrder {
    @SerialName("update_time")
    UPDATE_TIME,

    @SerialName("create_time")
    CREATE_TIME,
}

@Serializable
data class DisplaySetting(
    // ── RinCore 兼容层（B1 并树）：v4.8.183 定版玻璃字段 + 旧消费面字段（JSON blob 持久化，零迁移） ──
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val glassMaskPercent: Int = 50,
    val glassMaskColorMode: Int = 1,
    val glassNoisePercent: Int = 100,
    val glassFogPercent: Int = 3,
    val glassBlurRadiusDp: Int = 2,
    val glassLineWidthDp: Float = 1.0f,
    val glassLineBrightness: Int = 30,
    val glassPresetUpgraded: Boolean = false,
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,

    /** v5.1.2（用户要求）：输入栏是否显示「发送键 / 终止键」（同一个位置动态切换） */
    val showSendButton: Boolean = true,

    /** v5.1.2（用户要求）：输入栏是否显示「思考程度键」 */
    val showReasoningButton: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    // v4.8.121（用户定版）：代码块内自动渲染（html/svg/js 自动进入预览）
    val codeBlockAutoPreview: Boolean = false,
    // RinCore 自研：更新提示开关（设置→通知 页消费）
    val showUpdates: Boolean = true,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableQuoteHighlight: Boolean = false,
    val enableBlurEffect: Boolean = false,
    val backgroundEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    val conversationSortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
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
        // v5.0.84 用户定案：备份拆分为「软件本体」（DATABASE+FILES，与原版 3.1.0 完全一致）
        // 与「工作区」（WORKSPACE，我们后加的整工作区含 rootfs）两个独立能力。
        WORKSPACE,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)
