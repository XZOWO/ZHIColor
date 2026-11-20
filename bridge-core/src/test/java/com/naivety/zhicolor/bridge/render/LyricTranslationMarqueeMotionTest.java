package com.naivety.zhicolor.bridge.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LyricTranslationMarqueeMotionTest {

    @Test
    public void moderateOverflowGlidesSmoothlyBackToStartAfterSingingFinishes() {
        LyricTranslationMarqueeMotion motion = new LyricTranslationMarqueeMotion();
        LyricTranslationMarqueeMotion.Sample sample = new LyricTranslationMarqueeMotion.Sample();
        float width = 600f;
        float overflow = 240f; // 0.4 * width <= 1.25 * width -> MODE_SMOOTH_GLIDE

        // Upcoming inactive lyric starts quietly at 0.
        motion.sample(0f, overflow, width, false, true, true, false, false, 1_000L, sample);
        assertFalse(sample.animating);
        assertEquals(0f, sample.scrollProgress, 0.0001f);

        // While singing, tracks active progress smoothly up to 1.0.
        motion.sample(0.5f, overflow, width, true, true, true, false, false, 2_000L, sample);
        assertFalse(sample.animating);
        assertEquals(0.5f, sample.scrollProgress, 0.0001f);

        motion.sample(1f, overflow, width, true, true, true, false, false, 3_000L, sample);
        assertFalse(sample.animating);
        assertEquals(1f, sample.scrollProgress, 0.0001f);

        // Singing finishes: must NOT hard-cut to 0; smoothly glides 1.0 -> 0.0.
        motion.sample(1f, overflow, width, false, true, true, false, false, 3_016L, sample);
        assertTrue(sample.animating);
        assertEquals(LyricTranslationMarqueeMotion.MODE_SMOOTH_GLIDE, sample.mode);
        assertEquals(1f, sample.scrollProgress, 0.001f);

        float prev = sample.scrollProgress;
        long duration = LyricTranslationMarqueeMotion.resolveReturnDurationMs(
                overflow, width, false, true, false);
        for (long t = 3_066L; t < 3_016L + duration; t += 50L) {
            motion.sample(0f, overflow, width, false, true, true, false, false, t, sample);
            assertTrue("Should keep gliding at t=" + t, sample.animating);
            assertTrue("Scroll progress must decrease monotonically", sample.scrollProgress <= prev + 1e-4f);
            prev = sample.scrollProgress;
        }

        // Lands cleanly at 0 and stops requesting frames.
        motion.sample(0f, overflow, width, false, true, true, false, false, 3_016L + duration + 10L, sample);
        assertFalse(sample.animating);
        assertEquals(0f, sample.scrollProgress, 0.0001f);
        assertFalse(motion.isAnimating(4_000L));
    }

    @Test
    public void ultraWideTranslationUsesCalmCrossfadeGlideInsteadOfFastHorizontalZip() {
        LyricTranslationMarqueeMotion motion = new LyricTranslationMarqueeMotion();
        LyricTranslationMarqueeMotion.Sample sample = new LyricTranslationMarqueeMotion.Sample();
        float width = 500f;
        float overflow = 900f; // 1.8 * width > 1.25 * width -> MODE_CROSSFADE_GLIDE

        motion.sample(1f, overflow, width, true, true, true, false, false, 2_000L, sample);
        assertEquals(1f, sample.scrollProgress, 0.0001f);

        motion.sample(1f, overflow, width, false, true, true, false, false, 2_016L, sample);
        assertTrue(sample.animating);
        assertEquals(LyricTranslationMarqueeMotion.MODE_CROSSFADE_GLIDE, sample.mode);
        assertEquals(1f, sample.fromProgress, 0.0001f);
        assertEquals(1f, sample.outgoingAlpha, 0.001f);
        assertEquals(0f, sample.incomingAlpha, 0.001f);

        motion.sample(0f, overflow, width, false, true, true, false, false, 2_226L, sample);
        assertTrue(sample.animating);
        assertTrue(sample.outgoingAlpha > 0f && sample.outgoingAlpha < 0.5f);
        assertTrue(sample.incomingAlpha > 0.2f && sample.incomingAlpha < 0.9f);

        motion.sample(0f, overflow, width, false, true, true, false, false,
                2_016L + LyricTranslationMarqueeMotion.CROSSFADE_APPLE_MS + 10L, sample);
        assertFalse(sample.animating);
        assertEquals(0f, sample.scrollProgress, 0.0001f);
    }

    @Test
    public void sameFrameDuplicateHolderOffscreenGapScrubAndLegacyToggleBehaveCleanly() {
        LyricTranslationMarqueeMotion motion = new LyricTranslationMarqueeMotion();
        LyricTranslationMarqueeMotion.Sample sample = new LyricTranslationMarqueeMotion.Sample();

        // Same-frame duplicate inactive holder cannot clobber active progress.
        motion.sample(0.8f, 200f, 500f, true, true, true, false, false, 5_000L, sample);
        motion.sample(0f, 200f, 500f, false, true, true, false, false, 5_000L, sample);
        assertFalse(sample.animating);
        assertEquals(0.8f, sample.scrollProgress, 0.0001f);

        // Off-screen > 1s snaps silently back to 0 without belated rewind.
        motion.sample(0f, 200f, 500f, false, true, true, false, false, 7_500L, sample);
        assertFalse(sample.animating);
        assertEquals(0f, sample.scrollProgress, 0.0001f);

        // Active scrub snaps immediately.
        motion.sample(0.9f, 200f, 500f, true, true, true, false, false, 8_000L, sample);
        motion.sample(0f, 200f, 500f, false, true, true, false, true, 8_016L, sample);
        assertFalse(sample.animating);
        assertEquals(0f, sample.scrollProgress, 0.0001f);

        // Legacy mode snaps straight to 0 the instant activeLine becomes false.
        motion.sampleLegacy(0.9f, 200f, true, true, 9_000L, sample);
        assertEquals(0.9f, sample.scrollProgress, 0.0001f);
        motion.sampleLegacy(0.9f, 200f, false, true, 9_016L, sample);
        assertFalse(sample.animating);
        assertEquals(0f, sample.scrollProgress, 0.0001f);
    }
}
