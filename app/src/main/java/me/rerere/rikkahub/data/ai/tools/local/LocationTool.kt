package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume

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

/**
 * 通过 LocationManager.requestLocationUpdates 强制触发 GPS 硬件获取实时定位。
 *
 * getCurrentLocation 是被动 API, 不会主动启动 GPS 接收器;
 * requestLocationUpdates 会强制激活 GPS 硬件进行卫星锁定。
 */
@SuppressLint("MissingPermission")
private suspend fun requestFreshFixViaLocationManager(
    lm: LocationManager,
    timeoutMs: Long
): Location? = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine<Location?> { cont ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (cont.isActive) {
                    cont.resume(location)
                }
            }
        }

        val provider = if (try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Throwable) { false }) {
            LocationManager.GPS_PROVIDER
        } else if (try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Throwable) { false }) {
            LocationManager.NETWORK_PROVIDER
        } else {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        Log.i(TAG_LOC, "requestLocationUpdates provider=$provider timeout=${timeoutMs}ms")
        try {
            lm.requestLocationUpdates(
                provider,
                0L,
                0F,
                listener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.w(TAG_LOC, "requestLocationUpdates SecurityException", e)
            cont.resume(null)
            return@suspendCancellableCoroutine
        } catch (e: Throwable) {
            Log.w(TAG_LOC, "requestLocationUpdates failed", e)
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation {
            try { lm.removeUpdates(listener) } catch (_: Throwable) {}
        }
    }
}

fun locationTool(context: Context): Tool = Tool(
    name = "get_location",
    description = "获取设备当前实时 GPS 位置（经纬度、精度）。需要定位权限且定位服务已开启。" +
        "主动触发 GPS 硬件进行卫星锁定, 返回实时定位而非缓存。支持高精度/均衡/低功耗三种模式。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("accuracy", buildJsonObject {
                    put("type", "string")
                    put("description", "Location accuracy preference: high, balanced (default), or low")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in milliseconds (default 30000, min 30000, max 60000). " +
                        "GPS cold start needs 30-60 seconds. After timeout, falls back to cached fix.")
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
            .coerceIn(30000, 60000)  // 最小 30 秒: GPS 冷启动需要 30-60 秒

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

                        // ── 主动触发 GPS 硬件 ──
                        // requestLocationUpdates 强制激活 GPS 接收器进行卫星锁定
                        var fresh: Location? = requestFreshFixViaLocationManager(lm, timeoutMs.toLong())

                        // ── 备用: FusedLocation getCurrentLocation (被动, 但有时能拿到) ──
                        if (fresh == null && gmsAvailable) {
                            Log.i(TAG_LOC, "LocationManager failed, trying Fused getCurrentLocation")
                            fresh = try {
                                val client = LocationServices.getFusedLocationProviderClient(context)
                                withTimeoutOrNull(timeoutMs.toLong()) {
                                    client.getCurrentLocation(priority, null).await()
                                }
                            } catch (t: Throwable) { Log.w(TAG_LOC, "fused getCurrentLocation failed", t); null }
                        }

                        if (fresh != null) {
                            Log.i(TAG_LOC, "fresh fix obtained provider=${fresh.provider}")
                            buildJsonObject { putLocation(fresh, fresh.provider ?: "gps") }
                        } else {
                            // ── 降级: 缓存 ──
                            Log.w(TAG_LOC, "all fresh fix methods failed, falling back to cache")
                            val cached = try {
                                if (gmsAvailable) LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
                                else null
                            } catch (t: Throwable) { Log.w(TAG_LOC, "fused lastLocation failed", t); null }
                                ?: try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: SecurityException) { null }
                                ?: try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: SecurityException) { null }
                                ?: try { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (_: SecurityException) { null }

                            if (cached != null) {
                                val ageMs = System.currentTimeMillis() - cached.time
                                buildJsonObject {
                                    putLocation(cached, cached.provider ?: "cached")
                                    put("cached", true)
                                    put("age_ms", ageMs)
                                    put("note", "GPS fix timed out after ${timeoutMs}ms; returning last known location")
                                }
                            } else {
                                errorPayload("no fix available", "GPS fix failed and no cached location. " +
                                    "Try moving near a window/outdoors, ensure Location is enabled.")
                            }
                        }
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
