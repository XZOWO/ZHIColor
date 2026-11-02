package com.naivety.zhicolor.bridge

import com.naivety.zhicolor.lyrics.LyriconPayloadEncoder
import com.naivety.zhicolor.bridge.LyricAuxiliaryTextPolicy
import io.github.proify.lyricon.lyric.model.*
import org.junit.Assert.*
import org.junit.Test

class AdvancedLyricMetadataPipelineTest {
    private fun assembled(vararg lines: RichLyricLine): com.naivety.zhicolor.bridge.render.WordLyricModel {
        val encoded = LyriconPayloadEncoder.encode(Song(name="Song", lyrics=lines.toList()), "com.apple.android.music")!!
        val payload = LyricInfoContract.parse(encoded)!!
        val sink = object: LyricParseTraceSink {
            override fun traceEnabled() = false
            override fun trace(message: String) {}
            override fun onCoreParseFailure(primarySource: Boolean, failure: Throwable) {}
        }
        return NativeLyricModelAssembler(sink).assemble(payload.rawLyric, true, payload.lyric, true,
            payload.translationLyric, payload.lineMetadata).model
    }

    @Test fun translationAndRomanizationSurviveTogetherWithoutChangingStandardTranslation() {
        val line = assembled(RichLyricLine(begin=1000, end=4000, text="原文", translation="译文", roma="yuan wen")).lines.single()
        assertEquals("译文", line.translation)
        assertEquals("译文", LyricAuxiliaryTextPolicy.translation(line))
        assertEquals("yuan wen", line.romanization)
        assertNull(line.romanizationLine)
    }
    @Test fun romanizationOnlyRetainsLegacyFallbackButNoFalseAdvancedTranslation() {
        val line = assembled(RichLyricLine(begin=1000, end=4000, text="原文", roma="yuan wen")).lines.single()
        assertEquals("yuan wen", line.translation)
        assertEquals("", LyricAuxiliaryTextPolicy.translation(line))
        assertEquals("yuan wen", line.romanization)
    }
    @Test fun sameTimeRowsKeepIndependentIdsAndExplicitBackgroundParent() {
        val model = assembled(
            RichLyricLine(begin=1000, end=4000, text="lead", secondary="echo",
                metadata=LyricMetadata(mapOf("backgroundPosition" to "top"))),
            RichLyricLine(begin=1000, end=5000, text="other"))
        assertEquals(2, model.lines.size)
        val first = model.lines[0]
        assertNotEquals(first.lineId, model.lines[1].lineId)
        assertEquals("unknown", first.sourceRole)
        assertEquals("background", first.secondaryLine!!.sourceRole)
        assertEquals(first.lineId, first.secondaryLine!!.parentLineId)
        assertEquals("top", first.secondaryLine!!.backgroundPosition)
    }
}
