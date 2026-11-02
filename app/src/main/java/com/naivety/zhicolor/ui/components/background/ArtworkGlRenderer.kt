package com.naivety.zhicolor.ui.components.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.naivety.zhicolor.home.BackgroundMotionPolicy
import com.naivety.zhicolor.home.BackgroundAudioFrame
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * GLES equivalent of the artwork blender, rotating artwork, Gaussian blur, and pinch pipelines.
 * Publishes the final 1/8-resolution Gaussian frame to the Compose backdrop source.
 * All GL objects and the frame clock belong to this worker and die with the visible composable.
 */
internal class ArtworkGlRenderer(
    private val context: Context,
    initialWidth: Int,
    initialHeight: Int,
    private val density: Float,
    private val artworkBlend: Boolean = false,
    private val postBlurDp: Float = POST_SIGMA_DP,
    private val onFrame: (Bitmap, ImageBitmap, Boolean) -> Unit,
) {
    private data class Input(
        val artwork: ImageBitmap,
        val bands: List<Float>,
        val playing: Boolean,
        val dark: Boolean,
        val reducedMotion: Boolean,
        val trackKey: String?,
        val backgroundAudio: BackgroundAudioFrame,
    )

    private val thread = HandlerThread("ArtworkBackground").apply { start() }
    private val handler = Handler(thread.looper)
    private val releaseLock = Any()
    @Volatile private var input: Input? = null
    @Volatile private var released = false
    @Volatile private var running = true
    private var ready = false
    private var width = initialWidth.coerceAtLeast(1)
    private var height = initialHeight.coerceAtLeast(1)
    private var targetWidth = 0
    private var targetHeight = 0
    private var postWidth = 0
    private var postHeight = 0
    private var postHorizontalKernel = GaussianKernel(1f, floatArrayOf())
    private var postVerticalKernel = GaussianKernel(1f, floatArrayOf())
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val programs = IntArray(6)
    private val textures = IntArray(13)
    private val framebuffers = IntArray(9)
    private var vertexArray = 0
    private var quadBuffer = 0
    private var meshBuffer = 0
    private var meshVertexCount = 0
    private var meshWide = false
    private val meshIndex = Random.nextInt(5)
    private var meshShapes: JSONObject? = null
    private var uploadedArtwork: ImageBitmap? = null
    private var uploadedTrack: String? = null
    private var transition = 1f
    private var time = 0f
    private var low = 0f
    private var mid = 0f
    private var high = 0f
    private val audioMotion = BackgroundMotionPolicy()
    private val frameClock = ArtworkFrameClock()
    private var nextFrameTime = 0L
    private var frameIndex = 0L
    private var hasLut = false
    private var directionIndex = 0
    private var hasPresented = false
    private var framePixels: ByteBuffer? = null
    private val packBuffers = IntArray(3)
    private val packFences = LongArray(3)
    private val packArtwork = arrayOfNulls<ImageBitmap>(3)
    private val packChanged = BooleanArray(3)
    private val pendingReadbacks = ArrayDeque<Int>()
    private var nextPack = 0
    private var renderCpuNanos = 0L
    private var delivered = false
    private val uniformLocations = HashMap<Int, MutableMap<String, Int>>()
    private var preBlurKernel = GaussianKernel(1f, floatArrayOf())

    private val frame = object : Runnable {
        override fun run() {
            if (released || !running) return
            try {
                val cpuStart = android.os.Debug.threadCpuTimeNanos()
                input?.let { draw(it) }
                renderCpuNanos += android.os.Debug.threadCpuTimeNanos() - cpuStart
                if (frameIndex % 120L == 0L && input != null) {
                    Log.d(TAG, "frame=$frameIndex time=$time low=$low mid=$mid high=$high size=${width}x$height cpuMs=${renderCpuNanos / (frameIndex + 1) / 1_000_000.0} readback=pbo")
                }
                // A monotonic target avoids adding draw duration to every frame interval.
                frameIndex++
                nextFrameTime = max(nextFrameTime + if (frameIndex % 3L == 0L) 16L else 17L, SystemClock.uptimeMillis() + 1L)
                if (!released && running && (input?.let { it.playing && !it.reducedMotion } == true || transition < 1f)) {
                    handler.postAtTime(this, max(SystemClock.uptimeMillis() + 1L, nextFrameTime))
                }
            } catch (failure: Exception) {
                Log.e(TAG, "Artwork renderer stopped", failure)
                release()
            }
        }
    }

    init {
        handler.post {
            if (!released) {
                try {
                    initialize()
                    ready = true
                    nextFrameTime = SystemClock.uptimeMillis()
                    if (running) handler.post(frame) else input?.let { draw(it) }
                } catch (failure: Exception) {
                    Log.e(TAG, "Artwork renderer initialization failed", failure)
                    release()
                }
            }
        }
    }

    fun update(artwork: ImageBitmap, bands: List<Float>, playing: Boolean, dark: Boolean, reducedMotion: Boolean, trackKey: String? = null, backgroundAudio: BackgroundAudioFrame = BackgroundAudioFrame.SILENT) {
        if (released) return
        val previous = input
        if (previous != null && previous.artwork === artwork && previous.bands === bands &&
            previous.playing == playing && previous.dark == dark && previous.reducedMotion == reducedMotion && previous.trackKey == trackKey && previous.backgroundAudio == backgroundAudio) return
        input = Input(artwork, bands, playing, dark, reducedMotion, trackKey, backgroundAudio)
        val staticFrameChanged = previous == null || previous.artwork !== artwork || previous.dark != dark ||
            previous.reducedMotion != reducedMotion || previous.playing != playing || previous.trackKey != trackKey
        if (staticFrameChanged) handler.post {
            if (ready && !released && running) {
                handler.removeCallbacks(frame)
                frameClock.reset()
                nextFrameTime = SystemClock.uptimeMillis()
                handler.post(frame)
            }
        }
    }

    fun setActive(active: Boolean) {
        if (released || running == active) return
        running = active
        handler.removeCallbacks(frame)
        handler.post {
            if (!released && ready && active && running) {
                handler.removeCallbacks(frame)
                frameClock.reset()
                nextFrameTime = SystemClock.uptimeMillis()
                handler.post(frame)
            }
            Log.d(TAG, "active=$running frames=$frameIndex")
        }
    }

    fun resize(width: Int, height: Int) {
        if (!released) handler.post {
            this.width = width.coerceAtLeast(1)
            this.height = height.coerceAtLeast(1)
            if (ready && !released) {
                if (!running) input?.let { drawSafely(it) }
                else {
                    handler.removeCallbacks(frame)
                    frameClock.reset()
                    nextFrameTime = SystemClock.uptimeMillis()
                    handler.post(frame)
                }
            }
        }
    }

    fun isReleased(): Boolean = released

    fun release() {
        synchronized(releaseLock) {
            if (released) return
            released = true
            handler.removeCallbacks(frame)
            handler.post {
                try { cleanup() } finally { thread.quitSafely() }
            }
        }
    }

    private fun drawSafely(state: Input) {
        try {
            draw(state)
        } catch (failure: Exception) {
            Log.e(TAG, "Artwork renderer stopped", failure)
            release()
        }
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "EGL initialization failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
        val config = checkNotNull(configs[0])
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "No GLES 3 context" }
        eglSurface = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext))
        EGL14.eglSwapInterval(display, 1)
        programs[BLEND] = program(QUAD_VERTEX, BLEND_FRAGMENT)
        programs[ROTATE] = program(ROTATION_VERTEX, ROTATION_FRAGMENT)
        programs[BLUR] = program(QUAD_VERTEX, BLUR_FRAGMENT)
        programs[PINCH] = program(PINCH_VERTEX, PINCH_FRAGMENT)
        programs[POST_BLUR] = program(QUAD_VERTEX, POST_BLUR_FRAGMENT)
        programs[PRESENT] = program(QUAD_VERTEX, PRESENT_FRAGMENT)
        GLES30.glGenTextures(textures.size, textures, 0)
        GLES30.glGenFramebuffers(framebuffers.size, framebuffers, 0)
        GLES30.glGenBuffers(packBuffers.size, packBuffers, 0)
        val array = IntArray(1)
        GLES30.glGenVertexArrays(1, array, 0)
        vertexArray = array[0]
        GLES30.glBindVertexArray(vertexArray)
        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        quadBuffer = buffers[0]
        meshBuffer = buffers[1]
        val quad = floats(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.capacity() * 4, quad, GLES30.GL_STATIC_DRAW)
        createPinchMesh()
        allocateTexture(textures[BLENDED_TEXTURE], ARTWORK_SIZE, ARTWORK_SIZE)
        attach(framebuffers[BLEND], textures[BLENDED_TEXTURE])
        hasLut = loadLut()
        check(hasLut) { "Artwork color LUT is missing or malformed" }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GLES setup failed" }
        Log.d(TAG, "created size=${width}x$height lut=$hasLut")
    }

    private fun draw(state: Input) {
        resizeTargets()
        val artworkChanged = uploadedArtwork !== state.artwork
        val trackChanged = uploadedTrack != state.trackKey
        // Texture upload does not consume the beginning of the visible transition.
        val dt = frameClock.step(SystemClock.elapsedRealtimeNanos())
        if (artworkChanged || trackChanged) {
            val restart = ArtworkTransitionPolicy.restart(uploadedArtwork != null && hasPresented,
                artworkChanged, trackChanged, !state.trackKey.isNullOrEmpty(), transition >= 1f)
            uploadArtwork(state.artwork, restart)
            uploadedTrack = state.trackKey
        }
        // Pause freezes artwork motion; accessibility disables it instead of merely slowing it.
        if (state.playing && !state.reducedMotion) time += dt
        transition = if (state.reducedMotion) 1f else if (artworkChanged) transition else
            (transition + dt / if (artworkBlend) ArtworkBlendTiming.SECONDS else 1.45f).coerceAtMost(1f)
        if (artworkBlend) blendArtwork(ArtworkBlendTiming.amount(transition))
        if (!state.playing || state.reducedMotion || trackChanged) audioMotion.reset()
        // Independent analyzer report: the album spectrum's adaptive gain never drives this.
        val powers = audioMotion.update(
            if (state.playing && !state.reducedMotion) state.backgroundAudio else BackgroundAudioFrame.SILENT,
            dt, SystemClock.elapsedRealtimeNanos())
        low = powers.low
        mid = powers.mid
        high = powers.high
        rotateArtwork(state)
        blur(framebuffers[BLUR], textures[ROTATED_TEXTURE], 1f / targetWidth, 0f)
        blur(framebuffers[PINCH], textures[HORIZONTAL_TEXTURE], 0f, 1f / targetHeight)
        pinch(state)
        postBlur(framebuffers[POST_HORIZONTAL], textures[PINCHED_TEXTURE],
            1f / postWidth, 0f, postHorizontalKernel)
        postBlur(framebuffers[POST_VERTICAL], textures[POST_HORIZONTAL_TEXTURE],
            0f, 1f / postHeight, postVerticalKernel)
        present(state, artworkChanged)
        if (frameIndex == 0L) check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GLES first draw failed" }
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "EGL swap failed: ${EGL14.eglGetError()}" }
    }

    private fun resizeTargets() {
        val newWidth = (width / 4).coerceAtLeast(1)
        val newHeight = (height / 4).coerceAtLeast(1)
        val newPostWidth = (width / POST_DOWNSAMPLE).coerceAtLeast(1)
        val newPostHeight = (height / POST_DOWNSAMPLE).coerceAtLeast(1)
        if (targetWidth == newWidth && targetHeight == newHeight &&
            postWidth == newPostWidth && postHeight == newPostHeight) return
        targetWidth = newWidth
        targetHeight = newHeight
        postWidth = newPostWidth
        postHeight = newPostHeight
        if (meshWide != (width > height)) createPinchMesh()
        allocateTexture(textures[ROTATED_TEXTURE], targetWidth, targetHeight)
        allocateTexture(textures[HORIZONTAL_TEXTURE], targetWidth, targetHeight)
        allocateTexture(textures[BLURRED_TEXTURE], targetWidth, targetHeight)
        attach(framebuffers[ROTATE], textures[ROTATED_TEXTURE])
        attach(framebuffers[BLUR], textures[HORIZONTAL_TEXTURE])
        attach(framebuffers[PINCH], textures[BLURRED_TEXTURE])
        // Post-deformation Gaussian has independent targets: no pass samples its own attachment.
        allocateTexture(textures[PINCHED_TEXTURE], postWidth, postHeight)
        allocateTexture(textures[POST_HORIZONTAL_TEXTURE], postWidth, postHeight)
        allocateTexture(textures[POST_BLURRED_TEXTURE], postWidth, postHeight)
        attach(framebuffers[POST_PINCH], textures[PINCHED_TEXTURE])
        attach(framebuffers[POST_HORIZONTAL], textures[POST_HORIZONTAL_TEXTURE])
        attach(framebuffers[POST_VERTICAL], textures[POST_BLURRED_TEXTURE])
        allocateTexture(textures[PRESENTED_TEXTURE], postWidth, postHeight)
        allocateTexture(textures[SNAPSHOT_TEXTURE], postWidth, postHeight)
        attach(framebuffers[PRESENTED], textures[PRESENTED_TEXTURE])
        framePixels = ByteBuffer.allocateDirect(postWidth * postHeight * 4).order(ByteOrder.nativeOrder())
        clearReadbacks()
        allocateTexture(textures[READBACK_TEXTURE], postWidth, postHeight)
        attach(framebuffers[READBACK], textures[READBACK_TEXTURE])
        for (buffer in packBuffers) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, buffer)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, postWidth * postHeight * 4, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        hasPresented = false
        if (!artworkBlend) transition = 1f
        val sigma = (if (width > height) 120f else 85f) * density / 4f
        val stride = max(1f, 3f * sigma / 32f)
        val raw = FloatArray(32) { i -> exp(-.5f * ((i + 1) * stride).pow(2) / (sigma * sigma)) }
        val total = 1f + 2f * raw.sum()
        preBlurKernel = GaussianKernel(1f / total, FloatArray(64) { i ->
            if (i % 2 == 0) (i / 2 + 1) * stride else raw[i / 2] / total
        })
        GLES30.glUseProgram(programs[BLUR])
        uniform1f(programs[BLUR], "centerWeight", preBlurKernel.centerWeight)
        GLES30.glUniform2fv(uniformLocation(programs[BLUR], "sampleTaps[0]"), 32, preBlurKernel.pairedTaps, 0)
        // Convert dp -> display pixels -> actual target pixels independently for each axis.
        val sigmaPixels = postBlurDp * density
        postHorizontalKernel = gaussianKernel(sigmaPixels * postWidth / width)
        postVerticalKernel = gaussianKernel(sigmaPixels * postHeight / height)
        Log.d(TAG, "postGaussian sigmaDp=$postBlurDp size=${postWidth}x$postHeight taps=${postHorizontalKernel.tapCount}/${postVerticalKernel.tapCount}")
    }

    private fun uploadArtwork(artwork: ImageBitmap, restartTransition: Boolean) {
        val source = artwork.asAndroidBitmap()
        val copy = checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
        val size = max(copy.width, copy.height)
        val bitmap = if (size > ARTWORK_SIZE) {
            Bitmap.createScaledBitmap(copy, (copy.width * ARTWORK_SIZE / size).coerceAtLeast(1),
                (copy.height * ARTWORK_SIZE / size).coerceAtLeast(1), true)
        } else copy
        if (artworkBlend) {
            // Blend sources before deformation. Copying the current blend also preserves
            // continuity when another artwork arrives before this transition finishes.
            if (uploadedArtwork != null && hasPresented) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[BLEND])
                bindTexture(0, textures[PREVIOUS_TEXTURE])
                GLES30.glCopyTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                    0, 0, ARTWORK_SIZE, ARTWORK_SIZE, 0)
                transition = 0f
            } else {
                upload(textures[PREVIOUS_TEXTURE], bitmap)
                transition = 1f
            }
        } else if (uploadedArtwork == null || !hasPresented) {
            transition = 1f
        } else if (restartTransition) {
            // Save the visible composite, including an interrupted wave, before replacing the source.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[PRESENTED])
            bindTexture(0, textures[SNAPSHOT_TEXTURE])
            GLES30.glCopyTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 0, 0,
                postWidth, postHeight, 0)
            directionIndex = (directionIndex + 1) % 4
            transition = 0f
            Log.d(TAG, "trackWave direction=$directionIndex duration=1450ms")
        }
        if (!artworkBlend) upload(textures[PREVIOUS_TEXTURE], bitmap)
        upload(textures[CURRENT_TEXTURE], bitmap)
        if (bitmap !== copy) bitmap.recycle()
        copy.recycle()
        uploadedArtwork = artwork
        blendArtwork(if (artworkBlend) transition else 1f)
    }

    private fun blendArtwork(amount: Float = 1f) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[BLEND])
        GLES30.glViewport(0, 0, ARTWORK_SIZE, ARTWORK_SIZE)
        val program = programs[BLEND]
        GLES30.glUseProgram(program)
        bindTexture(0, textures[PREVIOUS_TEXTURE])
        bindTexture(1, textures[CURRENT_TEXTURE])
        uniform1i(program, "previousArtwork", 0)
        uniform1i(program, "currentArtwork", 1)
        uniform1f(program, "transitionMix", amount)
        drawQuad()
    }

    private fun rotateArtwork(state: Input) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[ROTATE])
        GLES30.glViewport(0, 0, targetWidth, targetHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val program = programs[ROTATE]
        GLES30.glUseProgram(program)
        bindTexture(0, textures[BLENDED_TEXTURE])
        uniform1i(program, "artwork", 0)
        uniform1f(program, "time", time)
        uniform1f(program, "aspect", width.toFloat() / height)
        uniform1f(program, "audioScale", 1f + .33f * (low * .9f + mid * .1f).pow(2))
        uniform1f(program, "contrast", 1f + .076f * low)
        uniform1f(program, "saturation", (if (width > height) 2.4f else 2f) + .166f * high)
        uniform1f(program, "blackScrim", if (state.dark) .5f else .12f)
        quadAttributes()
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, 3)
    }

    private fun blur(framebuffer: Int, texture: Int, dx: Float, dy: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, targetWidth, targetHeight)
        val program = programs[BLUR]
        GLES30.glUseProgram(program)
        bindTexture(0, texture)
        uniform1i(program, "image", 0)
        GLES30.glUniform2f(uniformLocation(program, "direction"), dx, dy)
        drawQuad()
    }

    private fun pinch(state: Input) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[POST_PINCH])
        GLES30.glViewport(0, 0, postWidth, postHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val program = programs[PINCH]
        GLES30.glUseProgram(program)
        bindTexture(0, textures[BLURRED_TEXTURE])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textures[LUT_TEXTURE])
        uniform1i(program, "image", 0)
        uniform1i(program, "colorLut", 1)
        uniform1i(program, "hasLut", if (hasLut) 1 else 0)
        uniform1f(program, "warpMix", (sin(time / 3.5f) + 1f) * .5f)
        uniform1f(program, "outputHeight", postHeight.toFloat())
        uniform1f(program, "whiteLift", if (state.dark) .1f else 0f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 24, 8)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 24, 16)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, meshVertexCount)
        GLES30.glDisableVertexAttribArray(2)
    }

    private data class GaussianKernel(val centerWeight: Float, val pairedTaps: FloatArray) {
        val tapCount: Int get() = pairedTaps.size / 2
    }

    private fun gaussianKernel(sigma: Float): GaussianKernel {
        if (sigma <= 0f) return GaussianKernel(1f, floatArrayOf())
        val safeSigma = sigma.coerceAtLeast(0.01f).toDouble()
        val radius = ceil(3.0 * safeSigma).toInt().coerceIn(1, MAX_POST_TAP_PAIRS * 2)
        val weights = DoubleArray(radius + 1) { distance ->
            exp(-0.5 * distance * distance / (safeSigma * safeSigma))
        }
        val total = weights[0] + 2.0 * weights.drop(1).sum()
        val paired = FloatArray(((radius + 1) / 2) * 2)
        var pair = 0
        var distance = 1
        while (distance <= radius) {
            val first = weights[distance]
            val second = if (distance < radius) weights[distance + 1] else 0.0
            val weight = first + second
            // Linear filtering reconstructs adjacent Gaussian taps with one texture lookup.
            paired[pair++] = if (weight > 0.0)
                ((distance * first + (distance + 1) * second) / weight).toFloat() else distance.toFloat()
            paired[pair++] = (weight / total).toFloat()
            distance += 2
        }
        return GaussianKernel((weights[0] / total).toFloat(), paired)
    }

    private fun postBlur(framebuffer: Int, texture: Int, dx: Float, dy: Float, kernel: GaussianKernel) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, postWidth, postHeight)
        val program = programs[POST_BLUR]
        GLES30.glUseProgram(program)
        bindTexture(0, texture)
        uniform1i(program, "image", 0)
        uniform1i(program, "tapCount", kernel.tapCount)
        uniform1f(program, "centerWeight", kernel.centerWeight)
        GLES30.glUniform2f(uniformLocation(program, "direction"), dx, dy)
        GLES30.glUniform2fv(uniformLocation(program, "pairedTaps[0]"),
            kernel.tapCount, kernel.pairedTaps, 0)
        drawQuad()
    }

    private fun present(state: Input, artworkChanged: Boolean) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[PRESENTED])
        GLES30.glViewport(0, 0, postWidth, postHeight)
        val program = programs[PRESENT]
        GLES30.glUseProgram(program)
        bindTexture(0, textures[POST_BLURRED_TEXTURE])
        bindTexture(1, textures[SNAPSHOT_TEXTURE])
        uniform1i(program, "image", 0)
        uniform1i(program, "previousFrame", 1)
        uniform1f(program, "progress", if (artworkBlend) 1f else transition)
        uniform1f(program, "dark", if (state.dark) 1f else 0f)
        val direction = when (directionIndex) {
            0 -> floatArrayOf(1f, -1f)
            1 -> floatArrayOf(1f, 0.65f)
            2 -> floatArrayOf(-1f, -0.8f)
            else -> floatArrayOf(-0.65f, 1f)
        }
        GLES30.glUniform2f(uniformLocation(program, "direction"), direction[0], direction[1])
        drawQuad()
        hasPresented = true
        // Flip on the GPU. Android ARGB_8888 stores RGBA bytes, so the native bulk copy
        // preserves the original pixels without a Kotlin row/channel conversion loop.
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, framebuffers[PRESENTED])
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, framebuffers[READBACK])
        GLES30.glBlitFramebuffer(0, 0, postWidth, postHeight, 0, postHeight, postWidth, 0,
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, framebuffers[READBACK])
        val animated = (state.playing && !state.reducedMotion) || transition < 1f
        if (!animated || !delivered) {
            clearReadbacks()
            val pixels = checkNotNull(framePixels).apply { position(0) }
            GLES30.glReadPixels(0, 0, postWidth, postHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            publishPixels(pixels, state.artwork, artworkChanged)
        } else {
            drainReadbacks()
            if (pendingReadbacks.size < packBuffers.size) {
                val slot = nextPack
                nextPack = (nextPack + 1) % packBuffers.size
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, packBuffers[slot])
                GLES30.glReadPixels(0, 0, postWidth, postHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
                packFences[slot] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                packArtwork[slot] = state.artwork
                packChanged[slot] = artworkChanged
                pendingReadbacks.addLast(slot)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                GLES30.glFlush()
            }
        }
        if (!delivered) {
            delivered = true
            Log.d(TAG, "composeFrame size=${postWidth}x$postHeight blurSource=actualArtwork")
        }
    }

    private fun publishPixels(pixels: java.nio.Buffer, artwork: ImageBitmap, changed: Boolean) {
        pixels.position(0)
        val bitmap = Bitmap.createBitmap(postWidth, postHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(pixels)
        onFrame(bitmap, artwork, changed)
    }

    private fun drainReadbacks() {
        while (pendingReadbacks.isNotEmpty()) {
            val slot = pendingReadbacks.first()
            val status = GLES30.glClientWaitSync(packFences[slot], 0, 0L)
            if (status == GLES30.GL_TIMEOUT_EXPIRED) break
            check(status == GLES30.GL_ALREADY_SIGNALED || status == GLES30.GL_CONDITION_SATISFIED) { "Readback fence failed" }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, packBuffers[slot])
            val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0,
                postWidth * postHeight * 4, GLES30.GL_MAP_READ_BIT)
            checkNotNull(mapped) { "Readback map failed" }
            try { publishPixels(mapped, checkNotNull(packArtwork[slot]), packChanged[slot]) }
            finally {
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                GLES30.glDeleteSync(packFences[slot])
                packFences[slot] = 0L
                packArtwork[slot] = null
                pendingReadbacks.removeFirst()
            }
        }
    }

    private fun clearReadbacks() {
        pendingReadbacks.clear()
        for (i in packFences.indices) {
            if (packFences[i] != 0L) GLES30.glDeleteSync(packFences[i])
            packFences[i] = 0L
            packArtwork[i] = null
        }
        nextPack = 0
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    private fun createPinchMesh() {
        val shapes = meshShapes ?: context.assets.open("background/PinchMeshes.json").bufferedReader()
            .use { JSONObject(it.readText()) }.also { meshShapes = it }
        meshWide = width > height
        val divisions = if (meshWide) 8 else 5
        val pair = shapes.getJSONArray(if (meshWide) "wide" else "compact").getJSONObject(meshIndex)
        val from = pair.getJSONArray("from")
        val to = pair.getJSONArray("to")
        check(from.length() == (divisions + 1) * (divisions + 1) && from.length() == to.length())
        // The extracted control points retain their original displacement. Two subdivisions
        // refine each cell before the two geometric states interpolate in the vertex shader.
        val subdivisions = 4
        val steps = divisions * subdivisions
        val values = ArrayList<Float>(steps * steps * 6 * 6)
        fun coordinate(x: Int, y: Int, destination: Boolean, axis: Int): Float {
            val points = if (destination) to else from
            val cellX = (x / subdivisions).coerceAtMost(divisions - 1)
            val cellY = (y / subdivisions).coerceAtMost(divisions - 1)
            val tx = (x - cellX * subdivisions).toFloat() / subdivisions
            val ty = (y - cellY * subdivisions).toFloat() / subdivisions
            val index = cellY * (divisions + 1) + cellX
            val p00 = points.getJSONArray(index).getDouble(axis).toFloat()
            val p10 = points.getJSONArray(index + 1).getDouble(axis).toFloat()
            val p01 = points.getJSONArray(index + divisions + 1).getDouble(axis).toFloat()
            val p11 = points.getJSONArray(index + divisions + 2).getDouble(axis).toFloat()
            val top = p00 + (p10 - p00) * tx
            val bottom = p01 + (p11 - p01) * tx
            return (top + (bottom - top) * ty) * 2f - 1f
        }
        fun vertex(x: Int, y: Int) {
            values.addAll(listOf(x.toFloat() / steps, y.toFloat() / steps,
                coordinate(x, y, false, 0), coordinate(x, y, false, 1),
                coordinate(x, y, true, 0), coordinate(x, y, true, 1)))
        }
        for (y in 0 until steps) for (x in 0 until steps) {
            vertex(x, y); vertex(x + 1, y); vertex(x, y + 1)
            vertex(x + 1, y); vertex(x + 1, y + 1); vertex(x, y + 1)
        }
        val vertices = floats(values.toFloatArray())
        meshVertexCount = values.size / 6
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshBuffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.capacity() * 4, vertices, GLES30.GL_STATIC_DRAW)
    }

    private fun loadLut(): Boolean {
        val bitmap = runCatching {
            context.assets.open("background/ArtworkLUT.png").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inScaled = false
                    inPremultiplied = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }
        }.getOrNull() ?: return false
        if (bitmap.width != 32 || bitmap.height != 1024) { bitmap.recycle(); return false }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textures[LUT_TEXTURE])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, 32, 32, 32, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        val pixels = IntArray(32 * 32)
        val slice = ByteBuffer.allocateDirect(32 * 32 * 4)
        repeat(32) { z ->
            bitmap.getPixels(pixels, 0, 32, 0, z * 32, 32, 32)
            slice.clear()
            pixels.forEach { pixel ->
                slice.put((pixel shr 16 and 255).toByte())
                slice.put((pixel shr 8 and 255).toByte())
                slice.put((pixel and 255).toByte())
                slice.put((pixel ushr 24).toByte())
            }
            slice.position(0)
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_3D, 0, 0, 0, z, 32, 32, 1,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, slice)
        }
        bitmap.recycle()
        return true
    }

    private fun upload(texture: Int, bitmap: Bitmap) {
        bindTexture(0, texture)
        textureParameters()
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun allocateTexture(texture: Int, width: Int, height: Int) {
        bindTexture(0, texture)
        textureParameters()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
    }

    private fun textureParameters() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun attach(framebuffer: Int, texture: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texture, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
    }

    private fun bindTexture(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    private fun quadAttributes() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
    }

    private fun drawQuad() {
        quadAttributes()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun uniformLocation(program: Int, name: String): Int =
        uniformLocations.getOrPut(program) { HashMap() }.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    private fun uniform1f(program: Int, name: String, value: Float) =
        GLES30.glUniform1f(uniformLocation(program, name), value)

    private fun uniform1i(program: Int, name: String, value: Int) =
        GLES30.glUniform1i(uniformLocation(program, name), value)

    private fun program(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES30.glCreateShader(type)
            GLES30.glShaderSource(id, source)
            GLES30.glCompileShader(id)
            val result = IntArray(1)
            GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, result, 0)
            check(result[0] != 0) { GLES30.glGetShaderInfoLog(id) }
            return id
        }
        val vertexId = shader(GLES30.GL_VERTEX_SHADER, vertex)
        val fragmentId = shader(GLES30.GL_FRAGMENT_SHADER, fragment)
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vertexId)
        GLES30.glAttachShader(id, fragmentId)
        GLES30.glLinkProgram(id)
        GLES30.glDeleteShader(vertexId)
        GLES30.glDeleteShader(fragmentId)
        val result = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, result, 0)
        check(result[0] != 0) { GLES30.glGetProgramInfoLog(id) }
        return id
    }

    private fun cleanup() {
        if (display != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
            if (eglSurface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) {
                clearReadbacks()
                GLES30.glDeleteBuffers(packBuffers.size, packBuffers, 0)
                programs.filter { it != 0 }.forEach(GLES30::glDeleteProgram)
                GLES30.glDeleteTextures(textures.size, textures, 0)
                GLES30.glDeleteFramebuffers(framebuffers.size, framebuffers, 0)
                GLES30.glDeleteBuffers(2, intArrayOf(quadBuffer, meshBuffer), 0)
                GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, eglContext)
        }
        if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
        EGL14.eglReleaseThread()
        input = null
        uploadedArtwork = null
        uploadedTrack = null
        ready = false
        Log.d(TAG, "released frames=$frameIndex")
    }

    companion object {
        private const val TAG = "ZHIColorBackdrop"
        private const val ARTWORK_SIZE = 512
        private const val POST_DOWNSAMPLE = 8
        // The original pre-warp blur already removes artwork detail. This final pass only
        // softens deformation edges; a second 32dp wash erased too much moving color contrast.
        private const val POST_SIGMA_DP = 18f
        private const val MAX_POST_TAP_PAIRS = 64
        private const val BLEND = 0
        private const val ROTATE = 1
        private const val BLUR = 2
        private const val PINCH = 3
        private const val POST_BLUR = 4
        private const val PRESENT = 5
        private const val POST_PINCH = 4
        private const val POST_HORIZONTAL = 5
        private const val POST_VERTICAL = 6
        private const val PREVIOUS_TEXTURE = 0
        private const val CURRENT_TEXTURE = 1
        private const val BLENDED_TEXTURE = 2
        private const val ROTATED_TEXTURE = 3
        private const val HORIZONTAL_TEXTURE = 4
        private const val BLURRED_TEXTURE = 5
        private const val LUT_TEXTURE = 6
        private const val PINCHED_TEXTURE = 7
        private const val POST_HORIZONTAL_TEXTURE = 8
        private const val POST_BLURRED_TEXTURE = 9
        private const val SNAPSHOT_TEXTURE = 10
        private const val PRESENTED_TEXTURE = 11
        private const val PRESENTED = 7
        private const val READBACK_TEXTURE = 12
        private const val READBACK = 8

        private fun floats(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(values).apply { position(0) }

        /** Solve x(t) for cubic-bezier(0, 0, .3, 1), then evaluate y(t). */
        private fun cubicTransition(x: Float): Float {
            if (x <= 0f || x >= 1f) return x.coerceIn(0f, 1f)
            var lo = 0f
            var hi = 1f
            repeat(14) {
                val t = (lo + hi) * .5f
                val px = .9f * (1f - t) * t * t + t * t * t
                if (px < x) lo = t else hi = t
            }
            val t = (lo + hi) * .5f
            return 3f * (1f - t) * t * t + t * t * t
        }

        private const val QUAD_VERTEX = """#version 300 es
            precision highp float;
            layout(location=0) in vec2 position;
            layout(location=1) in vec2 coordinates;
            out vec2 uv;
            void main() { uv=coordinates; gl_Position=vec4(position,0.0,1.0); }
        """

        private const val BLEND_FRAGMENT = """#version 300 es
            precision mediump float;
            uniform sampler2D previousArtwork;
            uniform sampler2D currentArtwork;
            uniform float transitionMix;
            in vec2 uv;
            out vec4 color;
            void main() {
                color=vec4(mix(texture(previousArtwork,uv).rgb,
                    texture(currentArtwork,uv).rgb,transitionMix),1.0);
            }
        """

        private const val ROTATION_VERTEX = """#version 300 es
            precision highp float;
            layout(location=0) in vec2 position;
            layout(location=1) in vec2 coordinates;
            uniform float time;
            uniform float aspect;
            uniform float audioScale;
            out vec2 uv;
            flat out int instanceId;
            mat2 rotation(float a) { return mat2(cos(a),-sin(a),sin(a),cos(a)); }
            void main() {
                int id=gl_InstanceID;
                float speed=id==0?120.0:(id==1?70.0:90.0);
                float scale=id==0?1.4:0.7;
                vec2 translation=id==0?vec2(0.0):(id==1?vec2(-.25,.15):vec2(.7,.7));
                vec2 p=rotation(6.283185482*time/speed)*position;
                p=(p*scale+translation)*audioScale;
                if(id==2) p=rotation(6.283185482*time/120.0)*p;
                p*=vec2(max(1.0,1.0/aspect),max(1.0,aspect));
                gl_Position=vec4(p,0.0,1.0);
                uv=vec2(coordinates.x,1.0-coordinates.y);
                instanceId=id;
            }
        """

        private const val ROTATION_FRAGMENT = """#version 300 es
            precision mediump float;
            uniform sampler2D artwork;
            uniform float contrast;
            uniform float saturation;
            uniform float blackScrim;
            in vec2 uv;
            flat in int instanceId;
            out vec4 color;
            void main() {
                vec3 rgb=texture(artwork,uv).rgb*(1.0-blackScrim-.0075*float(instanceId));
                rgb=(rgb-.5)*contrast+.5;
                vec3 weights=vec3(.21259999275,.71520000696,.072200000286);
                float luma=dot(rgb,weights);
                vec3 original=rgb;
                rgb=mix(vec3(luma),rgb,saturation);
                // AIR's red diagonal uses .2126 + .7873*s rather than .7874*s.
                rgb.r-=.0001*saturation*original.r;
                color=vec4(rgb,1.0);
            }
        """

        private const val BLUR_FRAGMENT = """#version 300 es
            precision highp float;
            uniform sampler2D image;
            uniform vec2 direction;
            uniform float centerWeight;
            uniform vec2 sampleTaps[32];
            in vec2 uv;
            out vec4 color;
            void main() {
                vec4 sum=texture(image,uv)*centerWeight;
                for(int i=0;i<32;i++) {
                    vec2 offset=direction*sampleTaps[i].x;
                    sum+=(texture(image,uv+offset)+texture(image,uv-offset))*sampleTaps[i].y;
                }
                color=sum;
            }
        """

        private const val PINCH_VERTEX = """#version 300 es
            precision highp float;
            layout(location=0) in vec2 coordinates;
            layout(location=1) in vec2 fromPosition;
            layout(location=2) in vec2 toPosition;
            uniform float warpMix;
            out vec2 uv;
            void main() {
                uv=coordinates;
                gl_Position=vec4(mix(fromPosition,toPosition,warpMix),0.0,1.0);
            }
        """

        private const val POST_BLUR_FRAGMENT = """#version 300 es
            precision highp float;
            uniform sampler2D image;
            uniform vec2 direction;
            uniform float centerWeight;
            uniform int tapCount;
            uniform vec2 pairedTaps[64];
            in vec2 uv;
            out vec4 color;
            void main() {
                vec4 sum=texture(image,uv)*centerWeight;
                for(int i=0;i<64;i++) {
                    if(i>=tapCount) break;
                    vec2 offset=direction*pairedTaps[i].x;
                    sum+=(texture(image,uv+offset)+texture(image,uv-offset))*pairedTaps[i].y;
                }
                color=sum;
            }
        """

        private const val PRESENT_FRAGMENT = """#version 300 es
            precision highp float;
            uniform sampler2D image;
            uniform sampler2D previousFrame;
            uniform vec2 direction;
            uniform float progress;
            uniform float dark;
            in vec2 uv;
            out vec4 color;
            void main() {
                vec2 d=normalize(direction);
                float span=abs(d.x)+abs(d.y);
                float position=dot(uv-.5,d)/span+.5;
                float tangent=dot(uv-.5,vec2(-d.y,d.x));
                float crest=progress*1.38-.19;
                float wave=.055*sin(tangent*8.0-progress*4.0);
                float reveal=1.0-smoothstep(crest-.12,crest+.12,position+wave);
                float ridge=exp(-pow((position+wave-crest)/.14,2.0));
                vec2 bend=d*ridge*.025*sin(progress*3.14159);
                vec3 incoming=texture(image,clamp(uv+bend,0.0,1.0)).rgb;
                // Separate theme veil from the source artwork color transform.
                float peak=max(max(incoming.r,incoming.g),incoming.b);
                float exposure=.24*(1.0-dark);
                incoming*=(1.0+exposure)/(1.0+exposure*peak);
                vec3 outgoing=texture(previousFrame,uv-bend).rgb;
                if(progress>=1.0) reveal=1.0;
                if(progress<=0.0) reveal=0.0;
                color=vec4(mix(outgoing,incoming,reveal),1.0);
            }
        """

        private const val PINCH_FRAGMENT = """#version 300 es
            precision highp float;
            uniform sampler2D image;
            uniform highp sampler3D colorLut;
            uniform bool hasLut;
            uniform float outputHeight;
            uniform float whiteLift;
            in vec2 uv;
            out vec4 color;
            void main() {
                vec4 sampled=texture(image,uv);
                vec3 rgb=mix(sampled.rgb/max(sampled.a,.00001),vec3(1.0),whiteLift);
                if(hasLut) rgb=texture(colorLut,clamp(rgb,0.0,1.0)).rgb;
                // gl_FragCoord's origin is bottom-left: only the lower half receives this scrim.
                rgb*=1.0-.4*clamp(1.0-2.0*gl_FragCoord.y/outputHeight,0.0,1.0);
                color=vec4(rgb,1.0);
            }
        """
    }
}
