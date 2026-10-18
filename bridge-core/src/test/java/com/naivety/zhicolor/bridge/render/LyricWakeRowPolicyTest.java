package com.naivety.zhicolor.bridge.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class LyricWakeRowPolicyTest {
    private static final float EPSILON = 0.000001f;

    @Test public void entranceAndWakeMasksPreserveEveryRoleAtEndpointsAndIntermediateAmounts() {
        for (boolean focused : new boolean[]{false, true}) {
            for (boolean active : new boolean[]{false, true}) {
                for (float aod : new float[]{0f, .25f, .5f, .75f, 1f}) {
                    for (float entrance : new float[]{0f, .125f, .5f, .875f, 1f}) {
                        for (float word : new float[]{0f, .25f, .5f, .75f, 1f}) {
                            float expected = (focused ? aod : 0f)
                                    + (active ? (1f - aod) * entrance * word : 0f);
                            assertEquals(expected,
                                    highlight(focused, active, aod, entrance, word), EPSILON);
                        }
                    }
                }
            }
        }
    }

    @Test public void incomingUnsungWordsRemainUnlitThroughoutTheWakeAndEntrance() {
        for (int frame = 0; frame <= 100; frame++) {
            float aod = frame / 100f;
            float entrance = 1f - aod;
            assertEquals(0f, highlight(false, true, aod, entrance, 0f), 0f);
            assertEquals(aod, highlight(true, true, aod, entrance, 0f), EPSILON);
            assertEquals(0f, highlight(false, false, aod, entrance, 1f), 0f);
        }
    }

    @Test public void entranceNeverSuppressesMoreBaseInkThanTheActualAodContribution() {
        for (boolean focused : new boolean[]{false, true}) {
            for (boolean active : new boolean[]{false, true}) {
                for (float aod : new float[]{0f, .25f, .5f, .75f, 1f}) {
                    for (float entrance : new float[]{0f, .25f, .5f, .75f, 1f}) {
                        float activation = LyricWakeRowPolicy.activation(focused, active, aod, entrance);
                        float fill = LyricWakeRowPolicy.fillAmount(focused, active, aod, entrance);
                        assertEquals(focused ? aod : 0f, activation * fill, EPSILON);
                        assertTrue(Float.isFinite(fill) && fill >= 0f && fill <= 1f);
                    }
                }
            }
        }
    }

    @Test public void invalidEntranceAmountsClampWithoutInventingBrightInk() {
        for (boolean focused : new boolean[]{false, true}) {
            for (boolean active : new boolean[]{false, true}) {
                for (float aod : new float[]{0f, .5f, 1f}) {
                    for (float invalid : new float[]{-1f, Float.NEGATIVE_INFINITY, Float.NaN}) {
                        assertEquals(highlight(focused, active, aod, 0f, .5f),
                                highlight(focused, active, aod, invalid, .5f), 0f);
                    }
                    for (float invalid : new float[]{2f, Float.POSITIVE_INFINITY}) {
                        assertEquals(highlight(focused, active, aod, 1f, .5f),
                                highlight(focused, active, aod, invalid, .5f), 0f);
                    }
                }
            }
        }
    }

    @Test
    public void allFourRolesMatchTheAodAndKaraokeBlendAtBothEndsAndMidpoint() {
        for (boolean focused : new boolean[]{false, true}) {
            for (boolean active : new boolean[]{false, true}) {
                for (float aod : new float[]{0f, .5f, 1f}) {
                    for (float word : new float[]{0f, .25f, .5f, .75f, 1f}) {
                        float expected = (focused ? aod : 0f)
                                + (active ? (1f - aod) * word : 0f);
                        assertEquals(expected, highlight(focused, active, aod, word), EPSILON);
                    }
                }
            }
        }
    }

    @Test
    public void brightOnlyUnsungTextNeverAcquiresAnIntermediateHighlight() {
        for (int frame = 0; frame <= 100; frame++) {
            float aod = frame / 100f;
            assertEquals(0f, highlight(false, true, aod, 0f), 0f);
            assertEquals((1f - aod) * .5f,
                    highlight(false, true, aod, .5f), EPSILON);
        }
    }

    @Test
    public void aodOnlyFocusFadesTheWholeLineUniformly() {
        for (float aod : new float[]{0f, .25f, .5f, .75f, 1f}) {
            assertEquals(aod, highlight(true, false, aod, 0f), 0f);
            assertEquals(aod, highlight(true, false, aod, 1f), 0f);
        }
    }

    @Test
    public void inactiveRowsKeepTheirBaseWithoutAnyActiveInk() {
        for (float aod : new float[]{0f, .5f, 1f}) {
            assertEquals(0f, LyricWakeRowPolicy.activation(false, false, aod), 0f);
            assertEquals(0f, LyricWakeRowPolicy.fillAmount(false, false, aod), 0f);
        }
    }

    @Test
    public void baseSuppressionOnlyTracksTheActualAodFocusedContribution() {
        for (boolean focused : new boolean[]{false, true}) {
            for (boolean active : new boolean[]{false, true}) {
                for (float aod : new float[]{0f, .25f, .5f, .75f, 1f}) {
                    float suppression = LyricWakeRowPolicy.activation(focused, active, aod)
                            * LyricWakeRowPolicy.fillAmount(focused, active, aod);
                    assertEquals(focused ? aod : 0f, suppression, EPSILON);
                }
            }
        }
    }

    @Test
    public void invalidAmountsClampToTheCorrespondingVisibleEndpoint() {
        for (boolean focused : new boolean[]{false, true}) {
            for (boolean active : new boolean[]{false, true}) {
                for (float invalid : new float[]{-1f, Float.NEGATIVE_INFINITY, Float.NaN}) {
                    assertEquals(LyricWakeRowPolicy.activation(focused, active, 0f),
                            LyricWakeRowPolicy.activation(focused, active, invalid), 0f);
                    assertEquals(LyricWakeRowPolicy.fillAmount(focused, active, 0f),
                            LyricWakeRowPolicy.fillAmount(focused, active, invalid), 0f);
                }
                for (float invalid : new float[]{2f, Float.POSITIVE_INFINITY}) {
                    assertEquals(LyricWakeRowPolicy.activation(focused, active, 1f),
                            LyricWakeRowPolicy.activation(focused, active, invalid), 0f);
                    assertEquals(LyricWakeRowPolicy.fillAmount(focused, active, 1f),
                            LyricWakeRowPolicy.fillAmount(focused, active, invalid), 0f);
                }
            }
        }
    }

    private static float highlight(boolean focused, boolean active, float aod, float wordMask) {
        return LyricWakeRowPolicy.activation(focused, active, aod)
                * LyricWakeTransitionPolicy.highlightAmount(wordMask,
                LyricWakeRowPolicy.fillAmount(focused, active, aod));
    }

    private static float highlight(boolean focused, boolean active, float aod,
            float entrance, float wordMask) {
        return LyricWakeRowPolicy.activation(focused, active, aod, entrance)
                * LyricWakeTransitionPolicy.highlightAmount(wordMask,
                LyricWakeRowPolicy.fillAmount(focused, active, aod, entrance));
    }
}
