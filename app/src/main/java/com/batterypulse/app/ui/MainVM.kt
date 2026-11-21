@file:OptIn(ExperimentalCoroutinesApi::class)

package com.batterypulse.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.batterypulse.app.BatteryPulseApp
import com.batterypulse.app.data.Analytics
import com.batterypulse.app.data.BatteryEvent
import com.batterypulse.app.data.Capacity
import com.batterypulse.app.data.DAY_MS
import com.batterypulse.app.data.Kind
import com.batterypulse.app.data.LocaleHelper
import com.batterypulse.app.data.Report
import com.batterypulse.app.data.Session
import com.batterypulse.app.export.Exporter
import com.batterypulse.app.export.ReportBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainVM(app: Application) : AndroidViewModel(app) {
    private val bp = app as BatteryPulseApp
    val store = bp.store
    val settings = bp.settings

    /** Reports window: 0 = 24 h, 1 = 7 days, 2 = 30 days, 3 = everything. */
    val reportRange = MutableStateFlow(1)

    /** History: 0 = events, 1 = sessions. */
    val historyTab = MutableStateFlow(0)

    /** Event filter: 0 = all, 1 = 1 % steps, 2 = charger / 100 %, 3 = screen. */
    val eventFilter = MutableStateFlow(0)

    /** History window: 0 = today, 1 = 7 days, 2 = 30 days, 3 = everything. */
    val historyRange = MutableStateFlow(0)

    val capacity: StateFlow<Capacity> = combine(store.changes, settings.state) { _, st -> st }
        .mapLatest { st -> ReportBuilder.capacity(getApplication<Application>(), store, st) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Capacity())

    /** Last 24 hours, for the dashboard. */
    val dash: StateFlow<Report> = store.changes
        .mapLatest {
            val now = System.currentTimeMillis()
            val from = now - DAY_MS
            Analytics.build(store.events(from, now), store.sessions(from, now), from, now)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Report())

    val report: StateFlow<Report> = combine(reportRange, store.changes) { r, _ -> r }
        .mapLatest { r ->
            val now = System.currentTimeMillis()
            val from = ReportBuilder.rangeStart(r, now, store.firstTs(), today = false)
            Analytics.build(store.events(from, now), store.sessions(from, now), from, now)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Report())

    val historyEvents: StateFlow<List<BatteryEvent>> =
        combine(historyRange, eventFilter, store.changes) { r, f, _ -> Pair(r, f) }
            .mapLatest { (r, f) ->
                val now = System.currentTimeMillis()
                val from = ReportBuilder.rangeStart(r, now, store.firstTs(), today = true)
                val kinds = when (f) {
                    1 -> listOf(Kind.LEVEL)
                    2 -> listOf(Kind.PLUGGED, Kind.UNPLUGGED, Kind.FULL)
                    3 -> listOf(Kind.SCREEN_ON, Kind.SCREEN_OFF)
                    else -> null
                }
                store.events(from, now, kinds, limit = 5000, newestFirst = true)
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val historySessions: StateFlow<List<Session>> = combine(historyRange, store.changes) { r, _ -> r }
        .mapLatest { r ->
            val now = System.currentTimeMillis()
            val from = ReportBuilder.rangeStart(r, now, store.firstTs(), today = true)
            store.sessions(from, now).reversed()
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun <T> io(block: () -> T, done: (T) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { block() }
            done(r)
        }
    }

    fun deleteSession(id: Long) = io({ store.deleteSession(id) }, { })

    fun clearAll() = io({ store.clearFinished() }, { })

    fun backup(uri: Uri, done: (Boolean) -> Unit) = io({
        try {
            val app = getApplication<Application>()
            app.contentResolver.openOutputStream(uri)?.use { store.backupTo(it) } != null
        } catch (e: Exception) {
            false
        }
    }, done)

    fun restore(uri: Uri, done: (Boolean) -> Unit) = io({
        try {
            val app = getApplication<Application>()
            app.contentResolver.openInputStream(uri)?.use { store.restoreFrom(it, app.cacheDir) } ?: false
        } catch (e: Exception) {
            false
        }
    }, done)

    /** Exports the period currently selected on the Reports tab. [done] gets null when there is nothing to export. */
    fun export(kind: Exporter.Format, done: (File?) -> Unit) {
        val idx = reportRange.value
        io({
            try {
                val ctx = LocaleHelper.wrap(getApplication<Application>())
                ReportBuilder.data(ctx, store, settings.state.value, idx)?.let { Exporter.export(ctx, kind, it) }
            } catch (e: Exception) {
                null
            }
        }, done)
    }
}
