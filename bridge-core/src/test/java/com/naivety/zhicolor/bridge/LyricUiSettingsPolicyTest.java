package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LyricUiSettingsPolicyTest {
    @Test
    public void timeoutTextUsesCanonicalReadbackRange() {
        assertEquals(0, LyricUiSettings.parseScreenTimeoutSeconds(""));
        assertEquals(1, LyricUiSettings.parseScreenTimeoutSeconds("1"));
        assertEquals(86_400, LyricUiSettings.parseScreenTimeoutSeconds("999999"));
        assertEquals(86_400,
                LyricUiSettings.parseScreenTimeoutSeconds("999999999999999999999999"));
        assertEquals(0, LyricUiSettings.parseScreenTimeoutSeconds("invalid"));
    }

}
