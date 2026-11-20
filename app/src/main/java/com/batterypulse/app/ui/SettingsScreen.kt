package com.batterypulse.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterypulse.app.BuildConfig
import com.batterypulse.app.R
import com.batterypulse.app.data.Fmt
import java.util.Date

@Composable
fun SettingsScreen(vm: MainVM, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val st by vm.settings.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.backup(uri) { ok ->
            onMessage(ctx.getString(if (ok) R.string.backup_ok else R.string.backup_failed))
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restore(uri) { ok ->
            onMessage(ctx.getString(if (ok) R.string.restore_ok else R.string.restore_failed))
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        // ---- alerts
        item {
            Panel {
                SectionTitle(stringResource(R.string.set_alerts))
                SwitchRow(stringResource(R.string.set_alert_full), null, st.alertFull) { v -> vm.settings.update { it.copy(alertFull = v) } }
                SwitchRow(stringResource(R.string.set_alert_limit), stringResource(R.string.set_alert_limit_hint), st.alertLimit) { v ->
                    vm.settings.update { it.copy(alertLimit = v) }
                }
                if (st.alertLimit) {
                    StepperRow(stringResource(R.string.set_limit_pct), st.limitPct, "%", 50, 99, 1) { v -> vm.settings.update { it.copy(limitPct = v) } }
                }
                SwitchRow(stringResource(R.string.set_alert_low), null, st.alertLow) { v -> vm.settings.update { it.copy(alertLow = v) } }
                if (st.alertLow) {
                    StepperRow(stringResource(R.string.set_low_pct), st.lowPct, "%", 5, 50, 1) { v -> vm.settings.update { it.copy(lowPct = v) } }
                }
                SwitchRow(stringResource(R.string.set_alert_temp), null, st.alertTemp) { v -> vm.settings.update { it.copy(alertTemp = v) } }
                if (st.alertTemp) {
                    StepperRow(stringResource(R.string.set_temp_limit), st.tempLimitC, "°C", 30, 60, 1) { v -> vm.settings.update { it.copy(tempLimitC = v) } }
                }
            }
        }

        // ---- look & language
        item {
            Panel {
                SectionTitle(stringResource(R.string.set_look))
                Text(stringResource(R.string.set_theme), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                ChipRow(
                    listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark)),
                    st.theme, { v -> vm.settings.update { it.copy(theme = v) } }
                )
                if (Build.VERSION.SDK_INT >= 31) {
                    SwitchRow(stringResource(R.string.set_dynamic), stringResource(R.string.set_dynamic_hint), st.dynamicColor) { v ->
                        vm.settings.update { it.copy(dynamicColor = v) }
                    }
                }
                Text(stringResource(R.string.set_language), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                val langs = listOf("system", "en", "ar")
                ChipRow(
                    listOf(stringResource(R.string.theme_system), "English", "العربية"),
                    langs.indexOf(st.lang).coerceAtLeast(0),
                    { i ->
                        vm.settings.update { it.copy(lang = langs[i]) }
                        (ctx as? Activity)?.recreate()
                    }
                )
            }
        }

        // ---- reliability
        item {
            Panel {
                SectionTitle(stringResource(R.string.set_reliability))
                SwitchRow(stringResource(R.string.set_boot), stringResource(R.string.set_boot_hint), st.resumeOnBoot) { v ->
                    vm.settings.update { it.copy(resumeOnBoot = v) }
                }
                Text(
                    stringResource(R.string.set_battery_opt_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
                FilledTonalButton(onClick = {
                    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    try {
                        ctx.startActivity(direct)
                    } catch (e: Exception) {
                        try {
                            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }
                }) { Text(stringResource(R.string.set_battery_opt)) }
            }
        }

        // ---- data
        item {
            Panel {
                SectionTitle(stringResource(R.string.set_data))
                StepperRow(stringResource(R.string.set_retention), st.retentionDays, stringResource(R.string.unit_days), 7, 3650, 30) { v ->
                    vm.settings.update { it.copy(retentionDays = v) }
                }
                StepperRow(
                    stringResource(R.string.set_design_capacity) + if (st.designMah == 0) " (${stringResource(R.string.auto)})" else "",
                    st.designMah, "mAh", 0, 30000, 100
                ) { v -> vm.settings.update { it.copy(designMah = v) } }
                Text(
                    stringResource(R.string.set_design_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = {
                        backupLauncher.launch("batterypulse_${Fmt.date(Date().time)}.db")
                    }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_backup)) }
                    FilledTonalButton(onClick = {
                        restoreLauncher.launch(arrayOf("*/*"))
                    }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_restore)) }
                }
                OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // ---- about
        item {
            Panel {
                SectionTitle(stringResource(R.string.set_about))
                KeyValue(stringResource(R.string.app_name), "v${BuildConfig.VERSION_NAME}")
                KeyValue(stringResource(R.string.set_device), "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
                Text(
                    stringResource(R.string.set_about_text), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_title)) },
            text = { Text(stringResource(R.string.clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll()
                    confirmClear = false
                    onMessage(ctx.getString(R.string.deleted))
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StepperRow(title: String, value: Int, unit: String, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Text("−") }
        Text(
            "$value $unit", fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        OutlinedButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { Text("+") }
    }
}
