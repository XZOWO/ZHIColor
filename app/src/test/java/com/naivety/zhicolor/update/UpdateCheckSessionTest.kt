package com.naivety.zhicolor.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckSessionTest {
    @Test
    fun foregroundRecompositionRotationAndBrowserReturnDoNotCheckAgain() {
        val session = UpdateCheckSession()
        assertTrue(session.enterForeground())
        assertFalse(session.enterForeground())
        assertFalse(session.complete())
        session.leaveForeground(changingConfigurations = true)
        assertFalse(session.enterForeground())
        session.leaveForeground(changingConfigurations = false)
        assertFalse(session.enterForeground())
    }

    @Test
    fun manualTapJoinsAutomaticRequestAndPromotesItsFeedback() {
        val session = UpdateCheckSession()
        assertTrue(session.enterForeground())
        assertFalse(session.requestManual())
        assertFalse(session.requestManual())
        assertTrue(session.complete())
        assertTrue(session.requestManual())
        assertTrue(session.complete())
    }

    @Test
    fun reenteringWhileNetworkRequestRunsKeepsSingleRequest() {
        val session = UpdateCheckSession()
        assertTrue(session.enterForeground())
        session.leaveForeground(changingConfigurations = false)
        assertFalse(session.enterForeground())
        assertFalse(session.complete())
        assertFalse(session.enterForeground())
        session.leaveForeground(changingConfigurations = false)
        assertFalse(session.enterForeground())
    }

    @Test
    fun failureCompletionAllowsRetryAndResetsManualFlag() {
        val session = UpdateCheckSession()
        assertTrue(session.requestManual())
        assertTrue(session.complete())
        assertTrue(session.enterForeground())
        assertFalse(session.complete())
    }

    @Test
    fun automaticCompletionDoesNotRetryOnReturnButManualCanRetry() {
        val session = UpdateCheckSession()
        assertTrue(session.enterForeground())
        assertFalse(session.complete())
        session.leaveForeground(changingConfigurations = false)
        assertFalse(session.enterForeground())
        assertTrue(session.requestManual())
        assertTrue(session.complete())
        assertTrue(session.requestManual())
        assertTrue(session.complete())
    }

    @Test
    fun newLaunchSessionCanAutomaticallyCheckAgain() {
        val firstLaunch = UpdateCheckSession()
        assertTrue(firstLaunch.enterForeground())
        assertFalse(firstLaunch.complete())
        val nextLaunch = UpdateCheckSession()
        assertTrue(nextLaunch.enterForeground())
        assertFalse(nextLaunch.complete())
    }
}
