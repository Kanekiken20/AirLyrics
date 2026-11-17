# Native lyrics display verification

All commands below run in `/workspace/AirLyrics` unless a baseline path is stated. This report records executable checks and their limits; it does not establish physical-device compatibility.

Load the local tooling environment first:

```bash
. /workspace/.tooling/airlyrics-env.sh
```

The environment requires its proxy/CA settings for Java dependency downloads. Those settings are user-local and not part of the source patch. See [BASELINE_BUILD_REPORT.md](BASELINE_BUILD_REPORT.md) for the unchanged checkout, setup, initial environment failures and native build evidence.

## Source and tooling checks

| Command | Result |
|---|---|
| `python3 -B -m unittest discover -s scripts/localization -p 'test_*.py'` | PASS: 19 tests |
| `python3 -B -m unittest discover -s scripts/architecture -p 'test_*.py'` | PASS: 27 tests |
| `python3 -B -m unittest discover -s scripts/test_reports -p 'test_*.py'` | PASS: 2 tests |
| `python3 -B -m unittest discover -s scripts/lyrics_benchmark -p 'test_*.py'` | PASS: 6 tests |
| `bash scripts/check_localization.sh` | PASS |
| `./gradlew :app:checkArchitecture -Pairlyrics.skipRustBuild=true --max-workers=2 --stacktrace` | PASS: source + compiled JVM dependency boundaries, including 27 checker tests |
| `git diff --cached --check` | Application, test and documentation changes pass. Verbatim vendored font notices retain two upstream trailing spaces and one final blank line; see below. |

## Android/JVM validation history

- Initial modified `:app:compileDebugKotlin -Pairlyrics.skipRustBuild=true --max-workers=2 --stacktrace`: PASS.
- First modified full JVM attempt: test compilation failed in the new media notification test's platform constructor. The test owner corrected it to use public API signatures.
- A later focused compile caught one missing import for the service extension used by `ServiceLocaleTest`; the test import was corrected.
- First executing full modified suite: **1,037 tests, 7 failures, 2 skipped**. Failures covered renderer line-animation setup, pending lifecycle callback expectations, disappearing-session fallback timing, deferred docked ViewRoot teardown and localized status continuity. The failures were handed to implementation owners. This initial run is not reported as a pass.

Unchanged baseline full JVM suite: PASS, 154 suites / 983 tests / 0 failures / 0 errors / 2 skipped optional external Apple audits (BUILD SUCCESSFUL in 1m52s). Therefore the seven modified failures were not present on baseline. Corrected suite, lint and final packaging results are appended below when complete.

Initial lint run failed inside lint's Kotlin FIR analysis (`No fir element was found for KtProperty`) on `FloatingPresentationLifecycleTest.kt`, which was being added during analysis. No lint check was disabled. A stable-source rerun is required before claiming lint success.

## Native core and packaging

The unchanged Rust engine built successfully in the baseline worktree with:

```bash
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release --locked
```

The result was verified as an ARM aarch64 Android ELF shared library and reused in the implementation worktree. APK commands use `-Pairlyrics.skipRustBuild=true` to package this already built engine. This does not skip native packaging. The APK contains `lib/arm64-v8a/libairlyrics_lyrics.so` plus both bundled fonts and their OFL notices. The Rust engine and JNI/data contract were unchanged; Rust unit tests were not rerun. Native execution still needs hardware.

Initial debug APK assembly and `apksigner verify --verbose --print-certs` passed. That artifact preceded final correctness fixes and is not the final source-verified APK. Final artifact details are recorded below after rebuilding current sources.

## Device, privacy, animation and power limits

`adb devices -l` is empty. No physical Galaxy A25 5G, One UI 8.5 firmware or usable emulator/KVM was available. Instrumentation, actual overlay gestures, rotation, TalkBack, IME/cutout bounds, font fallback rendering, lock-screen notification layout/privacy and Samsung AOD behavior remain **UNVERIFIED on hardware**. Robolectric assertions cover simulated behavior only.

No app CPU, wakeup, wake-lock, memory, frame-jank or battery comparison was measured. No 30-minute playback or overnight locked/AOD experiment was possible. Source/test evidence of disabled callbacks is not a battery-drain measurement.

Live Samsung AOD text: **UNVERIFIED** for the requested device/firmware. The implementation has no validated live-AOD adapter or toggle. It uses public notification fallback, with system-owned presentation and privacy. The fallback indicator's actual AOD visibility also requires the phone. See [AOD_CAPABILITY_REPORT.md](AOD_CAPABILITY_REPORT.md) for authoritative sources and physical verification steps.

## Final JVM and instrumentation compilation results

```bash
./gradlew :app:testDebugUnitTest -Pairlyrics.skipRustBuild=true --max-workers=2 --stacktrace
```

**PASS: BUILD SUCCESSFUL in 1m30s.** XML: **166 suites, 1,052 tests, 0 failures, 0 errors, 2 skipped**. The two skips are the same optional external Apple payload audits as baseline. This run includes every new/modified test, including API 26/28/30/35 geometry, API 28/35 overlay and screen monitor cases, shared timeline boundaries/gaps, paused seeks, lifecycle cancellation, hidden/ambient policy, notification privacy/permission gates, font/store compatibility and native settings controls. These are JVM/Robolectric results, not physical Android execution.

Before the full run, the corrected remaining regression classes passed all 31 cases. Fixture corrections preserved real assertions: tests now account for deferred ViewRoot traversal, seed playing state when expecting motion, flush queued service initialization before injecting media, and assert recovery eligibility/callback scheduling. Additional tests check paused-state idle behavior and destruction while playing.

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Pairlyrics.skipRustBuild=true --max-workers=2 --stacktrace
```

**PASS: BUILD SUCCESSFUL in 31s.** Existing instrumentation sources compile and their test APK packages successfully. **Instrumentation was not executed** because no device/emulator was available. This test-only APK is not a companion application or part of the product install.

## Final lint, architecture and app artifact

```bash
./gradlew :app:lintDebug -Pairlyrics.skipRustBuild=true --max-workers=2 --stacktrace
./gradlew :app:assembleDebug :app:checkArchitecture -Pairlyrics.skipRustBuild=true --max-workers=2 --stacktrace
```

**PASS:** stable-source lint completed in 1m32s with **0 errors and 28 warnings**. It produced `app/build/reports/lint-results-debug.{html,xml,sarif,txt}`. Warnings include dependency-update suggestions, unused resources, KTX suggestions, two clickable-view accessibility suggestions and physical left-edge coordinates. `Gravity.LEFT` intentionally represents physical WindowManager coordinates, so substituting locale-relative START would change positioning. The dock handle delegates taps to `performClick`; the resize control exposes keyboard and explicit TalkBack resize actions. These facts explain the warnings but do not establish physical TalkBack usability, which remains unverified. The earlier FIR crash did not recur after source/test files stopped changing.

**PASS:** final app assembly and source/JVM architecture boundaries completed in 8s, including the checker's 27 tests.

After staging newly added assets, the full whitespace check reported one upstream trailing space in each verbatim OFL license and a final blank line in `fonts/NOTICE.txt`. Those notices match the tested APK and have been preserved. `git diff --cached --check -- . ':(exclude)app/src/main/assets/fonts/*-OFL.txt' ':(exclude)app/src/main/assets/fonts/NOTICE.txt'` passes for the remaining source, tests and documentation; no application lint checks were disabled.

App artifact: `app/build/outputs/apk/debug/app-debug.apk` (21,736,981 bytes). Current tested-source SHA-256:

```text
952a9862b78d20e910754c2d7d872ba1f2d50735ad07c715c02da0f8938ffb4e
```

`apksigner verify --verbose` passes APK Signature Scheme v2 with the generated Android debug signer. `aapt dump badging` verifies the single production package `com.andsi.airlyrics`, version 1.2.10 / 21, min SDK 26, target SDK 36, compile SDK 37 and only arm64-v8a native code. `zipalign -c -P 16 4` passes; all native ELF LOAD segments use `0x4000` (16 KB) alignment. This is package/signature/alignment verification, not installation or device runtime verification.

The APK uses a locally generated debug key, not the upstream release key. Updating an installation signed by another key requires a matching signing build or a user-managed backup/reinstall; no device was modified here.

Raw successful build/test/lint/native logs are preserved in the ignored generated directory `app/build/reports/native-display-verification/`. Full JVM HTML/XML results remain under `app/build/reports/tests/testDebugUnitTest/` and `app/build/test-results/testDebugUnitTest/`.

Production-input linkage: the final `assembleDebug` validated current Gradle inputs after the successful suite; no implementation changes followed. A sorted SHA-256 manifest of production Android/Rust/build inputs (including the reused native library) is preserved at `app/build/reports/native-display-verification/production-inputs.sha256`. Manifest digest: `7344f69735b6a0928771d41cc70fbc9db4c5293ae93ee3c1da8ff2b57a78a20b`. Documentation-only commits do not alter these inputs.
