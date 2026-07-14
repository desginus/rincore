package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Additional system-intent tools beyond show_location_on_map.
 * Each dispatches a system intent — the user reviews + finalises in the destination app.
 */

fun shareTool(context: Context): Tool = Tool(
    name = "share",
    description = "Open the system share sheet so the user can send text or a URL to another app (messages, email, etc.). At least one of text or url must be provided.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text content to share")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "URL to share")
                })
                put("subject", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional subject (e.g., for email)")
                })
            },
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val text = params["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        val url = params["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        val subject = params["subject"]?.jsonPrimitive?.contentOrNull

        if (text == null && url == null) {
            return@Tool err("missing_content", "provide at least one of text or url")
        }

        val combined = listOfNotNull(text, url).joinToString("\n")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, combined)
            subject?.takeIf { it.isNotEmpty() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        val chooser = Intent.createChooser(intent, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)

        val payload = buildJsonObject { put("success", true) }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

fun createCalendarEventTool(context: Context): Tool = Tool(
    name = "create_calendar_event",
    description = "Open the system Calendar app pre-filled with a new event the user can review and save. Useful for \"schedule a meeting\", \"add an event for\", \"remind me on date X\". The user finalises in their default Calendar app — no event is saved without their explicit save action.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Title of the event.")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional event description / notes.")
                })
                put("location", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional location for the event.")
                })
                put("start_time_unix_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional start time in unix ms. If omitted, the calendar app picks the user's current time.")
                })
                put("end_time_unix_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional end time in unix ms.")
                })
                put("all_day", buildJsonObject {
                    put("type", "boolean")
                    put("description", "True for an all-day event. Defaults to false.")
                })
            },
            required = listOf("title"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val params = args.jsonObject
        val title = params["title"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_title", "title is required")
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            params["description"]?.jsonPrimitive?.contentOrNull?.let {
                putExtra(CalendarContract.Events.DESCRIPTION, it)
            }
            params["location"]?.jsonPrimitive?.contentOrNull?.let {
                putExtra(CalendarContract.Events.EVENT_LOCATION, it)
            }
            params["start_time_unix_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it)
            }
            params["end_time_unix_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let {
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it)
            }
            params["all_day"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()?.let {
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, it)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        fireIntent(context, intent, action = "create_calendar_event", summary = "Calendar: $title")
    },
)

// ── Internal helpers (shared by ShowLocationOnMapTool) ──

internal fun fireIntent(
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

internal fun err(code: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("ok", false)
        put("error", code)
        put("detail", detail)
    }.toString()))
