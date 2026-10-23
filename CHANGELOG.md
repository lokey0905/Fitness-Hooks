# Changelog

## 2.2.1 (Pre-release) - 2026-10-23

- Updated versionName to 2.2.1 and versionCode to 6.
- Published as a pre-release for manual download; the stable-only startup updater in 2.2.0 does not notify for this release.
- No runtime implementation changes relative to 2.2.0 are present in this source revision.

## 2.2.0 - 2026-10-11

- Set versionCode to 4 and show `fitness hook` plus the build version in the toolbar.
- Added a top-right About dialog with the app icon, build version, author, and project disclaimer.
- Added an automatic startup check of the public Fitness-Hooks GitHub Releases, with numeric version comparison, lifecycle-aware prompts, and an explicit link to the release page; no automatic downloads or installs.
- Moved SDK compatibility above the mode selector and matched Health Connect actions to the settings-row style.
- Added an over-50,000-step warning and confirmation, with seedling-specific daily-limit and time-zone caveats.
- Added mutually exclusive Recording API and Health Connect modes with source-selection reminders.
- Made Health Connect the leftmost mode and the default for users without a saved mode; preserved explicit choices.
- Restored the SDK compatibility setting's default to on for supported pre-Android-14 environments, retaining availability checks and an off fallback for missing target configuration.
- Distinguished managed work profiles from OEM clone spaces, deferring other profile availability to the Health Connect SDK without bypassing its restrictions.
- Added explicit, permission-gated Health Connect writes for the current local hour's manual steps.
- Added a current-hour label, explanatory text, separate mode values, and hour rollover handling.
- Deduplicate repeated writes by hourly client ID and persistent revision; zero clears this app's current-hour records.
- Check provider availability and profile support, and offer download/update on Android 13 and below.
- Added a Health Connect permission rationale screen and focused interval/revision tests.
- Passed 73 automated tests; the maintainer reports no issues in their current physical-device test, without claiming a complete device/OS/profile compatibility matrix.

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
