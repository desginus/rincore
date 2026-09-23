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

private fun readImageError(message: String, fix: String? = null): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildString {
        append("Error: $message")
        if (fix != null) append("\nFix: $fix")
    }))

fun createReadImageTool(allowedRoot: File): Tool = Tool(
    name = "read_image",
    description = "读取会话文件目录中的图片原文件 (用于查看此前因超出图片预算被降级为占位文本的图片)。传入图片文件的完整路径, 返回图片本体。",
    systemPrompt = { _, _ ->
        "read_image 使用规则: 会话历史中出现 \"[图片已省略 (超出本请求图片预算): <路径>]\" 占位时, 该图片原文件已保存在会话文件目录中; 若当前推理确实依赖该图内容, 调用 read_image 传入占位中的原路径即可重新查看。不要为装饰性目的重读大量旧图 (会快速耗尽图片预算), 只读当前任务真正需要的。"
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
        val file = File(path.removePrefix("file://").removePrefix("file:"))
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
            me.rerere.ai.ui.UIMessagePart.Text("[图片] ${file.name} (经 read_image 重新加载)"),
            me.rerere.ai.ui.UIMessagePart.Image(url = "file://$canonical"),
        )
    },
)
