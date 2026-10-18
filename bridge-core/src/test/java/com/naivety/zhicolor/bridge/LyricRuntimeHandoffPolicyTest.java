package com.naivety.zhicolor.bridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class LyricRuntimeHandoffPolicyTest {
    private static final String PLAYER = "com.apple.android.music";

    @Test public void firstConnectionWithoutLyricsDoesNotClaimNativeRenderer() {
        LyricRuntimeHandoffPolicy policy = new LyricRuntimeHandoffPolicy();
        assertFalse(policy.active(PLAYER, true, false, 1_000L));
        assertEquals(0L, policy.begin(PLAYER, true, false, "first", 1_000L));
    }

    @Test public void temporarySourceGapRetainsOwnershipForOneBoundedWindow() {
        LyricRuntimeHandoffPolicy policy = connected();
        assertTrue(policy.active(PLAYER, true, false, 2_000L));
        assertEquals(8_000L, policy.pendingUntil(PLAYER, 2_000L));
        assertTrue(policy.active(PLAYER, true, false, 7_999L));
        assertFalse(policy.active(PLAYER, true, false, 8_000L));
        assertFalse(policy.active(PLAYER, true, false, 20_000L));
        assertEquals(0L, policy.pendingUntil(PLAYER, 20_000L));
    }

    @Test public void repeatedMetadataMissDoesNotRenewSameTrackDeadline() {
        LyricRuntimeHandoffPolicy policy = connected();
        assertEquals(8_000L, policy.begin(PLAYER, true, true, "song-two", 2_000L));
        assertEquals(8_000L, policy.begin(PLAYER, true, false, "song-two", 5_000L));
        assertEquals(8_000L, policy.begin(PLAYER, true, false, "song-two", 20_000L));
        assertFalse(policy.active(PLAYER, true, false, 20_000L));
    }

    @Test public void anotherTrackGetsItsOwnWindowWhileFastSkipping() {
        LyricRuntimeHandoffPolicy policy = connected();
        policy.begin(PLAYER, true, true, "song-two", 2_000L);
        assertEquals(9_000L, policy.begin(PLAYER, true, false, "song-three", 3_000L));
        assertFalse(policy.matchesDeadline(PLAYER, 8_000L));
        assertTrue(policy.matchesDeadline(PLAYER, 9_000L));
    }

    @Test public void newPayloadDoesNotReleaseGeometryBarrierBeforeCommit() {
        LyricRuntimeHandoffPolicy policy = connected();
        policy.begin(PLAYER, true, true, "song-two", 2_000L);
        assertTrue(policy.active(PLAYER, true, true, 3_000L));
        assertEquals(8_000L, policy.pendingUntil(PLAYER, 3_000L));
        policy.complete(PLAYER);
        assertEquals(0L, policy.pendingUntil(PLAYER, 3_000L));
        assertFalse(policy.matchesDeadline(PLAYER, 8_000L));
        assertTrue(policy.active(PLAYER, true, true, 3_001L));
    }

    @Test public void completedModelRetainsEligibilityForNextSourceGap() {
        LyricRuntimeHandoffPolicy policy = connected();
        policy.begin(PLAYER, true, true, "song-two", 2_000L);
        policy.complete(PLAYER);
        assertTrue(policy.active(PLAYER, true, false, 4_000L));
        assertEquals(10_000L, policy.pendingUntil(PLAYER, 4_000L));
    }

    @Test public void disabledModuleImmediatelyDropsOwnershipAndHistory() {
        LyricRuntimeHandoffPolicy policy = connected();
        assertFalse(policy.active(PLAYER, false, true, 2_000L));
        assertFalse(policy.active(PLAYER, true, false, 2_001L));
        assertEquals(0L, policy.pendingUntil(PLAYER, 2_001L));
    }

    @Test public void playerChangeCannotBorrowPreviousPlayersOwnership() {
        LyricRuntimeHandoffPolicy policy = connected();
        assertTrue(policy.active(PLAYER, true, false, 2_000L));
        assertFalse(policy.active("com.other.player", true, false, 2_100L));
        assertFalse(policy.active(PLAYER, true, false, 2_200L));
    }

    @Test public void staleCompletionCannotClearOtherPlayerDeadline() {
        LyricRuntimeHandoffPolicy policy = connected();
        policy.begin(PLAYER, true, true, "song-two", 2_000L);
        policy.complete("com.other.player");
        assertEquals(8_000L, policy.pendingUntil(PLAYER, 3_000L));
    }

    private static LyricRuntimeHandoffPolicy connected() {
        LyricRuntimeHandoffPolicy policy = new LyricRuntimeHandoffPolicy();
        assertTrue(policy.active(PLAYER, true, true, 1_000L));
        return policy;
    }
}
