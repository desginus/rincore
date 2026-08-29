/**
 * 生成编排器（AI 传输链核心）— 模块: A. 传输链
 *
 * 职责:
 *  - 消息组装: system(缓存锚点+提示+层1概览+框架工具+记忆) + 历史 + 新消息
 *  - 分层路由: frameworkTools / domainTools 分离 + 域懒加载 (MCP 走 allDomainTools 池)
 *  - 协议强制: 发送前 MessageProtocol.enforce (首条 system + tool 配对)
 *  - 流式输出: GenerationChunk 回调 → 上层落盘
 *
 * 基线: 回滚自 3.2.2 (v3.5.0), 保留 v3.5.3 MCP 懒加载 (813af56d 移植)
 * 来源: 继承原版 + 自研演进
 *
 * 问题定位: 连接中断/SETTINGS/工具调用异常/冷启动 token 高 → 查本模块 + protocol + transformers
 */
package me.rerere.rikkahub.data.ai


/* ───【原版对齐】GenerationHandler ─────────────────────────────────────
 * 原版: 有同文件 (545 行) | RinCore 差异 +462 行 (893 行)
 * 来源: 原版移植 + 自研核心扩展
 * 功能: 生成编排 — system 构建/工具分层注入/流式循环/重试
 * 特点: 1. 分层动态注入 (框架工具 + 已加载域, 冷启动小 — 用户铁律);
 *        2. 消息原样发送零改动 (v3.6.74 降维方向废弃);
 *        3. 豁免工具机制 (v3.6.90 移出域管理 = 框架工具同级);
 *        4. UI 节流 100ms; 5. 断流自动重试 5 次
 * 逻辑: FRAMEWORK_TOOL_SET + exemptFromDomainTools 始终注入;
 *       loadedDomains LinkedHashSet 保序 (缓存前缀稳定)
 * 与原版主要差异:
 *   1. 原版每 chunk 直发无节流 — RinCore 100ms 批处理
 *   2. 原版无分层 — RinCore 域分层是核心自研
 *   3. 原版无豁免工具/插件工具注入
 * ────────────────────────────────────────────────────────────────────*/

import android.content.Context
import me.rerere.rikkahub.BuildConfig
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.ai.protocol.MessageProtocol
import me.rerere.ai.util.TraceLogger
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.ecosystem.tools.DynamicTools
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.FRAMEWORK_TOOL_SET
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.tools.routing.ToolRouter
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.CallTracer

private const val TAG = "GenerationHandler"

/** 框架层工具名 — 不参与域分类, 分层模式下直接注入 */
// FRAMEWORK_TOOL_SET 共享于 ToolsBuilder (v3.5.52 对齐稳定版口径)
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
    data class LoadedDomains(
        val domains: List<String> // v3.6.10: 保序 (加载顺序 — tools 前缀稳定)
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val settingsStore: SettingsStore,
    private val skillManager: me.rerere.rikkahub.data.files.SkillManager? = null,
) {
    /** 断流重试计数 (v3.5.46): 类成员 — 切后台/NAT/平台断流自动恢复, 每次生成最多 5 次 (v3.5.59) */
    private var streamRetryCount = 0

    /** v3.11.16: 网关连接超时独立计数 — header 阶段判死每次 15s, 与快失败型
     *  (毫秒级判死) 恢复机制互斥, 必须分道计数, 否则 30s/次的败因塞进 10s
     *  快速预算在第二次尝试后必然爆掉 (v3.11.14 实证: "重试 1 次后仍失败") */
    private var headerRetryCount = 0
    companion object {
        /** 工具执行超时 (ms): 工具挂起时返回超时错误, 不阻塞整个生成流程 */
        private const val TOOL_EXECUTION_TIMEOUT_MS = 60_000L

        /** v3.11.6 断流重试时间预算 (ms): 15 次重试总时长 10s 内完成
         *  (用户要求: 15 次共 10s, 前 5s 至少 7 次)。
         *  平滑指数递增 (100ms 起 x1.22); 超预算保留已输出内容+
         *  明确失败, 杜绝无限静默。 */
        private const val STREAM_RETRY_BUDGET_MS = 10_000L
    }

    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        conversationLoadedDomains: List<String>? = null, // v3.6.10: 保序 (Set 曾致跨轮顺序不定),
        toolPoolProvider: (() -> List<Tool>)? = null, // v3.6.118: invoke_tools 实时域列表
    ): Flow<GenerationChunk> = flow {
        // Trace ID 每次生成唯一 — 之前用 model.id 导致所有 trace 同 ID (日志无法区分)
        CallTracer.startTrace(id = java.util.UUID.randomUUID().toString().take(8))
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        // === 分层路由状态 ===
        // 注: 消息发送前统一组装, 各步共享同一构建路径 —
        // 覆盖所有会发送向 API 的消息 (含 step 循环内累积的新工具输出)
        val useLayered = assistant.useLayeredTools && tools.isNotEmpty()
        // 从 Conversation 恢复已加载的域（Feature #4: 跨对话持久化）
        // v3.6.10: LinkedHashSet 保序去重 — 加载顺序 = tools 数组顺序 (前缀稳定)
        val loadedDomains = java.util.LinkedHashSet<String>().apply {
            conversationLoadedDomains?.let { addAll(it) }
        }

        // 分离框架工具与用户域工具 (v3.6.90: 含用户移出域管理的豁免工具)
        val exemptSet = settings.exemptFromDomainTools
        val domainTools = tools.filter { it.name !in FRAMEWORK_TOOL_SET && it.name !in exemptSet }
        val frameworkTools = tools.filter { it.name in FRAMEWORK_TOOL_SET || it.name in exemptSet }
        Log.i(TAG, "frameworkToolSet(${FRAMEWORK_TOOL_SET.size}+${exemptSet.size}): ${(FRAMEWORK_TOOL_SET + exemptSet).sorted()}")
        Log.i(TAG, "frameworkTools found: ${frameworkTools.map { it.name }.sorted()}")

        // Skill 已拆分为独立工具 (skill_<name>)，无需集中提取 skillListText

        // G3 平台空流重试计数 (每次生成仅重试一次)
        var emptyRetryCount = 0
        // 断流重试计数 (切后台/NAT/平台断连 — IOException 自动恢复, 每次生成最多 5 次)
        // 类成员 (局部声明曾被编译器解析为块外不可见 — 提升为成员彻底规避)
        streamRetryCount = 0

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")
            CallTracer.event("STEP", "step_$stepIndex", "Step $stepIndex begin, ${tools.size} tools loaded, messages=${messages.size}")

            // Bug #2 修复: 每步重建 ToolRouter，读取最新 settings
            val currentSettings = settingsStore.settingsFlow.value
            val toolRouter = ToolRouter(
                overrides = currentSettings.toolDomainOverrides,
                customDescriptions = currentSettings.customDomainDescriptions,
                customDomains = currentSettings.customDomains,
                customKeywords = currentSettings.customDomainKeywords,
                domainNameOverrides = currentSettings.domainNameOverrides,
                hiddenDomains = currentSettings.hiddenDomains,
                removedBuiltinDomains = currentSettings.removedBuiltinDomains,
                exemptFromDomainTools = currentSettings.exemptFromDomainTools,
            )
            // 每步刷新 MCP 工具 (支持 mcp_connect 运行时添加) — 合并到域池走懒加载,
            // 不直接注入函数定义 (813af56d 移植: Token 65K → ~6K)
            val currentMcpTools = DynamicTools.getMcpTools()
            // v3.5.56 回归全量: 含框架工具 — 系统域/workspace 等必须可见可下钻
            // (v3.5.52 过滤框架 → 触发词指向的系统域展开为空 + workspace 5 工具
            // 失踪 — 用户实测 Bug; 全量池不影响 layer1 静态性, 缓存前缀稳定)
            val allDomainTools = (domainTools + frameworkTools + currentMcpTools).distinctBy { it.name }

            val layer1Prompt = if (useLayered) {
                toolRouter.buildLayer1(allDomainTools)
            } else {
                null
            }

            // v3.8.27: 顶层白名单 — 已加载域工具的合法名集合 (域内工具),
            // 顶层 tools 只放行: 批准框架 + 豁免 + 引擎工具 + 本集合成员
            // 技能/插件等任何工具未经 invoke_tools 加载绝不暴露在请求顶层
            val loadedDomainToolNames: Set<String> = if (useLayered) {
                loadedDomains.flatMap { toolRouter.getDomainTools(it, allDomainTools) }
                    .map { it.name }.toSet()
            } else emptySet()

            val toolsInternal = if (useLayered) {
                buildList {
                    Log.i(TAG, "generateInternal: build tools (layered)($assistant)")
                    // 框架工具 — 始终可调用, 不走域系统
                    if (assistant.enableMemory == true) {
                        val memoryAssistantId = if (assistant.useGlobalMemory) {
                            MemoryRepository.GLOBAL_MEMORY_ID
                        } else {
                            assistant.id.toString()
                        }
                        buildMemoryTools(
                            json = json,
                            onCreation = { content ->
                                memoryRepo.addMemory(memoryAssistantId, content)
                            },
                            onUpdate = { id, content ->
                                memoryRepo.updateContent(id, content)
                            },
                            onDelete = { id ->
                                memoryRepo.deleteMemory(id)
                            }
                        ).let(this::addAll)
                    }
                    // 其他框架工具 (search/conversation/workspace) — 始终注入
                    addAll(frameworkTools.filter { it.name != "memory_tool" })
                    // invoke_tools 元工具 — 操作 allDomainTools (含MCP), 模型按需加载
                    add(toolRouter.createInvokeToolsTool(allDomainTools, loadedDomains, toolPoolProvider))
                    // 已加载域的工具 (含MCP工具, 通过分类归入域) — 分层注入是底层逻辑,
                    // 请求体只带框架工具 + 已加载域 (冷启动小, v3.5.1 瘦身成果)。
                    // 工具总数由 layer1 数量统计告知模型 (配置决定, 静态)。
                    // v3.6.10: 保持加载顺序 (LinkedHashSet) — 新域追加尾部,
                    // 已加载前缀不变 → 缓存前缀稳定 (v3.5.58 sorted 曾致加载新域
                    // 后全量重排, 前缀断裂, 上百K只缓存十几K)
                    for (domain in loadedDomains) {
                        addAll(toolRouter.getDomainTools(domain, allDomainTools))
                    }
                    // skill 工具不分层直注 — 全量加载禁止 (用户铁律)。
                    // skill_<name> 工具经 invoke_tools("技能") 加载后直接可用 (D8),
                    // 加载一次对话内保持, 无需 use_skill 两步。
                }.distinctBy { it.name }
                    // v3.8.27: 顶层白名单硬过滤 — 除批准框架 + 豁免 + 引擎工具
                    // (memory_tool/invoke_tools) + 已加载域工具外, 任何工具
                    // (如泄漏的 skill__/plugin__) 一律剔除并记错, 绝不暴露
                    // 在请求 tools 顶层 (用户: 一律强制归入 invoke_tools 内部)
                    .also { built ->
                        val approved = FRAMEWORK_TOOL_SET + exemptSet +
                            setOf("memory_tool", "invoke_tools")
                        val leaked = built.filter { it.name !in approved && it.name !in loadedDomainToolNames }
                        if (leaked.isNotEmpty()) {
                            Log.e(TAG, "顶层泄漏工具被剔除: ${leaked.map { it.name }.sorted()}")
                        }
                    }
                    .filter {
                        it.name in FRAMEWORK_TOOL_SET || it.name in exemptSet ||
                            it.name == "memory_tool" || it.name == "invoke_tools" ||
                            it.name in loadedDomainToolNames
                    }
                    // v3.6.10: 不再整体重排 — 构建顺序 = 框架(固定) + invoke_tools +
                    // 已加载域(加载顺序, 域内名字序) — 新域追加尾部前缀稳定 (缓存命中)
                    .also { built ->
                        val mcpCount = built.count { it.name.startsWith("mcp__") }
                        val frameworkCount = built.count { it.name in FRAMEWORK_TOOL_SET }
                        Log.i(TAG, "toolsInternal (layered): ${built.size} total" +
                            " (mcp=$mcpCount framework=$frameworkCount domain=${built.size - mcpCount - frameworkCount})")
                    }
            } else {
                buildList {
                    Log.i(TAG, "generateInternal: build tools($assistant)")
                    if (assistant.enableMemory == true) {
                        val memoryAssistantId = if (assistant.useGlobalMemory) {
                            MemoryRepository.GLOBAL_MEMORY_ID
                        } else {
                            assistant.id.toString()
                        }
                        buildMemoryTools(
                            json = json,
                            onCreation = { content ->
                                memoryRepo.addMemory(memoryAssistantId, content)
                            },
                            onUpdate = { id, content ->
                                memoryRepo.updateContent(id, content)
                            },
                            onDelete = { id ->
                                memoryRepo.deleteMemory(id)
                            }
                        ).let(this::addAll)
                    }
                    addAll(tools)
                    addAll(currentMcpTools)
                }.distinctBy { it.name }.sortedBy { it.name }.also { built ->
                    val mcpCount = built.count { it.name.startsWith("mcp__") }
                    Log.i(TAG, "toolsInternal (full): ${built.size} total (mcp=$mcpCount)")
                }
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                CallTracer.event("SEND", "pre_api", "Calling generateInternal: model=${model.id}, provider=${provider.javaClass.simpleName}")
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                    layer1Prompt = layer1Prompt,
                    // v3.6.85: conversationId 已随 x-opencode-session 头一并移除 (v3.6.80)
                )
                CallTracer.event(
                    "RECV", "post_api",
                    "generateInternal returned, messages=${messages.size}",
                    metrics = sseDiagMetrics()
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                // G3 平台空流重试: 流式正常结束但模型未产出任何内容
                // (无文本/无思考/无工具调用) — 平台偶发空流, 重试一次
                val lastMsg = messages.lastOrNull()
                val emptyResponse = lastMsg != null && lastMsg.role == MessageRole.ASSISTANT &&
                    lastMsg.parts.none {
                        it is UIMessagePart.Text || it is UIMessagePart.Reasoning || it is UIMessagePart.Tool
                    }
                if (emptyResponse && emptyRetryCount < 1) {
                    emptyRetryCount++
                    CallTracer.event("RETRY", "empty_stream", "Empty assistant response, retrying once (step=$stepIndex)")
                    messages = messages.dropLast(1)
                    continue
                }

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break — emit loadedDomains for persistence
                    if (useLayered && loadedDomains.isNotEmpty()) {
                        emit(GenerationChunk.LoadedDomains(loadedDomains.toList()))
                    }
                    CallTracer.event("FINISH", "no_tools", "Conversation finished, no pending tools")
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    CallTracer.event("PAUSE", "tool_approval", "Waiting for user approval on ${pendingTools.size} tools")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            // v3.11.17: 工具连续相同失败聚合 — (工具名+参数指纹) 维度计数。
            // 实证 (bug 报告): 模型锁死在错误工具名 26 次重复空参调用, 每次都
            // 只回一行 "Missing: name" 等价于一次失败重复 26 遍, 无任何聚合
            // 反馈 — 低强度错误信号下模型永远走不出惯性通路。阈值 ≥3 时在
            // 工具结果前置升级警告, 强制打破循环 (作用域: 全部工具轮次共享)
            val toolFailureCounts = HashMap<String, Int>()
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        TraceLogger.log("ToolExec", "${tool.toolName}")
                            runCatching {
                            val toolDef = tools.find { toolDef -> toolDef.name == tool.toolName }
                                ?: toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                            if (toolDef == null) {
                                error("工具 ${tool.toolName} 未找到")
                            }
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                            }
                            // v3.11.18: 熔断物理闸门 — 同键 (工具名+参数指纹) 连续失败
                            // ≥6 次后拒绝下发执行, 直接返回硬阻断信号 (不执行)。
                            // 提示型纠错 (v3.11.17) 对已锁死的生成通路是低效的 —
                            // 错误循环必须物理断开; 前序轮次: 1-2 正常执行反馈,
                            // 3-5 警告执行, 6+ 拦截。
                            val gateKey = tool.toolName + "|" + tool.input.hashCode()
                            if ((toolFailureCounts[gateKey] ?: 0) >= 6) {
                                Log.w(TAG, "generateText: tool gated off (fused): ${tool.toolName}")
                                CallTracer.event("TOOL", "fused_gate",
                                    "tool=${tool.toolName} blocked, same-args failures=${toolFailureCounts[gateKey]}",
                                    metrics = sseDiagMetrics())
                                executedTools += tool.copy(
                                    output = listOf(UIMessagePart.Text(
                                        "⛔ 该调用已被客户端熔断拦截, 本次未执行。" +
                                        "工具 ${tool.toolName} 已连续以完全相同的参数失败至少 5 次, 客户端已禁止继续执行该参数组合。" +
                                        "更换参数或工具后才可能通过 (若重新调用会立刻被再次拦截)。" +
                                        "请回到意图层重新决策: 核对工具名 → 补齐参数 → 或换用其他工具 (invoke_tools) → 或以文字说明放弃该路径。")))
                                return@runCatching
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            CallTracer.event("TOOL", "exec_${toolDef.name}", "Executing ${toolDef.name}, args=${tool.input.length}c")
                            // 工具执行超时兜底: 工具挂起(网络/IO)时不永久卡住,
                            // 超时返回错误结果让模型继续 (修复: ChatCompletions 工具调用后一直加载)
                            val result = withTimeout(TOOL_EXECUTION_TIMEOUT_MS) {
                                toolDef.execute(args)
                            }
                            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                            val truncated = maybeTruncateToolOutput(tool.toolCallId, result, hasShellAccess, tool.toolName)
                            // v3.11.17: 连续相同失败熔断 — 判据: 结果文本以失败形态开头
                            // (Missing:/Error/Invalid/MCP manager not initialized/未找到/超时)。
                            // key = 工具名 + 参数指纹; 连续 ≥3 次相同失败后在回传文本
                            // 前插入升级警告: 告知重复无用 + 给出三条修正路径。
                            val finalOutput: List<UIMessagePart> = run {
                                val failText = truncated.filterIsInstance<UIMessagePart.Text>()
                                    .joinToString(" ") { it.text }.trim()
                                val isFailure = failText.startsWith("Missing:") ||
                                    failText.startsWith("Error") ||
                                    failText.startsWith("Invalid") ||
                                    failText.startsWith("MCP manager not initialized") ||
                                    failText.contains("工具 ${'$'}{tool.toolName} 未找到") ||
                                    failText.startsWith("Tool execution timed out")
                                if (isFailure) {
                                    val key = tool.toolName + "|" + tool.input.hashCode()
                                    val n = (toolFailureCounts[key] ?: 0) + 1
                                    toolFailureCounts[key] = n
                                    if (n >= 3) {
                                        CallTracer.event("TOOL", "failure_loop_break",
                                            "same-failure x$n: ${'$'}{tool.toolName}", metrics = sseDiagMetrics())
                                        return@run listOf(UIMessagePart.Text(
                                            "⚠️ 这是第 ${'$'}n 次以完全相同的参数调用 ${'$'}{tool.toolName} 并得到相同错误 (错误: ${'$'}{failText.take(120)})。" +
                                            "以相同方式重复该调用不会产生不同结果, 请立即停止重复。可选路径: " +
                                            "1) 重新确认你实际意图的工具名 (检查工具列表, 是否写错或选了错误工具); " +
                                            "2) 若是参数问题, 先补齐必填参数再调用; " +
                                            "3) 若该工具确实不可用, 换用其他工具 (可用 invoke_tools 查看可用工具) 或放弃该路径, 以文字向用户说明情况。" +
                                            "禁止再发出与本次相同的调用。")) + truncated
                                    }
                                } else {
                                    toolFailureCounts.remove(tool.toolName + "|" + tool.input.hashCode())
                                }
                                truncated
                            }
                            val outChars = result.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length }
                            CallTracer.event("TOOL", "result_${toolDef.name}",
                                "Exe输出: ${result.size} parts, ${outChars}c",
                                mapOf("tool" to toolDef.name, "parts" to "${result.size}"))
                            executedTools += tool.copy(
                                output = finalOutput
                            )
                        }.onFailure {
                            // 工具执行超时: 写回超时错误, 让模型继续 (不传播为取消)
                            if (it is TimeoutCancellationException) {
                                Log.w(TAG, "generateText: tool ${tool.toolName} timed out after ${TOOL_EXECUTION_TIMEOUT_MS}ms")
                                CallTracer.event("TOOL", "timeout_${tool.toolName}", "Tool execution timed out")
                                executedTools += tool.copy(
                                    output = listOf(
                                        UIMessagePart.Text(
                                            json.encodeToString(
                                                buildJsonObject {
                                                    put(
                                                        "error",
                                                        JsonPrimitive("工具执行超时(${TOOL_EXECUTION_TIMEOUT_MS / 1000}s): ${tool.toolName}")
                                                    )
                                                }
                                            )
                                        )
                                    )
                                )
                                return@onFailure
                            }
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            it.printStackTrace()
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(buildString {
                                                        append("[${it.javaClass.name}] ${it.message}")
                                                        append("\n${it.stackTraceToString()}")
                                                    })
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            // v3.6.74: 上下文降维方向废弃 — 工具输出原样保留, 不做压缩
            val compressedTools = executedTools

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    compressedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }
        // v3.11.6: 生成结束兜底清除重试提示 — 取消/异常路径不经过
        // 成功/预算耗尽分支, 提示会残留 (用户: 恢复后提示必须消失)
        processingStatus.value = null
        CallTracer.finishTrace()

    }.flowOn(Dispatchers.IO)

    // v3.8.6: 抓取 SSE 诊断现场 (TraceLogger 中 tag=SSE 的最近条目),
    // 挂到 CallTracer 事件的 metrics, 运行日志页直接可见
    private fun sseDiagMetrics(): Map<String, String> {
        val lines = me.rerere.ai.util.TraceLogger.takeTagged("SSE", 12)
        return if (lines.isEmpty()) emptyMap() else mapOf("sse_diag" to lines.joinToString(" | "))
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        layer1Prompt: String? = null,
    ) {
        // v3.6.74: 节选最近对话 (原上下文降维) 方向废弃 — 消息一律原样发送, 零改动
        val effectiveMessages: List<UIMessage> = messages
        var internalMessages = buildList {
            val sysPromptLen: Int
            val memPromptLen: Int
            val toolsPromptLen: Int
            var layer1Len: Int = 0

            // 原版 SystemPromptBuilder (stable/volatile 分区) 移植:
            // stable = 缓存锚点 + 系统提示 + Layer1 + 工具提示 (跨请求字节一致 → 缓存前缀)
            // volatile = 记忆 (易变 → 放前缀之后, 不破坏缓存)
            // 原版注释: "Volatile text in the prefix busts the cache every turn"
            val effectiveSystemPrompt =
                if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                    conversationSystemPrompt
                } else {
                    assistant.systemPrompt
                }
            // stable 部分: 缓存锚点 (静态规则块 ~870c) + 系统提示 + Layer1 域概览
            val stablePrompt = buildString {
                // 缓存锚点 — 静态规则块 + 当前模型名 (v3.6.6: 模型配置是缓存键一部分,
                // 模型切换 → Prompt 同步变化 → 缓存按模型隔离, 同模型前缀稳定)
                append(buildCacheAnchor(model.displayName))
                if (effectiveSystemPrompt.isNotBlank()) {
                    appendLine()
                    append(effectiveSystemPrompt)
                }
                // Layer1 域概览 — 静态 (域配置变化才更新)
                if (layer1Prompt != null) {
                    appendLine()
                    append(layer1Prompt)
                }
            }
            sysPromptLen = stablePrompt.length
            layer1Len = layer1Prompt?.length ?: 0

            // 框架工具 systemPrompt (瘦身 — v2.9.4/v3.5.1: 其余工具描述在请求 tools 数组,
            // 全量注入会导致工具池膨胀时冷启动 system 70K+ tokens)
            val toolPrompts = tools
                .filter { it.name in FRAMEWORK_TOOL_SET && it.name != "invoke_tools" }
                .map { tool -> tool.systemPrompt(model, effectiveMessages) }
                .filter { it.isNotBlank() }
            toolsPromptLen = toolPrompts.sumOf { it.length }

            // volatile 部分: 记忆 (易变, 放缓存前缀之后)
            val memoryPrompt = if (assistant.enableMemory) buildMemoryPrompt(memories = memories) else ""
            memPromptLen = memoryPrompt.length

            // v3.6.105: 强制启动技能 — 对话开始时把技能正文注入 system 尾部
            // (模型每轮必读; 缓存前缀不含尾部追加, 不影响命中)
            val forcedSkillAddendum = buildString {
                for (skillName in assistant.forcedSkills) {
                    val body = runCatching {
                        skillManager?.readSkillBody(skillName)
                    }.getOrNull()?.trim()
                    if (!body.isNullOrBlank()) {
                        if (isNotEmpty()) appendLine()
                        appendLine("## Forced Skill: $skillName (对话开始时强制启动, 每轮必须遵守)")
                        appendLine(body)
                    }
                }
            }.ifBlank { null }

            val (stableSystem, volatileSystem) = SystemPromptBuilder().buildSections(
                assistantPrompt = stablePrompt,
                memoryPrompt = memoryPrompt,
                recentChatsPrompt = "",   // 本项目未启用 recent chats 参考
                toolPrompts = toolPrompts,
                systemAddendum = forcedSkillAddendum,
            )
            // 记忆位置策略: 记忆放 stable 之后 (历史之前) — 记忆是稳定前缀一部分, 可命中
            val cacheFpSystem = listOf(stableSystem, volatileSystem).filter { it.isNotBlank() }.joinToString("\n")
            // v3.5.58 缓存核验: 请求体前缀指纹 (stable system+tools 序列化稳定)
            try {
                val fp = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((cacheFpSystem + tools.joinToString { it.name }).toByteArray())
                    .take(8).joinToString("") { "%02x".format(it) }
                Log.i(TAG, "cache-fp: $fp (system=${cacheFpSystem.length}c tools=${tools.size})")
            } catch (_: Exception) {}
            // stable+记忆 → 历史/当前轮 (记忆在稳定前缀内, 可命中)
            val fullSystem = listOf(stableSystem, volatileSystem).filter { it.isNotBlank() }.joinToString("\n")
            if (fullSystem.isNotBlank()) {
                val estTokens = fullSystem.length / 2.5
                Log.i(TAG, "System prompt breakdown: system=${sysPromptLen}c (~${(sysPromptLen/2.5).toInt()}t)" +
                    " layer1=${layer1Len}c (~${(layer1Len/2.5).toInt()}t)" +
                    " tools=${toolsPromptLen}c (~${(toolsPromptLen/2.5).toInt()}t)" +
                    " memory=${memPromptLen}c (~${(memPromptLen/2.5).toInt()}t)" +
                    " total=${fullSystem.length}c (~${estTokens.toInt()}t)")
                add(UIMessage.system(prompt = fullSystem))
            }
            addAll(effectiveMessages)
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        val totalChars = internalMessages.sumOf { msg ->
            msg.parts.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length }
        }
        val estTotalTokens = totalChars / 2.5
        Log.i(TAG, "Request total: ${internalMessages.size} messages, ${totalChars}c (~${estTotalTokens.toInt()}t)")

        // 协议层: 发送前结构性保证 (首条 system + tool 配对) — 幂等, 合规消息零修改
        val protocolMessages = MessageProtocol.enforce(internalMessages)
        if (protocolMessages != internalMessages) {
            Log.i(TAG, "MessageProtocol: 消息序列已修复 (${internalMessages.size} → ${protocolMessages.size})")
        }
        internalMessages = protocolMessages

        // v3.6.34: 流式基准 = 原始消息 (关键) — 压缩包只进请求 (internalMessages),
        // 流式累积/onUpdateMessages 回写必须用原始消息, 否则 UI 消息被替换成压缩包
        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
                // v3.6.80: 删除自研 x-opencode-session 头 — 原版无此头且 grok 正常,
                // 该头疑似触发 OpenCode grok 通道 400
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            // 断流自动恢复 (v3.5.46 根治): 输出中连接中断 (切后台网络切换/
            // NAT 超时/平台断流 — IOException) → 回滚本次已输出内容 → 自动重试。
            // 用户核心诉求: 一直保持连接, 不自己中断。重试请求消息相同 → 缓存命中。
            streamLoop@ while (true) {
            // v3.11.10: 重试预算在首次断流时刻重置起算 — 旧实现从流启动计时,
            // 含"流启动→断流"的静默期; 平台首包就静默时 watchdog 60s 单次
            // 已耗尽全部预算, 进入 catch 时连第一次重试都不满足条件,
            // 用户感知"一直没反应最后报错"且重试机制完全失效
            var retryBudgetStartMs = System.currentTimeMillis()
            val preStreamMessages = messages
            // v3.6.49: UI 更新节流 — 每 chunk 调 onUpdateMessages 触发整个 ChatPage
            // 重组, 流式期间高频重组是卡顿/发热/120Hz 掉帧根因。
            // v3.8.6: 50ms→5ms 用户实测 — 顿挫感反而极严重: 5ms 下每个 chunk
            // 都触发重组, UI 线程被重组任务淹没, 渲染帧率被拖低形成掉帧式
            // 顿挫 (与预热期 60Hz vsync 不匹配)。v3.8.7 回 50ms: OpenCode 大块
            // chunk 间隔本就大于 50ms, 节流不产生额外延迟, 密集时批处理保帧率。
            var lastUiUpdateMs = 0L
            try {
            // v3.11.4: 工具轮次诊断 — 运行日志页 SSE 现场可区分首轮/工具轮请求
            val toolRound = internalMessages.flatMap { m -> m.parts }
                .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Tool>()
                .count { it.output.isNotEmpty() }
            me.rerere.ai.util.TraceLogger.log(
                "SSE", "round: toolResultCount=$toolRound messages=${internalMessages.size}"
            )
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params
            ).collect {
                messages = messages.handleMessageChunk(chunk = it, model = model)
                it.usage?.let { usage ->
                    // 缓存诊断 (G4): 每次 usage 回传记录 prompt/cached 构成
                    if (usage.promptTokens > 0) {
                        val cacheHitRate = if (usage.promptTokens > 0) {
                            usage.cachedTokens * 100 / usage.promptTokens
                        } else 0
                        Log.i(
                            TAG,
                            "cache: prompt=${usage.promptTokens} cached=${usage.cachedTokens}" +
                                " hit=$cacheHitRate%"
                        )
                    }
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(usage = message.usage.merge(usage))
                        } else {
                            message
                        }
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastUiUpdateMs >= 50) {
                    onUpdateMessages(messages)
                    lastUiUpdateMs = now
                }
            }
            // 流式成功完成 — 补一次 UI 更新, 确保节流期间的最后内容完整显示
            processingStatus.value = null  // v3.11.4: 重试提示清除
            onUpdateMessages(messages)
            break@streamLoop  // 流式成功完成, 退出重试循环
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // 用户主动停止 — 不重试
            } catch (e: me.rerere.ai.provider.providers.openai.OpenCodeStreamUnconfirmedException) {
                // v3.8.32: OpenCode Zen 无完成信号关流 (ox 系等) — 服务端已完成或
                // 中途掐断在信号层面无法区分。保留已生成内容 (已随 chunk 流入
                // messages), 不回滚不重试, 交上层明确报错 — 杜绝静默截断。
                Log.w(TAG, "stream unconfirmed (${e.message}) — keep partial content, no retry")
                onUpdateMessages(messages)
                throw e
            } catch (e: java.io.IOException) {
                // 断流 (切后台/网络切换/NAT/平台): 回滚半截输出 → 自动重试
                // v3.11.12: 失败类型分级 — 这两类失败的恢复机制完全不同:
                //   A. 静默超时型 (watchdog "生成无有效数据超时"): 平台一个
                //      字节都不给, 每次重试自身又要等满 watchdog (60s+) —
                //      塞进 10s 快速预算必然互相矛盾 (v3.11.10 报错"重试 1 次
                //      耗时 60s 超预算"的根因)。单独策略: 仅重试 1 次 (网关
                //      瞬时挂起可能恢复), 再静默立即明确报错。总时长 2×watchdog
                //      封顶, 不产生"无限升级的等待"。
                //   B. 瞬时断流型 (connection reset/EOF/网络切换): 失败在毫秒
                //      级发生, 快速重试窗口 (<10s) 完整适用 15 次。
                //   C. 网关连接超时型 (watchdog header 阶段 "平台连接无响应"):
                //      每次判死耗 15s, 但失败本身典型可重试 (网关冷启动/瞬时挂起,
                //      新建连接往往立刻可用) — 用户定版: 15 次密集重试, 固定间隔,
                //      不受 10s 快速预算约束 (否则第二次尝试后预算必爆, 退化成
                //      只试 1 次)。上限 15 次 ≈ 4min 内完全穷尽。
                val isHeaderTimeout = e.message?.contains("平台连接无响应") == true
                if (isHeaderTimeout) {
                    if (headerRetryCount < 15) {
                        headerRetryCount++
                        // v3.11.17: 重试静默化 (用户定版) — 不再挂"正在重试" UI 提示,
                        // 失败回滚对用户零感知; 进度只进日志与 Trace
                        kotlinx.coroutines.delay(800)
                        Log.w(TAG, "header timeout — gateway retry $headerRetryCount/15: ${e.message}")
                        CallTracer.event("RETRY", "header_retry", "gateway no response, retry $headerRetryCount/15", metrics = sseDiagMetrics())
                        messages = preStreamMessages
                        onUpdateMessages(messages)
                        continue@streamLoop
                    }
                    processingStatus.value = null
                    onUpdateMessages(messages)
                    Log.e(TAG, "header timeout exhausted: $headerRetryCount retries")
                    throw java.io.IOException(
                        "[v${BuildConfig.VERSION_NAME}] 网关连续 $headerRetryCount 次连接无响应 (每次 15s 静默): 网关持续不可达，已保留已生成内容", e
                    )
                }
                val isWatchdogTimeout = e.message?.contains("生成无有效数据超时") == true
                if (isWatchdogTimeout) {
                    if (streamRetryCount == 0) {
                        streamRetryCount = 1
                        // v3.11.17: 静默恢复 (同上, 无 UI 提示)
                        kotlinx.coroutines.delay(300)
                        Log.w(TAG, "watchdog timeout — single recovery retry: ${e.message}")
                        CallTracer.event("RETRY", "watchdog_single_retry", e.message ?: "watchdog", metrics = sseDiagMetrics())
                        messages = preStreamMessages
                        onUpdateMessages(messages)
                        continue@streamLoop
                    }
                    // 第二次仍静默 — 明确报错 (不再进入任何重试循环)
                    processingStatus.value = null
                    onUpdateMessages(messages)
                    Log.e(TAG, "watchdog timeout twice: ${e.message}")
                    throw java.io.IOException(
                        "[v${BuildConfig.VERSION_NAME}] 平台两次等待均无响应 (${e.message ?: "watchdog 超时"}): 平台未返回有效数据，已保留已生成内容", e
                    )
                }
                // v3.11.10: 首次断流时重置预算起点 + 清零本次计数状态
                // (streamRetryCount==0 说明这轮失败发生在任何成功数据之前;
                // 后续轮次的失败继续从第一次断流累计, 预算切分正确)
                if (streamRetryCount == 0) {
                    retryBudgetStartMs = System.currentTimeMillis()
                }
                val retryElapsedMs = System.currentTimeMillis() - retryBudgetStartMs
                if (streamRetryCount < 15 && retryElapsedMs < STREAM_RETRY_BUDGET_MS) {
                    streamRetryCount++
                    // v3.11.16: 重试节奏扁平化 — 用户定版"密集重试", 固定 150ms
                    // (旧指数序列尾部拖到 1.6s, 冗余: 快失败重试间隔无意义,
                    // 密集命中恢复窗口才是目的; 15 次共 ≈2.3s)
                    val retryDelayMs = 150L
                    // v3.11.4: 重试期间 UI 状态提示 — 回滚瞬间内容消失,
                    // 无提示时用户感知"卡死"; 提示后用户知道在自动恢复
                    // v3.11.17: 静默重试 (同上, 无 UI 提示)
                    kotlinx.coroutines.delay(retryDelayMs)
                    Log.w(TAG, "stream interrupted (${e.message}), rolling back & retry $streamRetryCount/15 (delay=${retryDelayMs}ms)")
                    CallTracer.event("RETRY", "stream_interrupted", "interrupted: ${e.message}, rollback & retry $streamRetryCount/15", metrics = sseDiagMetrics())
                    messages = preStreamMessages  // 丢弃本次生成的半截内容
                    onUpdateMessages(messages)    // UI 同步回滚
                    continue@streamLoop  // 重试 (maxSteps 内, 消息相同缓存命中)
                }
                // v3.11.4: 重试次数或时间预算耗尽 — 保留已输出内容 + 明确失败
                // (旧行为: 半截输出已随回滚丢弃 → 用户看到"工具后无输出"卡死感;
                // 现在保留模型实际输出过的内容到 UI, 再明确报错)
                processingStatus.value = null
                onUpdateMessages(messages)
                val retryInfo = if (streamRetryCount >= 15) {
                    "重试 ${streamRetryCount} 次已达上限"
                } else {
                    "重试 ${streamRetryCount} 次后仍失败"
                }
                Log.e(TAG, "stream retry exhausted: $retryInfo, last error: ${e.message}")
                throw java.io.IOException(
                    "[v${BuildConfig.VERSION_NAME}] 平台持续无响应 ($retryInfo): ${e.message ?: "连接中断"}，已保留已生成内容", e
                )
            }
            }
        } else {
            val chunk = providerImpl.generateText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            )
            messages = messages.handleMessageChunk(chunk = chunk, model = model)
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(
                            usage = message.usage.merge(usage)
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
    }

    // invoke_tools 输出 exempt from truncation (工具列表必须完整)
    private val EXEMPT_FROM_TRUNCATION = setOf("invoke_tools")

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
        toolName: String = "",
    ): List<UIMessagePart> {
        // 特定工具(如 invoke_tools)不截断, 保证数据完整性
        if (toolName in EXEMPT_FROM_TRUNCATION) return output

        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
