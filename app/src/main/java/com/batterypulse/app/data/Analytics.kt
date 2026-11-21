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

/** A chart point. A NaN [y] marks a break in the line (tracking was off). */
data class XY(val x: Long, val y: Float)

/** Statistics of "time per 1 %". */
data class StepStats(
    val count: Int,
    val avgMs: Long,
    val medianMs: Long,
    val minMs: Long,
    val maxMs: Long,
    val minTs: Long,
    val maxTs: Long,
)

data class ChargerStat(
    val plug: Int,
    val sessions: Int,
    val pctH: Double?,
    val avgMa: Int?,
    val avgW: Double?,
)

data class Idle(val pctH: Double?, val hours: Double, val worstPctH: Double?, val worstTs: Long?)

data class Report(
    val from: Long = 0L,
    val to: Long = 0L,
    // level chart
    val levelPoints: List<XY> = emptyList(),
    val chargeBands: List<LongRange> = emptyList(),
    /** Periods with no data (tracking stopped). */
    val gaps: List<LongRange> = emptyList(),
    val gapMs: Long = 0L,
    // charging
    val chargeSessions: Int = 0,
    val avgChargePctH: Double? = null,
    val maxChargePctH: Double? = null,
    val avgChargeMa: Int? = null,
    val maxChargeMa: Int? = null,
    val avgChargeDurMs: Long? = null,
    val avgTimeTo100Ms: Long? = null,
    val avg80to100Ms: Long? = null,
    val fullCharges: Int = 0,
    val chargedPct: Int = 0,
    val chargers: List<ChargerStat> = emptyList(),
    val chargeSpeeds: List<XY> = emptyList(),
    // time per 1 %
    val chargeStep: StepStats? = null,
    val dischargeStep: StepStats? = null,
    /** Average ms per 1 % at levels 0-19, 20-79 and 80-100. */
    val chargeBandMs: List<Long?> = emptyList(),
    val dischargeBandMs: List<Long?> = emptyList(),
    /** Average minutes per 1 % while charging, for levels 1-10, 11-20, ... 91-100. */
    val chargeCurve: List<Float> = emptyList(),
    // discharging
    val dischargeSessions: Int = 0,
    val dischargePctH: Double? = null,
    val screenOnPctH: Double? = null,
    val screenOffPctH: Double? = null,
    val avgDischargeMa: Int? = null,
    val dischargedPct: Int = 0,
    val idle: Idle = Idle(null, 0.0, null, null),
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
    /** Average time per 1 % with the screen on / off, derived from the speeds. */
    val screenOnStepMs: Long? get() = screenOnPctH?.let { if (it > 0.0) (HOUR_MS / it).toLong() else null }
    val screenOffStepMs: Long? get() = screenOffPctH?.let { if (it > 0.0) (HOUR_MS / it).toLong() else null }
}

data class Capacity(
    val estimatedMah: Int? = null,
    val designMah: Int? = null,
    val osHealthPct: Int? = null,
    val osCycles: Int? = null,
    /** Weekly median of the measured capacity (mAh). */
    val trend: List<XY> = emptyList(),
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

    // ------------------------------------------------------------------ time per 1 %

    fun stepStats(events: List<BatteryEvent>, charging: Boolean): StepStats? {
        val st = events.filter { it.charging == charging && it.perPctMs > 0 }
        if (st.isEmpty()) return null
        val sorted = st.map { it.perPctMs }.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        val fast = st.minBy { it.perPctMs }
        val slow = st.maxBy { it.perPctMs }
        return StepStats(st.size, sorted.average().toLong(), median, fast.perPctMs, slow.perPctMs, fast.ts, slow.ts)
    }

    /** Average time per 1 % at levels 0-19, 20-79 and 80-100. */
    fun bandAverages(events: List<BatteryEvent>, charging: Boolean): List<Long?> {
        val b = List(3) { ArrayList<Long>() }
        for (e in events) {
            if (e.charging != charging || e.perPctMs <= 0) continue
            val i = when {
                e.level < 20 -> 0
                e.level < 80 -> 1
                else -> 2
            }
            b[i].add(e.perPctMs)
        }
        return b.map { if (it.isEmpty()) null else it.average().toLong() }
    }

    /** Minutes per 1 % while charging for each tenth of the battery. */
    fun chargeCurve(events: List<BatteryEvent>): List<Float> {
        val sum = LongArray(10)
        val n = IntArray(10)
        for (e in events) {
            if (!e.charging || e.perPctMs <= 0) continue
            val i = ((e.level - 1) / 10).coerceIn(0, 9)
            sum[i] += e.perPctMs
            n[i]++
        }
        return List(10) { if (n[it] == 0) 0f else sum[it] / n[it] / 60_000f }
    }

    /** Average time the last part of charging (80 % to 100 %) took. */
    fun avg80to100(events: List<BatteryEvent>): Long? {
        val times = events.filter { it.kind == Kind.LEVEL && it.charging }
            .groupBy { it.sessionId }
            .mapNotNull { (_, list) ->
                val l = list.sortedBy { it.ts }
                val a = l.firstOrNull { it.level >= 80 && it.prevLevel in 0..79 }
                val b = l.firstOrNull { it.level >= 100 }
                if (a != null && b != null && b.ts > a.ts) b.ts - a.ts else null
            }
        return if (times.isEmpty()) null else times.average().toLong()
    }

    // ------------------------------------------------------------------ gaps and standby

    /** Periods between a STOP row and the next row (tracking was off, nothing was recorded). */
    fun findGaps(events: List<BatteryEvent>, now: Long): List<LongRange> {
        val out = ArrayList<LongRange>()
        var stop: Long? = null
        for (e in events.sortedBy { it.ts }) {
            if (e.kind == Kind.STOP) {
                if (stop == null) stop = e.ts
            } else if (stop != null) {
                if (e.ts - stop >= 2 * 60_000L) out.add(stop..e.ts)
                stop = null
            }
        }
        val s = stop
        if (s != null && now - s >= 2 * 60_000L) out.add(s..now)
        return out
    }

    /** Battery drain while the screen stayed off for at least an hour (night / idle). */
    fun idleDrain(events: List<BatteryEvent>, sessions: List<Session>, screen: List<Pair<Long, Long>>, now: Long): Idle {
        val lv = events.sortedBy { it.ts }
        fun levelAt(t: Long): Int? {
            var r: Int? = null
            for (e in lv) {
                if (e.ts <= t) r = e.level else break
            }
            return r
        }
        var drop = 0.0
        var hours = 0.0
        var worst: Double? = null
        var worstTs: Long? = null
        val scr = screen.sortedBy { it.first }
        for (s in sessions) {
            if (s.charge) continue
            val a0 = s.startMs
            val a1 = s.endOr(now)
            val off = ArrayList<Pair<Long, Long>>()
            var cursor = a0
            for (w in scr) {
                if (w.second <= a0 || w.first >= a1) continue
                val on0 = maxOf(w.first, a0)
                if (on0 > cursor) off.add(Pair(cursor, on0))
                cursor = maxOf(cursor, minOf(w.second, a1))
            }
            if (a1 > cursor) off.add(Pair(cursor, a1))
            for (o in off) {
                if (o.second - o.first < HOUR_MS) continue
                val hi = levelAt(o.first) ?: continue
                val lo = levelAt(o.second) ?: continue
                if (lo > hi) continue
                val h = (o.second - o.first) / 3_600_000.0
                drop += hi - lo
                hours += h
                val rate = (hi - lo) / h
                if (worst == null || rate > worst) {
                    worst = rate
                    worstTs = o.first
                }
            }
        }
        return Idle(if (hours > 0.0) drop / hours else null, hours, worst, worstTs)
    }

    // ------------------------------------------------------------------ capacity

    private fun capacityOf(s: Session): Double? {
        if (s.endMs == null || abs(s.deltaPct) < 20 || s.startUah <= 0 || s.endUah <= 0) return null
        if (if (s.charge) s.endUah <= s.startUah else s.endUah >= s.startUah) return null
        val v = abs(s.endUah - s.startUah) / 1000.0 / (abs(s.deltaPct) / 100.0)
        return if (v in 500.0..30000.0) v else null
    }

    private fun median(values: List<Double>): Double {
        val v = values.sorted()
        val mid = v.size / 2
        return if (v.size % 2 == 1) v[mid] else (v[mid - 1] + v[mid]) / 2.0
    }

    /** Median of the capacity measured by the charge counter over the latest long sessions. */
    fun estimateCapacityMah(sessions: List<Session>): Int? {
        val est = sessions.sortedBy { it.startMs }.mapNotNull { capacityOf(it) }.takeLast(15)
        return if (est.isEmpty()) null else median(est).toInt()
    }

    /** Weekly median of the measured capacity, oldest first. */
    fun capacityTrend(sessions: List<Session>): List<XY> =
        sessions.mapNotNull { s -> capacityOf(s)?.let { Pair(s.endMs!!, it) } }
            .groupBy { it.first / (7 * DAY_MS) }
            .toSortedMap()
            .map { (_, list) -> XY(list.maxOf { it.first }, median(list.map { it.second }).toFloat()) }

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

    // ------------------------------------------------------------------ report

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
        val chargers = eligibleCharge.groupBy { it.plug }.map { (p, list) ->
            val h = list.sumOf { it.activeMs(now) } / 3_600_000.0
            val d = list.sumOf { abs(it.deltaPct) }
            val mas = list.mapNotNull { it.avgMa }
            val watts = ev.filter { it.charging && it.plug == p && it.currentMa != null && it.voltageMv > 0 }
                .map { it.voltageMv / 1000.0 * it.currentMa!! / 1000.0 }
            ChargerStat(
                plug = p,
                sessions = list.size,
                pctH = if (h > 0.0) d / h else null,
                avgMa = if (mas.isEmpty()) null else mas.average().toInt(),
                avgW = if (watts.isEmpty()) null else watts.average(),
            )
        }.sortedByDescending { it.pctH ?: 0.0 }
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

        // ---- temperature / level chart (a NaN point breaks the line where tracking stopped)
        val temps = ev.filter { it.kind == Kind.LEVEL && it.tempDeci > 0 }
        val levelPts = ArrayList<XY>()
        for (e in ev) {
            if (e.kind == Kind.SCREEN_ON || e.kind == Kind.SCREEN_OFF) continue
            levelPts.add(XY(e.ts, e.level.toFloat()))
            if (e.kind == Kind.STOP) levelPts.add(XY(e.ts, Float.NaN))
        }
        val gaps = findGaps(ev, now)

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

        val totalDown = ev.filter { it.kind == Kind.LEVEL && !it.charging && it.delta < 0 }.sumOf { -it.delta }
        val totalUp = ev.filter { it.kind == Kind.LEVEL && it.charging && it.delta > 0 }.sumOf { it.delta }

        return Report(
            from = from,
            to = to,
            levelPoints = levelPts,
            chargeBands = charge.map { maxOf(it.startMs, from)..minOf(it.endOr(now), now) }.filter { it.last > it.first },
            gaps = gaps,
            gapMs = gaps.sumOf { (minOf(it.last, now) - maxOf(it.first, from)).coerceAtLeast(0L) },
            chargeSessions = charge.size,
            avgChargePctH = if (chargeHours > 0.0 && chargePct > 0) chargePct / chargeHours else null,
            maxChargePctH = eligibleCharge.mapNotNull { it.speedPctPerHour(now) }.maxOrNull(),
            avgChargeMa = if (chargeMas.isEmpty()) null else chargeMas.average().toInt(),
            maxChargeMa = chargeMaxMa,
            avgChargeDurMs = if (finished.isEmpty()) null else finished.sumOf { it.durationMs(now) } / finished.size,
            avgTimeTo100Ms = if (to100.isEmpty()) null else to100.sum() / to100.size,
            avg80to100Ms = avg80to100(ev),
            fullCharges = charge.count { it.fullMs != null },
            chargedPct = totalUp,
            chargers = chargers,
            chargeSpeeds = chargeSpeeds,
            chargeStep = stepStats(ev, true),
            dischargeStep = stepStats(ev, false),
            chargeBandMs = bandAverages(ev, true),
            dischargeBandMs = bandAverages(ev, false),
            chargeCurve = chargeCurve(ev),
            dischargeSessions = dis.size,
            dischargePctH = if (disHours > 0.0 && disPct > 0) disPct / disHours else null,
            screenOnPctH = split.first,
            screenOffPctH = split.second,
            avgDischargeMa = if (disMas.isEmpty()) null else disMas.average().toInt(),
            dischargedPct = totalDown,
            idle = idleDrain(ev, dis, screen, now),
            screenOnMs = screenTotal,
            avgDailyScreenOnMs = (screenTotal / spanDays).toLong(),
            screenOnCount = clipped.size,
            longestScreenOnMs = clipped.maxOfOrNull { it.second - it.first } ?: 0L,
            avgTempC = if (temps.isEmpty()) null else temps.map { it.tempDeci }.average() / 10.0,
            maxTempC = temps.maxOfOrNull { it.tempDeci }?.let { it / 10.0 },
            tempPoints = temps.map { XY(it.ts, it.tempDeci / 10f) },
            days = days.takeLast(60),
            eventCount = ev.size,
        )
    }
}
