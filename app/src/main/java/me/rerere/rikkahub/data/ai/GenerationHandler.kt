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

import android.content.Context
import android.util.Log
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
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.ai.compression.NaturalLanguageFormatter
import me.rerere.rikkahub.data.ai.protocol.MessageProtocol
import me.rerere.ai.util.TraceLogger
import me.rerere.rikkahub.data.ai.compression.ToolOutputCompressor
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
) {
    /** 断流重试计数 (v3.5.46): 类成员 — 切后台/NAT/平台断流自动恢复, 每次生成最多 5 次 (v3.5.59) */
    private var streamRetryCount = 0
    // v3.6.9: 流式 token 估算计数器 — 类成员 (局部声明曾致 collect lambda 解析失败)
    private var lastStreamTextLen = 0
    private var estimatedCompletionTokens = 0
    companion object {
        /** 工具执行超时 (ms): 工具挂起时返回超时错误, 不阻塞整个生成流程 */
        private const val TOOL_EXECUTION_TIMEOUT_MS = 60_000L
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
        conversationLoadedDomains: Set<String>? = null,
    ): Flow<GenerationChunk> = flow {
        // Trace ID 每次生成唯一 — 之前用 model.id 导致所有 trace 同 ID (日志无法区分)
        CallTracer.startTrace(id = java.util.UUID.randomUUID().toString().take(8))
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        // === 分层路由状态 ===
        val useLayered = assistant.useLayeredTools && tools.isNotEmpty()
        // 从 Conversation 恢复已加载的域（Feature #4: 跨对话持久化）
        // v3.6.10: LinkedHashSet 保序去重 — 加载顺序 = tools 数组顺序 (前缀稳定)
        val loadedDomains = java.util.LinkedHashSet<String>().apply {
            conversationLoadedDomains?.let { addAll(it) }
        }

        // 分离框架工具与用户域工具
        val domainTools = tools.filter { it.name !in FRAMEWORK_TOOL_SET }
        val frameworkTools = tools.filter { it.name in FRAMEWORK_TOOL_SET }
        Log.i(TAG, "frameworkToolSet(${FRAMEWORK_TOOL_SET.size}): ${FRAMEWORK_TOOL_SET.sorted()}")
        Log.i(TAG, "frameworkTools found: ${frameworkTools.map { it.name }.sorted()}")

        // Skill 已拆分为独立工具 (skill_<name>)，无需集中提取 skillListText

        // G3 平台空流重试计数 (每次生成仅重试一次)
        var emptyRetryCount = 0
        // 断流重试计数 (切后台/NAT/平台断连 — IOException 自动恢复, 每次生成最多 5 次)
        // 类成员 (局部声明曾被编译器解析为块外不可见 — 提升为成员彻底规避)
        streamRetryCount = 0
        lastStreamTextLen = 0
        estimatedCompletionTokens = 0

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
                    add(toolRouter.createInvokeToolsTool(allDomainTools, loadedDomains))
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
                )
                CallTracer.event("RECV", "post_api", "generateInternal returned, messages=${messages.size}")
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
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            CallTracer.event("TOOL", "exec_${toolDef.name}", "Executing ${toolDef.name}, args=${tool.input.length}c")
                            // 工具执行超时兜底: 工具挂起(网络/IO)时不永久卡住,
                            // 超时返回错误结果让模型继续 (修复: ChatCompletions 工具调用后一直加载)
                            val result = withTimeout(TOOL_EXECUTION_TIMEOUT_MS) {
                                toolDef.execute(args)
                            }
                            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                            val truncated = maybeTruncateToolOutput(tool.toolCallId, result, hasShellAccess, tool.toolName)
                            val outChars = result.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length }
                            CallTracer.event("TOOL", "result_${toolDef.name}",
                                "Exe输出: ${result.size} parts, ${outChars}c",
                                mapOf("tool" to toolDef.name, "parts" to "${result.size}"))
                            executedTools += tool.copy(
                                output = truncated
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

            // Headroom: compress tool outputs at source (before storing in conversation history)
            CallTracer.event("TOOL", "compress_start", "Compressing ${executedTools.size} executed tool outputs")
            val compressedTools = executedTools.map { tool ->
                if (!ToolOutputCompressor.isSearchTool(tool.toolName)) return@map tool
                if (tool.output.isEmpty()) return@map tool
                val compressedOutput = tool.output.map { part ->
                    if (part is UIMessagePart.Text && part.text.length > 200) {
                        val formatted = NaturalLanguageFormatter.format(part.text)
                        if (formatted.length < part.text.length) {
                            TraceLogger.log("Compress", "${tool.toolName}: ${part.text.length}c -> ${formatted.length}c")
                            Log.i(TAG, "compress: ${tool.toolName} ${part.text.length} -> ${formatted.length}c")
                            UIMessagePart.Text(text = formatted.ifBlank { Log.w(TAG, "compress: empty output for ${tool.toolName}, keeping original"); part.text })
                        } else part
                    } else part
                }
                tool.copy(output = compressedOutput)
            }

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
        CallTracer.finishTrace()

    }.flowOn(Dispatchers.IO)

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
                .map { tool -> tool.systemPrompt(model, messages) }
                .filter { it.isNotBlank() }
            toolsPromptLen = toolPrompts.sumOf { it.length }

            // volatile 部分: 记忆 (易变, 放缓存前缀之后)
            val memoryPrompt = if (assistant.enableMemory) buildMemoryPrompt(memories = memories) else ""
            memPromptLen = memoryPrompt.length

            val (stableSystem, volatileSystem) = SystemPromptBuilder().buildSections(
                assistantPrompt = stablePrompt,
                memoryPrompt = memoryPrompt,
                recentChatsPrompt = "",   // 本项目未启用 recent chats 参考
                toolPrompts = toolPrompts,
                systemAddendum = null,
            )
            val system = listOf(stableSystem, volatileSystem).filter { it.isNotBlank() }.joinToString("\n")
            // v3.5.58 缓存核验: 请求体前缀指纹 (system+tools 序列化稳定 → 前缀可命中)
            try {
                val fp = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((system + tools.joinToString { it.name }).toByteArray())
                    .take(8).joinToString("") { "%02x".format(it) }
                Log.i(TAG, "cache-fp: $fp (system=${system.length}c tools=${tools.size})")
            } catch (_: Exception) {}
            if (system.isNotBlank()) {
                val estTokens = system.length / 2.5
                Log.i(TAG, "System prompt breakdown: system=${sysPromptLen}c (~${(sysPromptLen/2.5).toInt()}t)" +
                    " layer1=${layer1Len}c (~${(layer1Len/2.5).toInt()}t)" +
                    " tools=${toolsPromptLen}c (~${(toolsPromptLen/2.5).toInt()}t)" +
                    " memory=${memPromptLen}c (~${(memPromptLen/2.5).toInt()}t)" +
                    " total=${system.length}c (~${estTokens.toInt()}t)")
                add(UIMessage.system(prompt = system))
            }
            addAll(messages)
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

        // G4 缓存诊断增强: 消息指纹 — 跨请求对比定位缓存断点 (哪条消息每轮变化)
        // 复现缓存卡住时: adb logcat 抓相邻两轮 msg_fp, 指纹不同的消息即断点
        Log.i(TAG, "msg_fp: " + internalMessages.mapIndexed { i, m ->
            val types = m.parts.joinToString("+") { p ->
                @Suppress("DEPRECATION") // 序列化兼容必需
                when (p) {
                    is UIMessagePart.Text -> "t"
                    is UIMessagePart.Reasoning -> "r"
                    is UIMessagePart.Tool -> "tl"
                    is UIMessagePart.Image -> "i"
                    is UIMessagePart.ToolCall -> "tc" // DEPRECATION: 序列化兼容必需
                    else -> "?"
                }
            }
            val hash = m.parts.joinToString("|") { p ->
                @Suppress("DEPRECATION") // 序列化兼容必需
                when (p) {
                    is UIMessagePart.Text -> p.text.hashCode().toString()
                    is UIMessagePart.Reasoning -> p.reasoning.hashCode().toString()
                    is UIMessagePart.Tool -> (p.toolName + p.output.hashCode()).hashCode().toString()
                    is UIMessagePart.ToolCall -> p.toolCallId.hashCode().toString() // DEPRECATION: 兼容必需
                    else -> "0"
                }
            }.hashCode()
            "[$i:${m.role.name}:$types:$hash]"
        }.joinToString(" "))

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
            val preStreamMessages = messages
            try {
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
                } ?: run {
                    // v3.6.9 流式实时估算: 平台只在最后发 usage 时中途无更新 —
                    // 按增量文本估算 completion tokens (结束被真实 usage 覆盖)
                    val textLen = messages.lastOrNull()?.toText()?.length ?: 0
                    val delta = (textLen - lastStreamTextLen).coerceAtLeast(0)
                    lastStreamTextLen = textLen
                    if (delta > 0) {
                        estimatedCompletionTokens += delta / 4
                        messages = messages.mapIndexed { index, message ->
                            if (index == messages.lastIndex) {
                                val cur = message.usage
                                if (cur != null && cur.completionTokens >= estimatedCompletionTokens) {
                                    message
                                } else {
                                    message.copy(
                                        usage = (cur ?: me.rerere.ai.core.TokenUsage())
                                            .copy(completionTokens = estimatedCompletionTokens)
                                    )
                                }
                            } else {
                                message
                            }
                        }
                    }
                }
                onUpdateMessages(messages)
            }
            break@streamLoop  // 流式成功完成, 退出重试循环
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // 用户主动停止 — 不重试
            } catch (e: java.io.IOException) {
                // 断流 (切后台/网络切换/NAT/平台): 回滚半截输出 → 自动重试 (最多 5 次)
                // v3.5.59: 2→5 (用户实测网络切换频繁, 2 次不够); 间隔退避
                // 1s/2s/4s/8s — 避免连续失败风暴发热, 重试消息相同缓存命中
                if (streamRetryCount < 5) {
                    streamRetryCount++
                    kotlinx.coroutines.delay(1_000L * streamRetryCount)
                    Log.w(TAG, "stream interrupted (${e.message}), rolling back & retry $streamRetryCount/5")
                    messages = preStreamMessages  // 丢弃本次生成的半截内容
                    onUpdateMessages(messages)    // UI 同步回滚
                    lastStreamTextLen = 0         // v3.6.9: 回滚后计数器重置 (重新累计)
                    estimatedCompletionTokens = 0
                    continue@streamLoop  // 重试 (maxSteps 内, 消息相同缓存命中)
                }
                throw e
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
            } ?: run {
                // v3.6.9 流式实时估算: 平台只在最后发 usage 时中途无更新 —
                // 按增量文本估算 completion tokens (结束被真实 usage 覆盖)
                val textLen = messages.lastOrNull()?.toText()?.length ?: 0
                val delta = (textLen - lastStreamTextLen).coerceAtLeast(0)
                lastStreamTextLen = textLen
                if (delta > 0) {
                    estimatedCompletionTokens += delta / 4
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            val cur = message.usage
                            if (cur != null && cur.completionTokens >= estimatedCompletionTokens) {
                                message
                            } else {
                                message.copy(
                                    usage = (cur ?: me.rerere.ai.core.TokenUsage())
                                        .copy(completionTokens = estimatedCompletionTokens)
                                )
                            }
                        } else {
                            message
                        }
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
