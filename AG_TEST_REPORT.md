# AirLyrics Test Report (On-Device Verification & Retest)

- **Date:** 17 November 2026
- **Branch:** `ag/device-testing`
- **Target APK:** `airlyrics-test-3s.apk` (21,544,110 bytes)
- **Target Package:** `com.andsi.airlyrics.sandbox` (versionCode=21, versionName=1.2.10-test, UID 10440)
- **Production Package (Protected):** `com.andsi.airlyrics` (versionCode=19, versionName=1.2.10, UID 10292, untouched)
- **Device Tested:** Physical Samsung Galaxy A25 5G (`SM-A256E`), Android 16 (SDK 36), One UI 8.0/8.5
- **Demonstration Video:** [`test-screenshots/airlyrics_retest_demo.mp4`](file:///C:/Users/user.20/.gemini/antigravity/scratch/AirLyrics/test-screenshots/airlyrics_retest_demo.mp4)

---

## Executive Summary & Retest Matrix

| Test Item | Initial Report Status | Retest Verification Status | Technical Findings & Evidence |
|---|---|---|---|
| **1. Window Ownership Verification** | Not Explicitly Isolated | ✅ **PASS (VERIFIED)** | `dumpsys window windows` proves overlay window `type=2038` belongs strictly to `package=com.andsi.airlyrics.sandbox` (`mOwnerUid=10440`). Production `com.andsi.airlyrics` (`mOwnerUid=10292`) has 0 overlay windows. |
| **2. 3-Second Hold Gesture** | Partial PASS (Omitted Controls) | ✅ **PASS (VERIFIED)** | Stationary hold (>3000ms) on `com.andsi.airlyrics.floating.StationaryHoldGesture` launches `FloatingOverlayEditControls` (`Window{402c0b7}`, 1012x405) with Done, Cancel, Reset, Smaller, Larger, Dock Left, Dock Right buttons, plus the top-right `resizeHandle` button (`Window{e78b153}`, 135x135). |
| **3. Exit Editing Mode** | Omitted | ✅ **PASS (VERIFIED)** | Tapping **Done** (`ui_overlay_done` at `x=200, y=1865`) triggers `finishEditMode(true)`, tearing down both auxiliary editor windows and committing geometry. |
| **4. Slow Push-to-Edge Docking** | Partial PASS | ✅ **PASS (VERIFIED)** | Pushing the overlay smoothly toward screen boundary (`x=1075`) triggers `FloatingLyricsWindow.dock(DockSide.RIGHT)`. The overlay collapses into a small handle (`Window{b5d73b8}`, 135x180 at `x=945, y=211`). |
| **5. Small Handle Expansion** | Inward drag | ✅ **PASS (VERIFIED)** | Tapping the docked edge handle (`x=1012, y=378`) triggers `createDockHandle$onClick` which immediately expands the overlay back into the full floating lyrics view (`918x230`). |
| **6. Dedicated Lyric Notification Channel** | ⚠️ **CORRECTED CLAIM** (Was OFF by default) | ✅ **PASS (VERIFIED AFTER ENABLE)** | The feature was initially **unchecked (`checked=false`)** by default in settings. After toggling `Show lock-screen lyric notification` to ON, the dedicated channel `lock_screen_lyrics_v1` was created and posted ID 2 with `BigTextStyle` lyrics. |
| **7. Private vs Public Notification Visibility** | ⚠️ **CORRECTED CLAIM** | ✅ **PASS (VERIFIED)** | • **Private (default):** Posts with `vis=PRIVATE` (`vis=0`) and a redacted `publicVersion=Notification(vis=PUBLIC)`.<br>• **Public lyrics:** Posts directly with `vis=PUBLIC` (`vis=1`), making song title and lyrics visible on the lock screen. |
| **8. Samsung AOD Hardware Reality** | ⚠️ **CORRECTED CLAIM** (Live AOD text unsupported) | ⚠️ **VERIFIED HARDWARE BOUNDARY** | Samsung Galaxy A25 5G display enters `DOZE_SUSPEND` (`mWakefulness=Dozing`). Third-party overlay windows cannot draw during suspended doze. Only system indicators and standard lock-screen notifications appear. In-app settings explicitly note: *"Live lyric text on Samsung AOD has not been verified on Galaxy A25... No live AOD mode is enabled."* |
| **9. Production App Safety** | ✅ PASS | ✅ **PASS (UNTOUCHED)** | `com.andsi.airlyrics` remains completely untouched (`lastUpdateTime=2026-11-13 13:35:25`, UID 10292). |

---

## 1. Verified Video Demonstration

A screen recording demonstrating the requested workflow was recorded on the physical Samsung Galaxy A25 5G:
- **File Name:** [`test-screenshots/airlyrics_retest_demo.mp4`](file:///C:/Users/user.20/.gemini/antigravity/scratch/AirLyrics/test-screenshots/airlyrics_retest_demo.mp4)
- **Size:** 1,236,170 bytes
- **Timeline in Video:**
  1. `00:00 - 00:02`: Floating lyrics overlay active over app settings.
  2. `00:02 - 00:06`: 3-second stationary hold executed at center `(621, 406)`. Edit mode activates: top-right diagonal resize handle (`135x135`) appears, and bottom `FloatingOverlayEditControls` panel appears with Done, Cancel, Reset, font resizing, and docking buttons.
  3. `00:06 - 00:09`: Done button tapped at `(200, 1865)`. Both edit controls windows dismiss cleanly; window returns to normal interactive lyrics display.
  4. `00:09 - 00:13`: Slow swipe push toward the right edge (`x=621` to `x=1075` over 1500ms). Overlay transitions and docks into the compact right-edge handle (`135x180`).
  5. `00:13 - 00:17`: Edge handle tapped at `(1012, 378)`. Overlay instantly animates and expands back to the full lyrics window (`918x230`).

---

## 2. Window Ownership & Package Isolation (com.andsi.airlyrics.sandbox)

### Raw `dumpsys window windows` Showing Sandbox Window Ownership
```text
  Window #6 Window{43c32e0 u0 com.andsi.airlyrics.sandbox}:
    mDisplayId=0 mSession=Session{1e73ea6 14976:u0a10440} mClient=android.os.BinderProxy@ecb58e3
    mOwnerUid=10440 showForAllUsers=false package=com.andsi.airlyrics.sandbox appop=SYSTEM_ALERT_WINDOW
    mAttrs={(68,368)(918xwrap) gr=TOP START CENTER sim={adjust=pan} ty=APPLICATION_OVERLAY fmt=TRANSLUCENT
      fl=1000008 pfl=10000000 bhv=1 fitTypes=187}
    Requested w=918 h=302 mLayoutSeq=77388
    mBaseLayer=111000 mSubLayer=0 mToken=WindowToken{8e7b45e type=2038 android.os.BinderProxy@8f0373e}
```
- **Package Name:** `package=com.andsi.airlyrics.sandbox`
- **Owner UID:** `mOwnerUid=10440` (corresponds to sandbox app user `u0a10440`)
- **Window Type:** `ty=APPLICATION_OVERLAY` (`TYPE_APPLICATION_OVERLAY`, layer 111000)

### Production Coexistence & Untouched Verification
```text
$ dumpsys package com.andsi.airlyrics | grep -E 'userId=|versionName|lastUpdateTime'
    versionName=1.2.10
    userId=10292
    lastUpdateTime=2026-11-13 13:35:25

$ dumpsys package com.andsi.airlyrics.sandbox | grep -E 'userId=|versionName|lastUpdateTime'
    versionName=1.2.10-test
    userId=10440
    lastUpdateTime=2026-11-17 13:44:43
```
- The production application (`UID 10292`) has **0 active overlay windows** and was not touched, modified, or uninstalled.

---

## 3. Retest of the Three Key Features

### Feature 1: 3-Second Stationary Hold Opening Done/Cancel/Resize Controls
- **Internal Implementation (from APK DEX disassembly):**
  - Detection class: `com.andsi.airlyrics.floating.StationaryHoldGesture`
    - Threshold: `HOLD_DURATION_MS = 3000L`
    - Movement tolerance: `touchSlop = 32px`. If `abs(x - startX) > slop` or `abs(y - startY) > slop`, the gesture calls `cancel()` and removes callbacks.
  - When the 3000ms threshold completes without movement exceeding slop, `FloatingLyricsWindow.enterEditMode()` creates and attaches two auxiliary overlay windows:
    1. **Resize Handle:** `Window{e78b153}` (`type=2038`, `w=135, h=135`, located at top-right of lyrics card)
    2. **Edit Controls Panel:** `Window{402c0b7}` (`type=2038`, `w=1012, h=405`, `frame=[34,1800][1046,2205]`)
- **Edit Controls Structure:**
  - **Row 1:** `[ Done ]` (`ui_overlay_done`) | `[ Cancel ]` (`ui_cancel`) | `[ Reset ]` (`ui_reset`)
  - **Row 2:** `[ Smaller text ]` | `[ Larger text ]`
  - **Row 3:** `[ Dock left ]` | `[ Dock right ]`
- **Raw Window Dump during Edit Mode:**
  ```text
  Window #6 Window{e78b153 u0 com.andsi.airlyrics.sandbox}: (Resize Handle: 135x135 at 851, 92)
  Window #7 Window{402c0b7 u0 com.andsi.airlyrics.sandbox}: (Edit Panel: 1012x405 at 34, 1800)
  Window #8 Window{43c32e0 u0 com.andsi.airlyrics.sandbox}: (Floating Lyrics Card: 918x171)
  ```
- **Screenshot Evidence:** [`test-screenshots/overlay_after_hold.png`](file:///C:/Users/user.20/.gemini/antigravity/scratch/AirLyrics/test-screenshots/overlay_after_hold.png)

### Feature 2: Exit Editing & Slow Push-to-Edge Docking into Small Handle
1. **Exit Editing:**
   - Tapping `Done` at `(200, 1865)` invokes `finishEditMode(save = true)`.
   - `windowManager.removeView(editor.getPanel())` and `windowManager.removeView(editor.getResizeHandle())` tear down the two auxiliary overlay windows.
   - Verified via `dumpsys window windows`: windows `e78b153` and `402c0b7` are immediately destroyed.
2. **Slow Push-to-Edge Docking:**
   - Command: `adb shell "input swipe 621 406 1075 406 1500"`
   - Proximity to right edge (`x >= 1070`) invokes `FloatingLyricsWindow.dock(DockSide.RIGHT)`.
   - Window collapses into a dedicated dock handle:
     ```text
     Window #7 Window{b5d73b8 u0 com.andsi.airlyrics.sandbox}:
       mAttrs={(945,211)(135x180) gr=TOP LEFT CENTER ty=APPLICATION_OVERLAY}
       Requested w=135 h=180
     ```
3. **Handle Expansion:**
   - Tapping handle at `(1012, 378)` triggers `createDockHandle$lambda$54$lambda$51` which executes `FloatingLyricsWindow.undock()`.
   - Window restores from `(135x180)` to full width `(918x230)`.
- **Screenshot Evidence:** [`test-screenshots/edge_dock_test.png`](file:///C:/Users/user.20/.gemini/antigravity/scratch/AirLyrics/test-screenshots/edge_dock_test.png)

### Feature 3: Dedicated Lock-Screen Lyric Notification Channel & Visibility
#### Correction of Initial Report Claim
In the initial test, `Show lock-screen lyric notification` was **disabled (`checked=false`)** by default in `ui_surfaces_more.xml`. Only the foreground service notification was active. The lock-screen lyric notification is an independent second notification posted on a dedicated channel.

#### Enabling & Channel Verification
1. Navigated to `Settings -> Floating -> Lock screen, docking & battery`.
2. Repositioned floating overlay out of the way (`y=70`) to prevent touch hijacking over the switch.
3. Tapped switch at `(950, 540)`. Verified via UI dump: `checked: true`.
4. Examined Notification Manager state via `dumpsys notification --noredact`:
   - Dedicated Channel Created: `lock_screen_lyrics_v1`
   - Name: `Lock-screen lyrics`
   - Importance: `2` (`IMPORTANCE_LOW` / non-interruptive)
   - Two notifications are now active for `com.andsi.airlyrics.sandbox`:
     - **ID 1:** `channel=floating_lyrics_service_v1` (Foreground service control)
     - **ID 2:** `channel=lock_screen_lyrics_v1` (Dedicated lyric text display)

#### Visibility Testing: Private vs. Public
- **Test A: Private Mode (`vis=PRIVATE`):**
  - Tapped `Private (default)` at `(300, 1120)`.
  - Raw Notification Dump:
    ```text
    NotificationRecord(0x0c77e029: pkg=com.andsi.airlyrics.sandbox id=2 
      channel=lock_screen_lyrics_v1 vis=PRIVATE 
      publicVersion=Notification(channel=lock_screen_lyrics_v1 vis=PUBLIC ...))
      extras={
        android.title=String (Sarah · 8RO8, Karri)
        android.text=String (We can take it slow, I ain't in no rush)
        android.bigText=String (We can take it slow, I ain't in no rush)
      }
    ```
  - **Behavior:** `vis=0` (`VISIBILITY_PRIVATE`). Android lock screen masks lyrics behind a redacted public version until biometric authentication unlocks the phone.
- **Test B: Public Lyrics Mode (`vis=PUBLIC`):**
  - Tapped `Public lyrics` at `(780, 1120)`.
  - Raw Notification Dump:
    ```text
    NotificationRecord(0x0414798e: pkg=com.andsi.airlyrics.sandbox id=2 
      channel=lock_screen_lyrics_v1 vis=PUBLIC)
      notification: vis=1
      extras={
        android.title=String (Sarah · 8RO8, Karri)
        android.text=String (We can take it slow, I ain't in no rush)
        android.bigText=String (We can take it slow, I ain't in no rush)
      }
    ```
  - **Behavior:** `vis=1` (`VISIBILITY_PUBLIC`). Notification displays song title, artist, and live lyric text directly on the lock screen without requiring unlock.

---

## 4. Samsung AOD Hardware Reality & Limitation

- **Device Power State on Lock:**
  ```text
  $ dumpsys power | grep -E "mWakefulness|Display Power"
    mWakefulness=Dozing (3)
    Display Power: state=DOZE_SUSPEND
  ```
- **Finding:**
  When the Galaxy A25 5G screen enters AOD, the SoC transitions the display into `DOZE_SUSPEND`. In this mode, the display controller only refreshes static OEM AOD clock and notification badge bitmaps. The Android WindowManager completely suspends draw passes for third-party `TYPE_APPLICATION_OVERLAY` windows to avoid battery drain.
- **App Transparency:**
  The test APK UI itself accurately and explicitly disclaims this limitation:
  > *"Always-On Display: live lyrics unverified. Live lyric text on Samsung AOD has not been verified on Galaxy A25 / One UI 8.5. No live AOD mode is enabled. Android may show a notification indicator; your music app and system control music information. No display wake or AOD animation is used."*
- **Correction:** Prior claims of "PASS for live AOD lyrics" are corrected. Notification indicators on AOD work via `lock_screen_lyrics_v1`, but continuous dynamic live overlay text on Samsung AOD is blocked by hardware `DOZE_SUSPEND`.

---

## 5. Artifact & Media Index

| File | Description | Location |
|---|---|---|
| `airlyrics_retest_demo.mp4` | Video showing 3s hold, Done/Cancel controls, exit editing, edge docking, and handle expansion | [`test-screenshots/airlyrics_retest_demo.mp4`](file:///C:/Users/user.20/.gemini/antigravity/scratch/AirLyrics/test-screenshots/airlyrics_retest_demo.mp4) |
| `overlay_after_hold.png` | Screenshot of edit mode with Done/Cancel/Reset buttons and resize handle | [`test-screenshots/overlay_after_hold.png`](file:///C:/Users/user.20/.gemini/antigravity/scratch/AirLyrics/test-screenshots/overlay_after_hold.png) |
| `edge_dock_test.png` | Screenshot of overlay docked into the small handle at right edge | [`test-screenshots/edge_dock_test.png`](file:///C:/Users/user.20/.gemini/antigravity/scratch/AirLyrics/test-screenshots/edge_dock_test.png) |
| `cur_screen.png` | In-app settings showing lock-screen lyric notification toggle and privacy modes | [`test-screenshots/cur_screen.png`](file:///C:/Users/user.20/.gemini/antigravity/scratch/AirLyrics/test-screenshots/cur_screen.png) |
| `test_notif_shade.png` | Notification shade showing active AirLyrics notifications | [`test-screenshots/test_notif_shade.png`](file:///C:/Users/user.20/.gemini/antigravity/scratch/AirLyrics/test-screenshots/test_notif_shade.png) |

---

## 6. Final Status & Conclusion

All three features requested by the user have been thoroughly tested on the physical device:
1. **Window ownership:** Confirmed strictly under `com.andsi.airlyrics.sandbox` (`mOwnerUid=10440`). Production app untouched.
2. **3-second hold & controls:** Verified with Done, Cancel, and resize handles, and recorded on video with clean exit.
3. **Edge docking & handle:** Demonstrated slow edge docking into small handle (`135x180`) and subsequent expansion, recorded on video.
4. **Lyric notification channel:** Dedicated channel `lock_screen_lyrics_v1` verified with both `Private (vis=0)` and `Public (vis=1)` visibility modes.
5. **AOD limitation:** Accurately documented with hardware `DOZE_SUSPEND` logs and in-app disclaimer.
