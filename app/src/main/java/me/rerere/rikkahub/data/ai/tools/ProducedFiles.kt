/* 【域 B·AI 工具】 — 本对话产出登记 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * v4.8.121 (用户定版「本对话产出」): 本对话中模型产出/修改过的文件记录。
 * v4.8.122 (用户意见: 监控工作区文件相关工具, 编辑/生成/移动等全部登记):
 * 三条登记路径 — ①直登记 (workspace_write_file / workspace_edit_file 的工具参数);
 * ②工作区文件系统扫描 (shell/job 回合后按 mtime 兜底, 见 [scanWorkspaceSince]);
 * ③批量回写走 [recordBatch]。持久化于 Settings (DataStore), 按对话分组, 新→旧排列;
 * 每对话上限 80 条, 全局保留最近 200 个对话 (超限按每对话最新时间裁剪)。
 * UI = 速览页「本对话产出」胶囊窗列表 (点击 → 选项弹层)。
 * v4.8.125 (用户复核"点进去为什么是 4 个"): 路径规范化 [canonicalPath] —
 * 直登记 (工具参数形态不一: 裸相对 / "/workspace/x" / cwd 作用域) 与扫描产物
 * (filesDir 相对 → "/workspace/<rel>") 收敛为同一全根形态后再去重, 同一文件
 * 不再以多形态重复入列。
 */
@Serializable
data class ProducedFile(
    /** 工具写入路径 (规范化全根形态: /workspace/<rel>; 根沙箱绝对路径) */
    val path: String,
    /** 产出工具名 */
    val tool: String,
    /** 最近一次产出时间 (epoch ms) */
    val atMs: Long,
    /**
     * v5.0.59 **唯一路由键（工作区）**：产出发生时所在工作区 id。
     *
     * 这是"点开产出"时唯一不需要猜的信息。旧记录为 null（只能回退探测）。
     * 空字符串 = 当时确实没有工作区。
     */
    val workspaceId: String? = null,
    /**
     * v5.0.59 **唯一路由键（CWD）**：产出发生时生效的作用域，**相对形态**
     * （`projects/news`，null = filesDir 根）。
     *
     * 与 `WorkspaceManager.workspaceScopeDir(filesDir, cwd)` 同一口径 —— 解析时
     * `rootfsFileSize(wsId, path, cwd)` 一步即中，不需要任何搜索。
     */
    val cwd: String? = null,
    /**
     * v5.0.62 **最终答案：宿主文件系统绝对路径**。
     *
     * 这是"唯一对应码"的真正形态 —— 记录产出时它已经指向磁盘上那个真实的 File，
     * 打开时 `File(hostPath).isFile` 一次 stat 就够，**不需要任何 rootfs 语义、CWD 拼接、
     * 工作区匹配**。
     *
     * 跨包名/跨设备迁移时绝对路径会变（`/data/data/<pkg>/files/...`），所以解析侧
     * 会退化一步：按 `"/files/"` 之后的相对部分在当前 `filesDir` 重建。
     */
    val hostPath: String? = null,
)

object ProducedFiles {
    private const val MAX_PER_CONVERSATION = 80
    private const val MAX_CONVERSATIONS = 200

    /** 写类工具 → 其参数中的路径键 */
    private val PATH_ARG: Map<String, String> = mapOf(
        "workspace_write_file" to "path",
        "workspace_edit_file" to "path",
    )

    /**
     * v4.8.125: 路径规范化 — 全根语义唯一形态 "/workspace/<rel>"。
     * 背景: 写类工具的 path 参数形态不一 (裸相对 / "/workspace/x" / cwd 作用域),
     * 与 shell 回合 mtime 扫描产物 (filesDir 相对 → "/workspace/<rel>") 对不上,
     * 同一文件会以多形态重复入列 ("为什么是 4 个" 的来源之一)。cwd 非空时按
     * 作用域补前级 (与 proot /workspace 挂载映射同源); 非 /workspace 的其它
     * 绝对形态 (rootfs 其它区) 原样保留。
     */
    fun canonicalPath(raw: String, cwd: String?): String {
        val p = raw.trim().replace('\\', '/')
        if (p.isEmpty()) return p
        if (p == "/workspace") return "/workspace"
        val rel = when {
            p.startsWith("/workspace/") -> p.removePrefix("/workspace/")
            p.startsWith("/") -> return p
            else -> p
        }
        // v5.0.57 ★「本对话产出点击后统一提示未找到文件」的真根因：
        // WorkspaceCwdPicker 的 toAbsolutePath() 存进 assistant/folder 的 cwd 是
        // **带前缀形态** "/workspace/projects/foo"；而这里只做 trim('/') →
        // c = "workspace/projects/foo" → 拼出 "/workspace/workspace/projects/foo/x.png"，
        // 双重前缀 → resolveScopedFile 的 probes 全部落空 → 模糊查找也匹配不到 →
        // 用户点任何产出都弹「未找到文件」。
        // 修法：cwd 归一化为**纯相对作用域**（剥掉 /workspace 前缀），使本函数幂等。
        val c = cwd?.trim()?.replace('\\', '/')?.trim('/')
            ?.removePrefix("workspace/")
            ?.removePrefix("workspace")
            ?.trim('/')
            .orEmpty()
        val full = if (c.isNotEmpty() && !rel.startsWith("$c/")) "$c/$rel" else rel
        return "/workspace/" + full.trimStart('/')
    }

    /**
     * v5.0.59：作用域归一化 —— 与 `WorkspaceManager.workspaceScopeDir` 同口径。
     * `/workspace/projects/news` 与 `projects/news` 与 `//projects/news/` 都压成 `projects/news`；
     * `""` / `/workspace` / null 都压成 null（= filesDir 根）。
     */
    fun normalizeScope(raw: String?): String? {
        val t = raw?.trim()?.replace('\\', '/')?.trim('/')?.trim() ?: return null
        if (t.isEmpty() || t == "workspace") return null
        val rel = if (t.startsWith("workspace/")) t.removePrefix("workspace/") else t
        return rel.trim('/').ifBlank { null }
    }

    /** 从工具名 + 参数提取产出路径 (已规范化; cwd = 环路作用域, 用于形态收敛) */
    fun extractPath(toolName: String, args: JsonObject?, cwd: String? = null): String? {
        val key = PATH_ARG[toolName] ?: return null
        val raw = (args?.get(key) as? JsonPrimitive)?.content?.trim().orEmpty()
        return raw.ifEmpty { null }?.let { canonicalPath(it, cwd) }
    }

    /** 幂等登记: 同路径去重取最新 (新→旧), 每对话上限 80, 全局保留最近 200 对话 */
    fun record(
        current: Map<String, List<ProducedFile>>,
        conversationId: String,
        toolName: String,
        path: String,
        nowMs: Long,
        workspaceId: String? = null,
        cwd: String? = null,
        hostPath: String? = null,
    ): Map<String, List<ProducedFile>> {
        val existing = current[conversationId].orEmpty()
        val scope = normalizeScope(cwd)
        val updated = buildList {
            add(
                ProducedFile(
                    path = path,
                    tool = toolName,
                    atMs = nowMs,
                    workspaceId = workspaceId?.takeIf { it.isNotBlank() },
                    cwd = scope,
                    hostPath = hostPath?.takeIf { it.isNotBlank() },
                )
            )
            existing.forEach { if (it.path != path) add(it) }
        }.take(MAX_PER_CONVERSATION)

        val next = current.toMutableMap()
        next[conversationId] = updated
        if (next.size <= MAX_CONVERSATIONS) return next

        return next.entries
            .sortedByDescending { (_, v) -> v.maxOfOrNull { it.atMs } ?: 0L }
            .take(MAX_CONVERSATIONS)
            .associate { it.key to it.value }
    }

    /** v4.8.122: 批量登记 (一次事务写回; 逐条复用 record 的去重/上限/裁剪) */
    fun recordBatch(
        current: Map<String, List<ProducedFile>>,
        conversationId: String,
        entries: List<Pair<String, String>>,
        nowMs: Long,
        workspaceId: String? = null,
        cwd: String? = null,
        hostPaths: Map<String, String> = emptyMap(),
    ): Map<String, List<ProducedFile>> {
        if (entries.isEmpty()) return current
        var acc = current
        entries.forEach { (tool, path) ->
            if (path.isNotBlank()) {
                acc = record(
                    acc, conversationId, tool, path, nowMs, workspaceId, cwd,
                    hostPath = hostPaths[path],
                )
            }
        }
        return acc
    }

    /**
     * v4.8.122 (用户意见: 监控工作区文件相关工具 — 编辑/生成/移动等全部登记,
     * "容易且效果好"): 工作区文件系统扫描 — 返回 sinceMs 之后被创建/修改的文件
     * (rootfs 全根语义 "/workspace/<rel>")。shell / officecli / python 等一切
     * 生成路径的兜底; 排除 .versions 版本快照目录; 目录不计; 上限 200。
     * v4.8.146: cwdScope = 本对话生效 CWD (多对话并行时把归属边界收到本项目子树)。
     */
    /** v5.0.62: 扫描结果 = (rootfs 形态路径, 宿主绝对路径) */
    data class ScannedFile(val rootfsPath: String, val hostPath: String)

    suspend fun scanWorkspaceSince(
        workspaceId: String,
        sinceMs: Long,
        cwdScope: String? = null,
    ): List<ScannedFile> {
        return runCatching {
            val koin = org.koin.core.context.GlobalContext.get()
            val repo = koin.get<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
            val manager = koin.get<me.rerere.workspace.WorkspaceManager>()
            val entity = repo.getById(workspaceId) ?: return emptyList()
            val base = manager.filesDir(entity.root)
            if (!base.isDirectory) return emptyList()

            // v4.8.146 (用户复核"记录不准确, 多对话场景注意"): 扫描域 = 本对话生效 CWD 子树。
            // 多对话并行共用同一工作区时, 全根 mtime 扫描会把邻居对话/用户手改的文件
            // 记到本对话 (跨对话污染); CWD 域把"本对话项目范围"作为归属边界。
            // CWD 为空或路径不存在时退回全根 (保持旧行为, 避免漏报)。
            // v4.8.188: 点目录整体排除 — .versions(版本快照) / .render(渲染缓存) /
            // .git 等内部目录不属"对话产出"（.render 的 cache-*/page1.html 曾被卷进产出列表）。
            val scopeRel = cwdScope?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
            val scopeRoot = scopeRel?.let { File(base, it) }?.takeIf { it.isDirectory } ?: base

            val out = ArrayList<ScannedFile>()
            val seq = scopeRoot.walkTopDown().onEnter { dir -> !dir.name.startsWith(".") }
            for (f in seq) {
                if (out.size >= 200) break
                if (f.isFile && f.lastModified() >= sinceMs) {
                    val rel = f.relativeTo(base).path.replace(File.separatorChar, '/')
                    if (rel.isNotBlank()) {
                        // v5.0.62: 宿主绝对路径一并带出 —— 登记时直接落盘，
                        // 点开时 File(hostPath) 一次 stat 就行，不再做路径形态推断。
                        out.add(ScannedFile("/workspace/$rel", f.absolutePath))
                    }
                }
            }
            out
        }.getOrDefault(emptyList())
    }
}
