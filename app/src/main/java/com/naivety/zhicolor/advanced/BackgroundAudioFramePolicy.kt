package com.naivety.zhicolor.advanced

import com.naivety.zhicolor.home.BackgroundAudioFrame

/** A fresh Binder envelope does not make a stale capture fresh. */
internal object BackgroundAudioFramePolicy {
    fun freshOrSilent(frame: BackgroundAudioFrame, nowNanos: Long): BackgroundAudioFrame {
        if (!validReport(frame, nowNanos) || frame.history.size > 4) return BackgroundAudioFrame.SILENT
        var previous = 0L
        for (report in frame.history) {
            if (report.history.isNotEmpty() || !validReport(report, nowNanos) ||
                report.timestampNanos <= previous || report.timestampNanos > frame.timestampNanos)
                return BackgroundAudioFrame.SILENT
            previous = report.timestampNanos
        }
        return frame
    }

    private fun validReport(frame: BackgroundAudioFrame, nowNanos: Long): Boolean =
        frame.timestampNanos > 0L && frame.timestampNanos <= nowNanos &&
            nowNanos - frame.timestampNanos <= AudioFramePolicy.MAX_AGE_MS * 1_000_000L &&
            listOf(frame.low, frame.mid, frame.high).all { it.isFinite() && it in 0f..1f } &&
            frame.level.isFinite() && frame.level >= 0f
}
