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
suspend fun Settings.resolveRoutedModel(assistant: me.rerere.rikkahub.data.model.Assistant): Model? {
    val normal = findModelById(assistant.chatModelId ?: chatModelId)
    val pool = when (assistant.modelRouting) {
        1 -> fastChatModelIds
        2 -> ultraChatModelIds
        else -> return normal
    }
    if (pool.isEmpty()) return normal
    // 池内模型可能已被删除 —— 不能拿一个查不到的 id 去当选择结果
    val valid = pool.filter { findModelById(it) != null }
    if (valid.isEmpty()) return normal
    if (valid.size == 1) return findModelById(valid.first()) ?: normal

    // ═══ v5.3.34 ★★「选最快的模型」重做：用真实首字统计，且永不阻塞发送 ═══
    //
    // 旧实现在这里同步调用 `ModelRacer.pickFastest(timeoutMs = 1500)`：
    //   · 探针打的是 `GET {baseUrl}/models`（元数据端点）——**测的不是模型速度**；
    //   · 同一 baseUrl 的多个模型探针打同一个 URL ——「同 provider 内选最快」不成立；
    //   · 移动网 TLS 664ms~1.9s，1.5s 同步超时 → 实测**常常全部超时** → 回退池首，
    //     既没选出最优，又在首字前白等最多 1.5s（用户能感知）。
    //
    // 现在：`ModelLatencyStats` 记录**每次真实请求的首字耗时**（ChatService 写入），
    // 这里只读统计、零等待；没有样本就用池首，并把连接预热丢到后台。
    // 这与 LiteLLM 的 latency-based-routing 同一思路（用它自己的历史响应时间），
    // 只是搬到客户端、且不引入任何服务端依赖。
    val winner = me.rerere.rikkahub.data.ai.ModelLatencyStats.fastest(valid) ?: valid.first()
    // ═══ v5.3.34 ★后台把连接热起来 —— 必须真·后台 ═══
    // `ModelRacer.prefetch` 是 suspend（内含最长 5s 的并发探针）。这里如果直接调用，
    // 它就变成**在首字前同步等待探针** —— 正是本轮要消除的那个问题，只是换了名字。
    // 所以丢进 AppScope + Dispatchers.IO，fire-and-forget。
    runCatching {
        val scope = org.koin.core.context.GlobalContext.getOrNull()
            ?.getOrNull<me.rerere.rikkahub.AppScope>()
        val s = this
        scope?.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { me.rerere.rikkahub.data.ai.ModelRacer.prefetch(s, valid) }
        }
    }
    return findModelById(winner) ?: normal
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
