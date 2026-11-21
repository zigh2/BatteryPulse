package com.batterypulse.app.monitor

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.batterypulse.app.BatteryPulseApp
import com.batterypulse.app.R
import androidx.core.content.FileProvider
import com.batterypulse.app.data.Analytics
import com.batterypulse.app.data.DAY_MS
import com.batterypulse.app.data.BatteryEvent
import com.batterypulse.app.data.Fmt
import com.batterypulse.app.data.Kind
import com.batterypulse.app.data.LocaleHelper
import com.batterypulse.app.data.SType
import com.batterypulse.app.data.Session
import com.batterypulse.app.export.Exporter
import com.batterypulse.app.export.ReportBuilder
import com.batterypulse.app.ui.MainActivity
import java.io.File
import java.util.concurrent.Executors

/**
 * Foreground service that listens to the battery and screen broadcasts and writes the event log:
 * one row for every 1 % step, every plug / unplug, 100 % reached and every screen on / off.
 */
class MonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "monitor"
        const val ALERT_CHANNEL_ID = "alerts"
        const val REPORT_CHANNEL_ID = "reports"
        const val ACTION_STOP = "com.batterypulse.app.STOP"
        private const val NOTIF_ID = 1
        private const val ALERT_FULL = 11
        private const val ALERT_LIMIT = 12
        private const val ALERT_LOW = 13
        private const val ALERT_TEMP = 14
        private const val ALERT_WEEKLY = 15
        private const val TICK_MS = 60_000L

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var app: BatteryPulseApp
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var started = false
    private var closed = false

    // State below is only touched on the io thread.
    private var cur: Session? = null
    private var lastSnap: Snap? = null
    private var lastLevel = -1
    private var screenOn = false
    private var screenAccrueAt = 0L
    private var stepAnchor = -1L
    private var lastNotifAt = 0L
    private var lastNotifLevel = -1
    private var alertedFull = false
    private var alertedLimit = false
    private var alertedLow = false
    private var alertedTemp = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = applicationContext as BatteryPulseApp
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val snap = BatteryReader.read(context, intent) ?: return
            val now = System.currentTimeMillis()
            io.execute { handleBattery(snap, now) }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            val now = System.currentTimeMillis()
            io.execute { handleScreen(on, now) }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val snap = BatteryReader.read(this@MonitorService, BatteryReader.sticky(this@MonitorService))
            val now = System.currentTimeMillis()
            io.execute {
                if (snap != null) handleBattery(snap, now)
                app.settings.lastHeartbeat = now
                maybeWeeklyReport(now)
            }
            ui.postDelayed(this, TICK_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish(userRequested = true)
            return START_NOT_STICKY
        }
        val n = buildNotification(MonitorState.flow.value)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (!started) {
            started = true
            app.settings.monitoring = true
            MonitorState.flow.value = MonitorState.flow.value.copy(running = true)
            begin()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        finish(userRequested = false)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun begin() {
        val pm = getSystemService(PowerManager::class.java)
        val screen = pm.isInteractive
        val sticky = ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val sf = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, sf, ContextCompat.RECEIVER_NOT_EXPORTED)
        val snap = BatteryReader.read(this, sticky)
        val now = System.currentTimeMillis()
        io.execute { initialize(snap, screen, now) }
        ui.postDelayed(tick, TICK_MS)
    }

    private fun initialize(snap: Snap?, screen: Boolean, now: Long) {
        screenOn = screen
        screenAccrueAt = now
        // A session still open means the service died: close it where we last heard from it.
        val stale = app.store.openSession()
        if (stale != null) {
            val hb = app.settings.lastHeartbeat
            val end = if (hb > stale.startMs && hb < now) hb else now
            app.store.closeStale(stale, end)
            val last = app.store.lastEvent()
            if (last != null) {
                app.store.insertEvent(last.copy(id = 0, ts = maxOf(end, last.ts), kind = Kind.STOP, prevLevel = last.level, sessionId = stale.id))
            }
        }
        if (snap == null) return
        lastSnap = snap
        lastLevel = snap.level
        openSession(snap, now)
        app.store.insertEvent(ev(Kind.START, snap, now, snap.level))
        publish(snap, now, force = true)
        app.store.prune(app.settings.state.value.retentionDays)
    }

    private fun finish(userRequested: Boolean) {
        if (closed) return
        closed = true
        if (userRequested) app.settings.monitoring = false
        ui.removeCallbacks(tick)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) { }
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        val now = System.currentTimeMillis()
        io.execute {
            val s = lastSnap
            if (s != null && cur != null) {
                accrueScreen(now)
                app.store.insertEvent(ev(Kind.STOP, s, now, s.level))
                closeSession(s, now)
            }
        }
        io.shutdown()
        MonitorState.flow.value = Live()
        if (userRequested) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ------------------------------------------------------------------ event handling (io thread)

    private fun ev(kind: Int, s: Snap, now: Long, prev: Int, stepMs: Long = -1L) = BatteryEvent(
        id = 0, ts = now, kind = kind, level = s.level, prevLevel = prev, charging = s.charging, plug = s.plug,
        tempDeci = s.tempDeci, voltageMv = s.voltageMv, currentMa = s.currentMa, uah = s.uah,
        screenOn = screenOn, sessionId = cur?.id ?: 0L, stepMs = stepMs,
    )

    private fun accrueScreen(now: Long) {
        val c = cur
        if (c != null && screenOn) {
            cur = c.copy(screenOnMs = c.screenOnMs + (now - screenAccrueAt).coerceAtLeast(0L))
        }
        screenAccrueAt = now
    }

    private fun openSession(s: Snap, now: Long) {
        val draft = Session(
            id = 0, type = if (s.charging) SType.CHARGE else SType.DISCHARGE, startMs = now, endMs = null,
            startLevel = s.level, endLevel = s.level, plug = s.plug, fullMs = null, screenOnMs = 0L,
            startUah = s.uah, endUah = s.uah, maxTempDeci = s.tempDeci, sumMa = 0L, nMa = 0,
        )
        cur = draft.copy(id = app.store.insertSession(draft))
        screenAccrueAt = now
        stepAnchor = -1L // the first 1 % of a new session is partial, so it is not timed
    }

    private fun closeSession(s: Snap, now: Long) {
        val c = cur ?: return
        app.store.updateSession(
            c.copy(endMs = now, endLevel = s.level, endUah = if (s.uah > 0) s.uah else c.endUah)
        )
        cur = null
    }

    private fun handleBattery(s: Snap, now: Long) {
        if (closed) return
        accrueScreen(now)
        var persist = false
        val type = if (s.charging) SType.CHARGE else SType.DISCHARGE
        val c0 = cur
        if (c0 == null) {
            openSession(s, now)
            persist = true
        } else if (c0.type != type) {
            closeSession(s, now)
            openSession(s, now)
            app.store.insertEvent(ev(if (s.charging) Kind.PLUGGED else Kind.UNPLUGGED, s, now, lastLevel))
            persist = true
        }

        if (s.level != lastLevel) {
            // Time since the previous level change of this session; -1 for the first (partial) step.
            val step = if (stepAnchor > 0) now - stepAnchor else -1L
            app.store.insertEvent(ev(Kind.LEVEL, s, now, lastLevel, step))
            stepAnchor = now
            lastLevel = s.level
            persist = true
        }

        var c = cur ?: return
        if (s.charging && s.level >= 100 && c.fullMs == null) {
            c = c.copy(fullMs = now)
            cur = c
            app.store.insertEvent(ev(Kind.FULL, s, now, s.level))
            persist = true
        }

        c = c.copy(
            endLevel = s.level,
            endUah = if (s.uah > 0) s.uah else c.endUah,
            maxTempDeci = maxOf(c.maxTempDeci, s.tempDeci),
            sumMa = c.sumMa + (s.currentMa ?: 0),
            nMa = c.nMa + if (s.currentMa != null) 1 else 0,
        )
        cur = c
        lastSnap = s
        if (persist) {
            app.store.updateSession(c)
            app.settings.lastHeartbeat = now
        }
        checkAlerts(s)
        publish(s, now, force = persist)
    }

    private fun handleScreen(on: Boolean, now: Long) {
        if (closed) return
        accrueScreen(now)
        screenOn = on
        val s = lastSnap ?: return
        app.store.insertEvent(ev(if (on) Kind.SCREEN_ON else Kind.SCREEN_OFF, s, now, s.level))
        cur?.let { app.store.updateSession(it) }
        publish(s, now, force = true)
    }

    // ------------------------------------------------------------------ live state, notification, alerts

    private fun publish(s: Snap, now: Long, force: Boolean) {
        val c = cur
        val eta = if (c != null) Analytics.etaMs(app.store.recentLevelEvents(c.id, 6), s.level, s.charging) else null
        val live = Live(
            running = true, sessionId = c?.id ?: 0L, level = s.level, charging = s.charging, plug = s.plug,
            status = s.status, health = s.health, tempDeci = s.tempDeci, voltageMv = s.voltageMv,
            currentMa = s.currentMa, screenOn = screenOn, sinceMs = c?.startMs ?: now, etaMs = eta,
        )
        MonitorState.flow.value = live
        if (force || now - lastNotifAt >= 15_000L || s.level != lastNotifLevel) {
            lastNotifAt = now
            lastNotifLevel = s.level
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(live))
            BatteryWidget.refresh(this)
        }
    }

    private fun buildNotification(l: Live): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title: String
        val text: String
        if (l.level < 0) {
            title = getString(R.string.app_name)
            text = getString(R.string.notif_starting)
        } else {
            val state = if (l.charging) "${getString(R.string.state_charging)} · ${Fmt.plug(this, l.plug)}"
            else getString(R.string.state_discharging)
            title = "${l.level}% · $state"
            val parts = ArrayList<String>()
            if (l.tempDeci > 0) parts.add(Fmt.temp(l.tempDeci))
            if (l.currentMa != null) parts.add(Fmt.ma(kotlin.math.abs(l.currentMa)))
            val eta = l.etaMs
            if (eta != null) {
                parts.add(
                    getString(
                        if (l.charging) R.string.notif_eta_full else R.string.notif_eta_empty,
                        Fmt.duration(this, eta)
                    )
                )
            }
            text = parts.joinToString(" · ")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun alert(id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    /** Once every 7 days: build the last week's PDF and post a notification that opens it. */
    private fun maybeWeeklyReport(now: Long) {
        val st = app.settings.state.value
        if (!st.weeklyReport || closed) return
        val last = app.settings.lastWeekly
        if (last == 0L) {
            app.settings.lastWeekly = now
            return
        }
        if (now - last < 7 * DAY_MS) return
        app.settings.lastWeekly = now
        try {
            val data = ReportBuilder.data(this, app.store, st, 1) ?: return
            val dir = File(filesDir, "reports").apply { mkdirs() }
            val out = File(dir, "weekly_${Fmt.date(now)}.pdf")
            Exporter.export(this, Exporter.Format.PDF, data).copyTo(out, overwrite = true)
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }

            val uri = FileProvider.getUriForFile(this, "$packageName.files", out)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val share = Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val n = NotificationCompat.Builder(this, REPORT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_battery)
                .setContentTitle(getString(R.string.weekly_title))
                .setContentText(getString(R.string.weekly_text))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 2, view, flags))
                .addAction(0, getString(R.string.weekly_share), PendingIntent.getActivity(this, 3, share, flags))
                .build()
            getSystemService(NotificationManager::class.java).notify(ALERT_WEEKLY, n)
        } catch (_: Exception) {
        }
    }

    private fun checkAlerts(s: Snap) {
        val st = app.settings.state.value
        // full
        if (s.charging && s.level >= 100) {
            if (st.alertFull && !alertedFull) {
                alertedFull = true
                alert(ALERT_FULL, getString(R.string.alert_full_title), getString(R.string.alert_full_text))
            }
        } else if (!s.charging) alertedFull = false
        // charge limit
        if (s.charging && s.level >= st.limitPct && s.level < 100) {
            if (st.alertLimit && !alertedLimit) {
                alertedLimit = true
                alert(ALERT_LIMIT, getString(R.string.alert_limit_title, st.limitPct), getString(R.string.alert_limit_text))
            }
        } else if (!s.charging) alertedLimit = false
        // low battery
        if (!s.charging && s.level <= st.lowPct) {
            if (st.alertLow && !alertedLow) {
                alertedLow = true
                alert(ALERT_LOW, getString(R.string.alert_low_title, s.level), getString(R.string.alert_low_text))
            }
        } else if (s.charging || s.level > st.lowPct) alertedLow = false
        // temperature
        if (s.tempDeci >= st.tempLimitC * 10) {
            if (st.alertTemp && !alertedTemp) {
                alertedTemp = true
                alert(ALERT_TEMP, getString(R.string.alert_temp_title, Fmt.temp(s.tempDeci)), getString(R.string.alert_temp_text))
            }
        } else if (s.tempDeci < (st.tempLimitC - 2) * 10) alertedTemp = false
    }
}
