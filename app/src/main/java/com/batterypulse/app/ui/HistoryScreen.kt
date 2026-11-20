package com.batterypulse.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterypulse.app.R
import com.batterypulse.app.data.BatteryEvent
import com.batterypulse.app.data.Fmt
import com.batterypulse.app.data.Kind
import com.batterypulse.app.data.Session
import kotlin.math.abs

@Composable
fun HistoryScreen(vm: MainVM, onMessage: (String) -> Unit) {
    val tab by vm.historyTab.collectAsStateWithLifecycle()
    val filter by vm.eventFilter.collectAsStateWithLifecycle()
    val range by vm.historyRange.collectAsStateWithLifecycle()
    val events by vm.historyEvents.collectAsStateWithLifecycle()
    val sessions by vm.historySessions.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var toDelete by remember { mutableStateOf<Session?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.tab_history),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
            )
            ChipRow(
                listOf(stringResource(R.string.hist_events), stringResource(R.string.hist_sessions)),
                tab, { vm.historyTab.value = it }
            )
            ChipRow(
                listOf(
                    stringResource(R.string.range_today), stringResource(R.string.range_7d),
                    stringResource(R.string.range_30d), stringResource(R.string.range_all)
                ),
                range, { vm.historyRange.value = it }
            )
            if (tab == 0) {
                ChipRow(
                    listOf(
                        stringResource(R.string.filter_all), stringResource(R.string.filter_levels),
                        stringResource(R.string.filter_charger), stringResource(R.string.filter_screen)
                    ),
                    filter, { vm.eventFilter.value = it }
                )
            }
        }

        if (tab == 0) {
            if (events.isEmpty()) EmptyState() else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(events, key = { it.id }) { EventRow(it) }
                }
            }
        } else {
            if (sessions.isEmpty()) EmptyState() else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sessions, key = { it.id }) { s -> SessionCard(s) { toDelete = s } }
                }
            }
        }
    }

    val del = toDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_session_title)) },
            text = { Text(stringResource(R.string.delete_session_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSession(del.id)
                    toDelete = null
                    onMessage(ctx.getString(R.string.deleted))
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.no_data_yet),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge
        )
    }
}

private fun eventColor(e: BatteryEvent): Color = when (e.kind) {
    Kind.LEVEL -> if (e.charging) Brand.Charge else Brand.Discharge
    Kind.PLUGGED, Kind.FULL -> Brand.Charge
    Kind.UNPLUGGED -> Brand.Discharge
    Kind.SCREEN_ON, Kind.SCREEN_OFF -> Brand.Screen
    else -> Brand.Blue
}

@Composable
private fun EventRow(e: BatteryEvent) {
    val ctx = LocalContext.current
    val c = eventColor(e)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).background(c, CircleShape))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            val title = if (e.kind == Kind.LEVEL) {
                val d = e.delta
                val sign = if (d > 0) "+$d" else d.toString()
                "${e.level}%  ($sign%)"
            } else {
                Fmt.kindLabel(ctx, e.kind) + "  ·  ${e.level}%"
            }
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Text(
                Fmt.dateTime(e.ts), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val state = if (e.charging) "⚡ " + Fmt.plug(ctx, e.plug) else stringResource(R.string.state_discharging)
            Text(state, style = MaterialTheme.typography.labelMedium, color = c)
            val extra = listOfNotNull(
                if (e.tempDeci > 0) Fmt.temp(e.tempDeci) else null,
                e.currentMa?.let { Fmt.ma(abs(it)) }
            ).joinToString(" · ")
            if (extra.isNotEmpty()) {
                Text(extra, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(s: Session, onLongPress: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val now by rememberNow()
    val c = if (s.charge) Brand.Charge else Brand.Discharge
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = {}, onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongPress()
            })
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(c, CircleShape))
            Text(
                if (s.charge) stringResource(R.string.session_charge) + " · " + Fmt.plug(ctx, s.plug)
                else stringResource(R.string.session_discharge),
                fontWeight = FontWeight.Bold, color = c, modifier = Modifier.padding(start = 10.dp).weight(1f)
            )
            Text(
                "${s.startLevel}% → ${s.endLevel}%",
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium
            )
        }
        val end = s.endMs?.let { Fmt.dateTime(it) } ?: stringResource(R.string.now_label)
        Text(
            "${Fmt.dateTime(s.startMs)}  →  $end", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
        )
        KeyValue(stringResource(R.string.kv_duration), Fmt.duration(ctx, s.durationMs(now)))
        KeyValue(stringResource(R.string.kv_speed), Fmt.pctH(s.speedPctPerHour(now)))
        KeyValue(stringResource(R.string.kv_avg_current), Fmt.ma(s.avgMa?.let { abs(it) }))
        if (s.mAh != null) KeyValue(stringResource(R.string.kv_energy), Fmt.mah(s.mAh))
        KeyValue(stringResource(R.string.kv_screen_on_time), Fmt.duration(ctx, s.screenOnMs))
        KeyValue(stringResource(R.string.kv_max_temp), Fmt.temp(s.maxTempDeci))
        if (s.fullMs != null) KeyValue(stringResource(R.string.kv_reached_full), Fmt.dateTime(s.fullMs))
    }
}
