package com.naivety.zhicolor.bridge;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

/** Isolated test surface; this activity is never included in the release APK. */
public final class BackgroundProbeActivity extends Activity {
    FrameLayout target;
    LyricBackgroundHost host;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        target = new FrameLayout(this);
        target.setBackgroundColor(android.graphics.Color.MAGENTA);
        setContentView(target);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (host != null) host.stateChanged();
    }
    @Override protected void onDestroy() {
        if (host != null) host.reset();
        super.onDestroy();
    }
}
