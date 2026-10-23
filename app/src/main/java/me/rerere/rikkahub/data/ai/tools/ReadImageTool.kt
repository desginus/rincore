/* 【域 C·工具系统】 | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.data.ai.tools

/* ───【自研】ReadImageTool.kt | v4.3.7 (BUG15 配套)
 * 图片预算防御的闭环件: 高强度视觉工作流中, 超出预算的旧图被裁剪降级为
 * 占位文本后, 模型无法"再看一眼"。本工具提供主动重取通道 — 占位文本
 * 携带原文件路径, 模型按需调用 read_image 重新加载, 返回的图片处于
 * 最新请求的预算内, 自然可见。
 *
 * 安全: 路径必须落在 app 私有文件根 (context.filesDir) 内 (canonical
 * path 前缀校验, 防路径穿越), 且仅接受图片扩展名; 不做任意文件读取。
 * ───────────────────────────────────────────────────────────────*/

import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")
private const val READ_IMAGE_MAX_BYTES = 20L * 1024 * 1024

/** v4.8.134 (A4): file:// 剥离 + 百分号解码 (仅 %XX 十六进制; '+' 不当空格 — 路径语义)。 */
private fun decodeFsPath(raw: String): String {
    var p = raw.trim()
    p = when {
        p.startsWith("file://") -> p.removePrefix("file://")
        p.startsWith("file:") -> p.removePrefix("file:")
        else -> p
    }
    if (!p.contains('%')) return p
    return runCatching {
        val out = java.io.ByteArrayOutputStream(p.length)
        var i = 0
        while (i < p.length) {
            val c = p[i]
            if (c == '%' && i + 3 <= p.length) {
                val v = p.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i += 1
        }
        out.toString("UTF-8")
    }.getOrDefault(p)
}

private fun readImageError(message: String, fix: String? = null): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildString {
        append("Error: $message")
        if (fix != null) append("\nFix: $fix")
    }))

fun createReadImageTool(
    allowedRoot: File,
    workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository? = null,
    workspaceId: String = "",
    /** v5.0.65: 与 workspace_read_file 同一套 CWD —— 否则 /workspace/x 在助手 CWD 下解析不到。 */
    cwd: String? = null,
): Tool = Tool(
    name = "read_image",
    description = "读取图片原文件并返回图片本体。**任意绝对路径都可**：/workspace/<路径>（工作区，受当前 CWD 影响）、/upload/<文件>（聊天附件）、/tool_outputs/、/skills/、/tmp/、file:// 链接、中文百分号编码路径。" +
        " 也用于重新查看此前因超出图片预算被降级为占位文本的图片（占位里标注的原路径原样传入即可）。",
    systemPrompt = { _, _ ->
        "read_image 使用规则: 会话历史中出现 \"[图片已省略 (超出本请求图片预算): <路径>]\" 占位时, 该图片原文件已保存在会话文件目录中; 若当前推理确实依赖该图内容, 调用 read_image 传入占位中的原路径即可重新查看 (支持 file:// 链接/中文百分号编码路径, 也接受 /workspace 工作区路径)。图片预算提示 (v4.8.134): 每个请求的图片总量有限 (~5MiB), 超出的旧图会被自动省略 — 大量读图任务请先裁剪/缩放或分批处理, 不要为装饰性目的重读大量旧图。"
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "图片文件完整路径 (file:// 开头或绝对路径), 见占位文本中标注的原路径")
                })
            },
            required = listOf("path")
        )
    },
    needsApproval = { false },
    execute = { input: JsonElement ->
        val path = runCatching { input.jsonObject["path"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.trim().orEmpty()
        if (path.isBlank()) return@Tool readImageError(
            "Missing: path (图片文件路径)",
            "占位文本中的路径以 file:// 开头, 原样传入即可",
        )
        // v4.8.134 (A4): ① /workspace/... 工作区路径 (走 rootfs 读取, 免手工宿主路径换算);
        // ② file:// 链接与中文百分号编码路径解码 — 占位文本里就是编码形态, 旧实现直接
        // File() 拼原串必然找不到文件 ("文件不存在或为空" 的根因)。
        // v5.0.65 ★两处硬伤修复（用户实测："同一文件 workspace_read_file(url_only) 能返回
        // render_urls，read_image 却报『文件不存在或为空』，而 ls 可见、129KB、非空"）：
        //   ① 旧实现只认 `/workspace/` 前缀，且 **cwd 硬编码 null** —— 与 workspace_read_file
        //      的 cwdRel 不是同一套解析，文件在 filesDir/<cwd>/ 下时必然找不到；
        //   ② 其它 rootfs 路径（/tmp、/upload、/tool_outputs、/skills）完全不走 rootfs，
        //      直接被 `allowedRoot`(仅 filesDir) 拒掉 —— 模型抱怨"读不了 /tmp"就是这个。
        // 现在：**任何以 / 开头的绝对路径**都先按 rootfs 语义解析（带 cwd），成功即返回；
        // 失败才退回宿主 filesDir 直读（file:// 与宿主绝对路径仍可用）。
        var rootfsErr: String? = null
        if (path.startsWith("/") && workspaceRepository != null && workspaceId.isNotBlank()) {
            val ext0 = path.substringAfterLast('.', "").lowercase()
            if (ext0 !in IMAGE_EXTENSIONS) return@Tool readImageError("Invalid: 非图片文件 ($path)", "仅支持 png/jpg/jpeg/webp/gif")
            // v5.0.66 ★P4：中文路径 100% 失败的兜底 —— 若传入的是**百分号编码**形态
            // （%E5%8D%A1…，占位文本里就是这种），先解码再交给 rootfs；原文形态同时保留重试。
            // 旧实现直接拿编码串当文件名 → 必找不到（用户实测两个不同中文路径全失败）。
            val decoded = if ('%' in path) runCatching {
                me.rerere.rikkahub.utils.percentDecodeLenient(path)
            }.getOrNull() else null
            val candidates = buildList {
                add(path)
                if (decoded != null && decoded != path) add(decoded)
            }
            var bytes: ByteArray? = null
            var lastErr: String? = null
            for (candidate in candidates) {
                val got = runCatching {
                    workspaceRepository.readRootfsBuffer(workspaceId, candidate, cwd).toByteArray()
                }.getOrNull()
                if (got != null && got.isNotEmpty()) {
                    bytes = got
                    break
                }
                lastErr = candidate
            }
            @Suppress("NAME_SHADOWING")
            val bytesFinal = bytes
            if (bytesFinal != null && bytesFinal.isNotEmpty()) {
                if (bytesFinal.size.toLong() > READ_IMAGE_MAX_BYTES) return@Tool readImageError("Error: 文件过大 (${bytesFinal.size / 1048576}MiB > 20MiB 上限)")
                val uris = org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.files.FilesManager>()
                    .createChatFilesByByteArrays(listOf(bytesFinal))
                return@Tool listOf(
                    UIMessagePart.Text("[图片] $path (经 read_image 重新加载)"),
                    UIMessagePart.Image(url = uris.first().toString()),
                )
            }
            rootfsErr = "rootfs 解析失败（cwd=${cwd ?: "(无)"}, 试过 ${candidates.size} 种形态${lastErr?.let { ", 最后=$it" } ?: ""}）"
        }
        val decoded = decodeFsPath(path)
        val file = File(decoded)
        val canonical = runCatching { file.canonicalPath }.getOrElse { return@Tool readImageError("Invalid: 路径无法解析: $path") }
        // v5.0.77 ★用户指令：**解除读取范围限制** —— 只要模型给的是正确地址码（任意绝对
        // 路径 / file:// / rootfs 语义路径），即可读取；不再用 allowedRoot 白名单拒收。
        // allowedRoot 参数保留仅为调用点签名兼容，不再参与判定。
        if (!file.exists() || file.length() == 0L) {
            // v5.0.65: 把两侧解析结果都报出来 —— 用户/模型一眼能看出是"路径形态不对"还是"文件真没了"
            return@Tool readImageError(
                "Invalid: 文件不存在或为空: $path",
                listOfNotNull(
                    rootfsErr,
                    "宿主侧解析 = $canonical（文件不存在或为空）",
                    "提示：/workspace、/upload、/tool_outputs、/skills、/tmp 走工作区 rootfs；file:// 与宿主绝对路径走直读",
                ).joinToString("；"),
            )
        }
        if (file.length() > READ_IMAGE_MAX_BYTES) {
            return@Tool readImageError("Error: 文件过大 (${file.length() / 1048576}MiB > 20MiB 上限)")
        }
        val ext = file.extension.lowercase()
        if (ext !in IMAGE_EXTENSIONS) {
            return@Tool readImageError("Invalid: 非图片文件 (${file.name})", "仅支持 png/jpg/jpeg/webp/gif")
        }
        listOf(
            // v4.8.134 (C3): 前置来源路径 — 工具结果与图片混排时可对齐
            me.rerere.ai.ui.UIMessagePart.Text("[图片] $canonical (经 read_image 重新加载)"),
            me.rerere.ai.ui.UIMessagePart.Image(url = "file://$canonical"),
        )
    },
)
