# Isolated test build update verification

This update supersedes the original same-ID evaluation APK. The current debug build is `com.andsi.airlyrics.sandbox`, launcher label **AirLyrics Test**, version `1.2.10-test`, using the generated Android debug signer. The generated release manifest still uses `com.andsi.airlyrics`, label `@string/app_name` and version `1.2.10`.

The update includes the requested three-second hold, push-to-edge docking without edit mode/flinging, automatic opted-in lyric cards before and after locking, expanded notification text by default and a bounded keyguard wake recheck. Existing explicit compact/privacy preferences remain honored. No fake AOD or media-session impersonation was added.

Verification commands used the local tooling environment and already compiled, unchanged Rust engine (`-Pairlyrics.skipRustBuild=true`).

```bash
./gradlew :app:testDebugUnitTest --tests '*FloatingOverlayInteractionTest' --tests '*StationaryHoldGestureTest' --tests '*FloatingPresentationLifecycleTest' --tests '*LyricsScreenMonitorTest' --tests '*LyricsPresentationPolicyTest' --tests '*LockScreenLyricsNotificationTest' --tests '*LyricsDisplaySettingsStoreTest' --tests '*FloatingLyricsRotationTest' --tests '*ServiceLocaleTest' :app:assembleDebug :app:assembleDebugAndroidTest :app:processReleaseMainManifest -Pairlyrics.skipRustBuild=true --max-workers=2 --stacktrace
```

PASS: focused cases, app assembly, instrumentation APK compilation and release manifest generation (56 seconds). Existing pre-33 rotation/locale fixtures were corrected to grant the generated permission for the active application ID instead of hardcoding the production ID. The instrumentation document-provider helper also targets the variant's `BuildConfig.APPLICATION_ID`, protecting the production app from future sandbox test commands.

`aapt dump badging` verifies the test package, version and launcher label. `apksigner verify --verbose` passes APK v2 signing. `zipalign -c -P 16 4` passes. Release manifest inspection verifies its original ID and label. `bash scripts/check_localization.sh` and `git diff --check` pass for this increment.

No phone is connected: physical installation, interaction smoothness, Samsung lock-screen card placement, live AOD, battery and CPU remain device-unverified. The static/default data paths are app-scoped; do not select the production app's writable external lyrics folder when testing. No existing phone installation was updated or uninstalled here.

Final full checks:

```bash
./gradlew :app:testDebugUnitTest :app:checkArchitecture :app:lintDebug -Pairlyrics.skipRustBuild=true --max-workers=2 --stacktrace
```

PASS (4m37s): 1,061 tests, zero failures/errors, two optional external Apple audits skipped. Lint: zero errors, 28 warnings. Architecture source/JVM boundaries and its 27 checker tests pass. A legacy flag/drag test now disables docking in its fixture so it tests dragging independently of the newly requested edge-push behavior; dedicated edge tests cover slow movement, both edges, RTL and disabled docking.

APK SHA-256: `ac5221f71247ac006cef852b03d99a07f0c78a3d42535afd385025552d3f713c`. The uploaded copy was downloaded and matched this checksum. No device runtime or performance measurement is claimed.
