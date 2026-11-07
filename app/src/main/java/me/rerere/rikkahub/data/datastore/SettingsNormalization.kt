package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import kotlin.uuid.Uuid

/** 读盘后和每次修改后都要过一遍，保证内存里的设置始终是规整的。 */
internal fun Settings.normalized(): Settings =
    withBuiltInDefaults().withoutInvalidReferences().withValuesInRange()

// 补齐缺失的内置提供商/助手/TTS，内置提供商的描述信息始终以代码里的为准
internal fun Settings.withBuiltInDefaults(): Settings {
    var providers = this.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
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
    // RinCore：仅当完全为空时兜底默认助手，不自动补回被用户删除的默认助手
    val assistants = this.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
    val ttsProviders = this.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
    DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
        if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
            ttsProviders.add(defaultTTSProvider.copyProvider())
        }
    }
    return copy(
        providers = providers,
        assistants = assistants,
        ttsProviders = ttsProviders,
    )
}

// 去重并清理无效引用
internal fun Settings.withoutInvalidReferences(): Settings {
    val settings = this
    val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
    val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
    val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
    val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
    val validModelIds by lazy { settings.providers.flatMap { it.models }.mapTo(HashSet()) { it.id } }
    val asrProviders = settings.asrProviders.distinctBy { it.id }
    return settings.copy(
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
                }.toSet(),
                // ★v5.3.58：助手自己绑定的模型被删 → 回落到全局默认（null 会走 assistant.chatModelId ?: chatModelId）
                chatModelId = assistant.chatModelId.nullIfGone(validModelIds),
            )
        },
        ttsProviders = settings.ttsProviders.distinctBy { it.id },
        asrProviders = asrProviders,
        selectedASRProviderId = settings.selectedASRProviderId
            ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
            ?: asrProviders.firstOrNull()?.id,
        favoriteModels = settings.favoriteModels.filter { it in validModelIds },
        // ★v5.3.58 悬空模型引用自愈（旧 cleanupDeletedModels 是零调用点死代码，已并入这里）。
        // 只处理**可空**字段：它们各自有回退链，清成 null 是安全且正确的
        //（做梦 → fastModel → chatModel）。非空字段刻意不动 ——
        // DEFAULT_AUTO_MODEL_ID 并不是任何真实模型的 id（只是缺省占位），
        // 把它塞进去等于「修了个寂寞」；而随便挑一个真实模型又可能把用户
        // 悄悄指向一个没配 key 的提供商。删了模型就该让用户重选，报错比乱猜好。
        dreamMemoryModelId = settings.dreamMemoryModelId.nullIfGone(validModelIds),
        routingModelId = settings.routingModelId.nullIfGone(validModelIds),
        // 快速/极致池里被删掉的模型要剔除，否则竞速探针会去打已经不存在的模型
        fastChatModelIds = settings.fastChatModelIds.filter { it in validModelIds },
        ultraChatModelIds = settings.ultraChatModelIds.filter { it in validModelIds },
        modeInjections = settings.modeInjections.distinctBy { it.id },
        lorebooks = settings.lorebooks.distinctBy { it.id },
        quickMessages = settings.quickMessages.distinctBy { it.id },
    )
}

// 把取值收回合法范围，比如删掉搜索服务后选中的下标可能越界
private fun Settings.withValuesInRange(): Settings = copy(
    searchServiceSelected = searchServiceSelected.coerceIn(0, (searchServices.size - 1).coerceAtLeast(0)),
    defaultTTSPlaybackSpeed = defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f),
)

// ── RinCore 自研数据修复（原 PreferencesStore 解码链，迁移至规整阶段；幂等）──
/**
 * 1) 4.0.7.1 模型 abilities 数据层兜底（历史模型缺 abilities → 思考/工具门控哑火）
 * 2) v4.8.108 子代理预算 100K→1000K 一次性升级（旧默认值自动升值；手动值尊重保留）
 * 3) v4.8.139 子代理并发 3→7 一次性升级（存量助手 JSON 里固定旧默认）
 */
internal fun Settings.withOurDataRepairs(): Settings {
    var s = this
    if (s.providers.any { p -> p.models.any { it.abilities.isEmpty() } }) {
        s = s.copy(
            providers = s.providers.map { provider ->
                if (provider.models.none { it.abilities.isEmpty() }) provider
                else provider.copyProvider(
                    models = provider.models.map { model ->
                        if (model.abilities.isEmpty()) {
                            model.copy(abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId))
                        } else model
                    }
                )
            }
        )
    }
    val budget = if (s.subagentBudgetUpgraded) s.subagentTokenBudget
    else if (s.subagentTokenBudget == 100_000L) 1_000_000L else s.subagentTokenBudget
    val assistants = if (s.subagentConcurrencyUpgraded) s.assistants
    else s.assistants.map { a -> if (a.maxConcurrentSubAgents == 3) a.copy(maxConcurrentSubAgents = 7) else a }
    return s.copy(
        subagentTokenBudget = budget,
        subagentBudgetUpgraded = true,
        subagentConcurrencyUpgraded = true,
        assistants = assistants,
    )
}

/** ★v5.3.58：指向已不存在模型的可空设置 → 清成 null，交给各自的三级回退链。 */
private fun Uuid?.nullIfGone(valid: Set<Uuid>): Uuid? = this?.takeIf { it in valid }
