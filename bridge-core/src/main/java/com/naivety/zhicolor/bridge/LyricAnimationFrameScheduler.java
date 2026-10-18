package com.naivety.zhicolor.bridge;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.function.IntSupplier;

/** Coalesces row invalidation, progress and layout animation onto the same clock. */
final class LyricAnimationFrameScheduler {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<View, Boolean> views = new WeakHashMap<>();
    private final LyricFrameDispatcher dispatcher;
    private final IntSupplier cap;
    private final LyricAnimationSampleClock samples = new LyricAnimationSampleClock();
    private final Runnable flush = this::invalidatePendingViews;

    LyricAnimationFrameScheduler(IntSupplier cap) {
        this.cap = cap;
        dispatcher = new LyricFrameDispatcher(cap,
                callback -> Choreographer.getInstance().postFrameCallback(callback::accept));
    }

    void invalidate(View view) {
        if (view == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> invalidate(view));
            return;
        }
        if (!view.isAttachedToWindow()) return;
        views.put(view, Boolean.TRUE);
        dispatcher.request(flush);
    }

    void post(Runnable action) {
        if (Looper.myLooper() != Looper.getMainLooper()) main.post(() -> post(action));
        else dispatcher.request(action);
    }

    void cancel(Runnable action) { dispatcher.cancel(action); }

    long sampleTimeMillis() {
        return samples.sample(SystemClock.elapsedRealtimeNanos(), cap.getAsInt()) / 1_000_000L;
    }

    private void invalidatePendingViews() {
        ArrayList<View> batch = new ArrayList<>(views.keySet());
        views.clear();
        for (View view : batch) if (view != null && view.isAttachedToWindow()) view.invalidate();
    }
}
