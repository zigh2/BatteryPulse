package com.batterypulse.app.data

import java.util.Calendar
import kotlin.math.abs

const val HOUR_MS = 3_600_000L
const val DAY_MS = 24 * HOUR_MS

data class DayStat(
    val dayStart: Long,
    val screenOnMs: Long,
    val dischargedPct: Int,
    val chargedPct: Int,
)

data class XY(val x: Long, val y: Float)

data class Report(
    val from: Long = 0L,
    val to: Long = 0L,
    // level chart
    val levelPoints: List<XY> = emptyList(),
    val chargeBands: List<LongRange> = emptyList(),
    // charging
    val chargeSessions: Int = 0,
    val avgChargePctH: Double? = null,
    val maxChargePctH: Double? = null,
    val avgChargeMa: Int? = null,
    val maxChargeMa: Int? = null,
    val avgChargeDurMs: Long? = null,
    val avgTimeTo100Ms: Long? = null,
    val fullCharges: Int = 0,
    val chargedPct: Int = 0,
    val chargeByPlug: Map<Int, Double> = emptyMap(),
    val chargeSpeeds: List<XY> = emptyList(),
    // discharging
    val dischargeSessions: Int = 0,
    val dischargePctH: Double? = null,
    val screenOnPctH: Double? = null,
    val screenOffPctH: Double? = null,
    val avgDischargeMa: Int? = null,
    val dischargedPct: Int = 0,
    // screen
    val screenOnMs: Long = 0L,
    val avgDailyScreenOnMs: Long = 0L,
    val screenOnCount: Int = 0,
    val longestScreenOnMs: Long = 0L,
    // temperature
    val avgTempC: Double? = null,
    val maxTempC: Double? = null,
    val tempPoints: List<XY> = emptyList(),
    // per-day
    val days: List<DayStat> = emptyList(),
    val eventCount: Int = 0,
) {
    /** Estimated runtime of a full battery at the average discharge speed, in hours. */
    val fullLifeHours: Double? get() = dischargePctH?.let { if (it > 0.0) 100.0 / it else null }
    val screenOnLifeHours: Double? get() = screenOnPctH?.let { if (it > 0.0) 100.0 / it else null }
    val screenOffLifeHours: Double? get() = screenOffPctH?.let { if (it > 0.0) 100.0 / it else null }
    /** Equivalent full cycles in this period (100 % discharged = 1 cycle). */
    val equivalentCycles: Double get() = dischargedPct / 100.0
}

data class Capacity(
    val estimatedMah: Int? = null,
    val designMah: Int? = null,
    val osHealthPct: Int? = null,
    val osCycles: Int? = null,
) {
    /** Health from the measured capacity, falling back to what the OS reports. */
    val healthPct: Int?
        get() {
            val e = estimatedMah
            val d = designMah
            if (e != null && d != null && d > 0) return (e * 100 / d).coerceIn(1, 100)
            return osHealthPct
        }
}

object Analytics {

    /** Start of the local day containing [ts]. */
    fun dayStart(ts: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun nextDay(dayStart: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = dayStart
        c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Screen-on intervals (start, end) rebuilt from the event log. */
    fun screenIntervals(events: List<BatteryEvent>, now: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var onSince: Long? = null
        for (e in events.sortedBy { it.ts }) {
            when (e.kind) {
                Kind.SCREEN_ON -> if (onSince == null) onSince = e.ts
                Kind.START -> if (e.screenOn && onSince == null) onSince = e.ts
                Kind.SCREEN_OFF, Kind.STOP -> {
                    val s = onSince
                    if (s != null) {
                        out.add(Pair(s, e.ts))
                        onSince = null
                    }
                }
            }
        }
        val s = onSince
        if (s != null && now > s) out.add(Pair(s, now))
        return out
    }

    private fun overlap(a0: Long, a1: Long, b0: Long, b1: Long): Long =
        (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0L)

    /**
     * Discharge speed while the screen was on / off. Each 1 % step is classified by how much of it the screen
     * was on; steps that mix both states are ignored so the two rates stay honest.
     */
    fun screenSplit(events: List<BatteryEvent>, screen: List<Pair<Long, Long>>): Pair<Double?, Double?> {
        val levels = events.filter { it.kind == Kind.LEVEL && !it.charging }.sortedBy { it.ts }
        var dropOn = 0.0
        var msOn = 0L
        var dropOff = 0.0
        var msOff = 0L
        for (i in 1 until levels.size) {
            val a = levels[i - 1]
            val b = levels[i]
            if (a.sessionId != b.sessionId) continue
            val drop = a.level - b.level
            val dt = b.ts - a.ts
            if (drop <= 0 || dt <= 0L || dt > 12 * HOUR_MS) continue
            var on = 0L
            for (s in screen) on += overlap(a.ts, b.ts, s.first, s.second)
            val share = on.toDouble() / dt
            if (share >= 0.9) {
                dropOn += drop
                msOn += dt
            } else if (share <= 0.1) {
                dropOff += drop
                msOff += dt
            }
        }
        fun rate(d: Double, ms: Long): Double? = if (ms >= 5 * 60_000L && d >= 1.0) d / (ms / 3_600_000.0) else null
        return Pair(rate(dropOn, msOn), rate(dropOff, msOff))
    }

    /** Median of the capacity measured by the charge counter over long charge / discharge sessions. */
    fun estimateCapacityMah(sessions: List<Session>): Int? {
        val est = sessions
            .filter { it.endMs != null && abs(it.deltaPct) >= 20 && it.startUah > 0 && it.endUah > 0 }
            .filter { if (it.charge) it.endUah > it.startUah else it.endUah < it.startUah }
            .sortedBy { it.startMs }
            .takeLast(15)
            .map { abs(it.endUah - it.startUah) / 1000.0 / (abs(it.deltaPct) / 100.0) }
            .filter { it in 500.0..30000.0 }
            .sorted()
        if (est.isEmpty()) return null
        val mid = est.size / 2
        val m = if (est.size % 2 == 1) est[mid] else (est[mid - 1] + est[mid]) / 2.0
        return m.toInt()
    }

    /** Remaining time (ms) until 100 % / 0 % at the speed of the most recent 1 % steps, or null. */
    fun etaMs(recentLevelEvents: List<BatteryEvent>, level: Int, charging: Boolean): Long? {
        val ev = recentLevelEvents.sortedBy { it.ts }.takeLast(6)
        if (ev.size < 2) return null
        val dl = ev.last().level - ev.first().level
        val dt = (ev.last().ts - ev.first().ts) / 3_600_000.0
        if (dt <= 0.0 || dl == 0) return null
        val rate = dl / dt // percent per hour, signed
        val hours = when {
            charging && rate > 0 -> (100 - level) / rate
            !charging && rate < 0 -> level / -rate
            else -> return null
        }
        return if (hours in 0.0..99.0) (hours * HOUR_MS).toLong() else null
    }

    fun build(events: List<BatteryEvent>, sessions: List<Session>, from: Long, to: Long): Report {
        val now = to
        val ev = events.sortedBy { it.ts }
        val charge = sessions.filter { it.charge }
        val dis = sessions.filter { !it.charge }

        // ---- charging
        val chargeSpeeds = charge.mapNotNull { s -> s.speedPctPerHour(now)?.let { XY(s.startMs, it.toFloat()) } }
        val eligibleCharge = charge.filter { it.speedPctPerHour(now) != null }
        val chargeHours = eligibleCharge.sumOf { it.activeMs(now) } / 3_600_000.0
        val chargePct = eligibleCharge.sumOf { abs(it.deltaPct) }
        val byPlug = eligibleCharge.groupBy { it.plug }.mapNotNull { (p, list) ->
            val h = list.sumOf { it.activeMs(now) } / 3_600_000.0
            val d = list.sumOf { abs(it.deltaPct) }
            if (h > 0.0) Pair(p, d / h) else null
        }.toMap()
        val chargeMas = charge.mapNotNull { it.avgMa }
        val chargeMaxMa = ev.filter { it.charging && it.currentMa != null }.maxOfOrNull { it.currentMa!! }
        val finished = charge.filter { it.endMs != null && abs(it.deltaPct) >= 1 }
        val to100 = charge.filter { it.fullMs != null && it.startLevel < 100 }.map { it.fullMs!! - it.startMs }

        // ---- discharging
        val eligibleDis = dis.filter { it.speedPctPerHour(now) != null }
        val disHours = eligibleDis.sumOf { it.activeMs(now) } / 3_600_000.0
        val disPct = eligibleDis.sumOf { abs(it.deltaPct) }
        val screen = screenIntervals(ev, now)
        val split = screenSplit(ev, screen)
        val disMas = dis.mapNotNull { it.avgMa }.map { abs(it) }

        // ---- screen
        val clipped = screen.map { Pair(maxOf(it.first, from), minOf(it.second, now)) }.filter { it.second > it.first }
        val screenTotal = clipped.sumOf { it.second - it.first }
        val spanDays = ((now - from).toDouble() / DAY_MS).coerceAtLeast(1.0)

        // ---- temperature / level chart
        val temps = ev.filter { it.kind == Kind.LEVEL && it.tempDeci > 0 }
        val levelPts = ev.filter { it.kind != Kind.SCREEN_ON && it.kind != Kind.SCREEN_OFF }
            .map { XY(it.ts, it.level.toFloat()) }

        // ---- per day
        val days = ArrayList<DayStat>()
        var d = dayStart(from)
        var guard = 0
        while (d <= now && guard < 400) {
            val dEnd = nextDay(d)
            val scr = clipped.sumOf { overlap(it.first, it.second, d, dEnd) }
            var up = 0
            var down = 0
            for (e in ev) {
                if (e.kind != Kind.LEVEL || e.ts < d || e.ts >= dEnd) continue
                if (e.charging && e.delta > 0) up += e.delta
                if (!e.charging && e.delta < 0) down += -e.delta
            }
            days.add(DayStat(d, scr, down, up))
            d = dEnd
            guard++
        }
        val shownDays = days.takeLast(60)

        val totalDown = ev.filter { it.kind == Kind.LEVEL && !it.charging && it.delta < 0 }.sumOf { -it.delta }
        val totalUp = ev.filter { it.kind == Kind.LEVEL && it.charging && it.delta > 0 }.sumOf { it.delta }

        return Report(
            from = from,
            to = to,
            levelPoints = levelPts,
            chargeBands = charge.map { maxOf(it.startMs, from)..minOf(it.endOr(now), now) }.filter { it.last > it.first },
            chargeSessions = charge.size,
            avgChargePctH = if (chargeHours > 0.0 && chargePct > 0) chargePct / chargeHours else null,
            maxChargePctH = eligibleCharge.mapNotNull { it.speedPctPerHour(now) }.maxOrNull(),
            avgChargeMa = if (chargeMas.isEmpty()) null else chargeMas.average().toInt(),
            maxChargeMa = chargeMaxMa,
            avgChargeDurMs = if (finished.isEmpty()) null else finished.sumOf { it.durationMs(now) } / finished.size,
            avgTimeTo100Ms = if (to100.isEmpty()) null else to100.sum() / to100.size,
            fullCharges = charge.count { it.fullMs != null },
            chargedPct = totalUp,
            chargeByPlug = byPlug,
            chargeSpeeds = chargeSpeeds,
            dischargeSessions = dis.size,
            dischargePctH = if (disHours > 0.0 && disPct > 0) disPct / disHours else null,
            screenOnPctH = split.first,
            screenOffPctH = split.second,
            avgDischargeMa = if (disMas.isEmpty()) null else disMas.average().toInt(),
            dischargedPct = totalDown,
            screenOnMs = screenTotal,
            avgDailyScreenOnMs = (screenTotal / spanDays).toLong(),
            screenOnCount = clipped.size,
            longestScreenOnMs = clipped.maxOfOrNull { it.second - it.first } ?: 0L,
            avgTempC = if (temps.isEmpty()) null else temps.map { it.tempDeci }.average() / 10.0,
            maxTempC = temps.maxOfOrNull { it.tempDeci }?.let { it / 10.0 },
            tempPoints = temps.map { XY(it.ts, it.tempDeci / 10f) },
            days = shownDays,
            eventCount = ev.size,
        )
    }
}
