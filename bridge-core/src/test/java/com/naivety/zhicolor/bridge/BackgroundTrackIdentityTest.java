package com.naivety.zhicolor.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackgroundTrackIdentityTest {
    private String qq(String title, String credit) {
        return BackgroundTrackIdentity.key("com.tencent.qqmusic", "qq-track-1", null, title, credit, "Superstar (feat. Maria Mathea)");
    }
    @Test public void realQqLyricCallbacksDoNotChangeTrack() {
        String expected = qq("Are are", "Superstar-Mandee/Maria Mathea");
        for(String lyric : new String[]{"I don't know what it is", "That makes me feel like this",
                "I", "I don't know", "I don't know who you are",
                "But you must be some kind of superstar", "Wooh can't we get a little closer",
                "You rock it just like your supposed ta", "Hey boy I ain't got nothin' more to say"})
            assertEquals(expected, qq(lyric, "Superstar-Mandee/Maria Mathea"));
    }
    @Test public void qqNextSongWithSameAlbumAndArtistStillChangesTrack() {
        assertNotEquals(qq("lyric", "Superstar-Mandee/Maria Mathea"),
                BackgroundTrackIdentity.key("com.tencent.qqmusic", "qq-track-2", null, "lyric",
                        "Superstar-Mandee/Maria Mathea", "Superstar (feat. Maria Mathea)"));
    }
    @Test public void qqWithoutLyricPublishingKeepsTitleIdentity() {
        assertNotEquals(BackgroundTrackIdentity.key("com.tencent.qqmusic", null, null, "First", "IN-K", "album"),
                BackgroundTrackIdentity.key("com.tencent.qqmusic", null, null, "Second", "IN-K", "album"));
    }
    @Test public void stableMediaIdIgnoresDisplayAndAlbumRefreshes() {
        assertEquals(BackgroundTrackIdentity.key("player", "track-1", null, "line1", "artist", null),
                BackgroundTrackIdentity.key("player", "track-1", null, "line2", "artist updated", "album"));
    }
    @Test public void realMediaIdChangesEvenWithIdenticalCoverCredits() {
        assertNotEquals(BackgroundTrackIdentity.key("player", "1", null, "song", "artist", "album"),
                BackgroundTrackIdentity.key("player", "2", null, "song", "artist", "album"));
    }
    @Test public void stableMediaUriIgnoresDisplayUpdates() {
        assertEquals(BackgroundTrackIdentity.key("player", null, "content://track/1", "line1", null, null),
                BackgroundTrackIdentity.key("player", null, "content://track/1", "line2", null, null));
    }
    @Test public void otherPlayersRetainTitleIdentity() {
        assertNotEquals(BackgroundTrackIdentity.key("other", null, null, "first", "a-b", "album"),
                BackgroundTrackIdentity.key("other", null, null, "second", "a-b", "album"));
    }
    @Test public void emptyMetadataIsNotANewSong() {
        assertEquals("", BackgroundTrackIdentity.key("player", null, null, null, null, null));
    }
    @Test public void delimitersCannotCauseKeyCollisions() {
        assertNotEquals(BackgroundTrackIdentity.key("other", null, null, "a;b", "c", null),
                BackgroundTrackIdentity.key("other", null, null, "a", "b;c", null));
    }
}
