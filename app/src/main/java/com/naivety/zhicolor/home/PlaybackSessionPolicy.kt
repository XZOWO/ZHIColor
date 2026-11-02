package com.naivety.zhicolor.home

/** Selects the current player's music session without depending on Android framework classes. */
internal object PlaybackSessionPolicy {
    const val USAGE_UNKNOWN = 0
    const val USAGE_MEDIA = 1

    data class Candidate(
        val clientUid: Int,
        val active: Boolean,
        val sessionId: Int,
        val usage: Int
    )

    /** Prefers explicit media usage; unknown usage is only a fallback for vendor players. */
    fun select(playerUid: Int, candidates: Iterable<Candidate>): Int {
        if (playerUid <= 0) return 0
        var unknownSession = 0
        for (candidate in candidates) {
            if (candidate.clientUid != playerUid || !candidate.active || candidate.sessionId <= 0) continue
            when (candidate.usage) {
                USAGE_MEDIA -> return candidate.sessionId
                USAGE_UNKNOWN -> if (unknownSession == 0) unknownSession = candidate.sessionId
            }
        }
        return unknownSession
    }
}
