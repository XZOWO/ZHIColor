package com.naivety.zhicolor.bridge;

import static org.junit.Assert.*;

import com.naivety.zhicolor.bridge.render.LyricLineExitPolicy;
import com.naivety.zhicolor.bridge.render.LyricTimingMode;
import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordRange;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public class LyricScrubSyncPolicyTest {

    @Test
    public void progressToMillisMapsContinuouslyAndClampsBounds() {
        assertEquals(0L, LyricScrubSyncPolicy.progressToMillis(0, 1000, 240_000L));
        assertEquals(83_280L, LyricScrubSyncPolicy.progressToMillis(347, 1000, 240_000L));
        assertEquals(240_000L, LyricScrubSyncPolicy.progressToMillis(1000, 1000, 240_000L));
        assertEquals(0L, LyricScrubSyncPolicy.progressToMillis(-20, 1000, 240_000L));
        assertEquals(240_000L, LyricScrubSyncPolicy.progressToMillis(1500, 1000, 240_000L));
        assertEquals(-1L, LyricScrubSyncPolicy.progressToMillis(500, 0, 240_000L));
        assertEquals(-1L, LyricScrubSyncPolicy.progressToMillis(500, 1000, 0L));
    }

    @Test
    public void resolveDurationMillisPrioritizesMetadataThenSectionThenModel() {
        assertEquals(245_320L, LyricScrubSyncPolicy.resolveDurationMillis(245_320L, 245_000L, 240_000L));
        assertEquals(245_000L, LyricScrubSyncPolicy.resolveDurationMillis(-1L, 245_000L, 240_000L));
        assertEquals(240_000L, LyricScrubSyncPolicy.resolveDurationMillis(0L, 0L, 240_000L));
        assertEquals(-1L, LyricScrubSyncPolicy.resolveDurationMillis(-1L, -1L, -1L));
    }

    @Test
    public void decideScrollModeSmoothsShortDragsAndSnapsLongOrRapidSweeps() {
        assertEquals(
                LyricScrubSyncPolicy.ScrollMode.NONE,
                LyricScrubSyncPolicy.decideScrollMode(5, 5, 200L, true, true));
        assertEquals(
                LyricScrubSyncPolicy.ScrollMode.NONE,
                LyricScrubSyncPolicy.decideScrollMode(5, -1, 200L, true, true));
        assertEquals(
                LyricScrubSyncPolicy.ScrollMode.SMOOTH,
                LyricScrubSyncPolicy.decideScrollMode(5, 6, 150L, true, true));
        assertEquals(
                LyricScrubSyncPolicy.ScrollMode.SMOOTH,
                LyricScrubSyncPolicy.decideScrollMode(5, 8, 200L, true, true));
        assertEquals(
                LyricScrubSyncPolicy.ScrollMode.SMOOTH,
                LyricScrubSyncPolicy.decideScrollMode(5, 4, -1L, false, true));
        assertEquals(
                LyricScrubSyncPolicy.ScrollMode.IMMEDIATE,
                LyricScrubSyncPolicy.decideScrollMode(5, 9, 200L, true, true));
        assertEquals(
                LyricScrubSyncPolicy.ScrollMode.IMMEDIATE,
                LyricScrubSyncPolicy.decideScrollMode(5, 7, 200L, false, true));
        assertEquals(
                LyricScrubSyncPolicy.ScrollMode.IMMEDIATE,
                LyricScrubSyncPolicy.decideScrollMode(5, 6, 40L, true, true));
        assertEquals(
                LyricScrubSyncPolicy.ScrollMode.IMMEDIATE,
                LyricScrubSyncPolicy.decideScrollMode(5, 6, 200L, true, false));
        assertEquals(
                LyricScrubSyncPolicy.ScrollMode.IMMEDIATE,
                LyricScrubSyncPolicy.decideScrollMode(-1, 6, 200L, true, true));
    }

    @Test
    public void scrubbingOverridesClockAndRejectsExternalPlaybackTicks() {
        LyricScrubSyncPolicy policy = new LyricScrubSyncPolicy();
        policy.beginScrub(60_000L, 60_000L, true, 1f, 1_000L);
        assertEquals(LyricScrubSyncPolicy.Phase.SCRUBBING, policy.phase(1_000L));
        assertTrue(policy.isScrubbing(1_000L));
        assertTrue(policy.shouldSuppressTrackRestart(1_000L));

        policy.updateScrub(120_500L, 1_050L);
        assertEquals(120_500L, policy.resolveEffectivePositionMillis(60_200L, true, 1f, 1_060L));
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.IGNORE_STALE_POSITION,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 60_250L, 1_060L, true, 1f, 1_060L));

        // Backward scrub toward 0:00 stays exact and keeps suppressing restart blanking.
        policy.updateScrub(500L, 1_120L);
        assertEquals(500L, policy.resolveEffectivePositionMillis(60_400L, true, 1f, 1_130L));
        assertTrue(policy.shouldSuppressTrackRestart(1_130L));
    }

    @Test
    public void commitWindowHoldsSteadilyDuringSeekLoadAndConvergesWithoutRewind() {
        LyricScrubSyncPolicy policy = new LyricScrubSyncPolicy();
        policy.beginScrub(90_000L, 90_000L, true, 1f, 2_000L);
        policy.updateScrub(1_200L, 2_100L);
        policy.commitScrub(1_200L, true, 2_200L);

        assertEquals(LyricScrubSyncPolicy.Phase.COMMITTING, policy.phase(2_200L));
        assertFalse(policy.isScrubbing(2_200L));
        assertTrue(policy.isScrubOrCommitActive(2_200L));

        // Playing or paused: holds committed position steadily (no premature run-ahead while loading).
        assertEquals(1_200L, policy.resolveEffectivePositionMillis(90_300L, true, 1f, 2_400L));
        assertEquals(1_200L, policy.resolveEffectivePositionMillis(90_300L, false, 0f, 2_400L));

        // Stale pre-seek tick (timestamp before commit) must be ignored without clobbering play flags.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.IGNORE_STALE_POSITION,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 90_300L, 2_100L, true, 1f, 2_300L));
        assertFalse(policy.shouldApplyPlaybackFlagsWhenIgnored(
                LyricScrubSyncPolicy.STATE_PAUSED, 2_100L, 2_300L));

        // Fresh post-seek tick still carrying old pre-seek position must also be ignored.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.IGNORE_STALE_POSITION,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 90_350L, 2_250L, true, 1f, 2_320L));

        // Fresh post-seek tick near committed position (ColorOS whole-second seek -> 1_000ms).
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.ACCEPT_COMMITTED_SEEK,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 1_000L, 2_380L, true, 1f, 2_400L));
        assertEquals(LyricScrubSyncPolicy.Phase.IDLE, policy.phase(2_400L));
        assertTrue(policy.isPhaseLocking(2_400L));
        assertTrue(policy.isPostSeekGuardActive(2_400L));

        // Backward-hold prevents -200ms rewind until real playback reaches 1_200ms.
        assertEquals(1_200L, policy.resolveEffectivePositionMillis(1_050L, true, 1f, 2_450L));
        assertEquals(1_200L, policy.resolveEffectivePositionMillis(1_200L, true, 1f, 2_600L));
        assertFalse(policy.isPhaseLocking(2_600L));
        assertEquals(1_320L, policy.resolveEffectivePositionMillis(1_320L, true, 1f, 2_720L));

        // Track-restart suppression remains briefly active right after converging to ~0:00.
        assertTrue(policy.shouldSuppressTrackRestart(2_500L));
        assertFalse(policy.shouldSuppressTrackRestart(3_300L));
    }

    @Test
    public void commitWindowExpiresCleanlyAndOrphanScrubRecovers() {
        LyricScrubSyncPolicy policy = new LyricScrubSyncPolicy();
        policy.commitScrub(45_000L, true, 5_000L);
        assertEquals(LyricScrubSyncPolicy.Phase.COMMITTING, policy.phase(7_999L));
        assertEquals(LyricScrubSyncPolicy.Phase.IDLE, policy.phase(8_000L));
        // Timeout expiry without convergence does not block real playback behind a post-converge guard.
        assertFalse(policy.isPostSeekGuardActive(8_000L));
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.PASS_THROUGH,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 46_000L, 8_050L, true, 1f, 8_050L));

        policy.beginScrub(10_000L, 10_000L);
        assertTrue(policy.isScrubbing(21_999L));
        assertFalse(policy.isScrubbing(22_000L));
        assertEquals(LyricScrubSyncPolicy.Phase.IDLE, policy.phase(22_000L));
    }

    @Test
    public void cancelScrubClearsActiveAndCommitStateImmediately() {
        LyricScrubSyncPolicy policy = new LyricScrubSyncPolicy();
        policy.beginScrub(30_000L, 30_000L, true, 1f, 1_000L);
        policy.updateScrub(80_000L, 1_100L);
        policy.commitScrub(80_000L, true, 1_120L);
        policy.observePlaybackState(
                LyricScrubSyncPolicy.STATE_BUFFERING, 80_000L, 1_130L, false, 0f, 1_130L);
        assertTrue(policy.isBufferingAfterCommit(1_130L));

        policy.cancelScrub();
        assertEquals(LyricScrubSyncPolicy.Phase.IDLE, policy.phase(1_150L));
        assertFalse(policy.isScrubOrCommitActive(1_150L));
        assertFalse(policy.isBufferingAfterCommit(1_150L));
        assertFalse(policy.isPostSeekGuardActive(1_150L));
        assertFalse(policy.isPhaseLocking(1_150L));
        assertFalse(policy.shouldSuppressTrackRestart(1_150L));
        assertEquals(30_150L, policy.resolveEffectivePositionMillis(30_150L, true, 1f, 1_150L));
    }

    @Test
    public void bufferingAndConnectingExtendCommitHoldWithoutEarlyExitOrPlayFlagFlicker() {
        LyricScrubSyncPolicy policy = new LyricScrubSyncPolicy();
        policy.beginScrub(30_000L, 30_000L, true, 1f, 9_800L);
        policy.updateScrub(185_400L, 9_950L);
        policy.commitScrub(185_400L, true, 10_000L);

        // STATE_BUFFERING carrying target position must NOT exit COMMITTING early.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.IGNORE_STALE_POSITION,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_BUFFERING, 185_000L, 10_200L, false, 0f, 10_200L));
        assertEquals(LyricScrubSyncPolicy.Phase.COMMITTING, policy.phase(10_200L));
        assertTrue(policy.isBufferingAfterCommit(10_200L));
        assertFalse(policy.shouldApplyPlaybackFlagsWhenIgnored(
                LyricScrubSyncPolicy.STATE_BUFFERING, 10_200L, 10_200L));

        // Subsequent STATE_CONNECTING carrying 0ms extends deadline and keeps lyrics anchored.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.IGNORE_STALE_POSITION,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_CONNECTING, 0L, 12_200L, false, 0f, 12_200L));
        assertEquals(LyricScrubSyncPolicy.Phase.COMMITTING, policy.phase(14_000L));
        assertEquals(185_400L, policy.resolveEffectivePositionMillis(0L, true, 1f, 14_000L));

        // Another buffering pulse extends again; at 5.2s after commit, real STATE_PLAYING arrives.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.IGNORE_STALE_POSITION,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_BUFFERING, 185_000L, 14_200L, false, 0f, 14_200L));
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.ACCEPT_COMMITTED_SEEK,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 185_050L, 15_190L, true, 1f, 15_200L));
        assertEquals(LyricScrubSyncPolicy.Phase.IDLE, policy.phase(15_200L));
        assertFalse(policy.isBufferingAfterCommit(15_200L));
        assertTrue(policy.isPhaseLocking(15_200L));
        assertEquals(185_400L, policy.resolveEffectivePositionMillis(185_200L, true, 1f, 15_350L));
        assertEquals(185_450L, policy.resolveEffectivePositionMillis(185_450L, true, 1f, 15_600L));
        assertFalse(policy.isPhaseLocking(15_600L));

        // Verify MAX_BUFFERING_HOLD_MS ceiling prevents infinite freeze on broken streams.
        LyricScrubSyncPolicy capped = new LyricScrubSyncPolicy();
        capped.commitScrub(50_000L, true, 1_000L);
        for (long t = 2_000L; t <= 8_500L; t += 1_500L) {
            capped.observePlaybackState(
                    LyricScrubSyncPolicy.STATE_BUFFERING, 50_000L, t, false, 0f, t);
        }
        assertEquals(LyricScrubSyncPolicy.Phase.COMMITTING, capped.phase(8_999L));
        assertEquals(LyricScrubSyncPolicy.Phase.IDLE, capped.phase(9_000L));
    }

    @Test
    public void colorOsWholeSecondFloorHoldsMonotonicallyUntilPlaybackCatchesUp() {
        LyricScrubSyncPolicy policy = new LyricScrubSyncPolicy();
        policy.beginScrub(20_000L, 20_000L, true, 1f, 9_800L);
        policy.updateScrub(130_700L, 9_950L);
        policy.commitScrub(130_700L, true, 10_000L);

        // ColorOS truncates seekTo to whole seconds (130_000ms = -700ms behind 130_700ms).
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.ACCEPT_COMMITTED_SEEK,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 130_000L, 10_140L, true, 1f, 10_150L));
        assertTrue(policy.isPhaseLocking(10_150L));
        assertEquals(130_700L, policy.resolveEffectivePositionMillis(130_000L, true, 1f, 10_150L));

        // Intermediate periodic STATE_PLAYING while climbing 130_000 -> 130_700 keeps original
        // phase-lock deadline and lets underlying clock adopt real position without rewinding UI.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.ACCEPT_COMMITTED_SEEK,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 130_250L, 10_400L, true, 1f, 10_400L));
        assertEquals(130_700L, policy.resolveEffectivePositionMillis(130_250L, true, 1f, 10_400L));
        assertEquals(130_700L, policy.resolveEffectivePositionMillis(130_699L, true, 1f, 10_849L));

        // The moment real playback reaches 130_700ms, phase-lock releases seamlessly to 1:1.
        assertEquals(130_700L, policy.resolveEffectivePositionMillis(130_700L, true, 1f, 10_850L));
        assertFalse(policy.isPhaseLocking(10_850L));
        assertEquals(130_950L, policy.resolveEffectivePositionMillis(130_950L, true, 1f, 11_100L));

        // Safety timeout unlocks if a player stalls below hold anchor for >= POST_SEEK_BACKWARD_HOLD_MAX_MS.
        LyricScrubSyncPolicy stalled = new LyricScrubSyncPolicy();
        stalled.commitScrub(130_700L, true, 20_000L);
        stalled.observePlaybackState(
                LyricScrubSyncPolicy.STATE_PLAYING, 130_000L, 20_100L, true, 1f, 20_100L);
        assertEquals(130_700L, stalled.resolveEffectivePositionMillis(130_200L, true, 1f, 21_899L));
        assertEquals(130_200L, stalled.resolveEffectivePositionMillis(130_200L, true, 1f, 21_900L));
        assertFalse(stalled.isPhaseLocking(21_900L));
    }

    @Test
    public void delayedSeekConfirmationAheadOfCommittedTargetSlewsForwardMonotonically() {
        LyricScrubSyncPolicy policy = new LyricScrubSyncPolicy();
        policy.beginScrub(10_000L, 10_000L, true, 1f, 9_800L);
        policy.commitScrub(130_000L, true, 10_000L);

        // Delayed MediaSession confirmation reports audio already at 130_700ms (+700ms ahead).
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.ACCEPT_COMMITTED_SEEK,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 130_700L, 10_840L, true, 1f, 10_850L));
        assertTrue(policy.isPhaseLocking(10_850L));

        long prev = policy.resolveEffectivePositionMillis(130_700L, true, 1f, 10_850L);
        assertEquals(130_000L, prev);
        for (int dt = 30; dt <= 480; dt += 30) {
            long realPos = 130_700L + dt;
            long slewed = policy.resolveEffectivePositionMillis(realPos, true, 1f, 10_850L + dt);
            assertTrue("Expected monotonic forward slew at dt=" + dt, slewed >= prev);
            assertTrue("Expected slewed <= realPos at dt=" + dt, slewed <= realPos);
            prev = slewed;
        }
        // Locks 1:1 promptly by POST_SEEK_FORWARD_SLEW_MAX_DURATION_MS.
        long locked = policy.resolveEffectivePositionMillis(131_250L, true, 1f, 11_400L);
        assertEquals(131_250L, locked);
        assertFalse(policy.isPhaseLocking(11_400L));
    }

    @Test
    public void postConvergeGuardBlocksLaggingSeedlingBundlesWithOldOrMissingTimestamps() {
        LyricScrubSyncPolicy policy = new LyricScrubSyncPolicy();
        policy.beginScrub(30_000L, 30_000L, true, 1f, 10_000L);
        policy.updateScrub(130_700L, 10_400L);
        policy.commitScrub(130_700L, true, 10_450L);

        // MediaController converges promptly at t=10_600.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.ACCEPT_COMMITTED_SEEK,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 130_700L, 10_595L, true, 1f, 10_600L));
        assertTrue(policy.isPostSeekGuardActive(10_600L));

        // Lagging Seedling bundle with pre-commit timestamp is rejected cleanly.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.IGNORE_STALE_POSITION,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 31_100L, 10_350L, true, 1f, 11_100L));
        assertFalse(policy.shouldApplyPlaybackFlagsWhenIgnored(
                LyricScrubSyncPolicy.STATE_PAUSED, 10_350L, 11_100L));

        // Lagging Seedling bundle omitting timestamp (-1) while carrying pre-seek timeline is also rejected.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.IGNORE_STALE_POSITION,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 31_400L, -1L, true, 1f, 11_400L));

        // Legitimate fresh seek to an unrelated position passes straight through.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.PASS_THROUGH,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 200_000L, 11_600L, true, 1f, 11_600L));

        // Guard expires cleanly after POST_CONVERGE_GUARD_MS.
        assertFalse(policy.isPostSeekGuardActive(13_101L));
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.PASS_THROUGH,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 33_200L, -1L, true, 1f, 13_200L));
    }

    @Test
    public void audioTrackPrimingBackwardDipAfterConvergenceHoldsWithoutRewinding() {
        LyricScrubSyncPolicy policy = new LyricScrubSyncPolicy();
        policy.beginScrub(20_000L, 20_000L, true, 1f, 9_800L);
        policy.commitScrub(130_000L, true, 10_000L);

        // Premature STATE_PLAYING before AudioTrack priming finishes.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.ACCEPT_COMMITTED_SEEK,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 130_000L, 10_120L, true, 1f, 10_120L));
        assertEquals(130_500L, policy.resolveEffectivePositionMillis(130_500L, true, 1f, 10_620L));

        // AudioTrack priming finishes and republishes 130_050ms (-450ms dip behind displayed 130_500ms).
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.ACCEPT_COMMITTED_SEEK,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 130_050L, 10_620L, true, 1f, 10_620L));
        assertTrue(policy.isPhaseLocking(10_620L));
        assertEquals(130_500L, policy.resolveEffectivePositionMillis(130_050L, true, 1f, 10_620L));
        assertEquals(130_500L, policy.resolveEffectivePositionMillis(130_300L, true, 1f, 10_870L));
        assertEquals(130_550L, policy.resolveEffectivePositionMillis(130_550L, true, 1f, 11_120L));
        assertFalse(policy.isPhaseLocking(11_120L));
    }

    @Test
    public void briefPostConvergeRebufferingReentersCommitHoldWithoutBounce() {
        LyricScrubSyncPolicy policy = new LyricScrubSyncPolicy();
        policy.beginScrub(15_000L, 15_000L, true, 1f, 9_800L);
        policy.commitScrub(130_000L, true, 10_000L);
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.ACCEPT_COMMITTED_SEEK,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 130_000L, 10_150L, true, 1f, 10_150L));
        assertEquals(130_150L, policy.resolveEffectivePositionMillis(130_150L, true, 1f, 10_300L));

        // Player stalls to load rest of segment and emits STATE_BUFFERING.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.IGNORE_STALE_POSITION,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_BUFFERING, 130_100L, 10_300L, false, 0f, 10_300L));
        assertEquals(LyricScrubSyncPolicy.Phase.COMMITTING, policy.phase(10_300L));
        assertTrue(policy.isBufferingAfterCommit(10_300L));
        assertEquals(130_150L, policy.resolveEffectivePositionMillis(0L, true, 1f, 11_500L));

        // Resumes cleanly at 130_100ms without rewinding below 130_150ms.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.ACCEPT_COMMITTED_SEEK,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 130_100L, 11_600L, true, 1f, 11_600L));
        assertEquals(130_150L, policy.resolveEffectivePositionMillis(130_100L, true, 1f, 11_600L));
        assertEquals(130_200L, policy.resolveEffectivePositionMillis(130_200L, true, 1f, 11_700L));
    }

    @Test
    public void smallDragRejectsImmediatePreSeekResidualAndAcceptsRealPostSeek() {
        LyricScrubSyncPolicy policy = new LyricScrubSyncPolicy();
        policy.beginScrub(30_000L, 30_000L, true, 1f, 10_000L);
        policy.updateScrub(31_500L, 10_150L);
        policy.commitScrub(31_500L, true, 10_200L);

        // Residual pre-seek tick (within 50ms timestamp slack, sitting on un-seeked 30_230ms timeline).
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.IGNORE_STALE_POSITION,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 30_230L, 10_190L, true, 1f, 10_230L));
        assertEquals(LyricScrubSyncPolicy.Phase.COMMITTING, policy.phase(10_230L));

        // Real post-seek tick at 31_000ms (whole-second seek) converges and holds at 31_500ms.
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.ACCEPT_COMMITTED_SEEK,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 31_000L, 10_345L, true, 1f, 10_350L));
        assertEquals(31_500L, policy.resolveEffectivePositionMillis(31_200L, true, 1f, 10_550L));
        assertEquals(31_550L, policy.resolveEffectivePositionMillis(31_550L, true, 1f, 10_900L));
    }

    @Test
    public void rescrubDuringCommitPreservesPreSeekAnchorAndPausedOrStoppedBehaveCleanly() {
        LyricScrubSyncPolicy policy = new LyricScrubSyncPolicy();
        policy.beginScrub(30_000L, 30_000L, true, 1f, 10_000L);
        policy.updateScrub(120_000L, 10_200L);
        policy.commitScrub(120_000L, true, 10_300L);

        // User grabs SeekBar again before first seek finishes; original pre-seek (30_000) is preserved.
        policy.beginScrub(120_000L, 120_000L, true, 1f, 10_500L);
        policy.updateScrub(180_000L, 10_700L);
        policy.commitScrub(180_000L, true, 10_800L);
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.ACCEPT_COMMITTED_SEEK,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 180_000L, 10_950L, true, 1f, 10_950L));

        // Lagging Seedling snapshot carrying the original 30s timeline is still rejected!
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.IGNORE_STALE_POSITION,
                policy.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PLAYING, 31_100L, -1L, true, 1f, 11_100L));

        // Paused seek converges cleanly without engaging an infinite backward-hold.
        LyricScrubSyncPolicy paused = new LyricScrubSyncPolicy();
        paused.beginScrub(10_000L, 10_000L, false, 0f, 1_000L);
        paused.commitScrub(60_700L, false, 1_200L);
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.ACCEPT_COMMITTED_SEEK,
                paused.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_PAUSED, 60_000L, 1_350L, false, 0f, 1_350L));
        assertFalse(paused.isPhaseLocking(1_350L));
        assertEquals(60_000L, paused.resolveEffectivePositionMillis(60_000L, false, 0f, 1_350L));

        // STATE_STOPPED during COMMITTING exits cleanly to PASS_THROUGH.
        LyricScrubSyncPolicy stopped = new LyricScrubSyncPolicy();
        stopped.commitScrub(40_000L, true, 2_000L);
        assertEquals(
                LyricScrubSyncPolicy.PlaybackArbitration.PASS_THROUGH,
                stopped.observePlaybackState(
                        LyricScrubSyncPolicy.STATE_STOPPED, 0L, 2_100L, false, 0f, 2_100L));
        assertEquals(LyricScrubSyncPolicy.Phase.IDLE, stopped.phase(2_100L));
    }

    @Test
    public void secondaryVocalWaitingDotsAndExitTailRespondCleanlyToScrubbing() {
        WordLine vocal = new WordLine(
                12_000L,
                "backing vocal",
                new ArrayList<>(Collections.singletonList(new WordRange(12_000L, 0, 13, 13_500L))),
                13_500L,
                LyricTimingMode.WORD_TIMED);
        WordLine main = new WordLine(
                10_000L,
                "main vocal",
                new ArrayList<>(Arrays.asList(
                        new WordRange(10_000L, 0, 4, 11_000L),
                        new WordRange(11_000L, 5, 10, 14_000L))),
                14_000L,
                LyricTimingMode.WORD_TIMED);
        main.secondaryLine = vocal;

        // Apple style: secondary vocal appears as soon as scrubbed into singing main line.
        assertTrue(LyricSecondaryLayoutPolicy.isVocalVisible(main, true, 10_200L, true));
        assertFalse(LyricSecondaryLayoutPolicy.isVocalVisible(main, false, 10_200L, true));
        // Standard style: scrubbing forward/backward across threshold (12_000 - 300 = 11_700) toggles visibility.
        assertFalse(LyricSecondaryLayoutPolicy.isVocalVisible(main, true, 11_200L, false));
        assertTrue(LyricSecondaryLayoutPolicy.isVocalVisible(main, true, 11_800L, false));
        assertFalse(LyricSecondaryLayoutPolicy.isVocalVisible(main, true, 11_100L, false));

        // Waiting dots row expands inside break window and collapses at -400ms headstart.
        WordLine dots = new WordLine(
                0L,
                LyricTextSanitizer.WAITING_DOTS_MARKER,
                new ArrayList<>(),
                10_000L,
                LyricTimingMode.LINE_TIMED);
        dots.isWaitingDots = true;
        assertTrue(LyricWaitingDotsPolicy.shouldExpandWaitingDotsLayout(dots, 5_000L, true));
        assertFalse(LyricWaitingDotsPolicy.shouldExpandWaitingDotsLayout(dots, 9_700L, true));
        LyricWaitingDotsPolicy.DotFrameState midBreak =
                LyricWaitingDotsPolicy.evaluateDots(0L, 10_000L, 5_000L, true, false);
        assertTrue(midBreak.anyVisible);

        // Backward scrub discards stale highlight exit tail immediately.
        LyricLineExitPolicy exit = new LyricLineExitPolicy();
        exit.sample(true, 12_500L, 1_000L, true, 14_000L, 280L);
        exit.sample(false, 8_000L, 1_016L, true, 14_000L, 280L);
        assertFalse(exit.isExiting());
        assertEquals(0f, exit.highlightAmount(), 0.0001f);
    }
}
