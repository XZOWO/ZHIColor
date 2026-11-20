package com.naivety.zhicolor.bridge.render;

/**
 * Lyric scroll spring, staggered per-line cascade, and focus transition timing.
 */
public final class AppleLyricMotionPolicy {
    public static final long SCROLL_MS = 680L;
    public static final long FOCUS_MS = 280L;
    public static final long LINE_DELAY_MS = 35L;
    public static final long REVERSE_LINE_DELAY_MS = 20L;
    public static final int MAX_CASCADE_STEPS = 5;

    public static final float DEFAULT_DAMPING_RATIO = 0.78f;
    public static final float FAST_DAMPING_SPAN = 0.12f;
    public static final float FAST_RESPONSE_SECONDS = 0.48f;
    public static final float RESPONSE_SPAN_SECONDS = 0.27f;
    public static final float DEFAULT_RESPONSE_SECONDS = FAST_RESPONSE_SECONDS + RESPONSE_SPAN_SECONDS;

    private static final float INTERVAL_MIN_SECONDS = 0.20f;
    private static final float INTERVAL_MAX_SECONDS = 0.75f;
    private static final float INTERVAL_SPAN_SECONDS = 0.55f;
    private static final float SPRING_SETTLE_MULTIPLIER = 1.20f;

    private static final float FOCUS_P1X = 0.17f;
    private static final float FOCUS_P1Y = 0.0f;
    private static final float FOCUS_P2X = 0.83f;
    private static final float FOCUS_P2Y = 1.0f;

    private AppleLyricMotionPolicy() {}

    public static float dampingRatioForIntervalMillis(long intervalMillis) {
        float t = intervalInterpolation(intervalMillis);
        return DEFAULT_DAMPING_RATIO + (1f - t) * FAST_DAMPING_SPAN;
    }

    public static float responseSecondsForIntervalMillis(long intervalMillis) {
        float t = intervalInterpolation(intervalMillis);
        return FAST_RESPONSE_SECONDS + t * RESPONSE_SPAN_SECONDS;
    }

    public static float springValue(float progress) {
        return springValue(progress, DEFAULT_DAMPING_RATIO, DEFAULT_RESPONSE_SECONDS);
    }

    public static float springValue(float progress, float dampingRatio, float responseSeconds) {
        if (!Float.isFinite(progress) || progress <= 0f) {
            return 0f;
        }
        if (progress >= 1f) {
            return 1f;
        }
        double zeta = clamp(dampingRatio, 0.35f, 0.995f);
        double response = clamp(responseSeconds, 0.15f, 2.0f);
        double omegaN = (2d * Math.PI) / response;
        double omegaD = omegaN * Math.sqrt(1d - zeta * zeta);
        // Cap evaluation at the first spring peak so RecyclerView OverScroller never reverses
        // downward at the end of a scroll while incoming sung characters lift upward.
        double firstPeakSeconds = Math.PI / omegaD;
        double endSeconds = Math.min(SPRING_SETTLE_MULTIPLIER * response, firstPeakSeconds);
        double endValue = evaluateUnderdampedSpring(endSeconds, zeta, response);
        double sampleSeconds = Math.min(progress * endSeconds, endSeconds);
        double currentValue = evaluateUnderdampedSpring(sampleSeconds, zeta, response);
        if (!Double.isFinite(endValue) || Math.abs(endValue) < 1e-6d) {
            return clampUnit(progress);
        }
        double normalized = currentValue / endValue;
        return Double.isFinite(normalized) ? clampUnit((float) normalized) : clampUnit(progress);
    }

    public static float focusInterpolation(float progress) {
        if (!Float.isFinite(progress) || progress <= 0f) {
            return 0f;
        }
        if (progress >= 1f) {
            return 1f;
        }
        double targetX = progress;
        double low = 0d;
        double high = 1d;
        double u = targetX;
        for (int i = 0; i < 6; i++) {
            double x = cubicBezier(u, FOCUS_P1X, FOCUS_P2X) - targetX;
            if (Math.abs(x) < 1e-6d) {
                break;
            }
            if (x < 0d) {
                low = u;
            } else {
                high = u;
            }
            double dx = cubicBezierDerivative(u, FOCUS_P1X, FOCUS_P2X);
            if (Math.abs(dx) < 1e-6d) {
                u = 0.5d * (low + high);
            } else {
                double next = u - x / dx;
                u = (next > low && next < high) ? next : 0.5d * (low + high);
            }
        }
        return clampUnit((float) cubicBezier(u, FOCUS_P1Y, FOCUS_P2Y));
    }

    public static long cascadeDelayMillis(
            int rowPosition,
            int targetPosition,
            int lastVisiblePosition,
            float scrollDeltaPx) {
        if (!Float.isFinite(scrollDeltaPx) || Math.abs(scrollDeltaPx) < 0.5f) {
            return 0L;
        }
        if (scrollDeltaPx >= 0f) {
            if (rowPosition < 0 || targetPosition < 0 || rowPosition <= targetPosition) {
                return 0L;
            }
            int steps = Math.min(MAX_CASCADE_STEPS, rowPosition - targetPosition);
            return steps * LINE_DELAY_MS;
        }
        if (rowPosition < 0 || lastVisiblePosition < 0 || lastVisiblePosition <= rowPosition) {
            return 0L;
        }
        int steps = Math.min(MAX_CASCADE_STEPS, lastVisiblePosition - rowPosition);
        return steps * REVERSE_LINE_DELAY_MS;
    }

    public static float cascadeTranslationY(
            float scrollDeltaPx,
            long elapsedMillis,
            long scrollDurationMillis,
            long delayMillis,
            float dampingRatio,
            float responseSeconds) {
        if (!Float.isFinite(scrollDeltaPx)
                || Math.abs(scrollDeltaPx) < 0.5f
                || delayMillis <= 0L
                || scrollDurationMillis <= 0L
                || elapsedMillis <= 0L
                || elapsedMillis >= scrollDurationMillis + delayMillis) {
            return 0f;
        }
        float mainProgress = springValue(
                clampUnit((float) elapsedMillis / (float) scrollDurationMillis),
                dampingRatio,
                responseSeconds);
        float delayedProgress = elapsedMillis <= delayMillis
                ? 0f
                : springValue(
                        clampUnit((float) (elapsedMillis - delayMillis) / (float) scrollDurationMillis),
                        dampingRatio,
                        responseSeconds);
        float translation = scrollDeltaPx * (mainProgress - delayedProgress);
        return Float.isFinite(translation) ? translation : 0f;
    }

    /**
     * Dimensionless lag fraction in [0, 1] used to scale a row's stagger translation strictly by
     * that row's own observed screen displacement:
     *   r(t) = 1                                         for 0 <= t <= delay
     *   r(t) = (S_main(t) - S_delayed(t - delay)) / S_main(t)  for delay < t < duration + delay
     *   r(t) = 0                                         for t >= duration + delay
     */
    public static float cascadeLagRatio(
            long elapsedMillis,
            long scrollDurationMillis,
            long delayMillis,
            float dampingRatio,
            float responseSeconds) {
        if (delayMillis <= 0L || scrollDurationMillis <= 0L) {
            return 0f;
        }
        if (elapsedMillis <= delayMillis) {
            return 1f;
        }
        if (elapsedMillis >= scrollDurationMillis + delayMillis) {
            return 0f;
        }
        float mainProgress = springValue(
                clampUnit((float) elapsedMillis / (float) scrollDurationMillis),
                dampingRatio,
                responseSeconds);
        if (!Float.isFinite(mainProgress) || mainProgress <= 1e-6f) {
            return 1f;
        }
        float delayedProgress = springValue(
                clampUnit((float) (elapsedMillis - delayMillis) / (float) scrollDurationMillis),
                dampingRatio,
                responseSeconds);
        float ratio = (mainProgress - delayedProgress) / mainProgress;
        return clampUnit(ratio);
    }

    /**
     * Computes a single row's cascade translationY from its own measured layout displacement
     * (currentTop - startTop) and its pre-transition carry translationY.
     *
     * Visual position = currentTop + ty
     *                 = startTop + carry * (1 - S_delayed) + actualDeltaY * (S_delayed / S_main).
     * When a row's actual travel is reduced, reversed, or clamped by secondary vocals, waiting
     * dots, or scroll bounds, its spring amplitude scales identically with its true movement and
     * never injects a reverse-spring kick.
     */
    public static float cascadeRowTranslationY(
            float actualRowLayoutDeltaPx,
            float carryTranslationYPx,
            long elapsedMillis,
            long scrollDurationMillis,
            long delayMillis,
            float dampingRatio,
            float responseSeconds) {
        if (scrollDurationMillis <= 0L) {
            return 0f;
        }
        float safeDelta = Float.isFinite(actualRowLayoutDeltaPx) ? actualRowLayoutDeltaPx : 0f;
        float safeCarry = Float.isFinite(carryTranslationYPx) ? carryTranslationYPx : 0f;
        long safeDelay = Math.max(0L, delayMillis);
        if (elapsedMillis <= 0L) {
            return safeCarry;
        }
        if (elapsedMillis >= scrollDurationMillis + safeDelay) {
            return 0f;
        }
        float delayedProgress = elapsedMillis <= safeDelay
                ? 0f
                : springValue(
                        clampUnit((float) (elapsedMillis - safeDelay) / (float) scrollDurationMillis),
                        dampingRatio,
                        responseSeconds);
        float carryTerm = Math.abs(safeCarry) < 0.01f
                ? 0f
                : safeCarry * (1f - delayedProgress);
        float lagTerm = 0f;
        if (safeDelay > 0L && Math.abs(safeDelta) >= 0.01f) {
            float lagRatio = cascadeLagRatio(
                    elapsedMillis,
                    scrollDurationMillis,
                    safeDelay,
                    dampingRatio,
                    responseSeconds);
            lagTerm = -safeDelta * lagRatio;
        }
        float total = carryTerm + lagTerm;
        return Float.isFinite(total) ? total : 0f;
    }

    private static float intervalInterpolation(long intervalMillis) {
        if (intervalMillis <= 0L) {
            return 1f;
        }
        float seconds = intervalMillis / 1_000f;
        if (seconds < INTERVAL_MIN_SECONDS) {
            return 0f;
        }
        float clamped = Math.min(seconds, INTERVAL_MAX_SECONDS);
        return clampUnit((clamped - INTERVAL_MIN_SECONDS) / INTERVAL_SPAN_SECONDS);
    }

    private static double evaluateUnderdampedSpring(double seconds, double dampingRatio, double responseSeconds) {
        double omegaN = (2d * Math.PI) / responseSeconds;
        double decay = dampingRatio * omegaN;
        double root = Math.sqrt(1d - dampingRatio * dampingRatio);
        double omegaD = omegaN * root;
        double angle = omegaD * seconds;
        double envelope = Math.exp(-decay * seconds);
        return 1d - envelope * (Math.cos(angle) + (dampingRatio / root) * Math.sin(angle));
    }

    private static double cubicBezier(double u, double p1, double p2) {
        double inv = 1d - u;
        return 3d * inv * inv * u * p1 + 3d * inv * u * u * p2 + u * u * u;
    }

    private static double cubicBezierDerivative(double u, double p1, double p2) {
        double inv = 1d - u;
        return 3d * inv * inv * p1 + 6d * inv * u * (p2 - p1) + 3d * u * u * (1d - p2);
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static float clampUnit(float value) {
        return clamp(value, 0f, 1f);
    }
}
