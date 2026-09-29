# Pikmin Fix HC A13

An LSPosed compatibility module for Pikmin Bloom 153.0 on Android 13. It bypasses only Pikmin's Android 14 operating-system gates while preserving the real `Build.VERSION.SDK_INT`, allowing AndroidX Health Connect to select the Android 13 APK provider.

Package: `com.lokey0905.pikminfixhca13`

## Behavior

The module is scoped to `com.nianticlabs.pikmin` and installs two hooks only when the real SDK level is below 34:

- `FitnessClientHealthConnect.isSupported()` returns `true`.
- `FitnessManager.osSupportsHealthConnect()` returns `true`.

On Android 14 and later, no hook is installed. The module does not alter `Build.VERSION.SDK_INT`, force Google Fit, grant permissions, or replace Health Connect's provider selection.

## Requirements

- Android 13 with the standalone Health Connect provider installed
- LSPosed or a compatible Xposed framework
- Pikmin Bloom 153.0
- Health Connect permissions granted to Pikmin Bloom

## Installation

1. Install the module APK.
2. Enable it in LSPosed.
3. Scope the module to Pikmin Bloom only.
4. Force-stop and restart Pikmin Bloom.

The expected LSPosed log includes:

```text
PikminFixHCA13: target loaded; sdk=33; hooks=2
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
- A successful hook does not prove that the Health Connect provider is installed, available, or authorized.
- Changing the package name means this build does not upgrade the older `com.lokey.pikminhca13` module. Disable or uninstall the old module before enabling this one to avoid duplicate hooks.
- This repository documents static analysis and build verification only; it does not claim real-device validation.

See [docs/STATIC_ANALYSIS.md](docs/STATIC_ANALYSIS.md) for the evidence boundary and hook rationale.
