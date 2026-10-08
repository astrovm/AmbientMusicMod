# Maintenance audit

This fork updates the open-source app and its paired [Now Playing overlay](https://github.com/astrovm/NowPlaying/pull/1). It retains the upstream proprietary ASI recognition engine. These development builds have a different certificate from the upstream releases and cannot replace them in place.

## Repairs

- Companion binder death becomes a recognition error instead of an app crash.
- Disabled recognition stops before contacting the companion.
- Missing service callbacks and recognition results have bounded waits, with binding cleanup.
- Shizuku detached-client errors become unavailable-service responses.
- Accessibility settings accept full and short component names and OEM separators.
- History cursors close on empty and corrupt records; corrupt protobufs do not crash the app.
- Nullable music-service attribution tags are accepted.
- Blur animations and resume callbacks no longer create repeating observers for every invocation.
- Setup accepts an already installed companion only when its API and signing certificate match.
- Download-status cursors close, and zero or unknown download sizes have safe progress values.

## Build and dependency maintenance

Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.20, KSP 2.3.12, compile SDK 37, JDK 17. AndroidX, Room, Koin, Hilt, protobuf/gRPC, networking, and other available maintained libraries were updated from their official Maven metadata. Debug builds no longer require a private release keystore. GitHub CI and weekly Dependabot checks are included; the Gradle distribution has a verified checksum.

The app still targets SDK 35. The resource-only on-demand overlay now targets 35. AGP's legacy DSL and separate Kotlin plugin remain enabled for compatibility with existing build plugins and must be migrated before AGP 10. Several older libraries have no newer maintained release. Lint reports retain warnings instead of suppressing them. Signed crypto-provider classes are excluded from Hilt instrumentation using the Android Gradle plugin's supported instrumentation exclusion API.

## Upstream issue review

All 10 open upstream issues were reviewed on 2026-10-07.

| Issue | Result |
| --- | --- |
| [298](https://github.com/KieronQuinn/AmbientMusicMod/issues/298), background crash | Detached Shizuku and companion binder failure regressions covered. Other crash causes are not assumed resolved. |
| [332](https://github.com/KieronQuinn/AmbientMusicMod/issues/332), accessibility trigger | Component normalization repaired and tested. Physical-device overlay behavior still requires verification. |
| [342](https://github.com/KieronQuinn/AmbientMusicMod/issues/342), on-demand recognition | Missing-result timeouts and nullable attribution repaired. Actual Google recognition and device-specific root service behavior remain unverified. |
| 339, 325, 319, 277, 273, 46 | Feature requests: live notification, widget sizing, lower gain, lock-screen liking, and Wear OS. Not implemented by this maintenance change. |
| [337](https://github.com/KieronQuinn/AmbientMusicMod/issues/337), newer Now Playing engine | Requires a separate port of proprietary ASI binaries and native components. This fork retains the existing engine. |

## Validation

Run `bash gradlew assembleDebug testDebugUnitTest lintDebug --max-workers=2` with JDK 17 and the Android SDK configured. The app has 21 behavior tests covering binder failures, timeouts, disabled recognition, accessibility formats, history corruption, null attribution, and installed-companion trust checks. Tests use synthetic data.

Local APK builds, all 21 tests, and lint passed. A matching debug pair was installed in disposable Android emulators. The companion started and returned 15,943 bundled tracks on Android 16. No physical phone was modified during this development pass. Continuous background recognition, microphone results, accessibility overlays, and complete country-database updates still require end-to-end verification. This is not a claim that every upstream issue or feature request is resolved.
