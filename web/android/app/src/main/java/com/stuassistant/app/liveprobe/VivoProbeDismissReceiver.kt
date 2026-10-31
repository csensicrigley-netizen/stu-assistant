package com.stuassistant.app.liveprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** App-private immutable pending intents, scoped to one manual test session. */
class VivoProbeDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VivoIslandProbe.ACTION_FINISH && intent.action != VivoIslandProbe.ACTION_DISMISSED) return
        VivoIslandProbe(context).removed(intent.getStringExtra(VivoIslandProbe.EXTRA_SESSION),
            intent.action == VivoIslandProbe.ACTION_DISMISSED)
    }
}
