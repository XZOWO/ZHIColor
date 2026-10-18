package com.naivety.zhicolor.bridge
import com.naivety.zhicolor.lyrics.LyriconPayloadEncoder
import io.github.proify.lyricon.lyric.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class LyriconRenderPipelineTest {
 @Test fun originalEnglishSpacingAndRealAppleVocalTranslationSurviveCompletePipeline(){
  val line=RichLyricLine(begin=1000,end=8000,text="He  did it, didn't he?",words=listOf(
   LyricWord(begin=1000,end=1200,text="He"),LyricWord(begin=1500,end=1700,text="did"),LyricWord(begin=2000,end=2200,text="it"),LyricWord(begin=2500,end=2700,text="didn't"),LyricWord(begin=3000,end=3200,text="he")),
   translation="主句翻译",secondary="Don't stop!",secondaryWords=listOf(LyricWord(begin=4500,end=4700,text="Don"),LyricWord(begin=5000,end=5200,text="t"),LyricWord(begin=5500,end=5700,text="stop")),
   metadata=LyricMetadata(mapOf("secondaryTranslation" to "不要停！")))
  val payload=LyricInfoContract.parse(LyriconPayloadEncoder.encode(Song(name="Song",lyrics=listOf(line)),"com.apple.android.music")!!)!!
  assertEquals("He  did it, didn't he?",payload.rawLyric.replace(Regex("[\\[<][0-9:.]+[\\]>]"),"").trimEnd())
  val result=assembled(payload).lines.single();val vocal=result.secondaryLine!!
  assertEquals("He  did it, didn't he?",result.text);assertEquals("did",result.text.substring(result.words[1].start,result.words[1].end))
  assertEquals("Don't stop!",vocal.text);assertEquals("不要停！",vocal.translation);assertEquals("主句翻译",result.translation)
  assertEquals(4500L,vocal.firstProgressStartMillis());assertEquals(4700L,vocal.wordEndMillis(0));assertNull(vocal.translationLine)
  val secondary=JSONObject(payload.lineMetadata.let {"{\"rows\":$it}"}).getJSONArray("rows").getJSONObject(0).getJSONObject("secondary")
  assertFalse(secondary.has("translationWords"))
 }
 @Test fun missingAppleBackgroundTranslationDoesNotBorrowMainOrCreatePlaceholder(){
  val lines=listOf(RichLyricLine(begin=1000,end=8000,text="lead",translation="主句翻译",secondary="echo",secondaryWords=listOf(LyricWord(begin=4500,end=4700,text="echo"))),
   RichLyricLine(begin=9000,end=10000,text="next",metadata=LyricMetadata(mapOf("secondaryTranslation" to "orphan"))))
  val payload=LyricInfoContract.parse(LyriconPayloadEncoder.encode(Song(name="Song",lyrics=lines),"com.apple.android.music")!!)!!
  val model=assembled(payload);assertEquals("主句翻译",model.lines[0].translation);assertEquals("",model.lines[0].secondaryLine!!.translation)
  assertNull(model.lines[0].secondaryLine!!.translationLine);assertNull(model.lines[1].secondaryLine)
 }
 private fun assembled(payload:LyricInfoContract.Payload):com.naivety.zhicolor.bridge.render.WordLyricModel {
  val sink=object:LyricParseTraceSink{override fun traceEnabled()=false;override fun trace(message:String){};override fun onCoreParseFailure(primarySource:Boolean,failure:Throwable){}}
  return NativeLyricModelAssembler(sink).assemble(payload.rawLyric,true,payload.lyric,true,payload.translationLyric,payload.lineMetadata).model
 }
 @Test fun independentVocalAndTranslationSurviveEncoderToRenderer(){
  val song=Song(name="Song",lyrics=listOf(RichLyricLine(begin=1000,end=7000,text="lead",translation="真正翻译",secondary="echo voice",secondaryWords=listOf(LyricWord(begin=3500,end=3700,text="echo "),LyricWord(begin=4500,end=4700,text="voice")))))
  val payload=LyricInfoContract.parse(LyriconPayloadEncoder.encode(song,"com.apple.android.music")!!)!!
  assertFalse(payload.rawLyric.contains("echo"));assertFalse(payload.lyric.contains("echo"))
  val sink=object:LyricParseTraceSink{override fun traceEnabled()=false;override fun trace(message:String){};override fun onCoreParseFailure(primarySource:Boolean,failure:Throwable){}}
  val model=NativeLyricModelAssembler(sink).assemble(payload.rawLyric,true,payload.lyric,true,payload.translationLyric,payload.lineMetadata).model
  val line=model.lines.single();val vocal=line.secondaryLine!!
  assertEquals("真正翻译",line.translation);assertEquals("echo voice",vocal.text)
  assertEquals(3500L,vocal.firstProgressStartMillis());assertEquals(3700L,vocal.wordEndMillis(0));assertEquals(4700L,vocal.wordEndMillis(1))
  assertEquals(0f,vocal.wordProgress(0,2500),0f);assertEquals(1f,vocal.wordProgress(0,4000),0f);assertEquals(0f,vocal.wordProgress(1,4000),0f)
  assertEquals(0,line.sourceAlignment);assertTrue(line.sourceAlignmentProvided)
 }
 @Test fun appleDirectionsRemainPerLineAndOrdinarySourceDefaultsToCenter(){
  val lines=listOf(RichLyricLine(begin=1000,end=2000,text="left"),RichLyricLine(begin=3000,end=4000,text="right",isAlignedRight=true))
  val apple=JSONObject(LyriconPayloadEncoder.encode(Song(name="Song",lyrics=lines),"com.apple.android.music")!!).getJSONArray("lineMetadata")
  assertEquals(0,apple.getJSONObject(0).getInt("alignment"));assertEquals(2,apple.getJSONObject(1).getInt("alignment"))
  val ordinary=JSONObject(LyriconPayloadEncoder.encode(Song(name="Song",lyrics=listOf(lines[0])),"com.example.music")!!).getJSONArray("lineMetadata").getJSONObject(0)
  assertEquals(1,ordinary.getInt("alignment"));assertFalse(ordinary.getBoolean("alignmentProvided"))
 }
 @Test fun lyriconWordsReachNativeRendererWithoutStretchingGaps(){
  val song=Song(name="Song",artist="Artist",lyrics=listOf(RichLyricLine(begin=1000,end=2200,text="甲乙",words=listOf(LyricWord(begin=1000,end=1400,text="甲"),LyricWord(begin=1700,end=1900,text="乙")))))
  val json=JSONObject(LyriconPayloadEncoder.encode(song,"com.example.music")!!)
  val payload=LyricInfoContract.parse(json.toString())!!
  val parsed=LyricsCoreAdapter.parse(payload.rawLyric)
  assertFalse(parsed.lines.isEmpty())
  val words=parsed.lines.first().syllables
  assertEquals(2,words.size)
  assertEquals(1000L,words[0].startMillis);assertEquals(1400L,words[0].endMillis)
  assertEquals(1700L,words[1].startMillis);assertEquals(1900L,words[1].endMillis)
 }
}
