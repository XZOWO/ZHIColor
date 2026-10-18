package com.naivety.zhicolor.bridge;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Resolves obfuscated OPlus plugin targets (media-model classes and the immersive lyric position
 * controller) without relying on {@code m6.*} or other obfuscated names.
 */
final class OplusPluginDexKitAdapter {
    private static final Object DEXKIT_LOAD_LOCK = new Object();

    private static volatile boolean dexKitLoaded;

    private OplusPluginDexKitAdapter() {
    }

    /**
     * Resolves every plugin target in one DexKit scan. The media-model targets and the lyric
     * position controller fail independently, so a missing one never costs the other.
     */
    @SuppressLint("DuplicateCreateDexKit")
    static Resolution resolveAll(ClassLoader classLoader, String lyricsRecyclerViewClass) {
        ensureDexKitLoaded();
        //noinspection DuplicateCreateDexKit -- this plugin ClassLoader is scanned once.
        try (DexKitBridge bridge = DexKitBridge.create(classLoader, true)) {
            Method controller = null;
            Throwable controllerFailure = null;
            try {
                controller = findLyricPositionController(bridge, classLoader, lyricsRecyclerViewClass);
            } catch (Throwable t) {
                controllerFailure = t;
            }
            Targets targets = null;
            Throwable targetsFailure = null;
            try {
                targets = resolveTargets(bridge, classLoader);
            } catch (Throwable t) {
                targetsFailure = t;
            }
            return new Resolution(targets, targetsFailure, controller, controllerFailure);
        }
    }

    private static Targets resolveTargets(DexKitBridge bridge, ClassLoader classLoader)
            throws ReflectiveOperationException {
        Class<?> mediaModelClass = findOptionalSingleClass(
                bridge,
                classLoader,
                "MediaModel",
                "MediaModel(uniqueId=",
                ", lyricModel=",
                ", isLyricSupported=");
        boolean mediaInfoLayout = mediaModelClass == null;
        if (mediaInfoLayout) {
            mediaModelClass = findSingleClass(bridge, classLoader, "MediaInfo",
                    "MediaInfo(uniqueId=", ", lyricModel=", ", isLyricSupported=",
                    ", primaryColor=", ", artworkFullBgEnable=");
        }
        Class<?> multiIconClass = findSingleClass(
                bridge,
                classLoader,
                "MultiIconModel",
                "MultiIconModel(staticIcon=",
                ", lottieIcon=");
        Class<?> staticIconClass = mediaInfoLayout
                ? findSingleClass(bridge, classLoader, "StaticIcon",
                        "StaticIcon(drawable=", ", iconModelForCard=", ", artResult=")
                : findSingleClass(bridge, classLoader, "StaticIcon",
                        "StaticIcon(icon=", ", iconModelForCard=");
        Class<?> normalIconClass = findSingleClass(
                bridge,
                classLoader,
                "NormalIcon",
                "NormalIcon(icon=",
                ", primaryColor=");
        Class<?> lottieIconClass = findSingleClass(
                bridge,
                classLoader,
                "LottieIcon",
                "LottieIcon[assetName: ",
                " repeatCount=");
        Class<?> lyricModelClass = findSingleClass(
                bridge,
                classLoader,
                "LyricModel",
                "LyricModel(lines=");
        return bindResolvedClasses(
                mediaModelClass,
                multiIconClass,
                staticIconClass,
                normalIconClass,
                lottieIconClass,
                lyricModelClass,
                true,
                mediaInfoLayout);
    }

    /**
     * The immersive controller entry {@code void (Long position, boolean animate)} that forwards
     * to the recycler's timed {@code (boolean, long)} method and schedules the next row change
     * from the same position. Matched by shape and call, never by its obfuscated name.
     */
    private static Method findLyricPositionController(
            DexKitBridge bridge,
            ClassLoader classLoader,
            String lyricsRecyclerViewClass) throws ReflectiveOperationException {
        MethodDataList methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                        .paramTypes(Long.class, boolean.class)
                        .returnType(void.class)
                        .addInvoke(MethodMatcher.create()
                                .declaredClass(lyricsRecyclerViewClass)
                                .paramTypes(boolean.class, long.class)
                                .returnType(void.class))));
        if (methods.size() != 1) {
            throw new IllegalStateException(
                    "Expected one lyric position controller, found " + methods.size());
        }
        Method method = methods.get(0).getMethodInstance(classLoader);
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException("Lyric position controller is static: " + method);
        }
        method.setAccessible(true);
        return method;
    }

    static Targets legacy(ClassLoader classLoader) throws ReflectiveOperationException {
        return bindResolvedClasses(
                classLoader.loadClass("m6.t"),
                classLoader.loadClass("m6.v"),
                classLoader.loadClass("m6.z"),
                classLoader.loadClass("m6.i"),
                classLoader.loadClass("m6.q"),
                classLoader.loadClass("m6.s"),
                false);
    }

    static Targets bindResolvedClasses(
            Class<?> mediaModelClass,
            Class<?> multiIconClass,
            Class<?> staticIconClass,
            Class<?> normalIconClass,
            Class<?> lottieIconClass,
            Class<?> lyricModelClass,
            boolean resolvedByDexKit) throws ReflectiveOperationException {
        return bindResolvedClasses(mediaModelClass, multiIconClass, staticIconClass,
                normalIconClass, lottieIconClass, lyricModelClass, resolvedByDexKit, false);
    }

    static Targets bindResolvedClasses(
            Class<?> mediaModelClass,
            Class<?> multiIconClass,
            Class<?> staticIconClass,
            Class<?> normalIconClass,
            Class<?> lottieIconClass,
            Class<?> lyricModelClass,
            boolean resolvedByDexKit,
            boolean mediaInfoLayout) throws ReflectiveOperationException {
        Class<?> iconModelClass = normalIconClass.getSuperclass();
        if (iconModelClass == null || iconModelClass == Object.class) {
            throw new IllegalStateException("NormalIcon has no icon-model superclass");
        }
        Field lyricSupportedField = mediaInfoLayout
                ? requireColorOs17MediaInfoLayout(mediaModelClass, multiIconClass, lyricModelClass)
                : requireLastField(mediaModelClass, boolean.class);
        Field albumArtField = mediaInfoLayout
                ? requireDeclaredField(mediaModelClass, "d", multiIconClass)
                : requireField(mediaModelClass, multiIconClass, 0);
        Field lyricModelField = mediaInfoLayout
                ? requireDeclaredField(mediaModelClass, "p", lyricModelClass)
                : requireField(mediaModelClass, lyricModelClass, 0);
        Field staticDrawableField = requireField(staticIconClass, Drawable.class, 0);
        Field staticBitmapField = requireField(staticIconClass, Bitmap.class, 0);
        Field cardIconModelField;
        Field staticIconField = null;
        Field lottieIconField = null;
        Field staticArtworkResultField = null;
        Method staticIconGetter = null;
        Method lottieIconGetter = null;
        Constructor<?> staticIconConstructor;
        if (mediaInfoLayout) {
            requireInstanceFieldCount(multiIconClass, 2);
            staticIconField = requireDeclaredField(multiIconClass, "a", staticIconClass);
            lottieIconField = requireDeclaredField(multiIconClass, "b", lottieIconClass);
            requireInstanceFieldCount(staticIconClass, 5);
            requireDeclaredField(staticIconClass, "a", Drawable.class);
            requireDeclaredField(staticIconClass, "b", Bitmap.class);
            requireDeclaredField(staticIconClass, "c", iconModelClass);
            cardIconModelField = requireDeclaredField(staticIconClass, "d", normalIconClass);
            staticArtworkResultField = requireDeclaredField(staticIconClass, "e", null);
            Class<?> artworkResultClass = staticArtworkResultField.getType();
            Class<?> artColorsClass = requireDeclaredField(mediaModelClass, "r", null).getType();
            requireInstanceFieldCount(artworkResultClass, 2);
            requireDeclaredField(artworkResultClass, "a", Bitmap.class);
            requireDeclaredField(artworkResultClass, "b", artColorsClass);
            requireConstructor(artworkResultClass, Bitmap.class, artColorsClass);
            staticIconConstructor = requireConstructor(staticIconClass, Drawable.class, Bitmap.class,
                    iconModelClass, normalIconClass, artworkResultClass);
        } else {
            requireField(staticIconClass, Icon.class, 0);
            requireField(staticIconClass, iconModelClass, 0);
            cardIconModelField = requireField(staticIconClass, iconModelClass, 1);
            staticIconGetter = requireZeroArgMethod(multiIconClass, staticIconClass);
            lottieIconGetter = requireZeroArgMethod(multiIconClass, lottieIconClass);
            staticIconConstructor = requireConstructor(staticIconClass, Icon.class, Drawable.class,
                    Bitmap.class, iconModelClass, iconModelClass);
        }
        Method bitmapGetter = requireZeroArgMethod(iconModelClass, Bitmap.class);
        Method colorGetter = requireZeroArgMethod(iconModelClass, Integer.class);
        Constructor<?> normalIconConstructor = requireConstructor(
                normalIconClass,
                Bitmap.class,
                Integer.class);
        Constructor<?> multiIconConstructor = requireConstructor(
                multiIconClass,
                staticIconClass,
                lottieIconClass);
        return new Targets(
                mediaModelClass,
                albumArtField,
                lyricModelField,
                lyricSupportedField,
                staticIconGetter,
                lottieIconGetter,
                staticDrawableField,
                staticBitmapField,
                cardIconModelField,
                bitmapGetter,
                colorGetter,
                staticIconConstructor,
                normalIconConstructor,
                multiIconConstructor,
                resolvedByDexKit,
                staticIconField,
                lottieIconField,
                staticArtworkResultField,
                !mediaInfoLayout);
    }

    /** Verified 17.000.002 layout: q owns lyric support; s owns artwork background. */
    private static Field requireColorOs17MediaInfoLayout(
            Class<?> owner, Class<?> multiIconClass, Class<?> lyricModelClass)
            throws ReflectiveOperationException {
        requireInstanceFieldCount(owner, 20);
        for (String name : new String[]{"a", "b", "e", "h", "i", "k", "l", "m"}) {
            requireDeclaredField(owner, name, String.class);
        }
        requireDeclaredField(owner, "c", PendingIntent.class);
        for (String name : new String[]{"d", "f", "j"}) {
            requireDeclaredField(owner, name, multiIconClass);
        }
        requireDeclaredField(owner, "g", LinkedHashMap.class);
        requireDeclaredField(owner, "n", List.class);
        requireDeclaredField(owner, "o", List.class);
        requireDeclaredField(owner, "p", lyricModelClass);
        Field supported = requireDeclaredField(owner, "q", boolean.class);
        Class<?> artColors = requireDeclaredField(owner, "r", null).getType();
        requireInstanceFieldCount(artColors, 7);
        for (String name : new String[]{"a", "b", "c", "d", "e", "f", "g"}) {
            requireDeclaredField(artColors, name, int.class);
        }
        requireConstructor(artColors, int.class, int.class, int.class, int.class,
                int.class, int.class, int.class);
        requireDeclaredField(owner, "s", boolean.class);
        requireDeclaredField(owner, "t", Object.class);
        requireConstructor(owner, String.class, String.class, PendingIntent.class, multiIconClass,
                String.class, multiIconClass, LinkedHashMap.class, String.class, String.class,
                multiIconClass, String.class, String.class, String.class, List.class, List.class,
                lyricModelClass, boolean.class, boolean.class);
        return supported;
    }

    private static void requireInstanceFieldCount(Class<?> owner, int expected) {
        int count = 0;
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) count++;
        }
        if (owner.getSuperclass() != Object.class || count != expected) {
            throw new IllegalStateException("Unverified plugin field layout: " + owner.getName());
        }
    }

    private static Field requireDeclaredField(Class<?> owner, String name, Class<?> type) {
        try {
            Field field = owner.getDeclaredField(name);
            if (Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())
                    || (type != null && field.getType() != type)) {
                throw new IllegalStateException("Unverified plugin field: " + owner.getName() + "#" + name);
            }
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException failure) {
            throw new IllegalStateException("Missing plugin field: " + owner.getName() + "#" + name, failure);
        }
    }

    private static Class<?> findSingleClass(
            DexKitBridge bridge,
            ClassLoader classLoader,
            String description,
            String... anchors) throws ReflectiveOperationException {
        Class<?> match = findOptionalSingleClass(bridge, classLoader, description, anchors);
        if (match == null) {
            throw new IllegalStateException("Missing " + description);
        }
        return match;
    }

    private static Class<?> findOptionalSingleClass(
            DexKitBridge bridge, ClassLoader classLoader, String description, String... anchors)
            throws ReflectiveOperationException {
        ClassDataList classes = bridge.findClass(FindClass.create()
                .matcher(ClassMatcher.create().usingEqStrings(anchors)));
        if (classes.size() > 1) {
            throw new IllegalStateException(
                    "Expected one " + description + ", found " + classes.size() + ": " + classes);
        }
        return classes.isEmpty() ? null : classes.get(0).getInstance(classLoader);
    }

    private static Field requireField(Class<?> owner, Class<?> fieldType, int ordinal) {
        List<Field> matches = fields(owner, fieldType);
        if (ordinal >= matches.size()) {
            throw new IllegalStateException(
                    "Missing field " + fieldType.getName() + "[" + ordinal + "] in " + owner.getName());
        }
        Field field = matches.get(ordinal);
        field.setAccessible(true);
        return field;
    }

    private static Field requireLastField(Class<?> owner, Class<?> fieldType) {
        List<Field> matches = fields(owner, fieldType);
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "Missing field " + fieldType.getName() + " in " + owner.getName());
        }
        Field field = matches.get(matches.size() - 1);
        field.setAccessible(true);
        return field;
    }

    private static List<Field> fields(Class<?> owner, Class<?> fieldType) {
        ArrayList<Field> matches = new ArrayList<>();
        Class<?> current = owner;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType() == fieldType) {
                    matches.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return matches;
    }

    private static Method requireZeroArgMethod(Class<?> owner, Class<?> returnType) {
        Method match = null;
        Class<?> current = owner;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 0
                        || method.getReturnType() != returnType) {
                    continue;
                }
                if (match != null) {
                    throw new IllegalStateException(
                            "Ambiguous zero-arg " + returnType.getName() + " method in " + owner.getName());
                }
                match = method;
            }
            current = current.getSuperclass();
        }
        if (match == null) {
            throw new IllegalStateException(
                    "Missing zero-arg " + returnType.getName() + " method in " + owner.getName());
        }
        match.setAccessible(true);
        return match;
    }

    private static Constructor<?> requireConstructor(Class<?> owner, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Constructor<?> constructor = owner.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor;
    }

    private static void ensureDexKitLoaded() {
        if (dexKitLoaded) {
            return;
        }
        synchronized (DEXKIT_LOAD_LOCK) {
            if (!dexKitLoaded) {
                System.loadLibrary("dexkit");
                dexKitLoaded = true;
            }
        }
    }

    static final class Resolution {
        private final Targets targets;
        private final Throwable targetsFailure;
        /** Null when the plugin build has no unique controller entry; the module then only guards. */
        final Method lyricPositionController;
        final Throwable lyricPositionControllerFailure;

        Resolution(
                Targets targets,
                Throwable targetsFailure,
                Method lyricPositionController,
                Throwable lyricPositionControllerFailure) {
            this.targets = targets;
            this.targetsFailure = targetsFailure;
            this.lyricPositionController = lyricPositionController;
            this.lyricPositionControllerFailure = lyricPositionControllerFailure;
        }

        Targets requireTargets() {
            if (targets == null) {
                throw new IllegalStateException("OPlus plugin media-model targets unresolved", targetsFailure);
            }
            return targets;
        }
    }

    static final class Targets {
        final Class<?> mediaModelClass;
        final Field albumArtField;
        final Field lyricModelField;
        final Field lyricSupportedField;
        final Method staticIconGetter;
        final Method lottieIconGetter;
        final Field staticDrawableField;
        final Field staticBitmapField;
        final Field cardIconModelField;
        final Method iconModelBitmapGetter;
        final Method iconModelColorGetter;
        final Constructor<?> staticIconConstructor;
        final Constructor<?> normalIconConstructor;
        final Constructor<?> multiIconConstructor;
        final boolean resolvedByDexKit;
        private final Field staticIconField;
        private final Field lottieIconField;
        private final Field staticArtworkResultField;
        private final boolean staticIconIncludesPlatformIcon;

        Targets(
                Class<?> mediaModelClass,
                Field albumArtField,
                Field lyricModelField,
                Field lyricSupportedField,
                Method staticIconGetter,
                Method lottieIconGetter,
                Field staticDrawableField,
                Field staticBitmapField,
                Field cardIconModelField,
                Method iconModelBitmapGetter,
                Method iconModelColorGetter,
                Constructor<?> staticIconConstructor,
                Constructor<?> normalIconConstructor,
                Constructor<?> multiIconConstructor,
                boolean resolvedByDexKit,
                Field staticIconField,
                Field lottieIconField,
                Field staticArtworkResultField,
                boolean staticIconIncludesPlatformIcon) {
            this.mediaModelClass = mediaModelClass;
            this.albumArtField = albumArtField;
            this.lyricModelField = lyricModelField;
            this.lyricSupportedField = lyricSupportedField;
            this.staticIconGetter = staticIconGetter;
            this.lottieIconGetter = lottieIconGetter;
            this.staticDrawableField = staticDrawableField;
            this.staticBitmapField = staticBitmapField;
            this.cardIconModelField = cardIconModelField;
            this.iconModelBitmapGetter = iconModelBitmapGetter;
            this.iconModelColorGetter = iconModelColorGetter;
            this.staticIconConstructor = staticIconConstructor;
            this.normalIconConstructor = normalIconConstructor;
            this.multiIconConstructor = multiIconConstructor;
            this.resolvedByDexKit = resolvedByDexKit;
            this.staticIconField = staticIconField;
            this.lottieIconField = lottieIconField;
            this.staticArtworkResultField = staticArtworkResultField;
            this.staticIconIncludesPlatformIcon = staticIconIncludesPlatformIcon;
        }

        Object readStaticIcon(Object albumArt) throws ReflectiveOperationException {
            return staticIconField != null
                    ? staticIconField.get(albumArt) : staticIconGetter.invoke(albumArt);
        }

        Object readLottieIcon(Object albumArt) throws ReflectiveOperationException {
            return lottieIconField != null
                    ? lottieIconField.get(albumArt) : lottieIconGetter.invoke(albumArt);
        }

        Object createStaticIcon(Icon icon, Drawable drawable, Bitmap bitmap,
                                Object mini, Object card, Object original)
                throws ReflectiveOperationException {
            if (staticIconIncludesPlatformIcon) {
                return staticIconConstructor.newInstance(icon, drawable, bitmap, mini, card);
            }
            Object artworkResult = original == null
                    ? null : staticArtworkResultField.get(original);
            return staticIconConstructor.newInstance(drawable, bitmap, mini, card, artworkResult);
        }
    }
}
