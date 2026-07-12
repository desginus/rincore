package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.media.MediaScannerConnection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun mediaScannerTool(context: Context): Tool = Tool(
    name = "扫描媒体",
    description = "通知 Android 系统媒体扫描器刷新指定文件路径，使新文件出现在相册/音乐应用中。创建媒体文件后调用。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("paths", buildJsonObject {
                    put("type", "array")
                    put("description", "List of absolute file paths to scan")
                    put("items", buildJsonObject { put("type", "string") })
                })
            },
            required = listOf("paths")
        )
    },
    execute = {
        val params = it.jsonObject
        val pathsArray: JsonArray = (params["paths"] as? JsonArray)
            ?: params["paths"]?.jsonArray
            ?: error("paths is required")
        val paths = pathsArray.mapNotNull { entry -> entry.jsonPrimitive.contentOrNull }
            .filter { p -> p.isNotEmpty() }
        if (paths.isEmpty()) {
            return@Tool listOf(
                UIMessagePart.Text(buildJsonObject { put("error", "paths must not be empty") }.toString())
            )
        }
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("scanned", paths.size)
        }.toString()))
    }
)
