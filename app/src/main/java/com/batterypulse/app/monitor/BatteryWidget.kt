package com.batterypulse.app.monitor

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.batterypulse.app.R
import com.batterypulse.app.data.Fmt
import com.batterypulse.app.data.LocaleHelper
import com.batterypulse.app.ui.MainActivity
import kotlin.math.abs

/** Home-screen widget: level, state, time left and temperature. Refreshed by the service on every level change. */
class BatteryWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, BatteryWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, manager, ids)
        }

        private fun render(base: Context, manager: AppWidgetManager, ids: IntArray) {
            val ctx = LocaleHelper.wrap(base)
            val snap = BatteryReader.read(ctx, BatteryReader.sticky(ctx))
            val live = MonitorState.flow.value
            val views = RemoteViews(ctx.packageName, R.layout.widget_battery)
            if (snap == null) {
                views.setTextViewText(R.id.w_level, "—")
                views.setTextViewText(R.id.w_state, ctx.getString(R.string.app_name))
                views.setTextViewText(R.id.w_info, "")
            } else {
                views.setTextViewText(R.id.w_level, "${snap.level}%")
                views.setTextViewText(
                    R.id.w_state,
                    if (snap.charging) "⚡ " + ctx.getString(R.string.state_charging)
                    else ctx.getString(R.string.state_discharging)
                )
                val parts = ArrayList<String>()
                val eta = live.etaMs
                if (live.running && eta != null) {
                    parts.add(
                        ctx.getString(
                            if (snap.charging) R.string.notif_eta_full else R.string.notif_eta_empty,
                            Fmt.duration(ctx, eta)
                        )
                    )
                }
                if (snap.tempDeci > 0) parts.add(Fmt.temp(snap.tempDeci))
                snap.currentMa?.let { parts.add(Fmt.ma(abs(it))) }
                views.setTextViewText(R.id.w_info, parts.joinToString(" · "))
            }
            val open = PendingIntent.getActivity(
                ctx, 10, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.w_root, open)
            manager.updateAppWidget(ids, views)
        }
    }
}
