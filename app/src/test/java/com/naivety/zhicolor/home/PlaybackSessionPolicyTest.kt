package com.naivety.zhicolor.home

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSessionPolicyTest {
    private val playerUid = 10448

    @Test
    fun emptyCandidatesReturnZero() {
        assertEquals(0, PlaybackSessionPolicy.select(playerUid, emptyList()))
    }

    @Test
    fun activeMediaSessionForCurrentPlayerIsSelected() {
        assertEquals(297, select(candidate()))
    }

    @Test
    fun anotherPlayersUidIsRejected() {
        assertEquals(0, select(candidate(clientUid = playerUid + 1)))
    }

    @Test
    fun inactiveSessionIsRejected() {
        assertEquals(0, select(candidate(active = false)))
    }

    @Test
    fun nonPositiveSessionIdsAreRejected() {
        assertEquals(0, select(candidate(sessionId = 0), candidate(sessionId = -1)))
    }

    @Test
    fun notificationUsageIsRejected() {
        assertEquals(0, select(candidate(usage = 5)))
    }

    @Test
    fun voiceCommunicationUsageIsRejected() {
        assertEquals(0, select(candidate(usage = 2), candidate(usage = 3)))
    }

    @Test
    fun otherNonMusicUsagesAreRejected() {
        val usages = listOf(4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, -1, Int.MAX_VALUE)
        assertEquals(0, PlaybackSessionPolicy.select(playerUid, usages.map { candidate(usage = it) }))
    }

    @Test
    fun unknownUsageIsAcceptedAsFallback() {
        assertEquals(297, select(candidate(usage = 0)))
    }

    @Test
    fun explicitMediaIsPreferredOverEarlierUnknownUsage() {
        assertEquals(313, select(candidate(usage = 0), candidate(sessionId = 313, usage = 1)))
    }

    @Test
    fun firstEligibleMediaSessionWinsDeterministically() {
        assertEquals(297, select(candidate(), candidate(sessionId = 313)))
    }

    @Test
    fun firstEligibleUnknownSessionWinsWithoutMedia() {
        assertEquals(297, select(candidate(usage = 0), candidate(sessionId = 313, usage = 0)))
    }

    @Test
    fun invalidPlayerUidIsRejectedEvenWhenCandidateMatches() {
        for (uid in listOf(-1, 0)) {
            assertEquals(0, PlaybackSessionPolicy.select(uid, listOf(candidate(clientUid = uid))))
        }
    }

    @Test
    fun ineligibleMediaCannotDisplaceCurrentPlayersUnknownSession() {
        assertEquals(297, select(
            candidate(usage = 0),
            candidate(clientUid = playerUid + 1, sessionId = 313),
            candidate(sessionId = 321, active = false),
            candidate(sessionId = 0)
        ))
    }

    private fun select(vararg candidates: PlaybackSessionPolicy.Candidate): Int =
        PlaybackSessionPolicy.select(playerUid, candidates.asList())

    private fun candidate(
        clientUid: Int = playerUid,
        active: Boolean = true,
        sessionId: Int = 297,
        usage: Int = PlaybackSessionPolicy.USAGE_MEDIA
    ) = PlaybackSessionPolicy.Candidate(clientUid, active, sessionId, usage)
}
