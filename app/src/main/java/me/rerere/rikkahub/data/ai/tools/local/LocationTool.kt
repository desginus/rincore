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
 * Try multiple LocationProviders in order, return first valid fix.
 * Strategy: NETWORK first (cell/WiFi, seconds) -> GPS fallback (accurate but slow).
 *
 * On HyperOS 3, GPS may be "enabled" but deliver no fix indoors.
 * Must fall back to Network provider instead of dead-waiting on GPS.
 */
@SuppressLint("MissingPermission")
private suspend fun requestLocationFromProviders(
    lm: LocationManager,
    timeoutMs: Long
): Location? {
    val providers = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER,
    ).filter { p ->
        try { lm.isProviderEnabled(p) } catch (_: Throwable) { false }
    }
    if (providers.isEmpty()) return null
    val perProviderMs = maxOf(timeoutMs / providers.size, 5000L)
    for ((i, provider) in providers.withIndex()) {
        val result = trySingleProvider(lm, provider, perProviderMs)
        if (result != null) {
            Log.i(TAG_LOC, "got fix from $provider (${i + 1}/${providers.size})")
            return result
        }
        Log.w(TAG_LOC, "$provider timed out after ${perProviderMs}ms")
    }
    return null
}

@SuppressLint("MissingPermission")
private suspend fun trySingleProvider(
    lm: LocationManager,
    provider: String,
    timeoutMs: Long
): Location? = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine<Location?> { cont ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (cont.isActive) cont.resume(location)
            }
        }
        Log.i(TAG_LOC, "requestLocationUpdates provider=$provider timeout=${timeoutMs}ms")
        try {
            lm.requestLocationUpdates(provider, 2000L, 0F, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG_LOC, "SecurityException for $provider", e)
            cont.resume(null)
            return@suspendCancellableCoroutine
        } catch (e: Throwable) {
            Log.w(TAG_LOC, "requestLocationUpdates failed for $provider", e)
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
    description = "获取设备当前实时位置。优先 Network 定位(蜂窝/WiFi,秒级),不可用时降级 GPS。失败则返回缓存。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("accuracy", buildJsonObject {
                    put("type", "string")
                    put("description", "Location accuracy preference: high, balanced (default), or low")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in ms (default 45000, min 30000, max 60000).")
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
                    val anyEnabled = try {
                        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
                            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    } catch (_: Throwable) { false }
                    if (!anyEnabled) {
                        errorPayload("location services disabled",
                            "Ask the user to enable Location in Settings -> Location.")
                    } else {
                        val gmsAvailable = try {
                            GoogleApiAvailability.getInstance()
                                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
                        } catch (t: Throwable) { Log.w(TAG_LOC, "GMS check failed", t); false }

                        // Step 0: quick cache (<=5min, skip hardware)
                        val quickCache = getAnyLastLocation(lm, gmsAvailable, context)
                        if (quickCache != null && System.currentTimeMillis() - quickCache.time < 300_000L) {
                            Log.i(TAG_LOC, "quick cache hit age=${System.currentTimeMillis() - quickCache.time}ms")
                            buildJsonObject {
                                putLocation(quickCache, quickCache.provider ?: "cached")
                                put("cached", true)
                                put("age_ms", System.currentTimeMillis() - quickCache.time)
                            }
                        } else {
                            // Step 1: FusedLocation (GMS devices)
                            var fresh: Location? = null
                            if (gmsAvailable) {
                                Log.i(TAG_LOC, "trying FusedLocation priority=$accuracyStr timeout=${timeoutMs}ms")
                                fresh = try {
                                    val client = LocationServices.getFusedLocationProviderClient(context)
                                    withTimeoutOrNull(timeoutMs.toLong()) {
                                        client.getCurrentLocation(priority, null).await()
                                    }
                                } catch (t: Throwable) {
                                    Log.w(TAG_LOC, "FusedLocation failed", t); null
                                }
                            }

                            // Step 2: LocationManager multi-provider (Network -> GPS)
                            if (fresh == null) {
                                Log.i(TAG_LOC, "trying LocationManager multi-provider timeout=${timeoutMs}ms")
                                fresh = requestLocationFromProviders(lm, timeoutMs.toLong())
                            }

                            if (fresh != null) {
                                Log.i(TAG_LOC, "fresh fix via ${fresh.provider}")
                                buildJsonObject { putLocation(fresh, fresh.provider ?: "unknown") }
                            } else {
                                // Step 3: any cached location
                                Log.w(TAG_LOC, "all fresh methods failed, trying any cache")
                                val cached = getAnyLastLocation(lm, gmsAvailable, context)
                                if (cached != null) {
                                    val ageMs = System.currentTimeMillis() - cached.time
                                    buildJsonObject {
                                        putLocation(cached, cached.provider ?: "cached")
                                        put("cached", true)
                                        put("age_ms", ageMs)
                                        put("note", "all fresh fix methods timed out; returning last known location")
                                    }
                                } else {
                                    errorPayload("no fix available",
                                        "All location methods failed (FusedLocation + Network + GPS). " +
                                        "No cached location on device. " +
                                        "Try: 1) Move near window/outdoors. " +
                                        "2) Settings -> Location -> toggle off/on. " +
                                        "3) Settings -> Apps -> RinCore -> Permissions -> Location -> Allow all the time.")
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

/** Get last known location from all sources (no hardware trigger) */
@SuppressLint("MissingPermission")
private suspend fun getAnyLastLocation(
    lm: LocationManager,
    gmsAvailable: Boolean,
    context: Context
): Location? {
    if (gmsAvailable) {
        try {
            val loc = LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
            if (loc != null) return loc
        } catch (_: Throwable) {}
    }
    try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { return it } } catch (_: Throwable) {}
    try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { return it } } catch (_: Throwable) {}
    try { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)?.let { return it } } catch (_: Throwable) {}
    return null
}
