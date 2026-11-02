package com.naivety.zhicolor.advanced

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.naivety.zhicolor.bridge.advanced.AdvancedBackdrop
import com.naivety.zhicolor.home.BackgroundAudioFrame
import com.naivety.zhicolor.ui.components.background.ArtworkGlRenderer
import java.util.concurrent.atomic.AtomicReference

/**
 * Native content surface for an existing SystemUI view. It can be attached normally or be
 * manually measured, laid out and drawn from the native view's draw(Canvas) transaction.
 * The caller owns visibility, native alpha/matrices/clips and invalidation of that host.
 */
class AdvancedMusicBackdrop(hostContext: Context, private val moduleContext: Context) :
    View(hostContext), AdvancedBackdrop {
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()
    private var sourceArtwork: Bitmap? = null
    private var sourceTrack = ""
    private var offeredArtwork: Bitmap? = null
    private var offeredGeneration = 0
    private var offeredTrack = ""
    private var offeredRevision = 0L
    private var preparedRevision = -1L
    private var queuedRevision = -1L
    private var artworkWorker: ArtworkPreparationWorker? = null
    @Volatile private var image: ImageBitmap? = null
    private var displayed: Bitmap? = null
    @Volatile private var ready = false
    private var playing = false
    private var playback = PlaybackCaptureRequest(0, "", "", "")
    private var dynamicBackground = true
    private var audioReactive = true
    private var reducedMotion = false
    @Volatile private var active = false
    @Volatile private var released = false
    @Volatile private var rendererGeneration = 0L
    private var renderer: ArtworkGlRenderer? = null
    private var rendererWidth = 0
    private var rendererHeight = 0
    private var failedArtwork: ImageBitmap? = null
    private var bands = List(AudioFramePolicy.BAND_COUNT) { 0f }
    private var backgroundAudio = BackgroundAudioFrame.SILENT
    private var invalidateCallback: Runnable? = null
    private data class Prepared(val bitmap: Bitmap, val artwork: ImageBitmap, val generation: Long)
    private val pending = AtomicReference<Prepared?>(null)
    private val audio = AudioDynamicsClient(hostContext, moduleContext.applicationInfo.uid) { values, _, background ->
        bands = values
        backgroundAudio = background
        updateRenderer()
    }

    private val deliver = Runnable {
        val prepared = pending.getAndSet(null)
        if (prepared != null) {
            if (!released && active && prepared.generation == rendererGeneration && prepared.artwork === image) {
                // Displayed bitmaps may still be referenced by a recorded hardware Canvas;
                // let its native reference and GC retire them instead of recycling in flight.
                displayed = prepared.bitmap
                ready = true
                invalidateContent()
            } else prepared.bitmap.recycle()
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun setPlayback(artwork: Bitmap?, playing: Boolean, audioSessionId: Int,
                             route: String?, packageName: String?, trackKey: String?) = onMain {
        if (released) return@onMain
        this.playing = playing
        reducedMotion = !ValueAnimator.areAnimatorsEnabled()
        playback = PlaybackCaptureRequest(audioSessionId, route.orEmpty(), trackKey.orEmpty(), packageName.orEmpty())
        val valid = artwork?.takeUnless { it.isRecycled }
        val identity = packageName.orEmpty() + '\u0000' + trackKey.orEmpty()
        val generation = valid?.generationId ?: 0
        if (offeredArtwork !== valid || offeredGeneration != generation || offeredTrack != identity) {
            offeredArtwork = valid
            offeredGeneration = generation
            offeredTrack = identity
            offeredRevision++
            if (valid == null) {
                stopArtworkPreparation()
                sourceArtwork = null
                image = null
                stopRenderer()
                displayed = null
                invalidateContent()
            }
        }
        synchronize()
    }

    override fun setActive(active: Boolean) = onMain {
        if (released || this.active == active) return@onMain
        this.active = active
        if (active) reducedMotion = !ValueAnimator.areAnimatorsEnabled()
        synchronize()
        renderer?.setActive(active)
        if (!active) {
            stopArtworkPreparation()
            failedArtwork = null
        }
    }

    override fun setOptions(dynamicBackground: Boolean, audioReactive: Boolean) = onMain {
        if (released) return@onMain
        this.dynamicBackground = dynamicBackground
        this.audioReactive = audioReactive
        reducedMotion = !ValueAnimator.areAnimatorsEnabled()
        synchronize()
    }

    override fun setInvalidateCallback(callback: Runnable?) = onMain { invalidateCallback = callback }

    override fun isReady(): Boolean = !released && displayed?.isRecycled == false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        destination.set(0, 0, w, h)
        if (active && renderer != null) {
            renderer?.resize(w, h)
            rendererWidth = w; rendererHeight = h
        }
        synchronize()
    }

    private fun synchronize() {
        if (renderer?.isReleased() == true) {
            failedArtwork = image
            stopRenderer()
            invalidateContent()
        }
        audio.update(if (active && playing && dynamicBackground && audioReactive && !reducedMotion &&
                image != null && image !== failedArtwork && playback.sessionId > 0) playback else null)
        prepareArtwork()
        if (active && renderer == null && image != null && image !== failedArtwork &&
            preparedRevision == offeredRevision && width > 0 && height > 0) {
            val generation = ++rendererGeneration
            rendererWidth = width; rendererHeight = height
            // Keep module assets, not SystemUI assets. createPackageContext may have no
            // applicationContext; this renderer intentionally uses the supplied context.
            // Use the same rendered-frame transition as ArtworkMusicBackground, not the
            // former advanced-mode pre-blur artwork blend.
            renderer = ArtworkGlRenderer(moduleContext, width, height, resources.displayMetrics.density) { bitmap, artwork, _ ->
                if (released || !active || generation != rendererGeneration || artwork !== image) bitmap.recycle()
                else {
                    pending.getAndSet(Prepared(bitmap, artwork, generation))?.bitmap?.recycle()
                    main.removeCallbacks(deliver)
                    main.post(deliver)
                }
            }
        }
        updateRenderer()
    }

    private fun prepareArtwork() {
        val offered = offeredArtwork ?: return
        if (!active || released || preparedRevision == offeredRevision || queuedRevision == offeredRevision) return
        val worker = artworkWorker ?: ArtworkPreparationWorker(sourceArtwork, sourceTrack) { revision, bitmap, track ->
            if (!released && active && revision == offeredRevision) {
                preparedRevision = revision
                queuedRevision = -1L
                if (sourceArtwork !== bitmap) {
                    sourceArtwork = bitmap
                    image = bitmap.asImageBitmap()
                    pending.getAndSet(null)?.bitmap?.recycle()
                }
                sourceTrack = track
                synchronize()
            }
        }.also { artworkWorker = it }
        queuedRevision = offeredRevision
        worker.prepare(offeredRevision, offered, offeredGeneration, offeredTrack)
    }

    private fun stopArtworkPreparation() {
        artworkWorker?.close()
        artworkWorker = null
        queuedRevision = -1L
    }

    private fun updateRenderer() {
        if (!active) return
        val artwork = image ?: return
        if (renderer != null && (rendererWidth != width || rendererHeight != height)) {
            renderer?.resize(width, height)
            rendererWidth = width; rendererHeight = height
        }
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        renderer?.update(artwork, if (audioReactive) bands else emptyList(),
            playing && dynamicBackground, dark, reducedMotion || !dynamicBackground, sourceTrack,
            if (audioReactive) backgroundAudio else BackgroundAudioFrame.SILENT)
    }

    private fun stopRenderer() {
        ready = false
        rendererGeneration++
        renderer?.release()
        renderer = null
        main.removeCallbacks(deliver)
        pending.getAndSet(null)?.bitmap?.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = displayed
        if (frame != null && !frame.isRecycled) canvas.drawBitmap(frame, null, destination, paint)
    }

    private fun invalidateContent() {
        invalidate()
        invalidateCallback?.run()
    }

    override fun release() = onMain {
        if (released) return@onMain
        released = true
        active = false
        audio.release()
        stopRenderer()
        stopArtworkPreparation()
        displayed = null
        image = null
        sourceArtwork = null
        offeredArtwork = null
        invalidateCallback = null
    }

    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
}
