package com.batterypulse.app.export

import android.content.Context
import com.batterypulse.app.R
import com.batterypulse.app.data.Analytics
import com.batterypulse.app.data.Capacity
import com.batterypulse.app.data.DAY_MS
import com.batterypulse.app.data.SettingsState
import com.batterypulse.app.data.Store
import com.batterypulse.app.monitor.BatteryReader

/** Gathers the data for a report period; shared by the Reports tab, exports and the weekly report. */
object ReportBuilder {

    /** Period start for an index: 0 = 24 h (or today), 1 = 7 days, 2 = 30 days, 3 = everything. */
    fun rangeStart(index: Int, now: Long, firstTs: Long?, today: Boolean): Long = when (index) {
        0 -> if (today) Analytics.dayStart(now) else now - DAY_MS
        1 -> now - 7 * DAY_MS
        2 -> now - 30 * DAY_MS
        else -> firstTs ?: (now - DAY_MS)
    }

    fun capacity(ctx: Context, store: Store, st: SettingsState): Capacity {
        val now = System.currentTimeMillis()
        val sessions = store.sessions(0L, now)
        return Capacity(
            estimatedMah = Analytics.estimateCapacityMah(sessions),
            designMah = if (st.designMah > 0) st.designMah else BatteryReader.designCapacityMah(ctx),
            osHealthPct = BatteryReader.osHealthPct(ctx),
            osCycles = BatteryReader.read(ctx, BatteryReader.sticky(ctx))?.osCycles,
            trend = Analytics.capacityTrend(sessions),
        )
    }

    /** Export data for the period [rangeIdx], or null when there is nothing recorded in it. */
    fun data(ctx: Context, store: Store, st: SettingsState, rangeIdx: Int): ExportData? {
        val now = System.currentTimeMillis()
        val from = rangeStart(rangeIdx, now, store.firstTs(), today = false)
        val events = store.events(from, now)
        if (events.isEmpty()) return null
        val sessions = store.sessions(from, now)
        val label = ctx.getString(
            when (rangeIdx) {
                0 -> R.string.range_24h
                1 -> R.string.range_7d
                2 -> R.string.range_30d
                else -> R.string.range_all
            }
        )
        return ExportData(
            report = Analytics.build(events, sessions, from, now),
            events = events, sessions = sessions, capacity = capacity(ctx, store, st),
            from = from, to = now, rangeLabel = label,
        )
    }
}
