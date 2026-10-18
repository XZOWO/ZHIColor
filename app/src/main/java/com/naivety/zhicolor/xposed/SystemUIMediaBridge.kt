package com.naivety.zhicolor.xposed

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import androidx.core.content.ContextCompat
import com.naivety.zhicolor.home.MediaContract as C
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object SystemUIMediaBridge {
    private val initialized = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ZHIColor-media-read").apply { isDaemon = true } }

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = context.applicationContext
        try {
            ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != C.REQUEST) return
                    @Suppress("DEPRECATION")
                    val callback = intent.getParcelableExtra<PendingIntent>(C.CALLBACK) ?: return
                    if (callback.creatorPackage != C.APPLICATION_ID) return
                    val expectedUid = runCatching { app.packageManager.getApplicationInfo(C.APPLICATION_ID, 0).uid }.getOrNull() ?: return
                    if (callback.creatorUid != expectedUid) return
                    val nonce = intent.getStringExtra(C.NONCE) ?: return
                    val preferred = intent.getStringExtra(C.PREFERRED_PACKAGE)
                    val pending = goAsync()
                    worker.execute {
                        try {
                            val reply = snapshot(app, preferred).putExtra(C.NONCE, nonce)
                            callback.send(app, 0, reply)
                        } catch (t: Throwable) {
                            Log.w("ZHIColorMedia", "media response failed", t)
                        } finally { pending.finish() }
                    }
                }
            }, IntentFilter(C.REQUEST), ContextCompat.RECEIVER_EXPORTED)
        } catch (t: Throwable) {
            initialized.set(false)
            throw t
        }
    }

    private fun snapshot(context: Context, preferred: String?): Intent {
        val sessions = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(null)
        val controller = sessions.firstOrNull { it.packageName == preferred && it.metadata != null }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
            ?: sessions.firstOrNull { it.metadata != null }
            ?: return Intent().putExtra(C.AVAILABLE, false)
        val data = controller.metadata
        val art = runCatching {
            data?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: data?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: data?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: data?.description?.iconBitmap
        }.getOrNull()
        return Intent()
            .putExtra(C.AVAILABLE, true)
            .putExtra(C.PACKAGE, controller.packageName)
            .putExtra(C.TITLE, data?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()?.take(1024))
            .putExtra(C.ARTIST, data?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()?.take(1024))
            .putExtra(C.PLAYING, controller.playbackState?.state == PlaybackState.STATE_PLAYING)
            .putExtra(C.COVER, art?.let(::encodeCover))
    }

    private fun encodeCover(bitmap: Bitmap): ByteArray? = runCatching {
        if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) return null
        val scale = minOf(1f, 384f / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        val bytes = ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
        if (scaled !== bitmap) scaled.recycle()
        bytes.takeIf { it.size in 1..C.MAX_COVER_BYTES }
    }.getOrNull()
}
