package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    // v5.0.15（用户定版）: 搜索恒开启 = 本地搜索。UI 开关已移除，状态永久为开启
    // （LOCAL 语义 = 启用本应用的搜索服务，ChatToolFactory 注入 web_search 工具）。
    return BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
    ): List<Tool> {
        val workspaceTools = createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd)
        val tools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools))
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            addAll(workspaceTools)
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }
        // 过长的输出存成文件让模型用 shell 按需读取；没有 shell 时模型读不到文件，只能原样返回
        return if (workspaceTools.isEmpty()) tools else tools.map(::withOutputTruncation)
    }

    private fun withOutputTruncation(tool: Tool): Tool {
        if (tool.name in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return tool
        val execute = tool.execute
        return tool.copy(execute = { truncateOutput(execute(it)) })
    }

    private fun truncateOutput(output: List<UIMessagePart>): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS) return output

        val fileName = "${Uuid.random()}.txt"
        Log.i(TAG, "truncateOutput: saving $totalChars chars to $fileName")
        val fullText = textParts.joinToString("\n") { it.text }

        // v5.0.66 ★P5 修复（用户实测："路径是工具自己给的，但 wc -l /tool_outputs/xxx 返回 0，
        // 截断的大输出等于永久丢失"）：
        //  旧实现 `File(outputDir, fileName).writeText(fullText)` 完全不检查结果，也不 catch ——
        //  一旦写入失败（磁盘满 / 目录被清理 / 并发 mkdirs 失败），照样回一句
        //  "Full output saved to: ..."，模型按提示去读必然扑空。
        //  现在：写入后**校验非空**；失败则**不谎称已保存**，改为多吐内容 + 明确告知失败原因。
        val saved = runCatching {
            val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
            val target = File(outputDir, fileName)
            target.writeText(fullText)
            target.takeIf { it.length() > 0L }
        }.getOrNull()

        return if (saved == null) {
            listOf(
                UIMessagePart.Text(
                    buildString {
                        appendLine("[Tool output truncated: $totalChars characters total — 落盘失败，以下不是完整内容]")
                        appendLine("（无法写入 /tool_outputs，因此不提供文件路径，避免你去读一个不存在的文件）")
                        appendLine("建议：把命令输出重定向到工作区文件后用 workspace_read_file 分页读，例如 `cmd > /workspace/log.txt 2>&1`。")
                        appendLine()
                        append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS * 2))
                    }
                )
            ) + output.filter { it !is UIMessagePart.Text }
        } else {
            listOf(
                UIMessagePart.Text(
                    buildString {
                        appendLine("[Tool output truncated: $totalChars characters total]")
                        appendLine("Full output saved to: /tool_outputs/$fileName (${saved.length()} bytes)")
                        // v5.0.66: 统一用 workspace_read_file 读（走 rootfs 解析，与工具层一致）；
                        // shell 的 `cat` 只在 proot 的 /tool_outputs bind 可用时有效，不再作首选。
                        appendLine("Read it with: workspace_read_file(path=\"/tool_outputs/$fileName\", offset=1, limit=2000)")
                        appendLine("Or in shell: `grep \"pattern\" /tool_outputs/$fileName`（若 shell 报 No such file，改用上面的工具读法）")
                        appendLine()
                        append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS))
                    }
                )
            ) + output.filter { it !is UIMessagePart.Text }
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}
