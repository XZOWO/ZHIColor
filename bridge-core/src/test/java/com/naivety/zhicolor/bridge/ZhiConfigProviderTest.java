package com.naivety.zhicolor.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ZhiConfigProviderTest {
    @Test public void acceptsColorOsSystemUiWithApplicationUid() {
        assertTrue(ZhiConfigProvider.isTrustedCaller(10218, 10046, 10218));
    }
    @Test public void acceptsModuleAndSystemServer() {
        assertTrue(ZhiConfigProvider.isTrustedCaller(10046, 10046, 10218));
        assertTrue(ZhiConfigProvider.isTrustedCaller(1000, 10046, 10218));
    }
    @Test public void rejectsOtherAppsShellAndMissingSystemUi() {
        assertFalse(ZhiConfigProvider.isTrustedCaller(10219, 10046, 10218));
        assertFalse(ZhiConfigProvider.isTrustedCaller(2000, 10046, 10218));
        assertFalse(ZhiConfigProvider.isTrustedCaller(-1, 10046, -1));
    }
}
