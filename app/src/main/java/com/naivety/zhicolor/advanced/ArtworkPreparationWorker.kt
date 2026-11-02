package com.naivety.zhicolor.advanced

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference

/** Pixel comparison and ownership copies never execute on the SystemUI frame thread. */
internal class ArtworkPreparationWorker(
    initialBitmap: Bitmap?,
    initialTrack: String,
    private val onPrepared: (Long, Bitmap, String) -> Unit,
) {
    private val thread = HandlerThread("ZHIColor-ArtworkPrepare").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var cachedBitmap = initialBitmap
    private var cachedTrack = initialTrack
    @Volatile private var closed = false
    @Volatile private var newestTicket = 0L
    private data class Request(val ticket: Long, val bitmap: Bitmap, val generation: Int, val track: String)
    private val pending = AtomicReference<Request?>(null)

    private val prepare = Runnable {
        val request = pending.getAndSet(null)
        if (request != null && !closed && request.ticket == newestTicket) {
            val prepared = runCatching {
                val incoming = request.bitmap
                if (incoming.isRecycled || incoming.generationId != request.generation) return@runCatching null
                val previous = cachedBitmap
                // Normalize hardware-backed metadata into owned software pixels before sameAs.
                val copy = incoming.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching null
                // A mutable metadata bitmap may have changed while it was being copied.
                if (incoming.isRecycled || incoming.generationId != request.generation) {
                    copy.recycle()
                    null
                } else {
                    val unchanged = previous != null && !previous.isRecycled && cachedTrack == request.track &&
                        previous.width == copy.width && previous.height == copy.height && previous.sameAs(copy)
                    if (unchanged) { copy.recycle(); previous } else copy
                }
            }.getOrNull()
            if (prepared != null) {
                if (!closed && request.ticket == newestTicket) {
                    cachedBitmap = prepared
                    cachedTrack = request.track
                    main.post {
                        if (!closed && request.ticket == newestTicket) onPrepared(request.ticket, prepared, request.track)
                    }
                } else if (prepared !== cachedBitmap) prepared.recycle()
            }
        }
    }

    fun prepare(ticket: Long, bitmap: Bitmap, generation: Int, track: String) {
        if (closed) return
        newestTicket = ticket
        pending.set(Request(ticket, bitmap, generation, track))
        worker.removeCallbacks(prepare)
        worker.post(prepare)
    }

    fun close() {
        if (closed) return
        closed = true
        pending.set(null)
        worker.removeCallbacks(prepare)
        // Previously published bitmaps belong to the renderer and hardware Canvas.
        // Their native/GC references retire them; do not recycle a display-list input.
        thread.quitSafely()
    }
}
