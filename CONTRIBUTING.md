# Contributing

Thanks for helping! A few simple rules keep the project tidy.

1. **Open an issue first** for anything bigger than a small fix, so we can agree on the approach.
2. **Build and test** before sending a pull request: `gradle testDebugUnitTest assembleRelease` (JDK 17, Android SDK 35).
3. **Statistics belong in `Analytics.kt`** and need a unit test. Keep that file free of Android classes.
4. **Strings:** every user-visible text goes into both `values/strings.xml` and `values-ar/strings.xml`.
5. **Dependencies:** the project deliberately uses only AndroidX / Compose. Please do not add libraries without discussing it.
6. **Changelog:** add a line under the next version in `CHANGELOG.md`.

Reports of device-specific problems (current not shown, logger killed by the vendor skin, ...) are very welcome. Please
include the phone model, Android version and, if possible, a CSV export.
