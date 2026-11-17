# Build environment and baseline

Baseline: `730b030848d279f63f0134be9ee6a58acb536500` (`prepare v1.2.10`), equal to the supplied engineering snapshot. The repository was absent initially and was cloned from `https://github.com/AirLyrics/AirLyrics`. Implementation branch: `codex/native-lyrics-display`. Baseline worktree: `/workspace/AirLyrics-baseline` (detached at that commit). No pre-existing user changes or `AGENTS.md` were found.

## Configuration inspected

- `app/build.gradle.kts`: one `com.andsi.airlyrics` app, min SDK 26, target SDK 36, compile SDK 37; NDK 26.3.11579264; Java/Kotlin JVM target 17; arm64-v8a by default, x86_64 opt-in.
- `gradle/libs.versions.toml`: Android Gradle Plugin 9.5.1, Robolectric 4.17, JUnit 4.13.2. Kotlin is supplied by AGP's built-in integration.
- `gradle/wrapper/gradle-wrapper.properties`: Gradle 9.8.2 with pinned SHA-256.
- `lyrics-core/Cargo.toml`: Rust edition 2021, minimum Rust 1.88.0; JNI `cdylib`; vendored OpenSSL. Existing `buildRustLyrics` uses cargo-ndk, release/locked dependencies and reproducible path mapping.
- `.github/workflows/{pr-checks,full-android-rust-build,android-instrumentation,rust-checks}.yml` and `scripts/ci/install_android_packages.sh`: existing checks and build routes.

## Provisioning

The host initially had OpenJDK 21 JRE, `git`, C compiler, Make, Perl and Python, but no JDK compiler, Gradle installation, Android SDK/NDK, Rust or adb. Local tools were installed without changing project build settings:

- Temurin JDK 21.0.13 at `/workspace/.tooling/jdk`.
- Gradle 9.8.2 at `/workspace/.tooling/gradle-9.8.2`; distribution verified against the repository's SHA-256 `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607` and reused by the wrapper cache.
- Android command-line tools, SDK platform `android-37.0`, build tools 36.0.0 and platform tools 37.0.1 at `/workspace/.tooling/android`. Current SDK package naming uses `platforms;android-37.0`; `platforms;android-37` did not resolve.
- Android NDK 26.3.11579264.
- Rust 1.100.0 minimal toolchain (satisfies existing minimum), `aarch64-linux-android` standard library, cargo-ndk 4.1.2.

Ignored `local.properties` contains only `sdk.dir=/workspace/.tooling/android`. The environment requires its HTTP proxy and CA trust for Java downloads; these were configured in user-level Gradle settings and the local JDK trust store. The first wrapper attempt failed on a direct network connection; the first local Gradle attempt failed dependency resolution before proxy CA trust was loaded. Those were environment failures, not source test failures.

## Baseline checks

Commands run in the unchanged baseline worktree:

| Command | Result |
|---|---|
| `python3 -B -m unittest discover -s scripts/localization -p 'test_*.py'` | PASS: 15 tests |
| `JAVA_HOME=/workspace/.tooling/jdk python3 -B -m unittest discover -s scripts/architecture -p 'test_*.py'` | PASS: 27 tests (initial run without JDK failed setup; corrected environment passed) |
| `python3 -B -m unittest discover -s scripts/test_reports -p 'test_*.py'` | PASS: 2 tests |
| `python3 -B -m unittest discover -s scripts/lyrics_benchmark -p 'test_*.py'` | PASS: 6 tests |
| `bash scripts/check_localization.sh` | PASS |

Native ARM64 core: PASS. From `lyrics-core`, with the tooling environment loaded:

```bash
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release --locked
```

Finished successfully in 3m55s using Rust 1.100.0 and NDK r26d. `file` identifies the output as an ELF 64-bit ARM aarch64 shared object for Android. SHA-256 of the unstripped baseline `.so`: `96d9e807cf131f31614b3e71762f54f123eb3a7a4f12273508f0cd5ab7fa34c3`. Since Rust sources are unchanged, this verified library was copied to the implementation worktree's ignored JNI output directory for packaging. Intermediate Cargo outputs and the provisioned Rust toolchain were removed after the successful native build to preserve disk space for Robolectric/lint/APK checks; the verified `.so`, cargo-ndk binary, SDK/NDK and build logs remain. Reinstall a Rust toolchain and the `aarch64-linux-android` target before requesting another native rebuild. Native runtime/JNI execution is unverified without a device.

Baseline Android/JVM compilation passed, including main and test Kotlin. The initial full test execution could not load Robolectric's SDK 35 artifact because the forked test JVM did not inherit Gradle's proxy settings; repeated `java.net.ConnectException: Connection refused` errors were recorded, then the failing worker was stopped. This was not a successful baseline full-suite run. A user-local Gradle init script now supplies the proxy to test JVMs, and SDK 26/28/30/32/33/35 instrumented JARs were fetched to Maven's local cache with SHA-512 verification. No repository test/build configuration was altered for this environment fix.

Initial modified Kotlin compile: PASS (`./gradlew :app:compileDebugKotlin -Pairlyrics.skipRustBuild=true --max-workers=2 --stacktrace`), BUILD SUCCESSFUL in 31s. Full modified JVM suite, lint and APK results are recorded separately after completion.

## Hardware and measurement limits

`/workspace/.tooling/android/platform-tools/adb devices -l` returned an empty device list. `/dev/kvm` and USB device access were absent. No emulator or physical Galaxy A25/One UI 8.5 was available. Instrumentation, installation/update, screenshot, frame jank, CPU, memory, wakeup and battery tests have therefore not been performed. No performance figures are claimed.

## Completed baseline JVM suite

After proxy setup and verified test SDK caching, the unchanged baseline completed successfully:

```bash
. /workspace/.tooling/airlyrics-env.sh
cd /workspace/AirLyrics-baseline
./gradlew :app:testDebugUnitTest -Pairlyrics.skipRustBuild=true --max-workers=2 --stacktrace
```

**BUILD SUCCESSFUL in 1m52s.** XML: 154 suites, 983 tests, 0 failures, 0 errors, 2 skipped. Both skipped cases are pre-existing optional `AppleLyricsAuditTest` audits requiring external `AIRLYRICS_AUDIT_DIR` or `AIRLYRICS_AUDIT_INPUT`; no audit payload was supplied. The repository's report helper with `--strict` returns nonzero for these assumptions, so its strict exit status is not a source failure. Gradle and XML results were checked directly.

The initial modified suite's seven failures do not occur on this baseline. See [IMPLEMENTATION_VERIFICATION.md](IMPLEMENTATION_VERIFICATION.md) for their resolution and final results.
