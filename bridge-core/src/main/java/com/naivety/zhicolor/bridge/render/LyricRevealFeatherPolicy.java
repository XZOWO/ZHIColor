package com.naivety.zhicolor.bridge.render;

/** Continuous reveal-feather and overhang clip bounds along a lyric segment. */
public final class LyricRevealFeatherPolicy {
    private LyricRevealFeatherPolicy() {
    }

    public static final class Bounds {
        public boolean visible;
        public boolean solid;
        public float visibleWidth;
        public float clipLeft;
        public float clipRight;
        public float featherStart;
        public float featherEnd;
    }

    public static void resolve(
            float segmentLeft,
            float segmentWidth,
            float revealWidth,
            float backFeather,
            float frontFeather,
            float leftOverhang,
            float rightOverhang,
            Bounds out) {
        if (out == null) return;
        if (!Float.isFinite(segmentLeft) || !Float.isFinite(segmentWidth) || segmentWidth <= 0f
                || !Float.isFinite(revealWidth) || revealWidth <= 0f) {
            out.visible = false;
            out.solid = false;
            out.visibleWidth = 0f;
            out.clipLeft = Float.isFinite(segmentLeft) ? segmentLeft : 0f;
            out.clipRight = out.clipLeft;
            out.featherStart = out.clipLeft;
            out.featherEnd = out.clipLeft;
            return;
        }
        float visibleWidth = Math.max(0f, Math.min(segmentWidth, revealWidth));
        float safeLeftPad = Float.isFinite(leftOverhang) ? Math.max(0f, leftOverhang) : 0f;
        float safeRightPad = Float.isFinite(rightOverhang) ? Math.max(0f, rightOverhang) : 0f;
        float safeBack = Float.isFinite(backFeather) ? Math.max(0.5f, backFeather) : 0.5f;
        float safeFront = Float.isFinite(frontFeather) ? Math.max(0.5f, frontFeather) : 0.5f;
        float segmentRight = segmentLeft + segmentWidth;
        out.visible = true;
        out.visibleWidth = visibleWidth;
        out.clipLeft = segmentLeft - safeLeftPad;
        out.clipRight = segmentRight + safeRightPad;
        if (visibleWidth >= segmentWidth) {
            out.solid = true;
            out.featherStart = out.clipRight;
            out.featherEnd = out.clipRight + safeFront;
            return;
        }
        out.solid = false;
        float revealRight = segmentLeft + visibleWidth;
        float remaining = Math.max(0f, segmentRight - revealRight);
        float endRamp = safeBack + safeFront;
        float u = Math.min(1f, remaining / endRamp);
        // Taper the trailing full-alpha point onto clipRight over the final endRamp span so
        // the end of a segment or wrapped line never stalls against segmentRight and snaps solid.
        float featherStart = revealRight - safeBack * u + safeRightPad * (1f - u);
        float featherEnd = Math.max(featherStart + 1f, revealRight + safeFront);
        out.featherStart = featherStart;
        out.featherEnd = featherEnd;
    }
}
