package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

/** 所有提供商都还没有添加模型，视为尚未完成配置。 */
fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

/**
 * 按 ID 查找模型。
 *
 * @param fallback [uuid] 为空或已找不到对应模型时改用的模型 ID
 */
/**
 * v5.1.4 ★主对话模型路由（用户要求「快速 / 极致」）：
 *   - 普通模式 或 池未配置 → **完全走原路径**（零行为变化）；
 *   - 快速/极致 → 让 ModelRacer 并发竞速挑最快的，失败/超时一律回退池首，再失败回退普通模型。
 * 整段被 runCatching 包住：**这个功能彻底坏掉，最坏结果也只是用普通模型**。
 */
suspend fun Settings.resolveRoutedModel(assistant: me.rerere.rikkahub.data.model.Assistant): Model? {
    val normal = findModelById(assistant.chatModelId ?: chatModelId)
    val pool = when (assistant.modelRouting) {
        1 -> fastChatModelIds
        2 -> ultraChatModelIds
        else -> return normal
    }
    if (pool.isEmpty()) return normal
    return runCatching {
        val winner = me.rerere.rikkahub.data.ai.ModelRacer.pickFastest(this, pool)
        findModelById(winner ?: pool.first()) ?: normal
    }.getOrNull() ?: normal
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
