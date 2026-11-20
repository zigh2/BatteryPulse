<div align="center">

# BatteryPulse · نبض البطارية

**A battery logbook for Android. It records the exact date and time of every 1 % your battery gains or loses, when charging starts and stops, when it hits 100 %, and when the screen turns on and off, then turns the data into charts, statistics and PDF reports.**

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)
![No internet](https://img.shields.io/badge/internet-not%20required-blue)
![License](https://img.shields.io/badge/license-MIT-blue)

</div>

---

## Features

| | |
|---|---|
| **Per-percent log** | One row for every 1 % step with date, time (to the second), level, change, charger type, temperature, voltage, current and screen state. |
| **Charge sessions** | Start and stop time, start and end level, time to reach 100 %, charger type (AC / USB / wireless), average and peak current, energy moved. |
| **Discharge sessions** | Same data while on battery, plus how long the screen was on. |
| **Screen tracking** | Every screen on / off with the battery level at that moment; total, daily average and longest use. |
| **Charging speed** | %/hour and mA per session, per charger type, fastest session, average time to 100 %. |
| **Discharge speed** | %/hour overall, **with screen on** and **with screen off**, and the estimated life of a full battery in each case. |
| **Battery health** | Capacity measured from the charge counter (over long charges) compared with the design capacity, equivalent full cycles, and the OS-reported health / cycle count where the phone provides it. |
| **Live dashboard** | Animated gauge, time to full or to empty, current, power, voltage, temperature, health status and a 24-hour chart with charging periods shaded. |
| **Reports** | 24 h / 7 d / 30 d / all-time statistics with level, charging-speed, daily-discharge, daily-screen-time and temperature charts. |
| **Export** | Multi-page **PDF report** (KPIs, charts, tables), **XLSX** (summary, events, sessions, daily), and CSV for events and sessions. Backup and restore of the whole database. |
| **Alerts** | Fully charged, custom charge limit (e.g. 80 %), low battery and high temperature. |
| **Private** | No internet permission, no ads, no analytics. Everything stays on the phone. |
| **Look** | Material 3, light / dark / system, optional Material You colours, English and Arabic (RTL) with an in-app language switch. |
| **Light** | No third-party libraries besides AndroidX / Compose. Hand-written XLSX writer, native PDF. |

## Install

Download the latest `BatteryPulse-vX.Y.apk` from the [Releases](../../releases) page and install it.

> **Tip:** open *Settings → Allow unrestricted background use* inside the app. On Xiaomi, Huawei, Oppo and Samsung
> phones also enable *Autostart* and lock the app in recents, otherwise the system may stop the logger and leave gaps.

## How the numbers are produced

- **Level steps** come from the system battery broadcast, so an event is written the moment Android reports a new percent. Timestamps are device time.
- **Charging speed** is measured from plug-in until the battery reaches 100 %, so the slow trickle at the end does not drag the average down.
- **Screen on / off drain** classifies each 1 % step by how much of it the screen was on; steps that mix both states are ignored so the two rates stay honest.
- **Capacity and health** use the charge counter (µAh) over sessions that moved at least 20 %, and take the median of the latest sessions. Health is that value divided by the design capacity, which the app reads from the system or which you can enter in Settings. It needs a few long charges before it shows.
- **Time remaining** uses the speed of the last few 1 % steps.
- Current readings depend on the phone. Some vendors report no current at all; the app then simply shows a dash.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for details.

## Screenshots

Add screenshots to `docs/screenshots/` and link them here.

## Release workflow (the `incoming/` folder)

1. Put the project zip in `incoming/` (e.g. `BatteryPulse-v0.02.zip`) and push to `main`.
2. GitHub Actions replaces the whole project with the zip contents, runs the unit tests, builds the APK, commits the result,
   deletes the zip and **publishes a new Release** with the plain `BatteryPulse-vX.Y.apk` (no zip) and SHA-256 sums.

The version comes from the `VERSION` file inside the zip (fallback: the number in the zip file name).
If that version was already released, it is **bumped automatically** (`0.02 → 0.03`) so every drop is a new release.

`.github/workflows/` is intentionally **not** replaced by the zip: GitHub does not let the built-in token
modify workflow files. Change workflows by committing them normally.

To make a zip from this repository: `scripts/pack.sh` → `dist/BatteryPulse-v<VERSION>.zip`.

## Signing

The repo ships `app/batterypulse.keystore` (password `batterypulse`) so that every build is signed with the **same**
key and new APKs install over old ones. It is not secret. For your own key add these repository secrets:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Build locally

```bash
gradle testDebugUnitTest        # unit tests
gradle assembleRelease          # Gradle 8.9+, JDK 17, Android SDK 35
```

## Project layout

```
app/src/main/java/com/batterypulse/app
 ├─ monitor/   MonitorService (foreground), BatteryReader, MonitorState, BootReceiver
 ├─ data/      Store (SQLite), Models, Analytics (all statistics), Settings, Fmt
 ├─ export/    Exporter: CSV, XLSX and PDF writers
 └─ ui/        Jetpack Compose: Dashboard, History, Reports, Settings
```

## Contributing

Issues and pull requests are welcome, see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT, see [LICENSE](LICENSE).

---

<div dir="rtl">

## بالعربية

**نبض البطارية** سجلّ كامل لبطاريتك: يحفظ التاريخ والوقت لكل ارتفاع أو انخفاض بمقدار 1%، ووقت بدء الشحن وتوقفه ووصوله إلى 100%، ووقت تشغيل الشاشة وإطفائها، ثم يحوّل ذلك إلى مخططات وإحصائيات وتقارير PDF وإكسل.

- سرعة الشحن وسرعة التفريغ (والشاشة تعمل / مطفأة) والعمر المقدّر للبطارية.
- صحة البطارية من السعة المقاسة مقارنةً بالسعة الأصلية، وعدد الدورات.
- لوحة حية بمؤشر دائري ومخطط آخر 24 ساعة والوقت المتبقي.
- تنبيهات: اكتمال الشحن، حد شحن تحدده (مثل 80%)، البطارية المنخفضة، ارتفاع الحرارة.
- لا يطلب صلاحية الإنترنت، ولا إعلانات، وكل البيانات تبقى على الهاتف.
- واجهة عربية كاملة (RTL) مع تبديل اللغة من داخل التطبيق.

لأفضل نتيجة: من الإعدادات اسمح للتطبيق بالعمل في الخلفية دون قيود، وفعّل «التشغيل التلقائي» على هواتف شاومي وهواوي وأوبو وسامسونج.

</div>
