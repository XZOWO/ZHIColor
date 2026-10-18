package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.ZhiToolLyricMotion;

/** Shared visual tail for translations and backing vocals; each lane keeps its own word clock. */
final class LyricAuxiliaryVisualPolicy {
    private LyricAuxiliaryVisualPolicy() {}

    static float highlightAmount(WordLine parent, boolean visuallyActive, float focusAmount) {
        if (parent != null && parent.highlightExit.isExiting()) {
            return parent.highlightExit.highlightAmount();
        }
        float focus = Math.max(0f, Math.min(1f, focusAmount));
        return !visuallyActive ? 0f : parent == null ? focus
                : Math.min(parent.highlightExit.highlightAmount(), focus);
    }

    static long renderPosition(WordLine parent, long playbackPosition) {
        return parent != null && parent.highlightExit.isExiting()
                ? parent.highlightExit.renderPosition() : playbackPosition;
    }

    static int baseColor(int inactive, int active, float amount) {
        return LyricUiColors.blend(inactive, active, amount);
    }

    /** Called once when the parent enters its tail; incoming and still-singing lanes retain ownership. */
    static void synchronizeExit(WordLine parent, long now) {
        if (parent == null || !parent.highlightExit.isExiting()) return;
        long duration = parent.highlightExit.exitDurationMillis();
        if (!parent.secondaryFocus) {
            synchronizeExit(parent.secondaryLayout, now, duration);
            synchronizeExit(parent.visualSecondary, now, duration);
        }
        if (!parent.vocalVisible) {
            synchronizeExit(parent.vocalLayout, now, duration);
            synchronizeExit(parent.vocalAppearance, now, duration);
        }
    }

    private static void synchronizeExit(ZhiToolLyricMotion.Value channel, long now, long duration) {
        float current = channel.value(now);
        channel.reset(current);
        channel.retarget(0f, now, duration, true);
    }
}
