package com.naivety.zhicolor.advanced

import android.os.IBinder

/** Small stable Binder wire format; no application Parcelable crosses into SystemUI. */
internal object AudioDynamicsProtocol {
    const val PACKAGE = "com.naivety.zhicolor"
    const val SERVICE = "$PACKAGE.advanced.AudioDynamicsService"
    const val DESCRIPTOR = "$SERVICE.v1"
    const val CALLBACK = "$DESCRIPTOR.callback"
    const val UPDATE = IBinder.FIRST_CALL_TRANSACTION
    const val STOP = UPDATE + 1
    const val ACK = UPDATE + 2
    // Existing transactions and descriptors retain their exact v1 wire format.
    const val UPDATE_V2 = UPDATE + 3
    const val GET_PROTOCOL_VERSION = UPDATE + 4
    const val VERSION = 2
    const val FRAME = IBinder.FIRST_CALL_TRANSACTION
    const val FRAME_V2 = FRAME + 1
}
