package com.naivety.zhicolor.bridge.render;

/**
 * Pixel-based distance effects for a full-height lyric viewport.
 *
 * <p>All coordinates use the RecyclerView's local coordinate space. The caller supplies the
 * intersection of its actually visible rectangle and its padding clip, and the row's bounds in
 * that same space. Row bounds may include wrapping, translation and the current native scale.
 * No lyric index or fixed number of rows participates in the fade.</p>
 */
public final class LyricViewportMotionPolicy {
    /** A partially visible row retains pixels; the viewport clip removes it at the boundary. */
    private static final float EDGE_OPACITY_FRACTION = 0.18f;

    private LyricViewportMotionPolicy() {}

    /** Reusable output, so sampling several visible rows each frame does not allocate. */
    public static final class State {
        private boolean geometryAvailable;
        private boolean visible = true;
        private float distanceProgress;

        public boolean hasGeometry() {
            return geometryAvailable;
        }

        public boolean isVisible() {
            return visible;
        }

        public float distanceProgress() {
            return distanceProgress;
        }

        public float opacity(boolean focused, boolean fadeEnabled, float inactiveOpacity) {
            if (!visible) return 0f;
            if (focused || !fadeEnabled) return 1f;
            float base = Float.isFinite(inactiveOpacity) ? clampUnit(inactiveOpacity) : 1f;
            return base * (1f - (1f - EDGE_OPACITY_FRACTION) * easedDistance());
        }

        public float blurRadius(boolean focused, boolean blurEnabled, float maximumRadius) {
            if (!visible || focused || !blurEnabled || !Float.isFinite(maximumRadius)) return 0f;
            return Math.max(0f, maximumRadius) * easedDistance();
        }

        private float easedDistance() {
            return (float) Math.pow(distanceProgress, 1.6f);
        }
    }

    /**
     * Samples one row against the top or bottom boundary on its side of the focus.
     *
     * <p>The focus and its nearest neighbouring row keep the configured inactive opacity and
     * zero distance blur. Further rows gradually fade and blur across the space available on
     * that side. A tall row remains visible until its last pixel leaves the viewport. Missing
     * pre-layout geometry keeps the ordinary opacity and avoids inventing a hidden row.</p>
     */
    public static void resolve(
            float rowTop,
            float rowBottom,
            float focusCenterY,
            float focusRowHeight,
            float viewportTop,
            float viewportBottom,
            State out) {
        resolveAnchored(rowTop, rowBottom, rowTop, rowBottom,
                focusCenterY, focusRowHeight, viewportTop, viewportBottom, out);
    }

    /**
     * Anchors distance effects on the main lyric while keeping all visible vocal/translation
     * pixels alive. Expanding secondary content never moves the main lyric's distance anchor.
     */
    public static void resolveAnchored(
            float rowMainTop,
            float rowMainBottom,
            float visibleContentTop,
            float visibleContentBottom,
            float focusCenterY,
            float focusMainHeight,
            float viewportTop,
            float viewportBottom,
            State out) {
        if (out == null) throw new IllegalArgumentException("out");
        out.geometryAvailable = false;
        out.visible = true;
        out.distanceProgress = 0f;
        if (!Float.isFinite(rowMainTop) || !Float.isFinite(rowMainBottom)
                || !Float.isFinite(visibleContentTop) || !Float.isFinite(visibleContentBottom)
                || !Float.isFinite(focusCenterY)
                || !Float.isFinite(viewportTop) || !Float.isFinite(viewportBottom)
                || rowMainBottom <= rowMainTop || visibleContentBottom <= visibleContentTop
                || viewportBottom <= viewportTop) {
            return;
        }
        out.geometryAvailable = true;
        if (visibleContentBottom <= viewportTop || visibleContentTop >= viewportBottom) {
            out.visible = false;
            out.distanceProgress = 1f;
            return;
        }

        float rowHeight = rowMainBottom - rowMainTop;
        float rowCenterY = rowMainTop + rowHeight * 0.5f;
        float anchor = Math.max(viewportTop, Math.min(viewportBottom, focusCenterY));
        float focusHeight = Float.isFinite(focusMainHeight) && focusMainHeight > 0f
                ? focusMainHeight : rowHeight;
        float nearestRowDistance = (rowHeight + focusHeight) * 0.5f;
        float distance = Math.abs(rowCenterY - anchor);
        // Include half of this row: a centre beyond the edge can still have visible text.
        float availableDistance = (rowCenterY < anchor
                ? anchor - viewportTop : viewportBottom - anchor) + rowHeight * 0.5f;
        float fadingDistance = availableDistance - nearestRowDistance;
        if (fadingDistance > 0f && distance > nearestRowDistance) {
            out.distanceProgress = clampUnit(
                    (distance - nearestRowDistance) / fadingDistance);
        }
    }

    private static float clampUnit(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
