package com.naivety.zhicolor.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Selects the OEM's independent artwork/FlowLight model without changing artwork geometry. */
public final class SystemShapedArtworkPolicy {
    private final Access access;
    private final Map<Object, Models> models = new WeakHashMap<>();
    private boolean enabled;

    public SystemShapedArtworkPolicy() { this(new NativeAccess()); }

    SystemShapedArtworkPolicy(Access access) { this.access = access; }

    /** Called after the native background-model builder; all native fallback models stay intact. */
    public Object modelBuilt(Object builder, Object nativeModel) {
        if (nativeModel == null) return null;
        final Object owner;
        final Object shaped;
        try {
            owner = access.owner(builder);
            if (owner == null) return nativeModel;
            shaped = access.fullScreen(nativeModel) ? access.flowLight(builder, nativeModel) : nativeModel;
            if (shaped == null) return nativeModel;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // Unverified OEM variants keep their original model and renderer.
            return nativeModel;
        }
        synchronized (models) {
            models.put(owner, new Models(nativeModel, shaped));
            return enabled ? shaped : nativeModel;
        }
    }

    /** Updates the OEM state flow so layout, cover visibility and renderer use the same model. */
    public void setEnabled(boolean next) {
        ArrayList<Update> updates = new ArrayList<>();
        synchronized (models) {
            enabled = next;
            for (Map.Entry<Object, Models> entry : models.entrySet())
                updates.add(new Update(entry.getKey(), entry.getValue()));
        }
        for (Update update : updates) publish(update);
    }

    /** Drops old plugin objects while retaining the user's current selection. */
    public void reset() { synchronized (models) { models.clear(); } }

    private void publish(Update update) {
        // A collector can re-enter this policy. Never invoke OEM code while holding the cache lock.
        for (int retry = 0; retry < 3; retry++) {
            final Object selected;
            synchronized (models) {
                if (models.get(update.owner) != update.models) return;
                selected = enabled ? update.models.shaped : update.models.nativeModel;
            }
            try {
                Object current = access.current(update.owner);
                // A newer emission that has not passed the builder hook is never overwritten.
                if (!Objects.equals(current, update.models.nativeModel)
                        && !Objects.equals(current, update.models.shaped)) return;
                if (!Objects.equals(current, selected)) access.publish(update.owner, selected);
            } catch (ReflectiveOperationException | RuntimeException failure) { return; }
            synchronized (models) {
                if (models.get(update.owner) != update.models) return;
                if (selected == (enabled ? update.models.shaped : update.models.nativeModel)) return;
            }
        }
    }

    interface Access {
        Object owner(Object builder) throws ReflectiveOperationException;
        boolean fullScreen(Object model);
        Object flowLight(Object builder, Object model) throws ReflectiveOperationException;
        Object current(Object owner) throws ReflectiveOperationException;
        void publish(Object owner, Object model) throws ReflectiveOperationException;
    }

    private static final class Models {
        final Object nativeModel, shaped;
        Models(Object nativeModel, Object shaped) { this.nativeModel = nativeModel; this.shaped = shaped; }
    }

    private static final class Update {
        final Object owner;
        final Models models;
        Update(Object owner, Models models) { this.owner = owner; this.models = models; }
    }

    static final class NativeAccess implements Access {
        private static final String SECTION = "com.oplus.systemui.plugins.shared.template.section.media.";
        private static final String MODEL = "com.oplus.systemui.plugins.shared.template.section.model.media.";
        private static final String DATA = "com.oplus.systemui.plugins.shared.data.media.model.";
        private static final String BUILDER = SECTION + "MPImmersiveSectionViewModel$bgModel$1";
        private static final String VM = SECTION + "q";
        private static final String READ_ONLY = "com.oplus.systemui.plugins.r8.jj";
        private static final String MUTABLE = "kotlinx.coroutines.flow.p";
        private static final String DRAWABLE = "android.graphics.drawable.Drawable";
        private final Map<String, Class<?>> types;

        NativeAccess() { this.types = java.util.Collections.emptyMap(); }
        NativeAccess(Map<String, Class<?>> types) { this.types = types; }

        @Override public Object owner(Object builder) throws ReflectiveOperationException {
            return read(builder, BUILDER, "this$0", VM);
        }

        @Override public boolean fullScreen(Object model) {
            return model != null && named(model.getClass(), MODEL + "b");
        }

        @Override public Object flowLight(Object builder, Object model) throws ReflectiveOperationException {
            Object media = read(builder, BUILDER, "L$0", Object.class.getName());
            Object artwork = nullableRead(media, DATA + "t", "d", DATA + "y");
            Object icon = nullableRead(artwork, DATA + "y", "a", DATA + "A");
            Object result = nullableRead(icon, DATA + "A", "e", DATA + "b");
            Object palette = nullableRead(result, DATA + "b", "b", DATA + "a");
            Object drawable = read(model, MODEL + "b", "a", DRAWABLE);
            if (drawable == null) return null;
            ClassLoader loader = model.getClass().getClassLoader();
            Class<?> type = resolve(MODEL + "c", loader);
            Class<?> drawableType = resolve(DRAWABLE, loader);
            Constructor<?> constructor = type.getDeclaredConstructor(drawableType, int.class, int.class,
                    int.class, int.class, int.class);
            constructor.setAccessible(true);
            // Exact non-fullscreen branch of the verified OEM builder (including repeated g).
            return constructor.newInstance(drawable, color(palette, "a"), color(palette, "d"),
                    color(palette, "g"), color(palette, "g"), color(palette, "f"));
        }

        @Override public Object current(Object owner) throws ReflectiveOperationException {
            Object state = state(owner);
            Method get = state.getClass().getDeclaredMethod("getValue");
            if (get.getReturnType() != Object.class) throw new NoSuchMethodException("OEM state value");
            get.setAccessible(true);
            return get.invoke(state);
        }

        @Override public void publish(Object owner, Object model) throws ReflectiveOperationException {
            Object state = state(owner);
            // This shipped obfuscated StateFlow setter is g(Object), not setValue(Object).
            Method set = state.getClass().getDeclaredMethod("g", Object.class);
            if (set.getReturnType() != void.class) throw new NoSuchMethodException("OEM state setter");
            set.setAccessible(true);
            set.invoke(state, model);
        }

        private Object state(Object owner) throws ReflectiveOperationException {
            Object wrapper = read(owner, VM, "m", READ_ONLY);
            Object state = read(wrapper, READ_ONLY, "b", "com.oplus.systemui.plugins.r8.Lg");
            if (state == null || !named(state.getClass(), MUTABLE))
                throw new NoSuchFieldException("OEM mutable background state");
            return state;
        }

        private int color(Object palette, String name) throws ReflectiveOperationException {
            return palette == null ? 0 : (Integer) read(palette, DATA + "a", name, "int");
        }

        private Object nullableRead(Object object, String owner, String name, String type)
                throws ReflectiveOperationException {
            return object == null ? null : read(object, owner, name, type);
        }

        private Object read(Object object, String owner, String name, String type)
                throws ReflectiveOperationException {
            if (object == null || !named(object.getClass(), owner))
                throw new NoSuchFieldException("Unverified OEM owner " + owner);
            Field field = object.getClass().getDeclaredField(name);
            if (!named(field.getType(), type))
                throw new NoSuchFieldException("Unverified OEM field " + owner + "." + name);
            field.setAccessible(true);
            return field.get(object);
        }

        private boolean named(Class<?> type, String expected) {
            Class<?> alias = types.get(expected);
            return alias == null ? expected.equals(type.getName()) : alias == type;
        }

        private Class<?> resolve(String name, ClassLoader loader) throws ClassNotFoundException {
            Class<?> alias = types.get(name);
            return alias == null ? Class.forName(name, false, loader) : alias;
        }
    }
}
