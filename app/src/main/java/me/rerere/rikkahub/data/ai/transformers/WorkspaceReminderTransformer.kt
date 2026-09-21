package me.rerere.rikkahub.data.ai.transformers


/* ───【原版对齐】WorkspaceReminderTransformer.kt | 差异 ±7 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 与 ChatService.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages

        // 2.5.2/2.5.3 移植: 读取工作区 AGENTS.md (项目级指令) 并入系统提示
        val prompt = buildWorkspacePrompt(workspace, ctx.workspaceCwd) +
            buildAgentsPrompt(workspaceRepository, workspaceId, ctx.workspaceCwd)

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }
}

/**
 * 2.5.2/2.5.3 移植: AGENTS.md 读取 — /root/.agents、/workspace 根、会话当前目录三处。
 * 超过 64KB 跳过 (提示词膨胀保护), 读不到静默跳过。
 */
private suspend fun buildAgentsPrompt(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
    cwd: String?,
): String {
    // ProotShellRunner 将 HOME 固定为 /root; 相对 PWD 按 /workspace 解析。
    val workingDirectory = Paths.get("/workspace")
        .resolve(cwd?.takeIf { it.isNotBlank() } ?: ".")
        .normalize()
    val paths = linkedSetOf(
        "/root/.agents/AGENTS.md",
        "/workspace/AGENTS.md",
        workingDirectory.resolve("AGENTS.md").toString(),
    )
    val instructions = paths.mapNotNull { path ->
        try {
            val size = workspaceRepository.rootfsFileSize(workspaceId, path)
            require(size <= MAX_AGENTS_BYTES) { "AGENTS.md exceeds $MAX_AGENTS_BYTES bytes" }
            val content = ByteArrayOutputStream().use { output ->
                workspaceRepository.exportRootfsFile(workspaceId, path, output)
                output.toString(Charsets.UTF_8.name())
            }
            content.takeIf { it.isNotBlank() }?.let { path to it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("WorkspaceReminder", "Skipping workspace instructions: $path", e)
            null
        }
    }
    if (instructions.isEmpty()) return ""
    return buildString {
        appendLine()
        appendLine()
        appendLine("<workspace_instructions>")
        appendLine("Follow the AGENTS.md instructions below.")
        instructions.forEach { (path, content) ->
            appendLine()
            appendLine("AGENTS.md source: $path")
            appendLine(content)
        }
        append("</workspace_instructions>")
    }
}

private fun buildWorkspacePrompt(workspace: WorkspaceEntity, cwd: String? = null): String = buildString {
    appendLine("<workspace>")
    appendLine("You have access to a persistent Linux workspace named \"${workspace.name}\", running in a sandboxed proot rootfs environment.")
    appendLine("- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation.")
    appendLine("- All paths passed to workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`).")
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents — line-numbered, paginated via offset/limit (large files).")
    appendLine("  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files.")
    appendLine("  - `workspace_grep`: search file contents (regex), with optional glob filter — find definitions/usages/callers.")
    appendLine("  - `workspace_glob`: find files by name pattern (e.g. \"*.kt\") — newest first.")
    appendLine("  - `workspace_shell`: run shell commands (the files area is mounted at /workspace).")
    appendLine("- File work: prefer the dedicated tools over shell equivalents — `workspace_glob` (NOT find/ls), `workspace_grep` (NOT grep/rg), `workspace_read_file` (NOT cat/head/tail), `workspace_edit_file` (NOT sed) — they return structured output with unified truncation.")
    appendLine("- Large files: `workspace_read_file` reports totalLines and a continuation hint; keep reading with offset when truncated (default window 2000 lines).")
    appendLine("- Run `toolbox` any time to list the sandbox's built-in tools and check the environment (python3, pandoc, libraries) — a good first step when unsure what is available.")
    appendLine("- Document tools are installed and ready — prefer `office-edit` to modify an EXISTING file; use python/pandoc to create NEW files:")
    appendLine("  - `office-edit` (built-in): edits .docx / .pptx / .xlsx in place without losing formatting (patches only the touched XML; atomic — file untouched on failure).")
    appendLine("    `office-edit docx read <f>` · `office-edit docx replace <f> \"old\" \"new\"` · same with `pptx` · `office-edit xlsx read <f>` / `office-edit xlsx set <f> <sheet> <cell> \"value\"`.")
    appendLine("    Replacement text may span formatting runs. Runs on python3 — if the sandbox lacks it: `apt-get update && apt-get install -y python3`.")
    appendLine("  - `pandoc`: universal converter — `pandoc input.md -o output.docx` (also pdf/pptx/html/epub).")
    appendLine("  - `python-pptx` (python3): build PowerPoint slides programmatically.")
    appendLine("  - `python-docx` (python3): build Word documents programmatically.")
    appendLine("  - `reportlab` (python3): build PDF documents programmatically.")
    appendLine("  - `openpyxl` / `pandas` (python3): build Excel workbooks.")
    appendLine("  - To create a new document, slides, or spreadsheet: write a short Python script and run it via `workspace_shell`, or use `pandoc` for direct conversion. Save the output under `/workspace` and report the resulting path.")
    appendLine("- Code & text edits: prefer `apply-patch` (built-in) for precise multi-file edits — write a Codex-style patch and pipe it in:")
    appendLine("    apply-patch <<'PATCH'")
    appendLine("    *** Begin Patch")
    appendLine("    *** Update File: src/app.py")
    appendLine("    @@ def main():")
    appendLine("    -    old_call()")
    appendLine("    +    new_call()")
    appendLine("    *** End Patch")
    appendLine("    PATCH")
    appendLine("    Hunks are located by context lines (tolerant of whitespace differences); the whole patch applies in one transaction — if any hunk fails, nothing is written. Read files before editing; after a successful apply-patch, do not re-read them — trust the result.")
    appendLine("- Before delivering a document, run `office-check <file>` (built-in): verifies the file opens correctly and scans for leftover placeholders ({xxx} / 【xxx】 / TODO).")
    appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
    appendLine("- Keep the /workspace root tidy: create a dedicated subfolder per task/project and put its files there, instead of dumping loose files and directories at the root.")
    appendLine("- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change an uploaded file, copy it into `/workspace` first and edit the copy.")
    // v4.5.27: 助手级 CWD = 该助手的专一空间, 物理隔离在挂载层实现 —
    // 该文件夹即沙箱内 /workspace 根 (proot 挂载 + 文件工具解析同源), 外部目录不可见/不可达。
    val rawCwd = cwd?.trim('/').orEmpty()
    val scopeRel = (if (rawCwd == "workspace") "" else rawCwd.removePrefix("workspace/")).trim('/')
    if (scopeRel.isNotEmpty()) {
        appendLine("- This assistant's exclusive folder \"$scopeRel\" is mounted directly at `/workspace` — everything under /workspace is yours; areas outside it are not visible or reachable from this sandbox. Start by listing /workspace to see what you have.")
    }
    append("</workspace>")
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}

private val MAX_AGENTS_BYTES: Long = 64L * 1024
