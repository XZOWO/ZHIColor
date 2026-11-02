package com.naivety.zhicolor.advanced

import android.content.Context
import com.naivety.zhicolor.home.MusicSpectrumController
import com.naivety.zhicolor.home.BackgroundAudioFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One capture in the module process, shared by its activity and lock-screen bound service. */
internal class AudioCaptureHub private constructor(context: Context) {
    private val controller = MusicSpectrumController(context.applicationContext ?: context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val leases = LinkedHashSet<Lease>()
    private val policy = AudioCapturePolicy<Lease>()
    private var capturing: PlaybackCaptureRequest? = null

    init {
        scope.launch {
            controller.captureFrames.collect {
                synchronized(Companion) { publish() }
            }
        }
    }

    inner class Lease internal constructor() {
        private val bandState = MutableStateFlow(ZERO_BANDS)
        private val loudnessState = MutableStateFlow(0f)
        private val backgroundState = MutableStateFlow(BackgroundAudioFrame.SILENT)
        val bands = bandState.asStateFlow()
        val loudness = loudnessState.asStateFlow()
        val backgroundAudio = backgroundState.asStateFlow()
        private var closed = false

        fun start(session: Int, route: String = "", trackKey: String = "", playerPackage: String = "") {
            synchronized(Companion) {
                if (!closed) update(this, PlaybackCaptureRequest(session, route, trackKey, playerPackage))
            }
        }
        fun stop() = synchronized(Companion) { if (!closed) update(this, null) }
        fun close() = synchronized(Companion) {
            if (!closed) {
                update(this, null)
                closed = true
                leases.remove(this)
                if (leases.isEmpty()) {
                    scope.cancel()
                    controller.close()
                    synchronized(Companion) { if (instance === this@AudioCaptureHub) instance = null }
                }
            }
        }
        internal fun publish(values: List<Float>, level: Float, background: BackgroundAudioFrame) {
            bandState.value = values
            loudnessState.value = level
            backgroundState.value = background
        }
    }

    private fun update(lease: Lease, request: PlaybackCaptureRequest?) {
        policy.update(lease, request)
        val next = policy.selected
        if (next != capturing) {
            capturing = next
            if (next == null) controller.stop()
            else controller.start(next.sessionId, next.route, next.trackKey, next.playerPackage)
        }
        publish()
    }

    private fun publish() {
        leases.forEach { lease ->
            if (policy.receives(lease)) controller.captureFrames.value.let { lease.publish(it.bands, it.loudness, it.backgroundAudio) }
            else lease.publish(ZERO_BANDS, 0f, BackgroundAudioFrame.SILENT)
        }
    }

    companion object {
        private var instance: AudioCaptureHub? = null
        private val ZERO_BANDS = List(AudioFramePolicy.BAND_COUNT) { 0f }
        @Synchronized fun acquire(context: Context): AudioCaptureHub.Lease {
            val hub = instance ?: AudioCaptureHub(context).also { instance = it }
            return hub.Lease().also { hub.leases.add(it) }
        }
    }
}
