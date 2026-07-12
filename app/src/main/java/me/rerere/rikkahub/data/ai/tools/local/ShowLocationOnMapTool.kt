package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun showLocationOnMapTool(
    context: Context,
): Tool = Tool(
    name = "show_location_on_map",
    description = "Open the user's default maps app showing the given place / address / coordinates. Useful for \"where is X?\", \"show me the way to X\", \"find Y on the map\".",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Place name, address, or 'lat,lng' coordinate pair.")
                })
            },
            required = listOf("query"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val query = args.jsonObject["query"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_query", "query is required")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = "geo:0,0?q=${Uri.encode(query)}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        fireIntent(
            context, intent, action = "show_location_on_map",
            summary = "Map: $query",
        )
    },
)

private fun fireIntent(
    context: Context,
    intent: Intent,
    action: String,
    summary: String,
): List<UIMessagePart> {
    return try {
        if (intent.resolveActivity(context.packageManager) == null) {
            return listOf(UIMessagePart.Text(buildJsonObject {
                put("intent_fired", false)
                put("action", action)
                put("ok", false)
                put("error", "no_handler")
                put("summary", summary)
                put("detail", "no installed app can handle this intent")
            }.toString()))
        }
        context.startActivity(intent)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("intent_fired", true)
            put("action", action)
            put("ok", true)
            put("summary", summary)
        }.toString()))
    } catch (t: Throwable) {
        listOf(UIMessagePart.Text(buildJsonObject {
            put("intent_fired", false)
            put("action", action)
            put("ok", false)
            put("error", t::class.simpleName ?: "exception")
            put("summary", summary)
            put("detail", t.message.orEmpty())
        }.toString()))
    }
}

private fun err(code: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("ok", false)
        put("error", code)
        put("detail", detail)
    }.toString()))
