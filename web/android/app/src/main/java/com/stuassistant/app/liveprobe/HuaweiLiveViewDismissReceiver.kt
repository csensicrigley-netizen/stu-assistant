package com.stuassistant.app.liveprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only our explicit immutable PendingIntents; stale sessions cannot end a newer test. */
class HuaweiLiveViewDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HuaweiLiveViewProbe.ACTION_FINISH && intent.action != HuaweiLiveViewProbe.ACTION_DISMISSED) return
        HuaweiLiveViewProbe(context).removed(intent.getStringExtra(HuaweiLiveViewProbe.EXTRA_SESSION),
            intent.action == HuaweiLiveViewProbe.ACTION_DISMISSED)
    }
}
