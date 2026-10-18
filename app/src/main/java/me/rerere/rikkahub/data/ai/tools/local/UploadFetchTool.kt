/* 【域 C·工具系统】 — 上传码直取 | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.data.ai.tools.local

/*
 * v4.8.90: upload_fetch —— 上传码直取文件的工具（顶层注入，与 read_image 同条件）。
 *
 * 解决的真实痛点：上传统统堆在 `/upload` 下且物理名是随机 ID，模型要么找不到、
 * 要么翻半天。现在每个附件在 Prompt 里都带 `code="XXXXXXXX"`（见 DocumentAsPromptTransformer），
 * 模型拿码调本工具即可拿到**精确路径**，再交给 workspace_read_file / read_image 处理。
 *
 * 纯文件系统实现（不依赖 DB）：码本身就是相对路径的确定性派生，扫目录算码即可命中；
 * 未命中时返回最近上传列表兜底（让模型能把候选报给用户确认），绝不静默乱猜。
 *
 * v4.8.189 (L-A3): 码扫描/列表/路径派生一律经 UploadRef 单一解析器 (撤本文件直扫逻辑)。
 */

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.UploadCodes
import me.rerere.rikkahub.data.files.UploadRef
import java.io.File

fun createUploadFetchTool(filesRoot: File): Tool = Tool(
    name = "upload_fetch",
    description = "Resolve a user-uploaded file by its **upload code** and return its exact sandbox path. " +
        "New uploads use a time code (MMddHHmm+seq, e.g. `1003002901` — 10+ digits) which is also the " +
        "filename prefix under /upload; legacy files use an 8-char code. The code is shown as " +
        "code=\"...\" in <UploadFile> tags. Use this instead of browsing /upload blindly. " +
        "Also lists the most recent uploads when nothing matches. Read-only.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "上传码（新文件为时间码如 1003002901，即文件名前缀；历史文件为 8 位码）。与 query 二选一。")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "物理文件名片段（不知道码时的兜底；不区分大小写）")
                })
            },
            required = emptyList<String>(),
        )
    },
    needsApproval = { false },
    execute = { args ->
        val params = args.jsonObject
        val codeRaw = params["code"]?.jsonPrimitive?.contentOrNull
        val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()

        val uploadDir = filesRoot.resolve(FileFolders.UPLOAD)

        fun describe(f: File) = buildJsonObject {
            val ref = UploadRef.fromPhysicalName(f.name)
            put("code", ref.code)
            put("name", f.name)
            put("path", ref.sandboxPath)
            put("size_bytes", f.length())
            put("modified_ms", f.lastModified())
        }
        fun kotlinx.serialization.json.JsonObjectBuilder.recentList() {
            put("recent", buildJsonArray { UploadRef.listRecent(uploadDir).forEach { addJsonObject { describe(it) } } })
        }

        when {
            // ① 按码直取（主用法）
            !codeRaw.isNullOrBlank() -> {
                val norm = UploadCodes.normalize(codeRaw)
                val matches = UploadRef.findFilesByCode(uploadDir, codeRaw)
                when {
                    matches.size == 1 -> listOf(UIMessagePart.Text(buildJsonObject {
                        put("ok", true)
                        put("resolved_by", "code")
                        put("file", describe(matches.first()))
                        put("hint", "用 workspace_read_file 传 path 读取内容；图片类可交 read_image。")
                    }.toString()))
                    matches.size > 1 -> listOf(UIMessagePart.Text(buildJsonObject {
                        put("ok", false)
                        put("error", "该码命中 ${matches.size} 个文件（极罕见碰撞），请按 name 区分。")
                        put("candidates", buildJsonArray { matches.forEach { addJsonObject { put("name", it.name); put("path", UploadRef.fromPhysicalName(it.name).sandboxPath) } } })
                    }.toString()))
                    else -> listOf(UIMessagePart.Text(buildJsonObject {
                        put("ok", false)
                        put("error", "没有文件的码是 $norm" + (if (UploadCodes.hasCodeFormat(codeRaw)) "。" else "（注意：这不是合法上传码格式）。") +
                            "可改用 query 按文件名找，或见下方最近上传列表（把候选报给用户确认）。")
                        recentList()
                    }.toString()))
                }
            }
            // ② 物理名兜底
            !query.isNullOrBlank() -> {
                val matches = (uploadDir.listFiles() ?: emptyArray())
                    .filter { it.isFile && it.name.lowercase().contains(query) }
                when {
                    matches.isEmpty() -> listOf(UIMessagePart.Text(buildJsonObject {
                        put("ok", false)
                        put("error", "没有物理名包含「$query」的上传文件。上传码才是稳定引用，建议向用户索取。")
                        recentList()
                    }.toString()))
                    else -> listOf(UIMessagePart.Text(buildJsonObject {
                        put("ok", true)
                        put("resolved_by", "query")
                        put("files", buildJsonArray { matches.take(10).forEach { addJsonObject { describe(it) } } })
                        put("hint", "多个命中时优先选 modified_ms 最新的一条，或向用户确认 code。")
                    }.toString()))
                }
            }
            // ③ 什么都没给：返回最近上传，供模型向用户确认
            else -> listOf(UIMessagePart.Text(buildJsonObject {
                put("ok", false)
                put("error", "请传 code（上传码）或 query（文件名片段）。以下是最近上传的文件：")
                recent(this)
            }.toString()))
        }
    },
)
