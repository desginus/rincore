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
 * v4.7.10: AGENTS.md 读取缓存 — 每轮请求此前做最多 6 次重 IO (查大小+读全文 ×3
 * 路径; 每次含 Room 查询 + ensureWorkspace + 沙箱文件操作), 是"工具返回结果后
 * 等待很久"的主要来源。TTL + size 双机制: TTL(10s) 内直接命中 (零 IO);
 * 过期后仅查 size (轻), 未变续期用缓存, 变了才读全文; 不存在也缓存空条目
 * (避免每轮 3 次异常探测)。文件编辑后最多 10s 生效 (可接受)。
 */
private data class AgentsCacheEntry(val size: Long, val content: String, val at: Long)
private val agentsCache = java.util.concurrent.ConcurrentHashMap<String, AgentsCacheEntry>()
private const val AGENTS_CACHE_TTL_MS = 10_000L

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
        val cacheKey = "$workspaceId|$path"
        val now = System.currentTimeMillis()
        val cached = agentsCache[cacheKey]
        // TTL 内直接命中 — 零 IO
        if (cached != null && now - cached.at < AGENTS_CACHE_TTL_MS) {
            return@mapNotNull if (cached.content.isBlank()) null else path to cached.content
        }
        try {
            val size = workspaceRepository.rootfsFileSize(workspaceId, path)
            require(size <= MAX_AGENTS_BYTES) { "AGENTS.md exceeds $MAX_AGENTS_BYTES bytes" }
            // size 未变 — 续期用缓存 (省全文读)
            if (cached != null && cached.size == size) {
                agentsCache[cacheKey] = cached.copy(at = now)
                return@mapNotNull if (cached.content.isBlank()) null else path to cached.content
            }
            val content = ByteArrayOutputStream().use { output ->
                workspaceRepository.exportRootfsFile(workspaceId, path, output)
                output.toString(Charsets.UTF_8.name())
            }
            agentsCache[cacheKey] = AgentsCacheEntry(size, content, now)
            content.takeIf { it.isNotBlank() }?.let { path to it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 不存在/读取失败 — 缓存空条目 (TTL 内不重复探测)
            agentsCache[cacheKey] = AgentsCacheEntry(-1L, "", now)
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
    appendLine("- Office/document toolchain (installed & verified — read / edit / render / convert / batch):")
    appendLine("  - `officecli` (recommended, full Office suite for AI):")
    appendLine("    read: `officecli view <f> text` · render an image preview: `officecli view <f> screenshot` (PNG) or `html` · quality scan: `officecli view <f> issues`.")
    appendLine("    edit: `officecli add|set|remove|move <f> <path> ...` (DOM-like paths — /body/p[1], /Sheet1/A1; `--json` for structured output).")
    appendLine("    batch (atomic, single pass): `officecli batch <f> --commands '[{\"command\":\"set\",\"path\":\"/Sheet1/A1\",\"props\":{\"value\":\"x\"}}]'` · template merge: `officecli merge`.")
    appendLine("  - `office-edit` (built-in): surgical byte-preserving edit of an EXISTING .docx/.pptx/.xlsx — best for tiny text swaps;")
    appendLine("    `office-edit docx read <f>` · `office-edit docx replace <f> \"old\" \"new\"` · `office-edit xlsx set <f> <sheet> <cell> \"value\"` (atomic).")
    appendLine("  - `pandoc`: universal converter — `pandoc input.md -o output.docx` (md/html/docx/epub; PDF via `--pdf-engine=xelatex`).")
    appendLine("  - `python3` libraries: python-docx / python-pptx / openpyxl / pandas / reportlab / PyMuPDF — programmatic generation and PDF processing.")
    appendLine("    To build a new document programmatically: write a short Python script and run it via `workspace_shell`. Save output under /workspace and report the path.")
    appendLine("  - ⚠️ LibreOffice / soffice is NOT available (proot sandbox has no standard /proc — unfixable). NEVER call `soffice`/`libreoffice`;")
    appendLine("    for document preview / rendering use `officecli view <f> screenshot` instead.")
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
    appendLine("- Code tasks — work like a professional coding agent (this workflow prevents 90% of rework):")
    appendLine("  1. Explore before editing. Search first (rg / `workspace_grep` / `workspace_glob`), then read the target file AND its context")
    appendLine("     (imports, neighboring files, call sites). Mimic the project's existing conventions — same style, same libraries, same patterns.")
    appendLine("     NEVER assume a library is available: check the project's own files/config first.")
    appendLine("  2. Edit small and testable — one logical change at a time. Prefer `apply-patch` (multi-file, context-located, transactional);")
    appendLine("     use write/edit tools for new files; use scripts for bulk search-replace or generated content.")
    appendLine("  3. Constraints: ASCII by default (non-ASCII only when the file already uses it); comments only where the code is non-obvious (rare);")
    appendLine("     never log or commit secrets/keys.")
    appendLine("  4. Verify before you finish. Run the project's own lint / typecheck / test commands (find them in README, package.json, pyproject.toml,")
    appendLine("     Makefile). The sandbox ships: black, ruff, mypy (Python) · eslint, tsc (JS/TS) · node/npm, python3, git, rg, fdfind, jq, tree.")
    appendLine("     If the project defines no checks, say so explicitly in your final message.")
    appendLine("  5. Git hygiene: NEVER revert changes you did not make; never run destructive commands (git reset --hard, git checkout --);")
    appendLine("     do not commit unless the user asks.")
    appendLine("- Frontend/UI tasks: avoid bland generic ('AI slop') layouts — expressive type (avoid default font stacks), a clear color direction")
    appendLine("  (CSS variables; not purple-on-white by default), a few meaningful animations, non-flat backgrounds. Preserve an existing design system when present.")
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
