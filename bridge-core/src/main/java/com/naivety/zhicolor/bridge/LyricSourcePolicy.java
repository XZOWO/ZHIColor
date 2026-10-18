package com.naivety.zhicolor.bridge;
/** The native OPlus metadata format is a render contract, not an additional Provider source. */
public final class LyricSourcePolicy {
    private LyricSourcePolicy(){}
    public static boolean accepts(LyricInfoContract.Payload payload) {
        return payload!=null && "lyricon".equals(payload.provider) && "lyricon".equals(payload.source);
    }
}
