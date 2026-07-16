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
 * 通过 LocationManager.requestLocationUpdates 获取实时定位 (备用方案)。
 *
 * 注意: minTime 设为 2000ms 而非 0, 避免 HyperOS 3 将过于激进的请求限流。
 * FusedLocation getCurrentLocation 是优先方案, 这里仅在 GMS 不可用或 Fused 超时时使用。
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
                2000L,   // minTime: 2s, HyperOS 3 对 0ms 会限流不回调
                0F,      // minDistance: 0
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
        "优先使用 FusedLocation (Google Play Services), 不可用时降级为系统 LocationManager。" +
        "若实时定位失败, 返回最近 5 分钟内的缓存位置。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("accuracy", buildJsonObject {
                    put("type", "string")
                    put("description", "Location accuracy preference: high, balanced (default), or low")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in milliseconds (default 45000, min 30000, max 60000). " +
                        "GPS cold start needs 30-60 seconds.")
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
        val timeoutMs = (params["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 45000)
            .coerceIn(30000, 60000)

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

                        // ── 第 0 步: 快速缓存检查 ──
                        // 如果有 5 分钟内的缓存, 直接返回, 不触发硬件
                        val quickCache = getAnyLastLocation(lm, gmsAvailable, context)
                        if (quickCache != null && System.currentTimeMillis() - quickCache.time < 300_000L) {
                            Log.i(TAG_LOC, "quick cache hit age=${System.currentTimeMillis() - quickCache.time}ms")
                            buildJsonObject {
                                putLocation(quickCache, quickCache.provider ?: "cached")
                                put("cached", true)
                                put("age_ms", System.currentTimeMillis() - quickCache.time)
                            }
                        } else {
                            // ── 第 1 步: FusedLocation getCurrentLocation (现代 Android 最可靠) ──
                            var fresh: Location? = null
                            if (gmsAvailable) {
                                Log.i(TAG_LOC, "trying FusedLocation getCurrentLocation priority=$accuracyStr timeout=${timeoutMs}ms")
                                fresh = try {
                                    val client = LocationServices.getFusedLocationProviderClient(context)
                                    withTimeoutOrNull(timeoutMs.toLong()) {
                                        client.getCurrentLocation(priority, null).await()
                                    }
                                } catch (t: Throwable) { Log.w(TAG_LOC, "FusedLocation getCurrentLocation failed", t); null }
                            }

                            // ── 第 2 步: LocationManager requestLocationUpdates (备用) ──
                            // HyperOS 3 对 minTime=0 会限流, 改用 2000ms 避免被系统忽略
                            if (fresh == null) {
                                Log.i(TAG_LOC, "FusedLocation failed, trying LocationManager timeout=${timeoutMs}ms")
                                fresh = requestFreshFixViaLocationManager(lm, timeoutMs.toLong())
                            }

                            if (fresh != null) {
                                Log.i(TAG_LOC, "fresh fix obtained provider=${fresh.provider}")
                                buildJsonObject { putLocation(fresh, fresh.provider ?: "gps") }
                            } else {
                                // ── 第 3 步: 降级到任意缓存 ──
                                Log.w(TAG_LOC, "all fresh fix methods failed, falling back to cache")
                                val cached = getAnyLastLocation(lm, gmsAvailable, context)
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
                                        "Try moving near a window/outdoors, ensure Location is enabled. " +
                                        "On Xiaomi/HyperOS, check: Settings → Apps → RinCore → Permissions → Location → Allow all the time.")
                                }
                            }
                        }
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** 从所有可用来源获取最近一次定位 (不触发硬件) */
@SuppressLint("MissingPermission")
private suspend fun getAnyLastLocation(
    lm: LocationManager,
    gmsAvailable: Boolean,
    context: Context
): Location? {
    // FusedLocation lastLocation (最可能有效)
    if (gmsAvailable) {
        try {
            val loc = LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
            if (loc != null) return loc
        } catch (_: Throwable) {}
    }
    // 系统 LocationManager 各 provider
    try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { return it } } catch (_: Throwable) {}
    try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { return it } } catch (_: Throwable) {}
    try { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)?.let { return it } } catch (_: Throwable) {}
    return null
}
