package com.batterypulse.app.data

import android.content.Context
import android.os.BatteryManager
import com.batterypulse.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Number and date formatting. Latin digits everywhere so exports and screens always agree. */
object Fmt {
    private fun f(p: String) = SimpleDateFormat(p, Locale.US)

    fun dateTime(ms: Long): String = f("yyyy-MM-dd HH:mm:ss").format(Date(ms))
    fun time(ms: Long): String = f("HH:mm:ss").format(Date(ms))
    fun hm(ms: Long): String = f("HH:mm").format(Date(ms))
    fun date(ms: Long): String = f("yyyy-MM-dd").format(Date(ms))
    fun dayShort(ms: Long): String = f("MM-dd").format(Date(ms))
    fun dayHeader(ms: Long): String = f("EEE, d MMM yyyy").format(Date(ms))

    fun num1(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "%.1f", v)
    fun num0(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "%.0f", v)

    fun pctH(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "%.1f %%/h", v)
    fun ma(v: Int?): String = if (v == null) "—" else "$v mA"
    fun mah(v: Int?): String = if (v == null) "—" else "$v mAh"
    fun temp(deci: Int): String = if (deci <= 0) "—" else String.format(Locale.US, "%.1f °C", deci / 10.0)
    fun tempC(c: Double?): String = if (c == null) "—" else String.format(Locale.US, "%.1f °C", c)
    fun volt(mv: Int): String = if (mv <= 0) "—" else String.format(Locale.US, "%.2f V", mv / 1000.0)
    fun watt(mv: Int, ma: Int?): String =
        if (mv <= 0 || ma == null) "—" else String.format(Locale.US, "%.1f W", kotlin.math.abs(mv / 1000.0 * ma / 1000.0))
    fun hours(h: Double?): String = if (h == null) "—" else String.format(Locale.US, "%.1f h", h)

    /** "2h 05m", "14m 30s", "45s" with localized unit letters. */
    fun duration(c: Context, ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val uh = c.getString(R.string.unit_h)
        val um = c.getString(R.string.unit_m)
        val us = c.getString(R.string.unit_s)
        return when {
            h > 0 -> "$h$uh ${String.format(Locale.US, "%02d", m)}$um"
            m > 0 -> "$m$um ${String.format(Locale.US, "%02d", sec)}$us"
            else -> "$sec$us"
        }
    }

    /** "14m 20s (05-12 14:20)": a duration with the moment it happened. */
    fun durationAt(c: Context, ms: Long, ts: Long): String = "${duration(c, ms)} (${dayShort(ts)} ${hm(ts)})"

    fun charger(c: ChargerStat): String = listOfNotNull(
        c.pctH?.let { pctH(it) },
        c.avgMa?.let { ma(it) },
        c.avgW?.let { String.format(Locale.US, "%.1f W", it) },
    ).joinToString(" · ").ifEmpty { "—" }

    fun durationOrDash(c: Context, ms: Long?): String = if (ms == null) "—" else duration(c, ms)

    fun plug(c: Context, plug: Int): String = when {
        plug and Plug.WIRELESS != 0 -> c.getString(R.string.plug_wireless)
        plug and Plug.AC != 0 -> c.getString(R.string.plug_ac)
        plug and Plug.USB != 0 -> c.getString(R.string.plug_usb)
        plug and Plug.DOCK != 0 -> c.getString(R.string.plug_dock)
        else -> c.getString(R.string.plug_none)
    }

    fun health(c: Context, h: Int): String = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> c.getString(R.string.health_good)
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> c.getString(R.string.health_overheat)
        BatteryManager.BATTERY_HEALTH_DEAD -> c.getString(R.string.health_dead)
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> c.getString(R.string.health_over_voltage)
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> c.getString(R.string.health_failure)
        BatteryManager.BATTERY_HEALTH_COLD -> c.getString(R.string.health_cold)
        else -> c.getString(R.string.health_unknown)
    }

    fun kindLabel(c: Context, k: Int): String = c.getString(
        when (k) {
            Kind.LEVEL -> R.string.ev_level
            Kind.PLUGGED -> R.string.ev_plugged
            Kind.UNPLUGGED -> R.string.ev_unplugged
            Kind.FULL -> R.string.ev_full
            Kind.SCREEN_ON -> R.string.ev_screen_on
            Kind.SCREEN_OFF -> R.string.ev_screen_off
            Kind.START -> R.string.ev_start
            else -> R.string.ev_stop
        }
    )
}
