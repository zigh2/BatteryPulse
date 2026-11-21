package com.batterypulse.app.monitor

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.batterypulse.app.R
import com.batterypulse.app.data.LocaleHelper
import com.batterypulse.app.ui.MainActivity

/** Quick Settings tile: shows the level and state; tapping it opens the app. */
class BatteryTile : TileService() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onStartListening() {
        val tile = qsTile ?: return
        val snap = BatteryReader.read(this, BatteryReader.sticky(this))
        tile.state = if (MonitorState.flow.value.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = snap?.let { "${it.level}%" } ?: getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = snap?.let {
                getString(if (it.charging) R.string.state_charging else R.string.state_discharging)
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 11, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
