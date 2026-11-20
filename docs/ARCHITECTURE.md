# Architecture

```
MonitorService (foreground, specialUse)
 ├─ BatteryReader   one reading: level, plug, status, health, temperature, voltage, current, charge counter
 ├─ Store (SQLite)  events (the log) + sessions (charge / discharge periods)
 ├─ MonitorState    in-process live status (StateFlow) for the UI
 └─ Notification    level, state, time remaining, Stop action; separate high-priority alert channel

Analytics (pure Kotlin, unit-tested)
 └─ builds a Report from events + sessions: speeds, screen split, health, daily stats, chart points

UI (Jetpack Compose, Material 3)
 ├─ Dashboard   live state + last 24 hours
 ├─ History     events and sessions with filters
 ├─ Reports     statistics, charts, export
 └─ Settings    alerts, theme, language, data, backup
```

## Data

`events` has one row per fact: `LEVEL` (a 1 % step), `PLUGGED`, `UNPLUGGED`, `FULL` (100 % reached while charging),
`SCREEN_ON`, `SCREEN_OFF`, `START`, `STOP`. Every row also stores the battery state at that moment (level, previous level,
charging flag, charger type, temperature in 0.1 °C, voltage, current, charge counter, screen state, session id).

`sessions` has one row per continuous charging or discharging period: start / end time and level, charger type, time 100 %
was reached, accumulated screen-on time, charge counter at start and end, peak temperature, and a running sum of current
readings for the average.

## Service

The service registers `ACTION_BATTERY_CHANGED` and screen on / off receivers at runtime (the system does not deliver them
to manifest receivers). All database work runs on one single-thread executor so ordering is preserved.
A one-minute tick refreshes the notification and the heartbeat time.

A session changes when the charging state changes. A new `LEVEL` row is written only when the percent actually changes.
Reaching 100 % while a charger is connected writes one `FULL` row per session.

**Restarts.** If the process or the phone was down, the session that is still open is closed at the last heartbeat and a
`STOP` row is written there, so gaps are never counted as charging, discharging or screen time.

## Statistics

- **Speed** of a session is the percent moved divided by the time it took. A charging session stops counting at 100 %.
- **Screen split.** Each 1 % discharge step is classified as screen-on if the screen was on for at least 90 % of it and
  screen-off if it was on for at most 10 %. Mixed steps are ignored.
- **Capacity.** For sessions that moved at least 20 %, capacity = charge-counter change / percent moved. The median of the
  latest 15 values is used. Health = capacity / design capacity.
- **Cycles.** Equivalent cycles = total discharged percent / 100. If the OS reports a cycle count (Android 14+), that is shown instead.
- **Time remaining** extrapolates the speed of the last six 1 % steps of the current session.

## Exports

CSV, XLSX (hand-written OOXML in a zip) and PDF (`android.graphics.pdf`) are written without third-party libraries.
The PDF draws its own charts on the page canvas.

## Known limits

- Current (mA) and the charge counter are optional on Android and some vendors report nothing or odd units. The app handles
  missing values and shows a dash.
- Android may stop background apps aggressively on some phones. Use the battery-optimisation shortcut in Settings.
