package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.alarm.AlarmRepository
import me.rerere.rikkahub.data.alarm.AlarmScheduler
import me.rerere.rikkahub.data.db.entity.AlarmEntity
import org.koin.java.KoinJavaComponent
import java.time.ZonedDateTime
import java.util.UUID

private fun repo(): AlarmRepository = KoinJavaComponent.get(AlarmRepository::class.java)
private fun scheduler(): AlarmScheduler = KoinJavaComponent.get(AlarmScheduler::class.java)

fun alarmCreateTool(): Tool = Tool(
    name = "alarm_create",
    description = """
        Create an alarm. Supports single ("once") and weekly-repeating ("weekly") alarms.
        For "once": provide a ISO-8601 time string (e.g. "2026-07-10T08:00:00").
        For "weekly": provide hour (0-23), minute (0-59), and daysOfWeek (1=Mon .. 7=Sun).
    """.trimIndent().replace("\n", " "),
    needsApproval = { false }, // v3.6.13: 默认直接执行
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("label", buildJsonObject {
                    put("type", "string")
                    put("description", "Alarm label / title shown when it fires.")
                })
                put("note", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional note or message.")
                })
                put("schedule_type", buildJsonObject {
                    put("type", "string")
                    put("enum", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("once")); add(kotlinx.serialization.json.JsonPrimitive("weekly")) })
                    put("description", "once = single alarm, weekly = repeats on selected days.")
                })
                put("time", buildJsonObject {
                    put("type", "string")
                    put("description", "ISO-8601 date-time for 'once' (e.g. '2026-07-10T08:00:00'). Required for once.")
                })
                put("hour", buildJsonObject {
                    put("type", "integer")
                    put("description", "Hour (0-23) for 'weekly'. Required for weekly.")
                })
                put("minute", buildJsonObject {
                    put("type", "integer")
                    put("description", "Minute (0-59) for 'weekly'. Required for weekly.")
                })
                put("days_of_week", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "integer") })
                    put("description", "Days (1=Mon .. 7=Sun) for 'weekly'. Required for weekly.")
                })
                put("vibrate", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to vibrate when the alarm fires. Default true.")
                })
            },
            required = listOf("label", "schedule_type")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val label = params["label"]?.jsonPrimitive?.contentOrNull ?: "Alarm"
        val note = params["note"]?.jsonPrimitive?.contentOrNull
        val scheduleType = params["schedule_type"]?.jsonPrimitive?.contentOrNull ?: "once"
        val vibrate = params["vibrate"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val timezone = java.time.ZoneId.systemDefault().id

        val alarm: AlarmEntity
        val nextFireAtMs: Long?

        if (scheduleType == "once") {
            val time = params["time"]?.jsonPrimitive?.contentOrNull
            if (time == null) {
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "MISSING_TIME"); put("message", "time is required for 'once' alarms.")
                }.toString()))
            }
            val fireTime = try { ZonedDateTime.parse(time).withNano(0) } catch (e: Exception) {
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "INVALID_TIME"); put("message", "Cannot parse time: ${e.message}")
                }.toString()))
            }
            nextFireAtMs = if (fireTime > ZonedDateTime.now()) fireTime.toInstant().toEpochMilli() else null
            alarm = AlarmEntity(
                id = UUID.randomUUID().toString(),
                label = label,
                note = note,
                scheduleType = "once",
                time = time,
                timezone = timezone,
                vibrate = vibrate,
                nextFireAtMs = nextFireAtMs,
            )
        } else if (scheduleType == "weekly") {
            val hour = params["hour"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val minute = params["minute"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val daysRaw = params["days_of_week"]

            if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "INVALID_TIME"); put("message", "hour (0-23) and minute (0-59) are required for weekly.")
                }.toString()))
            }
            val days = daysRaw?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "MISSING_DAYS"); put("message", "days_of_week is required for weekly alarms.")
                }.toString()))
            if (days.isEmpty() || days.any { it !in 1..7 }) {
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "INVALID_DAYS"); put("message", "days_of_week must contain values 1-7 (Mon=1, Sun=7).")
                }.toString()))
            }

            alarm = AlarmEntity(
                id = UUID.randomUUID().toString(),
                label = label,
                note = note,
                scheduleType = "weekly",
                hour = hour,
                minute = minute,
                daysOfWeek = days.joinToString(","),
                timezone = timezone,
                vibrate = vibrate,
            )
            nextFireAtMs = scheduler().calculateNextFireAt(alarm)
        } else {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "INVALID_TYPE"); put("message", "schedule_type must be 'once' or 'weekly'.")
            }.toString()))
        }

        val savedAlarm = alarm.copy(nextFireAtMs = nextFireAtMs)
        repo().upsert(savedAlarm)
        if (nextFireAtMs != null) {
            scheduler().schedule(savedAlarm)
        }

        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("id", savedAlarm.id)
            put("label", savedAlarm.label)
            put("schedule_type", savedAlarm.scheduleType)
            put("next_fire_at", nextFireAtMs?.let {
                kotlinx.serialization.json.JsonPrimitive(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.of(timezone)).toString())
            } ?: kotlinx.serialization.json.JsonNull)
            put("timezone", timezone)
        }.toString()))
    }
)

fun alarmListTool(): Tool = Tool(
    name = "alarm_list",
    description = "List all alarms created in this app.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val alarms = kotlinx.coroutines.runBlocking { repo().getAllOnce() }

        listOf(UIMessagePart.Text(buildJsonObject {
            put("count", alarms.size)
            put("alarms", kotlinx.serialization.json.buildJsonArray {
                alarms.forEach { alarm ->
                    add(buildJsonObject {
                        put("id", alarm.id)
                        put("label", alarm.label)
                        put("schedule_type", alarm.scheduleType)
                        put("time", alarm.time ?: "")
                        put("hour", alarm.hour ?: -1)
                        put("minute", alarm.minute ?: -1)
                        put("days_of_week", alarm.daysOfWeek ?: "")
                        put("enabled", alarm.enabled)
                        put("next_fire_at", alarm.nextFireAtMs?.let {
                            java.time.Instant.ofEpochMilli(it).toString()
                        } ?: "")
                    })
                }
            })
        }.toString()))
    }
)

fun alarmDeleteTool(): Tool = Tool(
    name = "alarm_delete",
    description = "Delete an alarm by its ID. Requires user approval.",
    needsApproval = { false }, // v3.6.13: 默认直接执行
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Alarm ID returned by alarm_create or alarm_list.")
                })
            },
            required = listOf("id")
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
        if (id == null) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "MISSING_ID"); put("message", "id is required.")
            }.toString()))
        }
        val alarm = repo().getById(id)
        if (alarm == null) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NOT_FOUND"); put("message", "No alarm with id $id.")
            }.toString()))
        }
        scheduler().cancel(id)
        repo().deleteById(id)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("id", id)
        }.toString()))
    }
)
