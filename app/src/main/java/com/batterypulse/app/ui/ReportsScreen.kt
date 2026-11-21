package com.batterypulse.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterypulse.app.R
import com.batterypulse.app.data.Fmt
import com.batterypulse.app.data.HOUR_MS
import com.batterypulse.app.data.Report
import com.batterypulse.app.export.Exporter
import java.io.File
import java.util.Locale

@Composable
fun ReportsScreen(vm: MainVM, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val range by vm.reportRange.collectAsStateWithLifecycle()
    val r by vm.report.collectAsStateWithLifecycle()
    val cap by vm.capacity.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }

    fun doExport(kind: Exporter.Format) {
        if (busy) return
        busy = true
        vm.export(kind) { f ->
            busy = false
            if (f == null) onMessage(ctx.getString(R.string.export_nothing)) else shareFile(ctx, f, kind.mime)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(stringResource(R.string.tab_reports), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item {
            ChipRow(
                listOf(
                    stringResource(R.string.range_24h), stringResource(R.string.range_7d),
                    stringResource(R.string.range_30d), stringResource(R.string.range_all)
                ),
                range, { vm.reportRange.value = it }
            )
        }

        // ---- health
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    stringResource(R.string.stat_health), cap.healthPct?.let { "$it%" } ?: "—",
                    Brand.Health, Modifier.weight(1f),
                    sub = if (cap.healthPct == null) stringResource(R.string.health_needs_data) else null
                )
                StatCard(
                    stringResource(R.string.stat_cycles),
                    (cap.osCycles?.toString()) ?: Fmt.num1(r.equivalentCycles),
                    Brand.Blue, Modifier.weight(1f),
                    sub = stringResource(if (cap.osCycles != null) R.string.cycles_os else R.string.cycles_equivalent)
                )
            }
        }
        item {
            Panel {
                SectionTitle(stringResource(R.string.sec_health))
                KeyValue(stringResource(R.string.kv_design_capacity), Fmt.mah(cap.designMah))
                KeyValue(stringResource(R.string.kv_measured_capacity), Fmt.mah(cap.estimatedMah))
                KeyValue(stringResource(R.string.kv_os_health), cap.osHealthPct?.let { "$it%" } ?: "—")
                if (cap.trend.size >= 2) {
                    Text(
                        stringResource(R.string.chart_trend), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp)
                    )
                    val lo = cap.trend.minOf { it.y } * 0.97f
                    val hi = (cap.trend.maxOf { it.y } * 1.03f).coerceAtLeast(lo + 50f)
                    Box(Modifier.fillMaxWidth().height(130.dp).padding(top = 8.dp)) {
                        LineChart(
                            points = cap.trend, fromX = cap.trend.first().x, toX = cap.trend.last().x, yMin = lo, yMax = hi,
                            ySuffix = "", color = Brand.Health, modifier = Modifier.fillMaxSize(), step = false,
                            xLabel = { Fmt.dayShort(it) }
                        )
                    }
                }
            }
        }

        // ---- level chart
        item {
            Panel {
                SectionTitle(stringResource(R.string.chart_level))
                Text(
                    stringResource(R.string.chart_charging_shaded), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp)) {
                    if (r.levelPoints.size < 2) NoData() else LineChart(
                        points = r.levelPoints, fromX = r.from, toX = r.to, yMin = 0f, yMax = 100f, ySuffix = "%",
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize(), bands = r.chargeBands, gaps = r.gaps,
                        xLabel = { if (r.to - r.from <= 36 * HOUR_MS) Fmt.hm(it) else Fmt.dayShort(it) }
                    )
                }
                if (r.gapMs > 0) KeyValue(stringResource(R.string.kv_gap_total), Fmt.duration(ctx, r.gapMs))
            }
        }

        // ---- charging
        item {
            Panel {
                SectionTitle(stringResource(R.string.sec_charging))
                KeyValue(stringResource(R.string.kv_sessions), r.chargeSessions.toString())
                KeyValue(stringResource(R.string.kv_avg_speed), Fmt.pctH(r.avgChargePctH))
                KeyValue(stringResource(R.string.kv_max_speed), Fmt.pctH(r.maxChargePctH))
                KeyValue(stringResource(R.string.kv_avg_current), Fmt.ma(r.avgChargeMa))
                KeyValue(stringResource(R.string.kv_max_current), Fmt.ma(r.maxChargeMa))
                KeyValue(stringResource(R.string.kv_avg_duration), Fmt.durationOrDash(ctx, r.avgChargeDurMs))
                KeyValue(stringResource(R.string.kv_avg_to_full), Fmt.durationOrDash(ctx, r.avgTimeTo100Ms))
                KeyValue(stringResource(R.string.kv_full_charges), r.fullCharges.toString())
                KeyValue(stringResource(R.string.kv_charged), "${r.chargedPct}%")
                KeyValue(stringResource(R.string.kv_80_100), Fmt.durationOrDash(ctx, r.avg80to100Ms))
            }
        }
        if (r.chargers.isNotEmpty()) {
            item {
                Panel {
                    SectionTitle(stringResource(R.string.sec_chargers))
                    r.chargers.forEach { c ->
                        KeyValue("${Fmt.plug(ctx, c.plug)} ×${c.sessions}", Fmt.charger(c))
                    }
                }
            }
        }
        item {
            Panel {
                SectionTitle(stringResource(R.string.sec_steps))
                StepBlock(stringResource(R.string.sec_charging), r.chargeStep, Brand.Charge)
                StepBlock(stringResource(R.string.sec_discharging), r.dischargeStep, Brand.Discharge)
                Text(
                    stringResource(R.string.step_bands_hint), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp)
                )
                val bandNames = listOf(R.string.kv_band_low, R.string.kv_band_mid, R.string.kv_band_high)
                bandNames.forEachIndexed { i, name ->
                    KeyValue(
                        stringResource(name),
                        Fmt.durationOrDash(ctx, r.chargeBandMs.getOrNull(i)) + "  |  " +
                            Fmt.durationOrDash(ctx, r.dischargeBandMs.getOrNull(i))
                    )
                }
                KeyValue(stringResource(R.string.kv_step_screen_on), Fmt.durationOrDash(ctx, r.screenOnStepMs))
                KeyValue(stringResource(R.string.kv_step_screen_off), Fmt.durationOrDash(ctx, r.screenOffStepMs))
            }
        }
        if (r.chargeCurve.any { it > 0f }) {
            item {
                Panel {
                    SectionTitle(stringResource(R.string.chart_charge_curve))
                    BarChart(
                        values = r.chargeCurve, labels = List(10) { ((it + 1) * 10).toString() }, color = Brand.Charge,
                        modifier = Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp),
                        valueLabel = { String.format(Locale.US, "%.1f", it) }
                    )
                }
            }
        }
        if (r.chargeSpeeds.isNotEmpty()) {
            item {
                Panel {
                    SectionTitle(stringResource(R.string.chart_charge_speed))
                    BarChart(
                        values = r.chargeSpeeds.takeLast(14).map { it.y },
                        labels = r.chargeSpeeds.takeLast(14).map { Fmt.dayShort(it.x) },
                        color = Brand.Charge, modifier = Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp),
                        valueLabel = { String.format(Locale.US, "%.0f", it) }
                    )
                }
            }
        }

        // ---- discharging
        item {
            Panel {
                SectionTitle(stringResource(R.string.sec_discharging))
                KeyValue(stringResource(R.string.kv_sessions), r.dischargeSessions.toString())
                KeyValue(stringResource(R.string.kv_avg_speed), Fmt.pctH(r.dischargePctH))
                KeyValue(stringResource(R.string.kv_speed_screen_on), Fmt.pctH(r.screenOnPctH))
                KeyValue(stringResource(R.string.kv_speed_screen_off), Fmt.pctH(r.screenOffPctH))
                KeyValue(stringResource(R.string.kv_avg_current), Fmt.ma(r.avgDischargeMa))
                KeyValue(stringResource(R.string.kv_life_estimate), Fmt.hours(r.fullLifeHours))
                KeyValue(stringResource(R.string.kv_life_screen_on), Fmt.hours(r.screenOnLifeHours))
                KeyValue(stringResource(R.string.kv_life_screen_off), Fmt.hours(r.screenOffLifeHours))
                KeyValue(stringResource(R.string.kv_discharged), "${r.dischargedPct}%")
                KeyValue(stringResource(R.string.kv_idle), Fmt.pctH(r.idle.pctH))
                KeyValue(stringResource(R.string.kv_idle_hours), if (r.idle.hours > 0) Fmt.hours(r.idle.hours) else "—")
                val worstTs = r.idle.worstTs
                if (worstTs != null) {
                    KeyValue(
                        stringResource(R.string.kv_idle_worst),
                        Fmt.pctH(r.idle.worstPctH) + " (" + Fmt.dayShort(worstTs) + " " + Fmt.hm(worstTs) + ")"
                    )
                }
            }
        }
        item {
            Panel {
                SectionTitle(stringResource(R.string.chart_daily_discharge))
                DailyBars(r, Brand.Discharge, { it.dischargedPct.toFloat() }) { String.format(Locale.US, "%.0f", it) }
            }
        }

        // ---- screen
        item {
            Panel {
                SectionTitle(stringResource(R.string.sec_screen))
                KeyValue(stringResource(R.string.kv_screen_total), Fmt.duration(ctx, r.screenOnMs))
                KeyValue(stringResource(R.string.kv_screen_daily), Fmt.duration(ctx, r.avgDailyScreenOnMs))
                KeyValue(stringResource(R.string.kv_screen_count), r.screenOnCount.toString())
                KeyValue(stringResource(R.string.kv_screen_longest), Fmt.duration(ctx, r.longestScreenOnMs))
            }
        }
        item {
            Panel {
                SectionTitle(stringResource(R.string.chart_daily_screen))
                DailyBars(r, Brand.Screen, { it.screenOnMs / HOUR_MS.toFloat() }) { String.format(Locale.US, "%.1f", it) }
            }
        }

        // ---- temperature
        item {
            Panel {
                SectionTitle(stringResource(R.string.sec_temperature))
                KeyValue(stringResource(R.string.kv_avg_temp), Fmt.tempC(r.avgTempC))
                KeyValue(stringResource(R.string.kv_max_temp), Fmt.tempC(r.maxTempC))
                if (r.tempPoints.size >= 2) {
                    val lo = (r.tempPoints.minOf { it.y } - 2f).coerceAtLeast(0f)
                    val hi = (r.tempPoints.maxOf { it.y } + 2f).coerceAtLeast(lo + 8f)
                    Box(Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp)) {
                        LineChart(
                            points = r.tempPoints, fromX = r.from, toX = r.to, yMin = lo, yMax = hi, ySuffix = "°",
                            color = Brand.Hot, modifier = Modifier.fillMaxSize(), step = false,
                            xLabel = { if (r.to - r.from <= 36 * HOUR_MS) Fmt.hm(it) else Fmt.dayShort(it) }
                        )
                    }
                }
            }
        }

        // ---- export
        item {
            Panel {
                SectionTitle(stringResource(R.string.sec_export))
                Text(
                    stringResource(R.string.export_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp)
                )
                Button(onClick = { doExport(Exporter.Format.PDF) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.export_pdf))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { doExport(Exporter.Format.XLSX) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.export_xlsx))
                    }
                    FilledTonalButton(onClick = { doExport(Exporter.Format.CSV_EVENTS) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.export_csv_events))
                    }
                }
                FilledTonalButton(
                    onClick = { doExport(Exporter.Format.CSV_SESSIONS) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text(stringResource(R.string.export_csv_sessions)) }
            }
        }
    }
}

@Composable
private fun StepBlock(title: String, st: com.batterypulse.app.data.StepStats?, color: Color) {
    val ctx = LocalContext.current
    Text(
        title, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp)
    )
    if (st == null) {
        KeyValue(stringResource(R.string.kv_steps_count), "—")
    } else {
        KeyValue(stringResource(R.string.kv_steps_count), st.count.toString())
        KeyValue(stringResource(R.string.kv_step_avg), Fmt.duration(ctx, st.avgMs))
        KeyValue(stringResource(R.string.kv_step_median), Fmt.duration(ctx, st.medianMs))
        KeyValue(stringResource(R.string.kv_step_fastest), Fmt.durationAt(ctx, st.minMs, st.minTs))
        KeyValue(stringResource(R.string.kv_step_slowest), Fmt.durationAt(ctx, st.maxMs, st.maxTs))
    }
}

@Composable
private fun NoData() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.no_data_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DailyBars(r: Report, color: Color, value: (com.batterypulse.app.data.DayStat) -> Float, label: (Float) -> String) {
    val days = r.days.takeLast(14)
    if (days.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(60.dp)) { NoData() }
    } else {
        BarChart(
            values = days.map(value), labels = days.map { Fmt.dayShort(it.dayStart) }, color = color,
            modifier = Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp), valueLabel = label
        )
    }
}

fun shareFile(ctx: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, null))
}
