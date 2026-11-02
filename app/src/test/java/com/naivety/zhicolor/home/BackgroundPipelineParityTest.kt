package com.naivety.zhicolor.home

import com.naivety.zhicolor.advanced.BackgroundAudioFramePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** Both display entrances consume the same captured reports, not their transport frequency. */
class BackgroundPipelineParityTest {
    @Test fun appCallbacksAndValidatedIpcSnapshotsProduceTheSameEnvelopeEveryRenderTick() {
        val app = BackgroundMotionPolicy()
        val lockscreen = BackgroundMotionPolicy()
        val history = ArrayDeque<BackgroundAudioFrame>()
        repeat(180) { tick ->
            repeat(2) { subframe ->
                val index = tick * 2 + subframe
                val report = BackgroundAudioFrame(
                    if (index % 19 < 3) .8f else .15f,
                    if (index % 13 < 4) .5f else .1f,
                    if (index % 7 == 0) .4f else .02f,
                    if (index % 19 < 3) 100000f else 150f,
                    (index + 1L) * 8_333_333L,
                )
                history.addLast(report)
                while (history.size > 4) history.removeFirst()
                app.update(report, 0f, report.timestampNanos)
            }
            val latest = history.last()
            val snapshot = latest.copy(history = history.toList())
            val validated = BackgroundAudioFramePolicy.freshOrSilent(snapshot, latest.timestampNanos)
            assertEquals(snapshot, validated)
            lockscreen.update(validated, 0f, latest.timestampNanos)
            assertEquals(app.update(latest, 1f / 60f, latest.timestampNanos),
                lockscreen.update(validated, 1f / 60f, latest.timestampNanos))
            // A repeat publication or a second UI read cannot append old history again.
            assertEquals(app.update(latest, 0f, latest.timestampNanos),
                lockscreen.update(validated, 0f, latest.timestampNanos))
        }
    }
}
