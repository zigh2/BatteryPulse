# Changelog

All notable changes are listed here. The newest version is on top.
Release notes on GitHub are taken from the section matching the version.

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
