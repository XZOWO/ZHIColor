package com.naivety.zhicolor.advanced

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.naivety.zhicolor.home.BackgroundAudioFrame
import java.util.concurrent.atomic.AtomicReference

/** The host never constructs a Visualizer or the root capture helper. */
internal class AudioDynamicsClient(
    context: Context,
    private val moduleUid: Int,
    private val onFrame: (List<Float>, Float, BackgroundAudioFrame) -> Unit,
) {
    private val app = context.applicationContext ?: context
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var endpoint: IBinder? = null
    private var protocolVersion = 1
    private var connection: ServiceConnection? = null
    private var request: PlaybackCaptureRequest? = null
    private var generation = 0L
    @Volatile private var released = false
    private var lastFrameAt = 0L
    private val pending = AtomicReference<Frame?>(null)

    private data class Frame(val generation: Long, val sequence: Long, val timestamp: Long,
        val values: FloatArray, val loudness: Float, val background: BackgroundAudioFrame, val service: IBinder?)

    private val deliver = Runnable {
        val frame = pending.getAndSet(null)
        if (frame != null) {
            if (!released && request != null && AudioFramePolicy.accepts(generation, frame.generation,
                    SystemClock.elapsedRealtime(), frame.timestamp, frame.values, frame.loudness)) {
                lastFrameAt = frame.timestamp
                val background = BackgroundAudioFramePolicy.freshOrSilent(frame.background, SystemClock.elapsedRealtimeNanos())
                onFrame(frame.values.toList(), frame.loudness, background)
            }
            acknowledge(frame)
        }
    }

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(AudioDynamicsProtocol.CALLBACK); return true }
            if (code != AudioDynamicsProtocol.FRAME && code != AudioDynamicsProtocol.FRAME_V2)
                return super.onTransact(code, data, reply, flags)
            if (getCallingUid() != moduleUid) throw SecurityException("Unexpected audio publisher")
            data.enforceInterface(AudioDynamicsProtocol.CALLBACK)
            val generation = data.readLong()
            val sequence = data.readLong()
            val timestamp = data.readLong()
            val values = data.createFloatArray() ?: FloatArray(0)
            val loudness = data.readFloat()
            val background = if (code == AudioDynamicsProtocol.FRAME_V2) {
                val latest = data.readBackgroundReport()
                val count = data.readInt()
                require(count in 0..4)
                latest.copy(history = List(count) { data.readBackgroundReport() })
            } else BackgroundAudioFrame.SILENT
            val frame = Frame(generation, sequence, timestamp, values, loudness, background, endpoint)
            require(data.dataAvail() == 0)
            if (released) { acknowledge(frame); return true }
            pending.getAndSet(frame)?.let(::acknowledge)
            main.removeCallbacks(deliver)
            main.post(deliver)
            return true
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (released || request == null) return
            if (connection == null) bind()
            renew()
            main.postDelayed(this, 5_000L)
        }
    }
    private val stale = object : Runnable {
        override fun run() {
            if (released || request == null) return
            if (SystemClock.elapsedRealtime() - lastFrameAt > AudioFramePolicy.MAX_AGE_MS) silence()
            main.postDelayed(this, 250L)
        }
    }

    fun update(next: PlaybackCaptureRequest?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (released || request == next) return
        request = next
        generation++
        silence()
        if (next == null || next.sessionId <= 0) {
            request = null
            disconnect()
        } else {
            if (connection == null) bind() else renew()
            main.removeCallbacks(heartbeat)
            main.postDelayed(heartbeat, 5_000L)
            main.removeCallbacks(stale)
            main.postDelayed(stale, 250L)
        }
    }

    private fun bind() {
        if (released || request == null || connection != null) return
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (connection !== this || released || request == null) return
                endpoint = service
                protocolVersion = readProtocolVersion(service)
                renew()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (connection !== this) return
                endpoint = null
                protocolVersion = 1
                generation++
                silence()
            }
            override fun onBindingDied(name: ComponentName) {
                if (connection !== this) return
                disconnect()
                if (request != null && !released) {
                    main.postDelayed(heartbeat, 1_000L)
                    main.postDelayed(stale, 250L)
                }
            }
            override fun onNullBinding(name: ComponentName) = onBindingDied(name)
        }
        connection = next
        val intent = Intent().setComponent(ComponentName(AudioDynamicsProtocol.PACKAGE, AudioDynamicsProtocol.SERVICE))
        val bound = runCatching { app.bindService(intent, next, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "Audio service binding unavailable", it) }.getOrDefault(false)
        if (!bound && connection === next) {
            connection = null
            // Context can retain its local dispatcher even when AMS rejects the binding.
            runCatching { app.unbindService(next) }
        }
    }

    private fun renew() {
        val current = request ?: return
        send(if (protocolVersion >= 2) AudioDynamicsProtocol.UPDATE_V2 else AudioDynamicsProtocol.UPDATE) {
            writeLong(generation)
            writeInt(current.sessionId)
            writeString(current.route)
            writeString(current.playerPackage)
            writeString(current.trackKey)
        }
    }

    private fun readProtocolVersion(service: IBinder): Int {
        // A synchronous capability query is required: remote oneway unknown codes may still
        // report successful dispatch, so their Boolean result cannot negotiate a wire format.
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AudioDynamicsProtocol.DESCRIPTOR)
            if (!service.transact(AudioDynamicsProtocol.GET_PROTOCOL_VERSION, data, reply, 0)) 1
            else { reply.readException(); reply.readInt().coerceAtLeast(1) }
        } catch (failure: Exception) {
            Log.d(TAG, "Using audio protocol v1", failure)
            1
        } finally { data.recycle(); reply.recycle() }
    }

    private fun Parcel.readBackgroundReport(): BackgroundAudioFrame {
        val low = readFloat(); val mid = readFloat(); val high = readFloat()
        val level = readFloat(); val capturedNanos = readLong(); val calibrated = readInt()
        require(calibrated == 0 || calibrated == 1)
        return BackgroundAudioFrame(low, mid, high, level, capturedNanos, calibrated == 1)
    }

    private fun acknowledge(frame: Frame) {
        send(AudioDynamicsProtocol.ACK, frame.service) {
            writeLong(frame.generation)
            writeLong(frame.sequence)
        }
    }

    private fun send(code: Int, service: IBinder? = endpoint, payload: Parcel.() -> Unit = {}) {
        if (service == null) return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(AudioDynamicsProtocol.DESCRIPTOR)
            data.writeStrongBinder(callback)
            data.payload()
            service.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (failure: Exception) { Log.d(TAG, "Audio service transaction ended", failure) }
        finally { data.recycle() }
    }

    private fun silence() {
        lastFrameAt = 0L
        onFrame(List(AudioFramePolicy.BAND_COUNT) { 0f }, 0f, BackgroundAudioFrame.SILENT)
    }

    private fun disconnect() {
        send(AudioDynamicsProtocol.STOP)
        endpoint = null
        protocolVersion = 1
        val previous = connection
        connection = null
        if (previous != null) runCatching { app.unbindService(previous) }
        pending.set(null)
        main.removeCallbacks(deliver)
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(stale)
    }

    fun release() {
        if (released) return
        released = true
        request = null
        generation++
        disconnect()
        silence()
    }
    private companion object { const val TAG = "ZHIColorAdvancedAudio" }
}
