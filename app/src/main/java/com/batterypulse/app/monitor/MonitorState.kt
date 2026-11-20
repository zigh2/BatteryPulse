package com.batterypulse.app.monitor

import kotlinx.coroutines.flow.MutableStateFlow

data class Live(
    val running: Boolean = false,
    val sessionId: Long = 0L,
    val level: Int = -1,
    val charging: Boolean = false,
    val plug: Int = 0,
    val status: Int = 0,
    val health: Int = 0,
    val tempDeci: Int = 0,
    val voltageMv: Int = 0,
    val currentMa: Int? = null,
    val screenOn: Boolean = false,
    /** When the current charging / discharging period started. */
    val sinceMs: Long = 0L,
    /** Estimated time until 100 % (charging) or 0 % (discharging). */
    val etaMs: Long? = null,
)

/** In-process live status shared between the service and the UI. */
object MonitorState {
    val flow = MutableStateFlow(Live())
}
