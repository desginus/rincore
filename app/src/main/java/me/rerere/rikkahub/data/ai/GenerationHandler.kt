package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
    data class LoadedDomains(
        val domains: Set<String>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val settingsStore: SettingsStore,
) {
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
        enabledSkills: List<Pair<String, String>> = emptyList(), // skill 名 to 描述 (invoke_tools 返回)
    ): Flow<GenerationChunk> = flow {
        CallTracer.startTrace(id = model.id.toString())
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages
        // 清洗非法消息序列 (未配对 tool_call / 孤儿 tool 消息) — 防止 Qwen 报 "Required settings preface not received"
        val sanitized = messages.sanitizeToolCallSequence()
        if (sanitized.size != messages.size) {
            Log.w(TAG, "streamText: sanitized message sequence (${messages.size} -> ${sanitized.size})")
        }
        messages = sanitized

        // === 分层路由状态 ===
        val useLayered = assistant.useLayeredTools && tools.isNotEmpty()
        // 从 Conversation 恢复已加载的域（跨对话持久化），并过滤无效域（配置已删除/不再存在的域）
        val loadedDomains = mutableSetOf<String>().apply {
            conversationLoadedDomains?.let { addAll(it) }
        }

        // 工具池基准快照 — 请求内稳定 (缓存友好: 工具池内容不变则 system/tools 文本不变)
        val baseMcpTools = DynamicTools.getMcpTools()
        val baseAllTools = (tools + baseMcpTools).distinctBy { it.name }
        var currentAllTools = baseAllTools

        var loadedDomainsEmitted = false
        for (stepIndex in 0 until maxSteps) {
            // 每步清洗非法消息序列 — 流中断/工具失败会在循环内产生孤儿 tool_call,
            // 仅入口清洗无法覆盖 (Qwen/DeepSeek 会报 "Required settings preface not received")
            val stepCleaned = messages.sanitizeToolCallSequence()
            if (stepCleaned.size != messages.size) {
                Log.w(TAG, "streamText: step #$stepIndex sanitized (${messages.size} -> ${stepCleaned.size})")
            }
            messages = stepCleaned

            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")
            CallTracer.event("STEP", "step_$stepIndex", "Step $stepIndex begin, ${tools.size} tools loaded, messages=${messages.size}")

            // 每步重建 ToolRouter，读取最新 settings (三位一体: UI/invoke_tools/prompt 同源)
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

            // 幽灵域清理: 已加载域中配置已删除/隐藏的, 从 loadedDomains 移除 (懒加载同步)
            val validDomains = toolRouter.validDomainLabels
            val staleDomains = loadedDomains.filter { it !in validDomains }
            if (staleDomains.isNotEmpty()) {
                loadedDomains.removeAll(staleDomains.toSet())
                Log.i(TAG, "streamText: pruned ${staleDomains.size} stale loaded domains: $staleDomains")
            }

            // MCP 工具池变化检测: 名称集合相同则复用快照 (避免 Tool 对象重建/顺序波动),
            // 变化(如 mcp_connect 运行时新连接)才重建 — 缓存友好
            val stepMcpTools = DynamicTools.getMcpTools()
            val mcpNamesNow = stepMcpTools.map { it.name }
            val mcpNamesSnapshot = currentAllTools.filter { it.name.startsWith("mcp__") }.map { it.name }
            if (mcpNamesNow != mcpNamesSnapshot) {
                currentAllTools = (tools + stepMcpTools).distinctBy { it.name }
                Log.i(TAG, "streamText: MCP tool pool changed (${mcpNamesSnapshot.size} -> ${mcpNamesNow.size}), rebuilding allTools")
            }
            val allTools = currentAllTools

            val layer1Prompt = if (useLayered) {
                toolRouter.buildLayer1(allTools)
            } else {
                null
            }

            val toolsInternal = if (useLayered) {
                buildList {
                    Log.i(TAG, "generateInternal: build tools (layered)($assistant), pool=${allTools.size}, loadedDomains=${loadedDomains}")
                    // Memory 工具 — 始终可用 (高频, 带闭包回调, 不适合懒加载)
                    if (assistant?.enableMemory == true) {
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
                    // invoke_tools — 唯一始终注入的非 memory 工具, 模型通过它按需加载所有其他工具
                    add(toolRouter.createInvokeToolsTool(allTools, loadedDomains, enabledSkills))
                    // 已加载域的工具
                    for (domain in loadedDomains) {
                        addAll(toolRouter.getDomainTools(domain, allTools))
                    }
                }.distinctBy { it.name }
                    .sortedBy { it.name }
            } else {
                buildList {
                    Log.i(TAG, "generateInternal: build tools($assistant)")
                    if (assistant?.enableMemory == true) {
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
                    addAll(allTools)
                }.distinctBy { it.name }.sortedBy { it.name }
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

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break — emit loadedDomains for persistence
                    if (useLayered && loadedDomains.isNotEmpty()) {
                        emit(GenerationChunk.LoadedDomains(loadedDomains.toSet()))
                        loadedDomainsEmitted = true
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
                            // 优先已加载域; 已批准工具允许从全量池兜底执行 (用户已批准 = 信任,
                            // 避免工具未加载时执行失败 → 模型重试 → 再次调用 → 死循环)
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: allTools.find { it.name == tool.toolName }
                            if (toolDef == null) {
                                // 分层模式下工具必须先通过 invoke_tools 加载，禁止自动加载其他域工具
                                // 提示实际所属域, 引导模型自愈(加载正确域后重试)
                                val msg = if (useLayered) {
                                    val knownTool = allTools.find { it.name == tool.toolName }
                                    if (knownTool != null) {
                                        val actualDomain = toolRouter.classifyTool(knownTool)
                                        "工具 ${tool.toolName} 未加载。它属于域「$actualDomain」。请先调用 invoke_tools(\"$actualDomain\") 加载对应域后再重试。"
                                    } else if (tool.toolName == "memory_tool") {
                                        "memory_tool 不可用：记忆功能未启用。请在助手设置中开启「长期记忆」(enableMemory) 后重试。"
                                    } else {
                                        "工具 ${tool.toolName} 不存在于任何域。"
                                    }
                                } else {
                                    "工具 ${tool.toolName} 未找到"
                                }
                                error(msg)
                            }
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            CallTracer.event("TOOL", "exec_${toolDef.name}", "Executing ${toolDef.name}, args=${tool.input.length}c")
                            val result = toolDef.execute(args)
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
        // 兜底持久化: 无论循环如何退出(maxSteps 用尽/工具链中断/正常结束),
        // 只要加载过域就持久化 — 避免下一轮恢复失败导致 tools 变化 → 缓存失效
        if (!loadedDomainsEmitted && useLayered && loadedDomains.isNotEmpty()) {
            emit(GenerationChunk.LoadedDomains(loadedDomains.toSet()))
            Log.i(TAG, "streamText: fallback emit LoadedDomains after loop (maxSteps exhausted or interrupted)")
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
        val internalMessages = buildList {
            val sysPromptLen: Int
            val memPromptLen: Int
            val toolsPromptLen: Int
            var layer1Len: Int = 0

            val system = buildString {
                // 缓存锚点 — 静态规则块 + 工具目录 ~870 chars
                // 满足 Qwen 3.7 1000-token 缓存阈值, 保证跨请求前缀完全一致
                append(buildCacheAnchor())
                appendLine()
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }
                sysPromptLen = length

                // Layer1 域概览 — 缓存友好: 仅在域配置变化时更新
                if (layer1Prompt != null) {
                    appendLine()
                    append(layer1Prompt)
                }
                layer1Len = length - sysPromptLen

                // 工具 systemPrompt — 分层模式下只注入 invoke_tools 自身; 非分层注入全部
                if (layer1Prompt != null) {
                    tools.filter { it.name == "invoke_tools" }.forEach { tool ->
                        val sp = tool.systemPrompt(model, messages)
                        if (sp.isNotBlank()) {
                            appendLine()
                            append(sp)
                        }
                    }
                } else {
                    tools.forEach { tool ->
                        appendLine()
                        append(tool.systemPrompt(model, messages))
                    }
                }
                toolsPromptLen = length - sysPromptLen - layer1Len

                // 记忆 — 原始 RikkaHub 策略: 在 system message 内
                memPromptLen = if (assistant.enableMemory) {
                    val memoryPrompt = buildMemoryPrompt(memories = memories)
                    if (memoryPrompt.isNotBlank()) {
                        appendLine()
                        append(memoryPrompt)
                        memoryPrompt.length
                    } else 0
                } else 0
            }
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
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params
            ).collect {
                messages = messages.handleMessageChunk(chunk = it, model = model)
                it.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(usage = message.usage.merge(usage))
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(messages)
            }
            logCacheUsage(messages)
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
            logCacheUsage(messages)
        }
    }

    // ===== 缓存断层诊断 =====
    // DeepSeek 缓存 = 前缀单元制 (固定 token 间隔切单元), 输入跨台阶时单元重排会导致
    // 缓存断到 system (≈9.7K). 记录每轮缓存命中, 检测断层 (骤降 >50%) 以便区分
    // 平台台阶机制 vs 客户端前缀变化.
    private var lastStepCachedTokens = 0

    private fun logCacheUsage(messages: List<UIMessage>) {
        val usage = messages.lastOrNull()?.usage ?: return
        val cached = usage.cachedTokens
        val prompt = usage.promptTokens
        if (cached <= 0) return
        val pct = prompt.takeIf { it > 0 }?.let { cached * 100 / it }
        Log.i(TAG, "cache: prompt=$prompt cached=$cached (${pct}%)")
        if (lastStepCachedTokens > 0 && cached < lastStepCachedTokens * 0.5) {
            Log.w(TAG, "cache: 缓存断层! ${lastStepCachedTokens} -> $cached (prompt=$prompt) — 平台台阶重排或前缀变化")
        }
        lastStepCachedTokens = cached
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


/**
 * 清洗消息序列中的非法工具调用结构, 防止 Qwen/DashScope 校验失败
 * ("Required settings preface not received" 通常是消息序列非法的报错)
 *
 * 1. 丢弃孤儿 tool 消息 (TOOL 角色但前面无对应 assistant tool_call)
 * 2. 移除所有未配对的 assistant tool_call (流中断/多 tool_call 部分执行/工具失败残留,
 *    保留消息中的文本与已配对 tool_call)
 */
private fun List<UIMessage>.sanitizeToolCallSequence(): List<UIMessage> {
    val pendingCalls = mutableSetOf<String>()
    val result = mutableListOf<UIMessage>()
    for (msg in this) {
        when (msg.role) {
            MessageRole.ASSISTANT -> {
                result.add(msg)
                msg.getTools().filter { it.output.isEmpty() }.forEach { pendingCalls.add(it.toolCallId) }
            }
            MessageRole.TOOL -> {
                val ids = msg.getTools().map { it.toolCallId }
                if (ids.isNotEmpty() && ids.all { it in pendingCalls }) {
                    result.add(msg)
                    pendingCalls.removeAll(ids)
                } else {
                    Log.w(TAG, "sanitize: dropping orphan tool message $ids")
                }
            }
            else -> result.add(msg)
        }
    }
    // 移除所有未配对的 assistant tool_call (不只末尾 — 流中断/多 tool_call 部分执行/工具失败残留)
    if (pendingCalls.isNotEmpty()) {
        Log.w(TAG, "sanitize: removing ${pendingCalls.size} unpaired tool calls: $pendingCalls")
        val cleaned = mutableListOf<UIMessage>()
        for (msg in result) {
            if (msg.role == MessageRole.ASSISTANT) {
                val tools = msg.getTools()
                val kept = tools.filter { it.toolCallId !in pendingCalls }
                if (kept.size != tools.size) {
                    val hasText = msg.parts.any { it is UIMessagePart.Text && it.text.isNotBlank() }
                    if (kept.isEmpty() && !hasText) {
                        continue // 无内容无 tool_call → 整体移除
                    }
                    // 保留文本与已配对 tool_call, 仅移除未配对部分
                    cleaned.add(
                        msg.copy(parts = msg.parts.filterNot {
                            it is UIMessagePart.Tool && it.toolCallId in pendingCalls
                        })
                    )
                    continue
                }
            }
            cleaned.add(msg)
        }
        return cleaned
    }
    return result
}
