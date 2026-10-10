# Fitness Hooks 2.2.0

An LSPosed module for Pikmin Bloom 153.0 with two independent hooks:

- Android 13 Health Connect SDK-gate compatibility.
- Recording API on mobile step-result override.

Package: `com.lokey0905.FitnessHooks`

Version: `2.2.0` (`versionCode 4`).

The top-right About action shows the app icon, build version, author, and independent-project disclaimer.

SDK compatibility is in the shared area above the mode selector. Health Connect authorization and settings use matching settings rows.

Inputs above 50,000 steps show a warning and require confirmation before saving or syncing. This is an input threshold, not a measurement of the user's daily total. [Pikmin Bloom's official seedling FAQ](https://niantic.helpshift.com/hc/zh-hant/23-pikmin-bloom/faq/3343-faq-about-seedlings/) limits steps reflected in planter-pack seedlings to 50,000 per day; this is not stated as a cap on all game step records. The UI also notes that the effects of time-zone changes on game step attribution and daily-limit resets are unverified.

## Update Checks

Opening the settings app automatically checks the latest published stable [GitHub Release](https://github.com/lokey0905/Fitness-Hooks/releases). The request runs off the UI thread and is retained across screen rotation. A newer version shows its version and release notes after existing settings dialogs close. Choosing Download opens the release page in a browser; the app does not download or install APKs automatically. There is no manual check button or background schedule.

The check uses `tag_name`, accepting numeric tags such as `2.3.0` or `v2.3.0`, and compares version components numerically with `BuildConfig.VERSION_NAME`. Drafts and prereleases are ignored. No prompt is shown for an equal/older version, no accessible release, offline/failed requests, or GitHub rate limits; failures are not treated as proof that the app is up to date. Dismissing a prompt suppresses it for the current settings session, including rotation and returning from another app.

Only a fixed public GitHub API endpoint is queried, without credentials, step values, Health Connect records, or device identifiers. Internet permission supports this version-metadata request; it does not change Health Connect's write-only permission scope. Publish a public, non-prerelease GitHub Release with a matching numeric version tag and the intended APK to enable future update prompts. Bump both the APK's `versionName` and `versionCode`, and retain the signing key for installable upgrades. The `latest` endpoint chooses one release, so release ordering and tags must be maintained consistently.

## Health Connect Sync

Health Connect is the leftmost mode and the default when no mode has been saved. Existing explicit mode selections are preserved. The settings screen selects either Health Connect or Recording API. Selecting Health Connect immediately disables the target-side Recording override. Each mode keeps its own step value. Mode dialogs remind the user to select the matching step source in the target app; the module does not change the target app's source selection.

Health Connect sync uses the official AndroidX client with user-granted `WRITE_STEPS` permission. Press Sync to write this app's manual step total for the current local hour. The label shows the hour, and the explanation below it states that this is not the day's Health Connect total. The input resets for a new hour. A pending sync cannot write the previous hour's input into a new hour.

Repeated sync updates the same record using a fixed hourly client ID and a persisted increasing version. The record is marked `Metadata.manualEntry`, with Fitness Hooks as the data origin. Setting zero deletes this app's step records in the current-hour interval. Other apps' records are not modified. Sync has no background worker and does not request read or background permissions.

Android 13 and lower require an installed, enabled, up-to-date standalone Health Connect provider. The app offers the Google Play download/update page and disables unavailable SDK compatibility and sync controls. Android 14 and later use the framework provider. Managed work profiles remain blocked according to Health Connect's documented limitation. Other OEM clone spaces are not assumed to be work profiles: the app follows `HealthConnectClient.getSdkStatus`, then initializes the client and checks granted permissions before operations. In AndroidX 1.1.0, Android 14+ still reports unavailable when `UserManager.isProfile()` is true or the framework service is absent; the app does not bypass this check. Opening system Health Connect settings is not proof that a cloned app can write, and XSpace-specific behavior has not been device-verified. Installing an APK cannot add a missing framework service. Permission checks and writes still handle initialization failure or permission revocation.

Writing successfully to Health Connect does not prove that another app has read or accepted the record. Grant that app its own step-read permissions. Health Connect can apply source priority and aggregation rules.

References: [Android availability](https://developer.android.com/health-and-fitness/health-connect/availability), [permission setup](https://developer.android.com/health-and-fitness/health-connect/get-started), [write and upsert](https://developer.android.com/health-and-fitness/health-connect/write-data).

## Hooks

### SDK gate

On Android 13 and lower, the SDK hook can override:

- `FitnessClientHealthConnect.isSupported()`
- `FitnessManager.osSupportsHealthConnect()`

It preserves the real `Build.VERSION.SDK_INT`, allowing AndroidX Health Connect to retain its Android 13 provider selection. The SDK hook is not installed on Android 14 or later. With no saved preference, the setting defaults to on on supported Android 13-and-below devices with an available Health Connect provider; saved choices are preserved. It stays disabled when Health Connect is unavailable. The target-side fallback remains off until validated module settings arrive.

### Recording steps

The step hook targets:

```text
FitnessClientRecordingApi.queryStepCounts(int hours, int startTimeSec)
```

When enabled, it returns an `HourlyStepCountCollectionProto` before Pikmin starts the original Google Play services query. The result contains one current-hour bucket with the configured step count and its source remains `RECORDING_API_ON_MOBILE`. It does not write into Google Play services or Health Connect.

Hook settings are delivered to an in-process receiver and persisted in Pikmin's own private storage. A startup request and settings-screen broadcasts keep the target-side copy synchronized without cross-package provider reads. The configuration broadcast is protected by a signature permission, and Android keeps each user's target-side settings separate.

The setting controls the bucket total, not a guaranteed increment on every query. Unity and server-side reconciliation remain outside this module's static verification boundary.

## Requirements

- LSPosed or a compatible Xposed framework for the hook features; direct Health Connect sync does not need LSPosed
- Pikmin Bloom 153.0
- For the SDK hook: Android 13 with the standalone Health Connect provider and permissions
- For the step hook: Pikmin must select `RECORDING_API_ON_MOBILE`

## Installation

1. Install the module APK.
2. Open the module app and configure the two hooks.
3. Enable it in LSPosed.
4. Add both Fitness Hooks and Pikmin Bloom to the LSPosed scope.
5. Force-stop and restart both apps.

The Material 3 settings screen provides quick `+/- 1`, `10`, `100`, `500`, `1000`, and `5000` adjustments. It displays an activation warning when the module app process is not injected by LSPosed. On Android 14 and later, the Android 13 SDK compatibility option is disabled and always synchronized as off.

The expected LSPosed log includes:

```text
FitnessHooks: target loaded; configBridgeHooks=1; sdkHooks=2; stepHooks=1
FitnessHooks: target config received; saved=true; sdk=true; steps=true; count=6000
FitnessHooks: config loaded from target storage; sdk=true; steps=true; count=6000
FitnessHooks: Recording step override active; steps=6000
```

## Building

Build an installable debug APK:

```powershell
.\gradlew.bat clean assembleDebug
```

Run local interval/revision tests and Android lint:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug
```

Build a release APK:

```powershell
.\gradlew.bat clean assembleRelease
```

Release builds are unsigned unless all four signing properties are supplied in a user-local Gradle properties file, such as `%USERPROFILE%\.gradle\gradle.properties`:

```properties
RELEASE_STORE_FILE=C:/path/to/release.jks
RELEASE_STORE_PASSWORD=your-password
RELEASE_KEY_ALIAS=your-alias
RELEASE_KEY_PASSWORD=your-password
```

The local `app/libs/xposed-api-stubs.jar` dependency is `compileOnly` and is not packaged into the APK. Confirm its provenance and redistribution terms before publishing the repository.

## Compatibility Notes

- The hook targets names and signatures observed in Pikmin Bloom 153.0. Later releases may rename or remove them.
- A successful SDK hook does not prove that the Health Connect provider is installed, available, or authorized.
- A successful step hook proves only that Pikmin received the replacement protobuf; it does not prove server acceptance or the awarded delta.
- Settings are per Android user. Configure the module in the same user/profile that runs Pikmin.
- Changing the package name means this build does not upgrade older `com.lokey.pikminhca13` or `com.lokey0905.pikminfixhca13` builds. Disable or uninstall the old module before enabling this one to avoid duplicate hooks.
- Version 2.2.0 passed 73 automated tests and APK signature verification. The maintainer reports no issues in their current physical-device test; device, OS, and profile coverage were not specified, so this is not a full compatibility guarantee.

See [docs/STATIC_ANALYSIS.md](docs/STATIC_ANALYSIS.md) for the evidence boundary and hook rationale.
