package com.naivety.zhicolor.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private class FakeIndicator
    private class FakeUiConfig(
        @JvmField val v: Boolean,
        @JvmField val i: Boolean,
        @JvmField val j: Boolean
    )
    private class FakeFlow(@JvmField val value: FakeUiConfig) {
        fun getValue(): FakeUiConfig = value
    }
    private class FakeVmV1(@JvmField val b: FakeFlow)
    private class FakeVmV2(@JvmField val b: Any, @JvmField val c: FakeFlow)

    private class FakeSectionV1(
        @JvmField val u: FakeIndicator,
        @JvmField val k: FakeVmV1,
        private val notification: Boolean
    ) {
        fun o(): Boolean = notification
    }

    private class FakeOnLayoutListenerV2(
        @JvmField val a: Int,
        @JvmField val b: Any
    )

    private class FakeSectionV2(
        @JvmField val u: Any,
        @JvmField val w: FakeIndicator,
        @JvmField val l: FakeVmV2,
        private val notification: Boolean
    ) {
        fun o(): Boolean = notification
    }

    @Test fun resolvesAndReadsBothInitialAndRebuiltColorOs17SectionLayouts() {
        val v1Spec = LockscreenMusicAppPolicy.SECTION_SPECS[0]
        val v2Spec = LockscreenMusicAppPolicy.SECTION_SPECS[1]
        assertEquals("com.oplus.systemui.plugins.shared.template.section.media.s1", v1Spec.sectionClass)
        assertEquals("u", v1Spec.indicatorField)
        assertEquals("k", v1Spec.vmField)
        assertEquals("b", v1Spec.configFlowField)
        assertEquals("com.oplus.systemui.plugins.shared.template.section.media.x1", v2Spec.sectionClass)
        assertEquals("w", v2Spec.indicatorField)
        assertEquals("l", v2Spec.vmField)
        assertEquals("c", v2Spec.configFlowField)

        val testSpecs = listOf(
            v1Spec.copy(sectionClass = FakeSectionV1::class.java.name),
            v2Spec.copy(sectionClass = FakeSectionV2::class.java.name)
        )
        val binding1 = LockscreenMusicAppPolicy.resolveSectionBinding(
            FakeSectionV1::class.java, FakeIndicator::class.java.name, testSpecs
        )
        assertNotNull(binding1)
        val section1 = FakeSectionV1(
            FakeIndicator(),
            FakeVmV1(FakeFlow(FakeUiConfig(v = true, i = false, j = true))),
            notification = true
        )
        assertTrue(LockscreenMusicAppHook.StateAccess.create(section1, binding1!!).isLockscreenCard())

        // OnLayoutChangeListener shaped s1 must be rejected cleanly without throwing.
        val mismatchedS1Specs = listOf(v1Spec.copy(sectionClass = FakeOnLayoutListenerV2::class.java.name))
        assertNull(
            LockscreenMusicAppPolicy.resolveSectionBinding(
                FakeOnLayoutListenerV2::class.java, FakeIndicator::class.java.name, mismatchedS1Specs
            )
        )

        val binding2 = LockscreenMusicAppPolicy.resolveSectionBinding(
            FakeSectionV2::class.java, FakeIndicator::class.java.name, testSpecs
        )
        assertNotNull(binding2)
        val section2 = FakeSectionV2(
            Any(),
            FakeIndicator(),
            FakeVmV2(Any(), FakeFlow(FakeUiConfig(v = false, i = true, j = false))),
            notification = true
        )
        assertTrue(LockscreenMusicAppHook.StateAccess.create(section2, binding2!!).isLockscreenCard())
        val unlockedSection2 = FakeSectionV2(
            Any(),
            FakeIndicator(),
            FakeVmV2(Any(), FakeFlow(FakeUiConfig(v = false, i = false, j = true))),
            notification = true
        )
        assertFalse(LockscreenMusicAppHook.StateAccess.create(unlockedSection2, binding2).isLockscreenCard())
    }
}
