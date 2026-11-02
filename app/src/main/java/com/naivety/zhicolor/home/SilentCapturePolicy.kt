package com.naivety.zhicolor.home

/** A single bounded repair attempt for a live effect that delivers only digital silence. */
internal class SilentCapturePolicy {
    private var silentSeconds = 0f
    private var attempted = false
    fun observe(waveRms: Float, rawRms: Float?, seconds: Float, volume: Int?): Boolean {
        if (attempted) return false
        if (!waveRms.isFinite() || waveRms > 0.000001f ||
            (rawRms != null && (!rawRms.isFinite() || rawRms > 0.000001f)) || volume == 0) {
            silentSeconds = 0f
            return false
        }
        if (seconds.isFinite()) silentSeconds += seconds.coerceIn(0f, 0.25f)
        if (silentSeconds < 1.2f) return false
        attempted = true
        return true
    }
}
