# AirLyrics Test Report (On-Device Verification)

- **Date:** 17 November 2026
- **Branch:** `ag/device-testing`
- **Target APK:** `airlyrics-test-3s.apk` (21,544,110 bytes)
- **Target Package:** `com.andsi.airlyrics.sandbox`
- **Production Package (Protected):** `com.andsi.airlyrics` (v1.2.10, NOT modified or uninstalled)
- **Tester:** Antigravity AI Assistant

---

## Executive Summary

| Test Area | Status | Findings |
|---|---|---|
| **Environment Check** | ✅ PASS | adb v37.0.1, JDK 21.0.13 Temurin, Git 2.56.0, SDK 36 available. Rust not present (not needed for APK testing). |
| **Package Isolation** | ✅ PASS | Verified package is `com.andsi.airlyrics.sandbox` (`versionName="1.2.10-test"`, `versionCode=21`). Coexists with production `com.andsi.airlyrics`. |
| **3-Second Hold Gesture** | ✅ PASS | Long-press gesture (3200ms) on floating overlay detected at `(540, 464)`; unlocks and enters adjustment mode. |
| **Edge Docking & Handle** | ✅ PASS | Swiping toward display edge docks the overlay. Window position transitions cleanly with handle support. |
| **Handle Expansion** | ✅ PASS | Swiping back inward from edge expands the full lyrics window back into normal overlay mode. |
| **Portrait / Landscape Rotation** | ✅ PASS | Window geometry scales dynamically from `w=918` (portrait) to `w=1809` (landscape) and preserves normalized relative position upon restore. |
| **Lock-Screen Lyrics & Notifications** | ⚠️ OEM CONSTRAINED | Foreground service notification posted with `vis=PRIVATE` (channel `floating_lyrics`). Lock screen hides private contents per One UI security policy. |
| **Samsung AOD Behavior** | ⚠️ VERIFIED LIMITATION | Samsung Galaxy A25 5G enters `DOZE` / `DOZE_SUSPEND`. Third-party `TYPE_APPLICATION_OVERLAY` windows do not render live text on AOD. |
| **Storage & Lyrics Folder** | ✅ PASS | Default isolated storage created at `/storage/emulated/0/Android/data/com.andsi.airlyrics.sandbox/files/lyrics`. Synced and cached `1am in decatur - Karri, Hunxho`. |

---

## 1. Toolchain & Environment Audit

- **Operating System:** Windows 10 (10.0.19045)
- **ADB:** Android Debug Bridge version 1.0.41 (Version 37.0.1-15733141)
  - Location: `C:\Users\user.20\Documents\ChatGPT\lo\.toolchain\android-sdk\platform-tools\adb.exe`
- **JDK:** OpenJDK 21.0.13 (Temurin-21.0.13+9 LTS)
  - Location: `C:\Users\user.20\Documents\ChatGPT\lo\.toolchain\jdk\jdk-21.0.13+9`
- **Git:** Git for Windows 2.56.0.4
  - Location: `C:\Users\user.20\AppData\Local\Programs\Git\cmd\git.exe`
- **Rust:** Not installed (unnecessary since pre-built test APK with compiled JNI was supplied).

---

## 2. Package Name & Coexistence Verification

### Verification Command & Output
```bash
aapt dump badging airlyrics-test-3s.apk | grep package
# Output:
# package: name='com.andsi.airlyrics.sandbox' versionCode='21' versionName='1.2.10-test'
# sdkVersion:'26' targetSdkVersion:'36'
```

### On-Device Package Isolation
```bash
adb shell "pm list packages | grep com.andsi.airlyrics"
# Output:
# package:com.andsi.airlyrics.sandbox
# package:com.andsi.airlyrics
```
- **Rule Strict Compliance:** `com.andsi.airlyrics` was **never** uninstalled or replaced. Both packages coexist independently with isolated private data directories:
  - Production: `/data/user/0/com.andsi.airlyrics`
  - Sandbox: `/data/user/0/com.andsi.airlyrics.sandbox`

---

## 3. Physical Device & Samsung Firmware Specifications

| Property | Value | Notes |
|---|---|---|
| **Model** | `SM-A256E` | Samsung Galaxy A25 5G |
| **Manufacturer** | `samsung` | Samsung Electronics |
| **Android Version** | `16` (SDK 36) | Target SDK 36 |
| **One UI Version** | `80500` | One UI 8.0 / 8.5 |
| **Build ID** | `BP4A.251205.006.A256EXXSEEZH9` | Official user release-keys |
| **Security Patch** | `2026-08-05` | Current patch level |
| **Display Resolution** | `1080 x 2340` (450 DPI) | AMOLED 120Hz display |
| **AOD Setting** | `aod_mode=1` | AOD enabled in system settings |

---

## 4. Test Scenarios & Detailed Results

### Scenario 1: Permissions & Service Launch
1. Granted `SYSTEM_ALERT_WINDOW` via AppOps:
   ```bash
   adb shell appops set com.andsi.airlyrics.sandbox SYSTEM_ALERT_WINDOW allow
   ```
2. Granted `POST_NOTIFICATIONS` runtime permission.
3. Enabled Notification Listener:
   `com.andsi.airlyrics.sandbox/com.andsi.airlyrics.media.MediaNotificationListenerService`
4. Started MainActivity:
   ```bash
   adb shell am start -n com.andsi.airlyrics.sandbox/com.andsi.airlyrics.app.MainActivity
   ```
   *Result:* App launched and initialized successfully. Screenshot: `test-screenshots/sandbox_01_main.png`.

---

### Scenario 2: Floating Lyrics Overlay Activation & Window Hierarchy
- Window properties dumped via `dumpsys window windows`:
  - **Window Token:** `WindowToken{2639a87 type=2038}` (`TYPE_APPLICATION_OVERLAY`)
  - **Window Coordinates (Portrait):** `frame=[81,377][999,551]`
  - **Dimensions:** Width `918`, Height `174` (450 DPI)
  - **Surface Status:** `shown=true`, `isOnScreen=true`, `isVisible=true`
- Screenshot: `test-screenshots/sandbox_03_overlay_visible.png`.

---

### Scenario 3: 3-Second Hold Gesture (Adjustment Mode)
- **Reproduction:**
  ```bash
  adb shell "input swipe 540 464 540 464 3200"
  ```
- **Observed Behavior:**
  - Sustained touch input (>3000ms) within the floating window boundary triggers the hold-to-edit listener (`hold_to_edit` in `FloatingOverlayStore`).
  - Window enters unconstrained drag/resize position adjustment mode.
  - Position changed to `(94, 537)`.
- Screenshot: `test-screenshots/sandbox_04_3s_hold.png`.

---

### Scenario 4: Slow Push-to-Edge Docking & Handle
- **Reproduction:**
  ```bash
  adb shell "input swipe 540 537 1070 537 1800"
  ```
- **Observed Behavior:**
  - When pushed against the right display boundary (`x=1070`), the docking logic (`FloatingOverlayStore$DockSide`) detects proximity to screen margin.
  - The overlay collapses against the edge, minimizing obstruction.
- Screenshot: `test-screenshots/sandbox_05_dock_right.png`.

---

### Scenario 5: Handle Expansion
- **Reproduction:**
  ```bash
  adb shell "input swipe 1060 537 600 537 800"
  ```
- **Observed Behavior:**
  - Dragging inward from the edge handle restores the floating overlay to its active expanded width (`w=918`).
- Screenshot: `test-screenshots/sandbox_06_handle_expanded.png`.

---

### Scenario 6: Portrait / Landscape Rotation Handling
- **Reproduction:**
  ```bash
  # Force Landscape
  adb shell "settings put system accelerometer_rotation 0"
  adb shell "settings put system user_rotation 1"
  
  # Restore Portrait
  adb shell "settings put system user_rotation 0"
  adb shell "settings put system accelerometer_rotation 1"
  ```
- **Observed Metrics:**
  - **Portrait Frame:** `mAttrs={(94,537)(918xwrap)}` (Width: 918px)
  - **Landscape Frame:** `mAttrs={(185,227)(1809xwrap)}` (Width: 1809px)
  - **Restored Portrait Frame:** `mAttrs={(94,538)(918xwrap)}` (Width: 918px, Y restored within 1px margin)
- **Verdict:** Dynamic layout recalculation smoothly adapts to orientation changes while preserving vertical positioning.
- Screenshots: `test-screenshots/sandbox_07_landscape.png`, `test-screenshots/sandbox_08_portrait_restored.png`.

---

### Scenario 7: Lock Screen, Notifications & Samsung AOD Reality
- **Lock Screen State:**
  - System power state enters `mWakefulness=Dozing (3)`
  - Display transitions through `DOZE` -> `DOZE_SUSPEND`
- **Notification Visibility:**
  - Sandbox foreground notification:
    `channel=floating_lyrics`, `importance=2` (LOW), `vis=PRIVATE` (0)
  - Because `lock_screen_allow_private_notifications=0` on this device, the sensitive lyric contents are masked while locked until biometrically authenticated.
- **AOD Live Text Reality:**
  - **Finding:** Third-party Android applications cannot render continuous, dynamic View overlays on Samsung's Always-On Display.
  - When the screen turns off and AOD activates, the Samsung display hardware operates in `DOZE_SUSPEND` where CPU draw calls for `TYPE_APPLICATION_OVERLAY` are frozen to preserve battery.
  - **Recommendation:** Do not attempt fake wakelocks or full-screen overlays to force AOD lyrics. Relying on standard lock-screen notifications and Samsung Now Bar/music indicators is the correct, battery-safe Android architecture.
- Screenshot: `test-screenshots/sandbox_13_lockscreen.png`.

---

### Scenario 8: Local Storage & Lyrics Management
- **Storage Location:**
  ```
  /storage/emulated/0/Android/data/com.andsi.airlyrics.sandbox/files/lyrics
  ```
- **Verified Files:**
  - Automatically retrieved and saved LRCLIB cache for playing media:
    `1am in decatur - Karri, Hunxho · Cache · LRCLIB · Plain (2.6 KB)`
  - Directory is completely isolated from production AirLyrics.
- Screenshots: `test-screenshots/sandbox_10_lyrics_storage.png`, `test-screenshots/sandbox_11_lyrics_page.png`, `test-screenshots/sandbox_12_lyrics_scrolled.png`.

---

## 5. Artifact Index & Screenshots

All captured test screenshots are archived in `test-screenshots/`:
- `sandbox_01_main.png` — Main application dashboard
- `sandbox_02_floating_page.png` — Floating lyric configuration panel
- `sandbox_03_overlay_visible.png` — Active floating lyrics overlay
- `sandbox_04_3s_hold.png` — Overlay adjustment mode triggered via 3s hold
- `sandbox_05_dock_right.png` — Edge docking state
- `sandbox_06_handle_expanded.png` — Expanded handle state
- `sandbox_07_landscape.png` — Landscape orientation adaptation
- `sandbox_08_portrait_restored.png` — Restored portrait orientation
- `sandbox_09_settings.png` — General settings
- `sandbox_10_lyrics_storage.png` — Lyrics storage configuration
- `sandbox_11_lyrics_page.png` — Real-time lyrics parser & source status
- `sandbox_12_lyrics_scrolled.png` — Local lyrics save folder details
- `sandbox_13_lockscreen.png` — Lock screen state and notification behavior

---

## 6. Conclusion & Recommendation

The test build (`airlyrics-test-3s.apk`) succeeds across all interaction criteria:
1. **Safety:** 100% isolated package namespace (`com.andsi.airlyrics.sandbox`). Production app untouched.
2. **Interactivity:** 3-second hold gesture, edge docking, and handle expansion function reliably.
3. **Geometry:** Rotation transition dynamically scales between 918px and 1809px layouts without losing vertical tracking.
4. **Samsung AOD:** Truthful handling confirmed. Continuous live text on AOD is blocked by Samsung firmware power architecture (`DOZE_SUSPEND`), and should remain an honest system constraint rather than using battery-draining workarounds.
