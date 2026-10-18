package com.naivety.zhicolor.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockscreenMusicAppPolicyTest {
    @Test fun hidesAnEnabledLockscreenCardRegardlessOfScreenPower() {
        assertTrue(LockscreenMusicAppPolicy.hidden(true, true, true, false, true))
        assertTrue(LockscreenMusicAppPolicy.hidden(true, true, true, false, false))
    }

    @Test fun hidesAnEnabledAodCardEvenWhenKeyguardShowingClears() {
        for (keyguard in listOf(false, true)) {
            for (screenOn in listOf(false, true)) {
                assertTrue(LockscreenMusicAppPolicy.hidden(true, true, keyguard, true, screenOn))
            }
        }
    }

    @Test fun keepsAnUnlitNotificationCardHiddenBeforeLockscreenOrAodFlagsArrive() {
        assertTrue(LockscreenMusicAppPolicy.hidden(true, true, false, false, false))
    }

    @Test fun neverHidesDisabledAwakeUnlockedOrNonNotificationCards() {
        for (keyguard in listOf(false, true)) {
            for (aod in listOf(false, true)) {
                for (screenOn in listOf(false, true)) {
                    assertFalse(LockscreenMusicAppPolicy.hidden(false, true, keyguard, aod, screenOn))
                    assertFalse(LockscreenMusicAppPolicy.hidden(true, false, keyguard, aod, screenOn))
                }
            }
        }
        assertFalse(LockscreenMusicAppPolicy.hidden(true, true, false, false, true))
    }

    @Test fun keepsTheIconHiddenThroughSleepAodAndWakeThenRestoresOnUnlock() {
        val lockedToUnlocked = listOf(
            Triple(true, false, true), // Awake lockscreen.
            Triple(true, false, false), // Screen off before AOD.
            Triple(false, false, false), // Keyguard clears before AOD arrives.
            Triple(false, true, false), // Enter AOD with keyguardShowing cleared.
            Triple(false, false, false), // AOD clears before the screen wakes.
            Triple(true, false, false), // Keyguard returns before the screen wakes.
            Triple(true, false, true), // Wake to lockscreen.
            Triple(false, false, true) // Unlock.
        )
        val hidden = lockedToUnlocked.map { (keyguard, aod, screenOn) ->
            LockscreenMusicAppPolicy.hidden(true, true, keyguard, aod, screenOn)
        }
        assertEquals(listOf(true, true, true, true, true, true, true, false), hidden)
        assertEquals(listOf(4, 4, 4, 4, 4, 4, 4, 0),
            hidden.map { LockscreenMusicAppPolicy.visibility(0, it) })
        assertEquals(listOf(8, 8, 8, 8, 8, 8, 8, 8),
            hidden.map { LockscreenMusicAppPolicy.visibility(8, it) })
    }

    @Test fun disablingRestoresEveryNativeVisibilityIncludingGone() {
        for (native in listOf(0, 4, 8)) {
            assertEquals(if (native == 0) 4 else native,
                LockscreenMusicAppPolicy.visibility(native, true))
            assertEquals(native, LockscreenMusicAppPolicy.visibility(native, false))
        }
        // A native GONE update received while hidden supersedes an earlier VISIBLE request.
        var latestNative = 0
        assertEquals(4, LockscreenMusicAppPolicy.visibility(latestNative, true))
        latestNative = 8
        assertEquals(8, LockscreenMusicAppPolicy.visibility(latestNative, true))
        assertEquals(8, LockscreenMusicAppPolicy.visibility(latestNative, false))
    }

    @Test fun staleInitialProviderReadCannotReplaceANewerBroadcast() {
        assertTrue(LockscreenMusicAppPolicy.acceptRevision(-1, 0))
        assertTrue(LockscreenMusicAppPolicy.acceptRevision(0, 20))
        assertFalse(LockscreenMusicAppPolicy.acceptRevision(20, 0))
        assertFalse(LockscreenMusicAppPolicy.acceptRevision(20, 19))
        assertFalse(LockscreenMusicAppPolicy.acceptRevision(20, 20))
        assertFalse(LockscreenMusicAppPolicy.acceptRevision(-1, -1))
        assertTrue(LockscreenMusicAppPolicy.acceptRevision(20, 21))
    }
}
