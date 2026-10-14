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
): Tool = Tool(
    name = "read_image",
    description = "读取会话文件目录中的图片原文件 (用于查看此前因超出图片预算被降级为占位文本的图片)。传入图片文件的完整路径, 返回图片本体。",
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
        if (path.startsWith("/workspace/") && workspaceRepository != null && workspaceId.isNotBlank()) {
            val ext0 = path.substringAfterLast('.', "").lowercase()
            if (ext0 !in IMAGE_EXTENSIONS) return@Tool readImageError("Invalid: 非图片文件 ($path)", "仅支持 png/jpg/jpeg/webp/gif")
            val bytes = runCatching { workspaceRepository.readRootfsBuffer(workspaceId, path, null).toByteArray() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) return@Tool readImageError("Invalid: 文件不存在或为空: $path", "路径可能已被清理, 请求用户重新提供该图")
            if (bytes.size.toLong() > READ_IMAGE_MAX_BYTES) return@Tool readImageError("Error: 文件过大 (${bytes.size / 1048576}MiB > 20MiB 上限)")
            val uris = org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.files.FilesManager>()
                .createChatFilesByByteArrays(listOf(bytes))
            return@Tool listOf(
                UIMessagePart.Text("[图片] $path (经 read_image 重新加载)"),
                UIMessagePart.Image(url = uris.first().toString()),
            )
        }
        val decoded = decodeFsPath(path)
        val file = File(decoded)
        val canonical = runCatching { file.canonicalPath }.getOrElse { return@Tool readImageError("Invalid: 路径无法解析: $path") }
        val root = runCatching { allowedRoot.canonicalPath }.getOrElse { allowedRoot.path }
        if (canonical != root && !canonical.startsWith(root + File.separator)) {
            return@Tool readImageError(
                "Invalid: 路径不在会话文件目录内",
                "仅允许读取会话文件目录 ($root) 下的图片, 例如历史占位中标注的 upload/tool_outputs 文件",
            )
        }
        if (!file.exists() || file.length() == 0L) {
            return@Tool readImageError("Invalid: 文件不存在或为空: ${file.name}", "路径可能已被清理, 请求用户重新提供该图")
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
