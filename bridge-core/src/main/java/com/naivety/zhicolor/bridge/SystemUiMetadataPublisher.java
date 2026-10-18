package com.naivety.zhicolor.bridge;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Publishes Lyricon changes through the real SystemUI MediaController callback queues.
 * No synthetic media session, playback command or player-owned metadata is created.
 * Every OEM listener retains its own Handler, token, metadata and artwork.
 */
public final class SystemUiMetadataPublisher {
    private static final String TAG = "ZHIColorDelivery";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<MediaController, MetadataDeliveryState> controllers =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static Method dispatch;
    private static int metadataMessage;
    private static boolean pending;

    private SystemUiMetadataPublisher() {}

    public static synchronized Method dispatchMethod() throws ReflectiveOperationException {
        if (dispatch == null) {
            Method method = MediaController.class.getDeclaredMethod(
                    "postMessage", int.class, Object.class, Bundle.class);
            Field message = MediaController.class.getDeclaredField("MSG_UPDATE_METADATA");
            method.setAccessible(true);
            message.setAccessible(true);
            metadataMessage = message.getInt(null);
            dispatch = method;
        }
        return dispatch;
    }

    public static boolean isMetadataMessage(Object message) {
        return dispatch != null && message instanceof Integer && (Integer) message == metadataMessage;
    }

    public static void observe(MediaController controller, boolean listenerAdded) {
        if (controller == null) return;
        boolean added;
        synchronized (controllers) {
            added = !controllers.containsKey(controller);
            MetadataDeliveryState state = controllers.computeIfAbsent(
                    controller, ignored -> new MetadataDeliveryState());
            if (listenerAdded) state.listenerAdded();
        }
        if (added || listenerAdded) requestRefresh();
    }

    public static void delivered(MediaController controller, String payload) {
        synchronized (controllers) {
            controllers.computeIfAbsent(controller, ignored -> new MetadataDeliveryState())
                    .delivered(payload);
        }
    }

    /** Coalesce provider + song callbacks, but do not poll or depend on screen state. */
    public static void requestRefresh() {
        synchronized (controllers) {
            if (pending) return;
            pending = true;
        }
        main.post(() -> {
            synchronized (controllers) { pending = false; }
            publishPending();
        });
    }

    static void publishPending() {
        ArrayList<MediaController> targets;
        synchronized (controllers) { targets = new ArrayList<>(controllers.keySet()); }
        for (MediaController controller : targets) {
            MetadataDeliveryState state = controllers.get(controller);
            if (state == null) continue;
            try {
                // Read the session NOW, never replay metadata saved for the previous song.
                MediaMetadata metadata = controller.getMetadata();
                if (metadata == null) continue;
                String payload = ZhiBridgeRuntime.decorate(metadata, controller.getPackageName());
                if (!state.needsDelivery(payload)) continue;
                dispatchMethod().invoke(controller, metadataMessage, metadata, null);
                state.delivered(payload);
                Log.i(TAG, "metadata dispatched package=" + controller.getPackageName()
                        + " lyrics=" + (payload != null));
            } catch (Throwable error) {
                // Keep the previous state so the next source/config event can retry.
                Log.w(TAG, "metadata dispatch failed", error);
            }
        }
    }
}
