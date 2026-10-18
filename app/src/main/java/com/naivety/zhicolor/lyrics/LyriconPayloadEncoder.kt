package com.naivety.zhicolor.lyrics

import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.LyricWord
import com.naivety.zhicolor.bridge.render.TimedLyricTextMapper
import com.naivety.zhicolor.bridge.render.WordRange
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Only translates Lyricon model objects; it never reads another Provider family's payload. */
object LyriconPayloadEncoder {
    fun encode(song: Song, player: String): String? {
        val lines=song.lyrics.orEmpty().filter { it.begin>=0 && (!it.text.isNullOrBlank() || !it.words.isNullOrEmpty()) }.sortedBy { it.begin }
        if(lines.isEmpty()) return null
        val display=StringBuilder(); val raw=StringBuilder(); val translation=StringBuilder()
        val metadata=JSONArray()
        // Match FullLyricView: Apple/duet direction is per line; ordinary sources are centered.
        val honorDirection=player=="com.apple.android.music" || lines.any { it.isAlignedRight }
        for(line in lines) {
            val main=line.text?.takeIf { it.isNotBlank() } ?: line.words.orEmpty().joinToString("") { it.text.orEmpty() }
            display.append('[').append(time(line.begin)).append(']').append(clean(main)).append('\n')
            raw.append('[').append(time(line.begin)).append(']').append(words(line.words,main,line.end)).append('\n')
            val row=JSONObject().put("begin",line.begin).put("text",clean(main))
                .put("end",line.end).put("words",wordMetadata(line.words))
                .put("alignment",if(honorDirection) { if(line.isAlignedRight) 2 else 0 } else 1)
                .put("alignmentProvided",honorDirection)
            val secondary=line.secondary?.takeIf(String::isNotBlank)
                ?: line.secondaryWords.orEmpty().joinToString("") { it.text.orEmpty() }
            if(secondary.isNotBlank()) {
                val secondaryWords=wordMetadata(line.secondaryWords)
                val secondaryRow=JSONObject().put("text",clean(secondary))
                    .put("begin",if(secondaryWords.length()>0) secondaryWords.getJSONObject(0).getLong("begin") else line.begin)
                    .put("end",if(secondaryWords.length()>0) (0 until secondaryWords.length()).maxOf { secondaryWords.getJSONObject(it).getLong("end") } else line.end)
                    .put("words",secondaryWords)
                // AppleSongMapper publishes this only from the same native row's real
                // htmlTranslatedBackgroundVocalsLineText. It carries no word clock.
                line.metadata?.get("secondaryTranslation")?.takeIf(String::isNotBlank)?.let {
                    secondaryRow.put("translation",clean(it))
                }
                row.put("secondary",secondaryRow)
            }
            metadata.put(row)
            val translated=line.translation?.takeIf(String::isNotBlank) ?: line.roma?.takeIf(String::isNotBlank)
            row.put("translation",translated?.let(::clean).orEmpty())
            if(!line.translation.isNullOrBlank()) row.put("translationWords",wordMetadata(line.translationWords))
            if(translated!=null) translation.append('[').append(time(line.begin)).append(']').append(words(if(!line.translation.isNullOrBlank()) line.translationWords else null,translated,line.end)).append('\n')
        }
        return JSONObject().put("songName",song.name.orEmpty()).put("artist",song.artist.orEmpty()).put("songId",song.id.orEmpty())
            .put("lyric",display.toString()).put("rawLyric",raw.toString()).put("translationLyric",translation.toString())
            .put("lineMetadata",metadata)
            .put("provider","lyricon").put("source","lyricon").put("trackKey",player+"|"+song.id.orEmpty()+"|"+song.name.orEmpty()+"|"+song.artist.orEmpty()).toString()
    }
    private fun wordMetadata(items: List<LyricWord>?): JSONArray = JSONArray().also { result ->
        items.orEmpty().filter { it.begin>=0 && (it.end>it.begin || it.duration>0) && !it.text.isNullOrEmpty() }
            .sortedBy { it.begin }.forEach { word ->
                val finish=if(word.end>word.begin)word.end else word.begin+word.duration
                result.put(JSONObject().put("begin",word.begin).put("end",finish).put("text",clean(word.text.orEmpty())))
            }
    }
    fun matches(song: Song,title: String?,artist: String?): Boolean {
        val wanted=normalize(song.name);if(wanted.isEmpty()) return false
        val observed=normalize(title); val observedArtist=normalize(artist)
        val wantedArtist=normalize(song.artist)
        val artistMatches=wantedArtist.isEmpty() || observedArtist.isEmpty() || wantedArtist==observedArtist
        return (wanted==observed && artistMatches) || (observedArtist.contains(wanted) && normalize(song.artist).let { it.isNotEmpty()&&observedArtist.contains(it) })
    }
    private fun words(items: List<LyricWord>?,fallback: String,end: Long): String {
        val valid=items.orEmpty().filter { it.begin>=0 && (it.end>it.begin || it.duration>0) && !it.text.isNullOrEmpty() }.sortedBy { it.begin }
        if(valid.isEmpty()) return clean(fallback)
        val tokens=StringBuilder();val ranges=ArrayList<WordRange>()
        valid.forEach {word ->
            val start=tokens.length;tokens.append(clean(word.text.orEmpty()))
            ranges.add(WordRange(word.begin,start,tokens.length,if(word.end>word.begin)word.end else word.begin+word.duration))
        }
        val mapped=TimedLyricTextMapper.map(clean(fallback),tokens.toString(),ranges)
        if(mapped.words.isEmpty()) return clean(fallback)
        return buildString {
            var cursor=0
            mapped.words.forEach { word ->
                append(mapped.text.substring(cursor,word.start))
                append('<').append(time(word.timeMillis)).append('>').append(mapped.text.substring(word.start,word.end))
                append('<').append(time(word.endTimeMillis)).append('>')
                cursor=word.end
            }
            append(mapped.text.substring(cursor))
            if(end>mapped.words.last().endTimeMillis)append('<').append(time(end)).append('>')
        }
    }
    private fun clean(text: String)=text.replace('\n',' ').replace('\r',' ')
    private fun normalize(text: String?)=Normalizer.normalize(text.orEmpty(),Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("[\\p{P}\\p{Z}\\s]"),"")
    private fun time(value: Long): String { val ms=value.coerceAtLeast(0);return String.format(Locale.ROOT,"%02d:%02d.%03d",ms/60000,(ms/1000)%60,ms%1000) }
}
