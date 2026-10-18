package com.naivety.zhicolor.lyrics
import io.github.proify.lyricon.lyric.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class LyricSourceTest {
 @Test fun sourceRoutesAreOnlyLyriconEcosystem(){assertEquals(LyricSourceMode.OFF,LyricSourceMode.choose(false,true));assertEquals(LyricSourceMode.OFFICIAL,LyricSourceMode.choose(true,true));assertEquals(LyricSourceMode.DIRECT_PROVIDER,LyricSourceMode.choose(true,false))}
 @Test fun enhancedLyricsKeepAbsoluteWordTimesAndTranslations(){
  val song=Song(id="1",name="Song",artist="Artist",lyrics=listOf(RichLyricLine(begin=1000,end=2000,text="hello",words=listOf(LyricWord(begin=1100,end=1800,text="hello")),translation="你好")))
  val json=JSONObject(LyriconPayloadEncoder.encode(song,"com.example.music")!!)
  assertEquals("lyricon",json.getString("provider"));assertTrue(json.getString("rawLyric").contains("<00:01.100>hello<00:01.800>"));assertTrue(json.getString("translationLyric").contains("[00:01.000]你好"))
 }
 @Test fun sameTitleDifferentArtistIsNotTheSameTrack(){assertFalse(LyriconPayloadEncoder.matches(Song(name="Song",artist="A"),"Song","B"))}
 @Test fun qqTitleInArtistMetadataCanMatch(){assertTrue(LyriconPayloadEncoder.matches(Song(name="Song",artist="Artist"),"current lyric","Song - Artist"))}
 @Test fun textOnlyProviderUsesEventRowsNotAudioProgress(){val window=LyricTextWindow();for(i in 0..6)window.accept("line$i","track");val json=JSONObject(window.encode("Song","Artist","track")!!);assertEquals("sentence-window-v1",json.getString("displayMode"));assertEquals(4,json.getInt("currentLine"));assertEquals(5,json.getString("lyric").lines().size);assertFalse(json.has("position"));window.clear();assertFalse(window.available("track"))}
 @Test fun emptyLyricsDoNotInventPayload(){assertNull(LyriconPayloadEncoder.encode(Song(name="Song"),"com.example.music"))}
}
