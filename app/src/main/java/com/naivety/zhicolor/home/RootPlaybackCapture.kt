package com.naivety.zhicolor.home

import android.content.Context
import android.os.Process as AndroidProcess
import android.util.Log
import java.io.DataInputStream

/** The ordinary Visualizer remains primary. This stream repairs a silent vendor effect path. */
internal class RootPlaybackCapture(
    context: Context,
    private val playerUid: Int,
    private val onFrame: (List<Float>, Float, Float, BackgroundAudioFrame) -> Unit,
    private val onEnded: () -> Unit,
) : AutoCloseable {
    private val apk = context.applicationInfo.sourceDir
    @Volatile private var closed = false
    @Volatile private var process: Process? = null

    fun start() {
        Thread({
            try {
                if (closed) return@Thread
                require(playerUid >= 10000)
                val quotedApk = "'" + apk.replace("'", "'\"'\"'") + "'"
                val command = "CLASSPATH=$quotedApk exec /system/bin/app_process /system/bin " +
                    "com.naivety.zhicolor.home.PlaybackCaptureProcess $playerUid " + AndroidProcess.myPid()
                val child = ProcessBuilder("su", "-c", command).start()
                process = child
                if (closed) { close(); return@Thread }
                Thread({
                    child.errorStream.bufferedReader().use { lines ->
                        lines.lineSequence().forEach { Log.d(TAG, it.take(512)) }
                    }
                }, "ZHIColor-loopback-error").apply { isDaemon = true; start() }
                DataInputStream(child.inputStream.buffered()).use { input ->
                    check(input.readInt() == PlaybackCaptureProcess.MAGIC_V2)
                    while (!closed) {
                        val bands = List(SpectrumBands.COUNT) { input.readFloat() }
                        val rms = input.readFloat()
                        val peak = input.readFloat()
                        val background = BackgroundAudioFrame(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readLong(), true)
                        check(bands.all { it.isFinite() && it in 0f..1f } && rms.isFinite() && rms in 0f..1f && peak.isFinite() && peak in 0f..1f)
                        check(listOf(background.low, background.mid, background.high).all { it.isFinite() && it in 0f..1f } && background.level.isFinite() && background.level >= 0f && background.timestampNanos > 0L)
                        if (!closed) onFrame(bands, rms, peak, background)
                    }
                }
            } catch (error: Exception) {
                if (!closed) Log.w(TAG, "Playback loopback ended uid=$playerUid", error)
            } finally {
                close()
                onEnded()
            }
        }, "ZHIColor-loopback-read").apply { isDaemon = true; start() }
    }

    @Synchronized override fun close() {
        closed = true
        val child = process ?: return
        process = null
        // EOF wakes the helper's owner monitor and unregisters its temporary AudioPolicy.
        runCatching { child.outputStream.close() }
        Thread({
            if (!child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) child.destroy()
        }, "ZHIColor-loopback-close").apply { isDaemon = true; start() }
    }
    private companion object { const val TAG = "ZHIColorSpectrum" }
}
