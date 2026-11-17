# Fixed lyric panel on the lock screen and Samsung AOD

Investigation: 17 November 2026. Local implementation branch: `codex/native-lyrics-display`, checkout HEAD `e62eb31b` (documentation changes pending). This is an investigation of the existing implementation and platform capabilities, not a claim of a new working surface.

## Latest user clarification: fixed, passive panel

**Subsequent implementation:** A native fixed-widget candidate is now implemented and built; see [the panel test-build report](FIXED_LYRIC_PANEL_TEST_BUILD.md). The investigation's references to the delivered APK without a provider describe the earlier SHA-256 `ac5221f7…` build. The new `b9fe409a…` build contains a provider, but the requested Samsung positions and live AOD refresh remain unverified and unimplemented as a validated integration.

**Subsequent user-device result:** The user reports that AirLyrics Panel is not listed in the standard Samsung lock-screen picker and supplies an image of that picker. This is a failed listing check for the tested setup; it is not proof that every Samsung-hosted surface is unavailable. A separately verified home-screen listing and optional LockStar host check remain useful. Samsung's [third-party widget instructions](https://www.samsung.com/levant/mobile-phone-buying-guide/what-are-widgets/) specifically reference Good Lock / LockStar; current A25 host availability has not been established. See the test-build follow-up for the evidence boundary. No application-code change or rebuild was justified solely by these screenshots.

The desired presentation is a compact, fixed rectangular lyric panel, not the movable overlay used while unlocked. It must remain visible during the relevant playback session without tapping a notification or expanding a card.

| Surface | Requested placement and appearance |
| --- | --- |
| Interactive Samsung lock screen | Immediately above the existing music player/Now Bar near the bottom, above the camera and flashlight shortcuts. A small rectangular panel with at most two lyric lines; visual colors should coordinate with the song/player. |
| Samsung Always On Display | Immediately below the notification-icon row. A compact, fixed two-line lyric presentation with legible, restrained song-derived color. |

The panel has no drag, resize, dock, edit or playback controls. "Connected to the player" means it follows the selected real player's track, position, seek and playback changes; it does not create a second player or replace the existing media session. Song-derived colors should come from available artwork, cache per track/artwork change and retain a readable fallback. The user has not specified whether the second line should show wrapping or the next lyric; at most two visible lines is the established requirement.

These are acceptance requirements, not verified platform capabilities. A foreground notification card, an unlocked floating overlay, or a user-placed home-screen widget does not establish them. Samsung's host controls system-widget placement and AOD refresh; no public API allowing this application to anchor its view to another app's Now Bar or the notification-icon row was established by this investigation. AOD still requires a real permitted host, not an Activity that keeps the screen awake.

## Acceptance correction

The user requires the fixed lyric panels described above and explicitly rejects a lyric notification as the requested presentation. The user also reports that the panel does not appear on either surface. The notification demonstration in AG's report does not satisfy that requirement. No further tasks or messages were sent to AG during this investigation.

**The delivered APK does not implement that fixed lock-screen or live-AOD presentation.** This is a confirmed implementation gap, distinct from the question of whether a suitable Samsung-hosted integration is possible. Broader platform compatibility remains unverified; that label must not obscure the current APK's missing behavior.

## Confirmed causes in the delivered implementation

1. `FloatingServicePresentation.refreshLyricsPresentation()` calls `windowController.setPresentationVisible(screenClass == LyricsScreenClass.UNLOCKED)`. Locking deliberately detaches the normal lyric surface.
2. `LyricsPresentationPolicy` produces `LOCKSCREEN` with notification updates only, without `drawOverlay`, for a locked interactive screen. DOZE, DOZE_SUSPEND and OFF produce an ambient-indicator state without live rendering.
3. On noninteractive transitions, the coordinator stops lyric prediction and recovery callbacks. `publishLockScreenLine()` clears the dedicated lyric notification outside interactive states. Therefore even an ambient notification indicator is not guaranteed by this code path.
4. `FloatingLyricsWindow` uses `TYPE_APPLICATION_OVERLAY` and `FLAG_NOT_FOCUSABLE` (plus `FLAG_NOT_TOUCHABLE` for click-through). It is not a Samsung/SystemUI widget surface.
5. The delivered APK manifest contains no app-widget provider or lock-screen activity. There is no alternate native renderer that could receive the lyric handoff.

These findings explain why a notification test can pass while the requested lyric panel remains absent. Merely changing the notification's text, privacy or channel does not address that gap.

## Platform investigation

### Keeping the normal overlay attached is insufficient

Inspected Android 16 AOSP `WindowState.java`, `DisplayPolicy.java` and `WindowManager.java` from `android16-release`:

- `WindowState.canBeHiddenByKeyguard()` checks ordinary non-activity windows below the notification-shade layer.
- `DisplayPolicy.applyKeyguardPolicy()` hides those windows while keyguard is showing. Its show-when-locked exception additionally requires keyguard to be occluded. A small application overlay setting `FLAG_SHOW_WHEN_LOCKED` does not itself establish that occlusion.
- The public flag documentation limits `FLAG_SHOW_WHEN_LOCKED` to the topmost fullscreen window and directs applications to the Activity API.

This establishes the AOSP behavior, not a direct inspection of Samsung's proprietary modifications. Removing AirLyrics' suppression or adding a flag cannot be presented as a verified fix on the A25.

Sources: [Android 16 DisplayPolicy](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/wm/DisplayPolicy.java), [WindowState](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/wm/WindowState.java), [public window APIs](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SHOW_WHEN_LOCKED).

### A lock-screen Activity is a different surface

`Activity.setShowWhenLocked(true)` can allow an Activity to appear over keyguard. It does not embed the existing small overlay in Samsung's lock screen or make an Activity a low-power AOD surface. Automatic background launches face additional platform restrictions. No Activity, automatic keyguard occlusion, fake black-screen display or artificial wake behavior was added.

Sources: [Activity.setShowWhenLocked](https://developer.android.com/reference/android/app/Activity#setShowWhenLocked(boolean)), [background Activity launch restrictions](https://developer.android.com/guide/components/activities/background-starts).

### System-hosted widgets are a candidate, not established compatibility

Samsung's current support documentation describes selecting lock-screen widgets and displaying selected widgets on AOD. It does not establish that an arbitrary AirLyrics `AppWidgetProvider` is accepted by the A25's picker or that its text refreshes during suspended doze. Samsung's public Flex Window tutorial targets a Galaxy Z Flip cover display; it is not evidence of an A25 AOD integration.

Community code describes vendor XML fields such as `targetHost=lockAndAod`. No official API contract for those fields was located in this investigation. These examples are leads, not proof, and were not added to the app as though they were documented Samsung support. The original specification excludes reliance on undocumented vendor APIs.

A real host-supported widget could provide a lyric panel outside the notification list, with host-controlled size, placement and refresh. It would require the user to place it once through the actual host. A standard widget declaration or a successful build would not prove the requested AOD result.

Sources: [Samsung widget and AOD guide](https://www.samsung.com/us/support/answer/ANS10000945/), [Samsung lock-screen widget guide, updated September 2026](https://www.samsung.com/au/support/mobile-devices/add-widgets-to-lock-screen/), [Samsung Flex Window tutorial](https://developer.samsung.com/codelab/galaxy-z/widget-flex-window.html), [Android widget updates](https://developer.android.com/develop/ui/views/appwidgets/advanced).

### Suspended AOD requires a system-supported refresh path

Android documents that the CPU does not update the display in `STATE_DOZE_SUSPEND`. Allowing the normal renderer to keep scheduling work would not establish a visible AOD surface. A host can own its own power/display integration, but that integration has not been demonstrated for this application and firmware. No frame loop, alarm or wake lock was enabled to simulate success.

Source: [Display.STATE_DOZE_SUSPEND](https://developer.android.com/reference/android/view/Display#STATE_DOZE_SUSPEND).

## Checks actually performed

- Read and traced the presentation policy, screen monitor, floating-window flags and notification publication path.
- Inspected the delivered APK's compiled manifest with `apkanalyzer manifest print`. Package: `com.andsi.airlyrics.sandbox`, version 1.2.10-test/code 21; no widget provider or `showWhenLocked` Activity.
- Recomputed delivered APK SHA-256: `ac5221f71247ac006cef852b03d99a07f0c78a3d42535afd385025552d3f713c`.
- Rechecked `adb devices -l`: no attached devices. The user's phone is not connected to this cloud environment.
- Inspected Android 16 source and current official Android/Samsung documentation. No physical AOD test, widget-picker inspection, performance measurement or new APK build occurred during this investigation. No application code, signing, device settings or production app was changed.

## Result

Fixed lock-screen lyrics and live AOD lyrics remain **unimplemented in the delivered APK and unmet against the user's requirement**. The remaining feasibility question is whether the exact A25 firmware offers a usable system-hosted third-party surface, the requested placement and a refresh path. Existing notification footage, periodic ADB wakes and overlay ownership dumps do not answer that question. Do not ask for another notification-only retest or mark the requirement complete on that evidence.
