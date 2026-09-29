# Changelog

## 2.1.0 - 2026-09-29

- Rebuilt the settings screen with Material 3 components, day/night colors, and dynamic color support.
- Disabled the Android 13 SDK compatibility setting on Android 14 and later at UI, persistence, and transport layers.
- Added two-row quick `+/- 1`, `10`, `100`, `500`, `1000`, and `5000` step controls.
- Renamed the current package to `com.lokey0905.FitnessHooks`.
- Added an LSPosed activation warning to the settings screen.

## 2.0.3 - 2026-09-29

- Replaced cross-package provider reads with a target-process configuration receiver and Pikmin-private cached settings.
- Added a startup configuration handshake and settings-screen updates.
- Protected configuration updates with a signature permission.

## 2.0.2 - 2026-09-29

- Restored the read-only configuration provider after LSPosed 2.2.0 runtime testing showed that legacy `XSharedPreferences` is not present.
- Marked the module application `forceQueryable` so Pikmin can resolve the provider through Android package-visibility filtering.
- Restricted provider configuration calls to the Pikmin package.

## 2.0.1 - 2026-09-29

- Replaced the exported configuration provider with LSPosed `XSharedPreferences` for multi-user-safe settings access.
- Changed the Recording API override to return before the original Google Play services query.
- Added one-time configuration diagnostics to the LSPosed log.

## 2.0.0 - 2026-09-29

- Split the module into independent SDK-gate and Recording API step hooks.
- Added an in-app settings screen for enabling each hook and selecting the returned step count.
- Kept the Recording API hook available on Android 14 and later.
- Replaced the final `HourlyStepCountCollectionProto` without modifying Google Play services data.

## 1.0.0 - 2026-09-29

- Added the Android 13 Health Connect compatibility hooks for Pikmin Bloom 153.0.
- Kept the real SDK level intact so AndroidX can select the standalone APK provider.
- Renamed the module package to `com.lokey0905.pikminfixhca13`.
- Replaced repository-local signing credentials with optional user-local Gradle properties.
- Added open-source-oriented build, compatibility, and static-analysis documentation.
