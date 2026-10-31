package com.stuassistant.app.liveprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit immutable pending intents only; stale sessions cannot cancel a newer test. */
class LiveProbeDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LiveUpdateProbe.ACTION_FINISH && intent.action != LiveUpdateProbe.ACTION_DISMISSED) return
        LiveUpdateProbe(context).removed(intent.getStringExtra(LiveUpdateProbe.EXTRA_SESSION),
            intent.action == LiveUpdateProbe.ACTION_DISMISSED)
    }
}
