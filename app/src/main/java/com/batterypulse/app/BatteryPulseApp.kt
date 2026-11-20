package com.batterypulse.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.batterypulse.app.data.SettingsStore
import com.batterypulse.app.data.Store
import com.batterypulse.app.monitor.MonitorService

class BatteryPulseApp : Application() {
    lateinit var store: Store
        private set
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        settings = SettingsStore(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                MonitorService.CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                MonitorService.ALERT_CHANNEL_ID, getString(R.string.alert_channel), NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.alert_channel_desc)
                enableVibration(true)
            }
        )
    }
}
