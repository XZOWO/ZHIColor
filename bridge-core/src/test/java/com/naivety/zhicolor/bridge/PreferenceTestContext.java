package com.naivety.zhicolor.bridge;

import android.content.ContextWrapper;
import android.content.SharedPreferences;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** In-memory preferences with Android clear/put/remove ordering and commit-failure injection. */
final class PreferenceTestContext extends ContextWrapper {
    final Map<String, Map<String, Object>> domains = new LinkedHashMap<>();
    String failNextCommit;

    PreferenceTestContext() { super(null); }

    Map<String, Object> values(String name) {
        return domains.computeIfAbsent(name, ignored -> new LinkedHashMap<>());
    }

    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        Map<String, Object> values = values(name);
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    String operation = method.getName();
                    if (operation.equals("getAll")) return new LinkedHashMap<>(values);
                    if (operation.equals("contains")) return values.containsKey(args[0]);
                    if (operation.equals("edit")) return editor(name, values);
                    if (operation.startsWith("get")) return values.getOrDefault(args[0], args[1]);
                    return null;
                });
    }

    private SharedPreferences.Editor editor(String namespace, Map<String, Object> values) {
        Map<String, Object> pending = new LinkedHashMap<>();
        Set<String> removed = new LinkedHashSet<>();
        boolean[] clear = {false};
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    String operation = method.getName();
                    if (operation.startsWith("put")) {
                        Object value = args[1] instanceof Set ? new LinkedHashSet<>((Set<?>) args[1]) : args[1];
                        pending.put((String) args[0], value);
                        removed.remove(args[0]);
                    } else if (operation.equals("remove")) {
                        pending.remove(args[0]); removed.add((String) args[0]);
                    } else if (operation.equals("clear")) {
                        clear[0] = true;
                    } else if (operation.equals("apply") || operation.equals("commit")) {
                        if (clear[0]) values.clear();
                        for (String key : removed) values.remove(key);
                        values.putAll(pending);
                        if (operation.equals("apply")) return null;
                        if (namespace.equals(failNextCommit)) { failNextCommit = null; return false; }
                        return true;
                    }
                    return proxy;
                });
    }
}
