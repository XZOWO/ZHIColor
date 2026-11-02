package com.naivety.zhicolor.ui.components.background

/** Recovered ordinary blender: duration=.8, progress += 2*dt/duration, cubic(0,0,.3,1). */
internal object ArtworkBlendTiming {
    const val SECONDS = .4f
    fun amount(progress: Float): Float {
        val x = progress.coerceIn(0f, 1f)
        if (x == 0f || x == 1f) return x
        var low = 0f
        var high = 1f
        // Invert Bezier x(t)=.9*t*t*(1-t)+t*t*t; y(t)=3*t*t-2*t*t*t.
        repeat(16) {
            val t = (low + high) * .5f
            if (.9f * t * t * (1f - t) + t * t * t < x) low = t else high = t
        }
        val t = (low + high) * .5f
        return t * t * (3f - 2f * t)
    }
}
