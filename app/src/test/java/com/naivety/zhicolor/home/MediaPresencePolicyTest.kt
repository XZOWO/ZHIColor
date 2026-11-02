package com.naivety.zhicolor.home

import org.junit.Assert.*
import org.junit.Test

class MediaPresencePolicyTest {
    @Test fun sessionHandoffRetainsArtworkButRealRemovalEventuallyClearsIt() {
        val policy = MediaPresencePolicy()
        assertFalse(policy.retainMissingSession(0, false))
        assertTrue(policy.retainMissingSession(100, true))
        assertTrue(policy.retainMissingSession(900, true))
        assertFalse(policy.retainMissingSession(2500, true))
        policy.sessionAvailable()
        assertTrue(policy.retainMissingSession(3000, true))
    }

    @Test fun pausedMetadataKeepsTheSameBitmapIdentityWithoutRepeatedTransitions() {
        val policy = MediaPresencePolicy()
        val old = byteArrayOf(1, 2, 3)
        assertSame(old, policy.artwork(old, old.copyOf()))
        assertSame(old, policy.artwork(old, null))
        assertSame(old, policy.artwork(old, old.copyOf()))
    }

    @Test fun loadingNeverClearsTheVisibleImageBeforeReplacementIsReady() {
        val policy = MediaPresencePolicy()
        val old = byteArrayOf(1, 2, 3)
        assertSame(old, policy.artwork(old, null))
        assertSame(old, policy.artwork(old, null))
        assertSame(old, policy.artwork(old, null))
        val next = byteArrayOf(4, 5)
        assertSame(next, policy.artwork(old, next))
    }
}
