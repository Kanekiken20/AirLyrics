# Lock screen and Samsung AOD capability report

Investigation date: 2026-11-17. Reference checkout: `730b030848d279f63f0134be9ee6a58acb536500`.

## Verdict and evidence boundary

**Live AOD lyrics on Samsung Galaxy A25 5G / One UI 8.5: UNVERIFIED.** No target phone is connected to this execution environment. Its model, firmware, AOD configuration, widget picker, notification appearance, and text refresh behavior have not been inspected. No Samsung screenshot, recording, battery measurement, or successful device test is claimed.

| Surface | Public API feasibility | A25 / One UI 8.5 result |
| --- | --- | --- |
| Native floating window while unlocked | Existing `TYPE_APPLICATION_OVERLAY` implementation | UNVERIFIED on target hardware |
| Standard lyric notification on interactive lock screen | Supported Android notification mechanism; permissions, channel privacy and host layout apply | UNVERIFIED on target hardware |
| AOD notification indicator | System may show an icon according to its settings | UNVERIFIED; no text guarantee |
| Third-party widget on Samsung lock screen | Requires an actual compatible host/picker | UNVERIFIED; ordinary home-screen widget support is insufficient |
| Live third-party lyric text on AOD | No validated surface for this app/device/firmware | UNVERIFIED; no live AOD mode should be enabled or advertised |

Initial environment inspection found no `adb`, `emulator`, or `sdkmanager` on PATH and no `/dev/bus/usb`. Installing SDK tools can enable builds; it does not supply Samsung hardware. Use the final build/test report for subsequently installed tooling and commands actually run.

After SDK provisioning, `/workspace/.tooling/android/platform-tools/adb devices -l` was run and returned `List of devices attached` followed by an empty list. Neither `/dev/kvm` nor `/dev/bus/usb` exists. This confirms that the Samsung device experiment remains unavailable after build tooling installation.

## Authoritative API findings

- Android application overlays are below critical system windows and can be repositioned, resized or hidden by the system. Overlay permission does not establish an AOD surface. [WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY).
- `STATE_DOZE` is for low-power system content. In `STATE_DOZE_SUSPEND` and `STATE_ON_SUSPEND`, the CPU is not updating the display. These states must not run an app frame loop. [Display states](https://developer.android.com/reference/android/view/Display#STATE_DOZE_SUSPEND).
- Doze defers network and background execution. Avoid exact alarms, wake locks, optimization exemptions and artificial display wakeups for lyric presentation. [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby).
- Notification visibility is controlled jointly by the app and user. Use an explicit public opt-in; a private notification should provide a generic public version without song or lyric text. Updates reuse one ID and may be throttled. [Notification privacy and updates](https://developer.android.com/develop/ui/compose/notifications/create-notification#lockscreenNotification).
- Android 13+ notification permission applies to non-exempt lyric notifications. Starting a foreground service does not grant permission to display its notifications. AirLyrics is not the playing media app and should not claim the media-session exemption. [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).
- Widgets can receive event-triggered updates, but XML periodic updates cannot run below 30 minutes. A keyguard category is not evidence that a Samsung host accepts the widget or refreshes it in AOD. [Widget updates](https://developer.android.com/develop/ui/views/appwidgets/advanced#update-periodically).
- Samsung describes selecting available lock-screen widgets and displaying configured widgets on AOD. Its generic support page does not establish that arbitrary third-party widgets are accepted or continuously refreshed on this A25 firmware. [Samsung widget guide](https://www.samsung.com/us/support/answer/ANS10000945/).
- Samsung documents Now Bar for selected ongoing activities and active media players, with device-dependent availability. This is not an API contract allowing a separate lyrics utility to replace another player's media presentation. [Samsung Now Bar guide](https://www.samsung.com/us/support/answer/ANS10004605/).
- Android now provides public Live Update notification APIs, with eligibility requirements and additional OEM criteria. They do not guarantee AOD placement or Samsung Now Bar promotion. No appropriate, verified Samsung lyric-utility integration was found in the official documentation consulted. This is a research result, not proof that every firmware rejects it. [Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update).

## Safe shipping behavior

Use one existing media observer/repository and cached lyric timeline. Present native overlay content only while unlocked and interactive. On the interactive lock screen, an opt-in silent notification may update at line boundaries. Stop line prediction and animation callbacks in DOZE, DOZE_SUSPEND, ON_SUSPEND and OFF; a generic system-owned notification indicator is the ambient fallback. Refresh from current estimated playback when interactive again. Never replay old notifications or refetch solely because the display changed.

Keep the service-control notification and its adjustment route usable. Keep lock-screen lyric settings disabled by default and public content a separate explicit opt-in. Suppress lyric posting when notification permission/channel access is denied. Clear stale content on source loss, unavailable lyrics, user disable and service teardown. No fake black-screen activity, media-session impersonation, private vendor APIs, root, accessibility workaround, permanent wake lock or `FLAG_KEEP_SCREEN_ON` is justified.

Recommended user-visible wording: “Live AOD lyrics are unverified on this device. AirLyrics does not render live text while the display is off or dozing. Your system may show a notification indicator.” Do not label an icon or frozen notification as live lyric text.

## Reproducible target-device verification (not run here)

1. Connect the user's authorized phone with USB debugging enabled. Record these commands' actual outputs in a local test record; redact identifying serial numbers before sharing:

   ```sh
   adb devices -l
   adb shell getprop ro.product.manufacturer
   adb shell getprop ro.product.model
   adb shell getprop ro.build.version.release
   adb shell getprop ro.build.version.sdk
   adb shell getprop ro.build.display.id
   adb shell getprop ro.build.version.oneui
   adb shell dumpsys display
   adb shell dumpsys power
   ```

2. Record Settings → About phone → Software information, since a vendor property alone does not prove the marketing version. Record AOD mode (always/tap/scheduled/new notifications), lock-screen notification privacy, power saver, and whether Good Lock/LockStar is available. LockStar must remain optional.
3. Install the produced APK using `adb install -r <apk>`. A debug build can only update an existing installation with a matching signing key; otherwise use a separate test device/profile or explicitly back up user data before any uninstall.
4. Grant the existing overlay, notification access and notification-post permissions through normal settings. Start playback in an external player with known synchronized lyrics. Enable the lyric notification and explicit public visibility for the visibility test.
5. Observe interactive lock screen: collapsed and expanded line content, track change, pause/resume, forward/backward seeks, source loss, and stop. Verify one notification ID, no sound/vibration/blink, current line on wake, preserved service controls, and no old track after a source change. Then select private/hidden and ensure the public surface contains neither lyric nor metadata. Repeat with notification posting and channel permission denied.
6. Inspect Samsung's actual lock-screen widget picker. Record whether any ordinary third-party widget is hostable. Only if a useful native lyric widget is implemented and accepted, check its AOD placement and text refresh separately. The presence of a widget in a home-screen picker or `dumpsys appwidget` is not visible AOD proof.
7. Test AOD always/tap/new-notification modes individually. Use an external camera: `screencap` can omit ambient composition or perturb the test. Compare physical text changes with lyric boundaries and display-state logs. Record icon, frozen line, dynamic line and no content as different outcomes. Do not wake the display to force refresh.
8. Collect equivalent original-build and modified-build sessions: at least 30 minutes playing/unlocked, locked interactive, AOD, paused and no-media idle. Keep brightness, temperature, audio, network and battery settings comparable. Record `adb shell dumpsys batterystats`, `adb shell dumpsys meminfo com.andsi.airlyrics`, `adb shell dumpsys gfxinfo com.andsi.airlyrics`, and Perfetto scheduling/frame traces. Do not infer battery savings from compilation or callback structure.
9. Use Android's documented Doze test commands on a test session, restore their state afterwards, and verify no overlay/line frame work while noninteractive:

   ```sh
   adb shell dumpsys battery unplug
   adb shell dumpsys deviceidle force-idle
   # Observe and collect logs while idle; no artificial display wake.
   adb shell dumpsys deviceidle unforce
   adb shell dumpsys battery reset
   ```

Mark live AOD `SUPPORTED` only with a documented or genuinely user-allowed surface plus captured visible updates on the exact firmware, without prohibited workarounds. Mark `UNSUPPORTED` only after testing establishes the absence of an applicable supported surface. Until then retain `UNVERIFIED` and ship the conservative fallback.
