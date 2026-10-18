package com.naivety.zhicolor.bridge;

import android.content.*;import android.media.MediaMetadata;import android.net.Uri;import android.os.*;import android.view.*;import android.widget.TextView;import android.graphics.Typeface;
import java.lang.reflect.Field;import java.util.*;import java.util.concurrent.*;
import io.github.libxposed.api.XposedInterface;

/** Runtime gate and exclusive Lyricon input boundary; renderer algorithms stay in the upstream core. */
public final class ZhiBridgeRuntime {
    public interface Input { String payload(MediaMetadata metadata,String packageName); boolean hasPayload(String packageName); default void onSettingsChanged(){} }
    private static volatile Input input;
    private static volatile Context context;
    private static volatile boolean enabled;
    private static volatile Set<String> packages=Collections.emptySet();
    private static volatile String currentPackage="";
    private static volatile boolean started;
    private static volatile int snapshotHash;
    private static volatile boolean snapshotLoaded;
    private static final LyricRuntimeHandoffPolicy lyricHandoff = new LyricRuntimeHandoffPolicy();
    private static long scheduledHandoffDeadline;
    private static String scheduledHandoffPackage = "";
    private static int configFailures;
    private static final Map<MediaMetadata,String> originalLyrics=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View,ViewState> views=Collections.synchronizedMap(new WeakHashMap<>());
    private static final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ZHIColor-config");t.setDaemon(true);return t;});
    public static void setInput(Input value){input=value;if(value==null)lyricHandoff.reset();}
    public static void setCurrentPackage(String value){String next=value==null?"":value;if(!next.equals(currentPackage)){currentPackage=next;lyricHandoff.reset();new Handler(Looper.getMainLooper()).post(()->{if(!active())restoreViews();lyricsChanged();});}}
    public static boolean enabledFor(String pkg){return enabled&&packages.contains(pkg);}
    public static boolean isCurrentPlayerPackage(String pkg){return pkg!=null&&!pkg.isEmpty()&&pkg.equals(currentPackage)&&enabledFor(pkg);}
    public static boolean enabled(){return enabled;}
    public static Set<String> packages(){return new LinkedHashSet<>(packages);}
    public static boolean hasCurrentPayload(){Input i=input;String pkg=currentPackage;return enabledFor(pkg)&&i!=null&&i.hasPayload(pkg);}
    public static boolean active(){
        String pkg=currentPackage;Input i=input;long now=SystemClock.elapsedRealtime();
        boolean result=lyricHandoff.active(pkg,enabledFor(pkg)&&i!=null,i!=null&&i.hasPayload(pkg),now);
        scheduleHandoffRefresh(pkg,lyricHandoff.pendingUntil(pkg,now),now);
        return pkg.equals(currentPackage)&&result;
    }
    public static void beginLyricHandoff(String trackKey){
        String pkg=currentPackage;Input i=input;long now=SystemClock.elapsedRealtime();
        long deadline=lyricHandoff.begin(pkg,enabledFor(pkg)&&i!=null,i!=null&&i.hasPayload(pkg),trackKey,now);
        scheduleHandoffRefresh(pkg,deadline,now);
    }
    public static boolean hasPendingLyricHandoff(){return pendingLyricHandoffUntilElapsedMillis()>0L;}
    public static long pendingLyricHandoffUntilElapsedMillis(){active();return lyricHandoff.pendingUntil(currentPackage,SystemClock.elapsedRealtime());}
    public static void completeLyricHandoff(String expectedPackage){if(isCurrentPlayerPackage(expectedPackage))lyricHandoff.complete(expectedPackage);}
    private static synchronized void scheduleHandoffRefresh(String pkg,long deadline,long now){
        if(deadline<=now||(deadline==scheduledHandoffDeadline&&pkg.equals(scheduledHandoffPackage)))return;
        scheduledHandoffDeadline=deadline;scheduledHandoffPackage=pkg;
        new Handler(Looper.getMainLooper()).postDelayed(()->{
            if(!pkg.equals(currentPackage)||!lyricHandoff.matchesDeadline(pkg,deadline))return;
            lyricsChanged();
        },Math.max(1L,deadline-now));
    }
    public static synchronized void attach(Context c){
        if(c==null||started)return;started=true;context=c.getApplicationContext()==null?c:c.getApplicationContext();
        Handler handler=new Handler(Looper.getMainLooper());
        worker.execute(()->{try{Bundle cached=BridgeSettings.cachedSnapshot(context);if(cached!=null)applyConfig(context,cached,"cache");}catch(Throwable error){android.util.Log.w("ZHIColorDelivery","cached settings unavailable",error);}});
        handler.post(ZhiBridgeRuntime::refresh);
        handler.postDelayed(ZhiBridgeRuntime::refresh,1500);
        handler.postDelayed(ZhiBridgeRuntime::refresh,6000);
        BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
            Bundle snapshot=i.getBundleExtra(BridgeSettings.EXTRA_SNAPSHOT);
            if(snapshot==null)refresh();else worker.execute(()->{try{applyConfig(context,snapshot,"broadcast");}catch(Throwable error){android.util.Log.w("ZHIColorDelivery","settings broadcast failed",error);}});
        }};
        IntentFilter filter=new IntentFilter(BridgeSettings.CHANGED);
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,BridgeSettings.PERMISSION,null,Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver,filter,BridgeSettings.PERMISSION,null);
        BroadcastReceiver packagesChanged=new BroadcastReceiver(){public void onReceive(Context c,Intent i){MusicAppCatalog.invalidate();refresh();handler.postDelayed(ZhiBridgeRuntime::refresh,5500);}};
        IntentFilter packageFilter=new IntentFilter();packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);packageFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);packageFilter.addDataScheme("package");
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(packagesChanged,packageFilter,Context.RECEIVER_EXPORTED);else context.registerReceiver(packagesChanged,packageFilter);
        IntentFilter unlock=new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){refresh();}},unlock,Context.RECEIVER_EXPORTED);else context.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){refresh();}},unlock);
    }
    public static void refresh(){Context c=context;if(c==null)return;worker.execute(()->{try{
        Bundle snapshot=c.getContentResolver().call(Uri.parse("content://"+BridgeSettings.CONFIG_AUTHORITY),"snapshot",null,null);
        if(snapshot==null)throw new IllegalStateException("empty settings snapshot");
        applyConfig(c,snapshot,"provider");
        configFailures=0;
    }catch(Throwable error){
        android.util.Log.w("ZHIColorDelivery","settings snapshot failed; cached="+snapshotLoaded,error);
        int attempt=++configFailures;
        if(attempt<=5)new Handler(Looper.getMainLooper()).postDelayed(ZhiBridgeRuntime::refresh,Math.min(1000L<<attempt,30000L));
    }});}
    private static void applyConfig(Context c,Bundle snapshot,String source){
        // Advanced currently shares the standard renderer until its own pipeline is available.
        boolean next=snapshot.getBoolean(BridgeSettings.MASTER,false);
        ArrayList<String> list=snapshot.getStringArrayList(BridgeSettings.APPS);Set<String> allowed=list==null?Collections.emptySet():new LinkedHashSet<>(list);
        Bundle main=snapshot.getBundle("main");int hash=Objects.hash(next,allowed,stableHash(main),
                stableHash(snapshot.getBundle("debug")),stableHash(snapshot.getBundle("controls")));
        boolean explicitTranslationReset=snapshot.getBoolean(LyricUiSettings.EXTRA_CLEAR_TRANSLATION_OVERRIDES,false)
                ||snapshot.containsKey(LyricUiSettings.EXTRA_CLEAR_TRANSLATION_PACKAGES);
        if(!snapshotLoaded||hash!=snapshotHash||explicitTranslationReset){
            BridgeSettings.applySnapshot(c,snapshot);LyriconPlayerRegistry.refresh(c);
            enabled=next;packages=Collections.unmodifiableSet(allowed);snapshotHash=hash;snapshotLoaded=true;
            new Handler(Looper.getMainLooper()).post(()->{Input observer=input;if(observer!=null)observer.onSettingsChanged();if(!next||!active())restoreViews();SystemUiMetadataPublisher.requestRefresh();android.util.Log.i("ZHIColorDelivery","settings applied enabled="+next+" players="+allowed.size()+" source="+source);LockscreenLyricsModule m=LockscreenLyricsModule.zhiInstance;if(m!=null)m.refreshFromZhiRuntime(next);});}
    }
    private static int stableHash(Bundle b){if(b==null)return 0;int h=1;List<String> keys=new ArrayList<>(b.keySet());Collections.sort(keys);for(String key:keys){Object value=b.get(key);h=31*h+Objects.hash(key,value instanceof Bundle?stableHash((Bundle)value):value);}return h;}
    public static void lyricsChanged(){
        // Capture a source gap before metadata publication re-enters the guarded native loader.
        active();
        SystemUiMetadataPublisher.requestRefresh();
        LockscreenLyricsModule m=LockscreenLyricsModule.zhiInstance;
        new Handler(Looper.getMainLooper()).post(()->{if(!active())restoreViews();if(m!=null)m.refreshFromZhiRuntime(enabled);});
    }
    public static String decorate(MediaMetadata metadata,String pkg){
        if(metadata==null)return null;
        Input i=input;String payload=enabledFor(pkg)&&i!=null?i.payload(metadata,pkg):null;
        synchronized(originalLyrics){
            Bundle data=null;
            try {
                data=metadataBundle(metadata);
                if(payload!=null&&!payload.isEmpty()) {
                    if(originalLyrics.containsKey(metadata)&&payload.equals(data.getString("lyricInfo")))return payload;
                    if(!originalLyrics.containsKey(metadata))originalLyrics.put(metadata,data.getString("lyricInfo"));
                    data.putString("lyricInfo",payload);
                    Parcel parcel=Parcel.obtain();try{metadata.writeToParcel(parcel,0);if(parcel.dataSize()>512*1024){restoreOne(metadata,data);return null;}}finally{parcel.recycle();}
                } else if(originalLyrics.containsKey(metadata))restoreOne(metadata,data);
                return payload;
            }catch(Throwable ignored){if(data!=null&&originalLyrics.containsKey(metadata))restoreOne(metadata,data);return null;}
        }
    }
    private static Bundle metadataBundle(MediaMetadata value)throws Exception {Field f=MediaMetadata.class.getDeclaredField("mBundle");f.setAccessible(true);return (Bundle)f.get(value);}
    private static void restoreOne(MediaMetadata metadata,Bundle data){String old=originalLyrics.remove(metadata);if(old==null)data.remove("lyricInfo");else data.putString("lyricInfo",old);}
    public static void trackClone(MediaMetadata from,MediaMetadata to){if(from==to||to==null)return;synchronized(originalLyrics){if(originalLyrics.containsKey(from))originalLyrics.put(to,originalLyrics.get(from));}}
    private static void restoreMetadata(){synchronized(originalLyrics){for(MediaMetadata metadata:new ArrayList<>(originalLyrics.keySet()))if(metadata!=null)try{restoreOne(metadata,metadataBundle(metadata));}catch(Throwable ignored){}originalLyrics.clear();}}
    public static XposedInterface.Hooker guard(XposedInterface.Hooker hooker,String name){return chain->{
        boolean policy=name.startsWith("onOplus")||name.equals("onAodMediaSupportLookup");
        if(!(policy?enabled&&!packages.isEmpty():active()))return chain.proceed();
        if(policy){for(Object arg:chain.getArgs())if(arg instanceof String){String pkg=(String)arg;if(pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")&&!pkg.equals("com.android.systemui")&&!packages.contains(pkg))return chain.proceed();}}
        Object value=chain.getThisObject();if(value instanceof View&&lyricView((View)value)){View v=(View)value;synchronized(views){if(!views.containsKey(v))views.put(v,new ViewState(v));}}
        return hooker.intercept(chain);
    };}
    private static boolean lyricView(View v){for(int i=0;i<12&&v!=null;i++){if(v.getClass().getName().contains("LyricsRecyclerView"))return true;android.view.ViewParent p=v.getParent();v=p instanceof View?(View)p:null;}return false;}
    private static void restoreViews(){restoreMetadata();OfficialLyricMotionController.restoreAll();synchronized(views){for(Map.Entry<View,ViewState> e:views.entrySet())if(e.getKey()!=null)e.getValue().restore(e.getKey());views.clear();}}
    private static final class ViewState{
        final float alpha,sx,sy,size,extra,mult;final int l,t,r,b,fadeLength,height;final boolean verticalFade;final Typeface face;
        ViewState(View v){alpha=v.getAlpha();sx=v.getScaleX();sy=v.getScaleY();verticalFade=v.isVerticalFadingEdgeEnabled();fadeLength=v.getVerticalFadingEdgeLength();height=v.getLayoutParams()==null?Integer.MIN_VALUE:v.getLayoutParams().height;l=v.getPaddingLeft();t=v.getPaddingTop();r=v.getPaddingRight();b=v.getPaddingBottom();if(v instanceof TextView){TextView x=(TextView)v;size=x.getTextSize();extra=x.getLineSpacingExtra();mult=x.getLineSpacingMultiplier();face=x.getTypeface();}else{size=extra=mult=0;face=null;}}
        void restore(View v){v.setAlpha(alpha);v.setScaleX(sx);v.setScaleY(sy);v.setPadding(l,t,r,b);v.setVerticalFadingEdgeEnabled(verticalFade);v.setFadingEdgeLength(fadeLength);if(height!=Integer.MIN_VALUE&&v.getLayoutParams()!=null){v.getLayoutParams().height=height;}if(v instanceof TextView){TextView x=(TextView)v;x.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,size);x.setTypeface(face);x.setLineSpacing(extra,mult);}v.requestLayout();v.invalidate();}
    }
}
