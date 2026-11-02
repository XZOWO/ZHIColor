package com.naivety.zhicolor.ui.components.background

/** A late cover completes its song's in-flight wave; a genuine next song can interrupt it. */
internal object ArtworkTransitionPolicy {
    fun restart(hasPresentedArtwork: Boolean, artworkChanged: Boolean, trackChanged: Boolean,
                identifiedTrack: Boolean, complete: Boolean): Boolean {
        if (!hasPresentedArtwork) return false
        if (trackChanged) return true
        return artworkChanged && (!identifiedTrack || complete)
    }
}
