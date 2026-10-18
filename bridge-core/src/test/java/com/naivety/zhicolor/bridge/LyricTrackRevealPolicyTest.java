package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LyricTrackRevealPolicyTest {
    @Test public void asyncLyricsAndTranslationRefreshDoNotReplayEntrance() {
        LyricTrackRevealPolicy policy = new LyricTrackRevealPolicy();
        policy.observe("song|artist");
        assertTrue(policy.consumeCommittedTrack("provider-id"));
        policy.observe("song|artist");
        assertFalse(policy.consumeCommittedTrack("provider-id"));
        assertFalse(policy.consumeCommittedTrack("enriched-provider-id"));
    }

    @Test public void switchingAwayAndReturningRevealsEachTrack() {
        LyricTrackRevealPolicy policy = new LyricTrackRevealPolicy();
        policy.observe("a|artist");
        assertTrue(policy.consumeCommittedTrack("a"));
        policy.observe("b|artist");
        assertTrue(policy.consumeCommittedTrack("b"));
        policy.observe("a|artist");
        assertTrue(policy.consumeCommittedTrack("a"));
        assertFalse(policy.consumeCommittedTrack("a"));
    }

    @Test public void noLyricsIntermediateTrackStillAdvancesEntrance() {
        LyricTrackRevealPolicy policy = new LyricTrackRevealPolicy();
        policy.observe("a|artist");
        assertTrue(policy.consumeCommittedTrack("a"));
        policy.observe("instrumental|artist");
        policy.observe("a|artist");
        assertTrue(policy.consumeCommittedTrack("a"));
    }

    @Test public void emptyMetadataAndLateIdentityDoNotReplayCommittedTrack() {
        LyricTrackRevealPolicy policy = new LyricTrackRevealPolicy();
        assertFalse(policy.consumeCommittedTrack(""));
        policy.observe("|");
        assertTrue(policy.consumeCommittedTrack("a"));
        policy.observe("a|artist");
        assertFalse(policy.consumeCommittedTrack("a"));
        policy.observe(null);
        assertFalse(policy.consumeCommittedTrack("a"));
    }

    @Test public void payloadIdentityWorksBeforeMetadataIsAvailable() {
        LyricTrackRevealPolicy policy = new LyricTrackRevealPolicy();
        assertTrue(policy.consumeCommittedTrack("a"));
        assertFalse(policy.consumeCommittedTrack("a"));
        assertTrue(policy.consumeCommittedTrack("b"));
    }

    @Test public void lateArtistAndEquivalentMetadataDoNotReplayEntrance() {
        LyricTrackRevealPolicy policy = new LyricTrackRevealPolicy();
        policy.observe(TrackIdentity.buildKey("Song", ""));
        assertTrue(policy.consumeCommittedTrack("a"));
        policy.observe(TrackIdentity.buildKey("Song (Explicit)", "Artist"));
        assertFalse(policy.consumeCommittedTrack("a"));
        policy.observe(TrackIdentity.buildKey("Song", "Other Artist"));
        assertTrue(policy.consumeCommittedTrack("b"));
    }

    @Test public void firstKnownMetadataOnAnotherTrackDoesNotHideItsEntrance() {
        LyricTrackRevealPolicy policy = new LyricTrackRevealPolicy();
        assertTrue(policy.consumeCommittedTrack("a"));
        policy.observe("b|artist");
        assertTrue(policy.consumeCommittedTrack("b"));
    }
}
