# Fixed lyric panel test build

This build adds a real, passive Android app widget with a compact two-line layout and artwork-derived colors. It is a candidate surface for a system widget host. **It does not establish Samsung lock-screen placement or live AOD support, and it does not yet deliver the user's requested system-screen integration.** No new live-AOD mode is enabled.

## Device follow-up: standard Samsung picker does not list the widget

On 17 November 2026 the user reported that AirLyrics Panel is absent and supplied screenshots of the Samsung lock-screen widget picker and interactive lock screen. The visible alphabetical list starts with Battery, Calendar, Camera, Clock and Device care, without AirLyrics Panel. The second screenshot shows the existing lyric notification above a separate media player; that is not the requested fixed panel. Neither screenshot establishes AOD rendering. The installed APK version is not visible in those images.

The standard picker route has therefore failed for the tested setup. Do not repeatedly direct the user to that same picker or infer that the presence of a standard `keyguard` declaration guarantees listing. A home-screen widget-picker check can separately establish whether the new provider is installed/registered.

Samsung's [own third-party widget guidance](https://www.samsung.com/levant/mobile-phone-buying-guide/what-are-widgets/) directs users to Good Lock and its LockStar module. This is an **optional host investigation**, not a new dependency or a compatibility claim. Check whether the current LockStar on the actual A25 offers **App widgets / Add widget** and lists AirLyrics Panel. Older walkthroughs and a store listing do not prove that option remains available on this exact firmware. If it is available, placement above the player must still be tested. If absent, report that route unavailable rather than supplying invented menu instructions or another unchanged APK.

Even a successful LockStar placement would not enable live AOD text in this build: its coordinator deliberately stops ambient lyric prediction. Host acceptance, interactive lock-screen updates, AOD visibility and dynamic AOD refresh are separate checks.

## Implemented behavior

- Same APK and selected real media source as the existing lyrics service. No replacement media player/session.
- Fixed rectangular layout, at most two visible lines, no click actions, dragging, editing, resizing or playback controls inside the panel. The system host still owns placement and its own editing UI.
- Uses the existing lyric snapshot, offset, playback-speed anchor and predicted line boundaries. No separate lyric lookup or polling clock.
- Bounded artwork palette extraction on a service-owned worker, keyed by track/artwork revision. Late metadata updates are coalesced, URI decoding is downsampled, and old-track/teardown callbacks are rejected. No remote artwork URL is fetched.
- Dark song-derived background with high-contrast text; readable neutral fallback. Provider-owned Bitmaps are never recycled by the widget; only its own downsampled decode is recycled.
- Required provider attribution has a reserved line. Attributed lyrics use one lyric line plus one credit line so bilingual text cannot hide the credit.
- Separate widget opt-in, disabled by default. It does not require enabling the lyric notification. PRIVATE always supplies a generic placeholder; HIDDEN/off clears the host view. Only PUBLIC supplies lyrics or song-derived colors, including on a home-screen host that might cache views for the lock screen.
- Privacy changes/off update the system's cached widget directly even if the lyrics service is not running.
- Stop, source loss, replacement, disable and teardown remove old lyrics. Noninteractive states remove live lyric content and show an unavailable status instead of claiming a frozen line is live AOD. No lyric prediction/recovery timers run in suspended doze.

## Try the candidate surface

1. Update **AirLyrics Test** with this APK. It keeps package `com.andsi.airlyrics.sandbox` and the existing test signing key; the production package `com.andsi.airlyrics` is separate. Version name: `1.2.10-test-panel`.
2. Select the media source and start AirLyrics' existing floating-lyrics service as before.
3. Open **Floating → Lock screen, docking & battery**. Enable **Enable fixed lyric widget** and explicitly choose **Public lyrics** if you want text on a public surface. The existing lyric-notification toggle can stay off.
4. In a system widget picker, look for **AirLyrics Panel**. A normal home-screen host can exercise its line updates and colors. This is a test of the widget implementation, not proof of the requested Samsung placement.
5. Check Samsung's actual lock-screen widget picker separately. If it does not offer the widget, this build cannot insert it there. If it does, inspect which placements the host permits. `widgetCategory=keyguard` is a request, not a guarantee.
6. The intended lock-screen position is above the real music player/Now Bar; the intended AOD position is below notification icons. Neither position has been established on the Galaxy A25 firmware. This build deliberately does not expose an AOD switch promising otherwise.

The widget has `updatePeriodMillis=0`. Adding it does not start an extra foreground service or schedule periodic jobs. Android can retain cached RemoteViews after an abrupt process kill; an ordinary widget cannot guarantee fresh lyric text when its owning process is not executing. Normal service stop and UI privacy changes are explicitly handled.

## Verification scope

Robolectric exercises real RemoteViews inflation/update, public/private/hidden policy, a two-line cap and lack of click actions, generation rejection, widget restoration, notification-independent locked-state updates, ambient timer shutdown, artwork revisions/cancellation and provider credit. These are simulated platform tests; they do not inspect Samsung's proprietary host or prove physical AOD rendering, exact placement, battery consumption or frame smoothness.

No Samsung phone is connected to this cloud environment. No new tasks or messages were sent to external AG. Internal independent review identified and checked fixes for cached-widget privacy, late artwork and attribution clipping.

## Executed checks and artifact

Command (with the workspace JDK/SDK environment loaded):

```sh
./gradlew :app:testDebugUnitTest :app:checkArchitecture :app:lintDebug :app:assembleDebug \
  -Pairlyrics.skipRustBuild=true --console=plain
```

Result: **BUILD SUCCESSFUL**. Unit-test XML: **1,078 tests, 0 failures, 0 errors, 2 optional external audits skipped** (1,076 passed). Architecture checks pass for source and JVM dependencies, including 27 checker tests. Localization checks pass. Lint has **0 errors, 33 warnings**; the five added warnings concern newer widget XML attributes with existing size/layout fallbacks, the transparent outer layout used to hide panel content, and a KTX style suggestion.

The native Rust engine was not changed and its existing compiled library was reused. No new physical-device or battery/frame measurements were possible.

- APK: `/workspace/AirLyrics-delivery/AirLyrics-Test-Fixed-Panel.apk`
- Size: **21,566,998 bytes**
- SHA-256: `b9fe409afae35e4600d9690a611dec0585b99d8db2ffc8c549f814b1771fd27b`
- Manifest verified: `com.andsi.airlyrics.sandbox`, `1.2.10-test-panel`, version code 21, no shared UID, fixed-widget provider present.
- APK v2 signature and 16-KiB alignment verified. Test certificate SHA-256: `76185488cc77e150630aca1866108e9dd8a8f8c61c31f489ef1e97a247734452`, matching the previous side-by-side test APK.
- `adb devices -l` returned no attached devices.
