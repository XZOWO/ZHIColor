package com.naivety.zhicolor.home

import com.naivety.zhicolor.advanced.BackgroundAudioFramePolicy
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

/** Device regression: ~20 Hz 1024-byte captures with a .001--.0038 output RMS. */
class NormalizedBackgroundMotionTest {
    private fun capture(rms: Float, bass: Boolean = true): WaveformSpectrum.Frame {
        val bin = if (bass) 2 else 16
        val bytes = ByteArray(1024) {
            (128 + (128 * rms * sqrt(2.0) * sin(2 * PI * bin * it / 1024)).roundToInt())
                .coerceIn(0, 255).toByte()
        }
        return WaveformSpectrum().analyze(bytes, 48000000)
    }

    // Same geometry expression as ArtworkGlRenderer; no gain/curve changes in this repair.
    private fun scale(p: BackgroundAudioPower): Float {
        val weighted = .9f * p.low + .1f * p.mid
        return 1f + .33f * weighted * weighted
    }

    @Test fun observedOutputLevelsRetainPerceptibleBassGeometryAt20Hz() {
        for ((rms, meter) in listOf(.27f to .001f, .4f to .002f, .59f to .0038f)) {
            val pcm = capture(rms)
            val policy = BackgroundMotionPolicy()
            var latest = BackgroundAudioFrame.SILENT
            var result = BackgroundAudioPower(0f, 0f, 0f)
            repeat(120) { tick ->
                val now = (tick + 1L) * 16_666_667L
                if (tick % 3 == 0) latest = pcm.backgroundAudio.fromNormalizedCapture(meter, pcm.rms, now)
                result = policy.update(latest, 1f / 60f, now)
            }
            assertTrue("normalized music erased at meter=$meter rms=$rms scale=${scale(result)}", scale(result) > 1.15f)
            // Do not only check DTO flags: the old meter scaling reproduces the final regression.
            val oldPower = pcm.backgroundAudio.level * (meter / pcm.rms) * (meter / pcm.rms)
            val oldSample = BackgroundMotionPolicy.normalize(pcm.backgroundAudio.copy(level = oldPower))
            val oldGeometry = scale(BackgroundAudioPower(
                AmBackgroundEnvelope.curve(oldSample.low),
                AmBackgroundEnvelope.curve(oldSample.mid),
                AmBackgroundEnvelope.curve(oldSample.high)))
            assertTrue("old geometry should reproduce near-static output", oldGeometry < 1.01f)
        }
    }

    @Test fun outputVolumeChangesDoNotBecomeBeatsButMusicalSpectrumChangesDo() {
        val pcm = capture(.4f)
        val policy = BackgroundMotionPolicy()
        var reference: Float? = null
        var latest = BackgroundAudioFrame.SILENT
        repeat(180) { tick ->
            val now = (tick + 1L) * 16_666_667L
            if (tick % 3 == 0) latest = pcm.backgroundAudio.fromNormalizedCapture(
                if (tick < 90) .001f else .1f, pcm.rms, now)
            val value = scale(policy.update(latest, 1f / 60f, now))
            if (tick == 89) reference = value
            if (tick >= 90) assertEquals(reference!!, value, .000001f)
        }
        val treble = capture(.4f, bass = false)
        var final = BackgroundAudioPower(0f, 0f, 0f)
        repeat(300) { tick ->
            val now = (181L + tick) * 16_666_667L
            if (tick % 3 == 0) latest = treble.backgroundAudio.fromNormalizedCapture(.001f, treble.rms, now)
            final = policy.update(latest, 1f / 60f, now)
        }
        assertTrue(scale(final) < reference!! - .1f)
    }

    @Test fun appAndIpcHistoryAt16Or33msMatchIncludingMutePauseAndStale() {
        for (deliveryTicks in listOf(1, 2)) {
            val app = BackgroundMotionPolicy(); val lock = BackgroundMotionPolicy()
            val history = ArrayDeque<BackgroundAudioFrame>()
            var latest = BackgroundAudioFrame.SILENT
            repeat(360) { tick ->
                val now = (tick + 1L) * 16_666_667L
                if (tick == 180) { app.reset(); lock.reset(); history.clear() }
                if (tick % 3 == 0) {
                    val pcm = capture(if (tick % 30 < 9) .59f else .27f, bass = tick % 60 < 40)
                    latest = pcm.backgroundAudio.fromNormalizedCapture(if (tick in 120..149) 0f else .001f, pcm.rms, now)
                    history.addLast(latest); while (history.size > 4) history.removeFirst()
                    app.update(latest, 0f, now)
                }
                // Queue transport delivery before advancing the same logical render interval.
                if ((tick + 1) % deliveryTicks == 0) {
                    val snapshot = latest.copy(history = history.toList())
                    val validated = BackgroundAudioFramePolicy.freshOrSilent(snapshot, now)
                    assertEquals(snapshot, validated)
                    lock.update(validated, 0f, now)
                    val a = app.update(latest, deliveryTicks / 60f, now)
                    val b = lock.update(validated, deliveryTicks / 60f, now)
                    assertEquals(a, b); assertEquals(scale(a), scale(b), 0f)
                    assertEquals(a, app.update(latest, 0f, now))
                }
            }
            val stale = latest.timestampNanos + 500_000_001L
            assertEquals(1f, scale(app.update(latest, 1f / 60f, stale)), 0f)
            assertEquals(1f, scale(lock.update(latest, 1f / 60f, stale)), 0f)
            val quiet = capture(0f)
            assertEquals(0f, quiet.backgroundAudio.fromNormalizedCapture(null, quiet.rms, stale + 1).level, 0f)
        }
    }
}
