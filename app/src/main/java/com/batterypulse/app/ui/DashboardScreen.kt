package com.batterypulse.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterypulse.app.R
import com.batterypulse.app.data.DAY_MS
import com.batterypulse.app.data.Fmt
import com.batterypulse.app.monitor.MonitorState
import kotlin.math.abs

@Composable
fun DashboardScreen(vm: MainVM, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val live by MonitorState.flow.collectAsStateWithLifecycle()
    val dash by vm.dash.collectAsStateWithLifecycle()
    val cap by vm.capacity.collectAsStateWithLifecycle()
    val now by rememberNow()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(if (live.running) R.string.monitor_on else R.string.monitor_off),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (live.running) Brand.Charge else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (live.running) {
                    FilledTonalButton(onClick = onToggle) { Text(stringResource(R.string.action_stop)) }
                } else {
                    Button(onClick = onToggle, colors = ButtonDefaults.buttonColors()) {
                        Text(stringResource(R.string.action_start))
                    }
                }
            }
        }

        // ---- hero: gauge + state
        item {
            Panel {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val subtitle = when {
                        !live.running || live.level < 0 -> stringResource(R.string.monitor_off)
                        live.charging -> "⚡ " + stringResource(R.string.state_charging)
                        else -> stringResource(R.string.state_discharging)
                    }
                    BatteryGauge(level = live.level, charging = live.charging, subtitle = subtitle)
                    if (live.running && live.level >= 0) {
                        val eta = live.etaMs
                        val etaText = when {
                            live.charging && live.level >= 100 -> stringResource(R.string.eta_fully_charged)
                            eta == null -> stringResource(R.string.eta_calculating)
                            live.charging -> stringResource(R.string.eta_to_full, Fmt.duration(ctx, eta))
                            else -> stringResource(R.string.eta_to_empty, Fmt.duration(ctx, eta))
                        }
                        Text(
                            etaText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        val since = if (live.charging)
                            stringResource(R.string.since_plugged, Fmt.hm(live.sinceMs), Fmt.duration(ctx, now - live.sinceMs))
                        else
                            stringResource(R.string.since_unplugged, Fmt.hm(live.sinceMs), Fmt.duration(ctx, now - live.sinceMs))
                        Text(
                            since, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ---- live numbers
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    stringResource(R.string.stat_current),
                    if (live.currentMa == null) "—" else Fmt.ma(abs(live.currentMa!!)),
                    if (live.charging) Brand.Charge else Brand.Discharge, Modifier.weight(1f),
                    sub = Fmt.watt(live.voltageMv, live.currentMa)
                )
                StatCard(
                    stringResource(R.string.stat_temperature), Fmt.temp(live.tempDeci),
                    if (live.tempDeci >= 420) Brand.Hot else Brand.Blue, Modifier.weight(1f),
                    sub = if (live.running) Fmt.health(ctx, live.health) else null
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    stringResource(R.string.stat_voltage), Fmt.volt(live.voltageMv), Brand.Blue, Modifier.weight(1f),
                    sub = if (live.charging) Fmt.plug(ctx, live.plug) else null
                )
                StatCard(
                    stringResource(R.string.stat_screen),
                    stringResource(if (live.screenOn) R.string.screen_on else R.string.screen_off),
                    Brand.Screen, Modifier.weight(1f),
                    sub = stringResource(R.string.screen_today, Fmt.duration(ctx, screenToday(dash, now)))
                )
            }
        }

        // ---- health snapshot
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    stringResource(R.string.stat_health),
                    cap.healthPct?.let { "$it%" } ?: "—", Brand.Health, Modifier.weight(1f),
                    sub = if (cap.healthPct == null) stringResource(R.string.health_needs_data) else null
                )
                StatCard(
                    stringResource(R.string.stat_capacity), Fmt.mah(cap.estimatedMah), Brand.Health, Modifier.weight(1f),
                    sub = cap.designMah?.let { stringResource(R.string.capacity_design, it) }
                )
            }
        }

        // ---- 24 h chart
        item {
            Panel {
                Text(stringResource(R.string.chart_level_24h), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.chart_charging_shaded),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(Modifier.fillMaxWidth().height(170.dp).padding(top = 10.dp)) {
                    if (dash.levelPoints.size < 2) {
                        Text(
                            stringResource(R.string.no_data_yet), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LineChart(
                            points = dash.levelPoints, fromX = now - DAY_MS, toX = now, yMin = 0f, yMax = 100f,
                            ySuffix = "%", color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxSize(), bands = dash.chargeBands, gaps = dash.gaps
                        )
                    }
                }
            }
        }

        // ---- 24 h summary
        item {
            Panel {
                Text(stringResource(R.string.summary_24h), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                KeyValue(stringResource(R.string.kv_discharged), "${dash.dischargedPct}%")
                KeyValue(stringResource(R.string.kv_charged), "${dash.chargedPct}%")
                KeyValue(stringResource(R.string.kv_charge_speed), Fmt.pctH(dash.avgChargePctH))
                KeyValue(stringResource(R.string.kv_discharge_speed), Fmt.pctH(dash.dischargePctH))
                KeyValue(stringResource(R.string.kv_screen_on_time), Fmt.duration(ctx, dash.screenOnMs))
            }
        }
    }
}

/** Screen-on time since local midnight, from the last-24-hours report. */
private fun screenToday(dash: com.batterypulse.app.data.Report, now: Long): Long {
    val dayStart = com.batterypulse.app.data.Analytics.dayStart(now)
    return dash.days.firstOrNull { it.dayStart == dayStart }?.screenOnMs ?: 0L
}
