package com.naivety.zhicolor.bridge;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.session.MediaController;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Android/EGL lifecycle test, not OEM-hook or physical-device acceptance. */
public final class BackgroundLifecycleInstrumentation extends Instrumentation {
    private final JSONArray checks = new JSONArray();
    private BackgroundProbeActivity activity;
    private LyricBackgroundHost host;
    private Object content;
    private File out;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private static Object get(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value);
    }
    private void ui(Runnable action) { runOnMainSync(action); }
    private void check(String name, boolean ok, Object evidence) throws Exception {
        checks.put(new JSONObject().put("name", name).put("passed", ok).put("evidence", evidence));
        if (!ok) throw new AssertionError(name + ": " + evidence);
    }
    private long frame() throws Exception {
        Object renderer = get(content,"renderer");
        return renderer == null ? -1 : ((Number)get(renderer,"frameIndex")).longValue();
    }
    private long capture(String name) throws Exception {
        Bitmap[] image = {null};
        ui(() -> {
            image[0] = Bitmap.createBitmap(activity.target.getWidth(),activity.target.getHeight(),Bitmap.Config.ARGB_8888);
            activity.target.draw(new Canvas(image[0]));
        });
        try (FileOutputStream stream = new FileOutputStream(new File(out,name+".png"))) {
            image[0].compress(Bitmap.CompressFormat.PNG,100,stream);
        }
        long hash=1; int changed=0;
        for(int y=0;y<image[0].getHeight();y+=13) for(int x=0;x<image[0].getWidth();x+=13) {
            int pixel=image[0].getPixel(x,y);hash=hash*31+pixel;
            if(pixel!=android.graphics.Color.MAGENTA)changed++;
        }
        check(name+"_covers_native_background",changed>100,changed);
        image[0].recycle();return hash;
    }
    private void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);
             java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            while(in.read()!=-1) { }
        }
    }
    private boolean hasText(android.view.accessibility.AccessibilityNodeInfo node, String text) {
        if(node==null)return false;
        if(text.contentEquals(node.getText()==null?"":node.getText()))return true;
        for(int i=0;i<node.getChildCount();i++)if(hasText(node.getChild(i),text))return true;
        return false;
    }
    private void settingsUi(int choice, boolean expected) throws Exception {
        ui(()->BridgeSettings.controls(getTargetContext()).edit().putInt(LyricAppearanceSettings.BACKGROUND,choice)
            .remove(LyricAppearanceSettings.BACKGROUND_ANIMATION).apply());
        Activity preview=startActivitySync(new Intent(getTargetContext(),LyricsSettingsPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(900);
        String title=getTargetContext().getString(com.naivety.zhicolor.R.string.scr_background_animation);
        check(expected?"apple_settings_shows_animation":"system_settings_hides_animation",
            hasText(getUiAutomation().getRootInActiveWindow(),title)==expected,title);
        String summary=getTargetContext().getString(com.naivety.zhicolor.R.string.scr_background_animation_summary);
        check(expected?"apple_control_shows_power_summary":"system_choice_hides_power_summary",
            hasText(getUiAutomation().getRootInActiveWindow(),summary)==expected,summary);
        Bitmap shot=getUiAutomation().takeScreenshot();
        try(FileOutputStream stream=new FileOutputStream(new File(out,expected?"settings_apple.png":"settings_system.png"))){shot.compress(Bitmap.CompressFormat.PNG,100,stream);}
        shot.recycle();ui(preview::finish);
    }
    private void metadataRegression() throws Exception {
        Method identity=LyricBackgroundHost.class.getDeclaredMethod("metadataIdentity",String.class,android.media.MediaMetadata.class);
        identity.setAccessible(true);
        android.media.MediaMetadata.Builder builder=new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID,"qq-track-1")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST,"Superstar-Mandee/Maria Mathea")
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM,"Superstar (feat. Maria Mathea)");
        Object first=identity.invoke(null,"com.tencent.qqmusic",builder.putString(android.media.MediaMetadata.METADATA_KEY_TITLE,"I don't know").build());
        Object next=identity.invoke(null,"com.tencent.qqmusic",builder.putString(android.media.MediaMetadata.METADATA_KEY_TITLE,"But you must be some kind of superstar")
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION,240000).build());
        check("qq_lyric_and_duration_refresh_preserves_identity",first.equals(next),first);
        android.media.session.MediaSession session=new android.media.session.MediaSession(activity,"background-identity-test");
        try {
            MediaController a=session.getController();
            MediaController b=new MediaController(activity,session.getSessionToken());
            Method same=LyricBackgroundHost.class.getDeclaredMethod("sameSession",MediaController.class,MediaController.class);
            same.setAccessible(true);
            check("controller_wrappers_share_song_session",a!=b&&Boolean.TRUE.equals(same.invoke(null,a,b)),a!=b);
            android.media.session.MediaSession other=new android.media.session.MediaSession(activity,"background-other-test");
            try {check("different_sessions_still_rejected",Boolean.FALSE.equals(same.invoke(null,a,other.getController())),true);}
            finally {other.release();}
        } finally {session.release();}
    }
    @Override public void onStart() {
        Bundle result=new Bundle();Throwable failure=null;JSONObject report=new JSONObject();
        try {
            out=new File(getTargetContext().getExternalFilesDir(null),"background-qa");out.mkdirs();
            activity=(BackgroundProbeActivity)startActivitySync(new Intent(getTargetContext(),BackgroundProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            metadataRegression();
            Throwable[] setup={null};
            ui(() -> { try {
                BridgeSettings.controls(activity).edit().putBoolean(BridgeSettings.MASTER,true)
                    .putInt(LyricAppearanceSettings.BACKGROUND,1).remove(LyricAppearanceSettings.BACKGROUND_ANIMATION).apply();
                host=new LyricBackgroundHost(new LyricBackgroundHost.Runtime() {
                    public boolean active(){return true;} public boolean mediaPlaying(){return true;}
                    public MediaController controller(){return null;}
                });
                activity.host=host;host.settings(activity);
                Class<?> type=Class.forName("com.naivety.zhicolor.bridge.LyricBackgroundHost$MediaFrame");
                java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor();ctor.setAccessible(true);
                Object media=ctor.newInstance();Bitmap art=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
                for(int y=0;y<128;y++)for(int x=0;x<128;x++)art.setPixel(x,y,android.graphics.Color.rgb(x*2,y*2,(x+y)%128*2));
                set(media,"art",art);set(media,"track","fixture-playing");set(host,"mediaFrame",media);
                Method observe=LyricBackgroundHost.class.getDeclaredMethod("observeTarget",android.view.View.class);observe.setAccessible(true);observe.invoke(host,activity.target);
            } catch(Throwable e){setup[0]=e;} });
            if(setup[0]!=null)throw setup[0];
            for(int i=0;i<120;i++) {
                Thread.sleep(100);
                Object[] candidate={null};
                ui(()->{try{Map<?,?> slots=(Map<?,?>)get(host,"backgrounds");Object slot=slots.get(activity.target);if(slot!=null)candidate[0]=get(slot,"content");}catch(Exception ignored){}});
                content=candidate[0];
                if(content!=null&&((com.naivety.zhicolor.bridge.advanced.AdvancedBackdrop)content).isReady())break;
            }
            check("apple_frame_ready_without_draw_hook",content!=null&&((com.naivety.zhicolor.bridge.advanced.AdvancedBackdrop)content).isReady(),content!=null);
            Thread.sleep(500);long animated0=capture("animated_0");long f0=frame();Thread.sleep(1100);long animated1=capture("animated_1");
            check("playing_animation_advances",frame()>f0&&animated0!=animated1,f0+" -> "+frame());
            check("uses_the_same_app_transition_mode",Boolean.FALSE.equals(get(get(content,"renderer"),"artworkBlend")),get(get(content,"renderer"),"artworkBlend"));
            Throwable[] changed={null};
            ui(()->{try{
                Object media=get(host,"mediaFrame");Bitmap art=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
                for(int y=0;y<128;y++)for(int x=0;x<128;x++)art.setPixel(x,y,android.graphics.Color.rgb(255-x*2,255-y*2,32));
                set(media,"art",art);set(media,"track","fixture-next-track");host.stateChanged();
            }catch(Throwable e){changed[0]=e;}});
            if(changed[0]!=null)throw changed[0];
            Thread.sleep(350);float transition=((Number)get(get(content,"renderer"),"transition")).floatValue();
            check("song_change_uses_inflight_wave",transition>0f&&transition<1f,transition);
            Object waveBeforeLateCover=get(get(content,"renderer"),"directionIndex");
            ui(()->{try {
                Object media=get(host,"mediaFrame");Bitmap art=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
                art.eraseColor(android.graphics.Color.rgb(40,160,220));set(media,"art",art);host.stateChanged();
            }catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(200);
            check("late_cover_continues_same_wave",waveBeforeLateCover.equals(get(get(content,"renderer"),"directionIndex"))
                    &&((Number)get(get(content,"renderer"),"transition")).floatValue()>=transition,get(get(content,"renderer"),"transition"));
            capture("track_transition");Thread.sleep(1700);
            check("song_change_transition_completes",((Number)get(get(content,"renderer"),"transition")).floatValue()==1f,get(get(content,"renderer"),"transition"));
            Object originalImage=get(content,"image");
            Object direction=get(get(content,"renderer"),"directionIndex");
            for(int i=0;i<10;i++) {
                ui(()->{try{Object media=get(host,"mediaFrame");Bitmap art=(Bitmap)get(media,"art");
                    set(media,"art",art.copy(Bitmap.Config.ARGB_8888,false));host.stateChanged();
                }catch(Exception e){throw new RuntimeException(e);}});
                Thread.sleep(120);
            }
            Thread.sleep(300);
            check("same_song_copied_cover_reuses_image",originalImage==get(content,"image"),true);
            check("same_song_refresh_never_restarts_wave",direction.equals(get(get(content,"renderer"),"directionIndex"))
                    &&((Number)get(get(content,"renderer"),"transition")).floatValue()==1f,direction);
            ui(()->{try{Object media=get(host,"mediaFrame");set(media,"track","same-album-next-song");host.stateChanged();}
                catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(350);
            check("same_album_real_track_still_animates",!direction.equals(get(get(content,"renderer"),"directionIndex")),get(get(content,"renderer"),"transition"));
            Thread.sleep(1700);
            ui(()->{BridgeSettings.controls(activity).edit().putBoolean(LyricAppearanceSettings.BACKGROUND_ANIMATION,false).apply();host.settings(activity);});
            Thread.sleep(600);long static0=capture("static_0");long sf=frame();Thread.sleep(1100);long static1=capture("static_1");
            check("toggle_off_retains_static_apple",sf==frame()&&static0==static1,sf+" -> "+frame());
            ui(()->{BridgeSettings.controls(activity).edit().putBoolean(LyricAppearanceSettings.BACKGROUND_ANIMATION,true).apply();host.settings(activity);});
            Thread.sleep(700);long resume=frame();Thread.sleep(700);
            check("toggle_on_resumes",frame()>resume,resume+" -> "+frame());
            shell("input keyevent 223");Thread.sleep(900);long sleep=frame();Thread.sleep(1100);
            check("screen_off_stops_renderer",Boolean.FALSE.equals(get(content,"active"))&&frame()==sleep,sleep+" -> "+frame());
            check("screen_off_cancels_host_polling",Boolean.FALSE.equals(get(host,"scheduled")),get(host,"scheduled"));
            shell("input keyevent 224");shell("wm dismiss-keyguard");Thread.sleep(1400);long wake=frame();Thread.sleep(900);
            check("screen_on_resumes_renderer",Boolean.TRUE.equals(get(content,"active"))&&frame()>wake,wake+" -> "+frame());
            Object previous=content;
            ui(()->{
                android.view.ViewGroup parent=(android.view.ViewGroup)activity.target.getParent();
                int index=parent.indexOfChild(activity.target);
                android.view.ViewGroup.LayoutParams params=activity.target.getLayoutParams();
                parent.removeView(activity.target);parent.addView(activity.target,index,params);
            });
            Thread.sleep(1800);
            Object[] rebound={null};ui(()->{try{rebound[0]=get(((Map<?,?>)get(host,"backgrounds")).get(activity.target),"content");}catch(Exception ignored){}});
            content=rebound[0];
            check("detach_releases_old_renderer",Boolean.TRUE.equals(get(previous,"released")),get(previous,"released"));
            check("reattach_recreates_ready_background",content!=null&&content!=previous&&((com.naivety.zhicolor.bridge.advanced.AdvancedBackdrop)content).isReady(),content!=null);
            ui(()->{BridgeSettings.controls(activity).edit().putInt(LyricAppearanceSettings.BACKGROUND,0).apply();host.settings(activity);});Thread.sleep(250);
            Object[] slot={null};ui(()->{try{slot[0]=((Map<?,?>)get(host,"backgrounds")).get(activity.target);}catch(Exception ignored){}});
            check("system_choice_removes_overlay",Boolean.FALSE.equals(get(slot[0],"overlayAdded")),get(slot[0],"overlayAdded"));
            settingsUi(1,true);
            check("animation_defaults_enabled",LyricAppearanceSettings.load(getTargetContext()).backgroundAnimation,true);
            settingsUi(0,false);
            report.put("result","BACKGROUND_LIFECYCLE_PASS");
            result.putString("stream","BACKGROUND_LIFECYCLE_PASS checks="+checks.length());
        } catch(Throwable t) {
            failure=t;result.putString("stream",android.util.Log.getStackTraceString(t));
            try{report.put("result","BACKGROUND_LIFECYCLE_FAIL").put("error",android.util.Log.getStackTraceString(t));}catch(Exception ignored){}
        } finally {
            try{shell("input keyevent 224");shell("wm dismiss-keyguard");}catch(Exception ignored){}
            if(activity!=null)ui(()->{if(host!=null)host.reset();activity.finish();});
        }
        try {
            report.put("checks",checks).put("scope","isolated Android renderer; not OEM or physical-device acceptance");
            try(FileOutputStream stream=new FileOutputStream(new File(out,"report.json"))){stream.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }catch(Exception e){failure=e;}
        finish(failure==null?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
