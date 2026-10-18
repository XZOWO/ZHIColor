package com.naivety.zhicolor.bridge;
import org.junit.Test;import static org.junit.Assert.*;
public class LyricSourcePolicyTest {
 @Test public void onlyLyriconTaggedPayloadEntersRuntime(){String body="\"lyric\":\"[00:00.000]line\"";assertFalse(LyricSourcePolicy.accepts(LyricInfoContract.parse("{"+body+",\"provider\":\"native\",\"source\":\"native\"}")));assertTrue(LyricSourcePolicy.accepts(LyricInfoContract.parse("{"+body+",\"provider\":\"lyricon\",\"source\":\"lyricon\"}")));}
}
