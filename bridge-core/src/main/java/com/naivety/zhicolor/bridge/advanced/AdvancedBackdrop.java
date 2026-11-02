package com.naivety.zhicolor.bridge.advanced;

import android.graphics.Bitmap;

/** Process boundary adapter; its View paints inside the native animated target. */
public interface AdvancedBackdrop {
    void setPlayback(Bitmap artwork, boolean playing, int audioSessionId,
            String route, String playerPackage, String trackKey);
    void setActive(boolean active);
    void setOptions(boolean dynamicBackground, boolean audioReactive);
    void setInvalidateCallback(Runnable callback);
    /** Native drawing remains in charge until an actual frame is available. */
    boolean isReady();
    void release();
}
