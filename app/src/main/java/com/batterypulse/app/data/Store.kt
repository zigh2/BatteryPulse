package com.batterypulse.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** SQLite storage: the event log (every 1 % step, plug, screen ...) and the charge / discharge sessions. */
class Store(private val ctx: Context) {

    private class Helper(c: Context) : SQLiteOpenHelper(c, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE events(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL, kind INTEGER NOT NULL, level INTEGER NOT NULL, prev INTEGER NOT NULL,
                    charging INTEGER NOT NULL, plug INTEGER NOT NULL, temp_deci INTEGER NOT NULL,
                    mv INTEGER NOT NULL, ma INTEGER, uah INTEGER NOT NULL, screen INTEGER NOT NULL,
                    session_id INTEGER NOT NULL, step_ms INTEGER NOT NULL DEFAULT -1)"""
            )
            db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
            db.execSQL(
                """CREATE TABLE sessions(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER,
                    start_level INTEGER NOT NULL, end_level INTEGER NOT NULL, plug INTEGER NOT NULL,
                    full_ms INTEGER, screen_ms INTEGER NOT NULL, start_uah INTEGER NOT NULL,
                    end_uah INTEGER NOT NULL, max_temp INTEGER NOT NULL, sum_ma INTEGER NOT NULL,
                    n_ma INTEGER NOT NULL)"""
            )
            db.execSQL("CREATE INDEX idx_sessions_start ON sessions(start_ms)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE events ADD COLUMN step_ms INTEGER NOT NULL DEFAULT -1")
        }
    }

    companion object {
        const val DB_NAME = "batterypulse.db"
        const val DB_VERSION = 2
    }

    private var helper = Helper(ctx)
    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** Ticks every time data changes, so the UI can reload. */
    val changes = MutableStateFlow(0L)
    private fun bump() = changes.update { it + 1 }

    // ------------------------------------------------------------ events

    @Synchronized
    fun insertEvent(e: BatteryEvent): Long {
        val v = ContentValues().apply {
            put("ts", e.ts); put("kind", e.kind); put("level", e.level); put("prev", e.prevLevel)
            put("charging", if (e.charging) 1 else 0); put("plug", e.plug)
            put("temp_deci", e.tempDeci); put("mv", e.voltageMv)
            if (e.currentMa != null) put("ma", e.currentMa) else putNull("ma")
            put("uah", e.uah); put("screen", if (e.screenOn) 1 else 0); put("session_id", e.sessionId)
            put("step_ms", e.stepMs)
        }
        val id = db.insert("events", null, v)
        bump()
        return id
    }

    private fun readEvent(c: Cursor) = BatteryEvent(
        id = c.getLong(0), ts = c.getLong(1), kind = c.getInt(2), level = c.getInt(3), prevLevel = c.getInt(4),
        charging = c.getInt(5) == 1, plug = c.getInt(6), tempDeci = c.getInt(7), voltageMv = c.getInt(8),
        currentMa = if (c.isNull(9)) null else c.getInt(9), uah = c.getLong(10),
        screenOn = c.getInt(11) == 1, sessionId = c.getLong(12), stepMs = c.getLong(13),
    )

    private val eventCols = "id, ts, kind, level, prev, charging, plug, temp_deci, mv, ma, uah, screen, session_id, step_ms"

    @Synchronized
    fun events(from: Long, to: Long, kinds: List<Int>? = null, limit: Int = 50_000, newestFirst: Boolean = false): List<BatteryEvent> {
        val where = StringBuilder("ts >= ? AND ts <= ?")
        if (!kinds.isNullOrEmpty()) where.append(" AND kind IN (").append(kinds.joinToString(",")).append(")")
        val order = if (newestFirst) "DESC" else "ASC"
        val out = ArrayList<BatteryEvent>()
        db.rawQuery(
            "SELECT $eventCols FROM events WHERE $where ORDER BY ts $order, id $order LIMIT $limit",
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) out.add(readEvent(c)) }
        return out
    }

    /** The last [n] 1 % steps of one session, oldest first (used for the time-remaining estimate). */
    @Synchronized
    fun recentLevelEvents(sessionId: Long, n: Int): List<BatteryEvent> {
        val out = ArrayList<BatteryEvent>()
        db.rawQuery(
            "SELECT $eventCols FROM events WHERE session_id = ? AND kind = ${Kind.LEVEL} ORDER BY ts DESC, id DESC LIMIT $n",
            arrayOf(sessionId.toString())
        ).use { c -> while (c.moveToNext()) out.add(readEvent(c)) }
        return out.reversed()
    }

    @Synchronized
    fun lastEvent(): BatteryEvent? {
        db.rawQuery("SELECT $eventCols FROM events ORDER BY ts DESC, id DESC LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) readEvent(c) else null
        }
    }

    @Synchronized
    fun firstTs(): Long? {
        db.rawQuery("SELECT MIN(ts) FROM events", null).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }

    @Synchronized
    fun eventCount(): Int {
        db.rawQuery("SELECT COUNT(*) FROM events", null).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    // ------------------------------------------------------------ sessions

    private val sessionCols =
        "id, type, start_ms, end_ms, start_level, end_level, plug, full_ms, screen_ms, start_uah, end_uah, max_temp, sum_ma, n_ma"

    private fun readSession(c: Cursor) = Session(
        id = c.getLong(0), type = c.getInt(1), startMs = c.getLong(2),
        endMs = if (c.isNull(3)) null else c.getLong(3),
        startLevel = c.getInt(4), endLevel = c.getInt(5), plug = c.getInt(6),
        fullMs = if (c.isNull(7)) null else c.getLong(7),
        screenOnMs = c.getLong(8), startUah = c.getLong(9), endUah = c.getLong(10),
        maxTempDeci = c.getInt(11), sumMa = c.getLong(12), nMa = c.getInt(13),
    )

    private fun sessionValues(s: Session) = ContentValues().apply {
        put("type", s.type); put("start_ms", s.startMs)
        if (s.endMs != null) put("end_ms", s.endMs) else putNull("end_ms")
        put("start_level", s.startLevel); put("end_level", s.endLevel); put("plug", s.plug)
        if (s.fullMs != null) put("full_ms", s.fullMs) else putNull("full_ms")
        put("screen_ms", s.screenOnMs); put("start_uah", s.startUah); put("end_uah", s.endUah)
        put("max_temp", s.maxTempDeci); put("sum_ma", s.sumMa); put("n_ma", s.nMa)
    }

    @Synchronized
    fun insertSession(s: Session): Long {
        val id = db.insert("sessions", null, sessionValues(s))
        bump()
        return id
    }

    @Synchronized
    fun updateSession(s: Session) {
        db.update("sessions", sessionValues(s), "id = ?", arrayOf(s.id.toString()))
        bump()
    }

    /** Sessions that overlap [from]..[to], oldest first. */
    @Synchronized
    fun sessions(from: Long, to: Long): List<Session> {
        val out = ArrayList<Session>()
        db.rawQuery(
            "SELECT $sessionCols FROM sessions WHERE start_ms <= ? AND COALESCE(end_ms, ${Long.MAX_VALUE}) >= ? ORDER BY start_ms ASC",
            arrayOf(to.toString(), from.toString())
        ).use { c -> while (c.moveToNext()) out.add(readSession(c)) }
        return out
    }

    @Synchronized
    fun openSession(): Session? {
        db.rawQuery("SELECT $sessionCols FROM sessions WHERE end_ms IS NULL ORDER BY start_ms DESC LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) readSession(c) else null
        }
    }

    /** Closes a session that was left open (process killed, reboot) at [endMs], using the last level seen. */
    @Synchronized
    fun closeStale(s: Session, endMs: Long) {
        var level = s.endLevel
        db.rawQuery(
            "SELECT level FROM events WHERE ts <= ? ORDER BY ts DESC, id DESC LIMIT 1", arrayOf(endMs.toString())
        ).use { c -> if (c.moveToFirst()) level = c.getInt(0) }
        updateSession(s.copy(endMs = maxOf(endMs, s.startMs), endLevel = level))
    }

    @Synchronized
    fun deleteSession(id: Long) {
        db.delete("events", "session_id = ?", arrayOf(id.toString()))
        db.delete("sessions", "id = ?", arrayOf(id.toString()))
        bump()
    }

    // ------------------------------------------------------------ housekeeping

    @Synchronized
    fun prune(retentionDays: Int) {
        val cutoff = System.currentTimeMillis() - retentionDays * DAY_MS
        db.delete("events", "ts < ?", arrayOf(cutoff.toString()))
        db.delete("sessions", "end_ms IS NOT NULL AND end_ms < ?", arrayOf(cutoff.toString()))
        bump()
    }

    /** Deletes all history except the running session. */
    @Synchronized
    fun clearFinished() {
        val open = openSession()
        if (open == null) {
            db.delete("events", null, null)
            db.delete("sessions", null, null)
        } else {
            db.delete("events", "session_id <> ?", arrayOf(open.id.toString()))
            db.delete("sessions", "id <> ?", arrayOf(open.id.toString()))
        }
        bump()
    }

    // ------------------------------------------------------------ backup / restore

    @Synchronized
    fun backupTo(out: OutputStream) {
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        File(db.path).inputStream().use { it.copyTo(out) }
    }

    /** Replaces the database with the backup after checking that it really is one of ours. */
    @Synchronized
    fun restoreFrom(input: InputStream, cacheDir: File): Boolean {
        val tmp = File(cacheDir, "restore.db")
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        val valid = try {
            SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { t ->
                t.rawQuery("SELECT COUNT(*) FROM events", null).use { it.moveToFirst() }
                t.rawQuery("SELECT COUNT(*) FROM sessions", null).use { it.moveToFirst() }
                true
            }
        } catch (e: Exception) {
            false
        }
        if (!valid) {
            tmp.delete()
            return false
        }
        val path = db.path
        helper.close()
        File("$path-wal").delete()
        File("$path-shm").delete()
        tmp.copyTo(File(path), overwrite = true)
        tmp.delete()
        helper = Helper(ctx)
        helper.writableDatabase
        bump()
        return true
    }
}
