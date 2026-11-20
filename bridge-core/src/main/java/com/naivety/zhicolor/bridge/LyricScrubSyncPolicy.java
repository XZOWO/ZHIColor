package com.naivety.zhicolor.bridge;

/**
 * Coordinates live progress-bar scrubbing with lockscreen lyric positioning, post-scrub loading /
 * buffering holds, and monotonic clock re-convergence.
 *
 * <p>Pure-Java state machine with zero Android runtime dependencies:
 * <ul>
 *   <li>Maps SeekBar progress to continuous millisecond timestamps while dragging.</li>
 *   <li>Holds the committed scrub position steadily during {@code COMMITTING} (matching Apple
 *       Music's static handoff clock) instead of speculatively running ahead while streaming
 *       audio is still loading or buffering.</li>
 *   <li>Extends the commit hold dynamically while {@code STATE_BUFFERING} / {@code STATE_CONNECTING}
 *       events arrive, without prematurely exiting {@code COMMITTING} or flickering play state.</li>
 *   <li>Applies monotonic post-seek phase-lock upon convergence: holds slightly behind real audio
 *       (e.g. ColorOS whole-second seek floor or AudioTrack priming dips) without rewinding, and
 *       smoothly slews forward if playback resumed slightly ahead of the displayed scrub clock.</li>
 *   <li>Filters late lagging dual-source IPC packets (such as ColorOS Seedling bundles) during a
 *       short post-convergence guard window so stale pre-seek timestamps can never bounce lyrics
 *       backward and forward again.</li>
 *   <li>Suppresses false track-restart handoffs when scrubbing back toward {@code 0:00}.</li>
 * </ul>
 */
public final class LyricScrubSyncPolicy {
    /** Android PlaybackState constants mirrored here to keep this policy Android-free. */
    public static final int STATE_STOPPED = 1;
    public static final int STATE_PAUSED = 2;
    public static final int STATE_PLAYING = 3;
    public static final int STATE_FAST_FORWARDING = 4;
    public static final int STATE_REWINDING = 5;
    public static final int STATE_BUFFERING = 6;
    public static final int STATE_ERROR = 7;
    public static final int STATE_CONNECTING = 8;

    /** Holds the committed scrub target while the player processes seekTo and loads audio. */
    public static final long COMMIT_HOLD_MS = 3_000L;
    /** Dynamic deadline extension applied whenever the player reports buffering / connecting. */
    public static final long BUFFERING_EXTEND_HOLD_MS = 2_500L;
    /** Absolute ceiling on buffering hold from the initial commit so broken streams recover. */
    public static final long MAX_BUFFERING_HOLD_MS = 8_000L;
    /** ColorOS seek actions round to whole seconds (~1000ms) plus keyframe / broadcast latency. */
    public static final long COMMIT_CONVERGENCE_TOLERANCE_MS = 2_200L;
    /** Minimum wait before accepting convergence when a player omits lastPositionUpdateTime. */
    public static final long COMMIT_NO_TIMESTAMP_MIN_AGE_MS = 250L;
    /** Protects against false track-restart blanking right after a scrub commit converges. */
    public static final long POST_COMMIT_RESTART_SUPPRESS_MS = 800L;
    /** Guards against lagging Seedling bundles, AudioTrack priming dips, and brief re-buffering. */
    public static final long POST_CONVERGE_GUARD_MS = 2_500L;
    /** Max hold when confirmed playback starts behind the displayed scrub position (no rewind). */
    public static final long POST_SEEK_BACKWARD_HOLD_MAX_MS = 1_800L;
    /** Minimum post-converge backward dip that activates monotonic backward-hold phase-lock. */
    public static final long POST_SEEK_BACKWARD_HOLD_MIN_MS = 30L;
    /** Max forward delta absorbed with an exponential slew instead of a single-frame jump. */
    public static final long POST_SEEK_FORWARD_SLEW_MAX_MS = 2_500L;
    /** Exponential time constant for smooth forward catch-up after delayed seek confirmation. */
    public static final double POST_SEEK_FORWARD_SLEW_TAU_MS = 120d;
    /** Hard upper bound on forward-slew duration so lyrics always lock 1:1 promptly. */
    public static final long POST_SEEK_FORWARD_SLEW_MAX_DURATION_MS = 550L;
    /** Residual offset below which forward-slew snaps to exact 1:1 lock. */
    public static final long POST_SEEK_FORWARD_SLEW_EPSILON_MS = 12L;
    /** Safety expiration if a SeekBar touch gesture is cancelled without a stop callback. */
    public static final long SCRUB_ORPHAN_TIMEOUT_MS = 12_000L;
    /** Short row deltas glide smoothly; larger jumps snap immediately to follow the finger. */
    public static final int SMOOTH_SCROLL_MAX_DELTA = 3;
    /** Rapid row sweeps below this interval snap immediately so smooth scroll never lags. */
    public static final long RAPID_ROW_CHANGE_MIN_INTERVAL_MS = 90L;

    private static final long TIMESTAMP_FRESHNESS_SLACK_MS = 50L;
    private static final long SMALL_DRAG_PRE_SEEK_MAX_AGE_MS = 600L;
    private static final long SMALL_DRAG_MIN_MOVE_MS = 600L;
    private static final long SMALL_DRAG_PRE_SEEK_MATCH_MS = 280L;
    private static final long PRE_SEEK_STALE_MATCH_TOLERANCE_MS = 1_500L;
    private static final long PRE_SEEK_STALE_MIN_SEPARATION_MS = 2_500L;
    private static final long POST_CONVERGE_STALE_TIMESTAMP_WINDOW_MS = 400L;
    private static final long CONVERGENCE_FORWARD_AGE_CAP_MS = 3_000L;

    public enum Phase {
        IDLE,
        SCRUBBING,
        COMMITTING
    }

    public enum ScrollMode {
        NONE,
        SMOOTH,
        IMMEDIATE
    }

    public enum PlaybackArbitration {
        /** Normal playback processing (scrub controller is idle). */
        PASS_THROUGH,
        /** Pre-seek, buffering, or stale lagging playback position; do not rewind lyrics. */
        IGNORE_STALE_POSITION,
        /** Fresh post-seek playback tick (or post-seek priming correction); accept without restart handoff. */
        ACCEPT_COMMITTED_SEEK
    }

    private Phase phase = Phase.IDLE;
    private long scrubPositionMs = -1L;
    private long lastScrubEventElapsedMs = -1L;
    private long committedPositionMs = -1L;
    private long committedAtElapsedMs = -1L;
    private long firstCommitAtElapsedMs = -1L;
    private long commitDeadlineElapsedMs = -1L;
    private boolean playerBuffering;
    private long preSeekPositionMs = -1L;
    private long preSeekCapturedAtElapsedMs = -1L;
    private boolean preSeekPlaying;
    private float preSeekSpeed = 1f;
    private long lastCommitConvergedAtElapsedMs = -1L;
    private long lastAcceptedCommitAtElapsedMs = -1L;
    private long postConvergeGuardUntilElapsedMs = -1L;
    private long lastDisplayedPositionMs = -1L;
    private long lastDisplayedAtElapsedMs = -1L;
    private boolean lastDisplayedWasPlaying;
    private float lastDisplayedSpeed = 1f;
    private long phaseLockHoldPositionMs = -1L;
    private long phaseLockForwardOffsetMs = 0L;
    private long phaseLockStartedAtElapsedMs = -1L;
    private long lastRowScrollAtElapsedMs = -1L;

    /** Returns true when {@code playbackState} represents active network/decoder loading. */
    public static boolean isBufferingState(int playbackState) {
        return playbackState == STATE_BUFFERING || playbackState == STATE_CONNECTING;
    }

    /** Converts SeekBar progress ({@code 0..max}) into a continuous millisecond position. */
    public static long progressToMillis(int progress, int max, long durationMs) {
        if (max <= 0 || durationMs <= 0L) {
            return -1L;
        }
        int clamped = Math.max(0, Math.min(max, progress));
        return Math.round(durationMs * (clamped / (double) max));
    }

    /** Resolves track duration with exact MediaMetadata ms prioritized over fallbacks. */
    public static long resolveDurationMillis(
            long metadataDurationMs,
            long sectionDurationMs,
            long modelMaxEndMs) {
        if (metadataDurationMs > 0L) {
            return metadataDurationMs;
        }
        if (sectionDurationMs > 0L) {
            return sectionDurationMs;
        }
        return modelMaxEndMs > 0L ? modelMaxEndMs : -1L;
    }

    /** Chooses smooth spring scroll for short deliberate drags vs. immediate jump for sweeps. */
    public static ScrollMode decideScrollMode(
            int currentAdapterIndex,
            int targetAdapterIndex,
            long elapsedSinceLastRowChangeMs,
            boolean targetLaidOut,
            boolean motionEnabled) {
        if (targetAdapterIndex < 0 || targetAdapterIndex == currentAdapterIndex) {
            return ScrollMode.NONE;
        }
        if (!motionEnabled || currentAdapterIndex < 0) {
            return ScrollMode.IMMEDIATE;
        }
        int distance = Math.abs(targetAdapterIndex - currentAdapterIndex);
        if (distance > SMOOTH_SCROLL_MAX_DELTA) {
            return ScrollMode.IMMEDIATE;
        }
        if (!targetLaidOut && distance > 1) {
            return ScrollMode.IMMEDIATE;
        }
        if (elapsedSinceLastRowChangeMs >= 0L
                && elapsedSinceLastRowChangeMs < RAPID_ROW_CHANGE_MIN_INTERVAL_MS) {
            return ScrollMode.IMMEDIATE;
        }
        return ScrollMode.SMOOTH;
    }

    public synchronized void beginScrub(long initialPositionMs, long nowElapsedMs) {
        beginScrub(initialPositionMs, initialPositionMs, false, 1f, nowElapsedMs);
    }

    public synchronized void beginScrub(
            long initialPositionMs,
            long preSeekPlayerPositionMs,
            boolean playing,
            float playbackSpeed,
            long nowElapsedMs) {
        expireIfNeededLocked(nowElapsedMs);
        // Preserve the original pre-seek player timeline when the user starts another scrub
        // while the player is still loading or inside the post-converge guard window.
        if ((phase == Phase.IDLE && !isPostSeekGuardWindowLocked(nowElapsedMs))
                || preSeekPositionMs < 0L) {
            long anchor = preSeekPlayerPositionMs >= 0L
                    ? preSeekPlayerPositionMs
                    : initialPositionMs;
            if (anchor >= 0L) {
                preSeekPositionMs = anchor;
                preSeekCapturedAtElapsedMs = nowElapsedMs;
                preSeekPlaying = playing;
                preSeekSpeed = isFiniteNonZero(playbackSpeed) ? Math.abs(playbackSpeed) : 1f;
            }
        }
        phase = Phase.SCRUBBING;
        if (initialPositionMs >= 0L) {
            scrubPositionMs = initialPositionMs;
            recordDisplayedLocked(initialPositionMs, false, 0f, nowElapsedMs);
        }
        committedPositionMs = -1L;
        committedAtElapsedMs = -1L;
        commitDeadlineElapsedMs = -1L;
        playerBuffering = false;
        postConvergeGuardUntilElapsedMs = -1L;
        clearPhaseLockLocked();
        lastScrubEventElapsedMs = nowElapsedMs;
        lastRowScrollAtElapsedMs = -1L;
    }

    public synchronized long updateScrub(long positionMs, long nowElapsedMs) {
        long clamped = Math.max(0L, positionMs);
        phase = Phase.SCRUBBING;
        scrubPositionMs = clamped;
        lastScrubEventElapsedMs = nowElapsedMs;
        recordDisplayedLocked(clamped, false, 0f, nowElapsedMs);
        return clamped;
    }

    public synchronized long commitScrub(long finalPositionMs, long nowElapsedMs) {
        return commitScrub(finalPositionMs, true, nowElapsedMs);
    }

    public synchronized long commitScrub(
            long finalPositionMs,
            boolean playing,
            long nowElapsedMs) {
        long clamped = Math.max(0L, finalPositionMs);
        phase = Phase.COMMITTING;
        scrubPositionMs = clamped;
        committedPositionMs = clamped;
        committedAtElapsedMs = nowElapsedMs;
        firstCommitAtElapsedMs = nowElapsedMs;
        commitDeadlineElapsedMs = nowElapsedMs + COMMIT_HOLD_MS;
        playerBuffering = false;
        postConvergeGuardUntilElapsedMs = -1L;
        clearPhaseLockLocked();
        lastScrubEventElapsedMs = nowElapsedMs;
        lastRowScrollAtElapsedMs = -1L;
        recordDisplayedLocked(clamped, false, 0f, nowElapsedMs);
        return clamped;
    }

    public synchronized void cancelScrub() {
        phase = Phase.IDLE;
        scrubPositionMs = -1L;
        lastScrubEventElapsedMs = -1L;
        committedPositionMs = -1L;
        committedAtElapsedMs = -1L;
        firstCommitAtElapsedMs = -1L;
        commitDeadlineElapsedMs = -1L;
        playerBuffering = false;
        preSeekPositionMs = -1L;
        preSeekCapturedAtElapsedMs = -1L;
        preSeekPlaying = false;
        preSeekSpeed = 1f;
        lastCommitConvergedAtElapsedMs = -1L;
        lastAcceptedCommitAtElapsedMs = -1L;
        postConvergeGuardUntilElapsedMs = -1L;
        lastDisplayedPositionMs = -1L;
        lastDisplayedAtElapsedMs = -1L;
        lastDisplayedWasPlaying = false;
        lastDisplayedSpeed = 1f;
        clearPhaseLockLocked();
        lastRowScrollAtElapsedMs = -1L;
    }

    public synchronized Phase phase(long nowElapsedMs) {
        expireIfNeededLocked(nowElapsedMs);
        return phase;
    }

    public synchronized boolean isScrubbing(long nowElapsedMs) {
        expireIfNeededLocked(nowElapsedMs);
        return phase == Phase.SCRUBBING;
    }

    public synchronized boolean isScrubOrCommitActive(long nowElapsedMs) {
        expireIfNeededLocked(nowElapsedMs);
        return phase == Phase.SCRUBBING || phase == Phase.COMMITTING;
    }

    public synchronized boolean isBufferingAfterCommit(long nowElapsedMs) {
        expireIfNeededLocked(nowElapsedMs);
        return phase == Phase.COMMITTING && playerBuffering;
    }

    public synchronized boolean isPostSeekGuardActive(long nowElapsedMs) {
        expireIfNeededLocked(nowElapsedMs);
        return phase == Phase.IDLE
                && (isPostSeekGuardWindowLocked(nowElapsedMs) || isPhaseLockingLocked(nowElapsedMs));
    }

    public synchronized boolean isPhaseLocking(long nowElapsedMs) {
        expireIfNeededLocked(nowElapsedMs);
        return phase == Phase.IDLE && isPhaseLockingLocked(nowElapsedMs);
    }

    public synchronized boolean shouldSuppressTrackRestart(long nowElapsedMs) {
        expireIfNeededLocked(nowElapsedMs);
        if (phase != Phase.IDLE) {
            return true;
        }
        return lastCommitConvergedAtElapsedMs >= 0L
                && nowElapsedMs >= lastCommitConvergedAtElapsedMs
                && nowElapsedMs - lastCommitConvergedAtElapsedMs <= POST_COMMIT_RESTART_SUPPRESS_MS;
    }

    /**
     * Tells callers whether an ignored {@code PlaybackState} event may still update cached
     * play/pause flags. Returns {@code false} for buffering states, rejected post-converge stale
     * packets, and pre-commit timestamps so temporary loading or lagging IPC bundles never flicker
     * play state or release wake-locks.
     */
    public synchronized boolean shouldApplyPlaybackFlagsWhenIgnored(
            int playbackState,
            long lastPositionUpdateTimeMs,
            long nowElapsedMs) {
        expireIfNeededLocked(nowElapsedMs);
        if (playbackState < 0 || isBufferingState(playbackState)) {
            return false;
        }
        if (phase == Phase.IDLE) {
            return false;
        }
        if (phase == Phase.COMMITTING
                && lastPositionUpdateTimeMs > 0L
                && committedAtElapsedMs >= 0L
                && lastPositionUpdateTimeMs < committedAtElapsedMs - TIMESTAMP_FRESHNESS_SLACK_MS) {
            return false;
        }
        return true;
    }

    /**
     * Resolves the effective lyric position:
     * <ul>
     *   <li>{@code SCRUBBING}: tracks the finger position directly.</li>
     *   <li>{@code COMMITTING}: holds steadily at the committed seek target without speculative
     *       run-ahead while the player loads/buffers audio.</li>
     *   <li>{@code IDLE}: applies monotonic post-seek phase-lock (backward-hold or smooth
     *       exponential forward-slew) until locked 1:1 with live playback.</li>
     * </ul>
     */
    public synchronized long resolveEffectivePositionMillis(
            long playbackPositionMs,
            boolean playing,
            float playbackSpeed,
            long nowElapsedMs) {
        expireIfNeededLocked(nowElapsedMs);
        if (phase == Phase.SCRUBBING) {
            long resolved = scrubPositionMs >= 0L ? scrubPositionMs : playbackPositionMs;
            if (resolved >= 0L) {
                recordDisplayedLocked(resolved, false, 0f, nowElapsedMs);
            }
            return resolved;
        }
        if (phase == Phase.COMMITTING) {
            long resolved = committedPositionMs >= 0L ? committedPositionMs : playbackPositionMs;
            if (resolved >= 0L) {
                recordDisplayedLocked(resolved, false, 0f, nowElapsedMs);
            }
            return resolved;
        }
        if (phaseLockHoldPositionMs >= 0L) {
            long holdAge = phaseLockStartedAtElapsedMs >= 0L && nowElapsedMs >= phaseLockStartedAtElapsedMs
                    ? nowElapsedMs - phaseLockStartedAtElapsedMs
                    : POST_SEEK_BACKWARD_HOLD_MAX_MS;
            if (!playing
                    || playbackPositionMs < 0L
                    || playbackPositionMs >= phaseLockHoldPositionMs
                    || holdAge >= POST_SEEK_BACKWARD_HOLD_MAX_MS) {
                clearPhaseLockLocked();
            } else {
                recordDisplayedLocked(phaseLockHoldPositionMs, false, 0f, nowElapsedMs);
                return phaseLockHoldPositionMs;
            }
        } else if (phaseLockForwardOffsetMs < 0L) {
            long dt = phaseLockStartedAtElapsedMs >= 0L && nowElapsedMs >= phaseLockStartedAtElapsedMs
                    ? nowElapsedMs - phaseLockStartedAtElapsedMs
                    : POST_SEEK_FORWARD_SLEW_MAX_DURATION_MS;
            if (!playing
                    || playbackPositionMs < 0L
                    || dt >= POST_SEEK_FORWARD_SLEW_MAX_DURATION_MS) {
                clearPhaseLockLocked();
            } else {
                long remainingOffset = Math.round(
                        phaseLockForwardOffsetMs * Math.exp(-dt / POST_SEEK_FORWARD_SLEW_TAU_MS));
                if (Math.abs(remainingOffset) <= POST_SEEK_FORWARD_SLEW_EPSILON_MS) {
                    clearPhaseLockLocked();
                } else {
                    long slewed = Math.max(0L, playbackPositionMs + remainingOffset);
                    if (lastDisplayedPositionMs >= 0L && slewed < lastDisplayedPositionMs) {
                        slewed = lastDisplayedPositionMs;
                    }
                    recordDisplayedLocked(slewed, true, playbackSpeed, nowElapsedMs);
                    return slewed;
                }
            }
        }
        if (playbackPositionMs >= 0L) {
            recordDisplayedLocked(playbackPositionMs, playing, playbackSpeed, nowElapsedMs);
        }
        return playbackPositionMs;
    }

    /**
     * Backward-compatible overload inferring playback state from {@code playing}.
     */
    public synchronized PlaybackArbitration observePlaybackState(
            long computedPositionMs,
            long lastPositionUpdateTimeMs,
            boolean playing,
            float playbackSpeed,
            long nowElapsedMs) {
        return observePlaybackState(
                playing ? STATE_PLAYING : STATE_PAUSED,
                computedPositionMs,
                lastPositionUpdateTimeMs,
                playing,
                playbackSpeed,
                nowElapsedMs);
    }

    /**
     * Arbitrates an incoming {@code PlaybackState} update against active scrubbing, post-release
     * loading/buffering, and the post-convergence guard window.
     */
    public synchronized PlaybackArbitration observePlaybackState(
            int playbackState,
            long computedPositionMs,
            long lastPositionUpdateTimeMs,
            boolean playing,
            float playbackSpeed,
            long nowElapsedMs) {
        expireIfNeededLocked(nowElapsedMs);
        if (phase == Phase.SCRUBBING) {
            return PlaybackArbitration.IGNORE_STALE_POSITION;
        }
        if (phase == Phase.COMMITTING) {
            if (playbackState == STATE_STOPPED || playbackState == STATE_ERROR) {
                clearCommitAndGuardLocked();
                return PlaybackArbitration.PASS_THROUGH;
            }
            if (isBufferingState(playbackState)) {
                playerBuffering = true;
                long cap = firstCommitAtElapsedMs >= 0L
                        ? firstCommitAtElapsedMs + MAX_BUFFERING_HOLD_MS
                        : nowElapsedMs + BUFFERING_EXTEND_HOLD_MS;
                long extended = nowElapsedMs + BUFFERING_EXTEND_HOLD_MS;
                commitDeadlineElapsedMs = Math.min(cap, Math.max(commitDeadlineElapsedMs, extended));
                return PlaybackArbitration.IGNORE_STALE_POSITION;
            }
            if (computedPositionMs < 0L) {
                return PlaybackArbitration.IGNORE_STALE_POSITION;
            }
            long commitAge = Math.max(0L, nowElapsedMs - committedAtElapsedMs);
            boolean freshByTimestamp = lastPositionUpdateTimeMs > 0L
                    && lastPositionUpdateTimeMs >= committedAtElapsedMs - TIMESTAMP_FRESHNESS_SLACK_MS;
            boolean freshWithoutTimestamp = lastPositionUpdateTimeMs <= 0L
                    && commitAge >= COMMIT_NO_TIMESTAMP_MIN_AGE_MS;
            if (!freshByTimestamp && !freshWithoutTimestamp) {
                return PlaybackArbitration.IGNORE_STALE_POSITION;
            }
            if (isPreSeekSmallDragResidualLocked(
                    computedPositionMs, lastPositionUpdateTimeMs, commitAge, nowElapsedMs)) {
                return PlaybackArbitration.IGNORE_STALE_POSITION;
            }
            float clampedSpeed = isFiniteNonZero(playbackSpeed)
                    ? Math.max(0.25f, Math.min(4f, Math.abs(playbackSpeed)))
                    : 1f;
            long forwardAllowance = playing
                    ? (long) (Math.min(commitAge, CONVERGENCE_FORWARD_AGE_CAP_MS) * clampedSpeed)
                    : 0L;
            long lowerBound = committedPositionMs - COMMIT_CONVERGENCE_TOLERANCE_MS;
            long upperBound = committedPositionMs + COMMIT_CONVERGENCE_TOLERANCE_MS + forwardAllowance;
            if (computedPositionMs >= lowerBound && computedPositionMs <= upperBound) {
                long displayedAnchor = committedPositionMs;
                engagePostSeekPhaseLockLocked(
                        displayedAnchor, computedPositionMs, playing, nowElapsedMs);
                phase = Phase.IDLE;
                lastCommitConvergedAtElapsedMs = nowElapsedMs;
                lastAcceptedCommitAtElapsedMs = committedAtElapsedMs;
                postConvergeGuardUntilElapsedMs = nowElapsedMs + POST_CONVERGE_GUARD_MS;
                committedPositionMs = -1L;
                committedAtElapsedMs = -1L;
                commitDeadlineElapsedMs = -1L;
                scrubPositionMs = -1L;
                playerBuffering = false;
                if (!playing) {
                    recordDisplayedLocked(computedPositionMs, false, 0f, nowElapsedMs);
                } else if (phaseLockHoldPositionMs < 0L && phaseLockForwardOffsetMs == 0L) {
                    recordDisplayedLocked(computedPositionMs, true, playbackSpeed, nowElapsedMs);
                }
                return PlaybackArbitration.ACCEPT_COMMITTED_SEEK;
            }
            return PlaybackArbitration.IGNORE_STALE_POSITION;
        }

        // Phase.IDLE
        if (!isPostSeekGuardWindowLocked(nowElapsedMs)) {
            return PlaybackArbitration.PASS_THROUGH;
        }
        if (playbackState == STATE_STOPPED || playbackState == STATE_ERROR) {
            clearPostConvergeGuardLocked();
            return PlaybackArbitration.PASS_THROUGH;
        }
        // Reject lagging packets whose PlaybackState timestamp predates the accepted seek commit.
        if (lastPositionUpdateTimeMs > 0L
                && lastAcceptedCommitAtElapsedMs >= 0L
                && lastPositionUpdateTimeMs < lastAcceptedCommitAtElapsedMs - TIMESTAMP_FRESHNESS_SLACK_MS) {
            return PlaybackArbitration.IGNORE_STALE_POSITION;
        }
        long displayedNow = estimateCurrentDisplayedPositionLocked(nowElapsedMs);
        // Handle brief post-converge re-buffering by holding cleanly at the displayed position.
        if (isBufferingState(playbackState)) {
            long cap = firstCommitAtElapsedMs >= 0L
                    ? firstCommitAtElapsedMs + MAX_BUFFERING_HOLD_MS
                    : nowElapsedMs + BUFFERING_EXTEND_HOLD_MS;
            if (nowElapsedMs < cap && displayedNow >= 0L) {
                phase = Phase.COMMITTING;
                committedPositionMs = displayedNow;
                scrubPositionMs = displayedNow;
                committedAtElapsedMs = nowElapsedMs;
                commitDeadlineElapsedMs = Math.min(cap, nowElapsedMs + BUFFERING_EXTEND_HOLD_MS);
                playerBuffering = true;
                clearPhaseLockLocked();
                recordDisplayedLocked(displayedNow, false, 0f, nowElapsedMs);
                return PlaybackArbitration.IGNORE_STALE_POSITION;
            }
        }
        // Reject lagging dual-source IPC snapshots (e.g. ColorOS Seedling bundles omitting or
        // racing timestamps) that still carry the pre-seek timeline.
        if (computedPositionMs >= 0L
                && (lastPositionUpdateTimeMs <= 0L
                || (lastCommitConvergedAtElapsedMs >= 0L
                && lastPositionUpdateTimeMs <= lastCommitConvergedAtElapsedMs + POST_CONVERGE_STALE_TIMESTAMP_WINDOW_MS))
                && matchesPreSeekStaleSnapshotLocked(computedPositionMs, displayedNow, nowElapsedMs)) {
            return PlaybackArbitration.IGNORE_STALE_POSITION;
        }
        // Absorb AudioTrack-priming backward dips right after seek convergence without rewinding.
        if (playing && computedPositionMs >= 0L && displayedNow >= 0L) {
            long delta = computedPositionMs - displayedNow;
            if (delta <= -POST_SEEK_BACKWARD_HOLD_MIN_MS
                    && delta >= -POST_SEEK_BACKWARD_HOLD_MAX_MS) {
                boolean alreadyHoldingSameAnchor = phaseLockHoldPositionMs >= 0L
                        && phaseLockHoldPositionMs == displayedNow;
                phaseLockForwardOffsetMs = 0L;
                phaseLockHoldPositionMs = displayedNow;
                if (!alreadyHoldingSameAnchor) {
                    phaseLockStartedAtElapsedMs = nowElapsedMs;
                }
                recordDisplayedLocked(displayedNow, false, 0f, nowElapsedMs);
                return PlaybackArbitration.ACCEPT_COMMITTED_SEEK;
            }
        }
        return PlaybackArbitration.PASS_THROUGH;
    }

    /** Records a scrub-driven row change and returns elapsed ms since the prior row change. */
    public synchronized long recordRowScrollAndGetDeltaMs(long nowElapsedMs) {
        long delta = lastRowScrollAtElapsedMs >= 0L && nowElapsedMs >= lastRowScrollAtElapsedMs
                ? nowElapsedMs - lastRowScrollAtElapsedMs
                : -1L;
        lastRowScrollAtElapsedMs = nowElapsedMs;
        return delta;
    }

    private void expireIfNeededLocked(long nowElapsedMs) {
        if (phase == Phase.COMMITTING) {
            long deadline = commitDeadlineElapsedMs >= 0L
                    ? commitDeadlineElapsedMs
                    : (committedAtElapsedMs >= 0L ? committedAtElapsedMs + COMMIT_HOLD_MS : -1L);
            if (committedAtElapsedMs < 0L
                    || nowElapsedMs < committedAtElapsedMs
                    || (deadline >= 0L && nowElapsedMs >= deadline)) {
                phase = Phase.IDLE;
                lastCommitConvergedAtElapsedMs = nowElapsedMs;
                committedPositionMs = -1L;
                committedAtElapsedMs = -1L;
                commitDeadlineElapsedMs = -1L;
                scrubPositionMs = -1L;
                playerBuffering = false;
                postConvergeGuardUntilElapsedMs = -1L;
                clearPhaseLockLocked();
            }
        } else if (phase == Phase.SCRUBBING) {
            if (lastScrubEventElapsedMs >= 0L
                    && nowElapsedMs > lastScrubEventElapsedMs
                    && nowElapsedMs - lastScrubEventElapsedMs >= SCRUB_ORPHAN_TIMEOUT_MS) {
                phase = Phase.IDLE;
                scrubPositionMs = -1L;
                lastScrubEventElapsedMs = -1L;
                lastRowScrollAtElapsedMs = -1L;
                clearPhaseLockLocked();
            }
        } else {
            if (postConvergeGuardUntilElapsedMs >= 0L
                    && (nowElapsedMs < lastCommitConvergedAtElapsedMs
                    || nowElapsedMs > postConvergeGuardUntilElapsedMs)) {
                postConvergeGuardUntilElapsedMs = -1L;
            }
            if (!isPhaseLockingLocked(nowElapsedMs)) {
                clearPhaseLockLocked();
            }
        }
    }

    private void engagePostSeekPhaseLockLocked(
            long displayedAnchorMs,
            long confirmedPositionMs,
            boolean playing,
            long nowElapsedMs) {
        clearPhaseLockLocked();
        if (!playing || displayedAnchorMs < 0L || confirmedPositionMs < 0L) {
            return;
        }
        long delta = confirmedPositionMs - displayedAnchorMs;
        if (delta < 0L && delta >= -POST_SEEK_BACKWARD_HOLD_MAX_MS) {
            phaseLockHoldPositionMs = displayedAnchorMs;
            phaseLockStartedAtElapsedMs = nowElapsedMs;
            recordDisplayedLocked(displayedAnchorMs, false, 0f, nowElapsedMs);
        } else if (delta > 0L && delta <= POST_SEEK_FORWARD_SLEW_MAX_MS) {
            phaseLockForwardOffsetMs = displayedAnchorMs - confirmedPositionMs;
            phaseLockStartedAtElapsedMs = nowElapsedMs;
            recordDisplayedLocked(displayedAnchorMs, true, 1f, nowElapsedMs);
        }
    }

    private boolean isPreSeekSmallDragResidualLocked(
            long computedPositionMs,
            long lastPositionUpdateTimeMs,
            long commitAgeMs,
            long nowElapsedMs) {
        if (commitAgeMs >= SMALL_DRAG_PRE_SEEK_MAX_AGE_MS || preSeekPositionMs < 0L) {
            return false;
        }
        long preSeekNow = extrapolatePreSeekPositionLocked(nowElapsedMs);
        if (Math.abs(committedPositionMs - preSeekNow) <= SMALL_DRAG_MIN_MOVE_MS) {
            return false;
        }
        if (Math.abs(computedPositionMs - preSeekNow) > SMALL_DRAG_PRE_SEEK_MATCH_MS) {
            return false;
        }
        return lastPositionUpdateTimeMs <= 0L
                || lastPositionUpdateTimeMs < committedAtElapsedMs + 40L
                || commitAgeMs < COMMIT_NO_TIMESTAMP_MIN_AGE_MS;
    }

    private boolean matchesPreSeekStaleSnapshotLocked(
            long computedPositionMs,
            long displayedNowMs,
            long nowElapsedMs) {
        if (preSeekPositionMs < 0L || displayedNowMs < 0L) {
            return false;
        }
        if (Math.abs(computedPositionMs - displayedNowMs) <= PRE_SEEK_STALE_MIN_SEPARATION_MS) {
            return false;
        }
        long preSeekNow = extrapolatePreSeekPositionLocked(nowElapsedMs);
        long low = Math.min(preSeekPositionMs, preSeekNow) - PRE_SEEK_STALE_MATCH_TOLERANCE_MS;
        long high = Math.max(preSeekPositionMs, preSeekNow) + PRE_SEEK_STALE_MATCH_TOLERANCE_MS;
        return computedPositionMs >= low && computedPositionMs <= high;
    }

    private long extrapolatePreSeekPositionLocked(long nowElapsedMs) {
        if (preSeekPositionMs < 0L) {
            return -1L;
        }
        if (!preSeekPlaying || preSeekCapturedAtElapsedMs < 0L || nowElapsedMs <= preSeekCapturedAtElapsedMs) {
            return preSeekPositionMs;
        }
        long elapsed = nowElapsedMs - preSeekCapturedAtElapsedMs;
        return Math.max(0L, preSeekPositionMs + (long) (elapsed * preSeekSpeed));
    }

    private long estimateCurrentDisplayedPositionLocked(long nowElapsedMs) {
        if (phaseLockHoldPositionMs >= 0L) {
            return phaseLockHoldPositionMs;
        }
        if (lastDisplayedPositionMs < 0L) {
            return -1L;
        }
        if (lastDisplayedWasPlaying
                && lastDisplayedAtElapsedMs >= 0L
                && nowElapsedMs > lastDisplayedAtElapsedMs
                && isFiniteNonZero(lastDisplayedSpeed)) {
            return Math.max(
                    0L,
                    lastDisplayedPositionMs + (long) ((nowElapsedMs - lastDisplayedAtElapsedMs) * lastDisplayedSpeed));
        }
        return lastDisplayedPositionMs;
    }

    private void recordDisplayedLocked(
            long positionMs,
            boolean playing,
            float speed,
            long nowElapsedMs) {
        lastDisplayedPositionMs = positionMs;
        lastDisplayedAtElapsedMs = nowElapsedMs;
        lastDisplayedWasPlaying = playing;
        lastDisplayedSpeed = isFiniteNonZero(speed) ? Math.abs(speed) : 1f;
    }

    private boolean isPostSeekGuardWindowLocked(long nowElapsedMs) {
        return postConvergeGuardUntilElapsedMs >= 0L
                && lastCommitConvergedAtElapsedMs >= 0L
                && nowElapsedMs >= lastCommitConvergedAtElapsedMs
                && nowElapsedMs <= postConvergeGuardUntilElapsedMs;
    }

    private boolean isPhaseLockingLocked(long nowElapsedMs) {
        if (phaseLockHoldPositionMs >= 0L) {
            return phaseLockStartedAtElapsedMs >= 0L
                    && nowElapsedMs >= phaseLockStartedAtElapsedMs
                    && nowElapsedMs - phaseLockStartedAtElapsedMs < POST_SEEK_BACKWARD_HOLD_MAX_MS;
        }
        if (phaseLockForwardOffsetMs < 0L) {
            return phaseLockStartedAtElapsedMs >= 0L
                    && nowElapsedMs >= phaseLockStartedAtElapsedMs
                    && nowElapsedMs - phaseLockStartedAtElapsedMs < POST_SEEK_FORWARD_SLEW_MAX_DURATION_MS;
        }
        return false;
    }

    private void clearPhaseLockLocked() {
        phaseLockHoldPositionMs = -1L;
        phaseLockForwardOffsetMs = 0L;
        phaseLockStartedAtElapsedMs = -1L;
    }

    private void clearPostConvergeGuardLocked() {
        postConvergeGuardUntilElapsedMs = -1L;
        clearPhaseLockLocked();
    }

    private void clearCommitAndGuardLocked() {
        phase = Phase.IDLE;
        committedPositionMs = -1L;
        committedAtElapsedMs = -1L;
        commitDeadlineElapsedMs = -1L;
        scrubPositionMs = -1L;
        playerBuffering = false;
        clearPostConvergeGuardLocked();
    }

    private static boolean isFiniteNonZero(float speed) {
        return speed != 0f && !Float.isNaN(speed) && !Float.isInfinite(speed);
    }
}
