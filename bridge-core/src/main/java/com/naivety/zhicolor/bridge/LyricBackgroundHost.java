package com.naivety.zhicolor.bridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import com.naivety.zhicolor.bridge.advanced.AdvancedBackdrop;
import com.naivety.zhicolor.bridge.diagnostics.StructuredBridgeLog;
import com.naivety.zhicolor.bridge.diagnostics.BridgeDebugArea;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Background content only. Native lyrics, touch, layout and transitions retain their owners. */
final class LyricBackgroundHost {
    interface Runtime {
        boolean active();
        boolean mediaPlaying();
        MediaController controller();
    }
    private static final String TAG = "ZHIBackground";
    private final Runtime runtime;
    private String reportedState = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService mediaWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ZHIColor-background-media"); thread.setDaemon(true); return thread;
    });
    private final Map<View, BackdropSlot> backgrounds = new IdentityHashMap<>();
    private final Map<View, SectionSlot> sections = new IdentityHashMap<>();
    private static final long UNATTACHED_RETENTION_MS = 5_000L;
    private LyricAppearanceSettings options;
    private Context hostContext, moduleContext;
    private boolean scheduled, mediaPending;
    private DisplayManager displays;
    private final java.util.Set<View> observedTargets = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final View.OnAttachStateChangeListener attachmentListener = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { observeTarget(view); stateChanged(); }
        @Override public void onViewDetachedFromWindow(View view) { detach(view); }
    };
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { stateChanged(); }
        @Override public void onDisplayRemoved(int id) { stateChanged(); }
        @Override public void onDisplayChanged(int id) { stateChanged(); }
    };
    private PowerManager power;
    private long mediaReadAt;
    private int generation;
    private MediaFrame mediaFrame;
    private MediaController mediaController;
    private String mediaIdentity = "";
    private long mediaGeneration, metadataRevision;
    private MediaMetadata pendingMetadata;
    private final Runnable immediateRefresh = () -> { refreshSlots(); schedule(); };
    private final Runnable pulse = () -> {
        scheduled = false;
        refreshSlots();
        schedule();
    };

    LyricBackgroundHost(Runtime runtime) { this.runtime = runtime; }


    /** Native visibility/playback events are immediate; the periodic pulse is only clock correction. */
    void stateChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.removeCallbacks(immediateRefresh); main.post(immediateRefresh); return;
        }
        if (!backgrounds.isEmpty() || !sections.isEmpty()) {
            main.removeCallbacks(immediateRefresh); main.post(immediateRefresh);
        }
    }

    void settings(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> settings(context)); return; }
        hostContext = context;
        power = context.getSystemService(PowerManager.class);
        if (displays == null) {
            displays = context.getSystemService(DisplayManager.class);
            if (displays != null) displays.registerDisplayListener(displayListener, main);
        }
        options = LyricAppearanceSettings.load(context);
        reportState();
        for (View target : backgrounds.keySet()) target.invalidate();
        refreshSlots();
        schedule();
    }


    void observeSection(Object section) {
        observeSection(section, null);
    }
    void observeSection(Object section, ViewGroup container) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> observeSection(section, container)); return;
        }
        Object lyric = field(section, "n");
        if (container != null) {
            observeMediaContainer(container, lyric instanceof View ? (View) lyric : null);
            sections.put(container, new SectionSlot(container, lyric instanceof View ? (View) lyric : null, section));
        }
        observeBackgroundController(field(section, "x"), null, null);
        reportState();
    }
    /** The native bound section remains the owner even when its lyric child is GONE. */
    void observeMediaContainer(View container) { observeMediaContainer(container, null); }
    private void observeMediaContainer(View container, View aodOwner) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> observeMediaContainer(container, aodOwner)); return;
        }
        if (options == null) settings(container.getContext());
        sections.put(container, new SectionSlot(container, aodOwner, null));
        stateChanged();
    }
    void observeBackgroundController(Object controller, Object previous, Object next) {
        if (controller == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> observeBackgroundController(controller, previous, next)); return;
        }
        observeRenderer(field(controller, "i"));
        observeRenderer(previous);
        observeRenderer(next);
        schedule();
    }
    private void observeRenderer(Object renderer) {
        if (renderer == null) return;
        try {
            Method method = renderer.getClass().getMethod("d");
            method.setAccessible(true);
            Object result = method.invoke(renderer);
            if (result instanceof View) observeTarget((View) result);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Log.w(TAG, "background target unavailable", failure);
        }
    }




    /** Also catches a reused native surface whose controller bind preceded hook installation. */
    void observeAttached(View target) {
        if ("com.oplus.systemui.plugins.shared.template.component.media.view.q".equals(target.getClass().getName())) {
            observeTarget(target);
            stateChanged();
        }
    }
    private void observeTarget(View target) {
        if (options == null) settings(target.getContext());
        if (observedTargets.add(target)) target.addOnAttachStateChangeListener(attachmentListener);
        if (!backgrounds.containsKey(target)) {
            backgrounds.put(target, new BackdropSlot(target));
            Log.i(TAG, "background host attached: " + target.getClass().getName());
        }
        stateChanged();
    }

    void detach(View target) {
        sections.remove(target);
        BackdropSlot backdrop = backgrounds.remove(target);
        if (backdrop != null) backdrop.release();
        stateChanged();
    }
    void reset() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::reset); return; }
        generation++;
        main.removeCallbacks(pulse);
        main.removeCallbacks(immediateRefresh);
        scheduled = false;
        for (BackdropSlot slot : new ArrayList<>(backgrounds.values())) slot.release();
        backgrounds.clear(); sections.clear(); mediaFrame = null;
        for (View target : new ArrayList<>(observedTargets)) target.removeOnAttachStateChangeListener(attachmentListener);
        observedTargets.clear();
        mediaController = null; mediaIdentity = ""; pendingMetadata = null;
        mediaGeneration++; metadataRevision++;
    }
    private boolean enabled() { return options != null && options.appleBackground && runtime.active(); }
    private boolean interactive() {
        return power != null && power.isInteractive();
    }


    private boolean displayOn(View target) {
        Display display = target.getDisplay();
        return display != null && display.getState() == Display.STATE_ON;
    }
    private boolean hasVisibleMedia() {
        if (!enabled() || !interactive()) return false;
        for (BackdropSlot slot : backgrounds.values()) if (backgroundRunning(slot.target)) return true;
        return false;
    }
    private boolean hasMediaSurface() {
        if (!enabled()) return false;
        for (SectionSlot slot : sections.values())
            if (slot.target.isAttachedToWindow() && slot.target.isShown()
                    && slot.target.getWindowVisibility() == View.VISIBLE) return true;
        return LyricBackgroundSurfacePolicy.visible(enabled(), false, hasVisibleBackground());
    }
    private boolean hasVisibleBackground() {
        for (BackdropSlot slot : backgrounds.values()) {
            View target = slot.target;
            if (target.isAttachedToWindow() && target.isShown()
                    && target.getWindowVisibility() == View.VISIBLE) return true;
        }
        return false;
    }
    private boolean backgroundEligible(View target) {
        return hasMediaSurface() && target.isAttachedToWindow() && target.isShown()
                && target.getWindowVisibility() == View.VISIBLE;
    }
    private boolean backgroundRunning(View target) {
        return LyricBackgroundSurfacePolicy.running(backgroundEligible(target), interactive(), displayOn(target));
    }
    /** OEM lyric styling can remain AOD-styled on an awake screen; it never gates the backdrop. */
    void nativeAodChanged(View owner) { stateChanged(); }



    private void refreshSlots() {
        synchronizeMediaController();
        long now = SystemClock.uptimeMillis();
        for (SectionSlot slot : new ArrayList<>(sections.values())) {
            if ((slot.everAttached && !slot.target.isAttachedToWindow())
                    || (!slot.everAttached && !slot.target.isAttachedToWindow()
                    && now - slot.observedAt > UNATTACHED_RETENTION_MS)) {
                sections.remove(slot.target);
            } else slot.everAttached |= slot.target.isAttachedToWindow();
        }
        boolean visible = enabled() && hasVisibleMedia();
        for (BackdropSlot slot : new ArrayList<>(backgrounds.values())) {
            if (slot.everAttached && !slot.target.isAttachedToWindow()) { detach(slot.target); continue; }
            if (!slot.everAttached && !slot.target.isAttachedToWindow()
                    && now - slot.observedAt > UNATTACHED_RETENTION_MS) { detach(slot.target); continue; }
            slot.everAttached |= slot.target.isAttachedToWindow();
            if (backgroundRunning(slot.target)) slot.ensureContent();
            if (slot.content != null) {
                slot.content.setOptions(options.backgroundAnimation, options.backgroundAnimation);
                boolean active = backgroundRunning(slot.target);
                slot.content.setActive(active);
                if (active) {
                    layout(slot.view, slot.target);
                    slot.overlay.setBounds(0, 0, slot.target.getWidth(), slot.target.getHeight());
                    slot.playback();
                }
                if (!backgroundEligible(slot.target)) slot.handoffStarted = 0L;
                slot.updateOverlay(backgroundEligible(slot.target));
            }
        }
        if (!visible) { main.removeCallbacks(pulse); scheduled = false; }
        if (visible && !mediaPending && SystemClock.uptimeMillis() - mediaReadAt >= 1000) readMedia();
        reportState();
    }
    private void reportState() {
        StringBuilder status = new StringBuilder("choice=")
                .append(options == null ? -1 : options.background)
                .append(", enabled=").append(enabled()).append(", interactive=").append(interactive())
                .append(", animation=").append(options != null && options.backgroundAnimation)
                .append(", sections=").append(sections.size()).append(", targets=").append(backgrounds.size());
        for (BackdropSlot slot : backgrounds.values()) {
            status.append("; ").append(slot.target.getClass().getName())
                    .append(" attached=").append(slot.target.isAttachedToWindow())
                    .append(" shown=").append(slot.target.isShown())
                    .append(" size=").append(slot.target.getWidth()).append('x').append(slot.target.getHeight())
                    .append(" eligible=").append(backgroundEligible(slot.target))
                    .append(" displayOn=").append(displayOn(slot.target))
                    .append(" running=").append(backgroundRunning(slot.target))
                    .append(" content=").append(slot.content != null)
                    .append(" ready=").append(slot.content != null && slot.content.isReady());
        }
        String next = status.toString();
        if (next.equals(reportedState)) return;
        reportedState = next;
        StructuredBridgeLog.infoAlways(BridgeDebugArea.BOOTSTRAP, "BACKGROUND_STATE", next);
    }
    private void schedule() {
        if (!enabled() || !hasVisibleMedia() || scheduled) return;
        scheduled = true;
        main.postDelayed(pulse, hasVisibleMedia() ? 250L : 1000L);
    }
    private void readMedia() {
        final MediaController controller = runtime.controller();
        final int epoch = generation;
        final long expectedMediaGeneration = mediaGeneration, expectedRevision = metadataRevision;
        final MediaMetadata snapshot = pendingMetadata;
        pendingMetadata = null;
        if (controller == null || hostContext == null) return;
        final Context context = hostContext;
        mediaPending = true; mediaReadAt = SystemClock.uptimeMillis();
        mediaWorker.execute(() -> {
            MediaFrame frame = new MediaFrame();
            frame.controller = controller; frame.player = controller.getPackageName();
            try {
                MediaMetadata data = snapshot != null ? snapshot : controller.getMetadata();
                frame.identity = metadataIdentity(frame.player, data);
                if (data != null) {
                    frame.art = data.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (frame.art == null) frame.art = data.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    if (frame.art == null) frame.art = data.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                    if (frame.art == null) frame.art = data.getDescription().getIconBitmap();
                }
                readAudioSession(context, frame);
            } catch (RuntimeException failure) { Log.w(TAG, "media snapshot failed", failure); }
            main.post(() -> {
                mediaPending = false;
                if (epoch != generation || expectedMediaGeneration != mediaGeneration
                        || expectedRevision != metadataRevision || !sameSession(controller, runtime.controller())) return;
                if (!frame.identity.isEmpty() && !frame.identity.equals(mediaIdentity)) {
                    mediaIdentity = frame.identity;
                    mediaGeneration++;
                }
                frame.generation = mediaGeneration;
                frame.track = "media:" + mediaGeneration + ":" + Integer.toHexString(mediaIdentity.hashCode());
                // A new track can publish identity before its artwork. Keep the completed
                // visual until a replacement is ready; session/player/generation stay new.
                if (frame.art == null && mediaFrame != null) {
                    frame.art = mediaFrame.art;
                }
                mediaFrame = frame;
                for (BackdropSlot slot : backgrounds.values()) if (slot.content != null) slot.playback();
            });
        });
    }
    private void synchronizeMediaController() {
        MediaController current = runtime.controller();
        if (mediaController == current) return;
        if (sameSession(mediaController, current)) {
            mediaController = current;
            return;
        }
        mediaController = current;
        mediaIdentity = "";
        pendingMetadata = null;
        mediaGeneration++;
        metadataRevision++;
        mediaReadAt = 0L;
    }
    /** Called from the existing MediaController callback, independent of lyric arrival. */
    void metadataChanged(MediaController controller, MediaMetadata metadata) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> metadataChanged(controller, metadata)); return;
        }
        if (controller == null || !sameSession(controller, runtime.controller())) return;
        synchronizeMediaController();
        String identity;
        try { identity = metadataIdentity(controller.getPackageName(), metadata); }
        catch (RuntimeException failure) {
            Log.w(TAG, "metadata identity unavailable", failure); identity = "";
        }
        if (!identity.isEmpty() && !identity.equals(mediaIdentity)) {
            mediaIdentity = identity;
            mediaGeneration++;
        }
        metadataRevision++;
        pendingMetadata = metadata;
        mediaReadAt = 0L;
        stateChanged();
    }
    private static boolean sameSession(MediaController first, MediaController second) {
        return first == second || (first != null && second != null
                && first.getSessionToken().equals(second.getSessionToken()));
    }
    private static String metadataIdentity(String player, MediaMetadata metadata) {
        if (metadata == null) return "";
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.isEmpty()) title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null || artist.isEmpty()) artist = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        return BackgroundTrackIdentity.key(player, metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI), title, artist,
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
    }
    private static void readAudioSession(Context context, MediaFrame frame) {
        try {
            int uid = context.getPackageManager().getApplicationInfo(frame.player, 0).uid;
            AudioManager manager = context.getSystemService(AudioManager.class);
            if (manager == null) return;
            for (Object config : manager.getActivePlaybackConfigurations()) {
                Class<?> type = config.getClass();
                if (!Boolean.TRUE.equals(type.getMethod("isActive").invoke(config))) continue;
                if (((Number) type.getMethod("getClientUid").invoke(config)).intValue() != uid) continue;
                for (String name : new String[] {"getAudioSessionId", "getSessionId", "getClientAudioSessionId"}) {
                    try {
                        frame.session = ((Number) type.getMethod(name).invoke(config)).intValue();
                        if (frame.session > 0) break;
                    } catch (ReflectiveOperationException ignored) { }
                }
                try {
                    Object devices = type.getMethod("getAudioDeviceInfos").invoke(config);
                    if (devices instanceof AudioDeviceInfo[]) {
                        StringBuilder route = new StringBuilder();
                        for (AudioDeviceInfo device : (AudioDeviceInfo[]) devices) route.append(device.getId()).append(',');
                        frame.route = route.toString();
                    }
                } catch (ReflectiveOperationException ignored) { }
                if (frame.session > 0) break;
            }
        } catch (ReflectiveOperationException | android.content.pm.PackageManager.NameNotFoundException | RuntimeException ignored) { }
    }
    private static void layout(View content, View target) {
        int width = target.getWidth(), height = target.getHeight();
        if (width <= 0 || height <= 0) return;
        if (content.getWidth() != width || content.getHeight() != height || content.isLayoutRequested()) {
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, width, height);
        }
    }
    private static final ClassValue<Map<String, java.util.Optional<Field>>> FIELD_CACHE = new ClassValue<>() {
        @Override protected Map<String, java.util.Optional<Field>> computeValue(Class<?> type) {
            return new java.util.concurrent.ConcurrentHashMap<>();
        }
    };
    static Object field(Object owner, String name) {
        if (owner == null) return null;
        java.util.Optional<Field> cached = FIELD_CACHE.get(owner.getClass()).computeIfAbsent(name, key -> {
            for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
                try { Field f = type.getDeclaredField(key); f.setAccessible(true); return java.util.Optional.of(f); }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
            return java.util.Optional.empty();
        });
        try { return cached.isPresent() ? cached.get().get(owner) : null; }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private static final class SectionSlot {
        final View target, aodOwner;
        final Object section;
        final long observedAt = SystemClock.uptimeMillis();
        boolean everAttached;
        SectionSlot(View target, View aodOwner, Object section) {
            this.target = target; this.aodOwner = aodOwner; this.section = section;
            everAttached = target.isAttachedToWindow();
        }
    }
    private final class BackdropSlot {
        final View target;
        View view;
        AdvancedBackdrop content;
        boolean failed, everAttached, overlayAdded;
        final Drawable overlay = new Drawable() {
            @Override public void draw(Canvas canvas) {
                if (content == null || !backgroundEligible(target) || !content.isReady()) return;
                if (handoffStarted == 0L) handoffStarted = SystemClock.uptimeMillis();
                float fraction = backgroundRunning(target) && options.backgroundAnimation
                        && android.animation.ValueAnimator.areAnimatorsEnabled()
                        ? Math.min(1f, (SystemClock.uptimeMillis() - handoffStarted) / 180f) : 1f;
                fraction = fraction * fraction * (3f - 2f * fraction);
                int save = fraction < 1f ? canvas.saveLayerAlpha(0, 0, target.getWidth(), target.getHeight(),
                        Math.round(255f * fraction)) : canvas.save();
                view.draw(canvas);
                canvas.restoreToCount(save);
                if (fraction < 1f && backgroundRunning(target)) target.postInvalidateOnAnimation();
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(ColorFilter filter) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
        long handoffStarted;
        final long observedAt = SystemClock.uptimeMillis();
        BackdropSlot(View target) { this.target = target; everAttached = target.isAttachedToWindow(); }
        boolean ensureContent() {
            if (content != null) return true;
            if (failed) return false;
            try {
                if (moduleContext == null) moduleContext = target.getContext().createPackageContext(
                        BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY);
                Class<?> type = Class.forName("com.naivety.zhicolor.advanced.AdvancedMusicBackdrop", true,
                        LyricBackgroundHost.class.getClassLoader());
                Object instance = type.getConstructor(Context.class, Context.class).newInstance(target.getContext(), moduleContext);
                content = (AdvancedBackdrop) instance; view = (View) instance;
                content.setInvalidateCallback(() -> {
                    if (backgroundRunning(target)) overlay.invalidateSelf();
                });
                content.setOptions(options.backgroundAnimation, options.backgroundAnimation);
                playback();
                Log.i(TAG, "advanced background ready");
                return true;
            } catch (ReflectiveOperationException | android.content.pm.PackageManager.NameNotFoundException | RuntimeException failure) {
                failed = true; Log.e(TAG, "advanced background initialization failed", failure); return false;
            }
        }
        void playback() {
            MediaFrame frame = mediaFrame;
            if (frame != null) {
                boolean current = frame.generation == mediaGeneration && sameSession(frame.controller, runtime.controller());
                content.setPlayback(frame.art, current && runtime.mediaPlaying(), current ? frame.session : 0,
                        frame.route, frame.player, frame.track);
            }
        }
        void updateOverlay(boolean visible) {
            if (visible == overlayAdded) return;
            overlayAdded = visible;
            if (visible) target.getOverlay().add(overlay);
            else target.getOverlay().remove(overlay);
        }
        void release() {
            updateOverlay(false);
            if (content != null) content.release();
            content = null; view = null; target.invalidate();
        }
    }
    private static final class MediaFrame {
        Bitmap art;
        int session;
        long generation;
        MediaController controller;
        String player = "", track = "", route = "", identity = "";
    }
}
