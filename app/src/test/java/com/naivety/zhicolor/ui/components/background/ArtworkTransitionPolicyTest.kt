package com.naivety.zhicolor.ui.components.background

import org.junit.Assert.*
import org.junit.Test

class ArtworkTransitionPolicyTest {
    @Test fun firstImageDoesNotInventAPreviousTrack() {
        assertFalse(ArtworkTransitionPolicy.restart(false, true, true, true, true))
    }
    @Test fun sameSongRefreshDoesNotStartAWave() {
        assertFalse(ArtworkTransitionPolicy.restart(true, false, false, true, true))
    }
    @Test fun placeholderAndFinalCoverContinueTheExistingWave() {
        assertFalse(ArtworkTransitionPolicy.restart(true, true, false, true, false))
    }
    @Test fun realNextSongCanInterruptWithoutAnArbitraryCooldown() {
        assertTrue(ArtworkTransitionPolicy.restart(true, true, true, true, false))
        assertTrue(ArtworkTransitionPolicy.restart(true, false, true, true, false))
    }
    @Test fun artworkArrivingAfterCompletionStillUpdatesVisually() {
        assertTrue(ArtworkTransitionPolicy.restart(true, true, false, true, true))
    }
    @Test fun callersWithoutTrackIdentityRetainExistingBehavior() {
        assertTrue(ArtworkTransitionPolicy.restart(true, true, false, false, false))
    }
}
