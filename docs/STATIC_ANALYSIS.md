# Static Analysis Notes

## Scope

These notes apply to Pikmin Bloom 153.0 and describe static code findings only. They do not establish runtime feature flags, provider availability, permission state, or successful step reads on a physical device.

## Observed Gates

Pikmin's Health Connect implementation contains an Android 14 gate in `FitnessClientHealthConnect.isSupported()`. Its support decision combines the SDK level with Health Connect availability and feature status. A second operating-system decision is exposed by `FitnessManager.osSupportsHealthConnect()`.

Spoofing the global SDK level to 34 is unsafe on Android 13 because AndroidX Health Connect can then take the Android 14 framework-provider path. Keeping the true SDK value allows API 33 code to retain the standalone APK-provider path.

## Hook Effect

On SDK levels below 34, the module overrides only these two application-local Boolean gates:

```text
com.nianticproject.ichigo.fitness.FitnessClientHealthConnect.isSupported()
com.nianticproject.ichigo.fitness.FitnessManager.osSupportsHealthConnect()
```

On SDK 34 or later, the module exits before installing either hook. It does not modify provider status, feature status, permissions, source filtering, fitness-client selection, or step data.

## Evidence Boundary

The static code establishes that the two operating-system gates can be bypassed without globally changing `SDK_INT`. It does not prove that:

- the Android 13 Health Connect APK provider is installed or available;
- Pikmin has every required foreground or background permission;
- remote configuration selects Health Connect at runtime;
- Health Connect returns usable records;
- Pikmin Bloom versions other than 153.0 retain the same names and signatures.

Each Pikmin update should therefore be checked for both target methods before publishing a compatibility claim.
