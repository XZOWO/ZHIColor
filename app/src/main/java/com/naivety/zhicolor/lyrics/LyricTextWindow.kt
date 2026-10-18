package com.naivety.zhicolor.lyrics

import org.json.JSONObject
import java.util.Locale

/** Event-only Lyricon Providers have rows, not word timestamps. Tags below encode row order only. */
class LyricTextWindow {
    private val rows=ArrayDeque<Pair<String,String>>()
    private var trackKey=""
    @Synchronized fun clear(){rows.clear();trackKey=""}
    @Synchronized fun retainOnlyTrack(track:String){if(trackKey!=track)clear()}
    @Synchronized fun available(track:String)=track.isNotEmpty() && trackKey==track && rows.isNotEmpty()
    @Synchronized fun accept(text:String?,track:String) {
        if(track.isEmpty()){clear();return}
        if(trackKey!=track){clear();trackKey=track}
        if(text.isNullOrBlank()){clear();return}
        val lines=text.lines().map(String::trim).filter(String::isNotEmpty)
        if(lines.isEmpty())return
        val row=lines[0].take(2000) to lines.drop(1).joinToString(" ").take(2000)
        if(rows.lastOrNull()?.first==row.first)rows.removeLast()
        rows.addLast(row);while(rows.size>5)rows.removeFirst()
    }
    @Synchronized fun encode(title:String?,artist:String?,track:String):String? {
        if(!available(track))return null
        val main=StringBuilder();val translated=StringBuilder()
        rows.forEachIndexed {index,row -> val tag=String.format(Locale.ROOT,"[00:%02d.000]",index);main.append(tag).append(row.first).append('\n');if(row.second.isNotEmpty())translated.append(tag).append(row.second).append('\n')}
        return JSONObject().put("songName",title.orEmpty()).put("artist",artist.orEmpty()).put("trackKey",track)
            .put("lyric",main.toString().trimEnd()).put("rawLyric",main.toString().trimEnd()).put("translationLyric",translated.toString().trimEnd())
            .put("provider","lyricon").put("source","lyricon").put("displayMode","sentence-window-v1").put("currentLine",rows.size-1).toString()
    }
}
