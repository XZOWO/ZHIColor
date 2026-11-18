package com.naivety.zhicolor.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class LyricPositionPolicyTest {
    @Test
    public void appleTopInsetUsesTwelvePercentWithMinFloor() {
        assertEquals(0, LyricPositionPolicy.appleTopInsetPx(0, 156));
        assertEquals(180, LyricPositionPolicy.appleTopInsetPx(1500, 156));
        assertEquals(156, LyricPositionPolicy.appleTopInsetPx(900, 156));
        assertEquals(100, LyricPositionPolicy.appleTopInsetPx(300, 156));
    }

    @Test
    public void appleBottomInsetAllowsLastLineToReachTopAnchor() {
        assertEquals(1152, LyricPositionPolicy.appleBottomInsetPx(1500, 180, 168, 0));
        assertEquals(1200, LyricPositionPolicy.appleBottomInsetPx(1500, 180, 168, 1200));
        assertEquals(40, LyricPositionPolicy.appleBottomInsetPx(0, 180, 168, 40));
    }

    @Test
    public void desiredCenterSwitchesBetweenSystemAndAppleAnchoring() {
        assertEquals(222, LyricPositionPolicy.desiredCenterPx(true, 1500, 84, 180, 42f));
        assertEquals(180, LyricPositionPolicy.desiredCenterPx(true, 1500, 84, 180, -10f));
        assertEquals(834, LyricPositionPolicy.desiredCenterPx(false, 1500, 84, 180, 42f));

        // At targetIndex > 0 without RecyclerView top padding, offset = desiredCenter - 0 - anchor = topInset.
        int topInset = 180;
        int desiredCenter = LyricPositionPolicy.desiredCenterPx(true, 1500, 84, topInset, 42f);
        assertEquals(0, Math.round(desiredCenter - topInset - 42f));
    }

    @Test
    public void scrollerTopOffsetUsesZeroForFirstItemMarginAndTopInsetForFollowingItems() {
        int topInset = 180;
        int desiredCenter = LyricPositionPolicy.desiredCenterPx(true, 1500, 84, topInset, 42f);
        assertEquals(0, LyricPositionPolicy.scrollerTopOffsetPx(true, 0, desiredCenter, 0, 42f));
        assertEquals(topInset, LyricPositionPolicy.scrollerTopOffsetPx(true, 1, desiredCenter, 0, 42f));
        assertEquals(792, LyricPositionPolicy.scrollerTopOffsetPx(false, 0, 834, 0, 42f));
    }
}
