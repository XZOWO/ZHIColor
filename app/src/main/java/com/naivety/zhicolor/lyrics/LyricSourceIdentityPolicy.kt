package com.naivety.zhicolor.lyrics

import io.github.proify.lyricon.lyric.model.Song

/** A provider snapshot is available only for the session's current track. */
internal object LyricSourceIdentityPolicy {
    fun timedPayloadAvailable(song: Song?, encoded: String?, title: String?, artist: String?): Boolean =
        song != null && !song.lyrics.isNullOrEmpty() && !encoded.isNullOrBlank() &&
            LyriconPayloadEncoder.matches(song, title, artist)

    fun trackKey(packageName: String, mediaId: String?, title: String?, artist: String?, duration: Long): String =
        listOf(packageName, mediaId.orEmpty(), title.orEmpty(), artist.orEmpty(), duration.toString())
            .joinToString("|") { "${it.length}:$it" }
}
