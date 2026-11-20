package com.naivety.zhicolor.bridge;

/** The translation toggle never owns the backing-vocal channel. */
import com.naivety.zhicolor.bridge.render.LyricTimingMode;
import com.naivety.zhicolor.bridge.render.WordLine;

import java.util.Locale;

final class LyricSecondaryLayoutPolicy {
    private LyricSecondaryLayoutPolicy() {}

    static float translationAmount(boolean allLines, float enabledAmount, float focusAmount) {
        return unit(enabledAmount) * (allLines ? 1f : unit(focusAmount));
    }

    static float groupHeight(float main, float translationContribution,
            float translationAmount, float secondaryContribution, float secondaryAmount) {
        return Math.max(0f, main)
                + Math.max(0f, translationContribution) * unit(translationAmount)
                + Math.max(0f, secondaryContribution) * unit(secondaryAmount);
    }

    static int alignment(int configured, int sourceAlignment) {
        return configured == LyricUiConfig.ALIGN_DEFAULT
                ? Math.max(0, Math.min(2, sourceAlignment)) : configured;
    }

    static boolean isSecondaryAboveMain(WordLine main) {
        if (main == null || main.secondaryLine == null) {
            return false;
        }
        Boolean explicit = parseBackgroundPositionAbove(main.backgroundPosition);
        if (explicit == null) {
            explicit = parseBackgroundPositionAbove(main.secondaryLine.backgroundPosition);
        }
        if (explicit != null) {
            return explicit;
        }
        long secondaryStart = main.secondaryLine.firstProgressStartMillis();
        if (main.timingMode == LyricTimingMode.WORD_TIMED) {
            if (main.words == null || main.words.isEmpty()) {
                return false;
            }
            return secondaryStart < main.words.get(0).timeMillis;
        }
        return secondaryStart < main.timeMillis;
    }

    private static Boolean parseBackgroundPositionAbove(String rawPosition) {
        if (rawPosition == null) {
            return null;
        }
        String position = rawPosition.trim().toLowerCase(Locale.ROOT);
        if ("top".equals(position) || "above".equals(position) || "before".equals(position)) {
            return Boolean.TRUE;
        }
        if ("bottom".equals(position) || "below".equals(position) || "after".equals(position)) {
            return Boolean.FALSE;
        }
        return null;
    }

    static boolean hasSecondaryLayoutMismatch(
            boolean initialized,
            float currentTarget,
            float expectedTarget,
            boolean layoutRunning,
            boolean pumpStalled) {
        return !initialized
                || Math.abs(currentTarget - expectedTarget) > 0.01f
                || (layoutRunning && pumpStalled);
    }

    static long secondaryThresholdMillis(WordLine vocal) {
        if (vocal == null) {
            return Long.MAX_VALUE;
        }
        return vocal.firstProgressStartMillis()
                - (vocal.timingMode == LyricTimingMode.WORD_TIMED ? 300L : 0L);
    }

    static boolean isVocalReady(WordLine main, long position, boolean appleStyle) {
        WordLine vocal = main == null ? null : main.secondaryLine;
        if (vocal == null) {
            return false;
        }
        return appleStyle || position >= secondaryThresholdMillis(vocal);
    }

    static boolean isVocalVisible(WordLine main, boolean singing, long position, boolean appleStyle) {
        return singing && isVocalReady(main, position, appleStyle);
    }

    static float secondaryScale(boolean appleStyle, boolean reducedMotion, float appearance) {
        if (reducedMotion) {
            return 1f;
        }
        float progress = unit(appearance);
        return appleStyle ? 0.9f + 0.1f * progress : 0.5f + 0.5f * progress;
    }

    static float mainTopWithSecondaryAbove(
            float baseTop,
            boolean secondaryAboveMain,
            float secondaryAboveOffset) {
        return baseTop + (secondaryAboveMain ? Math.max(0f, secondaryAboveOffset) : 0f);
    }

    static float effectiveAboveMainOffset(
            float rawAboveOffset,
            float availableHeight,
            float nonSecondaryRequiredHeight) {
        float safeRaw = Math.max(0f, rawAboveOffset);
        if (safeRaw <= 0f || availableHeight <= 0f) {
            return safeRaw;
        }
        float maxSafe = Math.max(0f, availableHeight - Math.max(0f, nonSecondaryRequiredHeight));
        return Math.min(safeRaw, maxSafe);
    }

    static int idleMainTopCorrectionPx(
            boolean applePosition,
            boolean secondaryAboveMain,
            float beforeMainTop,
            float afterMainTop) {
        if (applePosition || !secondaryAboveMain) {
            return 0;
        }
        if (!Float.isFinite(beforeMainTop) || !Float.isFinite(afterMainTop)) {
            return 0;
        }
        return Math.round(afterMainTop - beforeMainTop);
    }

    private static float unit(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
