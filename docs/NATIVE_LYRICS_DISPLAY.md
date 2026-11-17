# Native lyrics display

This change extends the existing Kotlin/View AirLyrics application on `codex/native-lyrics-display`. It retains the application ID, foreground service, media-session listener, provider/repository pipeline, Rust engine and saved-lyrics formats. No new Android permission, service, backend or player was added.

For exact executable results, APK details and untested cases, see [IMPLEMENTATION_VERIFICATION.md](IMPLEMENTATION_VERIFICATION.md). The Samsung capability investigation is in [AOD_CAPABILITY_REPORT.md](AOD_CAPABILITY_REPORT.md). Automated Android tests simulate platform behavior; they do not establish Samsung compatibility or physical animation quality.

## Use the overlay

1. Select the playing app on **Media**, then **Show** on **Floating**.
2. On an unlocked, touchable overlay, hold still for **5 seconds** to open adjustment controls. Moving beyond touch slop, adding a finger, canceling, locking, rotating or hiding cancels the hold. A final haptic marks entry; there is no five-second drawing loop.
3. Drag the lyric to move it. Drag the corner resize control to change the box dimensions. **Smaller text / Larger text** change font size independently. The resize control also exposes labeled accessibility actions and arrow-key adjustments.
4. **Done** saves dimensions, font size and normalized position. **Cancel** restores the edit snapshot; **Reset** restores automatic dimensions and a reachable position within the draft. Rotation or screen lock cancels an unfinished edit.
5. Use **Dock left / Dock right** in adjustment controls, or fling outward at the corresponding edge. A deliberate fling requires at least 72 dp of outward travel and 800 dp/s release velocity. Docking replaces the lyric window with a 48 × 64 dp handle. Tap or swipe inward to expand; the latest lyric is restored from the shared timeline.

Click-through continues to pass touches to the app beneath it, including while docked. Its five-second hold cannot be detected. Use the ongoing notification's **Adjustment Mode** action to enable touch and open the editor. A locked touchable overlay still prevents dragging; it can be unlocked through existing controls. Edge docking and hold-to-edit can each be disabled in the new native display controls.

Geometry uses the existing relative position keys plus a separate normalized width/height and dock preference store. The native width slider takes precedence over an earlier resize width; it preserves the independent height and font size. Existing pixel-based positions still migrate through the original geometry path. Insets, cutouts, IME space and rotation use the WindowManager safe area; physical-device validation is still required.

## Typography and settings

The Floating font selector now includes **Soft / Apple-inspired (Inter)** and **Bold / Spotify-inspired (Manrope)**. Initial selection uses weights 600 and 800 respectively; the existing weight control remains available. These are open-source alternatives, not Apple or Spotify font files. System, sans-serif, serif, monospace and imported custom fonts remain available.

Fonts are bundled and cached with Android system-script fallback. Their original OFL licenses, pinned source URLs and hashes are packaged under `assets/fonts/`. English, Spanish, Simplified Chinese and Traditional Chinese labels are included. The selector includes static Latin, Arabic, CJK, emoji and multiline samples; visual shaping/fallback still needs device review.

**Floating → Native lyrics surfaces → Lock screen, docking & battery** contains the hold, docking, lock-screen privacy and minimal-motion options. Settings update the running service. Notification text uses System UI's font, size and placement.

## Lock screen and Always-On Display

Lock-screen lyrics are **off by default**. Enable them in native display controls while using the floating service, allow notifications on Android 13+, and configure Android/Samsung lock-screen notification visibility. The privacy choices are:

- **Private** (default): native `VISIBILITY_PRIVATE` with a generic public version containing neither lyrics nor song metadata. Android's own lock-screen disclosure settings still apply.
- **Public**: explicit opt-in to display lyric/song content where System UI permits it.
- **Hidden**: native `VISIBILITY_SECRET` to exclude the notification from the lock screen.

Metadata and compact display are optional. One silent, stable-ID lyric notification updates on sentence/seek/offset changes while the lock screen is interactive. It does not impersonate the selected player's media session. Source loss, replacement, hide or service teardown removes old lyric notifications; paused lyrics freeze without prediction polling. Channel/permission suppression is handled without a retry loop. Exact display and throttling depend on System UI.

**Galaxy A25 5G / One UI 8.5 live AOD: UNVERIFIED.** No phone was connected, and no supported dynamic AOD text surface was demonstrated. There is no live-AOD toggle, fake black activity or ambient lyric renderer. The existing ongoing notification may provide a system-owned AOD indicator if Samsung displays it; even that appearance requires device verification. No private OEM API, root, accessibility workaround, screen wake or wakelock was introduced.

## Lifecycle and power policy

The existing renderer now exposes a read-only line snapshot. Playback speed and elapsed-realtime samples are carried through the existing media protocol, and parsed lyric boundaries are cached for binary successor lookup. A single service timer predicts the next boundary; canceled provider generations cannot replace newer data.

Expanded, visible playback can animate. Docked/static, hidden, paused, locked and noninteractive surfaces stop overlay frames. Minimal motion, Battery Saver and system animation settings suppress optional motion. SPICY frame callbacks settle outside active timing ranges; visible-only segment wakeups resume after gaps. Visible settings previews retain their own looping clock and stop when hidden. Interactive lock-screen updates use sentence timing, without word/frame timers. OFF/DOZE/DOZE_SUSPEND cancel live prediction, media recovery and active lookup work; the latest position is reconciled on wake.

Media-session callbacks remain primary. The existing recovery poll is reduced from one second to fifteen seconds and runs only for playing, visible interactive presentation. AirLyrics' own notification updates no longer cause media rescans. These are code/test properties, **not measured battery or CPU improvements**. No physical profiling, 30-minute playback or overnight comparison was possible.

## Build, install and rollback

The existing `./gradlew :app:assembleDebug` route remains unchanged and builds the Rust JNI engine when its prerequisites are installed. This workspace separately built the unchanged ARM64 engine with `cargo ndk ... --release --locked`, then packaged that verified library with `-Pairlyrics.skipRustBuild=true`; see the verification report for exact commands and results.

The delivered debug APK targets ARM64, including the Galaxy A25 hardware architecture. APK construction/signature verification does not prove installation or device behavior. On a connected device, install with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A debug signature cannot update an installed upstream release signed with another key. To preserve an existing installation and its private data, build with that installation's signing key. Export saved lyrics before any uninstall or data-clearing action; this task does not uninstall or modify a phone. For rollback, restore a compatible prior build signed with the same key. Older code ignores the new preference files, and existing lyrics storage was not migrated or rewritten.

## Files and review map

| Area | Files |
|---|---|
| Overlay interaction | `floating/FloatingLyricsWindow.kt`, `FloatingOverlayEditControls.kt`, `StationaryHoldGesture.kt`, `FloatingWindowGeometry.kt`; `settings/store/FloatingOverlayStore.kt` |
| Shared timeline and routing | `floating/FloatingLyricsRenderer.kt`, `FloatingLyricsTextView.kt`, `LyricsLineSnapshot.kt`, `LyricsPresentationPolicy.kt`, `LyricsScreenMonitor.kt`, `FloatingServicePresentation.kt`; existing service/media/lyrics/visibility command helpers |
| Lock-screen notification | `floating/LockScreenLyricsNotification.kt`; `core/model/LyricsDisplaySettings.kt`; `settings/store/LyricsDisplaySettingsStore.kt` |
| Native settings | `ui/pages/floating/sections/FloatingNativeDisplaySection.kt`, `FloatingPageScope.kt`, `ui/model/FloatingUiHost.kt`, `app/host/MainActivityUiHost.kt`; four locale resource sets |
| Fonts | `core/model/FloatingLyricsStyle.kt`, `settings/store/FloatingLyricsFontStore.kt`, `i18n/FloatingStyleText.kt`, appearance selector, `assets/fonts/` |
| Media protocol | `media/model/CurrentMediaInfo.kt`, `CurrentMediaReader.kt`, `CurrentMediaBroadcast.kt`, `MediaNotificationListenerService.kt` |
| Validation | New/updated floating, media, settings, UI-host and screen/timing tests; localization checker and resource-merging tests; build/device reports linked above |

The remaining work that requires a phone is actual overlay and accessibility interaction, cutout/IME/rotation rendering, native JNI playback, install/update behavior, notification disclosure, Samsung AOD capability tests and comparative power/frame profiling. Follow the device procedure in the capability report before making compatibility or performance claims.
