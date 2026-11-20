package com.batterypulse.app.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.batterypulse.app.BatteryPulseApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BatteryPulseApp
        if (app.settings.monitoring && app.settings.state.value.resumeOnBoot) {
            MonitorService.start(context)
        }
    }
}
