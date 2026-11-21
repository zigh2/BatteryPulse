package com.batterypulse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsTest {

    private val min = 60_000L

    private fun ev(
        ts: Long, level: Int, prev: Int = -1, kind: Int = Kind.LEVEL, charging: Boolean = false,
        session: Long = 1L, screen: Boolean = false, uah: Long = 0L, step: Long = -1L,
    ) = BatteryEvent(
        id = 0, ts = ts, kind = kind, level = level, prevLevel = prev, charging = charging, plug = if (charging) Plug.AC else 0,
        tempDeci = 300, voltageMv = 4000, currentMa = null, uah = uah, screenOn = screen, sessionId = session, stepMs = step,
    )

    private fun session(
        type: Int, start: Long, end: Long?, from: Int, to: Int, full: Long? = null,
        startUah: Long = 0, endUah: Long = 0,
    ) = Session(
        id = 1, type = type, startMs = start, endMs = end, startLevel = from, endLevel = to, plug = Plug.AC,
        fullMs = full, screenOnMs = 0, startUah = startUah, endUah = endUah, maxTempDeci = 350, sumMa = 0, nMa = 0,
    )

    @Test
    fun chargingSpeedStopsCountingAtFull() {
        val s = session(SType.CHARGE, 0, 2 * HOUR_MS, 20, 100, full = HOUR_MS)
        assertEquals(80.0, s.speedPctPerHour(2 * HOUR_MS)!!, 0.001)
    }

    @Test
    fun tinySessionsHaveNoSpeed() {
        val s = session(SType.DISCHARGE, 0, 60_000L, 50, 50)
        assertNull(s.speedPctPerHour(60_000L))
    }

    @Test
    fun etaWhileCharging() {
        // +1 % every 6 minutes = 10 %/h, 50 % left -> 5 hours
        val events = (0..5).map { ev(it * 6 * min, 50 + it, 49 + it, charging = true) }
        val eta = Analytics.etaMs(events, 55, charging = true)
        assertNotNull(eta)
        assertEquals(4.5 * HOUR_MS, eta!!.toDouble(), 1000.0)
    }

    @Test
    fun etaIsNullWhenDirectionDoesNotMatch() {
        val events = (0..5).map { ev(it * 6 * min, 50 - it, 51 - it) }
        assertNull(Analytics.etaMs(events, 45, charging = true))
    }

    @Test
    fun capacityIsEstimatedFromChargeCounter() {
        // 20 % -> 80 % moved 2400 mAh, so the full battery is 4000 mAh
        val s = session(SType.CHARGE, 0, HOUR_MS, 20, 80, startUah = 1_000_000, endUah = 3_400_000)
        assertEquals(4000, Analytics.estimateCapacityMah(listOf(s)))
    }

    @Test
    fun shortSessionsAreIgnoredForCapacity() {
        val s = session(SType.CHARGE, 0, HOUR_MS, 50, 60, startUah = 1_000_000, endUah = 1_400_000)
        assertNull(Analytics.estimateCapacityMah(listOf(s)))
    }

    @Test
    fun screenIntervalsAreRebuiltFromEvents() {
        val events = listOf(
            ev(0, 90, kind = Kind.START, screen = true),
            ev(100, 90, kind = Kind.SCREEN_OFF),
            ev(200, 90, kind = Kind.SCREEN_ON, screen = true),
            ev(300, 90, kind = Kind.STOP),
        )
        assertEquals(listOf(Pair(0L, 100L), Pair(200L, 300L)), Analytics.screenIntervals(events, 400L))
    }

    @Test
    fun openScreenIntervalRunsUntilNow() {
        val events = listOf(ev(0, 90, kind = Kind.SCREEN_ON, screen = true))
        assertEquals(listOf(Pair(0L, 500L)), Analytics.screenIntervals(events, 500L))
    }

    @Test
    fun dischargeIsSplitByScreenState() {
        val events = listOf(
            ev(0, 90, 91),
            ev(10 * min, 89, 90),
            ev(20 * min, 88, 89),
            ev(50 * min, 87, 88),
        )
        val screen = listOf(Pair(0L, 10 * min))
        val (on, off) = Analytics.screenSplit(events, screen)
        assertEquals(6.0, on!!, 0.01)   // 1 % in 10 minutes
        assertEquals(3.0, off!!, 0.01)  // 2 % in 40 minutes
    }

    @Test
    fun reportSummarisesChargingSessions() {
        val s = session(SType.CHARGE, 0, 2 * HOUR_MS, 20, 100, full = HOUR_MS)
        val r = Analytics.build(emptyList(), listOf(s), 0L, 2 * HOUR_MS)
        assertEquals(80.0, r.avgChargePctH!!, 0.001)
        assertEquals(1, r.fullCharges)
        assertEquals(HOUR_MS, r.avgTimeTo100Ms)
        assertEquals(1, r.chargeSessions)
    }

    @Test
    fun reportCountsDischargedPercentAndLifeEstimate() {
        val events = listOf(
            ev(0, 100, 100),
            ev(30 * min, 95, 100),
            ev(60 * min, 90, 95),
        )
        val s = session(SType.DISCHARGE, 0, HOUR_MS, 100, 90)
        val r = Analytics.build(events, listOf(s), 0L, HOUR_MS)
        assertEquals(10, r.dischargedPct)
        assertEquals(10.0, r.dischargePctH!!, 0.001)
        assertEquals(10.0, r.fullLifeHours!!, 0.001)
        assertEquals(0.1, r.equivalentCycles, 0.0001)
    }

    @Test
    fun healthFallsBackToOsValue() {
        assertEquals(85, Capacity(estimatedMah = 4250, designMah = 5000).healthPct)
        assertEquals(91, Capacity(osHealthPct = 91).healthPct)
        assertTrue(Capacity().healthPct == null)
    }

    @Test
    fun stepStatsUsePerPercentTime() {
        val events = listOf(
            ev(10 * min, 89, 90, step = 10 * min),
            ev(40 * min, 88, 89, step = 30 * min),
            ev(100 * min, 86, 88, step = 60 * min), // a jump of 2 % counts as 30 min each
            ev(110 * min, 85, 86),                  // partial step, ignored
        )
        val st = Analytics.stepStats(events, charging = false)!!
        assertEquals(3, st.count)
        assertEquals(10 * min, st.minMs)
        assertEquals(30 * min, st.maxMs)
        assertEquals(30 * min, st.medianMs)
        assertNull(Analytics.stepStats(events, charging = true))
    }

    @Test
    fun chargingBandsAndCurve() {
        val events = listOf(
            ev(0, 10, 9, charging = true, step = 2 * min),
            ev(0, 50, 49, charging = true, step = 4 * min),
            ev(0, 90, 89, charging = true, step = 10 * min),
        )
        val bands = Analytics.bandAverages(events, charging = true)
        assertEquals(listOf<Long?>(2 * min, 4 * min, 10 * min), bands)
        val curve = Analytics.chargeCurve(events)
        assertEquals(2f, curve[0], 0.001f)
        assertEquals(4f, curve[4], 0.001f)
        assertEquals(10f, curve[8], 0.001f)
    }

    @Test
    fun timeFrom80To100() {
        val events = listOf(
            ev(0, 79, 78, charging = true),
            ev(10 * min, 80, 79, charging = true),
            ev(50 * min, 100, 99, charging = true),
        )
        assertEquals(40 * min, Analytics.avg80to100(events))
    }

    @Test
    fun gapsRunFromStopToNextEvent() {
        val events = listOf(
            ev(0, 80, kind = Kind.STOP),
            ev(60 * min, 78, kind = Kind.START),
            ev(61 * min, 77, kind = Kind.STOP),
            ev(62 * min, 77, kind = Kind.START), // under two minutes: not a gap
        )
        assertEquals(listOf(0L..60 * min), Analytics.findGaps(events, 70 * min))
    }

    @Test
    fun standbyDrainOnlyCountsLongScreenOffPeriods() {
        val events = listOf(
            ev(0, 90, kind = Kind.START),
            ev(0, 90, kind = Kind.SCREEN_OFF),
            ev(8 * HOUR_MS, 82, kind = Kind.SCREEN_ON, screen = true),
        )
        val s = session(SType.DISCHARGE, 0, 9 * HOUR_MS, 90, 80)
        val idle = Analytics.idleDrain(events, listOf(s), listOf(Pair(8 * HOUR_MS, 9 * HOUR_MS)), 9 * HOUR_MS)
        assertEquals(1.0, idle.pctH!!, 0.001)   // 8 % in 8 hours
        assertEquals(8.0, idle.hours, 0.001)
    }

    @Test
    fun capacityTrendIsOnePointPerWeek() {
        val w = 7 * DAY_MS
        val a = session(SType.CHARGE, 0, HOUR_MS, 20, 80, startUah = 1_000_000, endUah = 3_400_000)
        val b = session(SType.CHARGE, w, w + HOUR_MS, 20, 80, startUah = 1_000_000, endUah = 3_280_000)
        val t = Analytics.capacityTrend(listOf(a, b))
        assertEquals(2, t.size)
        assertEquals(4000f, t[0].y, 1f)
        assertEquals(3800f, t[1].y, 1f)
    }

    @Test
    fun levelLineBreaksWhereTrackingStopped() {
        val events = listOf(
            ev(0, 80, kind = Kind.START),
            ev(10 * min, 79, 80, step = -1),
            ev(20 * min, 79, kind = Kind.STOP),
            ev(60 * min, 70, kind = Kind.START),
        )
        val r = Analytics.build(events, emptyList(), 0L, 70 * min)
        assertTrue(r.levelPoints.any { it.y.isNaN() })
        assertEquals(40 * min, r.gapMs)
    }
}
