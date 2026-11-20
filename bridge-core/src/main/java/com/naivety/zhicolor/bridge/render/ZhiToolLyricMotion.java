package com.naivety.zhicolor.bridge.render;

/**
 * Android-free motion shared with the lock-screen renderer. Curves and timings
 * follow ZHITool's FullLyricView: elapsed-time cubic easing, continuous
 * retargeting, and a gradual loss of opacity and sharpness away from the focus.
 */
public final class ZhiToolLyricMotion {

    public static final long SCROLL_MS = 620L;
    public static final long FOCUS_MS = 300L;
    public static final long SECONDARY_MS = 380L;
    public static final long SECONDARY_DELAY_MS = 248L;
    public static final long SONG_SWITCH_MS = 500L;

    private ZhiToolLyricMotion() {
    }

    public static float easeOutCubic(float progress) {
        float remaining = 1f - clampUnit(progress);
        return 1f - remaining * remaining * remaining;
    }

    public static float distanceFade(float distanceSlots) {
        float distance = clampUnit(Math.abs(distanceSlots) / 3.6f);
        return (float) Math.pow(1f - distance, 1.45f);
    }

    public static float distanceBlur(float distanceSlots, float maxRadius) {
        float distance = clampUnit(Math.abs(distanceSlots) / 3.6f);
        return maxRadius * (float) Math.pow(distance, 1.6f);
    }

    private static float clampUnit(float value) {
        return Math.max(0f, Math.min(value, 1f));
    }

    /**
     * One interruptible channel. Callers supply one monotonic wall clock for
     * every channel, so skipped frames never stretch a transition's duration.
     */
    public static final class Value {
        private static final double CRITICALLY_DAMPED_EPSILON = 1e-6;
        private static final double SPRING_SETTLE_POSITION = 5e-4;
        private static final double SPRING_SETTLE_VELOCITY = 2e-3;
        private static final long MAX_SPRING_DURATION_MS = 5000L;

        private boolean initialized;
        private boolean running;
        private boolean useAppleSpring;
        private boolean useAppleFocus;
        private boolean usePhysicsSpring;
        private float from;
        private float target;
        private double springVelocity;
        private double springStiffness;
        private double springDamping;
        private long startedAt;
        private long duration;
        private long lastSampleAt = Long.MIN_VALUE;

        public float target() {
            return target;
        }

        public float value(long now) {
            long sampleAt = Math.max(now, lastSampleAt);
            lastSampleAt = sampleAt;
            if (!running) {
                return target;
            }
            if (usePhysicsSpring) {
                return samplePhysicsSpring(sampleAt);
            }
            long elapsed = sampleAt - startedAt;
            if (elapsed >= duration) {
                running = false;
                from = target;
                return target;
            }
            float progress = elapsed <= 0L ? 0f : (float) elapsed / duration;
            float eased = useAppleSpring
                    ? AppleLyricMotionPolicy.springValue(progress)
                    : (useAppleFocus
                    ? AppleLyricMotionPolicy.focusInterpolation(progress)
                    : easeOutCubic(progress));
            return from + (target - from) * eased;
        }

        public void retarget(float target, long now, long duration, boolean animate) {
            retarget(target, now, duration, animate, false);
        }

        public void retarget(
                float target,
                long now,
                long duration,
                boolean animate,
                boolean useAppleSpring) {
            retargetCurve(target, now, duration, animate, useAppleSpring, false);
        }

        public void retargetFocus(
                float target,
                long now,
                long duration,
                boolean animate,
                boolean appleMotion) {
            retargetCurve(target, now, duration, animate, false, appleMotion);
        }

        private void retargetCurve(
                float target,
                long now,
                long duration,
                boolean animate,
                boolean useAppleSpring,
                boolean useAppleFocus) {
            if (!initialized || !animate || duration <= 0L) {
                reset(target);
                lastSampleAt = now;
                return;
            }
            float current = value(now);
            if (this.target == target && (!running || (!usePhysicsSpring
                    && this.useAppleSpring == useAppleSpring
                    && this.useAppleFocus == useAppleFocus))) {
                return;
            }
            from = current;
            this.target = target;
            this.useAppleSpring = useAppleSpring;
            this.useAppleFocus = useAppleFocus;
            this.usePhysicsSpring = false;
            this.springVelocity = 0d;
            this.springStiffness = 0d;
            this.springDamping = 0d;
            startedAt = lastSampleAt;
            this.duration = duration;
            running = current != target;
        }

        public void retargetSpring(
                float target,
                long now,
                double stiffness,
                double damping,
                boolean animate) {
            if (!initialized || !animate || stiffness <= 0d || damping < 0d) {
                reset(target);
                lastSampleAt = now;
                return;
            }
            long sampleAt = lastSampleAt == Long.MIN_VALUE ? now : Math.max(now, lastSampleAt);
            float current = value(sampleAt);
            double currentVelocity = currentVelocity(sampleAt);
            if (this.target == target
                    && (!running || (usePhysicsSpring
                    && springStiffness == stiffness
                    && springDamping == damping))) {
                return;
            }
            from = current;
            this.target = target;
            this.useAppleSpring = false;
            this.useAppleFocus = false;
            this.usePhysicsSpring = true;
            this.springVelocity = currentVelocity;
            this.springStiffness = stiffness;
            this.springDamping = damping;
            startedAt = lastSampleAt;
            duration = 0L;
            running = Math.abs(current - target) > SPRING_SETTLE_POSITION
                    || Math.abs(currentVelocity) > SPRING_SETTLE_VELOCITY;
            if (!running) {
                from = target;
                springVelocity = 0d;
            }
        }

        public boolean isRunning(long now) {
            value(now);
            return running;
        }

        public void reset(float value) {
            initialized = true;
            running = false;
            useAppleSpring = false;
            useAppleFocus = false;
            usePhysicsSpring = false;
            from = value;
            target = value;
            springVelocity = 0d;
            springStiffness = 0d;
            springDamping = 0d;
            startedAt = 0L;
            duration = 0L;
            lastSampleAt = Long.MIN_VALUE;
        }

        private float samplePhysicsSpring(long sampleAt) {
            long elapsedMs = sampleAt - startedAt;
            if (elapsedMs <= 0L) {
                return from;
            }
            if (elapsedMs >= MAX_SPRING_DURATION_MS) {
                return settleSpring();
            }
            double t = elapsedMs / 1000.0;
            double[] state = evaluateSpring(
                    from - target,
                    springVelocity,
                    springStiffness,
                    springDamping,
                    t);
            double displacement = state[0];
            double velocity = state[1];
            if (Math.abs(displacement) <= SPRING_SETTLE_POSITION
                    && Math.abs(velocity) <= SPRING_SETTLE_VELOCITY) {
                return settleSpring();
            }
            return (float) (target + displacement);
        }

        private float settleSpring() {
            running = false;
            from = target;
            springVelocity = 0d;
            return target;
        }

        private double currentVelocity(long sampleAt) {
            if (!running) {
                return 0d;
            }
            long elapsedMs = Math.max(0L, sampleAt - startedAt);
            if (usePhysicsSpring) {
                return evaluateSpring(
                        from - target,
                        springVelocity,
                        springStiffness,
                        springDamping,
                        elapsedMs / 1000.0)[1];
            }
            if (duration <= 0L || elapsedMs >= duration) {
                return 0d;
            }
            if (useAppleSpring) {
                float p0 = (float) elapsedMs / duration;
                float p1 = Math.min(1f, (elapsedMs + 1L) / (float) duration);
                float dtSec = Math.max(1L, Math.round((p1 - p0) * duration)) / 1000f;
                if (dtSec <= 0f) {
                    return 0d;
                }
                float v0 = AppleLyricMotionPolicy.springValue(p0);
                float v1 = AppleLyricMotionPolicy.springValue(p1);
                return (target - from) * (v1 - v0) / dtSec;
            }
            double remaining = 1.0 - (double) elapsedMs / duration;
            return (target - from) * 3.0 * remaining * remaining / (duration / 1000.0);
        }

        private static double[] evaluateSpring(
                double x0,
                double v0,
                double stiffness,
                double damping,
                double t) {
            double alpha = damping * 0.5;
            double discriminant = alpha * alpha - stiffness;
            if (Math.abs(discriminant) <= CRITICALLY_DAMPED_EPSILON * stiffness) {
                double omega = Math.sqrt(stiffness);
                double decay = Math.exp(-omega * t);
                double coeff = v0 + omega * x0;
                double displacement = decay * (x0 + coeff * t);
                double velocity = decay * (v0 - omega * coeff * t);
                return new double[] { displacement, velocity };
            }
            if (discriminant < 0d) {
                double omegaD = Math.sqrt(-discriminant);
                double b = (v0 + alpha * x0) / omegaD;
                double decay = Math.exp(-alpha * t);
                double cos = Math.cos(omegaD * t);
                double sin = Math.sin(omegaD * t);
                double displacement = decay * (x0 * cos + b * sin);
                double velocity = decay * (v0 * cos - ((alpha * v0 + stiffness * x0) / omegaD) * sin);
                return new double[] { displacement, velocity };
            }
            double gamma = Math.sqrt(discriminant);
            double r1 = -alpha + gamma;
            double r2 = -alpha - gamma;
            double twoGamma = 2.0 * gamma;
            double a = (v0 - r2 * x0) / twoGamma;
            double b = x0 - a;
            double exp1 = Math.exp(r1 * t);
            double exp2 = Math.exp(r2 * t);
            double displacement = a * exp1 + b * exp2;
            double velocity = a * r1 * exp1 + b * r2 * exp2;
            return new double[] { displacement, velocity };
        }
    }
}
