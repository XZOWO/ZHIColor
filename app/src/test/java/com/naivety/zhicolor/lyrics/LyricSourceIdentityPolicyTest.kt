package com.naivety.zhicolor.lyrics

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricSourceIdentityPolicyTest {
    private val song=Song(name="Old Song",artist="Artist",lyrics=listOf(
        RichLyricLine(begin=1_000L,end=2_000L,text="old words")))

    @Test fun cachedTimedSongIsUnavailableForNextSessionTrack() {
        assertFalse(LyricSourceIdentityPolicy.timedPayloadAvailable(song,"encoded","Next Song","Artist"))
        assertTrue(LyricSourceIdentityPolicy.timedPayloadAvailable(song,"encoded","Old Song","Artist"))
    }
    @Test fun incompleteMetadataAndMissingEncodingDoNotClaimAvailability() {
        assertFalse(LyricSourceIdentityPolicy.timedPayloadAvailable(song,"encoded",null,null))
        assertFalse(LyricSourceIdentityPolicy.timedPayloadAvailable(song,null,"Old Song","Artist"))
        assertFalse(LyricSourceIdentityPolicy.timedPayloadAvailable(song,"encoded","Old Song","Other Artist"))
    }
    @Test fun trackKeyIncludesTitleEvenWhenPlayerReusesMediaId() {
        val old=track("Old Song")
        val next=track("Next Song")
        assertNotEquals(old,next)
        val window=LyricTextWindow()
        window.accept("old words",old)
        assertFalse(window.available(next))
        assertNull(window.encode("Next Song","Artist",next))
        assertTrue(window.available(old))
    }
    @Test fun metadataChangeDiscardsOldTextRowsBeforeNewTextArrives() {
        val window=LyricTextWindow()
        window.accept("old words",track("Old Song"))
        window.retainOnlyTrack(track("Next Song"))
        assertFalse(window.available(track("Old Song")))
        assertFalse(window.available(track("Next Song")))
    }
    @Test fun firstEventForNextTrackStartsFreshWindow() {
        val window=LyricTextWindow()
        window.accept("old words",track("Old Song"))
        window.accept("new words\n新翻译",track("Next Song"))
        val json=JSONObject(window.encode("Next Song","Artist",track("Next Song"))!!)
        assertEquals(0,json.getInt("currentLine"))
        assertFalse(json.getString("lyric").contains("old words"))
        assertTrue(json.getString("translationLyric").contains("新翻译"))
    }
    @Test fun eventWithoutSessionIdentityDoesNotKeepAnUnboundWindow() {
        val window=LyricTextWindow()
        window.accept("old words",track("Old Song"))
        window.accept("unbound words","")
        assertFalse(window.available(track("Old Song")))
        assertNull(window.encode("Next Song","Artist",track("Next Song")))
    }
    private fun track(title:String)=LyricSourceIdentityPolicy.trackKey(
        "com.apple.android.music","reused-media-id",title,"Artist",100_000L)
}
