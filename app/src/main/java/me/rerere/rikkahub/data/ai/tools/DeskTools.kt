package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

object DeskTools {

    fun editFile(workspaceRoot: File) = Tool(
        name = "edit_file",
        description = "编辑工作区文件。提供文件路径和新内容，自动写入并返回 diff。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("file_path", buildJsonObject {
                        put("type", "string")
                        put("description", "相对于工作区根目录的文件路径")
                    })
                    put("new_content", buildJsonObject {
                        put("type", "string")
                        put("description", "修改后的新内容")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "修改说明")
                    })
                },
                required = listOf("file_path", "new_content")
            )
        },
        execute = { input ->
            val filePath = input.jsonObject["file_path"]?.jsonPrimitive?.content ?: ""
            val newContent = input.jsonObject["new_content"]?.jsonPrimitive?.content ?: ""
            val desc = input.jsonObject["description"]?.jsonPrimitive?.content ?: ""
            val file = File(workspaceRoot, filePath)
            file.parentFile?.mkdirs()
            val oldContent = if (file.exists()) file.readText() else ""
            file.writeText(newContent)
            val diff = if (oldContent.isEmpty()) newContent
            else buildString {
                appendLine("--- $filePath")
                appendLine("+++ $filePath")
                oldContent.lines().forEach { appendLine("-$it") }
                newContent.lines().forEach { appendLine("+$it") }
            }
            listOf(UIMessagePart.Text("📝 $filePath${if (desc.isNotEmpty()) ": $desc" else ""}\n```diff\n$diff\n```"))
        }
    )

    fun readFile(workspaceRoot: File) = Tool(
        name = "read_file",
        description = "读取工作区文件内容。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("file_path", buildJsonObject {
                        put("type", "string")
                        put("description", "文件路径")
                    })
                },
                required = listOf("file_path")
            )
        },
        execute = { input ->
            val path = input.jsonObject["file_path"]?.jsonPrimitive?.content ?: ""
            val file = File(workspaceRoot, path)
            if (!file.exists()) listOf(UIMessagePart.Text("文件不存在: $path"))
            else listOf(UIMessagePart.Text("📄 $path (${file.readText().lines().size}行)\n\n```\n${file.readText()}\n```"))
        }
    )

    fun listFiles(workspaceRoot: File) = Tool(
        name = "list_files",
        description = "列出工作区目录。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "子目录路径，默认根目录")
                    })
                }
            )
        },
        execute = { input ->
            val sub = input.jsonObject["path"]?.jsonPrimitive?.content ?: ""
            val dir = if (sub.isEmpty()) workspaceRoot else File(workspaceRoot, sub)
            if (!dir.exists() || !dir.isDirectory) return@execute listOf(UIMessagePart.Text("目录不存在: $sub"))
            val result = buildString {
                appendLine("📁 ${sub.ifEmpty { "/" }}")
                dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    val icon = if (f.isDirectory) "📁" else "📄"
                    val size = if (f.isFile) " (${f.length() / 1024}KB)" else ""
                    appendLine("  $icon ${f.name}$size")
                }
            }
            listOf(UIMessagePart.Text(result))
        }
    )
}
