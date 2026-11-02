package com.naivety.zhicolor.bridge;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Paint;
import android.widget.TextView;
import android.view.View;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.naivety.zhicolor.bridge.render.WordLine;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises actual Android text measurement and the standard renderer on an emulator. */
public final class LyricAppearanceInstrumentation extends Instrumentation {
    private final JSONArray checks=new JSONArray();
    @Override public void onCreate(Bundle arguments) {super.onCreate(arguments);start();}
    private void check(String name,boolean passed,Object evidence)throws Exception {
        checks.put(new JSONObject().put("name",name).put("passed",passed).put("evidence",evidence));
        if(!passed)throw new AssertionError(name+": "+evidence);
    }
    private Method method(Class<?> type,String name,Class<?>...args)throws Exception {
        Method m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;
    }
    @Override public void onStart() {
        JSONObject report=new JSONObject();Bundle result=new Bundle();Throwable[] failure={null};
        try {
            runOnMainSync(()->{try{runChecks();}catch(Throwable t){failure[0]=t;}});
            if(failure[0]!=null)throw new RuntimeException(failure[0]);
            report.put("result","APPEARANCE_QA_PASS").put("checks",checks);
            result.putString("stream","APPEARANCE_QA_PASS checks="+checks.length());
        } catch(Throwable t) {
            try{report.put("result","APPEARANCE_QA_FAIL").put("checks",checks).put("error",android.util.Log.getStackTraceString(t));}catch(Exception ignored){}
            result.putString("stream",android.util.Log.getStackTraceString(t));
            failure[0]=t;
        }
        try {
            File out=new File(getTargetContext().getExternalFilesDir(null),"appearance-qa.json");
            try(FileOutputStream stream=new FileOutputStream(out)){stream.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }catch(Exception e){failure[0]=e;}
        finish(failure[0]==null?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private void runChecks()throws Exception {
        Context context=getTargetContext();
        SharedPreferences controls=BridgeSettings.controls(context);
        controls.edit().remove(LyricAppearanceSettings.BACKGROUND).remove(LyricAppearanceSettings.FONT)
                .remove(LyricAppearanceSettings.ROMANIZATION).remove("advanced_romanization").apply();
        LyricAppearanceSettings defaults=LyricAppearanceSettings.load(context);
        check("defaults_apple_background_and_font",defaults.background==1&&defaults.font==1,defaults.background+"/"+defaults.font);
        Class<?> type=Class.forName("com.naivety.zhicolor.bridge.LockscreenLyricsModule$OfficialLyricTextRenderer");
        Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
        Object renderer=constructor.newInstance();
        Method appearance=method(type,"setAppearance",LyricAppearanceSettings.class);
        Method font=method(type,"resolveConfiguredTypeface",Typeface.class);
        appearance.invoke(renderer,defaults);
        Typeface apple=(Typeface)font.invoke(renderer,Typeface.SERIF);
        check("apple_uses_retained_font_resolver",apple==AppleLyricTypeface.resolve(true),apple.toString());
        controls.edit().putInt(LyricAppearanceSettings.FONT,0).apply();
        appearance.invoke(renderer,LyricAppearanceSettings.load(context));
        Typeface system=(Typeface)font.invoke(renderer,Typeface.SERIF);
        check("system_font_restores_native_family",system==Typeface.SERIF,system.toString());
        controls.edit().putInt(LyricAppearanceSettings.FONT,1).apply();
        appearance.invoke(renderer,LyricAppearanceSettings.load(context));
        check("font_switch_back_recomputes_cache",font.invoke(renderer,Typeface.SERIF)==apple,true);
        Method slot=method(type,"resolveSlotHeight",TextView.class,WordLine.class,String.class,boolean.class);
        Method translation=method(type,"setTranslationEnabledImmediately",boolean.class);
        TextView view=new TextView(context);view.setTextSize(24);view.setPadding(20,20,20,20);
        view.measure(View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1400,View.MeasureSpec.EXACTLY));
        view.layout(0,0,540,1400);
        WordLine line=new WordLine(1000,"Hello world",new ArrayList<>(),5000);
        line.romanization="hē lō wǒ er de with a long phonetic line that wraps at the available width";
        line.translation="你好世界";line.advancedTranslationProvided=true;line.advancedTranslation="你好世界";
        line.secondaryLayout.reset(1f);line.visualSecondary.reset(1f);
        translation.invoke(renderer,true);
        controls.edit().putBoolean(LyricAppearanceSettings.ROMANIZATION,false).apply();
        appearance.invoke(renderer,LyricAppearanceSettings.load(context));
        int translationOnly=(Integer)slot.invoke(renderer,view,line,line.translation,false);
        controls.edit().putBoolean(LyricAppearanceSettings.ROMANIZATION,true).apply();
        appearance.invoke(renderer,LyricAppearanceSettings.load(context));
        int both=(Integer)slot.invoke(renderer,view,line,line.translation,false);
        check("romanization_adds_measured_height_without_replacing_translation",both>translationOnly,both+">"+translationOnly);
        translation.invoke(renderer,false);
        int romanizationOnly=(Integer)slot.invoke(renderer,view,line,line.translation,false);
        controls.edit().putBoolean(LyricAppearanceSettings.ROMANIZATION,false).apply();
        appearance.invoke(renderer,LyricAppearanceSettings.load(context));
        int neither=(Integer)slot.invoke(renderer,view,line,line.translation,false);
        check("romanization_independent_of_translation_toggle",romanizationOnly>neither,romanizationOnly+">"+neither);
        translation.invoke(renderer,true);
        int restored=(Integer)slot.invoke(renderer,view,line,line.translation,false);
        check("romanization_off_restores_standard_row_height",restored==translationOnly,restored+"/"+translationOnly);
        LyricRomanizationLayout layouts=new LyricRomanizationLayout();Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(apple);paint.setTextSize(40);
        LyricRomanizationLayout.Entry measured=layouts.resolve(line,true,paint,300,1,false);
        check("long_romanization_wraps",measured.layout.getLineCount()>1,measured.layout.getLineCount());
        Bitmap bitmap=Bitmap.createBitmap(340,(int)measured.height()+40,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.BLACK);measured.draw(canvas,20,20,Color.WHITE);
        File image=new File(context.getExternalFilesDir(null),"romanization-layout.png");
        try(FileOutputStream stream=new FileOutputStream(image)){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}
        int ink=0;for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)if(bitmap.getPixel(x,y)!=Color.BLACK)ink++;
        check("romanization_draws_real_glyphs",ink>100,ink);
        controls.edit().remove(LyricAppearanceSettings.BACKGROUND).remove(LyricAppearanceSettings.FONT)
                .remove(LyricAppearanceSettings.ROMANIZATION).apply();
    }
}
