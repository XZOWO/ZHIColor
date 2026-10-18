package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;

public class OplusPluginDexKitAdapterTest {
    private abstract static class FakeIconModel {
        abstract Bitmap bitmap();

        abstract Integer color();
    }

    private static final class FakeNormalIcon extends FakeIconModel {
        private final Bitmap bitmap;
        private final Integer color;

        FakeNormalIcon(Bitmap bitmap, Integer color) {
            this.bitmap = bitmap;
            this.color = color;
        }

        @Override
        Bitmap bitmap() {
            return bitmap;
        }

        @Override
        Integer color() {
            return color;
        }
    }

    private static final class FakeLottieIcon {
    }

    private static final class FakeStaticIcon {
        final Icon icon;
        final Drawable drawable;
        final Bitmap bitmap;
        final FakeIconModel mini;
        final FakeIconModel card;

        FakeStaticIcon(
                Icon icon,
                Drawable drawable,
                Bitmap bitmap,
                FakeIconModel mini,
                FakeIconModel card) {
            this.icon = icon;
            this.drawable = drawable;
            this.bitmap = bitmap;
            this.mini = mini;
            this.card = card;
        }
    }

    private static final class FakeMultiIcon {
        final FakeStaticIcon staticIcon;
        final FakeLottieIcon lottieIcon;

        FakeMultiIcon(FakeStaticIcon staticIcon, FakeLottieIcon lottieIcon) {
            this.staticIcon = staticIcon;
            this.lottieIcon = lottieIcon;
        }

        FakeStaticIcon staticIcon() {
            return staticIcon;
        }

        FakeLottieIcon lottieIcon() {
            return lottieIcon;
        }
    }

    private static final class FakeLyricModel {
        final List<?> lines;

        FakeLyricModel(List<?> lines) {
            this.lines = lines;
        }
    }

    @SuppressWarnings("unused")
    private static final class FakeMediaModel {
        final FakeMultiIcon albumArt = null;
        final FakeMultiIcon topRight = null;
        final FakeLyricModel lyricModel = null;
        final boolean playing = false;
        final boolean lyricSupported = false;
    }

    private static final class FakeArtColors {
        final int a = 0, b = 0, c = 0, d = 0, e = 0, f = 0, g = 0;

        FakeArtColors(int a, int b, int c, int d, int e, int f, int g) { }
    }

    private static final class FakeArtworkResult {
        final Bitmap a;
        final FakeArtColors b;

        FakeArtworkResult(Bitmap bitmap, FakeArtColors colors) {
            a = bitmap;
            b = colors;
        }
    }

    private static final class FakeStaticIcon17 {
        final Drawable a;
        final Bitmap b;
        final FakeIconModel c;
        final FakeNormalIcon d;
        final FakeArtworkResult e;

        FakeStaticIcon17(Drawable drawable, Bitmap bitmap, FakeIconModel mini,
                         FakeNormalIcon card, FakeArtworkResult result) {
            a = drawable;
            b = bitmap;
            c = mini;
            d = card;
            e = result;
        }
    }

    private static final class FakeMultiIcon17 {
        final FakeStaticIcon17 a;
        final FakeLottieIcon b;

        FakeMultiIcon17(FakeStaticIcon17 icon, FakeLottieIcon lottie) {
            a = icon;
            b = lottie;
        }
    }

    // Exact public field/constructor shape of SystemUIPlugin 17.000.002 MediaInfo.
    private static class FakeMediaInfo17 {
        final String a, b;
        final PendingIntent c;
        final FakeMultiIcon17 d;
        final String e;
        final FakeMultiIcon17 f;
        final LinkedHashMap<?, ?> g;
        final String h, i;
        final FakeMultiIcon17 j;
        final String k, l, m;
        final List<?> n, o;
        final FakeLyricModel p;
        final boolean q;
        final FakeArtColors r;
        final boolean s;
        final Object t;

        FakeMediaInfo17(String a, String b, PendingIntent c, FakeMultiIcon17 d, String e,
                        FakeMultiIcon17 f, LinkedHashMap<?, ?> g, String h, String i,
                        FakeMultiIcon17 j, String k, String l, String m, List<?> n, List<?> o,
                        FakeLyricModel p, boolean q, boolean s) {
            this.a = a; this.b = b; this.c = c; this.d = d; this.e = e; this.f = f;
            this.g = g; this.h = h; this.i = i; this.j = j; this.k = k; this.l = l;
            this.m = m; this.n = n; this.o = o; this.p = p; this.q = q; this.s = s;
            r = new FakeArtColors(0, 0, 0, 0, 0, 0, 0);
            t = null;
        }
    }

    private static final class UnknownMediaInfo extends FakeMediaInfo17 {
        final boolean u = false;

        UnknownMediaInfo() {
            super(null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, false, false);
        }
    }

    @Test
    public void bindsObfuscatedPluginModelsByStructure() throws Exception {
        OplusPluginDexKitAdapter.Targets targets =
                OplusPluginDexKitAdapter.bindResolvedClasses(
                        FakeMediaModel.class,
                        FakeMultiIcon.class,
                        FakeStaticIcon.class,
                        FakeNormalIcon.class,
                        FakeLottieIcon.class,
                        FakeLyricModel.class,
                        true);

        assertTrue(targets.resolvedByDexKit);
        assertEquals("albumArt", targets.albumArtField.getName());
        assertEquals("lyricModel", targets.lyricModelField.getName());
        assertEquals("lyricSupported", targets.lyricSupportedField.getName());
        assertEquals("staticIcon", targets.staticIconGetter.getName());
        assertEquals("lottieIcon", targets.lottieIconGetter.getName());
        assertEquals("bitmap", targets.iconModelBitmapGetter.getName());
        assertEquals("color", targets.iconModelColorGetter.getName());
    }

    @Test
    public void legacyIconAccessAndReconstructionKeepTheOldFiveArgumentConstructor() throws Exception {
        OplusPluginDexKitAdapter.Targets targets = OplusPluginDexKitAdapter.bindResolvedClasses(
                FakeMediaModel.class, FakeMultiIcon.class, FakeStaticIcon.class,
                FakeNormalIcon.class, FakeLottieIcon.class, FakeLyricModel.class, true);
        FakeNormalIcon mini = new FakeNormalIcon(null, 10);
        FakeNormalIcon card = new FakeNormalIcon(null, 20);
        FakeStaticIcon original = new FakeStaticIcon(null, null, null, mini, card);
        FakeLottieIcon lottie = new FakeLottieIcon();
        FakeMultiIcon album = new FakeMultiIcon(original, lottie);

        assertSame(original, targets.readStaticIcon(album));
        assertSame(lottie, targets.readLottieIcon(album));
        FakeStaticIcon repaired = (FakeStaticIcon) targets.createStaticIcon(
                null, null, null, mini, card, original);
        assertSame(mini, repaired.mini);
        assertSame(card, repaired.card);
    }

    @Test
    public void colorOs17MediaInfoWritesLyricSupportWithoutChangingArtworkBackground() throws Exception {
        OplusPluginDexKitAdapter.Targets targets = bindColorOs17(FakeMediaInfo17.class);
        FakeMediaInfo17 model = new FakeMediaInfo17(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, false, false);

        targets.lyricSupportedField.setBoolean(model, true);

        assertSame(FakeMediaInfo17.class, targets.mediaModelClass);
        assertEquals("d", targets.albumArtField.getName());
        assertEquals("p", targets.lyricModelField.getName());
        assertEquals("q", targets.lyricSupportedField.getName());
        assertTrue(model.q);
        assertFalse(model.s);
    }

    @Test
    public void colorOs17DirectFieldsAndConstructorPreserveArtworkResult() throws Exception {
        OplusPluginDexKitAdapter.Targets targets = bindColorOs17(FakeMediaInfo17.class);
        FakeArtworkResult processed = new FakeArtworkResult(null,
                new FakeArtColors(0, 0, 0, 0, 0, 0, 0));
        FakeNormalIcon mini = new FakeNormalIcon(null, 10);
        FakeNormalIcon card = new FakeNormalIcon(null, 20);
        FakeStaticIcon17 original = new FakeStaticIcon17(null, null, mini, card, processed);
        FakeLottieIcon lottie = new FakeLottieIcon();
        FakeMultiIcon17 album = new FakeMultiIcon17(original, lottie);

        assertSame(original, targets.readStaticIcon(album));
        assertSame(lottie, targets.readLottieIcon(album));
        FakeStaticIcon17 repaired = (FakeStaticIcon17) targets.createStaticIcon(
                null, null, null, mini, card, original);
        assertSame(mini, repaired.c);
        assertSame(card, repaired.d);
        assertSame(processed, repaired.e);
        assertSame(card, targets.cardIconModelField.get(repaired));
    }

    @Test
    public void unknownMediaInfoShapeIsRejectedInsteadOfSelectingLastBoolean() {
        assertThrows(IllegalStateException.class, () -> bindColorOs17(UnknownMediaInfo.class));
    }

    private static OplusPluginDexKitAdapter.Targets bindColorOs17(Class<?> model)
            throws Exception {
        return OplusPluginDexKitAdapter.bindResolvedClasses(
                model, FakeMultiIcon17.class, FakeStaticIcon17.class,
                FakeNormalIcon.class, FakeLottieIcon.class, FakeLyricModel.class, true, true);
    }
}
