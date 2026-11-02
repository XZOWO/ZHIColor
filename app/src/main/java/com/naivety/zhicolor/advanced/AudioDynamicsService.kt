package com.naivety.zhicolor.advanced

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.naivety.zhicolor.home.BackgroundAudioFrame

/** Capture stays in the module UID, so its permissions and app_process APK remain correct. */
class AudioDynamicsService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashMap<IBinder, Listener>()
    private var sequence = 0L
    private var destroyed = false
    private val systemUiUid by lazy {
        runCatching { packageManager.getApplicationInfo("com.android.systemui", 0).uid }.getOrDefault(-1)
    }

    private inner class Listener(val callback: IBinder, val ownerUid: Int) : IBinder.DeathRecipient {
        val capture = AudioCaptureHub.acquire(this@AudioDynamicsService)
        var generation = 0L
        var lastRenewedAt = 0L
        var inFlight = 0L
        var inFlightGeneration = 0L
        var rawBackground = false
        var nextPublishAt = 0L
        override fun binderDied() { main.post { remove(callback) } }
        fun close() {
            runCatching { callback.unlinkToDeath(this, 0) }
            capture.close()
        }
    }

    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(AudioDynamicsProtocol.DESCRIPTOR); return true }
            val uid = getCallingUid()
            if (!AudioSubscriberPolicy.accepts(uid, Process.myUid(), systemUiUid))
                throw SecurityException("Unexpected audio subscriber")
            if (code !in AudioDynamicsProtocol.UPDATE..AudioDynamicsProtocol.GET_PROTOCOL_VERSION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(AudioDynamicsProtocol.DESCRIPTOR)
            if (code == AudioDynamicsProtocol.GET_PROTOCOL_VERSION) {
                require(data.dataAvail() == 0)
                reply?.writeNoException()
                reply?.writeInt(AudioDynamicsProtocol.VERSION)
                return true
            }
            val callback = data.readStrongBinder() ?: return false
            when (code) {
                AudioDynamicsProtocol.UPDATE, AudioDynamicsProtocol.UPDATE_V2 -> {
                    val generation = data.readLong()
                    val session = data.readInt()
                    val route = data.readString().orEmpty().take(1024)
                    val packageName = data.readString().orEmpty().take(256)
                    val track = data.readString().orEmpty().take(2048)
                    require(data.dataAvail() == 0)
                    main.post {
                        if (!destroyed && session > 0) {
                            val listener = listeners[callback] ?: run {
                                if (listeners.size >= 8) return@post
                                val next = Listener(callback, uid)
                                if (runCatching { callback.linkToDeath(next, 0) }.isFailure) {
                                    next.close(); return@post
                                }
                                listeners[callback] = next
                                next
                            }
                            if (listener.ownerUid != uid) return@post
                            listener.generation = generation
                            listener.rawBackground = code == AudioDynamicsProtocol.UPDATE_V2
                            listener.nextPublishAt = 0L
                            listener.lastRenewedAt = SystemClock.elapsedRealtime()
                            listener.capture.start(session, route, track, packageName)
                            schedule()
                        }
                    }
                }
                AudioDynamicsProtocol.STOP -> {
                    require(data.dataAvail() == 0)
                    main.post { if (listeners[callback]?.ownerUid == uid) remove(callback) }
                }
                AudioDynamicsProtocol.ACK -> {
                    val generation = data.readLong()
                    val acknowledged = data.readLong()
                    require(data.dataAvail() == 0)
                    main.post {
                        listeners[callback]?.let {
                            if (it.ownerUid == uid && it.inFlight == acknowledged &&
                                it.inFlightGeneration == generation) it.inFlight = 0L
                        }
                    }
                }
            }
            return true
        }
    }

    private val publish = object : Runnable {
        override fun run() {
            if (destroyed) return
            val now = SystemClock.elapsedRealtime()
            for (listener in listeners.values.toList()) {
                if (now - listener.lastRenewedAt > AudioFramePolicy.LEASE_MS) {
                    remove(listener.callback)
                    continue
                }
                // One unacknowledged callback per client. A busy SystemUI never queues old frames.
                if (listener.inFlight != 0L) continue
                if (now < listener.nextPublishAt) continue
                val parcel = Parcel.obtain()
                try {
                    val next = ++sequence
                    parcel.writeInterfaceToken(AudioDynamicsProtocol.CALLBACK)
                    parcel.writeLong(listener.generation)
                    parcel.writeLong(next)
                    parcel.writeLong(now)
                    parcel.writeFloatArray(listener.capture.bands.value.toFloatArray())
                    parcel.writeFloat(listener.capture.loudness.value)
                    if (listener.rawBackground) {
                        val background = BackgroundAudioFramePolicy.freshOrSilent(
                            listener.capture.backgroundAudio.value, SystemClock.elapsedRealtimeNanos())
                        parcel.writeBackgroundReport(background)
                        parcel.writeInt(background.history.size)
                        background.history.forEach { parcel.writeBackgroundReport(it) }
                    }
                    listener.inFlight = next
                    listener.inFlightGeneration = listener.generation
                    listener.nextPublishAt = now + if (listener.rawBackground) 16L else 33L
                    val frameCode = if (listener.rawBackground) AudioDynamicsProtocol.FRAME_V2 else AudioDynamicsProtocol.FRAME
                    if (!listener.callback.transact(frameCode, parcel, null, IBinder.FLAG_ONEWAY)) remove(listener.callback)
                } catch (failure: Exception) {
                    Log.d(TAG, "Audio subscriber released", failure)
                    remove(listener.callback)
                } finally { parcel.recycle() }
            }
            if (listeners.isNotEmpty()) {
                val delay = listeners.values.minOf { listener ->
                    if (listener.inFlight != 0L) (if (listener.rawBackground) 16L else 33L)
                    else (listener.nextPublishAt - now).coerceIn(1L, 33L)
                }
                main.postDelayed(this, delay)
            }
        }
    }

    private fun schedule() {
        if (!main.hasCallbacks(publish)) main.post(publish)
    }
    private fun Parcel.writeBackgroundReport(frame: BackgroundAudioFrame) {
        writeFloat(frame.low); writeFloat(frame.mid); writeFloat(frame.high); writeFloat(frame.level)
        writeLong(frame.timestampNanos); writeInt(if (frame.calibrated) 1 else 0)
    }
    private fun remove(callback: IBinder) {
        listeners.remove(callback)?.close()
        if (listeners.isEmpty()) main.removeCallbacks(publish)
    }

    override fun onBind(intent: Intent): IBinder = endpoint
    override fun onUnbind(intent: Intent): Boolean {
        listeners.values.toList().forEach { remove(it.callback) }
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        listeners.values.toList().forEach { remove(it.callback) }
        super.onDestroy()
    }
    private companion object { const val TAG = "ZHIColorAdvancedAudio" }
}
