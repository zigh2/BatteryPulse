package com.batterypulse.app.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterypulse.app.BatteryPulseApp
import com.batterypulse.app.data.LocaleHelper
import com.batterypulse.app.monitor.MonitorService
import com.batterypulse.app.monitor.MonitorState

class MainActivity : ComponentActivity() {

    private val vm: MainVM by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BatteryPulseApp

        // If monitoring should be running but the service was killed, bring it back.
        if (app.settings.monitoring && !MonitorState.flow.value.running) {
            MonitorService.start(this)
        }

        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            val system = isSystemInDarkTheme()
            val dark = when (settings.theme) {
                1 -> false
                2 -> true
                else -> system
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
                onDispose { }
            }
            BatteryPulseTheme(dark, settings.dynamicColor) { AppRoot(vm) }
        }
    }
}
