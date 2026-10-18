package com.naivety.zhicolor.bridge;

import android.content.Context;
import java.util.*;

/** Only packages covered by installed Lyricon ecosystem Providers. */
final class LyriconPlayerRegistry {
    private static volatile Set<String> packages=Collections.emptySet();
    private static volatile int generation;
    static synchronized void refresh(Context context) {
        if(context==null) return;
        Set<String> next=new LinkedHashSet<>();
        for(MusicAppCatalog.Entry app:MusicAppCatalog.installed(context)) if(app.providerInstalled) next.add(app.packageName);
        if(!next.equals(packages)) { packages=Collections.unmodifiableSet(next); generation++; }
    }
    static boolean isExtraHistoryPackage(String pkg) { return packages.contains(pkg); }
    static List<String> extraHistoryPackages() { return new ArrayList<>(packages); }
    static int bindingsGeneration() { return generation; }
    static boolean isBlockedFromUniversalAdmission(String pkg) { return !packages.contains(pkg); }
}
