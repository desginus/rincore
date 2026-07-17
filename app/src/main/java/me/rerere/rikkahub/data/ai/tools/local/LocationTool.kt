package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
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
import java.util.concurrent.Executor

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
 * Android R+ one-shot location via LocationManager.getCurrentLocation.
 * No listener management needed — clean, no leak risk.
 */
@SuppressLint("MissingPermission")
private suspend fun getCurrentLocationRPlus(
    lm: LocationManager,
    provider: String,
    timeoutMs: Long
): Location? = suspendCancellableCoroutine { cont ->
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        cont.resume(null)
        return@suspendCancellableCoroutine
    }
    val signal = CancellationSignal()
    val executor = Executor { r -> r.run() }
    Log.i(TAG_LOC, "getCurrentLocation provider=$provider timeout=${timeoutMs}ms")
    try {
        lm.getCurrentLocation(provider, signal, executor) { location ->
            if (cont.isActive) cont.resume(location)
        }
    } catch (e: Throwable) {
        Log.w(TAG_LOC, "getCurrentLocation failed for $provider", e)
        cont.resume(null)
        return@suspendCancellableCoroutine
    }
    cont.invokeOnCancellation {
        signal.cancel()
    }
    // Manual timeout: getCurrentLocation doesn't support timeout natively
    Handler(Looper.getMainLooper()).postDelayed({
        if (cont.isActive) {
            Log.w(TAG_LOC, "getCurrentLocation $provider timed out")
            signal.cancel()
            cont.resume(null)
        }
    }, timeoutMs)
}

/**
 * Legacy requestLocationUpdates fallback (pre-R or when getCurrentLocation unavailable).
 * Critical: removeUpdates MUST be posted to main looper (same thread as registration),
 * otherwise HyperOS silently drops it and the listener leaks (green dot persists).
 */
@SuppressLint("MissingPermission")
private suspend fun requestUpdatesLegacy(
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
        Log.i(TAG_LOC, "requestLocationUpdates legacy provider=$provider timeout=${timeoutMs}ms")
        try {
            lm.requestLocationUpdates(provider, 2000L, 0F, listener, Looper.getMainLooper())
        } catch (e: Throwable) {
            Log.w(TAG_LOC, "requestLocationUpdates failed for $provider", e)
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation {
            // MUST post to main looper — removeUpdates requires same looper as registration
            Handler(Looper.getMainLooper()).post {
                try { lm.removeUpdates(listener) } catch (_: Throwable) {}
            }
        }
    }
}

/**
 * Try to get fresh location from a specific provider.
 * Prefers getCurrentLocation (R+) for reliability, falls back to legacy listener.
 */
@SuppressLint("MissingPermission")
private suspend fun tryProvider(
    lm: LocationManager,
    provider: String,
    timeoutMs: Long
): Location? {
    // R+: use one-shot API (no listener leak risk)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val result = getCurrentLocationRPlus(lm, provider, timeoutMs)
        if (result != null) return result
    }
    // Fallback: legacy listener approach
    return requestUpdatesLegacy(lm, provider, timeoutMs)
}

/**
 * Try multiple providers in order: NETWORK first (fast, works indoors) -> GPS (accurate).
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

    // Network: 15s (cell/WiFi, indoor-available)
    // GPS: remaining time (or 30s minimum)
    val networkTimeout = maxOf(timeoutMs / 3, 15_000L)
    val gpsTimeout = maxOf(timeoutMs - networkTimeout, 30_000L)

    for ((i, provider) in providers.withIndex()) {
        val pt = if (provider == LocationManager.NETWORK_PROVIDER) networkTimeout else gpsTimeout
        val result = tryProvider(lm, provider, pt)
        if (result != null) {
            Log.i(TAG_LOC, "got fix from $provider (${i + 1}/${providers.size})")
            return result
        }
        Log.w(TAG_LOC, "$provider timed out after ${pt}ms")
    }
    return null
}

fun locationTool(context: Context): Tool = Tool(
    name = "get_location",
    description = "获取设备当前实时位置。优先 Network 定位(蜂窝/WiFi),不可用时降级 GPS。失败则返回缓存。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("accuracy", buildJsonObject {
                    put("type", "string")
                    put("description", "Location accuracy preference: high, balanced (default), or low")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in ms (default 60000, min 45000, max 90000).")
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
        val timeoutMs = (params["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 60000)
            .coerceIn(45000, 90000)

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

                            // Step 2: LocationManager (Network -> GPS with proper cleanup)
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
                                        "Try: 1) Open Google Maps to trigger first fix. " +
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
