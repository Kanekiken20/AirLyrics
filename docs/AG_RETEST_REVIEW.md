# Review of Antigravity device retest

Reviewed external evidence from `Kanekiken20/AirLyrics`, branch `ag/device-testing`, commit `3b4498a04a1cac9fed3f1e9aedf976a24bf4b232`. Reported device: Samsung Galaxy A25 5G SM-A256E, Android 16 / SDK 36, user-reported One UI 8.5. The report header still says “8.0/8.5”; exact marketing-version evidence should be made consistent.

Sources:

- [Report at reviewed commit](https://github.com/Kanekiken20/AirLyrics/blob/3b4498a04a1cac9fed3f1e9aedf976a24bf4b232/AG_TEST_REPORT.md)
- [17.839-second video](https://github.com/Kanekiken20/AirLyrics/blob/3b4498a04a1cac9fed3f1e9aedf976a24bf4b232/test-screenshots/airlyrics_retest_demo.mp4)
- [Edit controls screenshot](https://github.com/Kanekiken20/AirLyrics/blob/3b4498a04a1cac9fed3f1e9aedf976a24bf4b232/test-screenshots/overlay_after_hold.png)
- [Dock handle screenshot](https://github.com/Kanekiken20/AirLyrics/blob/3b4498a04a1cac9fed3f1e9aedf976a24bf4b232/test-screenshots/edge_dock_test.png)

## Supported observations

| Feature | Review finding |
| --- | --- |
| Hold opens editing | Video shows the normal lyric window followed by the resize control and Done/Cancel/Reset/font/dock panel. The supplied screenshot clearly shows those controls. This closes the previous missing-controls evidence gap. The exact 3,000 ms threshold is covered by existing automated tests; the recording supports the physical workflow rather than precise touch latency. |
| Done exits editing | Controls visible around 4–6 seconds are gone by the 8-second sample. |
| Dock and expand | The recording shows a compact right-edge handle around 10–12 seconds, then expanded lyrics around 14–16 seconds. The supplied handle screenshot agrees. Reported bounds 135×180 px are consistent with the implemented 48×64 dp handle at the reported density. |
| Sandbox ownership | Supplied dumpsys excerpts identify `package=com.andsi.airlyrics.sandbox`, owner UID 10440, separate from the reported production UID 10292. Production's unchanged lastUpdateTime supports no binary update; it does not by itself audit every production data file. |
| Dedicated lyric notification | The retest corrects the disabled-toggle mistake and supplies excerpts for notification ID 2 on `lock_screen_lyrics_v1`, BigText content, PRIVATE with publicVersion, and PUBLIC visibility. This supports correct posting/configuration. |

The video was downloaded from the reviewed commit and sampled locally for visual review. No new physical-device commands were executed in this cloud environment. A static frame review is not a jank, battery or CPU measurement.

## Remaining limits and report corrections

1. **Visible lock-screen result:** The added demonstration video covers gestures, not locked-screen lyric cards. `test_notif_shade.png` shows the service/system notifications rather than the dedicated lyric card; it is not proof of public lyric visibility on the interactive lock screen. Capture that screen without unlocking and show two successive lyric boundaries; capture a private/redacted comparison. Posting `vis=PUBLIC` is distinct from proving Samsung renders the content.
2. **AOD:** DOZE_SUSPEND observations establish a power state, not the absence of every supported Samsung widget/notification surface. AirLyrics intentionally suppresses its ordinary overlay and timers in ambient states, so its disappearance cannot isolate an OEM rejection from app policy. The implemented overlay is not a live-AOD surface. Broader supported live AOD text remains **UNVERIFIED**; no validated AOD adapter is enabled. Do not upgrade that to a blanket firmware/hardware verdict from these excerpts.
3. **Build identity:** The reported 21,544,110-byte APK size matches the delivered APK. Record SHA-256 to confirm exact identity: `ac5221f71247ac006cef852b03d99a07f0c78a3d42535afd385025552d3f713c`.
4. **Trace accuracy:** The report calls the service channel `floating_lyrics_service_v1`; this tested source uses `floating_lyrics`. It also names an `undock()` method where this source uses `expand()`. Report observed behavior and actual dump fields; avoid presenting inferred method names as recorded traces. GitHub artifact links should be relative or HTTPS, not the author's `file:///C:/...` paths.
5. **Power/smoothness:** No measured CPU, wakeup, battery or frame-jank results were supplied. Functional success does not establish those performance targets.

No new implementation defect is established by this retest. The evidence now supports the editor/dock/expand workflow, with the remaining notification/AOD claims limited as above. No application code or device settings were changed during this review.
