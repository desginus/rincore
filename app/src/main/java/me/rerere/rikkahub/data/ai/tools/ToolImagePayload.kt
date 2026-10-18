/* 【域 E·模型工具】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.data.ai.tools


/* ───【自研】ToolImagePayload.kt — v4.8.115 工具产图渲染地址单一来源
 * 用户定版 (2026-10-09): "渲染地址全部统一, 不要一个地方一个转换"。
 * 本文件是工具产图渲染地址的唯一权威:
 *   产出 (模型侧)  = buildRenderPayload / buildRenderUrl (启用前验存, 死链零输出)
 *   提取 (显示侧)  = extractRenderUrls
 *   编码 (规范形态) = encodeMarkdownUrl
 * 全链: WorkspaceTools(读/写/壳三生产点·写入) / BrowserTools(截图·写入) / WorkspaceToolUIs(工具段提取·显示) ｜
 *       一切地址构造/提取必须经本文件, 别处不得自建 (v4.8.115 用户定版)。
 *
 * 规范地址形态 = percent 编码的 file:///data/data/<pkg>/files/workspaces/<id>/files/<rel>
 *  - markdown 语法安全 (中文/空格/括号不中断解析) — 模型可逐字复述;
 *  - 前缀为 ASCII — XSS 门 (isAppPrivateFileUri 字面前缀判定) 不受编码影响;
 *  - 解码收口在唯一一层: Coil 认领层 (WorkspaceImageFetch), 其余位置只透传。
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val MD_URL_HEX = "0123456789ABCDEF"

/** markdown 内联 URL 编码 — 保留 [A-Za-z0-9-_.~$&*+,;=:@/]，其余按 UTF-8 %XX (v4.8.113 引入, v4.8.115 收口至此) */
internal fun encodeMarkdownUrl(url: String): String {
    val safe = "-_.~$&*+,;=:@"
    val sb = StringBuilder(url.length + 16)
    for (ch in url) {
        when {
            ch == '/' -> sb.append('/')
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in safe -> sb.append(ch)
            else -> ch.toString().toByteArray(Charsets.UTF_8).forEach { b ->
                val v = b.toInt() and 0xFF
                sb.append('%').append(MD_URL_HEX[v shr 4]).append(MD_URL_HEX[v and 0x0F])
            }
        }
    }
    return sb.toString()
}

/**
 * v4.8.142 (用户诊断批次): 沙箱/宿主路径 → **候选宿主文件路径** (纯函数, 有序去重)。
 *
 * 映射表与真实挂载同源 (RepositoryModule 的 bindMounts — 注释明言"同一份挂载表
 * 既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析"; 目录名取 FileFolders 常量):
 *   /workspace/...      → <files>/workspaces/<id>/files + (cwd 语义拼接, 与 v4.5.27
 *                          专一空间挂载一致: 沙箱内 /workspace = 宿主 filesDir/<cwd>)
 *   /upload/...         → <files>/upload/...
 *   /tool_outputs/...   → <files>/tool_outputs/...
 *   /skills/...         → <files>/skills/...
 *   宿主字面形态        → 原样通过 (仅放行上述四个可显示区内)
 *   其它绝对路径 (/tmp /var /root …) → **空列表 = 不生成任何地址** (宁可没图, 不给死链)
 *
 * 旧实现把一切非 /workspace 路径当"工作区相对路径"往工作区根下拼 (只对 /workspace
 * 成立): /tmp/x.png → <ws>/tmp/x.png、/upload/x.png → <ws>/<cwd>/upload/x.png —
 * 均不存在; 且兜底末行无条件返回主形态 = 死文件也发"可复述显示"地址 (用户实证)。
 */
internal fun renderUrlCandidates(workspaceId: String, path: String, cwd: String?): List<String> {
    val filesRoot = "/data/data/${me.rerere.rikkahub.BuildConfig.APPLICATION_ID}/files"
    val wsRoot = "$filesRoot/workspaces/$workspaceId/files"

    var strip = path.trim()
    if (strip.startsWith("file://")) strip = strip.removePrefix("file://")
    // workspace:// 虚拟形态 = 工作区相对路径语义 (与 resolver 族一致; 剥 scheme 后走下方相对路径分支)
    if (strip.startsWith("workspace://")) strip = strip.removePrefix("workspace://")
    if ('%' in strip) strip = me.rerere.rikkahub.utils.percentDecodeLenient(strip)
    strip = me.rerere.rikkahub.utils.normalizeDataUserAlias(strip)

    // ① 宿主字面形态: 仅放行四个可显示区 (workspaces/upload/tool_outputs/skills)
    if (strip.startsWith("$filesRoot/")) {
        val allowed = strip.startsWith("$filesRoot/workspaces/") ||
            strip.startsWith("$filesRoot/${me.rerere.rikkahub.data.files.FileFolders.UPLOAD}/") ||
            strip.startsWith("$filesRoot/${me.rerere.rikkahub.data.files.FileFolders.TOOL_OUTPUTS}/") ||
            strip.startsWith("$filesRoot/${me.rerere.rikkahub.data.files.FileFolders.SKILLS}/")
        return if (allowed) listOf(strip) else emptyList()
    }

    // ② 具名挂载区 (沙箱形态): 与 bindMounts 同源映射 (绝不再拼接 cwd)
    val mounts = listOf(
        "/upload" to me.rerere.rikkahub.data.files.FileFolders.UPLOAD,
        "/tool_outputs" to me.rerere.rikkahub.data.files.FileFolders.TOOL_OUTPUTS,
        "/skills" to me.rerere.rikkahub.data.files.FileFolders.SKILLS,
    )
    for ((prefix, dir) in mounts) {
        if (strip == prefix || strip.startsWith("$prefix/")) {
            return listOf("$filesRoot/$dir" + strip.removePrefix(prefix))
        }
    }

    // ③ 工作区族: /workspace 前缀 / host 锚点形态 / 相对路径
    val hostRel = me.rerere.rikkahub.utils.normalizeHostWorkspacePath(strip)?.removePrefix("/workspace/")
    val isWorkspaceFamily = hostRel != null || !strip.startsWith("/") || strip.startsWith("/workspace/")
    if (!isWorkspaceFamily) return emptyList()
    val rel = hostRel ?: run {
        val base = strip.trimStart('/').removePrefix("workspace/").removePrefix("/workspace/")
        when {
            cwd.isNullOrEmpty() -> base
            // v4.8.73: 双前缀自愈 — base 已含 cwd 前缀时不再叠加。
            base == cwd || base.startsWith("$cwd/") -> base
            else -> "$cwd/$base"
        }
    }
    return buildList {
        add(rel)
        if (!cwd.isNullOrEmpty()) {
            if (rel.startsWith("$cwd/")) add(rel.removePrefix("$cwd/")) else add("$cwd/$rel")
        }
    }.distinct().map { "$wsRoot/$it" }
}

/**
 * 规范渲染地址 — **发出前逐候选验存 (isFile && canRead)，全不中返回 null**。
 * v4.8.142 撤除两处"带病兜底": ①末行"全不中仍返回主形态"(死文件也发地址 —
 * shell 输出扫描器从源码字符串提取的假路径就曾据此发出死链); ②同名 walkTopDown
 * 搜索(会命中"存在的错文件" — 用户定版: 宁可没图, 不给死链/错链)。
 */
internal fun buildRenderUrl(workspaceId: String, path: String, cwd: String? = null): String? {
    for (candidate in renderUrlCandidates(workspaceId, path, cwd)) {
        val f = java.io.File(candidate)
        if (f.isFile && f.canRead()) return encodeMarkdownUrl("file://$candidate")
    }
    return null
}

/** markdown 行构建 (纯): (原始路径, 规范url) 对列表 → header+lines; 空 → null。 */
internal fun renderMarkdownOf(entries: List<Pair<String, String>>): String? {
    if (entries.isEmpty()) return null
    val lines = entries.map { (path, url) ->
        val rawName = path.substringAfterLast('/')
        val alt = rawName.substringBeforeLast('.')
            .replace(Regex("[\\[\\]()#`\n\r]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "img" }
        "![$alt]($url)"
    }
    return "以下是已生成图片的可直接输出渲染地址，把下面每一行原样复述到回复正文即可在气泡内显示图片:\n" +
        lines.joinToString("\n")
}

/** v4.8.142: 渲染载荷 — urls/markdown 仅含验存通过者; skipped = 被丢弃数 (供调用方出提示)。 */
internal data class RenderPayload(
    val urls: List<String>,
    val markdown: String?,
    val skipped: Int,
)

/** 单点构建 (全部产图工具统一入口): 验存→地址→markdown→丢弃计数, 一次完成。 */
internal fun buildRenderPayload(workspaceId: String, paths: List<String>, cwd: String?): RenderPayload {
    val entries = paths.mapNotNull { p -> buildRenderUrl(workspaceId, p, cwd)?.let { p to it } }
    return RenderPayload(
        urls = entries.map { it.second },
        markdown = renderMarkdownOf(entries),
        skipped = paths.size - entries.size,
    )
}

/** 丢弃提示文案 (模型可见面): 说清"没图"与去向, 不输出任何死链话术。 */
internal fun renderSkippedNote(n: Int): String =
    "$n image path(s) got no render_urls/render_markdown — not under /workspace, " +
        "/upload, /tool_outputs or /skills, or the file does not exist. " +
        "No address was emitted (dead links are never generated); " +
        "copy the file into the workspace first if it must be displayed."

/**
 * 从工具输出 JSON 提取 render_urls (显示侧唯一提取入口; v4.8.115 前为
 * WorkspaceToolUIs 私有副本 renderUrlsOf)。防御性解析: 结构异常返回空列表。
 */
internal fun extractRenderUrls(content: JsonElement?): List<String> =
    runCatching {
        ((content as? JsonObject)?.get("render_urls") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }.getOrNull().orEmpty()
