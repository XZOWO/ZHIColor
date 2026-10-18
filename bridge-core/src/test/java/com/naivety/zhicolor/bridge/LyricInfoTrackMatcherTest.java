package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LyricInfoTrackMatcherTest {
    @Test
    public void rawTrackHintMismatchMakesPayloadStale() throws Exception {
        LyricInfoContract.Payload payload = payload(
                "Instrumental Gap",
                "Artist B",
                "",
                "[ti:Previous Song]\n[ar:Artist A]\n[00:00]old line");

        assertFalse(LyricInfoTrackMatcher.payloadMatchesTrack(
                payload,
                "Instrumental Gap",
                "Artist B"));
    }

    @Test
    public void nativePayloadUsesOriginalTrackKeyAfterDisplayCleanup() {
        String originalKey = TrackIdentity.buildKey("Song (Live)", "Artist feat. Guest");
        LyricInfoContract.Payload payload = new LyricInfoContract.Payload(
                "Song",
                "Artist",
                "Album",
                "id",
                "[00:00]line",
                "[00:00]line",
                "",
                "native-test",
                originalKey,
                1L,
                "test");

        assertTrue(LyricInfoTrackMatcher.payloadMatchesTrack(
                payload,
                "Song (Live)",
                "Artist feat. Guest"));
    }

    @Test
    public void providerCoreStableTrackKeyMatchesItsSystemUiTitleAndArtist() {
        LyricInfoContract.Payload payload = new LyricInfoContract.Payload(
                "I Knew It, I Knew You (Piano Version)",
                "Taylor Swift",
                "",
                "",
                "[00:00]line",
                "[00:00]line",
                "",
                "com.lxwalnut.music.mobile",
                "noid|i knew it, i knew you (piano version)|taylor swift|190",
                4L,
                "com.lxwalnut.music.mobile-v5");

        assertTrue(LyricInfoTrackMatcher.payloadMatchesTrack(
                payload,
                "I Knew It, I Knew You (Piano Version)",
                "Taylor Swift"));
        assertFalse(LyricInfoTrackMatcher.payloadMatchesTrack(
                payload,
                "I Knew It, I Knew You (Acoustic Version)",
                "Taylor Swift"));
    }

    @Test
    public void matchingStableKeyWinsOverDashedFirstLyric() {
        LyricInfoContract.Payload payload = new LyricInfoContract.Payload(
                "Song", "Artist", "", "", "[00:01]A - B", "[00:01]A - B", "",
                "provider", "id|song|artist|180", 7L, "provider-v5");

        assertTrue(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "Song", "Artist"));
    }

    @Test
    public void identitylessPayloadDoesNotMatchArbitraryTrack() {
        LyricInfoContract.Payload payload = payload("", "", "", "[00:01]old line");

        assertFalse(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "New Song", "Artist"));
    }

    @Test
    public void lyriconPlayerKeyMatchesTitleAndArtistAndRejectsOtherTracks() {
        LyricInfoContract.Payload payload = lyriconPayload("123", "Song", "Artist");

        assertTrue(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "Song", "Artist"));
        assertFalse(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "Other Song", "Artist"));
        assertFalse(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "Song", "Other Artist"));
    }

    @Test
    public void lyriconNumericArtistIsNotInterpretedAsProviderDuration() {
        LyricInfoContract.Payload payload = lyriconPayload("", "Song", "1975");

        assertTrue(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "Song", "1975"));
        assertFalse(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "1975", "Song"));
    }

    @Test
    public void lyriconKeyPreservesLiteralPipesInSongFields() {
        LyricInfoContract.Payload payload = lyriconPayload("id|part", "Song | Title", "A | B");

        assertTrue(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "Song | Title", "A | B"));
        assertFalse(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "Song", "A | B"));
        assertFalse(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "Song | Title", "Other"));
    }

    @Test
    public void lyriconStableKeyStillRejectsMismatchedPayloadNames() {
        LyricInfoContract.Payload payload = new LyricInfoContract.Payload(
                "New Song", "Artist", "", "123", "[00:01]line", "[00:01]line", "",
                "lyricon", "com.apple.android.music|123|Previous Song|Artist", 0L, "lyricon");

        assertFalse(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "New Song", "Artist"));
        assertTrue(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "Previous Song", "Artist"));
    }

    private static LyricInfoContract.Payload lyriconPayload(String id, String title, String artist) {
        return new LyricInfoContract.Payload(title, artist, "", id,
                "[00:01]line", "[00:01]line", "", "lyricon",
                "com.apple.android.music|" + id + '|' + title + '|' + artist, 0L, "lyricon");
    }

    private static LyricInfoContract.Payload payload(
            String title,
            String artist,
            String provider,
            String rawLyric) {
        return new LyricInfoContract.Payload(
                title,
                artist,
                "",
                "",
                rawLyric,
                rawLyric,
                "",
                provider,
                "",
                0L,
                "");
    }
}
