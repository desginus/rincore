/* 【域 V·记忆系统】 — 唯一可见性判据（v5.1.14 统一重构） */
package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.AssistantMemory

/**
 * 记忆系统的**唯一判据层**（v5.1.14 重构）。
 *
 * 背景：此前"模型能看到什么"由 `ChatService` 自己拼（base+folder+sibling+own 四层），
 * "用户能看到什么"由 `AssistantMemoryPage` 自己拼（三档 when），"挂载层级"在两个地方
 * 各写一遍 —— 三处口径一旦漂移就会出现「用户看不到但模型能看到」「设置到项目包后消失」
 * 这类事故（v5.1.13 修过一轮同类问题）。
 *
 * 现在：**任何"某条记忆在某上下文是否可见"的判断只能调本文件**。
 * UI 管理页与注入链都只是把结果拿去渲染/拼 prompt，不再各自定义语义。
 */
object MemoryVisibility {

    /** 记忆的存储层。由归属字段唯一决定，不依赖调用方解释。 */
    enum class Layer { ASSISTANT, FOLDER, CONVERSATION }

    /** 一条记忆的存储层（归属字段互斥：包层 conversationId 必为 null，反之亦然）。 */
    fun layerOf(memory: AssistantMemory): Layer = when {
        memory.conversationId != null -> Layer.CONVERSATION
        memory.folderId != null -> Layer.FOLDER
        else -> Layer.ASSISTANT
    }

    /** 是否做梦产物（来源维度，与存储层正交）。 */
    fun isDream(memory: AssistantMemory): Boolean = memory.source == "dream"

    /**
     * 注入可见性：该上下文（助手 + 可选项目包 + 可选对话）模型能看到哪些层。
     *
     * [mountLevel] 只影响"挂载范围"（1=仅助手级 / 2=项目包内=默认 / 3=仅当前对话），
     * 这是**用户设置**，与"存储层"无关 —— 两者混淆正是历史 bug 的来源。
     */
    fun visibleForInjection(
        memory: AssistantMemory,
        folderId: String?,
        conversationId: String?,
        /** 同包内其它对话的 id（"项目包内互通"语义，v5.0.67 定案） */
        siblingConversationIds: Set<String> = emptySet(),
        mountLevel: Int,
    ): Boolean {
        val layer = layerOf(memory)
        // ① 挂载范围（用户设置）先收窄
        when (mountLevel) {
            1 -> if (layer != Layer.ASSISTANT) return false
            3 -> if (layer == Layer.FOLDER) return false
                else if (layer == Layer.CONVERSATION && memory.conversationId != conversationId) return false
            else -> Unit // 2 = 项目包内（默认，不额外收窄）
        }
        // ② 归属上下文（存储层自身的一致性）
        return when (layer) {
            Layer.ASSISTANT -> true
            Layer.FOLDER -> folderId != null && memory.folderId == folderId
            Layer.CONVERSATION -> when {
                conversationId == null -> false
                memory.conversationId == conversationId -> true
                // 项目包内互通：同包其它对话的记忆同样可见（这是"包"的意义）
                else -> memory.conversationId in siblingConversationIds
            }
        }
    }

    /**
     * 管理页可见性：**永远返回该助手全部记忆**（不按 mountLevel 收窄）。
     *
     * 管理页是"我改得了"的地方；一旦按注入层级过滤，用户就既看不到也改不了
     * 自己挂到项目包里的记忆（v5.1.13 修过一次，这里写成唯一实现防再犯）。
     */
    fun visibleForManagement(memory: AssistantMemory): Boolean = true

    /**
     * 管理页按档位筛选（三档真正分流，互不重叠）：
     * ASSISTANT   = 助手级 + 全部项目包层（保证任何一条都能被管理到）
     * FOLDER      = 只显示项目包层
     * CONVERSATION = 只显示对话层（由下方按对话分组渲染）
     */
    enum class ManagementTab { ASSISTANT, FOLDER, CONVERSATION }

    fun matchesTab(memory: AssistantMemory, tab: ManagementTab): Boolean = when (tab) {
        ManagementTab.ASSISTANT -> layerOf(memory) != Layer.CONVERSATION
        ManagementTab.FOLDER -> layerOf(memory) == Layer.FOLDER
        ManagementTab.CONVERSATION -> false
    }

    /** 来源档位（主动写入 / 做梦整理）。 */
    fun matchesSource(memory: AssistantMemory, dream: Boolean): Boolean = isDream(memory) == dream
}
