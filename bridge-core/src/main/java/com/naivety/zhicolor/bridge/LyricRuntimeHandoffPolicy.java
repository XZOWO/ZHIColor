package com.naivety.zhicolor.bridge;

/** Bounded render ownership while a previously connected player's next lyric is pending. */
final class LyricRuntimeHandoffPolicy {
    static final long MAX_WAIT_MILLIS = 6_000L;

    private String packageName = "";
    private String trackKey = "";
    private boolean observedPayload;
    // Keep an expired deadline until a model commits or the track changes. Polling a missing
    // source must never start another wait and hide a genuinely lyricless song forever.
    private long waitingUntil;

    synchronized boolean active(String pkg, boolean allowed, boolean hasPayload, long now) {
        selectPackage(pkg);
        if (!allowed) {
            resetState();
            return false;
        }
        observedPayload |= hasPayload;
        if (hasPayload) return true;
        if (!observedPayload) return false;
        if (waitingUntil == 0L) {
            trackKey = "";
            waitingUntil = deadline(now);
        }
        return now < waitingUntil;
    }

    synchronized long begin(String pkg, boolean allowed, boolean hasPayload,
                            String nextTrackKey, long now) {
        selectPackage(pkg);
        if (!allowed) {
            resetState();
            return 0L;
        }
        observedPayload |= hasPayload;
        if (!observedPayload) return 0L;
        String next = nextTrackKey == null ? "" : nextTrackKey;
        if (waitingUntil != 0L && (next.isEmpty() || next.equals(trackKey))) {
            return waitingUntil;
        }
        trackKey = next;
        waitingUntil = deadline(now);
        return waitingUntil;
    }

    synchronized long pendingUntil(String pkg, long now) {
        return packageName.equals(normalize(pkg)) && now < waitingUntil ? waitingUntil : 0L;
    }

    synchronized boolean matchesDeadline(String pkg, long candidate) {
        return packageName.equals(normalize(pkg)) && waitingUntil == candidate;
    }

    synchronized void complete(String pkg) {
        if (!packageName.equals(normalize(pkg))) return;
        waitingUntil = 0L;
        trackKey = "";
    }

    synchronized void reset() {
        packageName = "";
        resetState();
    }

    private void selectPackage(String pkg) {
        String next = normalize(pkg);
        if (packageName.equals(next)) return;
        packageName = next;
        resetState();
    }

    private void resetState() {
        observedPayload = false;
        waitingUntil = 0L;
        trackKey = "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    private static long deadline(long now) {
        return now > Long.MAX_VALUE - MAX_WAIT_MILLIS ? Long.MAX_VALUE : now + MAX_WAIT_MILLIS;
    }
}
