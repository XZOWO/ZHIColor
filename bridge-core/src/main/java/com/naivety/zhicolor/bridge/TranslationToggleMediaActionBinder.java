package com.naivety.zhicolor.bridge;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.InsetDrawable;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import com.naivety.zhicolor.bridge.diagnostics.BridgeDebugArea;
import com.naivety.zhicolor.bridge.diagnostics.StructuredBridgeLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns every OPlus media-card model operation behind the lyric translation toggle: locating the
 * player's favorite action (PlaybackState.CustomAction mapping, the OPlus heart marker, or the
 * single-action Rule0 fallback), replacing it in place, promoting it within the Rule0 custom
 * actions and presenting the enabled state.
 *
 * <p>Decision logic lives in {@link PlayerTranslationTogglePolicy}; this class only executes on
 * the media model objects owned by SystemUI, so the logic stays out of
 * {@link LockscreenLyricsModule}.</p>
 */
final class TranslationToggleMediaActionBinder {

    interface Host {
        Context currentApplicationContext();

        String logProcessName();

        boolean isLyricInfoTranslationEnabled(String packageName);

        void rememberTranslationIconFingerprint(Drawable drawable);

        void onTranslationToggleClicked(String packageName);
    }

    interface TranslationIconLoader {
        TranslationIcon load(Context context, String packageName);
    }

    private static final String TRANSLATION_ICON_RESOURCE_NAME = "ic_translation";
    private static final String SALT_DESKTOP_LYRIC_ACTION = "com.salt.music.desktop_lyrics";

    interface ActionIdReader {
        String read(Object mediaAction);
    }

    private final List<OriginalButton> originalButtons = new ArrayList<>();
    private final ActionIdReader actionIdReader;
    private final Host host;
    private final TranslationIconLoader iconLoader;
    private String lastTranslationToggleConfigLogKey = "";
    private long lastTranslationToggleConfigLogAt;

    TranslationToggleMediaActionBinder(Host host) {
        this(host, TranslationToggleMediaActionBinder::findTranslationIconInPackage);
    }

    TranslationToggleMediaActionBinder(Host host, TranslationIconLoader iconLoader) {
        this(host, iconLoader, action -> {
            PlaybackState.CustomAction custom = findPlaybackStateCustomAction(
                    invokeNoArgByName(action, "getAction"));
            return custom == null ? "" : custom.getAction();
        });
    }

    TranslationToggleMediaActionBinder(
            Host host, TranslationIconLoader iconLoader, ActionIdReader actionIdReader) {
        this.host = host;
        this.iconLoader = iconLoader;
        this.actionIdReader = actionIdReader;
    }

    synchronized void applyTranslationToggle(
            String packageName,
            Object mediaButton,
            boolean allowOverride,
            boolean userWantsButton,
            String currentProviderPackage,
            int modelTranslationCount,
            int payloadTranslationChars) {
        if (isEmpty(packageName) || mediaButton == null) {
            debug("REJECTED", "replace action skipped, package=" + nullToEmpty(packageName)
                    + ", mediaButton=" + (mediaButton != null));
            return;
        }

        Object mediaButtonEx = invokeNoArgByName(mediaButton, "getMediaButtonEx");
        Object actions = invokeNoArgByName(mediaButtonEx, "getRule0CustomActions");
        if (!(actions instanceof List)) {
            debug("REJECTED", "replace action skipped: Rule0 actions missing"
                    + ", package=" + nullToEmpty(packageName)
                    + ", mediaButtonEx=" + (mediaButtonEx == null
                    ? "null"
                    : mediaButtonEx.getClass().getName())
                    + ", actions=" + (actions == null
                    ? "null"
                    : actions.getClass().getName()));
            return;
        }
        OriginalButton original = originalButton(mediaButtonEx, (List<?>) actions);
        original.restore(mediaButtonEx);
        List<?> actionList = new ArrayList<>(original.actions);
        boolean isCurrentProvider = packageName.equals(currentProviderPackage);
        boolean hasTranslation = hasTranslationPayload(modelTranslationCount, payloadTranslationChars);
        boolean knownWithoutTranslation = isCurrentProvider && modelTranslationCount == 0
                && !hasTranslation;
        boolean canShowPublicAction = userWantsButton && isCurrentProvider && !knownWithoutTranslation;
        boolean canShowOverride = userWantsButton && isCurrentProvider && hasTranslation;
        allowOverride = allowOverride && canShowOverride;

        Object heartAction = invokeNoArgByName(mediaButtonEx, "getHeartAction");
        debug("INSPECT", "inspect Rule0 actions, package=" + nullToEmpty(packageName)
                + ", count=" + actionList.size()
                + ", isCurrentProvider=" + isCurrentProvider
                + ", hasTranslation=" + hasTranslation
                + ", canShowPublic=" + canShowPublicAction
                + ", canShowOverride=" + canShowOverride
                + ", allowOverride=" + allowOverride
                + ", heart=" + (heartAction == null
                ? "null"
                : heartAction.getClass().getName())
                + ", actions=" + describeRule0ActionIds(actionList));

        Object overrideCandidate = null;
        String overrideActionId = "";
        for (Object mediaAction : actionList) {
            String actionId = actionIdReader.read(mediaAction);
            if (isEmpty(actionId)) continue;
            boolean integrationAction =
                    LyricInfoContract.ACTION_TOGGLE_TRANSLATION.equals(actionId);
            boolean legacySaltAction = SALT_DESKTOP_LYRIC_ACTION.equals(actionId);
            if (integrationAction) {
                if (!canShowPublicAction) {
                    removeRule0Action(mediaButtonEx, actionList, mediaAction);
                    debug("Removed lyricInfo translation toggle action for " + packageName);
                    return;
                }
                configure(
                        mediaButton,
                        mediaButtonEx,
                        actionList,
                        mediaAction,
                        packageName,
                        "public",
                        actionId);
                if (PlayerTranslationTogglePolicy
                        .shouldBindOplusHeartAlongsidePublicTranslationAction(packageName)) {
                    bindOplusHeartAlongsidePublicAction(
                            mediaButtonEx,
                            mediaAction,
                            packageName);
                }
                return;
            }
            if (legacySaltAction) {
                if (!canShowOverride) {
                    debug("Left player desktop-lyric action untouched for " + packageName);
                    return;
                }
                configure(
                        mediaButton,
                        mediaButtonEx,
                        actionList,
                        mediaAction,
                        packageName,
                        "salt-legacy",
                        actionId);
                return;
            }
            if (overrideCandidate == null && allowOverride
                    && PlayerTranslationTogglePolicy.isOverrideActionCandidate(packageName, actionId)) {
                overrideCandidate = mediaAction;
                overrideActionId = actionId;
            }
        }
        if (overrideCandidate == null && !PlayerSystemUiPolicy.MD3_MUSIC.equals(packageName)) {
            overrideCandidate = findOverrideFallback(mediaButtonEx, actionList);
            overrideActionId = "";
        }
        if (overrideCandidate == null
                && allowOverride
                && hasTranslationPayload(modelTranslationCount, payloadTranslationChars)) {
            Object synthetic = createSyntheticTranslationAction(mediaButtonEx, packageName);
            if (synthetic != null) {
                ArrayList<Object> withTranslation = new ArrayList<>(actionList.size() + 1);
                withTranslation.add(synthetic);
                withTranslation.addAll(actionList);
                actionList = withTranslation;
                tryInvokeOneArgByName(mediaButtonEx, "setRule0CustomActions", withTranslation);
                writeFieldValue(mediaButtonEx, "rule0CustomActions", withTranslation);
                // Mark the extension as custom-action capable as well. Some players start with
                // an empty Rule0 list and hasCustomAction=false; leaving that bit unchanged can make the
                // OPlus binder discard the newly supplied Rule0 action on its next pass.
                setHasCustomAction(mediaButtonEx, true);
                debug("Created translation action for player without custom actions, package="
                        + nullToEmpty(packageName));
                configure(
                        mediaButton,
                        mediaButtonEx,
                        actionList,
                        synthetic,
                        packageName,
                        "public",
                        "");
                return;
            }
        }
        if (overrideCandidate != null && allowOverride) {
            configure(
                    mediaButton,
                    mediaButtonEx,
                    actionList,
                    overrideCandidate,
                    packageName,
                    "player-override",
                    overrideActionId);
            return;
        }
        debug("REJECTED", "no translation action candidate | package=" + nullToEmpty(packageName)
                + ", currentProvider=" + nullToEmpty(currentProviderPackage)
                + ", modelTranslations=" + modelTranslationCount
                + ", payloadTranslationChars=" + payloadTranslationChars);
    }

    /**
     * ColorOS 17's main-card semantic branch drops custom0 even when the semantic model has
     * our configured action. Fill that empty output slot after the native main-card mapper;
     * leave its transport/cast slots and the separate QS mapper untouched.
     */
    synchronized Object applyToMainActions(
            String packageName, Object semanticButton, Object mainActions, boolean eligible) {
        if (!eligible || semanticButton == null || mainActions == null
                || invokeNoArgByName(mainActions, "getAction0") != null) {
            return mainActions;
        }
        Object buttonEx = invokeNoArgByName(semanticButton, "getMediaButtonEx");
        Object actions = invokeNoArgByName(buttonEx, "getRule0CustomActions");
        Object translation = null;
        if (actions instanceof List) {
            for (Object action : (List<?>) actions) {
                if (isConfiguredTranslationAction(action)) {
                    translation = action;
                    break;
                }
            }
        }
        if (translation == null) {
            Object heart = invokeNoArgByName(buttonEx, "getHeartAction");
            if (isConfiguredTranslationAction(heart)) translation = heart;
        }
        if (translation == null || invokeNoArgByName(mainActions, "getAction1") == translation) {
            return mainActions;
        }
        Object[] values = {
                translation,
                invokeNoArgByName(mainActions, "getPreOrCustom"),
                invokeNoArgByName(mainActions, "getPlayPauseOrCustom"),
                invokeNoArgByName(mainActions, "getNextOrCustom"),
                invokeNoArgByName(mainActions, "getAction1")
        };
        try {
            for (Method method : mainActions.getClass().getDeclaredMethods()) {
                if (!"copy".equals(method.getName()) || method.getParameterCount() != 5) continue;
                method.setAccessible(true);
                Object copied = method.invoke(mainActions, values);
                if (copied != null && invokeNoArgByName(copied, "getAction0") == translation
                        && invokeNoArgByName(copied, "getPreOrCustom") == values[1]
                        && invokeNoArgByName(copied, "getPlayPauseOrCustom") == values[2]
                        && invokeNoArgByName(copied, "getNextOrCustom") == values[3]
                        && invokeNoArgByName(copied, "getAction1") == values[4]) {
                    debug("MAIN_ACTIONS_OUTPUT", "Filled main-card translation slot, package="
                            + nullToEmpty(packageName) + ", action=" + describeAction(translation)
                            + ", nativeOtherSlotsPreserved=true");
                    return copied;
                }
            }
        } catch (Throwable t) {
            debug("MAIN_ACTIONS_COPY_FAILED", "Main-card translation copy unavailable, package="
                    + nullToEmpty(packageName) + ", reason=" + t);
        }
        return mainActions;
    }

    private static boolean isConfiguredTranslationAction(Object action) {
        Object description = invokeNoArgByName(action, "getContentDescription");
        return description != null
                && (TranslationActionPresentationPolicy.contentDescription(false).contentEquals(description.toString())
                || TranslationActionPresentationPolicy.contentDescription(true).contentEquals(description.toString()))
                && invokeNoArgByName(action, "getAction") instanceof Runnable;
    }

    private static boolean hasTranslationPayload(int modelTranslationCount, int payloadTranslationChars) {
        return modelTranslationCount > 0 || payloadTranslationChars > 0;
    }

    /**
     * When a player exposes neither PlaybackState.CustomAction nor an OPlus heart action, build
     * the same SystemUI MediaAction type that the framework uses for public custom actions so its
     * click is still handled entirely inside the Bridge.
     */
    private Object createSyntheticTranslationAction(Object mediaButtonEx, String packageName) {
        Context context = host.currentApplicationContext();
        TranslationIcon translationIcon = findTranslationIcon(context, packageName);
        if (mediaButtonEx == null || translationIcon == null || translationIcon.icon == null) {
            return null;
        }
        try {
            ClassLoader loader = mediaButtonEx.getClass().getClassLoader();
            Class<?> actionExClass = Class.forName(
                    "com.android.systemui.media.controls.models.player.OplusMediaActionEx",
                    true,
                    loader);
            Object actionEx = actionExClass.getDeclaredConstructor(
                    boolean.class,
                    Icon.class,
                    android.net.Uri.class,
                    boolean.class,
                    Boolean.class,
                    boolean.class,
                    String.class).newInstance(
                    false,
                    translationIcon.icon,
                    null,
                    false,
                    null,
                    false,
                    null);
            Class<?> actionClass = Class.forName(
                    "com.android.systemui.media.controls.shared.model.MediaAction",
                    true,
                    loader);
            Runnable click = () -> {
                boolean before = host.isLyricInfoTranslationEnabled(packageName);
                debug("synthetic translation action clicked, package="
                        + nullToEmpty(packageName) + ", enabledBefore=" + before);
                host.onTranslationToggleClicked(packageName);
            };
            return actionClass.getDeclaredConstructor(
                    Drawable.class,
                    Runnable.class,
                    CharSequence.class,
                    Drawable.class,
                    Integer.class,
                    actionExClass).newInstance(
                    translationIcon.drawable,
                    click,
                    TranslationActionPresentationPolicy.contentDescription(
                            host.isLyricInfoTranslationEnabled(packageName)),
                    null,
                    null,
                    actionEx);
        } catch (Throwable t) {
            debug("synthetic translation action unavailable, package="
                    + nullToEmpty(packageName) + ", reason=" + t.getClass().getSimpleName());
            return null;
        }
    }

    // OPlus models have mutable structural hashCode/equals; key snapshots by weak identity.
    private OriginalButton originalButton(Object button, List<?> actions) {
        for (int i = originalButtons.size() - 1; i >= 0; i--) {
            OriginalButton original = originalButtons.get(i);
            Object owner = original.owner.get();
            if (owner == button) return original;
            if (owner == null) originalButtons.remove(i);
        }
        OriginalButton original = new OriginalButton(button, actions);
        originalButtons.add(original);
        return original;
    }

    private static final class OriginalButton {
        final WeakReference<Object> owner;
        final List<Object> actions;
        final List<OriginalAction> originals = new ArrayList<>();
        final Object hasCustomAction;

        OriginalButton(Object button, List<?> actions) {
            owner = new WeakReference<>(button);
            this.actions = new ArrayList<>(actions);
            Object customAction = invokeNoArgByName(button, "getHasCustomAction");
            hasCustomAction = customAction instanceof Boolean
                    ? customAction : readFieldValue(button, "hasCustomAction");
            for (Object action : actions) originals.add(new OriginalAction(action));
            Object heart = invokeNoArgByName(button, "getHeartAction");
            if (heart != null && actions.stream().noneMatch(action -> action == heart)) {
                originals.add(new OriginalAction(heart));
            }
        }

        void restore(Object button) {
            for (OriginalAction original : originals) original.restore();
            tryInvokeOneArgByName(button, "setRule0CustomActions", new ArrayList<>(actions));
            if (hasCustomAction instanceof Boolean) {
                setHasCustomAction(button, (Boolean) hasCustomAction);
            }
        }
    }

    private static final class OriginalAction {
        final Object target;
        final Object runnable;
        final Object description;
        final Object drawable;
        final Object semanticIcon;
        final int alpha;

        OriginalAction(Object target) {
            this.target = target;
            runnable = invokeNoArgByName(target, "getAction");
            description = invokeNoArgByName(target, "getContentDescription");
            drawable = invokeNoArgByName(target, "getIcon");
            semanticIcon = readFieldValue(invokeNoArgByName(target, "getMediaActionEx"), "icon");
            alpha = drawable instanceof Drawable ? ((Drawable) drawable).getAlpha() : 255;
        }

        void restore() {
            tryInvokeOneArgByName(target, "setAction", runnable);
            tryInvokeOneArgByName(target, "setContentDescription", description);
            tryInvokeOneArgByName(target, "setIcon", drawable);
            if (drawable instanceof Drawable) ((Drawable) drawable).setAlpha(alpha);
            writeFieldValue(invokeNoArgByName(target, "getMediaActionEx"), "icon", semanticIcon);
        }
    }

    private void removeRule0Action(Object mediaButtonEx, List<?> actions, Object target) {
        if (actions == null || actions.isEmpty() || target == null) {
            return;
        }
        ArrayList<Object> remaining = new ArrayList<>(actions.size());
        boolean removed = false;
        for (Object action : actions) {
            if (action == target) {
                removed = true;
            } else {
                remaining.add(action);
            }
        }
        if (!removed) {
            return;
        }
        tryInvokeOneArgByName(mediaButtonEx, "setRule0CustomActions", remaining);
        writeFieldValue(mediaButtonEx, "rule0CustomActions", remaining);
    }

    /**
     * QQ Music and KuGou (original) expose their favorite without a PlaybackState.CustomAction
     * backing the Rule0 action. Locate it through the OPlus heart marker first, then fall back to
     * the single-action Rule0 list those players keep.
     */
    private Object findOverrideFallback(Object mediaButtonEx, List<?> actions) {
        Object heartAction = invokeNoArgByName(mediaButtonEx, "getHeartAction");
        if (heartAction != null) {
            debug("override fallback located favorite via OPlus heart action"
                    + ", action=" + heartAction.getClass().getName());
            return heartAction;
        }
        if (actions != null && actions.size() == 1) {
            debug("override fallback located single Rule0 action");
            return actions.get(0);
        }
        return null;
    }

    /**
     * Copy translation presentation onto the OPlus heart without promoting it into Rule0.
     * Poweramp's visible favorite slot after a pause rebuild is that heart, not Rule0[0].
     */
    void bindOplusHeartAlongsidePublicAction(
            Object mediaButtonEx,
            Object publicMediaAction,
            String packageName) {
        Object heartAction = invokeNoArgByName(mediaButtonEx, "getHeartAction");
        if (heartAction == null) {
            debug("public translation action present, OPlus heart absent, package="
                    + nullToEmpty(packageName));
            return;
        }
        if (heartAction == publicMediaAction) {
            debug("OPlus heart is the public translation action, package="
                    + nullToEmpty(packageName));
            return;
        }
        debug("bind OPlus heart alongside public translation action, package="
                + nullToEmpty(packageName)
                + ", heart=" + heartAction.getClass().getName());
        if (!replaceMediaActionIcon(heartAction, packageName)) {
            rememberCurrentMediaActionIcon(heartAction);
        }
        present(heartAction, packageName);
        tryInvokeOneArgByName(heartAction, "setAction", (Runnable) () -> {
            boolean before = host.isLyricInfoTranslationEnabled(packageName);
            debug("translation action clicked, package=" + nullToEmpty(packageName)
                    + ", slot=oplus-heart"
                    + ", enabledBefore=" + before);
            host.onTranslationToggleClicked(packageName);
            present(heartAction, packageName);
            debug("translation action click applied, package=" + nullToEmpty(packageName)
                    + ", slot=oplus-heart"
                    + ", enabledAfter=" + host.isLyricInfoTranslationEnabled(packageName));
        });
        maybeLogConfiguredTranslationMediaAction(packageName, "public-heart", "", null, mediaButtonEx);
    }

    private void configure(
            Object mediaButton,
            Object mediaButtonEx,
            List<?> actions,
            Object mediaAction,
            String packageName,
            String protocol,
            String originalActionId) {
        boolean publicProtocol = "public".equals(protocol);
        boolean overrideProtocol = "player-override".equals(protocol);
        boolean enabledBefore = host.isLyricInfoTranslationEnabled(packageName);
        debug("CONFIGURE", "configure translation action, package=" + nullToEmpty(packageName)
                + ", protocol=" + protocol
                + ", originalAction=" + nullToEmpty(originalActionId)
                + ", enabledBefore=" + enabledBefore
                + ", mediaAction=" + (mediaAction == null
                ? "null"
                : mediaAction.getClass().getName()));
        if (publicProtocol || overrideProtocol) {
            OriginalAction before = new OriginalAction(mediaAction);
            if (!replaceMediaActionIcon(mediaAction, packageName)) {
                before.restore();
                if (publicProtocol) {
                    rememberCurrentMediaActionIcon(mediaAction);
                }
                debug("REJECTED", "translation action kept unchanged: semantic resource icon was not applied"
                        + ", package=" + nullToEmpty(packageName) + ", protocol=" + protocol);
                return;
            }
            promoteTranslationToggleAction(mediaButtonEx, actions, mediaAction, packageName);
        } else {
            replaceMediaActionIcon(mediaAction, packageName);
        }

        present(mediaAction, packageName);
        tryInvokeOneArgByName(mediaAction, "setAction", (Runnable) () -> {
            boolean before = host.isLyricInfoTranslationEnabled(packageName);
            debug("translation action clicked, package=" + nullToEmpty(packageName)
                    + ", enabledBefore=" + before);
            host.onTranslationToggleClicked(packageName);
            present(mediaAction, packageName);
            debug("translation action click applied, package=" + nullToEmpty(packageName)
                    + ", enabledAfter=" + host.isLyricInfoTranslationEnabled(packageName));
        });
        maybeLogConfiguredTranslationMediaAction(
                packageName, protocol, originalActionId, mediaButton, mediaButtonEx);
    }

    private void promoteTranslationToggleAction(
            Object mediaButtonEx, List<?> actions, Object translationAction, String packageName) {
        if (actions.isEmpty()) {
            // A player can have an OPlus heart without any public custom actions. Replacing
            // that heart alone leaves Rule0 empty, so the lock-screen binder never exposes it.
            // Only fill an empty list; populated action rows retain their original slot count.
            if (!PlayerSystemUiPolicy.preservesNativeActionRow(packageName)) {
                ArrayList<Object> supplied = new ArrayList<>(1);
                supplied.add(translationAction);
                tryInvokeOneArgByName(mediaButtonEx, "setRule0CustomActions", supplied);
                writeFieldValue(mediaButtonEx, "rule0CustomActions", supplied);
                setHasCustomAction(mediaButtonEx, true);
            }
            return;
        }
        if (actions.get(0) == translationAction || PlayerSystemUiPolicy.preservesNativeActionRow(packageName)) {
            debug("promote translation action skipped for " + packageName);
            return;
        }
        // If translationAction is not in actions (e.g. it is an OPlus heartAction),
        // do NOT inject it into rule0CustomActions to avoid inflating the action list and displacing buttons.
        if (!actions.contains(translationAction)) {
            debug("promote translation action skipped: action is not in rule0CustomActions, package=" + packageName);
            return;
        }
        ArrayList<Object> ordered = new ArrayList<>(actions.size());
        ordered.add(translationAction);
        for (Object action : actions) {
            if (action != translationAction) {
                // MD3's public translation replaces its desktop-lyric slot; keep favorite in QS slot 2.
                if (PlayerSystemUiPolicy.MD3_MUSIC.equals(packageName)
                        && "com.md3music.toggle_desktop_lyric".equals(actionIdReader.read(action))) continue;
                ordered.add(action);
            }
        }
        tryInvokeOneArgByName(mediaButtonEx, "setRule0CustomActions", ordered);
        Object applied = invokeNoArgByName(mediaButtonEx, "getRule0CustomActions");
        if (!(applied instanceof List)
                || ((List<?>) applied).isEmpty()
                || ((List<?>) applied).get(0) != translationAction) {
            writeFieldValue(mediaButtonEx, "rule0CustomActions", ordered);
        }
        debug("Promoted lyricInfo translation toggle within OPlus Rule0 custom actions");
    }

    private static void setHasCustomAction(Object button, boolean value) {
        tryInvokeOneArgByName(button, "setHasCustomAction", value);
        writeFieldValue(button, "hasCustomAction", value);
    }

    private void rememberCurrentMediaActionIcon(Object mediaAction) {
        Object icon = invokeNoArgByName(mediaAction, "getIcon");
        if (icon instanceof Drawable) {
            host.rememberTranslationIconFingerprint((Drawable) icon);
        }
    }

    private boolean replaceMediaActionIcon(Object mediaAction, String packageName) {
        Context context = host.currentApplicationContext();
        if (mediaAction == null || isEmpty(packageName)) {
            return false;
        }
        try {
            TranslationIcon translationIcon = findTranslationIcon(context, packageName);
            debug("ICON_LOOKUP", "replace media action icon, package=" + nullToEmpty(packageName)
                    + ", foundIcon=" + (translationIcon != null)
                    + ", action=" + mediaAction.getClass().getName());
            if (translationIcon == null
                    || translationIcon.icon == null) {
                debug("REJECTED", "replace media action icon skipped: resource-backed semantic icon missing"
                        + ", package=" + nullToEmpty(packageName));
                return false;
            }
            Drawable drawable = translationIcon.drawable;
            debug("ICON_DRAWABLE", "replace media action icon drawable=" + (drawable != null)
                    + ", drawableClass=" + className(drawable)
                    + ", action=" + mediaAction.getClass().getName());
            if (drawable != null) {
                drawable = drawable.mutate();
                host.rememberTranslationIconFingerprint(drawable);
                tryInvokeOneArgByName(mediaAction, "setIcon", drawable);
                Object appliedIcon = invokeNoArgByName(mediaAction, "getIcon");
                debug("ICON_APPLIED", "replace media action icon applied=" + (appliedIcon == drawable)
                        + ", appliedClass=" + (appliedIcon == null
                        ? "null"
                        : appliedIcon.getClass().getName()));
            }
            Object mediaActionEx = invokeNoArgByName(mediaAction, "getMediaActionEx");
            boolean written = writeFieldValue(mediaActionEx, "icon", translationIcon.icon);
            Object appliedSemanticIcon = written ? readFieldValue(mediaActionEx, "icon") : null;
            boolean identical = written && appliedSemanticIcon == translationIcon.icon;
            debug("ICON_SEMANTIC", "replace semantic icon, package=" + nullToEmpty(packageName)
                    + ", mediaActionEx=" + className(mediaActionEx)
                    + ", written=" + written
                    + ", readBackIdentical=" + identical
                    + ", requestedClass=" + className(translationIcon.icon)
                    + ", appliedClass=" + className(appliedSemanticIcon));
            return identical;
        } catch (Throwable t) {
            StructuredBridgeLog.error("Failed to load lyric translation icon: " + t, t);
            return false;
        }
    }

    static final class TranslationIcon {
        final Object icon;
        final Drawable drawable;

        TranslationIcon(Object icon, Drawable drawable) {
            this.icon = icon;
            this.drawable = drawable;
        }
    }

    private TranslationIcon findTranslationIcon(
            Context context, String providerPackage) {
        // Host-player packages can expose unrelated resources with the same generic name and
        // different theme tints. Keep one canonical white action icon across every v5 player.
        return iconLoader.load(
                context,
                TranslationActionPresentationPolicy.CANONICAL_ICON_PACKAGE);
    }

    private static TranslationIcon findTranslationIconInPackage(
            Context context, String packageName) {
        if (context == null || isEmpty(packageName)) {
            debug("ICON_LOOKUP_CONTEXT", "canonical icon context missing, package="
                    + nullToEmpty(packageName) + ", context=" + className(context));
            return null;
        }
        try {
            Context packageContext = context.createPackageContext(
                    packageName,
                    Context.CONTEXT_IGNORE_SECURITY);
            Resources resources = packageContext.getResources();
            int resourceId = resources.getIdentifier(
                    TRANSLATION_ICON_RESOURCE_NAME,
                    "drawable",
                    packageName);
            if (resourceId == 0) {
                debug("ICON_LOOKUP_RESOURCE0", "canonical icon resource missing, package=" + packageName
                        + ", context=" + context.getPackageName()
                        + ", packageContext=" + packageContext.getPackageName());
                return null;
            }
            Drawable drawable = packageContext.getDrawable(resourceId);
            if (drawable == null) {
                debug("ICON_LOOKUP_DRAWABLE_NULL", "canonical icon drawable missing, package=" + packageName
                        + ", resourceId=" + resourceId);
                return null;
            }
            drawable = drawable.mutate();
            drawable.setTint(Color.WHITE);
            Icon semanticIcon = Icon.createWithResource(packageContext, resourceId);
            return new TranslationIcon(
                    semanticIcon,
                    drawable);
        } catch (Throwable error) {
            debug("ICON_LOOKUP_EXCEPTION", "canonical icon lookup failed, package=" + packageName
                    + ", context=" + className(context)
                    + ", error=" + error.getClass().getName()
                    + ", message=" + nullToEmpty(error.getMessage()));
            return null;
        }
    }

    private void present(Object mediaAction, String packageName) {
        boolean enabled = host.isLyricInfoTranslationEnabled(packageName);
        debug("update action presentation, package=" + nullToEmpty(packageName)
                + ", enabled=" + enabled
                + ", action=" + (mediaAction == null
                ? "null"
                : mediaAction.getClass().getName()));
        tryInvokeOneArgByName(
                mediaAction,
                "setContentDescription",
                TranslationActionPresentationPolicy.contentDescription(enabled));
        Object icon = invokeNoArgByName(mediaAction, "getIcon");
        if (icon instanceof Drawable) {
            ((Drawable) icon).setAlpha(
                    TranslationActionPresentationPolicy.imageAlpha(enabled));
            ((Drawable) icon).invalidateSelf();
        }
    }

    static boolean isSystemUiSafeSemanticIconType(int iconType) {
        // OPlusMediaViewPagerAdapter#setSemanticButton unconditionally calls getResPackage()
        // for every non-null OplusMediaActionEx icon. Framework Icon throws for BITMAP, DATA,
        // URI and adaptive-bitmap types, so this high-impact SystemUI path must be resource-only.
        return iconType == Icon.TYPE_RESOURCE;
    }

    Drawable createTranslationActionPresentationDrawable(
            String providerPackage,
            boolean enabled) {
        TranslationIcon translationIcon = findTranslationIcon(
                host.currentApplicationContext(),
                providerPackage);
        if (translationIcon == null || translationIcon.drawable == null) return null;
        Drawable drawable = translationIcon.drawable.mutate();
        drawable.setAlpha(TranslationActionPresentationPolicy.imageAlpha(enabled));
        return new InsetDrawable(
                drawable,
                TranslationActionPresentationPolicy.ACTION_ICON_INSET_FRACTION);
    }

    private void maybeLogConfiguredTranslationMediaAction(
            String packageName, String protocol, String originalActionId,
            Object mediaButton, Object mediaButtonEx) {
        String actionId = isEmpty(originalActionId) ? "" : originalActionId;
        String logKey = packageName + "|" + protocol + "|" + actionId;
        long now = SystemClock.elapsedRealtime();
        if (logKey.equals(lastTranslationToggleConfigLogKey)
                && now - lastTranslationToggleConfigLogAt
                < LyricTimingTuningConstants.Translation.TOGGLE_CONFIG_LOG_THROTTLE_MS) {
            return;
        }
        lastTranslationToggleConfigLogKey = logKey;
        lastTranslationToggleConfigLogAt = now;
        StructuredBridgeLog.debug(BridgeDebugArea.PLAYER_SPECIAL, "CONFIGURED", () -> {
            Object actions = invokeNoArgByName(mediaButtonEx, "getRule0CustomActions");
            Object first = actions instanceof List && !((List<?>) actions).isEmpty()
                    ? ((List<?>) actions).get(0) : null;
            Object heart = invokeNoArgByName(mediaButtonEx, "getHeartAction");
            Object custom0 = invokeNoArgByName(mediaButton, "getCustom0");
            return "Configured lyricInfo translation toggle for " + packageName
                    + ", protocol=" + protocol
                    + (isEmpty(actionId) ? "" : ", originalAction=" + actionId)
                    + ", rule0[0]=" + describeAction(first)
                    + ", heart=" + describeAction(heart)
                    + ", custom0=" + (mediaButton == null ? "unavailable" : describeAction(custom0));
        });
    }

    private static PlaybackState.CustomAction findPlaybackStateCustomAction(Object runnable) {
        if (runnable == null) {
            return null;
        }
        Class<?> current = runnable.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(runnable);
                    if (value instanceof PlaybackState.CustomAction) {
                        return (PlaybackState.CustomAction) value;
                    }
                } catch (Throwable ignored) {
                    // Continue through synthetic fields and superclass fields.
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static String describeRule0ActionIds(List<?> actions) {
        if (actions == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder("[");
        int limit = Math.min(actions.size(), 8);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            Object mediaAction = actions.get(i);
            Object runnable = invokeNoArgByName(mediaAction, "getAction");
            PlaybackState.CustomAction customAction = findPlaybackStateCustomAction(runnable);
            if (customAction != null) {
                builder.append(customAction.getAction());
            } else {
                builder.append(mediaAction == null
                        ? "null"
                        : mediaAction.getClass().getSimpleName());
            }
        }
        if (actions.size() > limit) {
            builder.append(", +").append(actions.size() - limit);
        }
        return builder.append(']').toString();
    }

    private static Object invokeNoArgByName(Object target, String methodName) {
        if (target == null || isEmpty(methodName)) {
            return null;
        }
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static void tryInvokeOneArgByName(
            Object target, String methodName, Object arg) {
        if (target == null || isEmpty(methodName)) {
            return;
        }
        Class<?> current = target.getClass();
        while (current != null) {
            Method[] methods = current.getDeclaredMethods();
            for (Method method : methods) {
                if (!methodName.equals(method.getName())
                        || method.getParameterTypes().length != 1) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    method.invoke(target, arg);
                    return;
                } catch (Throwable ignored) {
                    // Try another overload or superclass implementation.
                }
            }
            current = current.getSuperclass();
        }
    }

    private static boolean writeFieldValue(Object target, String fieldName, Object value) {
        if (target == null || isEmpty(fieldName)) {
            return false;
        }
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return true;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static Object readFieldValue(Object target, String fieldName) {
        if (target == null || isEmpty(fieldName)) {
            return null;
        }
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private void debug(String message) {
        debug("BUTTON", message);
    }

    private static void debug(String event, String message) {
        StructuredBridgeLog.debug(
                BridgeDebugArea.PLAYER_SPECIAL,
                event,
                () -> message);
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String describeAction(Object action) {
        Object description = invokeNoArgByName(action, "getContentDescription");
        if (description == null) description = readFieldValue(action, "contentDescription");
        return className(action) + "{contentDescription=" + String.valueOf(description) + "}";
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
