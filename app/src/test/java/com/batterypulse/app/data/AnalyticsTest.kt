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
        session: Long = 1L, screen: Boolean = false, uah: Long = 0L,
    ) = BatteryEvent(
        id = 0, ts = ts, kind = kind, level = level, prevLevel = prev, charging = charging, plug = if (charging) Plug.AC else 0,
        tempDeci = 300, voltageMv = 4000, currentMa = null, uah = uah, screenOn = screen, sessionId = session,
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
}
