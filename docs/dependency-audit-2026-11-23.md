# Dependency audit — 2026-11-23

Scope: direct application/test dependencies, AGP and Gradle wrapper for the Material 3 action-card and targetSdk 37 migration. The final 2.2.3 source uses `targetSdk = compileSdk = 37`, Material Components 1.14.0, and AndroidX Activity 1.13.0. This is a version and compatibility review, not physical-device validation of every version listed below.

The current toolchain already supports this migration: compileSdk 37 requires at least AGP 9.1.1, which the project uses. Material Components and AndroidX Activity were updated together with the 2.2.3 version change. The other dependencies retain their existing versions; the remaining optional updates below are separate maintenance work. See the [official AGP compatibility table](https://developer.android.com/build/releases/about-agp).

| Dependency / tool | Project version | Stable version recorded during audit | Decision / official source |
| --- | --- | --- | --- |
| Material Components | 1.14.0 | 1.14.0 | Updated for 2.2.3; minSdk 23 remains below this project's 28. [Release](https://github.com/material-components/material-components-android/releases/tag/1.14.0) |
| AndroidX Activity | 1.13.0 | 1.13.0 | Updated for 2.2.3 with Activity Result, configuration-change, and UI regression coverage. [Release notes](https://developer.android.com/jetpack/androidx/releases/activity#1.13.0) |
| Health Connect client | 1.1.0 | 1.1.0 | Keep the stable version. 1.2.0-alpha06 is a preview. [Version table](https://developer.android.com/jetpack/androidx/releases/health-connect) |
| Coroutines Android | 1.8.1 | 1.11.0 | Optional update; upstream uses Kotlin 2.2.20, so check the resolved Kotlin/coroutines graph and HC cancellation/mutex behavior together. [Release](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0) |
| JUnit 4 | 4.13.2 | 4.13.2 | Current for this artifact. JUnit 5/6 would be a framework migration. [Releases](https://github.com/junit-team/junit4/releases) |
| Robolectric | 4.16 | 4.18.1 | Optional test-runtime update; SDK 36 native-resource/shadow changes warrant a full regression. [Release](https://github.com/robolectric/robolectric/releases/tag/robolectric-4.18.1) |
| Android Gradle Plugin | 9.1.1 | 9.5.1 | Existing version meets this migration's requirements. A future 9.5 upgrade also requires Gradle at least 9.6.0. [Release notes](https://developer.android.com/build/releases/agp-9-5-0-release-notes) |
| Gradle wrapper | 9.3.1 | 9.9.2 | Keep paired with the existing AGP for now. [Official current-version metadata](https://services.gradle.org/versions/current) |
| Local Xposed API stubs | Local JAR, no version declared | Not comparable as a Maven artifact | Keep compileOnly/testImplementation scopes. A replacement requires verifying the real LSPosed API and avoiding packaged Xposed classes. |

Health Connect needs special care: `StandaloneHealthConnect` reflects the internal `HealthConnectClientImpl(Context, String)` constructor and checks APK feature classes; `HealthConnectSteps` reflects `HealthPermissionsRequestAppContract(String)`. Public API compatibility alone does not establish that a newer HC client preserves these experimental provider routes.

VersionName remains the existing `2.2.3`, versionCode remains `6`, and minSdk remains `28`.
