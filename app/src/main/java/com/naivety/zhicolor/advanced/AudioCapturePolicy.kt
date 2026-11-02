package com.naivety.zhicolor.advanced

internal data class PlaybackCaptureRequest(
    val sessionId: Int,
    val route: String,
    val trackKey: String,
    val playerPackage: String,
)

/** Requests are leases: a retiring screen cannot stop a newer visible consumer. */
internal class AudioCapturePolicy<K> {
    private val requests = LinkedHashMap<K, PlaybackCaptureRequest>()
    val selected: PlaybackCaptureRequest? get() = requests.values.lastOrNull()

    fun update(owner: K, request: PlaybackCaptureRequest?) {
        // Heartbeats and recomposition do not steal priority from a later subscriber.
        if (requests[owner] == request) return
        requests.remove(owner)
        if (request != null && request.sessionId > 0) requests[owner] = request
    }

    fun receives(owner: K): Boolean = requests[owner] != null && requests[owner] == selected
}

internal object AudioFramePolicy {
    const val MAX_AGE_MS = 500L
    const val LEASE_MS = 15_000L
    const val BAND_COUNT = 48

    fun accepts(expectedGeneration: Long, generation: Long, now: Long, captured: Long,
                bands: FloatArray, loudness: Float): Boolean =
        generation == expectedGeneration && captured <= now && now - captured <= MAX_AGE_MS &&
            bands.size == BAND_COUNT && bands.all { it.isFinite() && it in 0f..1f } &&
            loudness.isFinite() && loudness in 0f..1f
}
