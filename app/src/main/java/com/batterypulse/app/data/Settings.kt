package com.batterypulse.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

data class SettingsState(
    /** 0 = system, 1 = light, 2 = dark */
    val theme: Int = 0,
    val dynamicColor: Boolean = false,
    /** "system", "en" or "ar" */
    val lang: String = "system",
    val resumeOnBoot: Boolean = true,
    // alerts
    val alertFull: Boolean = true,
    val alertLimit: Boolean = false,
    val limitPct: Int = 80,
    val alertLow: Boolean = true,
    val lowPct: Int = 15,
    val alertTemp: Boolean = true,
    val tempLimitC: Int = 42,
    // data
    val retentionDays: Int = 365,
    /** Manual design capacity in mAh; 0 = detect automatically. */
    val designMah: Int = 0,
    /** Post a PDF report notification every 7 days. */
    val weeklyReport: Boolean = true,
)

class SettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("batterypulse", Context.MODE_PRIVATE)

    val state = MutableStateFlow(load())

    private fun load(): SettingsState {
        val d = SettingsState()
        return SettingsState(
            theme = sp.getInt("theme", d.theme).coerceIn(0, 2),
            dynamicColor = sp.getBoolean("dynamic", d.dynamicColor),
            lang = sp.getString("lang", d.lang) ?: d.lang,
            resumeOnBoot = sp.getBoolean("boot", d.resumeOnBoot),
            alertFull = sp.getBoolean("alert_full", d.alertFull),
            alertLimit = sp.getBoolean("alert_limit", d.alertLimit),
            limitPct = sp.getInt("limit_pct", d.limitPct).coerceIn(50, 99),
            alertLow = sp.getBoolean("alert_low", d.alertLow),
            lowPct = sp.getInt("low_pct", d.lowPct).coerceIn(5, 50),
            alertTemp = sp.getBoolean("alert_temp", d.alertTemp),
            tempLimitC = sp.getInt("temp_c", d.tempLimitC).coerceIn(30, 60),
            retentionDays = sp.getInt("retention", d.retentionDays).coerceIn(7, 3650),
            designMah = sp.getInt("design_mah", d.designMah).coerceIn(0, 30000),
            weeklyReport = sp.getBoolean("weekly", d.weeklyReport),
        )
    }

    fun update(transform: (SettingsState) -> SettingsState) {
        val n = transform(state.value)
        state.value = n
        sp.edit()
            .putInt("theme", n.theme)
            .putBoolean("dynamic", n.dynamicColor)
            .putString("lang", n.lang)
            .putBoolean("boot", n.resumeOnBoot)
            .putBoolean("alert_full", n.alertFull)
            .putBoolean("alert_limit", n.alertLimit)
            .putInt("limit_pct", n.limitPct)
            .putBoolean("alert_low", n.alertLow)
            .putInt("low_pct", n.lowPct)
            .putBoolean("alert_temp", n.alertTemp)
            .putInt("temp_c", n.tempLimitC)
            .putInt("retention", n.retentionDays)
            .putInt("design_mah", n.designMah)
            .putBoolean("weekly", n.weeklyReport)
            .apply()
    }

    /** True while monitoring is meant to be running (survives process death / reboot). */
    var monitoring: Boolean
        get() = sp.getBoolean("monitoring", false)
        set(v) { sp.edit().putBoolean("monitoring", v).apply() }

    /** Monitoring starts by itself the first time the app is opened. */
    var firstRunDone: Boolean
        get() = sp.getBoolean("first_run_done", false)
        set(v) { sp.edit().putBoolean("first_run_done", v).apply() }

    var lastHeartbeat: Long
        get() = sp.getLong("heartbeat", 0L)
        set(v) { sp.edit().putLong("heartbeat", v).apply() }

    var lastWeekly: Long
        get() = sp.getLong("last_weekly", 0L)
        set(v) { sp.edit().putLong("last_weekly", v).apply() }
}
