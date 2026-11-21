# Changelog

All notable changes are listed here. The newest version is on top.
Release notes on GitHub are taken from the section matching the version.

## [0.02]

### Added
- **Time per 1 %**: every 1 % step now stores how long it took. Shown in the history, with average, median, fastest and slowest, by level band (0–19, 20–79, 80–100), with screen on / off, and as a charging curve (minutes per 1 % by level). Included in PDF, XLSX and CSV.
- Average time from 80 % to 100 %.
- Charger comparison: sessions, speed, current and power per charger type.
- Standby drain: battery lost while the screen stayed off for an hour or more, with the worst period.
- Measured capacity trend over time (weekly).
- Gaps: periods with no data are shaded grey in charts and the line is no longer drawn across them; total missing time is shown.
- Home-screen widget and Quick Settings tile.
- Weekly PDF report notification (can be turned off in Settings).

### Changed
- Database upgraded to version 2 (adds the step duration column); existing data is kept.

## [0.01]

### Added
- Foreground logger that records every 1 % battery step, charger connect / disconnect, reaching 100 %, screen on / off and tracking start / stop, each with temperature, voltage, current and charger type.
- Charge and discharge sessions with duration, level range, speed (%/h), average current, energy moved, screen-on time and peak temperature.
- Live dashboard: gauge, time to full / empty, current, power, voltage, temperature, health, 24-hour level chart with charging shaded.
- Reports for 24 h / 7 d / 30 d / all time: charging speed (also per charger type), discharge speed with screen on and off, estimated battery life, battery health and cycles, screen time, temperature, and daily charts.
- Export: PDF report with charts and tables, XLSX, CSV (events and sessions). Backup and restore of the database.
- Alerts: fully charged, charge limit, low battery, high temperature.
- English and Arabic (RTL), light / dark / Material You themes, resume after reboot.
- Unit tests for the statistics code and a GitHub workflow that tests, builds and publishes releases from `incoming/`.
