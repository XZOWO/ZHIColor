package com.naivety.zhicolor.lyrics

enum class LyricSourceMode { OFF, OFFICIAL, DIRECT_PROVIDER;
    companion object {
        fun choose(enabled:Boolean,officialInstalled:Boolean)=if(!enabled)OFF else if(officialInstalled)OFFICIAL else DIRECT_PROVIDER
    }
}
