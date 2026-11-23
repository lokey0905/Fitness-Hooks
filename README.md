# Fitness Hooks 2.2.3

An LSPosed module for Pikmin Bloom with independent compatibility and step hooks:

- Android 13 Health Connect SDK-gate compatibility.
- Experimental Android 14+ routing to the standalone Health Connect APK.
- Recording API on mobile step-result override.

Package: `com.lokey0905.FitnessHooks`

Current source version: `2.2.3` (`versionCode 6`).

The top-right Settings action controls experimental features and dynamic colors. The About action shows the app icon, build version, author, and independent-project disclaimer.

Compatibility controls appear below the mode selector in Health Connect mode. A single outlined Material 3 action card below the compatibility controls (including the experimental standalone HC option when visible) groups Health Connect status, the primary action, and a secondary text button for HC settings. The primary action offers installation/update when needed, then write authorization, then writing steps once permission has been verified. Completing authorization does not automatically write the draft; press Write steps to send it. Recording mode keeps its Save action below the step controls.

The app compiles against API 37 and targets the same API (`targetSdk = compileSdk`), retaining `minSdk 28`. Both the settings and permission-rationale screens handle system bars, display cutouts, and keyboard insets; light/dark system-bar icons follow the surface color. Dependency versions and optional maintenance updates are recorded in [the dependency audit](docs/dependency-audit-2026-11-23.md).

System information contains two independent sections: local compatibility checks and App installation checks. Both start collapsed when no known issue is found. A failed compatibility check or a missing app automatically expands its respective section; unknown results alone do not force it open. A manual expand/collapse choice takes precedence for the current screen and survives screen recreation. A fresh launch uses the current results to choose the default again.

App installation checks always list Google Fit, Health Connect, and Pikmin Bloom, regardless of the mode or experimental setting. The header has no pass/fail or installation-count summary; expand it to see each app's installed, not-installed, or unknown status. Health Connect counts as installed when either the system provider or standalone APK is present. Checks use the current Android user without launching apps or requesting replies from their processes. Installed but disabled apps still count as installed, while lookup failures remain unknown. Returning to the screen or pressing **Check again** refreshes the results. Installation does not establish Hook activation, permissions, or step synchronization.

Inputs above 50,000 steps show a warning and require confirmation before saving or syncing. This is an input threshold, not a measurement of the user's daily total. [Pikmin Bloom's official seedling FAQ](https://niantic.helpshift.com/hc/zh-hant/23-pikmin-bloom/faq/3343-faq-about-seedlings/) limits steps reflected in planter-pack seedlings to 50,000 per day; this is not stated as a cap on all game step records. The UI also notes that the effects of time-zone changes on game step attribution and daily-limit resets are unverified.

## Update Checks

Opening the settings app automatically checks the latest published stable [GitHub Release](https://github.com/lokey0905/Fitness-Hooks/releases). The request runs off the UI thread and is retained across screen rotation. A newer version shows its version and release notes after existing settings dialogs close. Choosing Download opens the release page in a browser; the app does not download or install APKs automatically. There is no manual check button or background schedule.

The check uses `tag_name`, accepting numeric tags such as `2.3.0` or `v2.3.0`, and compares version components numerically with `BuildConfig.VERSION_NAME`. Drafts and prereleases are ignored. No prompt is shown for an equal/older version, no accessible release, offline/failed requests, or GitHub rate limits; failures are not treated as proof that the app is up to date. Dismissing a prompt suppresses it for the current settings session, including rotation and returning from another app.

Only a fixed public GitHub API endpoint is queried, without credentials, step values, Health Connect records, or device identifiers. Internet permission supports this version-metadata request; it does not change Health Connect's write-only permission scope. Publish a public, non-prerelease GitHub Release with a matching numeric version tag and the intended APK to enable future update prompts. Release 2.2.3 retains `versionCode 6` at the maintainer's request; update notifications compare `versionName`. Future releases should normally increase both version fields and retain the signing key. The `latest` endpoint chooses one release, so release ordering and tags must be maintained consistently.

## Health Connect Sync

Health Connect is the leftmost mode and the default when no mode has been saved. Existing explicit mode selections are preserved. The settings screen selects either Health Connect or Recording API. Selecting Health Connect immediately disables the target-side Recording override. Each mode keeps its own step value. Mode dialogs remind the user to select the matching step source in the target app; the module does not change the target app's source selection.

Health Connect sync uses the official AndroidX client with user-granted `WRITE_STEPS` permission. Press Write steps to write this app's manual step total for the current local hour. The label shows the hour, and the explanation below it states that this is not the day's Health Connect total. The input resets for a new hour. A pending sync cannot write the previous hour's input into a new hour.

Repeated sync updates the same record using a fixed hourly client ID and a persisted increasing version. The record is marked `Metadata.manualEntry`, with Fitness Hooks as the data origin. Setting zero deletes this app's step records in the current-hour interval. Other apps' records are not modified. Sync has no background worker and does not request read or background permissions.

Android 13 and lower require an installed, enabled, up-to-date standalone Health Connect provider. The app offers the Google Play download/update page and disables unavailable SDK compatibility and sync controls. Android 14 and later use the framework provider by default, with an experimental APK route described below. Managed work profiles remain blocked. Other OEM clone spaces are not assumed to be work profiles: the default route follows `HealthConnectClient.getSdkStatus`, then initializes the client and checks granted permissions before operations. In AndroidX 1.1.0, the Android 14+ framework route reports unavailable when `UserManager.isProfile()` is true or the framework service is absent. The experimental APK route checks the APK in the current Android user instead. Opening a settings page is not proof that a cloned app can read or write, and XSpace-specific behavior has not been device-verified. Permission checks and writes still handle initialization failure or permission revocation.

Writing successfully to Health Connect does not prove that another app has read or accepted the record. Grant that app its own step-read permissions. Health Connect can apply source priority and aggregation rules.

References: [Android availability](https://developer.android.com/health-and-fitness/health-connect/availability), [permission setup](https://developer.android.com/health-and-fitness/health-connect/get-started), [write and upsert](https://developer.android.com/health-and-fitness/health-connect/write-data).

## Hooks

### SDK gate

On Android 13 and lower, the SDK hook can override:

- `FitnessClientHealthConnect.isSupported()`
- `FitnessManager.osSupportsHealthConnect()`

It preserves the real `Build.VERSION.SDK_INT`, allowing AndroidX Health Connect to retain its Android 13 provider selection. The SDK hook is not installed on Android 14 or later. With no saved preference, the setting defaults to on on supported Android 13-and-below devices with an available Health Connect provider; saved choices are preserved. It stays disabled when Health Connect is unavailable. The target-side fallback remains off until validated module settings arrive.

The system information card keeps the real SDK and U Extension values. Its **local compatibility checks** run equivalents of Pikmin's `isSupported()` and `osSupportsHealthConnect()`: SDK 34+, an available selected Health Connect provider, and the background-read feature reported available. The module also hooks these local methods on Android 13 and lower using the same override callback and the app's saved, validated SDK compatibility setting. Include Fitness Hooks in the LSPosed scope and restart it after enabling the module. The compatibility controls appear below the mode selector in Health Connect mode. Changing a compatibility switch immediately saves the preference, publishes the effective setting, and refreshes the local checks. Temporary provider unavailability disables the Android 13 SDK override without erasing the saved preference. These checks do not read health records or request additional permissions. They show results in Fitness Hooks' own process, not confirmation that the separate Pikmin process was hooked or has its read permissions.

### Experimental standalone Health Connect on Android 14+

Open Settings from the toolbar, enable **Experimental features**, and turn on **Use standalone Health Connect** in Health Connect mode. Both settings default to off. This replaces the former U Extension override; its old saved switch is deliberately ignored. Android 13 already uses the APK provider and does not need this option. The app never changes `SDK_INT`, system properties, or U Extension results.

The selected route uses `com.google.android.apps.healthdata` for Fitness Hooks writes, Pikmin reads, and the supported Google Fit integration described below. The module creates the AndroidX APK client directly; the target hooks replace the factory/status selection and explicitly open the APK settings page. It also hooks the standalone HC process itself: when the expected class and method structure matches, `cvv.a()` (APK disabled) and `cvv.b()` (migration enabled) return false while this mode is enabled, retaining the APK's own settings and API instead of its Android 14 migration route. APK permission requests remain separate from framework permissions. Pikmin still requires genuine step-read and background-read grants from the APK. The local probe checks the APK feature implementation instead of the platform extension. No permission grant or feature result is fabricated.

Install the standalone APK in the same Android user as Fitness Hooks and Pikmin. The provider-side compatibility hook has no fixed APK version allowlist; it validates the `cvv` class, fields, constructor, and methods before installing both hooks. Its reference analysis used **Health Connect `2026.10.01.00.release` (`versionCode 283955`)**. Matching structure is required but does not prove identical behavior in another build. The client still requires the existing minimum API version (`versionCode 171302`), an enabled exported Health Data service, and APK background-feature availability. Missing, outdated, disabled, or failing APK providers stop the selected operation; they never silently fall back to the system data store. Turning off the experimental master setting restores normal routing while retaining the APK choice. Switching routes is disabled during a step sync.

The toolbar's **Open HC** shortcut opens system HC directly when the standalone APK is absent. When the APK is installed in the current Android user, it offers **App Health Connect** and **System Health Connect**. Opening either settings page does not change the selected sync provider. An unavailable settings activity shows an error; the APK may still redirect itself if its compatibility hook is inactive.

In LSPosed, manually add **Health Connect (`com.google.android.apps.healthdata`)** and **Google Fit (`com.google.android.apps.fitness`)** to the module scope alongside Fitness Hooks and Pikmin; updating the module does not guarantee that an existing scope selection gains these entries. Install the apps in the same Android user. After updating the module or changing the route or scope, force-stop and reopen **Health Connect, Google Fit, and Pikmin**. The hook also invalidates Pikmin's cached client on observed route changes.

The HC and Fit processes read only the effective standalone switch from a read-only module provider at startup. The provider checks the caller's Android user and HC/Fit package UID; subsequent updates require the module's signature permission. The startup wait is limited to 750 ms. A timeout, failure, or missing/invalid value leaves the choice pending instead of selecting system HC. While pending, Fit's hooked HC operations report unavailable or fail before opening a client or sync-state store. The same in-flight read can complete later, and a signed broadcast can also resolve the choice; an older read cannot overwrite a newer verified update. Only a verified boolean fixes Fit's provider for the process. Subsequent route changes still require restarting Fit so its cached client and sync-state stores remain consistent. The HC APK's compatibility gate remains off until a verified enable value arrives. This channel does not expose step values or health records, and does not trigger or reschedule Google Fit synchronization.

A late verified value permits later calls; it does not replay failed calls or guarantee that Fit retries them. In the inspected Fit build, initializing an HC settings screen while the route is still pending can propagate the blocked store's exception and close the screen or process. Reopen Fit after configuration is available in that case. This pending-screen behavior and cold-start recovery have not been verified on a physical device with this build.

Allow **Health Connect itself** usage access in Android's Usage access settings. The inspected APK has its own prompt and settings link when this is missing during background-read authorization; Fitness Hooks does not grant it automatically. Separately grant Fitness Hooks permission to write steps, and Pikmin permission to read steps and read in the background, within the standalone HC provider. Enable HC sync again in Google Fit and complete its own permission request for the APK provider. Existing system-HC records and permissions are not copied; input drafts are retained and syncing writes only to the selected provider. Apps outside the module's supported scope keep their own provider choice.

This does not recover APK records previously migrated or deleted. Restarting HC is required because the hooks cannot cancel migration work that already passed its checks. Existing migration jobs may retry, and the inspected APK's cleanup worker cancels itself on real Android 14+; normal APK maintenance behavior is not guaranteed by restoring the settings page.

This remains an unsupported experimental route. The provider hook is based on the inspected APK's two migration checks and leaves the real SDK, feature checks, permission grants, and usage-access checks intact. Its restored UI/API behavior and actual background reads have not yet passed device verification. Other APK versions, devices, Android users, work profiles, and OEM clone spaces are not established as compatible. Installation/version checks and automated tests do not establish runtime provider support.

#### Google Fit routing and sync state

The Fit hook was derived from the installed **`2026.10.22.02.arm64-v8a.release` (`versionCode 2027389421`)**. There is no version allowlist; the required class and method structures must match. On Android 14+, it redirects `gkv.B()` to Fit's own APK client `btt(Context)`, replaces `bsy.a(Context)` availability with APK-provider checks, uses Fit's existing `buj` permission contract through `btg`, and routes HC settings to the APK. Actual permission results, provider signature checks, feature availability, record conversion, and Fit's sync rules remain intact. This is not a claim that every Fit version supports the same obfuscated symbols.

Fit keeps one HC route for the lifetime of each process. Restart it whenever the experiment or its master switch changes. The client, permission, and sync-state hook groups must all install before the route is enabled. This prevents a cached sync store from being used with a different provider halfway through a process.

The APK route gives separate names to five HC-only stores (`AhpConsent`, `AhpDownsyncState`, `AhpUpsyncState`, and their two debug stores) and two databases (`ahp_downsync_interval_queue.db`, `ahp_mapping.db`). The names receive the `_fitnesshooks_hc_apk` suffix before they are opened. System HC's tokens, checkpoints, consent, and ID mapping remain in their original files; switching back and restarting Fit uses those files again. No existing state or health record is deleted, copied, or migrated. The new APK-side consent starts with Fit's normal consent flow; it is separate from actual HC permission grants.

This enables Fit's existing synchronization to target the APK provider after consent and authorization. It does not force a sync, alter DeFit records, bypass source filtering, or promise that historical steps are immediately backfilled. End-to-end DeFit → Fit → APK HC → Pikmin delivery and background operation still require device acceptance.

### Appearance settings

Settings also offers **Disable dynamic colors**. Dynamic colors remain enabled by default when supported; disabling them uses the app's own theme. The preference is saved immediately and the screen is recreated when the settings dialog closes to apply a changed appearance.

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
- Pikmin Bloom with the matching client methods (original hooks inspected in 153.0; HC support checks also inspected in 156.0)
- For the SDK hook: Android 13 with the standalone Health Connect provider and permissions
- For experimental standalone HC on Android 14+: compatible HC/Fit implementations, HC and Google Fit in the LSPosed scope, and the provider's usage-access and per-app permissions
- For the step hook: Pikmin must select `RECORDING_API_ON_MOBILE`

## Installation

1. Install the module APK.
2. Open the module app and configure the desired mode and compatibility options.
3. Enable it in LSPosed.
4. Add both Fitness Hooks and Pikmin Bloom to the LSPosed scope. For experimental standalone HC on Android 14+, also add Health Connect and Google Fit manually.
5. Force-stop and restart both apps, plus Health Connect and Google Fit when using the experimental standalone route. In Fit, enable HC sync for this provider and complete authorization.

The Material 3 settings screen provides quick `+/- 1`, `10`, `100`, `500`, `1000`, and `5000` adjustments. It displays an activation warning when the module app process is not injected by LSPosed. On Android 14 and later, the Android 13 SDK compatibility option is disabled and always synchronized as off.

Example LSPosed logs for Android 13 with SDK and Recording hooks enabled:

```text
FitnessHooks: target loaded; configBridgeHooks=1; sdkHooks=2; standaloneHcHooks=0; stepHooks=1
FitnessHooks: target config received; saved=true; sdk=true; standaloneHc=false; steps=true; count=6000
FitnessHooks: config loaded from target storage; sdk=true; standaloneHc=false; steps=true; count=6000
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

- The original hooks target names and signatures observed in Pikmin Bloom 153.0. The standalone-provider client route targets the HC implementation inspected in Pikmin 156.0. The hook inside the Health Connect APK checks the class and method structure without restricting the APK version. Later releases may rename, remove, or change the behavior of these methods and require a new analysis.
- A successful SDK hook does not prove that the Health Connect provider is installed, available, or authorized.
- A successful step hook proves only that Pikmin received the replacement protobuf; it does not prove server acceptance or the awarded delta.
- Settings are per Android user. Configure the module in the same user/profile that runs Pikmin.
- Changing the package name means this build does not upgrade older `com.lokey.pikminhca13` or `com.lokey0905.pikminfixhca13` builds. Disable or uninstall the old module before enabling this one to avoid duplicate hooks.
- Version 2.2.0 passed 73 automated tests and APK signature verification. The maintainer reports no issues in their current physical-device test; device, OS, and profile coverage were not specified, so this is not a full compatibility guarantee.

See [docs/STATIC_ANALYSIS.md](docs/STATIC_ANALYSIS.md) for the evidence boundary and hook rationale.
