package com.naivety.zhicolor.bridge.render;

/** Per-lyric-line completion and highlight fade; never attached to a recycled Android view. */
public final class LyricLineExitPolicy {
    private static final long MAX_EXIT_MS = 640L;
    private static final long MAX_OBSERVATION_GAP_MS = 1_000L;
    private boolean initialized;
    private boolean active;
    private boolean exiting;
    private final ZhiToolLyricMotion.Value entrance = new ZhiToolLyricMotion.Value();
    private final ZhiToolLyricMotion.Value liftEntrance = new ZhiToolLyricMotion.Value();
    private float exitFromHighlight;
    private float exitFromLift;
    private float liftActivation;
    private long lastPosition;
    private long lastSampleAt;
    private long finishPosition;
    private long exitStartedAt;
    private long exitFromPosition;
    private long exitMillis;
    private long renderPosition;
    private float highlight;

    /**
     * Active reveal follows playback exactly, including pause. A natural deactivation completes
     * the reveal/lift and fades the highlight together, using one bounded monotonic UI clock.
     * Position jumps, detached/reused rows and disabled animation discard the tail immediately.
     */
    public void sample(boolean nextActive, long position, long now, boolean animate,
                       long completeAtPosition, long highlightFadeMillis) {
        long elapsed = initialized ? Math.max(0L, now - lastSampleAt) : 0L;
        boolean continuous = initialized && now >= lastSampleAt
                && elapsed <= MAX_OBSERVATION_GAP_MS
                && position >= lastPosition
                && position - lastPosition <= Math.max(160L, elapsed + 160L);
        if (!animate || !continuous) {
            exiting = false;
            active = nextActive;
            entrance.reset(nextActive ? 1f : 0f);
            liftEntrance.reset(nextActive ? 1f : 0f);
        } else if (active && !nextActive) {
            exitFromHighlight = entrance.value(now);
            exitFromLift = liftEntrance.value(now);
            exiting = true;
            exitStartedAt = now;
            exitFromPosition = lastPosition;
            // A short/already-complete lift retains the normal focus fade. A longer lift
            // extends that same fade so neither channel ends before the other.
            exitMillis = Math.min(MAX_EXIT_MS, Math.max(Math.max(1L, highlightFadeMillis),
                    Math.max(0L, finishPosition - exitFromPosition)));
        } else if (!active && nextActive) {
            // An interrupted tail re-enters from the ink actually visible at this instant.
            float current = exiting ? exitHighlight(now) : 0f;
            entrance.reset(current);
            entrance.retarget(1f, now, highlightFadeMillis, true);
            liftEntrance.reset(exiting ? exitFromLift : 0f);
            liftEntrance.retarget(1f, now, highlightFadeMillis, true);
        }

        if (nextActive) {
            exiting = false;
            finishPosition = Math.max(position, completeAtPosition);
            renderPosition = position;
            highlight = entrance.value(now);
            liftActivation = liftEntrance.value(now);
        } else if (exiting) {
            long exitElapsed = Math.max(0L, now - exitStartedAt);
            double progress = Math.min(1d, exitElapsed / (double) exitMillis);
            renderPosition = exitFromPosition
                    + (long) ((finishPosition - exitFromPosition) * progress);
            // The complement of ZHITool's ease-out cubic, evaluated directly so tiny
            // positive highlight values do not round to zero before the shared deadline.
            highlight = exitHighlight(now);
            liftActivation = exitFromLift;
            if (exitElapsed >= exitMillis) {
                exiting = false;
                highlight = 0f;
                liftActivation = 0f;
            }
        } else {
            renderPosition = position;
            highlight = 0f;
            liftActivation = 0f;
        }
        initialized = true;
        active = nextActive;
        lastPosition = position;
        lastSampleAt = now;
    }

    public boolean isExiting() { return exiting; }
    public boolean isEntering(long now) { return active && entrance.isRunning(now); }
    public long exitDurationMillis() { return exiting ? exitMillis : 0L; }
    public long renderPosition() { return renderPosition; }
    public float highlightAmount() { return highlight; }
    public float liftActivationAmount() { return liftActivation; }

    private float exitHighlight(long now) {
        float remaining = (float) (1d - Math.min(1d,
                Math.max(0L, now - exitStartedAt) / (double) Math.max(1L, exitMillis)));
        return exitFromHighlight * remaining * remaining * remaining;
    }

    public void reset() {
        initialized = false;
        active = false;
        exiting = false;
        entrance.reset(0f);
        liftEntrance.reset(0f);
        liftActivation = 0f;
        highlight = 0f;
        renderPosition = 0L;
    }
}
