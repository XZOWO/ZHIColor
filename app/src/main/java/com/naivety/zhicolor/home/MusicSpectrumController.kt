package com.naivety.zhicolor.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns a playback-session visualizer only while its home-screen consumer is visible. */
internal class MusicSpectrumController(context: Context) {
    private val app = context.applicationContext
    private val state = MutableStateFlow(List(SpectrumBands.COUNT) { 0f })
    val bands = state.asStateFlow()
    private val loudnessState = MutableStateFlow(0f)
    /** Shared 0..1 musical dynamics; consumers must not normalize it again. */
    val loudness: StateFlow<Float> = loudnessState.asStateFlow()
    private val backgroundState = MutableStateFlow(BackgroundAudioFrame.SILENT)
    val backgroundAudio: StateFlow<BackgroundAudioFrame> = backgroundState.asStateFlow()
    data class CaptureFrame(val bands: List<Float>, val loudness: Float, val backgroundAudio: BackgroundAudioFrame)
    private val captureState = MutableStateFlow(CaptureFrame(state.value, 0f, BackgroundAudioFrame.SILENT))
    val captureFrames = captureState.asStateFlow()

    private fun publish(values: List<Float>, level: Float, background: BackgroundAudioFrame) {
        state.value = values
        loudnessState.value = level
        backgroundState.value = background
        captureState.value = CaptureFrame(values, level, background)
    }

    private fun silence() {
        session?.clearBackgroundHistory()
        publish(List(SpectrumBands.COUNT) { 0f }, 0f, BackgroundAudioFrame.SILENT)
    }
    private var session: CaptureSession? = null
    private var requestedSession: Int? = null
    private var requestedRoute = ""
    private var requestedMediaKey = ""
    private var requestedPlayerPackage = ""
    private var closed = false
    private var pendingRetry: Runnable? = null
    // One queue serializes every native creation/release, including rapid route and tab changes.
    private val captureThread = HandlerThread("ZHIColor-Spectrum").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val routeHandler = Handler(Looper.getMainLooper())
    private var observingDevices = false
    private var outputDevices = emptySet<Int>()
    private val routeChanged = Runnable {
        synchronized(this) { requestedSession?.let { replaceCapture(it, requestedRoute) } }
    }
    private val devicesCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshDevices()
    }

    @Synchronized fun start(audioSessionId: Int, routeKey: String = "", mediaKey: String = "", playerPackage: String = "") {
        if (closed) return
        if (audioSessionId <= 0) { stop(); return }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val target = audioSessionId
        requestedSession = target
        requestedRoute = routeKey
        requestedMediaKey = mediaKey
        requestedPlayerPackage = playerPackage
        if (!observingDevices) {
            outputDevices = currentOutputDevices()
            runCatching { audioManager?.registerAudioDeviceCallback(devicesCallback, routeHandler) }
                .onSuccess { observingDevices = true }
                .onFailure { Log.w(TAG, "Device observation unavailable", it) }
        }
        if (session?.audioSessionId != target || session?.routeKey != routeKey || session?.mediaKey != mediaKey || session?.playerPackage != playerPackage) replaceCapture(target, routeKey)
    }

    @Synchronized fun stop() {
        requestedSession = null
        pendingRetry?.let(routeHandler::removeCallbacks)
        pendingRetry = null
        routeHandler.removeCallbacks(routeChanged)
        if (observingDevices) runCatching { audioManager?.unregisterAudioDeviceCallback(devicesCallback) }
        observingDevices = false
        val previous = session
        session = null
        silence()
        previous?.stop()
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        stop()
        captureHandler.post { captureThread.quitSafely() }
    }

    private fun currentOutputDevices() = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Our UID loopback adds/removes a virtual port. It is not a physical route change;
        // rebinding on that notification creates a capture -> reconnect -> release feedback loop.
        ?.filterNot { it.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
        ?.map { it.id }?.toSet().orEmpty()

    @Synchronized private fun refreshDevices() {
        val current = currentOutputDevices()
        if (current == outputDevices || requestedSession == null) return
        outputDevices = current
        routeHandler.removeCallbacks(routeChanged)
        routeHandler.postDelayed(routeChanged, 400L)
    }

    private fun replaceCapture(audioSessionId: Int, routeKey: String, retryAttempt: Int = 0) {
        pendingRetry?.let(routeHandler::removeCallbacks)
        pendingRetry = null
        val previous = session
        silence()
        previous?.stop()
        val next = CaptureSession(audioSessionId, routeKey, requestedMediaKey, requestedPlayerPackage, retryAttempt)
        session = next
        next.start()
    }

    private inner class CaptureSession(val audioSessionId: Int, val routeKey: String, val mediaKey: String, val playerPackage: String, val retryAttempt: Int) {
        private val handler = captureHandler
        @Volatile private var stopped = false
        private var effect: Visualizer? = null
        private var frames = 0
        private var signalFrames = 0
        private var lastFrameAt = 0L
        private var waveformRms = 0f
        private val dynamics = MusicDynamics()
        private val backgroundHistory = ArrayDeque<BackgroundAudioFrame>()
        fun clearBackgroundHistory() = backgroundHistory.clear()
        private val spectrum = WaveformSpectrum()
        private val silencePolicy = SilentCapturePolicy()
        private var loopback: RootPlaybackCapture? = null
        private var loopbackActive = false
        private val loopbackTimeout = Runnable {
            if (!loopbackActive) { loopback?.close(); loopback = null }
        }
        private var measurementEnabled = false
        private val measurement = Visualizer.MeasurementPeakRms()
        private val staleCheck = object : Runnable {
            override fun run() {
                if (stopped) return
                if (SystemClock.elapsedRealtime() - lastFrameAt > 500L) {
                    synchronized(this@MusicSpectrumController) {
                        if (session === this@CaptureSession) {
                            silence()
                            waveformRms = 0f
                        }
                    }
                }
                handler.postDelayed(this, 250L)
            }
        }

        fun start() {
            handler.post {
                if (stopped) return@post
                try {
                    val visualizer = Visualizer(audioSessionId)
                    effect = visualizer
                    val range = Visualizer.getCaptureSizeRange()
                    visualizer.captureSize = 1024.coerceIn(range[0], range[1])
                    check(visualizer.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED) == Visualizer.SUCCESS)
                    measurementEnabled = runCatching {
                        visualizer.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS) == Visualizer.SUCCESS
                    }.getOrDefault(false)
                    val listener = object : Visualizer.OnDataCaptureListener {
                        override fun onFftDataCapture(visualizer: Visualizer, fft: ByteArray, samplingRate: Int) = Unit
                        override fun onWaveFormDataCapture(visualizer: Visualizer, waveform: ByteArray, samplingRate: Int) {
                            if (stopped || loopbackActive) return
                            val capturedAtNanos = SystemClock.elapsedRealtimeNanos()
                            val capture = spectrum.analyze(waveform, samplingRate)
                            waveformRms = capture.rms
                            val meter = readRawMeter(visualizer)
                            val background = capture.backgroundAudio.fromNormalizedCapture(
                                meter?.first, capture.rms, capturedAtNanos)
                            acceptFrame(capture.bands, capture.rms, meter?.first, meter?.second, "visualizer", background)
                        }
                    }
                    check(visualizer.setDataCaptureListener(listener, minOf(60_000, Visualizer.getMaxCaptureRate()), true, false) == Visualizer.SUCCESS)
                    check(visualizer.setEnabled(true) == Visualizer.SUCCESS)
                    lastFrameAt = SystemClock.elapsedRealtime()
                    handler.postDelayed(staleCheck, 250L)
                    Log.i(TAG, "started session=$audioSessionId route=$routeKey captureSize=${visualizer.captureSize} meter=${if (measurementEnabled) "measurement" else "waveform"}")
                } catch (error: Exception) {
                    Log.w(TAG, "Visualization unavailable session=$audioSessionId", error)
                    release()
                    stopped = true
                    synchronized(this@MusicSpectrumController) {
                        if (session === this@CaptureSession) {
                            session = null
                            silence()
                            if (!closed && requestedSession == audioSessionId && requestedRoute == routeKey && retryAttempt < 3) {
                                val retry = Runnable {
                                    synchronized(this@MusicSpectrumController) {
                                        pendingRetry = null
                                        if (!closed && session == null && requestedSession == audioSessionId && requestedRoute == routeKey) {
                                            replaceCapture(audioSessionId, routeKey, retryAttempt + 1)
                                        }
                                    }
                                }
                                pendingRetry = retry
                                routeHandler.postDelayed(retry, 500L shl retryAttempt)
                            }
                        }
                    }
                }
            }
        }

        fun stop() {
            stopped = true
            handler.post { release() }
        }

        private fun acceptFrame(values: List<Float>, rms: Float, rawRms: Float?, rawPeak: Float?, source: String, background: BackgroundAudioFrame) {
            if (stopped) return
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastFrameAt > 0L) (now - lastFrameAt).coerceAtLeast(0L) / 1_000f else 0.05f
            val volume = runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            val frame = dynamics.process(values, rms, rawRms, rawPeak, dt, volume)
            lastFrameAt = now
            synchronized(this@MusicSpectrumController) {
                if (session !== this@CaptureSession || stopped) return
                val raw = if (volume == 0) BackgroundAudioFrame.SILENT.copy(
                    timestampNanos = background.timestampNanos) else background.copy(history = emptyList())
                if (backgroundHistory.lastOrNull()?.timestampNanos?.let { it >= raw.timestampNanos } == true) backgroundHistory.clear()
                backgroundHistory.addLast(raw)
                while (backgroundHistory.size > 4) backgroundHistory.removeFirst()
                publish(frame.bands, frame.loudness, raw.copy(history = backgroundHistory.toList()))
            }
            frames++
            val peak = frame.bands.maxOrNull() ?: 0f
            if (peak > 0f) signalFrames++
            if (frames % 30 == 0) Log.d(TAG, "session=$audioSessionId frames=$frames signalFrames=$signalFrames peak=$peak rawRms=$rawRms normalizedRms=$rms loudness=${frame.loudness} onset=${frame.onset} reference=${frame.reference} volume=$volume dt=$dt source=$source")
            if (source == "visualizer" && silencePolicy.observe(rms, rawRms, dt, volume)) startLoopback()
        }

        private fun startLoopback() {
            if (stopped || loopback != null || playerPackage.isBlank()) return
            val uid = runCatching { app.packageManager.getApplicationInfo(playerPackage, 0).uid }.getOrNull() ?: return
            if (uid < 10000) return
            Log.i(TAG, "silent session=$audioSessionId; testing playback loopback uid=$uid")
            loopback = RootPlaybackCapture(app, uid, onFrame = { values, rms, peak, background ->
                handler.post {
                    if (!stopped && loopback != null && (rms > 0.000001f || loopbackActive)) {
                        if (!loopbackActive) {
                            loopbackActive = true
                            handler.removeCallbacks(loopbackTimeout)
                            dynamics.reset()
                            silence()
                            lastFrameAt = 0L
                            Log.i(TAG, "playback loopback active session=$audioSessionId uid=$uid")
                        }
                        acceptFrame(values, rms, rms, peak, "uid-loopback", background)
                    }
                }
            }, onEnded = {
                handler.post {
                    if (!stopped && loopbackActive) {
                        loopbackActive = false
                        dynamics.reset()
                        silence()
                        lastFrameAt = 0L
                        Log.i(TAG, "playback loopback released session=$audioSessionId")
                    }
                }
            }).also { it.start() }
            handler.postDelayed(loopbackTimeout, 5_000L)
        }

        private fun readRawMeter(visualizer: Visualizer): Pair<Float, Float>? {
            if (measurementEnabled) {
                val status = runCatching { visualizer.getMeasurementPeakRms(measurement) }
                    .getOrDefault(Visualizer.ERROR)
                if (status == Visualizer.SUCCESS) return LoudnessLevel.fromMillibels(measurement.mRms) to LoudnessLevel.fromMillibels(measurement.mPeak)
                measurementEnabled = false
                Log.i(TAG, "RMS measurement unavailable session=$audioSessionId status=$status; using waveform RMS")
            }
            return null
        }

        private fun release() {
            handler.removeCallbacks(staleCheck)
            handler.removeCallbacks(loopbackTimeout)
            loopback?.close()
            loopback = null
            loopbackActive = false
            effect?.let { visualizer ->
                runCatching { visualizer.setEnabled(false) }
                runCatching { visualizer.setDataCaptureListener(null, 0, false, false) }
                runCatching { visualizer.release() }
            }
            effect = null
            waveformRms = 0f
            Log.i(TAG, "released session=$audioSessionId frames=$frames signalFrames=$signalFrames")
        }
    }

    private companion object { const val TAG = "ZHIColorSpectrum" }
}
