package com.naivety.zhicolor.bridge;

/** Main glyphs own the focus; translations and backing vocals extend below that anchor. */
final class LyricMainAnchorPolicy {
    private LyricMainAnchorPolicy() {}

    static float mainTop(float mainHeight, float collapsedSlotHeight) {
        return Math.max(0f, (collapsedSlotHeight - Math.max(0f, mainHeight)) * 0.5f);
    }

    static int slotHeight(int collapsedSlotHeight, float mainHeight, float groupHeight) {
        return Math.max(0, collapsedSlotHeight)
                + (int) Math.ceil(Math.max(0f, groupHeight - Math.max(0f, mainHeight)));
    }

    static float focusCenter(float mainTop, float mainHeight, float introHeight, float introAmount) {
        float amount = Math.max(0f, Math.min(1f, introAmount));
        float bodyCenter = Math.max(0f, introHeight) + mainTop + Math.max(0f, mainHeight) * 0.5f;
        return bodyCenter + (Math.max(0f, introHeight) * 0.5f - bodyCenter) * amount;
    }

    static float focusHeight(float mainHeight, float introHeight, float introAmount) {
        float amount = Math.max(0f, Math.min(1f, introAmount));
        return Math.max(1f, mainHeight + (Math.max(0f, introHeight) - mainHeight) * amount);
    }
}
