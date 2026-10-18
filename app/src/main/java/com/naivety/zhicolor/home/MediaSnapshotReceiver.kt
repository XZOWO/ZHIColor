package com.naivety.zhicolor.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Non-exported; only the app-created PendingIntent can deliver the SystemUI reply. */
class MediaSnapshotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MediaContract.RESPONSE) HomeRepository.acceptSnapshot(intent)
    }
}
