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
    private static final float TRANSFORM_TRANSLATION_EPSILON_PX = 0.5f;
    private static final float TRANSFORM_SCALE_EPSILON = 0.005f;
    private static final float TRANSFORM_ROTATION_EPSILON_DEG = 0.05f;

    private LyricViewportMotionPolicy() {}

    /** Reusable vertical viewport bounds in the RecyclerView's local coordinate space. */
    public static final class ViewportBounds {
        public float top;
        public float bottom;
    }

    /**
     * Reports whether a view on the RecyclerView-to-window chain has a non-identity transform or
     * partial clip bounds that distorts {@code View#getLocalVisibleRect(Rect)} (for example,
     * translation clipping against the display edge or un-inverted scale shrinking the rect).
     */
    public static boolean hasViewportTransformOrClip(
            float translationX,
            float translationY,
            float scaleX,
            float scaleY,
            float rotation,
            float rotationX,
            float rotationY,
            boolean hasPartialClipBounds,
            boolean hasAnimationMatrix) {
        return hasPartialClipBounds
                || hasAnimationMatrix
                || !Float.isFinite(translationX)
                || !Float.isFinite(translationY)
                || !Float.isFinite(scaleX)
                || !Float.isFinite(scaleY)
                || !Float.isFinite(rotation)
                || !Float.isFinite(rotationX)
                || !Float.isFinite(rotationY)
                || Math.abs(translationX) > TRANSFORM_TRANSLATION_EPSILON_PX
                || Math.abs(translationY) > TRANSFORM_TRANSLATION_EPSILON_PX
                || Math.abs(scaleX - 1f) > TRANSFORM_SCALE_EPSILON
                || Math.abs(scaleY - 1f) > TRANSFORM_SCALE_EPSILON
                || Math.abs(rotation) > TRANSFORM_ROTATION_EPSILON_DEG
                || Math.abs(rotationX) > TRANSFORM_ROTATION_EPSILON_DEG
                || Math.abs(rotationY) > TRANSFORM_ROTATION_EPSILON_DEG;
    }

    /**
     * Resolves the effective vertical lyric viewport inside the RecyclerView's local coordinates.
     *
     * <p>During page/mode enter or exit animations (such as ColorOS 17's cover-to-lyrics spring,
     * which starts with a large positive {@code translationY} or shrunk {@code scaleY}),
     * {@code View#getLocalVisibleRect(Rect)} clips against the screen boundary and fails to invert
     * scaling, truncating {@code bottom}. Any inactive row drawn while outside that transient rect
     * would bake {@code visible = false} (alpha 0) into its display list and remain invisible once
     * the spring settles while playback is paused. Falling back to the RecyclerView's own
     * padding-clipped layout bounds during transient transforms/clips or off-screen pre-draws
     * preserves resting visibility, fade and blur for every row.</p>
     */
    public static boolean resolveViewportBounds(
            int recyclerHeight,
            int paddingTop,
            int paddingBottom,
            boolean clipToPadding,
            boolean localVisibleAvailable,
            int localVisibleTop,
            int localVisibleBottom,
            boolean transientTransformOrClip,
            ViewportBounds out) {
        if (out == null) throw new IllegalArgumentException("out");
        out.top = 0f;
        out.bottom = 0f;
        if (recyclerHeight <= 0) {
            return false;
        }
        int layoutTop = clipToPadding ? Math.max(0, paddingTop) : 0;
        int layoutBottom = recyclerHeight - (clipToPadding ? Math.max(0, paddingBottom) : 0);
        if (layoutBottom <= layoutTop) {
            return false;
        }
        if (transientTransformOrClip || !localVisibleAvailable) {
            out.top = layoutTop;
            out.bottom = layoutBottom;
            return true;
        }
        int top = Math.max(localVisibleTop, layoutTop);
        int bottom = Math.min(localVisibleBottom, layoutBottom);
        if (bottom <= top) {
            out.top = layoutTop;
            out.bottom = layoutBottom;
            return true;
        }
        out.top = top;
        out.bottom = bottom;
        return true;
    }

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
