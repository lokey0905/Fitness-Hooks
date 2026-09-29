# Static Analysis Notes

## Scope

These notes apply to Pikmin Bloom 153.0 and describe static code findings only. They do not establish runtime feature flags, provider availability, permission state, or successful step reads on a physical device.

## Observed Gates

Pikmin's Health Connect implementation contains an Android 14 gate in `FitnessClientHealthConnect.isSupported()`. Its support decision combines the SDK level with Health Connect availability and feature status. A second operating-system decision is exposed by `FitnessManager.osSupportsHealthConnect()`.

Spoofing the global SDK level to 34 is unsafe on Android 13 because AndroidX Health Connect can then take the Android 14 framework-provider path. Keeping the true SDK value allows API 33 code to retain the standalone APK-provider path.

## SDK Hook Effect

On SDK levels below 34, the module overrides only these two application-local Boolean gates:

```text
com.nianticproject.ichigo.fitness.FitnessClientHealthConnect.isSupported()
com.nianticproject.ichigo.fitness.FitnessManager.osSupportsHealthConnect()
```

On SDK 34 or later, the SDK-gate hooks are not installed. They do not modify provider status, feature status, permissions, source filtering, or the real SDK value.

## Recording Step Hook

Pikmin's `FitnessClientRecordingApi.queryStepCounts(int, int)` returns the final `HourlyStepCountCollectionProto` consumed by `FitnessManager`. V2.0.1 hooks this method before its original execution and, when enabled, immediately returns a configured current-hour bucket. This avoids depending on completion of the original `LocalRecordingClient.readData()` task.

The settings data-source enum uses value 3, while the result protobuf uses `HourlyStepCountCollectionProto.FitnessDataSource.RECORDING_API_ON_MOBILE`, value 7. V2 resolves the result enum by name instead of reusing the settings value.

The hook changes only Pikmin's in-process result. It does not insert records into Google Play services, Health Connect, or another Android user/profile.

Configuration is delivered through a signature-permission-protected broadcast receiver registered inside Pikmin, then persisted in Pikmin's own private preferences. The module settings screen publishes changes, and the target requests the current module settings on startup. Step queries therefore do not depend on cross-package provider visibility or LSPosed preference classes. Android isolates both the module and target copies per user/profile.

## Evidence Boundary

The static code establishes that the two operating-system gates can be bypassed without globally changing `SDK_INT`. It does not prove that:

- the Android 13 Health Connect APK provider is installed or available;
- Pikmin has every required foreground or background permission;
- remote configuration selects Health Connect at runtime;
- Health Connect returns usable records;
- Unity accepts the configured bucket as a new step delta;
- the backend accepts or awards the configured value;
- Pikmin Bloom versions other than 153.0 retain the same names and signatures.

Each Pikmin update should therefore be checked for both target methods before publishing a compatibility claim.
