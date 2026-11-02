package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.tts.provider.TTSProviderSetting
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** 所有提供商都还没有添加模型，视为尚未完成配置。 */
fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

/**
 * 按 ID 查找模型。
 *
 * @param fallback [uuid] 为空或已找不到对应模型时改用的模型 ID
 */
/**
 * 主对话模型路由（用户要求「快速 / 极致」）。
 *
 * v5.3.34 ★语义已重做：**不再用 `/models` 探针在发送前同步竞速**。
 * 原因（代码级）：探针测的是元数据端点，不是模型推理速度；同 baseUrl 的多个模型
 * 探针打同一个 URL，「同 provider 内选最快」不成立；且 1.5s 同步超时在移动网上
 * 常常全部超时 → 回退池首，既没选出最优，又在首字前白等。
 *
 * 现在选模型依据 = `ModelLatencyStats`（**真实请求的历史首字耗时**，由 ChatService 记录）：
 *   - 普通模式 或 池未配置 → 完全走原路径（零行为变化）；
 *   - 有历史样本 → 选历史最快；无样本 → 池首；
 *   - 连接预热丢进 AppScope 后台，**本轮选择与发送零等待**；
 *   - 整个过程 runCatching：功能彻底坏掉，最坏结果也只是用普通模型。
 */
suspend fun Settings.resolveRoutedModel(
    assistant: me.rerere.rikkahub.data.model.Assistant,
    // v5.3.35：会话 ID 用于「粘性」（避免每轮换一个脑子）；不传则每次独立选
    conversationId: String? = null,
    // v5.3.35：当前上下文粗略 token 估算（0 = 未知）。用于排除窗口装不下的模型。
    estimatedTokens: Int = 0,
): Model? {
    val normal = findModelById(assistant.chatModelId ?: chatModelId)
    val pool = when (assistant.modelRouting) {
        1 -> fastChatModelIds
        2 -> ultraChatModelIds
        else -> return normal
    }
    if (pool.isEmpty()) return normal

    val models = pool.mapNotNull { findModelById(it) }.distinctBy { it.id }
    if (models.isEmpty()) return normal

    // ═══ v5.3.35 ★★「能力先于速度」—— 这一条直接对应工具调用被当正文的事故 ═══
    //
    // 旧实现（v5.3.34）只问「谁首字最快」，于是池里一个**未声明 TOOL 能力**的模型
    // 也能因为上次首字快而被选中；而 `ChatCompletionsAPI:1070` 只在 abilities 含 TOOL 时
    // 才发 `tools` 参数 → 该模型拿不到工具定义，却看到历史里的工具调用痕迹 →
    // **把工具调用模仿成正文输出**（用户实测：DeepSeek 中途路由到智谱后工具调用变成正文）。
    //
    // 现在：先按「能否胜任本轮」过滤（工具能力 + 上下文窗口），
    // 只在**合格集合**里按速度排序；一个都不合格就回退用户明确选的普通模型。
    //
    // requireTools 保守取「助手的任何工具来源非空」—— `localTools` 默认就含 TimeInfo，
    // 所以实际上恒为 true。宁可少用快速池，也不要出一个坏的轮次。
    val requireTools = assistant.localTools.isNotEmpty() ||
        assistant.enableMemory ||
        assistant.enableWebSearch

    val eligible = me.rerere.rikkahub.data.ai.ModelRouting.eligibleModels(
        models = models,
        requireTools = requireTools,
        estimatedTokens = estimatedTokens,
    )
    if (eligible.isEmpty()) {
        runCatching {
            android.util.Log.w(
                "ModelRouting",
                "池内 ${models.size} 个模型全部不合格（requireTools=$requireTools, tokens=$estimatedTokens）" +
                    " → 回退普通模型 ${normal?.displayName}",
            )
        }
        return normal
    }

    // 只有 1 个合格候选时不必比速度，直接用它
    if (eligible.size == 1) {
        val only = eligible.first()
        conversationId?.let { me.rerere.rikkahub.data.ai.ModelLatencyStats.markSticky(it, only.id) }
        return findModelById(only.id) ?: normal
    }

    // 会话粘性优先 + 速度排序（见 ModelRouting.pick 的 1.5 倍容忍度）
    val winner = me.rerere.rikkahub.data.ai.ModelRouting.pick(eligible, conversationId)
        ?: eligible.first()
    conversationId?.let { me.rerere.rikkahub.data.ai.ModelLatencyStats.markSticky(it, winner.id) }

    // ═══ v5.3.34 ★后台把连接热起来 —— 必须真·后台 ═══
    // `ModelRacer.prefetch` 是 suspend（内含最长 5s 的并发探针）。这里如果直接调用，
    // 它就变成**在首字前同步等待探针** —— 正是要消除的那个问题，只是换了名字。
    runCatching {
        val scope = org.koin.core.context.GlobalContext.getOrNull()
            ?.getOrNull<me.rerere.rikkahub.AppScope>()
        val s = this
        val ids = eligible.map { it.id }
        scope?.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { me.rerere.rikkahub.data.ai.ModelRacer.prefetch(s, ids) }
        }
    }
    return findModelById(winner.id) ?: normal
}

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

/** 在所有提供商的模型里按 ID 查找。 */
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
 * 当前助手的聊天模型，助手没有单独指定时用全局聊天模型。
 *
 * 已持久化的会话有自己的配置快照，应改用 [me.rerere.rikkahub.data.model.getChatModelOf]。
 */
fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

/**
 * 当前选中的助手，选中的 ID 已不存在时回退到第一个助手。
 *
 * 已持久化的会话应改用 [me.rerere.rikkahub.data.model.getAssistantOf]。
 */
fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

/** 按 ID 查找助手，不存在时返回 null，不做回退。 */
fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

/** 该助手绑定的快捷消息，顺序跟随全局快捷消息列表。 */
fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

/** 当前选中的 TTS 提供商，选中的 ID 已不存在时回退到第一个。 */
fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

/** 当前选中的 ASR 提供商，未选择或选中的 ID 已不存在时回退到第一个。 */
fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

/**
 * 查找模型实际使用的提供商配置。
 *
 * 模型设置了 [Model.providerOverwrite] 时返回这份覆盖配置（不带模型列表），它不在 [providers] 里。
 *
 * @param checkOverwrite 为 false 时忽略覆盖配置，始终返回模型所属的提供商
 * @return 模型不属于 [providers] 里任何提供商时返回 null，即使它带有覆盖配置
 */
fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

/** 模型所属的提供商，即模型列表里包含它的那个。 */
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

/**
 * RinCore 自研：删除模型后的级联清理 — 所有指向被删模型的引用全部清空/回退。
 * 设置项（chat/fast/title/image/video/translate/suggestion/ocr/compress/routing）
 * + 收藏列表 + 助手绑定（回退全局默认 null）。
 */
fun Settings.cleanupDeletedModels(deletedModelIds: Set<Uuid>): Settings {
    fun Uuid?.clearIfDeleted(): Uuid? = if (this != null && this in deletedModelIds) null else this
    fun Uuid.rollbackIfDeleted(): Uuid = if (this in deletedModelIds) Uuid.random() else this
    return copy(
        chatModelId = chatModelId.rollbackIfDeleted(),
        fastModelId = fastModelId.rollbackIfDeleted(),
        imageGenerationModelId = imageGenerationModelId.rollbackIfDeleted(),
        videoGenerationModelId = videoGenerationModelId.rollbackIfDeleted(),
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
