package com.batterypulse.app.data

import kotlin.math.abs

/** What a row in the event log means. Every row also carries the battery state at that moment. */
object Kind {
    const val LEVEL = 0        // battery level changed by 1 %
    const val PLUGGED = 1      // charger connected
    const val UNPLUGGED = 2    // charger disconnected
    const val FULL = 3         // reached 100 % while charging
    const val SCREEN_ON = 4
    const val SCREEN_OFF = 5
    const val START = 6        // monitoring started
    const val STOP = 7         // monitoring stopped
}

/** Raw values of BatteryManager.EXTRA_PLUGGED bits. */
object Plug {
    const val NONE = 0
    const val AC = 1
    const val USB = 2
    const val WIRELESS = 4
    const val DOCK = 8
}

object SType {
    const val DISCHARGE = 0
    const val CHARGE = 1
}

data class BatteryEvent(
    val id: Long,
    val ts: Long,
    val kind: Int,
    val level: Int,
    /** Level before this event, or -1 when unknown. */
    val prevLevel: Int,
    val charging: Boolean,
    val plug: Int,
    /** Temperature in tenths of a degree Celsius (0 = unknown). */
    val tempDeci: Int,
    val voltageMv: Int,
    /** Signed current in mA: positive while charging, negative while discharging. */
    val currentMa: Int?,
    /** Charge counter in µAh (0 = unknown). */
    val uah: Long,
    val screenOn: Boolean,
    val sessionId: Long,
    /** For LEVEL rows: time since the previous level change of the same session, or -1 when that step is partial. */
    val stepMs: Long = -1L,
) {
    val delta: Int get() = if (prevLevel >= 0) level - prevLevel else 0

    /** Time this event took per 1 % (a jump of 2 % counts half), or -1 when unknown. */
    val perPctMs: Long get() = if (kind == Kind.LEVEL && stepMs > 0 && delta != 0) stepMs / abs(delta) else -1L
}

/** One continuous charging or discharging period. */
data class Session(
    val id: Long,
    val type: Int,
    val startMs: Long,
    val endMs: Long?,
    val startLevel: Int,
    val endLevel: Int,
    val plug: Int,
    /** When 100 % was reached (charging sessions only). */
    val fullMs: Long?,
    val screenOnMs: Long,
    val startUah: Long,
    val endUah: Long,
    val maxTempDeci: Int,
    val sumMa: Long,
    val nMa: Int,
) {
    val charge: Boolean get() = type == SType.CHARGE
    val deltaPct: Int get() = endLevel - startLevel
    val avgMa: Int? get() = if (nMa > 0) (sumMa / nMa).toInt() else null
    val mAh: Int? get() = if (startUah > 0 && endUah > 0) (abs(endUah - startUah) / 1000).toInt() else null

    fun endOr(now: Long): Long = endMs ?: now
    fun durationMs(now: Long): Long = (endOr(now) - startMs).coerceAtLeast(0L)

    /** Time spent actually gaining/losing percent (charging stops counting once 100 % is reached). */
    fun activeMs(now: Long): Long {
        val end = if (charge) (fullMs ?: endOr(now)) else endOr(now)
        return (end - startMs).coerceAtLeast(0L)
    }

    /** Percent per hour, or null when the session is too short to say anything. */
    fun speedPctPerHour(now: Long): Double? {
        val d = abs(deltaPct)
        val h = activeMs(now) / 3_600_000.0
        return if (d >= 1 && h >= 0.03) d / h else null
    }
}
