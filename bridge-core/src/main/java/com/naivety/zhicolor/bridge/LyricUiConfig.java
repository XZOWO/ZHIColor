package com.naivety.zhicolor.bridge;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

final class LyricUiConfig {
    static final int SCHEMA_VERSION = 5;
    static final int MOTION_STANDARD = 0;
    static final int MOTION_REDUCED = 1;
    static final int MOTION_OFF = 2;
    static final int WEIGHT_SYSTEM = 0;
    static final int WEIGHT_REGULAR = 1;
    static final int WEIGHT_MEDIUM = 2;
    static final int WEIGHT_BOLD = 3;
    static final int ALIGN_START = 0;
    static final int ALIGN_CENTER = 1;
    static final int ALIGN_END = 2;
    static final int ALIGN_DEFAULT = 3;
    static final int TRANSLATION_CURRENT_ONLY = 0;
    static final int TRANSLATION_ALL_LINES = 1;
    private static final Pattern OPAQUE_RGB = Pattern.compile("#[0-9A-Fa-f]{6}");

    final int schemaVersion;
    final int activeOpacityPercent;
    final int currentUnrevealedOpacityPercent;
    final int activeTranslationOpacityPercent;
    final int activeTranslationProgressOpacityPercent;
    final int inactiveOpacityPercent;
    final boolean inactiveTranslationFollowsMain;
    final int inactiveTranslationOpacityPercent;
    final boolean verticalFadeEnabled;
    final int verticalFadeLengthTenthsDp;
    final boolean inactiveRowFadeEnabled;
    final int inactiveRowFadePercent;
    final boolean blurEnabled;
    final int blurRadiusTenthsPx;
    final boolean scaleEnabled;
    final int inactiveScalePercent;
    final boolean glowEnabled;
    final int glowIntensityPercent;
    final int glowRadiusPercent;
    final String primaryColor;
    final String glowColor;
    final int motionMode;
    final boolean translationMarqueeEnabled;
    final int maxRefreshRateHz;
    final boolean defaultTranslationEnabled;
    final int translationDisplayMode;
    final boolean lineTimedProgressEnabled;
    final boolean translationProgressEnabled;
    final boolean secondaryProgressEnabled;
    final boolean charLiftEnabled;
    final int charLiftStrengthPercent;
    final boolean screenTimeoutEnabled;
    final int screenTimeoutSeconds;
    final int mainFontTenthsSp;
    final int translationFontRatioPercent;
    final int secondaryFontRatioPercent;
    final int fontWeight;
    final int alignment;
    final int lineSpacingTenthsDp;
    final int wrappedLineSpacingTenthsDp;

    private LyricUiConfig(Builder builder) {
        schemaVersion = SCHEMA_VERSION;
        activeOpacityPercent = clamp(builder.activeOpacityPercent, 50, 100);
        currentUnrevealedOpacityPercent = Math.min(
                clamp(builder.currentUnrevealedOpacityPercent, 20, 100),
                activeOpacityPercent);
        activeTranslationProgressOpacityPercent = Math.min(
                clamp(builder.activeTranslationProgressOpacityPercent, 20, 100),
                activeOpacityPercent);
        activeTranslationOpacityPercent = Math.min(
                clamp(builder.activeTranslationOpacityPercent, 20, 100),
                activeTranslationProgressOpacityPercent);
        inactiveOpacityPercent = clamp(builder.inactiveOpacityPercent, 30, 100);
        inactiveTranslationFollowsMain = builder.inactiveTranslationFollowsMain;
        inactiveTranslationOpacityPercent = clamp(
                builder.inactiveTranslationOpacityPercent,
                20,
                100);
        verticalFadeEnabled = builder.verticalFadeEnabled;
        verticalFadeLengthTenthsDp = clamp(builder.verticalFadeLengthTenthsDp, 0, 1200);
        inactiveRowFadeEnabled = builder.inactiveRowFadeEnabled;
        inactiveRowFadePercent = clamp(builder.inactiveRowFadePercent, 50, 100);
        blurEnabled = builder.blurEnabled;
        blurRadiusTenthsPx = roundStep(clamp(builder.blurRadiusTenthsPx, 0, 80), 5);
        scaleEnabled = builder.scaleEnabled;
        inactiveScalePercent = clamp(builder.inactiveScalePercent, 75, 100);
        glowIntensityPercent = LyricUiGlowPolicy.canonicalIntensityPercent(
                builder.glowEnabled,
                builder.glowIntensityPercent);
        glowEnabled = LyricUiGlowPolicy.enabledForIntensity(glowIntensityPercent);
        glowRadiusPercent = clamp(builder.glowRadiusPercent, 10, 24);
        primaryColor = sanitizeColor(builder.primaryColor, "#FFFFFF");
        glowColor = sanitizeColor(builder.glowColor, "#FFD68A");
        motionMode = clamp(builder.motionMode, MOTION_STANDARD, MOTION_OFF);
        translationMarqueeEnabled = builder.translationMarqueeEnabled;
        maxRefreshRateHz = sanitizeRefreshRate(builder.maxRefreshRateHz);
        defaultTranslationEnabled = builder.defaultTranslationEnabled;
        translationDisplayMode = clamp(builder.translationDisplayMode,
                TRANSLATION_CURRENT_ONLY, TRANSLATION_ALL_LINES);
        lineTimedProgressEnabled = builder.lineTimedProgressEnabled;
        translationProgressEnabled = builder.translationProgressEnabled;
        secondaryProgressEnabled = builder.secondaryProgressEnabled;
        charLiftEnabled = builder.charLiftEnabled;
        charLiftStrengthPercent = clamp(builder.charLiftStrengthPercent, 0, 200);
        screenTimeoutEnabled = builder.screenTimeoutEnabled;
        screenTimeoutSeconds = LyricUiSettings.sanitizeScreenTimeoutSeconds(
                builder.screenTimeoutSeconds);
        mainFontTenthsSp = clamp(builder.mainFontTenthsSp, 180, 280);
        translationFontRatioPercent = clamp(builder.translationFontRatioPercent, 55, 75);
        secondaryFontRatioPercent = clamp(builder.secondaryFontRatioPercent, 55, 75);
        fontWeight = clamp(builder.fontWeight, WEIGHT_SYSTEM, WEIGHT_BOLD);
        alignment = builder.alignment >= ALIGN_START && builder.alignment <= ALIGN_DEFAULT
                ? builder.alignment : ALIGN_DEFAULT;
        lineSpacingTenthsDp = roundStep(clamp(builder.lineSpacingTenthsDp, -50, 200), 5);
        wrappedLineSpacingTenthsDp = roundStep(
                clamp(builder.wrappedLineSpacingTenthsDp, -10, 80),
                5);
    }

    static LyricUiConfig defaults() {
        return new Builder().build();
    }

    Builder buildUpon() {
        return new Builder(this);
    }

    LyricUiConfig resetAppearance() {
        LyricUiConfig defaults = defaults();
        return buildUpon()
                .activeOpacityPercent(defaults.activeOpacityPercent)
                .currentUnrevealedOpacityPercent(defaults.currentUnrevealedOpacityPercent)
                .activeTranslationOpacityPercent(defaults.activeTranslationOpacityPercent)
                .activeTranslationProgressOpacityPercent(
                        defaults.activeTranslationProgressOpacityPercent)
                .inactiveOpacityPercent(defaults.inactiveOpacityPercent)
                .inactiveTranslationFollowsMain(defaults.inactiveTranslationFollowsMain)
                .inactiveTranslationOpacityPercent(defaults.inactiveTranslationOpacityPercent)
                .verticalFadeEnabled(defaults.verticalFadeEnabled)
                .verticalFadeLengthTenthsDp(defaults.verticalFadeLengthTenthsDp)
                .inactiveRowFadeEnabled(defaults.inactiveRowFadeEnabled)
                .inactiveRowFadePercent(defaults.inactiveRowFadePercent)
                .blurEnabled(defaults.blurEnabled)
                .blurRadiusTenthsPx(defaults.blurRadiusTenthsPx)
                .scaleEnabled(defaults.scaleEnabled)
                .inactiveScalePercent(defaults.inactiveScalePercent)
                .glowEnabled(defaults.glowEnabled)
                .glowIntensityPercent(defaults.glowIntensityPercent)
                .glowRadiusPercent(defaults.glowRadiusPercent)
                .primaryColor(defaults.primaryColor)
                .glowColor(defaults.glowColor)
                .motionMode(defaults.motionMode)
                .mainFontTenthsSp(defaults.mainFontTenthsSp)
                .translationFontRatioPercent(defaults.translationFontRatioPercent)
                .secondaryFontRatioPercent(defaults.secondaryFontRatioPercent)
                .fontWeight(defaults.fontWeight)
                .alignment(defaults.alignment)
                .lineSpacingTenthsDp(defaults.lineSpacingTenthsDp)
                .wrappedLineSpacingTenthsDp(defaults.wrappedLineSpacingTenthsDp)
                .build();
    }

    static int sanitizeRefreshRate(int value) {
        return value == 60 || value == 90 || value == 120 ? value : 0;
    }

    static String sanitizeColor(String value, String fallback) {
        if (value == null || !OPAQUE_RGB.matcher(value.trim()).matches()) return fallback;
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int roundStep(int value, int step) {
        return Math.round(value / (float) step) * step;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof LyricUiConfig)) return false;
        LyricUiConfig other = (LyricUiConfig) object;
        return activeOpacityPercent == other.activeOpacityPercent
                && currentUnrevealedOpacityPercent == other.currentUnrevealedOpacityPercent
                && activeTranslationOpacityPercent == other.activeTranslationOpacityPercent
                && activeTranslationProgressOpacityPercent
                == other.activeTranslationProgressOpacityPercent
                && inactiveOpacityPercent == other.inactiveOpacityPercent
                && inactiveTranslationFollowsMain == other.inactiveTranslationFollowsMain
                && inactiveTranslationOpacityPercent == other.inactiveTranslationOpacityPercent
                && verticalFadeEnabled == other.verticalFadeEnabled
                && verticalFadeLengthTenthsDp == other.verticalFadeLengthTenthsDp
                && inactiveRowFadeEnabled == other.inactiveRowFadeEnabled
                && inactiveRowFadePercent == other.inactiveRowFadePercent
                && blurEnabled == other.blurEnabled
                && blurRadiusTenthsPx == other.blurRadiusTenthsPx
                && scaleEnabled == other.scaleEnabled
                && inactiveScalePercent == other.inactiveScalePercent
                && glowEnabled == other.glowEnabled
                && glowIntensityPercent == other.glowIntensityPercent
                && glowRadiusPercent == other.glowRadiusPercent
                && motionMode == other.motionMode
                && translationMarqueeEnabled == other.translationMarqueeEnabled
                && maxRefreshRateHz == other.maxRefreshRateHz
                && defaultTranslationEnabled == other.defaultTranslationEnabled
                && translationDisplayMode == other.translationDisplayMode
                && lineTimedProgressEnabled == other.lineTimedProgressEnabled
                && translationProgressEnabled == other.translationProgressEnabled
                && secondaryProgressEnabled == other.secondaryProgressEnabled
                && charLiftEnabled == other.charLiftEnabled
                && charLiftStrengthPercent == other.charLiftStrengthPercent
                && screenTimeoutEnabled == other.screenTimeoutEnabled
                && screenTimeoutSeconds == other.screenTimeoutSeconds
                && mainFontTenthsSp == other.mainFontTenthsSp
                && translationFontRatioPercent == other.translationFontRatioPercent
                && secondaryFontRatioPercent == other.secondaryFontRatioPercent
                && fontWeight == other.fontWeight
                && alignment == other.alignment
                && lineSpacingTenthsDp == other.lineSpacingTenthsDp
                && wrappedLineSpacingTenthsDp == other.wrappedLineSpacingTenthsDp
                && primaryColor.equals(other.primaryColor)
                && glowColor.equals(other.glowColor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                activeOpacityPercent, currentUnrevealedOpacityPercent,
                activeTranslationOpacityPercent, activeTranslationProgressOpacityPercent,
                inactiveOpacityPercent, inactiveTranslationFollowsMain,
                inactiveTranslationOpacityPercent, verticalFadeEnabled,
                verticalFadeLengthTenthsDp, inactiveRowFadeEnabled, inactiveRowFadePercent,
                blurEnabled, blurRadiusTenthsPx,
                scaleEnabled, inactiveScalePercent, glowEnabled, glowIntensityPercent,
                glowRadiusPercent, primaryColor, glowColor, motionMode,
                translationMarqueeEnabled, maxRefreshRateHz,
                defaultTranslationEnabled, translationDisplayMode, lineTimedProgressEnabled,
                translationProgressEnabled, secondaryProgressEnabled,
                charLiftEnabled, charLiftStrengthPercent,
                screenTimeoutEnabled, screenTimeoutSeconds,
                mainFontTenthsSp, translationFontRatioPercent, secondaryFontRatioPercent, fontWeight, alignment,
                lineSpacingTenthsDp, wrappedLineSpacingTenthsDp);
    }

    static final class Builder {
        private int activeOpacityPercent = 100;
        private int currentUnrevealedOpacityPercent = 50;
        private int activeTranslationOpacityPercent = 60;
        private int activeTranslationProgressOpacityPercent = 80;
        private int inactiveOpacityPercent = 36;
        private boolean inactiveTranslationFollowsMain = true;
        private int inactiveTranslationOpacityPercent = 36;
        private boolean verticalFadeEnabled = true;
        private int verticalFadeLengthTenthsDp = 519;
        private boolean inactiveRowFadeEnabled = true;
        private int inactiveRowFadePercent = 90;
        private boolean blurEnabled = true;
        private int blurRadiusTenthsPx = 20;
        private boolean scaleEnabled = true;
        private int inactiveScalePercent = 96;
        private boolean glowEnabled = true;
        private int glowIntensityPercent = 60;
        private int glowRadiusPercent = 14;
        private String primaryColor = "#FFFFFF";
        private String glowColor = "#FFD68A";
        private int motionMode = MOTION_STANDARD;
        private boolean translationMarqueeEnabled = true;
        private int maxRefreshRateHz;
        private boolean defaultTranslationEnabled = false;
        private int translationDisplayMode = TRANSLATION_ALL_LINES;
        private boolean lineTimedProgressEnabled = LyricUiSettings.DEFAULT_LINE_TIMED_PROGRESS_ENABLED;
        private boolean translationProgressEnabled = LyricUiSettings.DEFAULT_TRANSLATION_PROGRESS_ENABLED;
        private boolean secondaryProgressEnabled = LyricUiSettings.DEFAULT_SECONDARY_PROGRESS_ENABLED;
        private boolean charLiftEnabled = LyricUiSettings.DEFAULT_CHAR_LIFT_ENABLED;
        private int charLiftStrengthPercent = LyricUiSettings.DEFAULT_CHAR_LIFT_STRENGTH_PERCENT;
        private boolean screenTimeoutEnabled = true;
        private int screenTimeoutSeconds;
        private int mainFontTenthsSp = 240;
        private int translationFontRatioPercent = 66;
        private int secondaryFontRatioPercent = 66;
        private int fontWeight = WEIGHT_SYSTEM;
        private int alignment = ALIGN_DEFAULT;
        private int lineSpacingTenthsDp;
        private int wrappedLineSpacingTenthsDp;

        Builder() {
        }

        Builder(LyricUiConfig source) {
            activeOpacityPercent = source.activeOpacityPercent;
            currentUnrevealedOpacityPercent = source.currentUnrevealedOpacityPercent;
            activeTranslationOpacityPercent = source.activeTranslationOpacityPercent;
            activeTranslationProgressOpacityPercent =
                    source.activeTranslationProgressOpacityPercent;
            inactiveOpacityPercent = source.inactiveOpacityPercent;
            inactiveTranslationFollowsMain = source.inactiveTranslationFollowsMain;
            inactiveTranslationOpacityPercent = source.inactiveTranslationOpacityPercent;
            verticalFadeEnabled = source.verticalFadeEnabled;
            verticalFadeLengthTenthsDp = source.verticalFadeLengthTenthsDp;
            inactiveRowFadeEnabled = source.inactiveRowFadeEnabled;
            inactiveRowFadePercent = source.inactiveRowFadePercent;
            blurEnabled = source.blurEnabled;
            blurRadiusTenthsPx = source.blurRadiusTenthsPx;
            scaleEnabled = source.scaleEnabled;
            inactiveScalePercent = source.inactiveScalePercent;
            glowEnabled = source.glowEnabled;
            glowIntensityPercent = source.glowIntensityPercent;
            glowRadiusPercent = source.glowRadiusPercent;
            primaryColor = source.primaryColor;
            glowColor = source.glowColor;
            motionMode = source.motionMode;
            translationMarqueeEnabled = source.translationMarqueeEnabled;
            maxRefreshRateHz = source.maxRefreshRateHz;
            defaultTranslationEnabled = source.defaultTranslationEnabled;
            translationDisplayMode = source.translationDisplayMode;
            lineTimedProgressEnabled = source.lineTimedProgressEnabled;
            translationProgressEnabled = source.translationProgressEnabled;
            secondaryProgressEnabled = source.secondaryProgressEnabled;
            charLiftEnabled = source.charLiftEnabled;
            charLiftStrengthPercent = source.charLiftStrengthPercent;
            screenTimeoutEnabled = source.screenTimeoutEnabled;
            screenTimeoutSeconds = source.screenTimeoutSeconds;
            mainFontTenthsSp = source.mainFontTenthsSp;
            translationFontRatioPercent = source.translationFontRatioPercent;
            secondaryFontRatioPercent = source.secondaryFontRatioPercent;
            fontWeight = source.fontWeight;
            alignment = source.alignment;
            lineSpacingTenthsDp = source.lineSpacingTenthsDp;
            wrappedLineSpacingTenthsDp = source.wrappedLineSpacingTenthsDp;
        }

        Builder activeOpacityPercent(int v) { activeOpacityPercent = v; return this; }
        Builder currentUnrevealedOpacityPercent(int v) {
            currentUnrevealedOpacityPercent = v;
            return this;
        }
        Builder activeTranslationOpacityPercent(int v) {
            activeTranslationOpacityPercent = v;
            return this;
        }
        Builder activeTranslationProgressOpacityPercent(int v) {
            activeTranslationProgressOpacityPercent = v;
            return this;
        }
        Builder inactiveOpacityPercent(int v) { inactiveOpacityPercent = v; return this; }
        Builder inactiveTranslationFollowsMain(boolean v) {
            inactiveTranslationFollowsMain = v;
            return this;
        }
        Builder inactiveTranslationOpacityPercent(int v) {
            inactiveTranslationOpacityPercent = v;
            return this;
        }
        Builder verticalFadeEnabled(boolean v) { verticalFadeEnabled = v; return this; }
        Builder verticalFadeLengthTenthsDp(int v) {
            verticalFadeLengthTenthsDp = v;
            return this;
        }
        Builder inactiveRowFadeEnabled(boolean v) {
            inactiveRowFadeEnabled = v;
            return this;
        }
        Builder inactiveRowFadePercent(int v) { inactiveRowFadePercent = v; return this; }
        Builder blurEnabled(boolean v) { blurEnabled = v; return this; }
        Builder blurRadiusTenthsPx(int v) { blurRadiusTenthsPx = v; return this; }
        Builder scaleEnabled(boolean v) { scaleEnabled = v; return this; }
        Builder inactiveScalePercent(int v) { inactiveScalePercent = v; return this; }
        Builder glowEnabled(boolean v) { glowEnabled = v; return this; }
        Builder glowIntensityPercent(int v) { glowIntensityPercent = v; return this; }
        Builder glowRadiusPercent(int v) { glowRadiusPercent = v; return this; }
        Builder primaryColor(String v) { primaryColor = v; return this; }
        Builder glowColor(String v) { glowColor = v; return this; }
        Builder motionMode(int v) { motionMode = v; return this; }
        Builder translationMarqueeEnabled(boolean v) { translationMarqueeEnabled = v; return this; }
        Builder maxRefreshRateHz(int v) { maxRefreshRateHz = v; return this; }
        Builder defaultTranslationEnabled(boolean v) { defaultTranslationEnabled = v; return this; }
        Builder translationDisplayMode(int v) { translationDisplayMode = v; return this; }
        Builder lineTimedProgressEnabled(boolean v) { lineTimedProgressEnabled = v; return this; }
        Builder translationProgressEnabled(boolean v) { translationProgressEnabled = v; return this; }
        Builder secondaryProgressEnabled(boolean v) { secondaryProgressEnabled = v; return this; }
        Builder charLiftEnabled(boolean v) { charLiftEnabled = v; return this; }
        Builder charLiftStrengthPercent(int v) { charLiftStrengthPercent = v; return this; }
        Builder screenTimeoutEnabled(boolean v) { screenTimeoutEnabled = v; return this; }
        Builder screenTimeoutSeconds(int v) { screenTimeoutSeconds = v; return this; }
        Builder mainFontTenthsSp(int v) { mainFontTenthsSp = v; return this; }
        Builder translationFontRatioPercent(int v) { translationFontRatioPercent = v; return this; }
        Builder secondaryFontRatioPercent(int v) { secondaryFontRatioPercent = v; return this; }
        Builder fontWeight(int v) { fontWeight = v; return this; }
        Builder alignment(int v) { alignment = v; return this; }
        Builder lineSpacingTenthsDp(int v) { lineSpacingTenthsDp = v; return this; }
        Builder wrappedLineSpacingTenthsDp(int v) {
            wrappedLineSpacingTenthsDp = v;
            return this;
        }
        LyricUiConfig build() { return new LyricUiConfig(this); }
    }
}
