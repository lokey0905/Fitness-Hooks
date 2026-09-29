# Fitness Hooks V2

An LSPosed module for Pikmin Bloom 153.0 with two independent hooks:

- Android 13 Health Connect SDK-gate compatibility.
- Recording API on mobile step-result override.

Package: `com.lokey0905.FitnessHooks`

## Hooks

### SDK gate

On Android 13 and lower, the SDK hook can override:

- `FitnessClientHealthConnect.isSupported()`
- `FitnessManager.osSupportsHealthConnect()`

It preserves the real `Build.VERSION.SDK_INT`, allowing AndroidX Health Connect to retain its Android 13 provider selection. The SDK hook is not installed on Android 14 or later.

### Recording steps

The step hook targets:

```text
FitnessClientRecordingApi.queryStepCounts(int hours, int startTimeSec)
```

When enabled, it returns an `HourlyStepCountCollectionProto` before Pikmin starts the original Google Play services query. The result contains one current-hour bucket with the configured step count and its source remains `RECORDING_API_ON_MOBILE`. It does not write into Google Play services or Health Connect.

Hook settings are delivered to an in-process receiver and persisted in Pikmin's own private storage. A startup request and settings-screen broadcasts keep the target-side copy synchronized without cross-package provider reads. The configuration broadcast is protected by a signature permission, and Android keeps each user's target-side settings separate.

The setting controls the bucket total, not a guaranteed increment on every query. Unity and server-side reconciliation remain outside this module's static verification boundary.

## Requirements

- LSPosed or a compatible Xposed framework
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
- This repository documents static analysis and build verification only; it does not claim real-device validation.

See [docs/STATIC_ANALYSIS.md](docs/STATIC_ANALYSIS.md) for the evidence boundary and hook rationale.
