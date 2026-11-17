# AirLyrics Test Report (On-Device Final Verification)

- **Date:** 17 November 2026
- **Branch:** `ag/device-testing`
- **Tested APK:** `airlyrics-test-3s.apk` (21,544,110 bytes)
- **APK SHA-256:** `AC5221F71247AC006CEF852B03D99A07F0C78A3D42535AFD385025552D3F713C`
- **Target Package (Sandbox):** `com.andsi.airlyrics.sandbox` (versionCode=21, versionName=1.2.10-test, UID 10440)
- **Production Package (Protected):** `com.andsi.airlyrics` (versionCode=19, versionName=1.2.10, UID 10292, untouched)
- **Device Tested:** Physical Samsung Galaxy A25 5G (`SM-A256E`), Android 16 (SDK 36), One UI 8.0/8.5

---

## Executive Summary & Verification Matrix

| Test Item | Verification Status | Technical Findings & Evidence |
|---|---|---|
| **1. Tested APK Hash & Identity** | ✅ **PASS (VERIFIED)** | SHA-256: `AC5221F71247AC006CEF852B03D99A07F0C78A3D42535AFD385025552D3F713C`. File: `airlyrics-test-3s.apk`. Package: `com.andsi.airlyrics.sandbox` (`UID 10440`). |
| **2. Production App Safety** | ✅ **PASS (UNTOUCHED)** | Production `com.andsi.airlyrics` (`UID 10292`, `lastUpdateTime=2026-11-13 13:35:25`) was never uninstalled, overwritten, or modified. Coexists independently with sandbox app. |
| **3. Overlay Window Ownership** | ✅ **PASS (VERIFIED)** | `dumpsys window windows` confirms all floating windows (`type=2038`) belong exclusively to `com.andsi.airlyrics.sandbox` (`mOwnerUid=10440`). Production app has 0 overlay windows. |
| **4. 3-Second Hold & Editing Controls** | ✅ **PASS (VERIFIED)** | Stationary hold (>3000ms) on `StationaryHoldGesture` launches `FloatingOverlayEditControls` (`Window{402c0b7}`, 1012x405) with Done, Cancel, Reset, font resizing, and docking buttons, plus resize handle (`Window{e78b153}`, 135x135). |
| **5. Exit Editing Mode** | ✅ **PASS (VERIFIED)** | Tapping **Done** (`ui_overlay_done` at `x=200, y=1865`) dismisses auxiliary editor windows cleanly and commits geometry without side effects. |
| **6. Slow Edge Docking & Handle** | ✅ **PASS (VERIFIED)** | Smooth swipe push to right boundary (`x=1075`) docks the overlay into a compact handle (`Window{b5d73b8}`, 135x180 at `x=945, y=211`). Tapping the handle restores the full lyrics window (`918x230`). |
| **7. Lock-Screen Public Lyrics (Live Update)** | ✅ **PASS (VERIFIED)** | Dedicated channel `lock_screen_lyrics_v1` with Public lyrics displays song title, artist, and live lyrics directly on Samsung One UI lock-screen card while locked and awake. 26-second video captures 3 continuous lyric line changes without unlocking, opening AirLyrics, or pulling down the notification shade. |
| **8. Lock-Screen Private Lyrics (Redaction)** | ✅ **PASS (VERIFIED)** | Setting visibility to `Private (default)` posts with `vis=PRIVATE` (`vis=0`) and a redacted `publicVersion=Notification(vis=PUBLIC)`. On the locked device, lyrics and track info are completely redacted/hidden from the lock screen. Previous privacy choice (`Public lyrics`) restored afterward. |
| **9. Dedicated Notification Dump (ID 2)** | ✅ **PASS (VERIFIED)** | Raw notification record for ID 2 on channel `lock_screen_lyrics_v1` extracted and archived: [test-screenshots/notification_id2_dump.txt](test-screenshots/notification_id2_dump.txt). |
| **10. Samsung AOD Dynamic Lyrics** | ❓ **UNVERIFIED** | Galaxy A25 5G display enters `DOZE_SUSPEND` (`mWakefulness=Dozing`), preventing third-party overlay windows. However, Doze alone does not prove every supported Samsung surface is unavailable; feature remains marked **UNVERIFIED**. |

---

## 1. APK Verification & Hash Details

- **File Name:** `airlyrics-test-3s.apk`
- **File Size:** 21,544,110 bytes
- **SHA-256 Hash:**
  ```text
  AC5221F71247AC006CEF852B03D99A07F0C78A3D42535AFD385025552D3F713C
  ```
- **Verification Command:**
  ```powershell
  Get-FileHash .\airlyrics-test-3s.apk -Algorithm SHA256
  ```
- **Package Manifest Information:**
  - Package Name: `com.andsi.airlyrics.sandbox`
  - Version Code: `21`
  - Version Name: `1.2.10-test`
  - Assigned Device UID: `10440` (`u0_a440`)

---

## 2. Lock-Screen Public Lyrics Verification (Final Verification Pass 1)

### Test Execution Details
1. Navigated to `Settings -> Floating -> Lock screen, docking & battery`.
2. Toggled `Show lock-screen lyric notification` to **ON**.
3. Selected **`Public lyrics`** visibility.
4. Locked the physical device via `input keyevent 26` and allowed the system lock-screen timeout to engage (`lock_screen_lock_after_timeout = 5000ms`, waited >6.5s) ensuring `isKeyguardShowing=true` and `mCurrentFocus=Window{... NotificationShade}`.
5. Kept the phone screen awake continuously using periodic `input keyevent 224` wake events without unlocking, without opening AirLyrics, and without pulling down the notification shade.
6. Recorded a 26-second on-device video capturing live lyric synchronization.

### Demonstration Video & Visual Evidence
- **Primary Video Evidence:** [test-screenshots/airlyrics_lockscreen_public_lyrics.mp4](test-screenshots/airlyrics_lockscreen_public_lyrics.mp4) (6,253,791 bytes)
- **Track Playing:** *Summer In Manila (Oakland Pt.3)* · Karri
- **Observed Live Lyric-Line Changes on Lock-Screen Notification Card:**
  - **Line 1:** `They all stop and stare 'cause you light up the room (you light up, you light up)`
    - Screenshot: [test-screenshots/lock_public_change1.png](test-screenshots/lock_public_change1.png)
  - **Line 2:** `Still in love with you even when I'm mad at you`
    - Screenshot: [test-screenshots/lock_public_change2.png](test-screenshots/lock_public_change2.png)
  - **Line 3:** `I know I can be an asshole sometimes, girl, I admit it`
    - Screenshot: [test-screenshots/lock_public_change3.png](test-screenshots/lock_public_change3.png)
  - Additional Full Lock-Screen Card Capture: [test-screenshots/lock_summer_manila.png](test-screenshots/lock_summer_manila.png)

### Verification Conclusion: ✅ PASS
The One UI lock-screen card updates lyrics in real time while the phone remains locked and screen is awake, with no user interaction or shade pull required.

---

## 3. Lock-Screen Private Visibility & Redaction (Final Verification Pass 2)

### Test Execution Details
1. Selected **`Private (default)`** in AirLyrics lock-screen settings.
2. Verified Notification Manager state via `dumpsys notification --noredact`:
   - Notification ID 2 posted with `vis=PRIVATE` (`vis=0`).
   - Attached `publicVersion` notification with `vis=PUBLIC` (`vis=1`), containing redacted placeholder text:
     - `android.title = "AirLyrics lyrics"`
     - `android.text = "Lyrics are private"`
3. Locked the phone (`input keyevent 26`), waited 7s for keyguard engagement, and woke the screen (`input keyevent 224`).
4. Captured lock screen display: [test-screenshots/lock_screen_private_redacted.png](test-screenshots/lock_screen_private_redacted.png).
5. **Observation:** Real-time lyric text and song title are completely masked/redacted from the lock screen.
6. **Restoration:** Returned to AirLyrics settings and restored the previous privacy choice (**`Public lyrics`**). Verified restoration via screenshot and UI state.

### Verification Conclusion: ✅ PASS
Private visibility properly instructs Android Keyguard to mask sensitive lyrics and track info when locked.

---

## 4. Dedicated Lyric Notification Channel Dump (Final Verification Pass 3)

The raw notification record for Notification ID 2 on channel `lock_screen_lyrics_v1` was captured and saved to:
- **Dump File:** [test-screenshots/notification_id2_dump.txt](test-screenshots/notification_id2_dump.txt)

### Excerpt of Notification ID 2 Structure:
```text
NotificationRecord(0x0eccf65c: pkg=com.andsi.airlyrics.sandbox user=UserHandle{0} id=2 tag=null importance=2 
  key=0|com.andsi.airlyrics.sandbox|2|null|10440: Notification(channel=lock_screen_lyrics_v1 shortcut=null 
  contentView=null vibrate=null sound=null defaults=0 flags=ONGOING_EVENT|ONLY_ALERT_ONCE|SILENT color=0x00000000 
  category=status vis=PRIVATE publicVersion=Notification(channel=lock_screen_lyrics_v1 shortcut=null 
  contentView=null vibrate=null sound=null defaults=0 flags=ONGOING_EVENT|ONLY_ALERT_ONCE color=0x00000000 
  category=status groupKey=silent vis=PUBLIC semFlags=0x0 semPriority=0 semMissedCount=0 semScenarioType=0)))
  uid=10440 userId=0
  opPkg=com.andsi.airlyrics.sandbox
  flags=ONGOING_EVENT|ONLY_ALERT_ONCE|SILENT
  notification=
        vis=0 (PRIVATE)
        extras={
            android.title=String (Mode · Rexx Life Raj, Karri, Marc E. Bassy)
            android.template=String (android.app.Notification$BigTextStyle)
            android.text=String (Baby, come and get in your mode, yeah)
            androidx.core.app.extra.COMPAT_TEMPLATE=String (androidx.core.app.NotificationCompat$BigTextStyle)
            android.bigText=String (Baby, come and get in your mode, yeah)
        }
  publicNotification=
        vis=1 (PUBLIC)
        extras={
            android.title=String (AirLyrics lyrics)
            android.text=String (Lyrics are private)
        }
  effectiveNotificationChannel=NotificationChannel{mId='lock_screen_lyrics_v1', mName=Lock-screen lyrics, mImportance=2, ...}
```

---

## 5. Retest of Gesture & Docking Controls (Summary)

The gesture workflow was fully validated and recorded on the device:
- **Demonstration Video:** [test-screenshots/airlyrics_retest_demo.mp4](test-screenshots/airlyrics_retest_demo.mp4) (1,236,170 bytes)

### Summary of Gesture Findings:
1. **3-Second Stationary Hold:**
   - Threshold: `3000ms`, `touchSlop = 32px` via `StationaryHoldGesture`.
   - Opens `FloatingOverlayEditControls` (`Window{402c0b7}`, 1012x405) and `resizeHandle` (`Window{e78b153}`, 135x135).
   - Screenshot: [test-screenshots/overlay_after_hold.png](test-screenshots/overlay_after_hold.png).
2. **Exit Editing:**
   - Tapping `Done` (`ui_overlay_done` at `(200, 1865)`) commits geometry and immediately removes both auxiliary windows (`dumpsys window windows` verified).
3. **Slow Push-to-Edge Docking:**
   - Smooth swipe push to right edge (`x=1075`) collapses the overlay into compact dock handle `Window{b5d73b8}` (`135x180` at `x=945, y=211`).
   - Screenshot: [test-screenshots/edge_dock_test.png](test-screenshots/edge_dock_test.png).
4. **Dock Handle Expansion:**
   - Tapping the edge handle (`1012, 378`) animates the handle back to full interactive lyrics (`918x230`).

---

## 6. Samsung AOD Hardware Reality

- **Current Status:** ❓ **UNVERIFIED**
- **Hardware Observation on Galaxy A25 5G:**
  ```text
  $ dumpsys power | grep -E "mWakefulness|Display Power"
    mWakefulness=Dozing (3)
    Display Power: state=DOZE_SUSPEND
  ```
  On Galaxy A25 5G, display sleep transitions into `DOZE_SUSPEND`, where third-party overlay windows cannot refresh dynamically without battery drain.
- **Surface Scope:**
  While `DOZE_SUSPEND` is present on this device model, Doze alone does not prove every supported Samsung surface is unavailable across the entire Samsung ecosystem or all One UI versions. Therefore, live AOD remains marked **UNVERIFIED**.
- **In-App Disclaimer:**
  AirLyrics sandbox settings state:
  > *"Always-On Display: live lyrics unverified. Live lyric text on Samsung AOD has not been verified on Galaxy A25 / One UI 8.5. No live AOD mode is enabled."*

---

## 7. Protected Production App Verification

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
- Production application `com.andsi.airlyrics` (`UID 10292`) remains **100% untouched**, with 0 active overlay windows and unchanged package install/update timestamps.

---

## 8. Artifact & Media Index (Working GitHub-Relative Links)

| File | Description | Relative Link |
|---|---|---|
| `airlyrics_lockscreen_public_lyrics.mp4` | 26s video of live lock-screen lyrics updating (Public mode) | [test-screenshots/airlyrics_lockscreen_public_lyrics.mp4](test-screenshots/airlyrics_lockscreen_public_lyrics.mp4) |
| `lock_screen_private_redacted.png` | Screenshot of lock screen showing private lyrics redacted/masked | [test-screenshots/lock_screen_private_redacted.png](test-screenshots/lock_screen_private_redacted.png) |
| `notification_id2_dump.txt` | Raw dumpsys notification output for ID 2, channel `lock_screen_lyrics_v1` | [test-screenshots/notification_id2_dump.txt](test-screenshots/notification_id2_dump.txt) |
| `lock_public_change1.png` | Lock-screen lyric change 1 (*Summer In Manila*) | [test-screenshots/lock_public_change1.png](test-screenshots/lock_public_change1.png) |
| `lock_public_change2.png` | Lock-screen lyric change 2 (*Summer In Manila*) | [test-screenshots/lock_public_change2.png](test-screenshots/lock_public_change2.png) |
| `lock_public_change3.png` | Lock-screen lyric change 3 (*Summer In Manila*) | [test-screenshots/lock_public_change3.png](test-screenshots/lock_public_change3.png) |
| `lock_summer_manila.png` | Full lock screen card view with public lyrics | [test-screenshots/lock_summer_manila.png](test-screenshots/lock_summer_manila.png) |
| `airlyrics_retest_demo.mp4` | Video showing 3s hold, controls, exit, and slow edge docking | [test-screenshots/airlyrics_retest_demo.mp4](test-screenshots/airlyrics_retest_demo.mp4) |
| `overlay_after_hold.png` | Edit mode with Done, Cancel, and resize handle | [test-screenshots/overlay_after_hold.png](test-screenshots/overlay_after_hold.png) |
| `edge_dock_test.png` | Small handle docked at right edge (`135x180`) | [test-screenshots/edge_dock_test.png](test-screenshots/edge_dock_test.png) |
| `cur_screen.png` | In-app settings for lock-screen lyric notification | [test-screenshots/cur_screen.png](test-screenshots/cur_screen.png) |
| `test_notif_shade.png` | Notification shade showing active AirLyrics notifications | [test-screenshots/test_notif_shade.png](test-screenshots/test_notif_shade.png) |
