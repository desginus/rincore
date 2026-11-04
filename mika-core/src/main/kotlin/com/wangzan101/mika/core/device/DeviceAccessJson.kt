package com.wangzan101.mika.core.device

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

fun DeviceAccessResult.toJson() = buildJsonObject {
    put("status", this@toJson.status); put("enabled", this@toJson.enabled)
    put("credential_configured", this@toJson.credentialConfigured); put("attempt_blocked", this@toJson.attemptBlocked)
    this@toJson.storedKind?.let { put("stored_kind", it.name.lowercase()) }
    this@toJson.detectedKind?.let { put("detected_kind", it.name.lowercase()) }
    if (this@toJson.status == "credential_kind_mismatch") {
        put("message", "Re-enter the credential on the phone using the current lock-screen method (PIN or password).")
    }
    this@toJson.state?.let { s -> putJsonObject("device") {
        put("interactive", s.interactive); put("keyguard_showing", s.keyguardShowing)
        put("device_locked", s.deviceLocked); put("secure", s.secure)
        put("helper_available", s.helperAvailable); put("user_unlocked", s.userUnlocked); put("ready", s.ready)
    } }
}
