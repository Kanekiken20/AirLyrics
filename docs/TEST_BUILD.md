# Side-by-side evaluation build

The debug variant is now an isolated evaluation install, requested so the existing AirLyrics app does not need an update or uninstall.

The latest candidate is `1.2.10-test-panel`, containing a passive two-line app widget. See [fixed-panel testing, checks and limitations](FIXED_LYRIC_PANEL_TEST_BUILD.md). Exact Samsung lock-screen/AOD placement and live ambient lyrics are not established by this build.

| Variant | Application ID | Launcher name | Signing |
| --- | --- | --- | --- |
| Debug/test | `com.andsi.airlyrics.sandbox` | AirLyrics Test | Locally generated Android debug key |
| Release | `com.andsi.airlyrics` | AirLyrics | Existing release signing configuration |

A different signature alone cannot install two applications with the same application ID. The debug application ID is distinct, so Android assigns separate application data, permissions, settings, notification channels and default app-specific lyrics storage. The Kotlin namespace and JNI class names remain unchanged. No new companion app or production service was added.

Build the test APK using the existing debug task. In this workspace the unchanged native engine has already been compiled:

```bash
. /workspace/.tooling/airlyrics-env.sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest -Pairlyrics.skipRustBuild=true --max-workers=2 --stacktrace
```

The artifact is `app/build/outputs/apk/debug/app-debug.apk`. Install it normally as **AirLyrics Test**. Leave the original AirLyrics installed. Grant overlay, notification access and notification-post permissions to the test app separately, select a music source, and enable its floating window. Keep default lyrics storage or choose a separate test folder: deliberately selecting the same writable external lyrics folder in both apps would share those files. No existing app folder is selected or migrated automatically.

To remove the evaluation build, uninstall **AirLyrics Test** only. Future test updates need the same test signing key. The release variant keeps its original ID and signing configuration.

The app ID, label, components, signature and packaged native engine are checked from the generated APK; physical installation alongside the user's existing app cannot be verified here because no phone is connected. The earlier APK with ID `com.andsi.airlyrics` is superseded for this side-by-side testing purpose. Do not uninstall the real app to install that earlier artifact.

## Interaction update

- Hold for **3 seconds** to adjust, rather than five.
- Push past either edge while dragging to dock immediately. No edit mode or fling velocity is required. Short drags, disabled docking and click-through retain their existing behavior.
- Opted-in lyric cards now update automatically while the screen is interactive, both before and after locking. Full text is the default; explicitly chosen compact mode remains available. Private content stays redacted unless the user chooses public visibility or system disclosure allows it.
- One delayed wake-state recheck covers keyguard settling without a recurring polling loop. Ambient frames and prediction remain disabled.

For visible lock-screen cards, enable the lyric notification, allow notification posting, choose Public lyrics if you want unauthenticated disclosure, and select detailed/card-style lock-screen notifications in Samsung settings. Android/Samsung decides the layout, size, expansion and position; the app cannot force a centred tile or merge with another app's media notification. Live Samsung AOD remains unverified.
