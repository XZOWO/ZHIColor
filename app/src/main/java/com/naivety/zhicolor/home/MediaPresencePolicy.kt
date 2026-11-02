package com.naivety.zhicolor.home

/** A transient session hand-off must not flash the idle page or discard the paused cover. */
internal class MediaPresencePolicy {
    private var missingSince: Long? = null

    fun retainMissingSession(nowMillis: Long, previouslyAvailable: Boolean): Boolean {
        if (!previouslyAvailable) return false
        val since = missingSince ?: nowMillis.also { missingSince = it }
        return nowMillis - since < 2_000L
    }

    fun sessionAvailable() { missingSince = null }

    fun artwork(previous: ByteArray?, incoming: ByteArray?): ByteArray? {
        if (incoming != null) {
            return if (previous != null && incoming.contentEquals(previous)) previous else incoming
        }
        // A loading gap is not media removal. Keep the visible image until a replacement
        // arrives; acceptSnapshot clears the complete state after confirmed session removal.
        return previous
    }
}
