package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val TAG_LOC = "LocationTool"

private fun errorPayload(message: String, recovery: String? = null): JsonObject =
    buildJsonObject {
        put("error", message)
        if (recovery != null) put("recovery", recovery)
    }

private fun JsonObjectBuilder.putLocation(loc: Location, providerName: String) {
    put("latitude", loc.latitude)
    put("longitude", loc.longitude)
    put("accuracy_m", loc.accuracy)
    if (loc.hasAltitude()) put("altitude", loc.altitude)
    if (loc.hasSpeed()) put("speed", loc.speed)
    if (loc.hasBearing()) put("bearing", loc.bearing)
    put("provider", providerName)
    put("timestamp_ms", loc.time)
}

fun locationTool(context: Context): Tool = Tool(
    name = "get_location",
    description = "获取设备当前 GPS 位置（经纬度、精度）。需要定位权限且定位服务已开启。支持高精度/均衡/低功耗三种模式，优先使用缓存热定位。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("accuracy", buildJsonObject {
                    put("type", "string")
                    put("description", "Location accuracy preference: high, balanced (default), or low")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in milliseconds (default 30000, min 1000, max 60000). After timeout, falls back to most recent cached fix.")
                })
            }
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val accuracyStr = params["accuracy"]?.jsonPrimitive?.contentOrNull ?: "balanced"
        val priority = when (accuracyStr) {
            "high" -> Priority.PRIORITY_HIGH_ACCURACY
            "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            "low" -> Priority.PRIORITY_LOW_POWER
            else -> null
        }
        val timeoutMs = (params["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 30000)
            .coerceIn(1000, 60000)

        val payload: JsonObject = when {
            priority == null -> errorPayload("unknown accuracy: $accuracyStr")
            !PermissionHelper.hasRuntime(context, listOf(Manifest.permission.ACCESS_FINE_LOCATION)) ->
                errorPayload("permission ACCESS_FINE_LOCATION not granted")
            else -> {
                val lm = context.getSystemService(LocationManager::class.java)
                if (lm == null) {
                    errorPayload("location services disabled")
                } else {
                    val gpsEnabled = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Throwable) { false }
                    val networkEnabled = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Throwable) { false }
                    if (!gpsEnabled && !networkEnabled) {
                        errorPayload("location services disabled", "Ask the user to enable Location in Settings → Location.")
                    } else {
                        val gmsAvailable = try {
                            GoogleApiAvailability.getInstance()
                                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
                        } catch (t: Throwable) { Log.w(TAG_LOC, "GMS check failed", t); false }

                        // Try cached fix first
                        val cachedNow: Location? = try {
                            if (gmsAvailable) LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
                            else null
                        } catch (t: Throwable) { Log.w(TAG_LOC, "fused lastLocation failed", t); null }
                            ?: try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: SecurityException) { null }
                            ?: try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: SecurityException) { null }
                            ?: try { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (_: SecurityException) { null }

                        val cachedAgeMs = cachedNow?.let { System.currentTimeMillis() - it.time }
                        if (cachedNow != null && cachedAgeMs != null && cachedAgeMs < 120_000) {
                            Log.i(TAG_LOC, "returning fresh cached fix age=${cachedAgeMs}ms provider=${cachedNow.provider}")
                            buildJsonObject {
                                putLocation(cachedNow, cachedNow.provider ?: "cached")
                                put("cached", true)
                                put("age_ms", cachedAgeMs)
                            }
                        } else if (gmsAvailable) {
                            val fresh: Location? = try {
                                val client = LocationServices.getFusedLocationProviderClient(context)
                                withTimeoutOrNull(timeoutMs.toLong()) {
                                    client.getCurrentLocation(priority, null).await()
                                }
                            } catch (t: Throwable) { Log.w(TAG_LOC, "getCurrentLocation failed", t); null }
                            when {
                                fresh != null -> buildJsonObject { putLocation(fresh, "fused") }
                                cachedNow != null -> buildJsonObject {
                                    putLocation(cachedNow, cachedNow.provider ?: "cached")
                                    put("cached", true)
                                    put("age_ms", cachedAgeMs ?: -1L)
                                    put("note", "fresh fix timed out after ${timeoutMs}ms; returning last known")
                                }
                                else -> errorPayload("no fix yet", "Try moving near a window/outdoors, or open a maps app once to seed the location cache.")
                            }
                        } else {
                            if (cachedNow != null) buildJsonObject {
                                putLocation(cachedNow, cachedNow.provider ?: "lm")
                                put("cached", true)
                                if (cachedAgeMs != null) put("age_ms", cachedAgeMs)
                            }
                            else errorPayload("no fix available", "Google Play Services unavailable and no cached fix. Enable Wi-Fi for network-based location.")
                        }
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
