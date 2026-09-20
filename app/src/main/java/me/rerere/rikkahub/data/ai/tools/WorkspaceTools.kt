package me.rerere.rikkahub.data.ai.tools


/* ───【原版对齐】WorkspaceTools | 差异 +65 行
 * 来源: 原版移植 + 自研 (工作区工具增强)
 * 差异: 工具审批默认值 (v3.6.13)、CWD 支持等自研
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

// v4.6.1 编程能力增强: read 分页 / search 上限 (对齐业界编程 Agent 设计)
private const val READ_DEFAULT_LIMIT = 2000
private const val READ_MAX_LIMIT = 10000
private const val GREP_DEFAULT_MAX = 100
private const val GREP_MAX_LIMIT = 500
private const val GLOB_DEFAULT_MAX = 200
private const val GLOB_MAX_LIMIT = 1000

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_show_file" to false,
    "workspace_shell" to false, // v3.6.13: 默认直接执行 (用户: 不弹批复)
    "workspace_grep" to false,  // v4.6.1: 只读搜索, 免审批
    "workspace_glob" to false,  // v4.6.1: 只读搜索, 免审批
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    return createWorkspaceToolsWithApprovals(workspaceId, cwd, approvalOverrides, workspaceRepository)
}

/** 静态版 — 配置驱动 (workspaceId 非空即注入), 非 suspend, UI 与模型侧共用。
 *  信源统一: 工具总数/列表与模型侧完全一致 (v3.5.44)。
 *  approval 用默认 (不查 getById); 需要精确 approval 时用 suspend 版。 */
fun createWorkspaceToolsStatic(
    workspaceId: String?,
    cwd: String? = null,
    workspaceRepository: WorkspaceRepository? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val repo = workspaceRepository ?: return emptyList()
    return createWorkspaceToolsWithApprovals(workspaceId, cwd, emptyMap(), repo)
}

private fun createWorkspaceToolsWithApprovals(
    workspaceId: String,
    cwd: String?,
    approvalOverrides: Map<String, Boolean>,
    workspaceRepository: WorkspaceRepository,
): List<Tool> {
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = workspaceCwdRel(cwd)

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository, cwd),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository, cwd),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository, cwd),
        createShowFileTool(workspaceId, ::needsApproval, workspaceRepository, cwd),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        // v4.6.1: 代码探索双件套 (对齐 Claude Code 的 Grep/Glob 设计 —
        // 专用工具优于 shell 拼接: 结构化输出/统一截断/免审批只读)
        createGrepTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        createGlobTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
    )
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Text files are returned with line numbers; large files are paginated: use offset (1-based start
        line) and limit (line count, default $READ_DEFAULT_LIMIT) to read further sections. The result
        reports totalLines and a continuation hint when truncated.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "1-based line number to start reading from. Defaults to 1.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of lines to read. Defaults to $READ_DEFAULT_LIMIT.")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val params = it.jsonObject
        val path = normalizeScopedPath(params.absolutePath("path"), cwd)
        val cwdRel = workspaceCwdRel(cwd)
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path, cwdRel)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path, cwdRel)
            // v4.6.1: 行号分页 (对齐业界编程 Agent 的 Read 工具设计)
            val offset = (params.string("offset")?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val limit = (params.string("limit")?.toIntOrNull() ?: READ_DEFAULT_LIMIT).coerceIn(1, READ_MAX_LIMIT)
            val allLines = text.split('\n')
            val totalLines = allLines.size
            val startIdx = (offset - 1).coerceAtMost(totalLines)
            val endIdx = (startIdx + limit).coerceAtMost(totalLines)
            val window = allLines.subList(startIdx, endIdx)
            val numbered = buildString {
                window.forEachIndexed { i, line ->
                    append((startIdx + i + 1).toString().padStart(6))
                    append('→')
                    append(line)
                    if (i < window.size - 1) append('\n')
                }
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", numbered)
                        put("totalLines", totalLines)
                        put("rangeStart", startIdx + 1)
                        put("rangeEnd", endIdx)
                        if (endIdx < totalLines) {
                            put("truncated", true)
                            put("continuation", "File has $totalLines lines. Continue with offset=${endIdx + 1}.")
                        }
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
                put("append", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Append to the end of the file instead of replacing it. Defaults to false.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = normalizeScopedPath(params.absolutePath("path"), cwd)
        val cwdRel = workspaceCwdRel(cwd)
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val append = params["append"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite, cwdRel, append)
        val resultJson = entry.toJson().toMutableMap()
        if (path.isImagePath()) {
            resultJson["render_url"] = JsonPrimitive(buildRenderUrl(workspaceId, path, cwdRel))
        }
        listOf(UIMessagePart.Text(JsonObject(resultJson).toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = normalizeScopedPath(params.absolutePath("path"), cwd)
        val cwdRel = workspaceCwdRel(cwd)
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path, cwdRel)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true, cwd = cwdRel)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

/**
 * 将工作区文件呈现在对话下附的胶囊窗中, 供用户查看/导出/分享。
 * 仅负责展示锚定 — 写入文件不会自动显示, 需要向用户递交文件时显式调用本工具。
 */
private fun createShowFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
) = Tool(
    name = "workspace_show_file",
    description = """
        Present an existing workspace file to the user as a file chip attached to the conversation.
        Use this for documents, reports, or other downloadable files that the user may want to export/share.
        Do NOT use this for images that should appear inline in the chat bubble — inline images are handled
        automatically via render_url in workspace_read_file / workspace_write_file / workspace_shell results.
        The file must already exist — writing a file does NOT show it automatically; call this tool explicitly.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_show_file") },
    execute = {
        val path = normalizeScopedPath(it.jsonObject.absolutePath("path"), cwd)
        val cwdRel = workspaceCwdRel(cwd)
        val size = workspaceRepository.rootfsFileSize(workspaceId, path, cwdRel) // 不存在则抛异常
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("size", size)
                    put("status", "shown")
                }.toString()
            )
        )
    },
)

/**
 * v4.6.1: 内容搜索 (Grep) — ripgrep 优先, grep 兜底。
 * 对齐业界编程 Agent: "Content search: Use Grep (NOT shell grep)"。
 */
private fun createGrepTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_grep",
    description = """
        Search file contents in the workspace (ripgrep when available, grep fallback).
        Returns matching lines as path:line:content. Use this instead of shell grep/rg for code search.
        Parameters: pattern (regex, required); path (search root, defaults to /workspace);
        glob (file filter like "*.kt", optional); maxResults (default $GREP_DEFAULT_MAX).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Regex pattern to search for")
                })
                putPathProperty(required = false)
                put("glob", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional file filter, e.g. \"*.kt\" or \"*.md\"")
                })
                put("maxResults", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum matching lines to return. Defaults to $GREP_DEFAULT_MAX, max $GREP_MAX_LIMIT.")
                })
            },
            required = listOf("pattern"),
        )
    },
    needsApproval = { needsApproval("workspace_grep") },
    execute = {
        val params = it.jsonObject
        val pattern = params.string("pattern") ?: error("pattern is required")
        require(pattern.isNotBlank()) { "pattern must not be blank" }
        val searchPath = normalizeScopedPath(
            params.string("path")?.takeIf { it.isNotBlank() } ?: "/workspace",
            defaultCwd,
        )
        val glob = params.string("glob")?.takeIf { it.isNotBlank() }
        val maxResults = (params.string("maxResults")?.toIntOrNull() ?: GREP_DEFAULT_MAX)
            .coerceIn(1, GREP_MAX_LIMIT)
        val pathArg = searchPath.shellQuote()
        val patternArg = pattern.shellQuote()
        val globArg = glob?.let { " --glob ${it.shellQuote()}" } ?: ""
        val grepInclude = glob?.let { " --include=${it.shellQuote()}" } ?: ""
        val command = """
            if command -v rg >/dev/null 2>&1; then
              rg --line-number --no-heading --color never -e $patternArg$globArg -- $pathArg 2>/dev/null | head -n $maxResults
            else
              grep -rn -I -e $patternArg$grepInclude -- $pathArg 2>/dev/null | head -n $maxResults
            fi
            exit 0
        """.trimIndent()
        val result = workspaceRepository.executeCommand(workspaceId, command, defaultCwd.orEmpty())
        val output = (result.stdout ?: "").trim()
        val lines = if (output.isBlank()) emptyList() else output.split('\n')
        val truncated = lines.size >= maxResults
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("pattern", pattern)
                    put("path", searchPath)
                    if (glob != null) put("glob", glob)
                    put("matches", lines.size)
                    if (truncated) {
                        put("truncated", true)
                        put("note", "Showing first $maxResults matches; refine pattern/glob for more precision.")
                    }
                    put("text", output)
                }.toString()
            )
        )
    },
)

/**
 * v4.6.1: 文件名搜索 (Glob) — 对齐业界: "File search: Use Glob (NOT find or ls)"。
 * 输出按修改时间倒序 (最新在前)。
 */
private fun createGlobTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_glob",
    description = """
        Find files by name pattern in the workspace (e.g. "*.kt", "README*", "config.*").
        Returns matching file paths sorted by modification time (newest first).
        Use this instead of shell find/ls for file discovery.
        Parameters: pattern (required); path (search root, defaults to /workspace);
        maxResults (default $GLOB_DEFAULT_MAX).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "File name pattern, e.g. \"*.kt\"")
                })
                putPathProperty(required = false)
                put("maxResults", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum paths to return. Defaults to $GLOB_DEFAULT_MAX, max $GLOB_MAX_LIMIT.")
                })
            },
            required = listOf("pattern"),
        )
    },
    needsApproval = { needsApproval("workspace_glob") },
    execute = {
        val params = it.jsonObject
        val pattern = params.string("pattern") ?: error("pattern is required")
        require(pattern.isNotBlank()) { "pattern must not be blank" }
        val searchPath = normalizeScopedPath(
            params.string("path")?.takeIf { it.isNotBlank() } ?: "/workspace",
            defaultCwd,
        )
        val maxResults = (params.string("maxResults")?.toIntOrNull() ?: GLOB_DEFAULT_MAX)
            .coerceIn(1, GLOB_MAX_LIMIT)
        val pathArg = searchPath.shellQuote()
        val patternArg = pattern.shellQuote()
        // mtime 倒序 (find -printf 不支持时降级为原序) — 全路径输出
        val command = """
            if find $pathArg -maxdepth 0 -printf '' 2>/dev/null; then
              find $pathArg -type f -name $patternArg -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n $maxResults | cut -d' ' -f2-
            else
              find $pathArg -type f -name $patternArg 2>/dev/null | head -n $maxResults
            fi
            exit 0
        """.trimIndent()
        val result = workspaceRepository.executeCommand(workspaceId, command, defaultCwd.orEmpty())
        val output = (result.stdout ?: "").trim()
        val lines = if (output.isBlank()) emptyList() else output.split('\n')
        val truncated = lines.size >= maxResults
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("pattern", pattern)
                    put("path", searchPath)
                    put("count", lines.size)
                    if (truncated) {
                        put("truncated", true)
                        put("note", "Showing newest $maxResults files; refine pattern for more precision.")
                    }
                    put("text", output)
                }.toString()
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        // 完整归一化 ("/workspace/xxx" / "xxx" / "workspace/xxx" -> "xxx"): 该值同时是
        // proot 的 /workspace 挂载源选择依据 (v4.5.27), 必须单一精确口径。
        val cwd = workspaceCwdRel(params.string("cwd") ?: defaultCwd).orEmpty()
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        val cwdRel = cwd.ifBlank { null }
        val combinedOutput = (result.stdout ?: "") + "\n" + (result.stderr ?: "")
        val imagePaths = extractImagePathsFromText(combinedOutput)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                    if (imagePaths.isNotEmpty()) {
                        put("render_urls", buildJsonArray {
                            imagePaths.forEach { add(JsonPrimitive(buildRenderUrl(workspaceId, it, cwdRel))) }
                        })
                    }
                }.toString()
            )
        )
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
    cwd: String? = null,
): String = readRootfsBuffer(workspaceId, path, cwd).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
    cwd: String? = null,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path, cwd)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it, cwd) }
}

/**
 * 4.0.13: 图片落盘/读取后生成标准本地渲染 URL。
 * 格式严格对齐当前 resolver 已支持的 host 字面路径:
 * file:///data/data/<package>/files/workspaces/<UUID>/files/<relPath>
 * 该路径由 proot -b 参数决定, resolver 通过 HOST_WS_PREFIXES 识别并
 * normalize 到 /workspace/<rel>, 再遍历 workspace root 命中真实文件。
 * 模型在回复中原样复述 ![](render_url) 即可被 Markdown/ZoomableAsyncImage
 * 直接渲染, 无需再自己拼凑地址。
 */
/**
 * 4.0.14: 从 shell stdout/stderr 中启发式提取可能生成的图片路径。
 * 支持 /workspace/...、workspace://...、file:///data/data/.../files/... 等形态。
 * 提取结果用于在 shell 返回中附带 render_urls，减少模型再次 read_file 的负担。
 */
private fun extractImagePathsFromText(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val regex = """(?i)(?:file://)?(?:/workspace/|workspace://|/data/data/[^\s"'`<>|]*/files/workspaces/[^\s"'`<>|]*/files/)[^\s"'`<>|]+\.(?:png|jpg|jpeg|gif|webp|bmp|svg|heic|heif|avif|ico)""".toRegex()
    return regex.findAll(text)
        .map { it.value.trim() }
        .distinct()
        .toList()
}

private fun buildRenderUrl(workspaceId: String, path: String, cwd: String? = null): String {
    // v4.5.27: CWD 专一空间 — 生成宿主完整路径时拼入 cwd 段, resolver 侧无需感知 cwd。
    // host 字面形态输入 (含 workspaces/<UUID>/files/) 直接取其后段, 不再二次拼 cwd。
    val hostRel = me.rerere.rikkahub.utils.normalizeHostWorkspacePath(path)?.removePrefix("/workspace/")
    val rel = hostRel ?: run {
        val base = path.trimStart('/').removePrefix("workspace/").removePrefix("/workspace/")
        if (cwd.isNullOrEmpty()) base else "$cwd/$base"
    }
    return "file:///data/data/me.rincore.app/files/workspaces/$workspaceId/files/$rel"
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
    cwd: String? = null,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path, cwd).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
                put("render_url", buildRenderUrl(workspaceId, path, cwd))
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
    cwd: String? = null,
    append: Boolean = false,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val redirect = if (append) ">>" else ">"
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite && !append).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat $redirect $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
        cwd = cwd,
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
    cwd: String? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        cwd = cwd.orEmpty(),
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

/**
 * v4.5.27: 助手级 CWD 归一化 — 输入形如 "/workspace/xxx"、"xxx"、"workspace/xxx" 或 "/workspace",
 * 输出相对 files 的纯子路径 ("xxx") 或 null (无约束)。所有链路的 cwd 参数统一经此口径。
 */
private fun workspaceCwdRel(cwd: String?): String? {
    if (cwd.isNullOrBlank()) return null
    val raw = cwd.trim('/')
    val rel = (if (raw == "workspace") "" else raw.removePrefix("workspace/")).trim('/')
    return rel.ifBlank { null }
}

/**
 * v4.5.27: CWD 专一空间 — 模型路径归一化。
 * 助手文件夹在沙箱内即 /workspace 根 (proot 挂载 + 直读解析同源实现);
 * 模型若携带 cwd 名前缀 (/workspace/<cwd>/x, 历史惯性) 则剥离之, 保持单一语义。
 */
private fun normalizeScopedPath(path: String, cwd: String?): String {
    val scopeRel = workspaceCwdRel(cwd) ?: return path
    val scopeAbs = "/workspace/$scopeRel"
    return when {
        path == scopeAbs -> "/workspace"
        path.startsWith("$scopeAbs/") -> "/workspace/" + path.removePrefix("$scopeAbs/")
        else -> path
    }
}

// 免强制审批的可写安全区: 工作区文件目录、临时目录和技能目录
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp", "/skills")

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWritableRoots()
    }.getOrDefault(true)

private fun String.isOutsideWritableRoots(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return WRITABLE_ROOT_PREFIXES.none { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
