package com.naivety.zhicolor.bridge;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import com.naivety.zhicolor.bridge.render.WordLine;
import java.util.WeakHashMap;

/** A separate auxiliary text lane inside the standard renderer; it owns no scroll or animation. */
final class LyricRomanizationLayout {
    private final WeakHashMap<WordLine, Entry> entries = new WeakHashMap<>();

    void clear() { entries.clear(); }

    Entry resolve(WordLine line, boolean enabled, Paint source, float availableWidth, int alignment, boolean rtl) {
        String text = LyricAuxiliaryTextPolicy.romanization(line, enabled);
        if (text.isEmpty() || availableWidth <= 0f) return null;
        int width = Math.max(1, (int) Math.floor(availableWidth));
        Layout.Alignment gravity = alignment == 1 ? Layout.Alignment.ALIGN_CENTER
                : (alignment == 2 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL);
        Entry entry = entries.get(line);
        if (entry == null || !entry.matches(text, width, source, gravity, rtl)) {
            entry = new Entry(text, width, source, gravity, rtl);
            entries.put(line, entry);
        }
        return entry;
    }

    static final class Entry {
        final String text;
        final int width;
        final float size;
        final Typeface typeface;
        final Layout.Alignment alignment;
        final boolean rtl;
        final TextPaint paint;
        final StaticLayout layout;

        Entry(String text, int width, Paint source, Layout.Alignment alignment, boolean rtl) {
            this.text = text; this.width = width; this.size = source.getTextSize();
            this.typeface = source.getTypeface(); this.alignment = alignment; this.rtl = rtl;
            paint = new TextPaint(source);
            paint.setShader(null);
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .setAlignment(alignment).setIncludePad(true)
                    .setTextDirection(rtl ? android.text.TextDirectionHeuristics.RTL : android.text.TextDirectionHeuristics.LTR)
                    .build();
        }

        boolean matches(String text, int width, Paint source, Layout.Alignment alignment, boolean rtl) {
            return this.text.equals(text) && this.width == width && size == source.getTextSize()
                    && typeface == source.getTypeface() && this.alignment == alignment && this.rtl == rtl;
        }

        float height() { return layout.getHeight(); }

        void draw(Canvas canvas, float x, float y, int color) {
            paint.setColor(color);
            int save = canvas.save();
            canvas.translate(x, y);
            layout.draw(canvas);
            canvas.restoreToCount(save);
        }
    }
}
