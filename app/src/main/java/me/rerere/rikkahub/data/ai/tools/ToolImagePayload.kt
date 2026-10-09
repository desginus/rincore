/* 【域 E·模型工具】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.data.ai.tools


/* ───【自研】ToolImagePayload.kt — v4.8.115 工具产图渲染地址单一来源
 * 用户定版 (2026-10-09): "渲染地址全部统一, 不要一个地方一个转换"。
 * 本文件是工具产图渲染地址的唯一权威:
 *   产出 (模型侧)  = buildRenderUrl / buildRenderMarkdown
 *   提取 (显示侧)  = extractRenderUrls
 *   编码 (规范形态) = encodeMarkdownUrl
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
 * 规范渲染地址 (v4.8.115: 返回 percent 编码形态 — 与 render_markdown 同一形态,
 * 消灭"裸路径/编码串"双形态矛盾)。CWD 专一空间语义不变 (v4.5.27):
 * 生成宿主完整路径时拼入 cwd 段, resolver 侧无需感知 cwd; host 字面形态输入
 * (含 workspaces/<UUID>/files/) 直接取其后段, 不再二次拼 cwd。
 */
internal fun buildRenderUrl(workspaceId: String, path: String, cwd: String? = null): String {
    val hostRel = me.rerere.rikkahub.utils.normalizeHostWorkspacePath(path)?.removePrefix("/workspace/")
    val rel = hostRel ?: run {
        val base = path.trimStart('/').removePrefix("workspace/").removePrefix("/workspace/")
        when {
            cwd.isNullOrEmpty() -> base
            // v4.8.73: 双前缀自愈 — base 已含 cwd 前缀 (模型把 host 渲染地址换算回
            // /workspace/<cwd>/... 形态再传参) 时不再叠加; 叠加 = 地址解析全失败。
            base == cwd || base.startsWith("$cwd/") -> base
            else -> "$cwd/$base"
        }
    }
    val pkg = me.rerere.rikkahub.BuildConfig.APPLICATION_ID
    return encodeMarkdownUrl("file:///data/data/$pkg/files/workspaces/$workspaceId/files/$rel")
}

/**
 * 「可直接输出后渲染」的 markdown 行 — 模型把 render_markdown 原样复述进回复正文,
 * 图片即在气泡内显示 (配合 XssSafeLinks 的 file:// 私有目录放行)。
 * 无图片返回 null (零注入)。
 */
internal fun buildRenderMarkdown(workspaceId: String, paths: List<String>, cwd: String?): String? {
    if (paths.isEmpty()) return null
    val lines = paths.map { path ->
        val rawName = path.substringAfterLast('/')
        val alt = rawName.substringBeforeLast('.')
            .replace(Regex("[\\[\\]()#`\\n\\r]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "img" }
        "![$alt](${buildRenderUrl(workspaceId, path, cwd)})"
    }
    return "以下是已生成图片的可直接输出渲染地址，把下面每一行原样复述到回复正文即可在气泡内显示图片:\n" +
        lines.joinToString("\n")
}

/**
 * 从工具输出 JSON 提取 render_urls (显示侧唯一提取入口; v4.8.115 前为
 * WorkspaceToolUIs 私有副本 renderUrlsOf)。防御性解析: 结构异常返回空列表。
 */
internal fun extractRenderUrls(content: JsonElement?): List<String> =
    runCatching {
        ((content as? JsonObject)?.get("render_urls") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }.getOrNull().orEmpty()
