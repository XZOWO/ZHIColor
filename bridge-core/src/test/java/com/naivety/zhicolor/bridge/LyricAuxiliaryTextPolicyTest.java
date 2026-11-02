package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.WordLine;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public class LyricAuxiliaryTextPolicyTest {
    private WordLine line() {return new WordLine(1000,"原文",new ArrayList<>());}
    @Test public void translationAndRomanizationRemainSeparate() {
        WordLine line=line();line.translation="legacy";line.romanization="romaji";
        line.advancedTranslationProvided=true;line.advancedTranslation="译文";
        assertEquals("译文",LyricAuxiliaryTextPolicy.translation(line));
        assertEquals("romaji",LyricAuxiliaryTextPolicy.romanization(line,true));
        assertEquals("",LyricAuxiliaryTextPolicy.romanization(line,false));
        assertEquals("译文",LyricAuxiliaryTextPolicy.translation(line));
    }
    @Test public void legacyRomanizationAliasDoesNotAppearTwiceOrBecomeTranslation() {
        WordLine line=line();line.translation="romaji";line.romanization="romaji";
        assertEquals("",LyricAuxiliaryTextPolicy.translation(line));
        assertEquals("romaji",LyricAuxiliaryTextPolicy.romanization(line,true));
    }
    @Test public void explicitEmptyTranslationWinsOverLegacyAlias() {
        WordLine line=line();line.translation="romaji";line.romanization="romaji";
        line.advancedTranslationProvided=true;
        assertEquals("",LyricAuxiliaryTextPolicy.translation(line));
    }
    @Test public void ordinaryTranslationWithoutRichMetadataIsUnchanged() {
        WordLine line=line();line.translation="译文";
        assertEquals("译文",LyricAuxiliaryTextPolicy.translation(line));
        assertEquals("",LyricAuxiliaryTextPolicy.romanization(line,true));
        assertEquals("",LyricAuxiliaryTextPolicy.translation(null));
    }
}
