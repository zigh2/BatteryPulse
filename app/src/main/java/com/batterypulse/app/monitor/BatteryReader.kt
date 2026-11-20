package com.batterypulse.app.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs
import kotlin.math.roundToInt

/** One reading of everything the OS tells us about the battery. */
data class Snap(
    val level: Int,
    /** True whenever a charger is connected (also at 100 %). */
    val charging: Boolean,
    val plug: Int,
    val status: Int,
    val health: Int,
    val tempDeci: Int,
    val voltageMv: Int,
    /** Signed: positive while charging, negative while discharging. */
    val currentMa: Int?,
    /** Charge counter in µAh, 0 if the device does not report it. */
    val uah: Long,
    val osCycles: Int?,
)

object BatteryReader {

    fun sticky(ctx: Context): Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    fun read(ctx: Context, i: Intent?): Snap? {
        if (i == null) return null
        val raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (raw < 0 || scale <= 0) return null
        val level = (raw * 100f / scale).roundToInt().coerceIn(0, 100)
        val plug = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plug != 0
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        var currentMa: Int? = null
        var uah = 0L
        try {
            val cur = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
            if (cur != Int.MIN_VALUE && cur != 0) {
                // Most devices report µA, a few report mA. Sign conventions differ between vendors,
                // so the sign is taken from the charging state instead.
                val a = abs(cur.toLong())
                val ma = if (a >= 20_000L) (a / 1000).toInt() else a.toInt()
                currentMa = if (charging) ma else -ma
            }
            val c = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Long.MIN_VALUE
            if (c != Long.MIN_VALUE && c > 0) uah = c
        } catch (_: Throwable) {
        }
        val cycles = i.getIntExtra("android.os.extra.CYCLE_COUNT", -1).takeIf { it > 0 }
        return Snap(
            level = level,
            charging = charging,
            plug = plug,
            status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
            health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
            tempDeci = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            currentMa = currentMa,
            uah = uah,
            osCycles = cycles,
        )
    }

    /** Battery design capacity (mAh) from the framework's power profile; null when the device hides it. */
    fun designCapacityMah(ctx: Context): Int? = try {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val obj = cls.getConstructor(Context::class.java).newInstance(ctx)
        val v = cls.getMethod("getBatteryCapacity").invoke(obj) as Double
        if (v in 500.0..30000.0) v.toInt() else null
    } catch (_: Throwable) {
        null
    }

    /** State of health in percent as reported by the OS (Android 14+, only on devices that implement it). */
    fun osHealthPct(ctx: Context): Int? {
        if (Build.VERSION.SDK_INT < 34) return null
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val v = bm.getIntProperty(10) // BATTERY_PROPERTY_STATE_OF_HEALTH
            if (v in 1..100) v else null
        } catch (_: Throwable) {
            null
        }
    }
}
