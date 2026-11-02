package com.naivety.zhicolor.advanced

/** SystemUI can have a dedicated package UID instead of android.uid.system. */
internal object AudioSubscriberPolicy {
    fun accepts(callerUid: Int, moduleUid: Int, systemUiUid: Int): Boolean =
        callerUid >= 0 && (callerUid == 1000 || callerUid == moduleUid ||
            (systemUiUid > 0 && callerUid == systemUiUid))
}
